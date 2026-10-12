"""Job-scoped reference resumes, explicit DRIVER analysis and human-approved greetings.

This module never connects to BOSS. Writes are leased to the account's desktop.
"""
import base64
import datetime
import hashlib
import html
import json
import math
import re
import threading
import time
import urllib.request
import urllib.error
import uuid

import talent_pool as pool
from passive_pool import account, dump, now, uid

AI_BASE = 'http://127.0.0.1:8080/api/v1/recruitment-ai/'
MAX_UPLOAD = 10 * 1024 * 1024
MATCH_THRESHOLD = 80
# Opaque BOSS access credentials use the same bound as connector.security_identifier.
# They routinely exceed 600 characters and must remain intact outside model input.
SECURITY_ID = re.compile(r"^[A-Za-z0-9_~][A-Za-z0-9_~\-=]{0,4095}$")


def schema(db):
    # Do not use executescript: preserve the caller's transaction.
    for sql in (
        '''CREATE TABLE IF NOT EXISTS hr_sourcing_jobs(
          id TEXT PRIMARY KEY,owner TEXT NOT NULL,account TEXT NOT NULL,job_id TEXT NOT NULL,
          body TEXT NOT NULL,version INTEGER NOT NULL DEFAULT 1,
          profile_state TEXT NOT NULL DEFAULT 'idle',profile TEXT NOT NULL DEFAULT '{}',
          profile_token TEXT NOT NULL DEFAULT '',profile_at REAL NOT NULL DEFAULT 0,
          profile_error TEXT NOT NULL DEFAULT '',updated_at TEXT NOT NULL,UNIQUE(owner,account,job_id))''',
        '''CREATE TABLE IF NOT EXISTS hr_sourcing_refs(
          id TEXT PRIMARY KEY,owner TEXT NOT NULL,job TEXT NOT NULL,digest TEXT NOT NULL,
          filename TEXT NOT NULL,body TEXT NOT NULL,file_key TEXT NOT NULL,created_at TEXT NOT NULL,
          UNIQUE(owner,job,digest))''',
        '''CREATE TABLE IF NOT EXISTS hr_sourcing_runs(
          id TEXT PRIMARY KEY,owner TEXT NOT NULL,job TEXT NOT NULL,version INTEGER NOT NULL,
          wanted INTEGER NOT NULL,state TEXT NOT NULL,body TEXT NOT NULL,result TEXT NOT NULL DEFAULT '{}',
          error TEXT NOT NULL DEFAULT '',started REAL NOT NULL,updated_at TEXT NOT NULL)''',
        '''CREATE TABLE IF NOT EXISTS hr_sourcing_candidates(
          id TEXT PRIMARY KEY,owner TEXT NOT NULL,job TEXT NOT NULL,geek TEXT NOT NULL,
          body TEXT NOT NULL,read_at REAL NOT NULL,UNIQUE(owner,job,geek))''',
        '''CREATE TABLE IF NOT EXISTS hr_sourcing_seen(
          run TEXT NOT NULL,candidate TEXT NOT NULL,PRIMARY KEY(run,candidate))''',
        '''CREATE TABLE IF NOT EXISTS hr_sourcing_contacts(
          id TEXT PRIMARY KEY,owner TEXT NOT NULL,account TEXT NOT NULL,job TEXT NOT NULL,
          candidate TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL,
          friend_id TEXT NOT NULL DEFAULT '',created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
          UNIQUE(owner,account,job,candidate))''',
    ):
        db.execute(sql)
    if 'criteria' not in {row[1] for row in db.execute('PRAGMA table_info(hr_sourcing_jobs)')}:
        db.execute("ALTER TABLE hr_sourcing_jobs ADD COLUMN criteria TEXT NOT NULL DEFAULT '{}'")
    if 'demand_source' not in {row[1] for row in db.execute('PRAGMA table_info(hr_sourcing_jobs)')}:
        db.execute("ALTER TABLE hr_sourcing_jobs ADD COLUMN demand_source TEXT NOT NULL DEFAULT '{}'")


PERSONAL_CRITERION = re.compile(r'性别|男生|女生|男性|女性|年龄|身高|婚育|民族|宗教|残障|外貌|颜值|长相|气质|形象好')
CRITERIA_POLICY = 2
DOMAIN_GROUPS = [
    ("图像处理 / 计算机视觉", ("图像", "计算机视觉", "机器视觉", "目标检测", "图像分割", "视觉算法", "OCR", "OpenCV", "computer vision")),
    ("后端开发", ("后端", "服务端", "Java", "Spring", "接口开发", "API开发")),
    ("前端开发", ("前端", "React", "Vue", "网页开发")),
    ("人力资源", ("人力资源", "招聘", "人事", "HR")),
    ("行政 / 接待", ("秘书", "行政", "接待", "办公事务")),
    ("大模型 / NLP", ("大模型", "LLM", "NLP", "自然语言", "RAG")),
]


def direction_terms(text):
    for label, terms in DOMAIN_GROUPS:
        if any(re.search((r"(?<![A-Za-z])" + re.escape(term) + r"(?![A-Za-z])")
                         if re.fullmatch(r"[A-Za-z ]+", term) else re.escape(term), text, re.I) for term in terms):
            return label, list(terms)
    return "", []


def suggested_criteria(job):
    data, profile = json.loads(job["body"]), json.loads(job["profile"])
    name = data.get("jobName", "")
    jd = data.get("description", "")
    direction, _ = direction_terms(name)
    if not direction:
        direction, _ = direction_terms(jd)
    core = f"有{direction or name}相关项目或实际工作经历，需有具体任务依据"
    # Only suggest tools explicitly present in the formal JD.
    tools = [term for term in ("C++", "Python", "Java", "Spring", "OpenCV", "PyTorch", "TensorFlow",
                              "React", "Vue", "SQL", "MySQL", "Excel", "RAG")
             if re.search(r"(?<![A-Za-z])" + re.escape(term) + r"(?![A-Za-z])", jd, re.I)]
    skills = [value for value in profile.get("skills", []) if isinstance(value, str) and not PERSONAL_CRITERION.search(value)]
    skill = ("实际使用过 " + " / ".join(tools[:5]) + " 中至少一项完成工作")
    if not tools:
        skill = next(iter(profile.get("requirements", []) or skills), "具备岗位 JD 写明的核心工作技能")
    experience = next(iter(profile.get("experience", [])), f"有完成{direction or name}相关工作任务或项目交付的实际经历")
    values = [(core, 50, True, "direction"), (skill, 30, False, "skills"), (experience, 20, False, "experience")]
    return [{"id": f"k{i+1}", "text": text[:250], "weight": weight, "required": required, "kind": kind,
             "terms": direction_terms(text)[1] if kind == "direction" else []}
            for i, (text, weight, required, kind) in enumerate(values)]


def matching_criteria(job):
    saved = json.loads(job['criteria'])
    suggestions = suggested_criteria(job)
    if saved.get('conditions'):
        return {**saved, "confirmed": saved.get("confirmed") is True and saved.get("policyVersion") == CRITERIA_POLICY,
                "needsReview": saved.get("policyVersion") != CRITERIA_POLICY, "suggestedConditions": suggestions}
    return {'confirmed': False, 'conditions': suggestions, "suggestedConditions": suggestions,
            'scoreBasis': 'known_conditions', 'threshold': MATCH_THRESHOLD, "policyVersion": CRITERIA_POLICY}


DEGREES = {'中专': 1, '高中': 1, '大专': 2, '专科': 2, '本科': 3, '学士': 3,
           '硕士': 4, '研究生': 4, '博士': 5}
DEGREE_LABELS = {1: '高中 / 中专', 2: '大专', 3: '本科', 4: '硕士', 5: '博士'}


def degree_level(value):
    if not isinstance(value, str) or re.search(r'在读|未毕业|肄业|不限|未知', value):
        return None
    levels = {level for name, level in DEGREES.items() if name in value}
    # "博士研究生" is one degree; otherwise ambiguous alternatives stay unknown.
    if '博士' in value:
        levels.discard(4)
    return next(iter(levels)) if len(levels) == 1 else None


def year_range(value):
    if not isinstance(value, str) or re.search(r'不限|未知', value):
        return None
    if re.fullmatch(r'(?:应届(?:生|毕业生)?|在校生|无(?:工作)?经验)', value.strip()):
        return (0, 0)
    match = re.search(r'(?<![\d.])(\d+(?:\.\d+)?)\s*(?:[-–—~～至到]\s*(\d+(?:\.\d+)?))?\s*年', value)
    if not match:
        return None
    lower = float(match[1])
    upper = float(match[2]) if match[2] else float('inf') if re.search(r'以上|至少|不少于', value) else lower
    return (lower, upper) if 0 <= lower <= upper and lower <= 60 else None


def basic_requirements(data):
    """Only explicit job minimums; no age, school prestige or inferred tenure."""
    jd = data.get('description', '')
    education = data.get('education', '')
    experience = data.get('experience', '')
    if not education:
        found = re.search(r'(博士|硕士|研究生|本科|学士|大专|专科|高中|中专)\s*(?:及以上|以上)?\s*(?:学历|学位)', jd)
        education = found[0] if found else ''
    if not experience:
        found = re.search(r'\d+(?:\.\d+)?\s*(?:[-–—~～至到]\s*\d+(?:\.\d+)?)?\s*年\s*(?:以上)?\s*(?:的)?\s*(?:相关)?\s*(?:工作|开发|行业|项目)?经验', jd)
        experience = found[0] if found else ''
    result = []
    level, years = degree_level(education), year_range(experience)
    if level is not None:
        result.append({'id': 'education', 'label': '学历', 'minimum': level,
                       'text': '最低学历：'+DEGREE_LABELS[level]})
    if years is not None and years[0] > 0:
        result.append({'id': 'years', 'label': '工作年限', 'minimum': years[0],
                       'text': f'最低工作年限：{years[0]:g} 年'})
    return result


def basic_checks(value, requirements):
    facts = value.get('basicFacts', {})
    checks = []
    for requirement in requirements:
        status, evidence = 'unknown', '卡片未提供可核实的'+requirement['label']
        if requirement['id'] == 'education' and facts.get('education') is not None:
            level = facts['education']
            status = 'matched' if level >= requirement['minimum'] else 'unmatched'
            evidence = '卡片学历：'+DEGREE_LABELS[level]
        elif requirement['id'] == 'years' and facts.get('years') is not None:
            lower, upper = facts['years']
            # A range straddling the minimum is not a confirmed match or failure.
            status = 'matched' if lower >= requirement['minimum'] else 'unmatched' if upper is not None and upper < requirement['minimum'] else 'unknown'
            evidence = '卡片工作年限：'+facts['yearsText']
        checks.append({**requirement, 'status': status, 'evidence': evidence})
    return checks


def current_basic_checks(checks, requirements):
    return all(any(check.get('id') == requirement['id'] and check.get('minimum') == requirement['minimum']
                   and check.get('status') == 'matched' for check in checks) for requirement in requirements)


def criteria(user, body):
    owner, actor = pool.context(user)
    rows = body.get('conditions')
    if not isinstance(rows, list) or not 1 <= len(rows) <= 5:
        raise ValueError('请设置 1–5 条岗位匹配条件')
    clean = []
    for index, row in enumerate(rows):
        if not isinstance(row, dict): raise ValueError('匹配条件格式不正确')
        text = pool.text(row.get('text'), 250)
        weight = row.get('weight')
        if not text or type(weight) is not int or not 1 <= weight <= 100:
            raise ValueError('请填写条件与 1–100 的权重')
        if PERSONAL_CRITERION.search(text):
            raise ValueError('匹配条件应填写工作能力或经验，不能使用性别、年龄、外貌等个人属性')
        kind = row.get("kind", "custom")
        if kind not in {"direction", "skills", "experience", "custom"}:
            raise ValueError("匹配条件类型不正确")
        clean.append({'id': f'k{index+1}', 'text': text, 'weight': weight, 'required': row.get('required') is True,
                      "kind": kind, "terms": direction_terms(text)[1] if kind == "direction" else []})
    with pool.database() as db:
        schema(db); expire(db); job = get_job(db, owner, body)
        if job['version'] != body.get('version'): raise pool.Conflict('岗位条件已变化，请刷新核对')
        if job['profile_state'] != 'complete': raise ValueError('请先完成岗位画像')
        if db.execute("SELECT 1 FROM hr_sourcing_runs WHERE owner=? AND job=? AND state='running'",
                      (owner, job['id'])).fetchone(): raise pool.Conflict('请先暂停寻找并等待当前分析结束，再修改条件')
        value = {'confirmed': True, 'conditions': clean, 'scoreBasis': 'known_conditions', 'threshold': MATCH_THRESHOLD,
                 "policyVersion": CRITERIA_POLICY}
        if json.loads(job['criteria']) != value:
            db.execute('UPDATE hr_sourcing_jobs SET criteria=?,version=version+1,updated_at=? WHERE id=?',
                       (dump(value), now(), job['id']))
            pool.event(db, owner, actor, job['id'], 'sourcing.criteria', None, value)
        return view(db, get_job(db, owner, body))


def expire(db):
    cutoff = time.time()-360
    db.execute("UPDATE hr_sourcing_jobs SET profile_state='failed',profile_error='生成未完成，请手动重试；没有自动重复调用' WHERE profile_state='running' AND profile_at<?", (cutoff,))
    db.execute("UPDATE hr_sourcing_runs SET state='failed',error='寻源分析未完成，请手动重新获取' WHERE state='running' AND started<?", (cutoff,))
    db.execute("UPDATE hr_sourcing_contacts SET status='uncertain',updated_at=? WHERE status='sending' AND updated_at<?",
               (now(), datetime.datetime.fromtimestamp(time.time()-600, datetime.timezone.utc).isoformat()))
    db.execute("UPDATE hr_sourcing_contacts SET status='expired',updated_at=? WHERE status='queued' AND json_extract(body,'$.readAt')<?",
               (now(), time.time()-1800))


def get_job(db, owner, body):
    row = db.execute('SELECT * FROM hr_sourcing_jobs WHERE id=? AND owner=?', (uid(body.get('id')), owner)).fetchone()
    if not row or row['account'] != account(body.get('accountKey')):
        raise ValueError('职位与当前招聘账号不一致，请从职位管理重新进入')
    return refresh_demand_source(db, row)


def refresh_demand_source(db, job):
    source = json.loads(job['demand_source'])
    if not source.get('id'):
        return job
    import recruitment_demands as demands
    demands.schema(db)
    demand = db.execute('SELECT * FROM recruitment_demands WHERE id=? AND owner=?',
                        (source['id'], job['owner'])).fetchone()
    current = (demand and demand['version'] == source.get('version')
               and demand['ai_status'] == 'complete' and demand['ai_version'] == demand['version']
               and demand['ai_job'] == source.get('analysisId'))
    if not current and job['profile_state'] == 'complete':
        db.execute("UPDATE hr_sourcing_jobs SET profile_state='stale',profile_error='关联招聘需求已变化，请重新使用最新岗位分析',criteria='{}',version=version+1 WHERE id=?",
                   (job['id'],))
        return db.execute('SELECT * FROM hr_sourcing_jobs WHERE id=?', (job['id'],)).fetchone()
    return job


def view(db, row, run_id=None):
    requirements = basic_requirements(json.loads(row['body']))
    refs = [dict(r) for r in db.execute('SELECT id,filename,body,created_at FROM hr_sourcing_refs WHERE owner=? AND job=? ORDER BY created_at,id', (row['owner'], row['id']))]
    for item in refs:
        data = json.loads(item.pop('body'))
        item.update(characters=len(data['text']), note=data.get('note', ''))
    runs = list(db.execute('SELECT * FROM hr_sourcing_runs WHERE owner=? AND job=?'
                          + (' AND id=?' if run_id else '') + ' ORDER BY started DESC,id LIMIT 1',
                          (row['owner'], row['id'], uid(run_id)) if run_id else (row['owner'], row['id'])))
    run = None
    if runs:
        r = runs[0]
        run = {'id': r['id'], 'state': r['state'], 'error': r['error'], 'startedAt': r['started'],
               'wanted': r['wanted'], 'version': r['version'], **json.loads(r['result'])}
        run.pop('matched', None)
        for item in run.get('candidates', []):
            item['basicRequirementsPending'] = not current_basic_checks(item.get('basicChecks', []), requirements)
            if item['basicRequirementsPending']:
                item['canGreet'] = False
            contact = db.execute('SELECT id,status,friend_id,body FROM hr_sourcing_contacts WHERE owner=? AND job=? AND candidate=?',
                                 (row['owner'], row['id'], item['id'])).fetchone()
            item['contact'] = dict(contact) if contact else None
            if contact:
                item['contact']['waitInfo'] = json.loads(item['contact'].pop('body')).get('waitInfo', {})
    return {**json.loads(row['body']), 'id': row['id'], 'jobId': row['job_id'], 'accountKey': row['account'],
            'version': row['version'], 'profileState': row['profile_state'], 'profile': json.loads(row['profile']),
            'profileError': row['profile_error'], 'profileStartedAt': row['profile_at'],
            'criteria': matching_criteria(row), 'references': refs, 'run': run,
            'basicRequirements': requirements,
            'sourceDemand': json.loads(row['demand_source'])}


def listing(user, query):
    owner, _ = pool.context(user)
    with pool.database() as db:
        schema(db); expire(db)
        if query.get('id'):
            return view(db, get_job(db, owner, query), query.get('runId'))
        key = account(query.get('accountKey'))
        row = db.execute('SELECT * FROM hr_sourcing_jobs WHERE owner=? AND account=? AND job_id=?',
                         (owner, key, pool.text(query.get('jobId'), 180))).fetchone()
        return view(db, refresh_demand_source(db, row)) if row else None


def demand(user, body):
    """Use an existing demand analysis for this exact job. No model or BOSS call."""
    import recruitment_demands as demands
    owner, actor = pool.context(user)
    with pool.database() as db:
        schema(db); expire(db); demands.schema(db)
        job = get_job(db, owner, body)
        if job['version'] != body.get('version'):
            raise pool.Conflict('寻源条件已变化，请刷新后选择招聘需求')
        row = db.execute('SELECT * FROM recruitment_demands WHERE id=? AND owner=?',
                         (uid(body.get('demandId')), owner)).fetchone()
        if not row:
            raise ValueError('招聘需求不存在')
        snapshot = demands.view(row, db)
        if snapshot.get('jobId') != job['job_id'] or snapshot.get('jobAccountKey') != job['account']:
            raise ValueError('招聘需求与当前职位或账号不一致，不能套用')
        result = snapshot.get('aiResult', {})
        if (row['ai_status'] != 'complete' or row['ai_version'] != row['version']
                or result.get('sourceVersion') != row['version']
                or result.get('sourceJobId') != job['job_id']
                or result.get('sourceJobAccountKey') != job['account']):
            raise ValueError('请先在招聘需求池生成该职位的最新 AI 岗位分析')
        canonical = lambda text: re.sub(r'\s+', ' ', html.unescape(re.sub(r'<[^>]*>', ' ', text))).strip()
        current = json.loads(job['body'])
        if canonical(snapshot['jobDescription']) != canonical(current['description']):
            raise ValueError('关联岗位 JD 已变化，请在招聘需求池重新生成后再使用')
        if job['profile_state'] == 'running' or db.execute(
                "SELECT 1 FROM hr_sourcing_runs WHERE owner=? AND job=? AND state='running'",
                (owner, job['id'])).fetchone():
            raise pool.Conflict('请等待当前分析结束后再更换招聘需求')
        portrait = result['portrait']
        skills = [str(item.get('name', '')) for item in result.get('competencies', [])]
        skills += [str(item) for item in portrait.get('mustHave', [])]
        skills = list(dict.fromkeys(value for value in skills if value and not PERSONAL_CRITERION.search(value)))[:10]
        profile = {'summary': portrait.get('summary', ''), 'suggestedJd': snapshot['jobDescription'],
                   'skills': skills, 'requirements': portrait.get('mustHave', []), 'experience': portrait.get('experience', []),
                   'evidence': [{'requirement': item.get('name', ''), 'evidence': item.get('evidence', '')}
                                for item in result.get('competencies', [])],
                   'questions': portrait.get('evaluationFocus', []),
                   'limitations': ['招聘需求池岗位分析，仅供 HR 确认匹配条件。']}
        source = {'id': row['id'], 'version': row['version'], 'position': snapshot['position'],
                  'referenceCount': len(snapshot['references']), 'modelId': result.get('modelId', ''),
                  'analysisId': row['ai_job']}
        if json.loads(job['demand_source']) != source or job['profile_state'] != 'complete':
            db.execute("UPDATE hr_sourcing_jobs SET profile=?,profile_state='complete',profile_error='',criteria='{}',demand_source=?,version=version+1,updated_at=? WHERE id=?",
                       (dump(profile), dump(source), now(), job['id']))
            pool.event(db, owner, actor, job['id'], 'sourcing.demand', None, source)
        return view(db, db.execute('SELECT * FROM hr_sourcing_jobs WHERE id=?', (job['id'],)).fetchone())


def save(user, body):
    owner, actor = pool.context(user)
    key = account(body.get('accountKey')); job_id = pool.text(body.get('jobId'), 180)
    if not re.fullmatch(r'[\w~-]+', job_id): raise ValueError('请选择具体职位')
    clean = {k: pool.text(body.get(k, ''), limit) for k, limit in
             {'jobName': 200, 'description': 10000, 'education': 100, 'experience': 100}.items()}
    if not clean['jobName'] or not clean['description']: raise ValueError('请先读取该职位的完整 JD')
    with pool.database() as db:
        schema(db); expire(db)
        old = db.execute('SELECT * FROM hr_sourcing_jobs WHERE owner=? AND account=? AND job_id=?', (owner, key, job_id)).fetchone()
        if old:
            previous = json.loads(old['body'])
            if previous != clean:
                if old['profile_state'] == 'running': raise pool.Conflict('画像正在生成，请完成后更新 JD')
                if any(previous.get(key) != clean[key] for key in ('jobName', 'description')):
                    db.execute("UPDATE hr_sourcing_jobs SET body=?,version=version+1,profile_state='stale',criteria='{}',updated_at=? WHERE id=?",
                               (dump(clean), now(), old['id']))
                else:
                    # Restoring official degree/tenure labels invalidates old matches,
                    # but does not require paying to regenerate an unchanged JD profile.
                    db.execute("UPDATE hr_sourcing_jobs SET body=?,version=version+1,updated_at=? WHERE id=?",
                               (dump(clean), now(), old['id']))
            record = old['id']
        else:
            record = str(uuid.uuid4())
            db.execute('INSERT INTO hr_sourcing_jobs(id,owner,account,job_id,body,updated_at) VALUES(?,?,?,?,?,?)',
                       (record, owner, key, job_id, dump(clean), now()))
        pool.event(db, owner, actor, record, 'sourcing.job', None, {'jobId': job_id})
        return view(db, db.execute('SELECT * FROM hr_sourcing_jobs WHERE id=?', (record,)).fetchone())


def references(user, body, remove=False):
    import resume_storage as storage
    owner, actor = pool.context(user)
    with pool.database() as db:
        schema(db); expire(db); job = get_job(db, owner, body)
        if job['profile_state'] == 'running': raise pool.Conflict('画像正在生成，请完成后修改参考简历')
        if remove:
            changed = db.execute('DELETE FROM hr_sourcing_refs WHERE id=? AND owner=? AND job=?',
                                 (uid(body.get('referenceId')), owner, job['id'])).rowcount
            if not changed: raise ValueError('参考简历不存在')
            db.execute("UPDATE hr_sourcing_jobs SET version=version+1,profile_state='stale',criteria='{}' WHERE id=?", (job['id'],))
            return view(db, get_job(db, owner, body))
    raw = base64.b64decode(body.get('fileBase64', ''), validate=True)
    filename = storage.original_filename(body.get('filename'))
    extension = filename.rsplit('.', 1)[-1].lower()
    if len(raw) > MAX_UPLOAD or extension not in {'pdf', 'docx'}: raise ValueError('支持 PDF、DOCX，每份不超过 10 MB')
    storage.validate(raw, extension)
    digest = hashlib.sha256(raw).hexdigest()
    record = str(uuid.uuid5(uuid.NAMESPACE_URL, f'hr-reference:{owner}:{job["id"]}:{digest}'))
    with pool.database() as db:
        schema(db)
        if db.execute('SELECT 1 FROM hr_sourcing_refs WHERE id=? AND owner=?', (record, owner)).fetchone():
            return view(db, get_job(db, owner, body))
    text, note = storage.extract(raw, extension)
    # Extraction is offline. Upload alone never starts a paid model call.
    if len(text.strip()) < 30: raise ValueError('该简历未提取到足够正文，请上传含文字层的 PDF 或 Word 文件')
    location = storage.store(owner, record, raw, extension)
    with pool.database() as db:
        schema(db); job = get_job(db, owner, body)
        if job['profile_state'] == 'running': raise pool.Conflict('画像正在生成，请完成后重新上传')
        if db.execute('SELECT COUNT(*) FROM hr_sourcing_refs WHERE owner=? AND job=?', (owner, job['id'])).fetchone()[0] >= 10:
            raise ValueError('参考简历已达到 10 份上限')
        inserted = db.execute('INSERT OR IGNORE INTO hr_sourcing_refs VALUES(?,?,?,?,?,?,?,?)',
                              (record, owner, job['id'], digest, filename, dump({'text': text[:60000], 'note': note}), location, now()))
        if inserted.rowcount:
            db.execute("UPDATE hr_sourcing_jobs SET version=version+1,profile_state='stale',criteria='{}',updated_at=? WHERE id=?", (now(), job['id']))
            pool.event(db, owner, actor, record, 'sourcing.reference', None, {'job': job['id'], 'digest': digest})
        return view(db, get_job(db, owner, body))


def driver(kind, snapshot, cookie):
    request = urllib.request.Request(AI_BASE+kind, data=dump(snapshot).encode(),
                                     headers={'Content-Type': 'application/json', 'Cookie': cookie})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=320) as response:
            raw = response.read(512001)
    except urllib.error.HTTPError as error:
        # Only locally defined error codes leave this boundary; never echo an
        # upstream body, URL, credential, or candidate detail.
        messages = {401: '平台登录已失效，请重新登录赫逸平台后再分析',
                    403: '当前账号没有岗位分析权限',
                    409: '驱动模型未就绪，请检查模型配置',
                    429: '驱动模型当前调用较多，请稍后再分析',
                    502: '驱动模型返回不完整或服务异常，本轮已停止，没有自动重复调用',
                    503: '驱动模型服务暂不可用，本轮已停止',
                    504: '驱动模型分析超时，本轮已停止'}
        known = {
            'HR_AI_UPSTREAM_HTTP': '驱动模型供应商返回服务错误，本轮已停止',
            'HR_AI_OUTPUT_LIMIT': '驱动模型输出达到长度上限，本批结果不完整，已停止',
            'HR_AI_STREAM_INTERRUPTED': '驱动模型响应流中断，本轮已停止',
            'HR_AI_CONTENT_FILTER': '驱动模型未接受本次内容，请调整岗位描述',
            'HR_AI_TOOL_CALL': '驱动模型返回了工具调用，未执行任何工具，本轮已停止',
            'HR_AI_INVALID_JSON': '驱动模型返回的分析不是完整 JSON，本轮已停止',
            'HR_AI_INVALID_RESULT': '驱动模型未完整返回候选人或匹配条件，本轮已停止',
            'HR_AI_INVALID_STREAM': '驱动模型响应流格式不正确，本轮已停止',
            'HR_AI_CONNECTION': '驱动模型连接中断，本轮已停止',
            'HR_AI_OVERSIZED': '驱动模型返回内容超过大小限制，本轮已停止',
            'HR_AI_TRUNCATED': '驱动模型输出达到长度上限，本批结果不完整，已停止',
            'HR_AI_TOOLS': '驱动模型返回了工具调用，未执行任何工具，本轮已停止',
            'HR_AI_TIMEOUT': '驱动模型分析超时，本轮已停止',
        }
        try:
            detail = json.loads(error.read(8193)).get('message', '')
        except (ValueError, AttributeError, UnicodeDecodeError, OSError):
            detail = ''
        if isinstance(detail, str):
            for code, message in known.items():
                if detail.endswith('['+code+']'):
                    raise ValueError(message+'；没有自动重复调用 ['+code+']') from None
        raise ValueError(messages.get(error.code, f'岗位分析服务返回 HTTP {error.code}，本轮已停止')) from None
    if len(raw) > 512000: raise ValueError('模型结果过长')
    result = json.loads(raw).get('data')
    if not isinstance(result, dict): raise ValueError('模型返回不完整')
    return result


def profile(user, body, cookie):
    owner, actor = pool.context(user)
    with pool.database() as db:
        schema(db); expire(db); job = get_job(db, owner, body)
        if job['version'] != body.get('version'): raise pool.Conflict('岗位或参考简历已变化，请刷新后生成')
        if job['profile_state'] in {'running', 'complete'}: return view(db, job)
        if db.execute("SELECT 1 FROM hr_sourcing_jobs WHERE owner=? AND profile_state='running'", (owner,)).fetchone():
            raise pool.Conflict('已有画像正在生成，请等待完成')
        refs = [json.loads(r['body'])['text'][:8000] for r in db.execute('SELECT body FROM hr_sourcing_refs WHERE owner=? AND job=? ORDER BY created_at,id', (owner, job['id']))]
        if not refs: raise ValueError('请先上传至少一份参考简历')
        data = json.loads(job['body']); token = uuid.uuid4().hex
        snapshot = {'jobName': data['jobName'], 'jobDescription': data['description'], 'references': refs}
        db.execute("UPDATE hr_sourcing_jobs SET profile_state='running',profile_token=?,profile_at=?,profile_error='' WHERE id=?", (token, time.time(), job['id']))
        pool.event(db, owner, actor, job['id'], 'sourcing.profile-request', None, {'version': job['version'], 'token': token})
    threading.Thread(target=profile_worker, args=(owner, job['id'], token, snapshot, cookie), daemon=True).start()
    return listing(user, body)


def profile_worker(owner, record, token, snapshot, cookie):
    try:
        result = driver('sourcing-profile', snapshot, cookie)
        if not result.get('suggestedJd') or not result.get('skills') or not result.get('evidence'): raise ValueError('incomplete')
        state, error = 'complete', ''
    except Exception:
        result, state, error = {}, 'failed', '画像生成未完成，请检查驱动模型后手动重试；系统没有自动重复调用'
    with pool.database() as db:
        schema(db)
        db.execute('UPDATE hr_sourcing_jobs SET profile_state=?,profile=?,profile_error=? WHERE owner=? AND id=? AND profile_token=? AND profile_state=\'running\'',
                   (state, dump(result), error, owner, record, token))


def safe_text(value, limit=2000):
    return value.strip()[:limit] if isinstance(value, str) else ''


def candidate(raw, job_id):
    if not isinstance(raw, dict): raise ValueError('候选人资料不正确')
    info = next((raw[k] for k in ('geekCard', 'geekInfo', 'geekBaseInfo') if isinstance(raw.get(k), dict)), raw)
    def first(*keys):
        for key in keys:
            for source in (info, raw):
                value = source.get(key)
                if isinstance(value, (str, int)) and not isinstance(value, bool) and str(value).strip(): return str(value).strip()
        return ''
    geek = first('encryptGeekId', 'encGeekId')
    actual_job = first('encryptJobId', 'encJobId', 'jobId')
    if not geek or (actual_job and actual_job != job_id): return None
    if not re.fullmatch(r'[\w~.-]{1,180}', geek): return None
    identifiers = {name: first(*keys) for name, keys in {
        'geekId': ('encryptGeekId', 'encGeekId'), 'expectId': ('expectId',),
        'lid': ('lid',), 'securityId': ('securityId',), 'suid': ('suid',)}.items()}
    if any(len(value) > (4096 if key == 'securityId' else 600)
           for key, value in identifiers.items()): return None
    if identifiers['securityId'] and not SECURITY_ID.fullmatch(identifiers['securityId']): return None
    # Only explicitly job-related fields enter the model; identifiers and names
    # stay outside it. Education/school, age and other personal attributes are omitted.
    evidence = []
    for source in (info, raw):
        for key in ('geekDesc', 'description', 'workYearDesc', 'geekWorkYear', 'expectPositionName', 'positionName'):
            value = source.get(key)
            value = safe_text(value.get('content') if isinstance(value, dict) else value, 600)
            if value and value not in evidence: evidence.append(value)
        for key in ('skills', 'skillList', 'geekSkills', 'matches'):
            for value in (source.get(key) or [])[:10] if isinstance(source.get(key), list) else []:
                if isinstance(value, str) and value.strip(): evidence.append(value[:100])
        for key in ('workExperienceList', 'geekWorkExpList', 'works', 'workExpList', 'geekWorks'):
            for work in (source.get(key) or [])[:3] if isinstance(source.get(key), list) else []:
                if isinstance(work, dict):
                    evidence.extend(safe_text(work.get(k), 400) for k in ('positionName', 'position', 'responsibility', 'description', 'workDesc') if work.get(k))
    # Standard degree level and explicit tenure are checked locally. School names,
    # graduation dates, age and gender never enter this check or model input.
    degree = degree_level(first('degreeName', 'degreeDesc', 'educationName', 'geekDegree', 'degree', 'education'))
    tenure = first('workYearDesc', 'geekWorkYearDesc', 'experienceDesc', 'geekWorkYear', 'workYears', 'work_years', 'experience')
    years = year_range(tenure)
    if degree is None:
        levels = []
        for source in (info, raw):
            for key in ('geekEdus', 'educationList', 'eduList'):
                for edu in source.get(key, []) if isinstance(source.get(key), list) else []:
                    if isinstance(edu, dict):
                        parsed = degree_level(next((edu[k] for k in ('degreeName', 'educationName', 'degree', 'education')
                                                   if isinstance(edu.get(k), str)), ''))
                        if parsed is not None:
                            levels.append(parsed)
        degree = max(levels) if levels else None
    facts = {'education': degree, 'years': [years[0], None if math.isinf(years[1]) else years[1]] if years else None,
             'yearsText': tenure[:100] if years else ''}
    return {'geekId': geek, 'name': first('geekName', 'name', 'nickname')[:100] or '候选人',
            'evidenceText': '\n'.join(evidence)[:2000] or '未提供可核实的岗位相关资料',
            'basicFacts': facts,
            'identifiers': identifiers, 'canGreet': all(identifiers.get(k) for k in ('geekId', 'expectId', 'lid', 'securityId'))}


def search(user, body, cookie):
    owner, actor = pool.context(user)
    wanted = body.get('count')
    if type(wanted) is not int or not 1 <= wanted <= 20: raise ValueError('请选择 1–20 人')
    raw = body.get('candidates')
    if not isinstance(raw, list) or not 1 <= len(raw) <= 40: raise ValueError('本次需要 1–40 条本机读取的候选人资料')
    try: token = str(uuid.UUID(body.get('requestId', '')))
    except (ValueError, TypeError, AttributeError): raise ValueError('寻源请求标识无效')
    record = str(uuid.uuid5(uuid.NAMESPACE_URL, f'hr-sourcing-search:{owner}:{token}'))
    batch = body.get('batchIndex', 1)
    page = body.get('page', 1)
    if type(batch) is not int or batch < 1:
        raise ValueError('分析批次必须为正整数')
    if type(page) is not int or page < 1:
        raise ValueError('页码必须为正整数')
    with pool.database() as db:
        schema(db); expire(db); job = get_job(db, owner, body)
        requirements = basic_requirements(json.loads(job['body']))
        old = db.execute('SELECT * FROM hr_sourcing_runs WHERE id=? AND owner=?', (record, owner)).fetchone()
        previous, source = {}, {}
        if old:
            if old['job'] != job['id']: raise pool.Conflict('该请求已用于其他职位')
            previous = json.loads(old['result'])
            if previous.get('basicRequirements', []) != requirements:
                raise pool.Conflict('岗位学历、年限核验已更新，请重新开始获取；不会自动重跑旧分析')
            completed_batches = previous.get('batches', 1)
            if batch <= completed_batches:
                return view(db, job, record)
            if old['version'] != job['version'] or old['wanted'] != wanted:
                raise pool.Conflict('寻源岗位、画像或目标人数已变化，请重新获取')
            if old['state'] != 'complete' or old['error']:
                raise pool.Conflict('上一批尚未完成或已停止，不会重复提交 AI')
            if previous.get('stopReason') == 'no_matches_100' or (
                    previous.get('examined', 0) >= 100 and not previous.get('qualified', 0)):
                raise pool.Conflict('已分析 100 人仍无匹配，请先调整并保存匹配条件')
            if batch != completed_batches + 1:
                raise ValueError('寻源分析批次不连续')
            source = {value['id']: value for value in json.loads(old['body'])}
            # Upgrade earlier runs lazily. Full card data stays in the candidate
            # table; the session dedup index grows without rewriting all cards.
            for cid in source:
                db.execute('INSERT OR IGNORE INTO hr_sourcing_seen VALUES(?,?)', (record, cid))
        elif batch != 1:
            raise ValueError('请先开始第一批寻源分析')
        if job['profile_state'] != 'complete' or job['version'] != body.get('version'):
            raise ValueError('请先生成当前职位的寻源画像')
        rules = matching_criteria(job)
        if not rules['confirmed']: raise ValueError('请先确认并保存匹配条件，再开始寻找')
        if db.execute("SELECT 1 FROM hr_sourcing_runs WHERE owner=? AND state='running'", (owner,)).fetchone():
            raise pool.Conflict('已有寻源分析在执行，请等待完成')
        parsed = {}
        for entry in raw:
            value = candidate(entry, job['job_id'])
            if not value: continue
            value['basicChecks'] = basic_checks(value, requirements)
            cid = str(uuid.uuid5(uuid.NAMESPACE_URL, f'hr-source-candidate:{owner}:{job["id"]}:{value["geekId"]}'))
            value['id'] = cid
            if not db.execute('SELECT 1 FROM hr_sourcing_seen WHERE run=? AND candidate=?',
                              (record, cid)).fetchone():
                parsed[cid] = value
        if not parsed and source:
            return view(db, job, record)
        if not parsed: raise ValueError('已读取候选人，但卡片的职位或访问参数校验未通过，本次未调用 AI。请刷新获取候选人后重试')
        if not previous.get('qualified', 0):
            parsed = dict(list(parsed.items())[:max(0, 100 - previous.get('examined', 0))])
        for cid, value in parsed.items():
            db.execute('INSERT OR REPLACE INTO hr_sourcing_candidates VALUES(?,?,?,?,?,?)',
                       (cid, owner, job['id'], value['geekId'], dump(value), time.time()))
            db.execute('INSERT OR IGNORE INTO hr_sourcing_seen VALUES(?,?)', (record, cid))
        snapshot = {'jobDescription': json.loads(job['body'])['description'], 'profile': dump({'conditions': rules['conditions']}),
                    'criteria': rules['conditions'], 'batchIndex': batch,
                    'candidates': [{'id': cid, 'evidence': value['evidenceText'], 'basicChecks': value['basicChecks']}
                                   for cid, value in parsed.items()]}
        progress = {**previous, 'batches': batch, 'pagesRead': max(page, previous.get('pagesRead', 0)),
                    'basicRequirements': requirements,
                    'threshold': MATCH_THRESHOLD, 'referenceOnly': True}
        if old:
            db.execute("UPDATE hr_sourcing_runs SET state='running',body=?,result=?,started=?,updated_at=? WHERE id=?",
                       (dump(list(parsed.values())), dump(progress), time.time(), now(), record))
        else:
            db.execute('INSERT INTO hr_sourcing_runs(id,owner,job,version,wanted,state,body,result,started,updated_at) VALUES(?,?,?,?,?,\'running\',?,?,?,?)',
                       (record, owner, job['id'], job['version'], wanted, dump(list(parsed.values())),
                        dump(progress), time.time(), now()))
        pool.event(db, owner, actor, record, 'sourcing.search-request', None,
                   {'job': job['id'], 'count': wanted, 'examined': len(parsed), 'batch': batch, 'page': page})
    threading.Thread(target=search_worker, args=(owner, record, snapshot, cookie), daemon=True).start()
    return listing(user, {**body, 'runId': record})


def no_match_suggestions(conditions, stats):
    suggestions = []
    for condition in sorted(conditions, key=lambda c: stats.get(c['id'], {}).get('unknown', 0), reverse=True):
        counts = stats.get(condition['id'], {})
        unknown, unmatched = counts.get('unknown', 0), counts.get('unmatched', 0)
        if unknown:
            suggestions.append(f'“{condition["text"]}”有 {unknown} 人的卡片未说明。'
                               + ('建议核对候选人资料与岗位最低要求，资料缺失不能视为符合。' if condition.get('basic')
                                  else '建议改为卡片中可验证的工作经历或具体技能，细节留待简历核实。'))
        elif unmatched:
            suggestions.append(f'“{condition["text"]}”有 {unmatched} 人明确不符。'
                               + ('请核对岗位 JD 的最低要求；不会自动降低学历或年限门槛。' if condition.get('basic')
                                  else '请核对是否确为必要条件；相近岗位或可迁移经历可写入条件。'))
        if len(suggestions) == 2:
            break
    suggestions.append('建议保留 2–3 条核心工作能力，可替代技能明确写“满足其中一项即可”，并核对当前关联职位及 JD。修改后需由 HR 确认保存再寻找。')
    return suggestions


def qualified_match(row, conditions):
    if any(check['status'] != 'matched' for check in row.get('basicChecks', [])):
        return False
    if row["score"] < MATCH_THRESHOLD or row.get("matchState", "matched") != "matched":
        return False
    if not conditions:
        return True
    states = {check["criterionId"]: check["status"] for check in row["checks"]}
    total = sum(condition["weight"] for condition in conditions)
    known = sum(condition["weight"] for condition in conditions if states[condition["id"]] != "unknown")
    return known >= total * .6 and all(
        not condition["required"] or states[condition["id"]] == "matched" for condition in conditions)


def search_worker(owner, record, snapshot, cookie):
    try:
        # Short, request-local aliases reduce model copying errors without
        # exposing BOSS identifiers or weakening exact candidate binding.
        aliases = {f'c{index + 1}': value['id'] for index, value in enumerate(snapshot['candidates'])}
        model_input = {key: snapshot[key] for key in ('jobDescription', 'profile')}
        if snapshot.get('criteria'): model_input['criteria'] = snapshot['criteria']
        model_input['candidates'] = [
            {'id': alias, 'evidence': value['evidence']}
            for alias, value in zip(aliases, snapshot['candidates'])]
        result = driver('sourcing-match', model_input, cookie)
        rows = result.get('candidates')
        expected = set(aliases)
        if not isinstance(rows, list) or not all(isinstance(row, dict) for row in rows) \
                or len(rows) != len(expected) or {r.get('id') for r in rows} != expected:
            raise ValueError('AI 返回的人选标识不完整，本轮已停止；已匹配人选仍保留')
        if any(type(r.get('score')) not in (int, float) or not math.isfinite(r['score']) or not 0 <= r['score'] <= 100 for r in rows):
            raise ValueError('AI 返回的匹配分格式不正确，本轮已停止')
        if snapshot.get('criteria'):
            expected_conditions = {c['id'] for c in snapshot['criteria']}
            for row in rows:
                checks = row.get('checks')
                if row.get('scoreBasis') != 'known_conditions' or row.get('matchState') not in {
                        'matched', 'below_threshold', 'needs_info', 'required_mismatch'} \
                        or not isinstance(checks, list) or len(checks) != len(expected_conditions) \
                        or any(not isinstance(c, dict) or c.get('status') not in {
                            'matched', 'partial', 'unmatched', 'unknown'} for c in checks) \
                        or {c.get('criterionId') for c in checks} != expected_conditions:
                    raise ValueError('AI 返回的逐条条件分析不完整，本轮已停止；没有自动重复调用')
                conditions = snapshot['criteria']
                states = {check['criterionId']: check['status'] for check in checks}
                total = sum(condition['weight'] for condition in conditions)
                known = sum(condition['weight'] for condition in conditions if states[condition['id']] != 'unknown')
                row['coverage'] = round(known / total * 100, 1)
                if any(condition['required'] and states[condition['id']] == 'unmatched' for condition in conditions):
                    row['matchState'] = 'required_mismatch'
                elif known < total * .6 or any(
                        condition['required'] and states[condition['id']] != 'matched' for condition in conditions):
                    row['matchState'] = 'needs_info'
                elif row['score'] < MATCH_THRESHOLD:
                    row['matchState'] = 'below_threshold'
        for row in rows:
            checks = next(value.get('basicChecks', []) for value in snapshot['candidates'] if value['id'] == aliases[row['id']])
            row['basicChecks'] = checks
            if any(check['status'] == 'unmatched' for check in checks):
                row['matchState'] = 'required_mismatch'
            elif any(check['status'] == 'unknown' for check in checks) and row.get('matchState') != 'required_mismatch':
                row['matchState'] = 'needs_info'
            row['id'] = aliases[row['id']]
        error = ''
    except ValueError as problem:
        safe = str(problem)
        error = safe if safe.startswith(('AI 返回', '驱动模型', '平台登录', '当前账号', '岗位分析')) else 'AI 寻源分析未完成，本轮已停止；未发送任何招呼'
        result = {}
    except Exception:
        result, error = {}, 'AI 寻源分析未完成，本轮已停止；未发送任何招呼'
    with pool.database() as db:
        schema(db)
        run = db.execute('SELECT * FROM hr_sourcing_runs WHERE id=? AND owner=?', (record, owner)).fetchone()
        if not run or run['state'] != 'running': return
        previous = json.loads(run['result'])
        if previous.get('batches', 1) != snapshot.get('batchIndex', 1): return
        if not error:
            source = {v['id']: v for v in json.loads(run['body'])}
            matched = {item['id']: item for item in previous.get('matched', previous.get('candidates', []))}
            for item in result['candidates']:
                if qualified_match(item, snapshot.get("criteria", [])):
                    matched[item['id']] = {**item, 'name': source[item['id']]['name'],
                                           'canGreet': source[item['id']]['canGreet']}
            ranked = sorted(matched.values(), key=lambda row: -row['score'])
            condition_stats = previous.get('conditionStats', {})
            for item in rows:
                for check in item.get('checks', []):
                    counts = condition_stats.setdefault(check['criterionId'], {})
                    counts[check['status']] = counts.get(check['status'], 0) + 1
                for check in item.get('basicChecks', []):
                    counts = condition_stats.setdefault('basic:'+check['id'], {})
                    counts[check['status']] = counts.get(check['status'], 0) + 1
            result = {**previous, 'matched': ranked[:run['wanted']], 'candidates': ranked[:run['wanted']],
                      'conditionStats': condition_stats,
                      'examined': previous.get('examined', 0) + len(snapshot['candidates']),
                      'qualified': previous.get('qualified', 0) + sum(qualified_match(r, snapshot.get("criteria", [])) for r in rows),
                      'needsInfo': previous.get('needsInfo', 0) + sum(r.get('matchState') == 'needs_info' for r in rows),
                      'belowThreshold': previous.get('belowThreshold', 0) + sum(r.get('matchState') in ('below_threshold', 'required_mismatch') for r in rows),
                      'reviewExamples': [{**r, 'name': source[r['id']]['name']} for r in
                        sorted((r for r in rows if r.get('matchState') != 'matched'), key=lambda r: -r['score'])[:3]],
                      'referenceOnly': True}
            if result['examined'] >= 100 and result['qualified'] == 0:
                basic = [{**check, 'id': 'basic:'+check['id'], 'basic': True}
                         for check in snapshot['candidates'][0].get('basicChecks', [])]
                result.update(stopped=True, stopReason='no_matches_100',
                              suggestions=no_match_suggestions(basic+snapshot.get('criteria', []), condition_stats))
        else:
            result = {**previous, 'partial': bool(previous.get('candidates')), 'stopped': True}
        db.execute('UPDATE hr_sourcing_runs SET state=?,result=?,error=?,updated_at=? WHERE id=?',
                   ('failed' if error and not result.get('candidates') else 'complete', dump(result), error, now(), record))


def greet(user, body):
    owner, actor = pool.context(user)
    message = pool.text(body.get('message'), 1000)
    if body.get('confirmed') is not True or not message: raise ValueError('请确认该候选人和打招呼内容')
    with pool.database() as db:
        schema(db); expire(db); job = get_job(db, owner, body)
        cid = uid(body.get('candidateId'))
        old = db.execute('SELECT * FROM hr_sourcing_contacts WHERE owner=? AND account=? AND job=? AND candidate=?',
                         (owner, job['account'], job['id'], cid)).fetchone()
        if old: return {'id': old['id'], 'status': old['status'], 'accountKey': job['account']}
        if not matching_criteria(job)["confirmed"]:
            raise ValueError("匹配规则已更新，请确认条件并重新评估后再联系")
        selected = db.execute('SELECT * FROM hr_sourcing_candidates WHERE id=? AND owner=? AND job=?', (cid, owner, job['id'])).fetchone()
        if not selected or time.time()-selected['read_at']>1800: raise ValueError('候选人卡片已过期，请重新获取后再联系')
        detail = json.loads(selected['body'])
        if not current_basic_checks(detail.get('basicChecks', []), basic_requirements(json.loads(job['body']))):
            raise ValueError('学历、工作年限尚未通过当前岗位要求核验，请重新获取后再联系')
        if not detail['canGreet']: raise ValueError('该卡片缺少平台首次打招呼参数，请通过官方页面联系')
        current_run = db.execute('SELECT * FROM hr_sourcing_runs WHERE owner=? AND job=? ORDER BY started DESC,id LIMIT 1', (owner, job['id'])).fetchone()
        if not current_run or current_run['state'] != 'complete' or current_run['version'] != job['version'] or cid not in {
                r['id'] for r in json.loads(current_run['result']).get('candidates', [])}:
            raise ValueError('请从当前职位已完成的寻源结果中选择候选人')
        record = str(uuid.uuid4()); data = json.loads(job['body'])
        payload = {'kind': 'active_greet', 'message': message, 'name': detail['name'],
                   'jobId': job['job_id'], 'jobName': data['jobName'], 'jobDescription': data['description'],
                   'education': data['education'], 'experience': data['experience'],
                   'identifiers': detail['identifiers'], 'confirmedBy': actor, 'readAt': selected['read_at'],
                   'enabledAt': int(time.time()), 'ruleId': 'active-'+record, 'origin': 'active'}
        db.execute('INSERT INTO hr_sourcing_contacts(id,owner,account,job,candidate,body,status,created_at,updated_at) VALUES(?,?,?,?,?,?,\'queued\',?,?)',
                   (record, owner, job['account'], job['id'], cid, dump(payload), now(), now()))
        pool.event(db, owner, actor, record, 'sourcing.greet-approved', None, {'candidate': cid, 'job': job['id'], 'message': message})
        return {'id': record, 'status': 'queued', 'accountKey': job['account']}


def device_contacts(db, owner, key):
    schema(db); expire(db)
    return [{**json.loads(r['body']), 'id': r['id'], 'status': r['status'], 'friendId': r['friend_id']}
            for r in db.execute("SELECT * FROM hr_sourcing_contacts WHERE owner=? AND account=? AND status IN ('queued','sent','uncertain') ORDER BY created_at", (owner, key))]


def claim(db, owner, key, record):
    schema(db)
    row = db.execute("SELECT * FROM hr_sourcing_contacts WHERE id=? AND owner=? AND account=? AND status='queued'", (record, owner, key)).fetchone()
    if not row: return None
    if time.time()-json.loads(row['body'])['readAt']>1800:
        db.execute("UPDATE hr_sourcing_contacts SET status='expired',updated_at=? WHERE id=?", (now(), record))
        return {'claimed': False}
    timezone = datetime.timezone(datetime.timedelta(hours=8))
    stamp = datetime.datetime.fromtimestamp(time.time(), timezone)
    midnight = stamp.replace(hour=0,minute=0,second=0,microsecond=0)
    used = 0
    for previous in db.execute('SELECT body,status,created_at FROM hr_sourcing_contacts WHERE owner=? AND account=?', (owner,key)):
        payload = json.loads(previous['body'])
        attempted = payload.get('attemptedAt')
        if attempted is None and previous['status'] in {'sending','sent','uncertain'}:
            attempted = datetime.datetime.fromisoformat(previous['created_at']).timestamp()
        if type(attempted) in (int,float) and midnight.timestamp() <= attempted < midnight.timestamp()+86400:
            used += 1
    payload = json.loads(row['body'])
    if used >= 150:
        payload['waitInfo'] = {'reasonCode':'DAILY_GREETING_LIMIT','retryAt':int(midnight.timestamp()+86400)}
        db.execute('UPDATE hr_sourcing_contacts SET body=?,updated_at=? WHERE id=?',(dump(payload),now(),record))
        return {'claimed':False,'reasonCode':'DAILY_GREETING_LIMIT','used':used,'limit':150}
    payload['attemptedAt'] = time.time()
    # Claim and daily accounting share this transaction across all desktops.
    db.execute("UPDATE hr_sourcing_contacts SET status='sending',body=?,updated_at=? WHERE id=?", (dump(payload),now(),record))
    return {'claimed': True}


def report(db, owner, key, body):
    schema(db)
    if body.get('waitingContactId'):
        reason = body.get('reasonCode')
        retry = body.get('retryAt', 0)
        if reason not in {'REQUEST_BUDGET_WAIT','SEND_INTERVAL','JOB_STATUS_WAIT','JOB_NOT_OPEN','RISK_PAUSED','GREET_QUOTA','CARD_EXPIRED','DAILY_GREETING_LIMIT'} \
                or type(retry) not in (int,float) or not math.isfinite(retry) or not 0 <= retry <= time.time()+86410:
            raise ValueError('等待状态不正确')
        row = db.execute("SELECT id,body FROM hr_sourcing_contacts WHERE id=? AND owner=? AND account=? AND status='queued'",
                         (uid(body['waitingContactId']), owner, key)).fetchone()
        if row:
            payload = json.loads(row['body'])
            payload['waitInfo'] = {'reasonCode': reason, 'retryAt': retry}
            db.execute('UPDATE hr_sourcing_contacts SET body=?,updated_at=? WHERE id=?',
                       (dump(payload), now(), row['id']))
    if body.get('commandId'):
        db.execute("UPDATE hr_sourcing_contacts SET status=?,updated_at=? WHERE id=? AND owner=? AND account=? AND status IN ('sending','uncertain')",
                   (body['status'], now(), uid(body['commandId']), owner, key))
    if body.get('contactId'):
        friend = str(body.get('friendId', ''))
        if not re.fullmatch(r'[1-9]\d{0,19}', friend): raise ValueError('会话标识不正确')
        row = db.execute("SELECT * FROM hr_sourcing_contacts WHERE id=? AND owner=? AND account=? AND status='sent'",
                         (uid(body['contactId']), owner, key)).fetchone()
        if not row or row['friend_id'] not in ('', friend): raise ValueError('主动邀约的会话关联不一致')
        db.execute('UPDATE hr_sourcing_contacts SET friend_id=? WHERE id=?', (friend, row['id']))


def received_job(db, owner, key, rule_id, friend):
    schema(db)
    if not rule_id.startswith('active-'): return None
    row = db.execute("SELECT * FROM hr_sourcing_contacts WHERE id=? AND owner=? AND account=? AND status='sent' AND friend_id=?",
                     (rule_id[7:], owner, key, friend)).fetchone()
    if not row: raise ValueError('主动邀约简历未关联已确认成功的会话')
    detail = json.loads(row['body'])
    return {'jobId': detail['jobId'], 'jobName': detail['jobName'], 'description': detail['jobDescription'],
            'education': detail['education'], 'experience': detail['experience'], 'projectId': '', 'origin': 'active'}


def recover():
    with pool.database() as db:
        schema(db)
        db.execute("UPDATE hr_sourcing_jobs SET profile_state='failed',profile_error='服务已更新，请手动重新生成' WHERE profile_state='running'")
        db.execute("UPDATE hr_sourcing_runs SET state='failed',error='服务已更新，请手动重新获取' WHERE state='running'")
        db.execute("UPDATE hr_sourcing_contacts SET status='uncertain' WHERE status='sending'")
