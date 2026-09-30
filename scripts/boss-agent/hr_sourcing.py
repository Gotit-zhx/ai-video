"""Job-scoped reference resumes, explicit DRIVER analysis and human-approved greetings.

This module never connects to BOSS. Writes are leased to the account's desktop.
"""
import base64
import datetime
import hashlib
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


PERSONAL_CRITERION = re.compile(r'性别|男生|女生|男性|女性|年龄|身高|婚育|民族|宗教|残障|外貌|颜值|长相|气质|形象好')


def matching_criteria(job):
    saved = json.loads(job['criteria'])
    if saved.get('conditions'): return saved
    profile = json.loads(job['profile'])
    skills = [v[:50] for v in profile.get('skills', []) if isinstance(v, str) and not PERSONAL_CRITERION.search(v)]
    return {'confirmed': False, 'conditions': [
        {'id': 'k1', 'text': '具有本岗位相关或可迁移的工作经验，相近岗位经历也可匹配', 'weight': 50, 'required': False},
        {'id': 'k2', 'text': ('；'.join(skills[:3]) or '能够完成本岗位的核心日常工作')+'（具备其中一项即可）', 'weight': 30, 'required': False},
        {'id': 'k3', 'text': ('；'.join(skills[3:6]) or '有工作任务交付、沟通或协作的实际经历')+'（具备其中一项即可）', 'weight': 20, 'required': False}],
        'scoreBasis': 'known_conditions', 'threshold': MATCH_THRESHOLD}


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
        clean.append({'id': f'k{index+1}', 'text': text, 'weight': weight, 'required': row.get('required') is True})
    with pool.database() as db:
        schema(db); expire(db); job = get_job(db, owner, body)
        if job['version'] != body.get('version'): raise pool.Conflict('岗位条件已变化，请刷新核对')
        if job['profile_state'] != 'complete': raise ValueError('请先完成岗位画像')
        if db.execute("SELECT 1 FROM hr_sourcing_runs WHERE owner=? AND job=? AND state='running'",
                      (owner, job['id'])).fetchone(): raise pool.Conflict('请先暂停寻找并等待当前分析结束，再修改条件')
        value = {'confirmed': True, 'conditions': clean, 'scoreBasis': 'known_conditions', 'threshold': MATCH_THRESHOLD}
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


def get_job(db, owner, body):
    row = db.execute('SELECT * FROM hr_sourcing_jobs WHERE id=? AND owner=?', (uid(body.get('id')), owner)).fetchone()
    if not row or row['account'] != account(body.get('accountKey')):
        raise ValueError('职位与当前招聘账号不一致，请从职位管理重新进入')
    return row


def view(db, row, run_id=None):
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
            contact = db.execute('SELECT id,status,friend_id FROM hr_sourcing_contacts WHERE owner=? AND job=? AND candidate=?',
                                 (row['owner'], row['id'], item['id'])).fetchone()
            item['contact'] = dict(contact) if contact else None
    return {**json.loads(row['body']), 'id': row['id'], 'jobId': row['job_id'], 'accountKey': row['account'],
            'version': row['version'], 'profileState': row['profile_state'], 'profile': json.loads(row['profile']),
            'profileError': row['profile_error'], 'profileStartedAt': row['profile_at'],
            'criteria': matching_criteria(row), 'references': refs, 'run': run}


def listing(user, query):
    owner, _ = pool.context(user)
    with pool.database() as db:
        schema(db); expire(db)
        if query.get('id'):
            return view(db, get_job(db, owner, query), query.get('runId'))
        key = account(query.get('accountKey'))
        row = db.execute('SELECT * FROM hr_sourcing_jobs WHERE owner=? AND account=? AND job_id=?',
                         (owner, key, pool.text(query.get('jobId'), 180))).fetchone()
        return view(db, row) if row else None


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
            if json.loads(old['body']) != clean:
                if old['profile_state'] == 'running': raise pool.Conflict('画像正在生成，请完成后更新 JD')
                db.execute("UPDATE hr_sourcing_jobs SET body=?,version=version+1,profile_state='stale',criteria='{}',updated_at=? WHERE id=?",
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
    return {'geekId': geek, 'name': first('geekName', 'name', 'nickname')[:100] or '候选人',
            'evidenceText': '\n'.join(evidence)[:2000] or '未提供可核实的岗位相关资料',
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
        old = db.execute('SELECT * FROM hr_sourcing_runs WHERE id=? AND owner=?', (record, owner)).fetchone()
        previous, source = {}, {}
        if old:
            if old['job'] != job['id']: raise pool.Conflict('该请求已用于其他职位')
            previous = json.loads(old['result'])
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
                    'candidates': [{'id': cid, 'evidence': value['evidenceText']} for cid, value in parsed.items()]}
        progress = {**previous, 'batches': batch, 'pagesRead': max(page, previous.get('pagesRead', 0)),
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
            suggestions.append(f'“{condition["text"]}”有 {unknown} 人的卡片未说明。建议改为卡片中可验证的工作经历或具体技能，细节留待简历核实。')
        elif unmatched:
            suggestions.append(f'“{condition["text"]}”有 {unmatched} 人明确不符。请核对是否确为必要条件；相近岗位或可迁移经历可写入条件。')
        if len(suggestions) == 2:
            break
    suggestions.append('建议保留 2–3 条核心工作能力，可替代技能明确写“满足其中一项即可”，并核对当前关联职位及 JD。修改后需由 HR 确认保存再寻找。')
    return suggestions


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
        for row in rows:
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
                if item['score'] >= MATCH_THRESHOLD and item.get('matchState', 'matched') == 'matched':
                    matched[item['id']] = {**item, 'name': source[item['id']]['name'],
                                           'canGreet': source[item['id']]['canGreet']}
            ranked = sorted(matched.values(), key=lambda row: -row['score'])
            condition_stats = previous.get('conditionStats', {})
            for item in rows:
                for check in item.get('checks', []):
                    counts = condition_stats.setdefault(check['criterionId'], {})
                    counts[check['status']] = counts.get(check['status'], 0) + 1
            result = {**previous, 'matched': ranked[:run['wanted']], 'candidates': ranked[:run['wanted']],
                      'conditionStats': condition_stats,
                      'examined': previous.get('examined', 0) + len(snapshot['candidates']),
                      'qualified': previous.get('qualified', 0) + sum(r['score'] >= MATCH_THRESHOLD and r.get('matchState', 'matched') == 'matched' for r in rows),
                      'needsInfo': previous.get('needsInfo', 0) + sum(r.get('matchState') == 'needs_info' for r in rows),
                      'belowThreshold': previous.get('belowThreshold', 0) + sum(r.get('matchState') in ('below_threshold', 'required_mismatch') for r in rows),
                      'reviewExamples': [{**r, 'name': source[r['id']]['name']} for r in
                        sorted((r for r in rows if r.get('matchState') != 'matched'), key=lambda r: -r['score'])[:3]],
                      'referenceOnly': True}
            if result['examined'] >= 100 and result['qualified'] == 0:
                result.update(stopped=True, stopReason='no_matches_100',
                              suggestions=no_match_suggestions(snapshot.get('criteria', []), condition_stats))
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
        if old: return {'id': old['id'], 'status': old['status']}
        selected = db.execute('SELECT * FROM hr_sourcing_candidates WHERE id=? AND owner=? AND job=?', (cid, owner, job['id'])).fetchone()
        if not selected or time.time()-selected['read_at']>1800: raise ValueError('候选人卡片已过期，请重新获取后再联系')
        detail = json.loads(selected['body'])
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
        return {'id': record, 'status': 'queued'}


def device_contacts(db, owner, key):
    schema(db); expire(db)
    return [{**json.loads(r['body']), 'id': r['id'], 'status': r['status'], 'friendId': r['friend_id']}
            for r in db.execute("SELECT * FROM hr_sourcing_contacts WHERE owner=? AND account=? AND status IN ('queued','sent') ORDER BY created_at", (owner, key))]


def claim(db, owner, key, record):
    schema(db)
    row = db.execute("SELECT * FROM hr_sourcing_contacts WHERE id=? AND owner=? AND account=? AND status='queued'", (record, owner, key)).fetchone()
    if not row: return None
    if time.time()-json.loads(row['body'])['readAt']>1800:
        db.execute("UPDATE hr_sourcing_contacts SET status='expired',updated_at=? WHERE id=?", (now(), record))
        return {'claimed': False}
    db.execute("UPDATE hr_sourcing_contacts SET status='sending',updated_at=? WHERE id=?", (now(), record))
    return {'claimed': True}


def report(db, owner, key, body):
    schema(db)
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
