"""Private local resume objects, legacy OSS reads and bounded text extraction."""
import base64
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import shutil
import sys
import tempfile
import unicodedata
import zipfile

MAX_BYTES = 25*1024*1024
NAME_VERSION = 2
TYPES = {'pdf': 'application/pdf', 'docx': 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
         'doc': 'application/msword', 'png': 'image/png', 'jpg': 'image/jpeg', 'jpeg': 'image/jpeg'}
NON_NAMES = {'个人简历', '求职简历', '中文简历', '我的简历', '应聘简历', '附件简历', '基本信息', '联系方式',
             '工作经历', '教育经历', '项目经历', '姓名', '真实姓名', '应聘岗位', '求职意向', '总经理',
             '总经理秘书', '行政管理', '行政助理', '行政前台', '销售代表', '人事专员', '工作经验',
             '工作年限', '资格证书', '求职信息', '个人优势', '未填写', '保密', '简历', '本科', '大专',
             '更新', '最新', '版本', '最终版', '秘书', '运营', '销售', '附件', '厦门', '福建'}


def facts(text):
    """Conservative local extraction. Never substitutes the job's requirements."""
    text = str(text or "")
    lines = [re.sub(r"[ \t\u3000]+", " ", line).strip() for line in text.splitlines()]
    compact = re.sub(r"[ \t\u3000]+", "", text)
    result = {}
    education = re.search(r"(?:最高学历|学历)[：:]?([^\n，,；;]{1,25})", compact)
    degrees = ("博士", "硕士", "本科", "大专", "专科", "高中", "中专")
    scope = education[1] if education else compact
    for degree in degrees:
        if degree in scope:
            result["education"] = degree
            break
    experience = re.search(r"(?:工作年限|工作经验|工作经历)[：:]?(\d{1,2}(?:\.\d+)?年(?:以上|以下)?)", compact)
    if not experience:
        experience = re.search(r"(?<!\d)(\d{1,2}(?:\.\d+)?年)(?:的)?工作(?:经验|经历)", compact)
    if experience:
        result["experience"] = experience[1]
    elif "一年的工作经历" in compact or "一年工作经验" in compact:
        result["experience"] = "1年"
    city = re.search(r"(?:现居住地|现居城市|现居地|现居|所在城市|居住地|当前城市)[：:]([^\n，,；;|]{2,40})", compact)
    if city:
        result["city"] = city[1]
    company = re.search(r"(?:最近公司|目前公司|现任公司|当前公司)[：:]([^\n，,；;|]{2,100})", compact)
    if company:
        result["company"] = company[1]
    else:
        # Prefer a current employment entry; otherwise the latest dated company.
        candidates = []
        for line in lines:
            date = re.search(r"(?:19|20)\d{2}[./年-]\d{1,2}", line)
            if not date:
                continue
            # Strip a full date range before looking for an employer name.
            entry = re.sub(r"^.*?(?:19|20)\d{2}[./年-]\d{1,2}(?:[./月-]\d{1,2}日?)?"
                           r"\s*[-—–~至]\s*(?:(?:19|20)\d{2}[./年-]\d{1,2}(?:[./月-]\d{1,2}日?)?|至今|今)\s*", "", line)
            match = re.search(r"([\u4e00-\u9fffA-Za-z0-9（）()·]{2,70}(?:有限公司|集团|教育机构|研究院))", entry)
            stamp = tuple(int(n) for n in re.findall(r"\d+", date[0]))
            if match:
                employer = re.sub(r"^(?:并于|并在|于|在)", "", match[1])
                if employer == "教育机构":
                    employer = "教育机构（简历未注明名称）"
                candidates.append(("至今" in line, stamp, employer))
            elif "至今" in line and "教育机构" in line:
                candidates.append((True, stamp, "教育机构（简历未注明名称）"))
        if candidates:
            result["company"] = max(candidates)[2]
    return result


def clean_name(value):
    value = re.sub(r'\s+', '', value).strip(' :：|丨')
    return value if re.fullmatch(r'[\u4e00-\u9fff]{2,4}', value) and value not in NON_NAMES else ''


def original_filename(value):
    """Display metadata only; never use an attachment name as a filesystem path."""
    value = unicodedata.normalize('NFKC', str(value or ''))
    value = re.sub(r'[\x00-\x1f\x7f]', '', value).replace('\\', '/').rsplit('/', 1)[-1].strip()
    return value[:240]


def filename_name(value):
    value = original_filename(value)
    if not re.search(r'\.(pdf|docx?|png|jpe?g)$', value, re.I):
        return ''
    stem = re.sub(r'\.(pdf|docx?|png|jpe?g)$', '', value, flags=re.I)
    # Remove common resume labels, copy counters and dates, not arbitrary job words.
    stem = re.sub(r'的?(?:个人|求职|中文|应聘|附件|我的)?简历|RESUME|CV', ' ', stem, flags=re.I)
    stem = re.sub(r'[\d]+(?:年|月|日|岁|届|版)?', ' ', stem)
    stem = re.sub(r'(?:最新版|最终版|更新版|更新|副本|新版)', ' ', stem)
    parts = re.split(r'[_\-—–+|丨·,，;；()（）\[\]【】\s]+', stem)
    surnames = set('赵钱孙李周吴郑王冯陈褚卫蒋沈韩杨朱秦尤许何吕施张孔曹严华金魏陶姜戚谢邹喻柏水窦章'
                   '云苏潘葛奚范彭郎鲁韦昌马苗凤花方俞任袁柳鲍史唐费廉岑薛雷贺倪汤滕殷罗毕郝邬安常'
                   '乐于傅皮卞齐康伍余元顾孟平黄和穆萧尹姚邵汪祁毛禹狄米贝明臧计伏成戴宋茅庞熊纪'
                   '舒屈项祝董梁杜阮蓝闵席季麻强贾路娄危江童颜郭梅盛林刁钟徐邱骆高夏蔡田樊胡凌霍'
                   '虞万支柯管卢莫房缪解应宗丁宣邓郁单杭洪包左石崔吉龚程嵇邢裴陆荣翁荀羊惠甄曲家'
                   '封储靳段富巫焦巴弓牧山谷车侯宓蓬全班仰秋仲伊宫宁仇栾甘厉祖武符刘景詹束龙叶幸'
                   '司韶黎乔苍双闻莘党翟谭贡劳逄姬申扶堵冉宰郦雍郤璩桑桂濮牛寿通边扈燕冀郏浦尚'
                   '农温别庄晏柴瞿阎连习艾鱼容向古易慎戈廖庾终暨居衡步都耿满弘匡国文寇广禄阙东欧'
                   '沃利蔚越夔隆师巩厍聂晁勾敖融冷訾辛阚那简饶曾沙鞠须丰巢关蒯相查后荆红游竺权盖'
                   '益桓公仉督晋楚闫岳帅缑亢况郜有商牟佘佴伯赏墨哈谯笪年爱阳佟言福南火铁迟漆赫')
    names = {clean_name(part) for part in parts if clean_name(part) and part[0] in surnames
             and not re.search(r'经理|助理|秘书|工程|设计|运营|专员|管理|销售|客服|市场|本科|大专|硕士', part)}
    return next(iter(names)) if len(names) == 1 else ''


def identity(filename, text):
    name = filename_name(filename)
    if name:
        return name, 'resume_filename'
    name = person_name(text)
    return name, 'resume_text' if name else ''


def ocr_name(raw, extension):
    """Last resort on the first page; low-confidence names remain unresolved."""
    if extension not in {'pdf', 'png', 'jpg', 'jpeg'}:
        return ''
    try:
        with tempfile.TemporaryDirectory(prefix='heyi-resume-ocr-') as folder:
            source = Path(folder) / ('source.' + extension)
            source.write_bytes(raw)
            image = source
            if extension == 'pdf':
                prefix = Path(folder) / 'page'
                subprocess.run(['pdftoppm', '-f', '1', '-l', '1', '-singlefile', '-scale-to', '2400',
                                '-png', str(source), str(prefix)], stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, timeout=8, check=True)
                image = prefix.with_suffix('.png')
            result = subprocess.run(['tesseract', str(image), 'stdout', '-l', 'chi_sim', '--psm', '11', 'tsv'],
                                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=12, check=True,
                                    env={**os.environ, 'OMP_THREAD_LIMIT': '1'})
            return ocr_identity(result.stdout.decode('utf-8'))
    except (OSError, ValueError, subprocess.SubprocessError):
        return ''


def ocr_identity(tsv):
    lines = {}
    for row in csv.DictReader(io.StringIO(tsv), delimiter='\t'):
        text = (row.get('text') or '').strip()
        if not text or row.get('level') != '5':
            continue
        key = tuple(row.get(k) for k in ('page_num', 'block_num', 'par_num', 'line_num'))
        lines.setdefault(key, []).append((text, float(row.get('conf', '-1'))))
    header = [' '.join(t for t, _ in words) for words in lines.values()]
    name = person_name('\n'.join(header))
    confidence = [c for words in lines.values() if clean_name(''.join(t for t, _ in words)) == name
                  for _, c in words]
    return name if name and confidence and min(confidence) >= 88 else ''


def person_name(text):
    """Conservative header extraction; no AI call and no guessed filename name."""
    text = unicodedata.normalize('NFKC', str(text or '')[:60000])
    text = re.sub(r'[A-Za-z0-9_-]{24,}~*', '', text)[:6000]
    lines = [re.sub(r'[ \t]+', ' ', line).strip() for line in text.splitlines() if line.strip()][:60]
    clean = clean_name
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
        if not value and index+1 < len(lines) and re.match(r'^(?:性别\s*[:：]?\s*)?(?:男|女)(?=\s|[|丨]|$)', lines[index+1]):
            value = clean(line)
        if value: candidates.append(value)
        # Designed resumes often place an unlabelled name next to RESUME or
        # contact details. Require that context and a unique short header name.
        if not value and index < 18 and clean(line):
            adjacent = lines[max(0, index-1):index] + lines[index+1:index+3]
            if any(re.fullmatch(r'(?:PERSONAL\s*)?R\s*E\s*S\s*U\s*M\s*E|个人简历|求职简历', s, re.I)
                   or re.match(r'^(?:电话|手机|邮箱|联系方式)\s*[:：]', s) for s in adjacent):
                candidates.append(clean(line))
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
    validate(raw, extension)
    key = prefix(owner) + record + '.' + extension
    target = local_path(owner, key)
    target.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if target.exists():
        if hashlib.sha256(target.read_bytes()).digest() != hashlib.sha256(raw).digest():
            raise ValueError('同一简历存储标识的内容不一致')
    else:
        if shutil.disk_usage(target.parent).free < len(raw) + 2*1024**3:
            raise ValueError('简历存储空间不足，请先释放空间')
        fd, temporary = tempfile.mkstemp(prefix='.resume-', dir=target.parent)
        try:
            with os.fdopen(fd, 'wb') as output:
                output.write(raw)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, target)
        finally:
            Path(temporary).unlink(missing_ok=True)
    # New HR documents must remain available after the user stops OSS.
    # Existing remote records retain their original keys and download fallback.
    if os.environ.get('APP_HR_OSS_WRITE_ENABLED') == '1':
        bucket().put_object(key, raw, headers={'Content-Type': TYPES[extension],
            'x-oss-object-acl': 'private', 'Content-Disposition': 'attachment'})
    return key


def local_path(owner, key):
    if not key.startswith(prefix(owner)) or not re.fullmatch(re.escape(prefix(owner))+r'[a-f0-9-]{36}\.(pdf|docx|doc|png|jpe?g)', key):
        raise ValueError('简历存储记录无效')
    root = Path(os.environ.get('APP_HR_LOCAL_RESUME_DIR',
                '/opt/heyi/boss-agent/runtime/resume-files')).resolve()
    target = root / key
    if not target.resolve().is_relative_to(root):
        raise ValueError('简历存储路径无效')
    return target


def download(owner, key):
    local = local_path(owner, key)
    if local.is_file():
        if local.stat().st_size > MAX_BYTES:
            raise ValueError('简历文件过大')
        return local.read_bytes()
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
