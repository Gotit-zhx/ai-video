"""Private OSS resume objects and bounded text extraction. Never accepts remote URLs."""
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import unicodedata
import zipfile

MAX_BYTES = 25*1024*1024
TYPES = {'pdf': 'application/pdf', 'docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
         'doc': 'application/msword', 'png': 'image/png', 'jpg': 'image/jpeg', 'jpeg': 'image/jpeg'}


def person_name(text):
    """Conservative header extraction; no AI call and no guessed filename name."""
    text = unicodedata.normalize('NFKC', str(text or '')[:6000])
    lines = [re.sub(r'[ \t]+', ' ', line).strip() for line in text.splitlines() if line.strip()][:60]
    invalid = {'个人简历', '求职简历', '基本信息', '联系方式', '工作经历', '教育经历', '项目经历',
               '姓名', '真实姓名', '应聘岗位', '求职意向', '总经理', '总经理秘书', '未填写', '保密'}
    def clean(value):
        value = re.sub(r'\s+', '', value).strip(' :：|丨')
        return value if re.fullmatch(r'[\u4e00-\u9fff]{2,4}', value) and value not in invalid else ''
    explicit = []
    for index, line in enumerate(lines):
        match = re.match(r'^(?:姓\s*名|真实姓名)\s*[:：]?\s*([\u4e00-\u9fff ]{2,8}?)(?=\s{1,2}(?:性别|年龄|电话)|[|丨,，]|$)', line)
        if match and clean(match[1]): explicit.append(clean(match[1]))
        if re.fullmatch(r'姓\s*名\s*[:：]?', line) and index+1 < len(lines):
            value = clean(lines[index+1])
            if value: explicit.append(value)
    if explicit:
        return explicit[0] if len(set(explicit)) == 1 else ''
    # BOSS PDF templates may extract columns before the heading. A name next to
    # the header's gender/age row is stronger evidence than the first text line.
    candidates = []
    for index, line in enumerate(lines):
        if re.fullmatch(r'(工作|项目|教育|实习)(经历|背景)|专业技能|个人优势', line): break
        match = re.match(r'^([\u4e00-\u9fff ]{2,8}?)\s+(?:男|女)(?=\s|[|丨]|$)', line)
        value = clean(match[1]) if match else ''
        if not value and index+1 < len(lines) and re.match(r'^(?:男|女)(?=\s|[|丨]|$)', lines[index+1]):
            value = clean(line)
        if value: candidates.append(value)
    return candidates[0] if len(set(candidates)) == 1 else ''


def validate(raw, extension):
    if extension not in TYPES or not 1 <= len(raw) <= MAX_BYTES: raise ValueError('简历仅支持 PDF、Word 和图片，单个文件不超过 25MB')
    valid = (extension == 'pdf' and raw.startswith(b'%PDF-') or extension == 'doc' and raw.startswith(bytes.fromhex('d0cf11e0a1b11ae1'))
             or extension == 'png' and raw.startswith(b'\x89PNG\r\n\x1a\n') or extension in {'jpg', 'jpeg'} and raw.startswith(b'\xff\xd8\xff'))
    if extension == 'docx':
        try:
            with zipfile.ZipFile(io.BytesIO(raw)) as z:
                items = z.infolist()
                valid = len(items) <= 2048 and sum(x.file_size for x in items) <= 32*1024*1024 and 'word/document.xml' in z.namelist()
                valid = valid and not any(x.flag_bits & 1 or x.file_size > 8*1024*1024 or '..' in x.filename.split('/') or 'vbaProject' in x.filename for x in items)
        except (OSError, zipfile.BadZipFile): valid = False
    if not valid: raise ValueError('简历格式与文件内容不一致，未保存')


def prefix(owner):
    return 'heyi/hr-resumes/' + hashlib.sha256(owner.encode()).hexdigest()[:24] + '/'


def bucket():
    import oss2
    credentials = Path(os.environ['APP_HR_OSS_CREDENTIALS_FILE'])
    values = {}
    for line in credentials.read_text().splitlines():
        if '=' in line and not line.lstrip().startswith('#'):
            key, value = line.split('=', 1); values[key.strip()] = value.strip()
    auth = oss2.Auth(values['accessKeyId'], values['accessKeySecret'])
    return oss2.Bucket(auth, os.environ['APP_HR_OSS_ENDPOINT'], os.environ['APP_HR_OSS_BUCKET'], connect_timeout=15)


def store(owner, record, raw, extension):
    key = prefix(owner) + record + '.' + extension
    client = bucket()
    client.put_object(key, raw, headers={'Content-Type': TYPES[extension], 'x-oss-object-acl': 'private', 'Content-Disposition': 'attachment'})
    return key


def download(owner, key):
    if not key.startswith(prefix(owner)) or not re.fullmatch(re.escape(prefix(owner))+r'[a-f0-9-]{36}\.(pdf|docx|doc|png|jpe?g)', key):
        raise ValueError('简历存储记录无效')
    response = bucket().get_object(key)
    try:
        raw = response.read(MAX_BYTES+1)
    finally: response.close()
    if len(raw) > MAX_BYTES: raise ValueError('简历文件过大')
    return raw


def extract(raw, extension):
    if extension not in {'pdf', 'docx'}: return '', '此文件未提取文字；AI 分析前可由 HR 补充正文'
    try:
        result = subprocess.run([sys.executable, str(Path(__file__).resolve()), '--extract', extension], input=raw,
                                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=20, check=True,
                                env={'PATH': os.environ.get('PATH', '/usr/bin:/bin'), 'PYTHONIOENCODING': 'utf-8'})
        text = result.stdout.decode()[:60000].strip()
        return (text, '已提取正文；请结合原件核对，扫描图片和复杂排版可能遗漏') if text else ('', '未提取到文字，可能是扫描件；可补充简历正文后分析')
    except Exception: return '', '文字提取未完成；原文件已保存，可查看原件并补充正文'


def extract_worker(extension):
    # A malformed PDF/XML must not monopolize the HTTP service.
    import resource
    resource.setrlimit(resource.RLIMIT_AS, (768*1024*1024, 768*1024*1024))
    resource.setrlimit(resource.RLIMIT_CPU, (15, 15))
    raw = sys.stdin.buffer.read(MAX_BYTES+1)
    validate(raw, extension)
    if extension == 'pdf':
        from pypdf import PdfReader
        reader = PdfReader(io.BytesIO(raw), strict=False)
        if reader.is_encrypted: return
        parts = []; count = 0
        for page in reader.pages[:50]:
            text = page.extract_text() or ''; parts.append(text); count += len(text)
            if count >= 60000: break
        print('\n'.join(parts)[:60000])
    else:
        from defusedxml import ElementTree
        with zipfile.ZipFile(io.BytesIO(raw)) as z: text = z.read('word/document.xml')
        root = ElementTree.fromstring(text)
        print('\n'.join(''.join(p.itertext()) for p in root.iter('{http://schemas.openxmlformats.org/wordprocessingml/2006/main}p'))[:60000])

if __name__ == '__main__' and len(sys.argv) == 3 and sys.argv[1] == '--extract': extract_worker(sys.argv[2])
