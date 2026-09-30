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
import uuid

import talent_pool as pool
from passive_pool import account, dump, now, uid

AI_BASE = 'http://127.0.0.1:8080/api/v1/recruitment-ai/'
MAX_UPLOAD = 10 * 1024 * 1024


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
        '''CREATE TABLE IF NOT EXISTS hr_sourcing_contacts(
          id TEXT PRIMARY KEY,owner TEXT NOT NULL,account TEXT NOT NULL,job TEXT NOT NULL,
          candidate TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL,
          friend_id TEXT NOT NULL DEFAULT '',created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
          UNIQUE(owner,account,job,candidate))''',
    ):
        db.execute(sql)


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


def view(db, row):
    refs = [dict(r) for r in db.execute('SELECT id,filename,body,created_at FROM hr_sourcing_refs WHERE owner=? AND job=? ORDER BY created_at,id', (row['owner'], row['id']))]
    for item in refs:
        data = json.loads(item.pop('body'))
        item.update(characters=len(data['text']), note=data.get('note', ''))
    runs = list(db.execute('SELECT * FROM hr_sourcing_runs WHERE owner=? AND job=? ORDER BY started DESC,id LIMIT 1', (row['owner'], row['id'])))
    run = None
    if runs:
        r = runs[0]
        run = {'id': r['id'], 'state': r['state'], 'error': r['error'], 'startedAt': r['started'],
               'wanted': r['wanted'], 'version': r['version'], **json.loads(r['result'])}
        for item in run.get('candidates', []):
            contact = db.execute('SELECT id,status,friend_id FROM hr_sourcing_contacts WHERE owner=? AND job=? AND candidate=?',
                                 (row['owner'], row['id'], item['id'])).fetchone()
            item['contact'] = dict(contact) if contact else None
    return {**json.loads(row['body']), 'id': row['id'], 'jobId': row['job_id'], 'accountKey': row['account'],
            'version': row['version'], 'profileState': row['profile_state'], 'profile': json.loads(row['profile']),
            'profileError': row['profile_error'], 'profileStartedAt': row['profile_at'], 'references': refs, 'run': run}


def listing(user, query):
    owner, _ = pool.context(user)
    with pool.database() as db:
        schema(db); expire(db)
        if query.get('id'):
            return view(db, get_job(db, owner, query))
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
                db.execute("UPDATE hr_sourcing_jobs SET body=?,version=version+1,profile_state='stale',updated_at=? WHERE id=?",
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
            db.execute("UPDATE hr_sourcing_jobs SET version=version+1,profile_state='stale' WHERE id=?", (job['id'],))
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
            db.execute("UPDATE hr_sourcing_jobs SET version=version+1,profile_state='stale',updated_at=? WHERE id=?", (now(), job['id']))
            pool.event(db, owner, actor, record, 'sourcing.reference', None, {'job': job['id'], 'digest': digest})
        return view(db, get_job(db, owner, body))


def driver(kind, snapshot, cookie):
    request = urllib.request.Request(AI_BASE+kind, data=dump(snapshot).encode(),
                                     headers={'Content-Type': 'application/json', 'Cookie': cookie})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=320) as response:
        raw = response.read(512001)
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
    if any(len(value)>600 for value in identifiers.values()): return None
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
    with pool.database() as db:
        schema(db); expire(db); job = get_job(db, owner, body)
        old = db.execute('SELECT * FROM hr_sourcing_runs WHERE id=? AND owner=?', (record, owner)).fetchone()
        if old:
            if old['job'] != job['id']: raise pool.Conflict('该请求已用于其他职位')
            return view(db, job)
        if job['profile_state'] != 'complete' or job['version'] != body.get('version'):
            raise ValueError('请先生成当前职位的寻源画像')
        if db.execute("SELECT 1 FROM hr_sourcing_runs WHERE owner=? AND state='running'", (owner,)).fetchone():
            raise pool.Conflict('已有寻源分析在执行，请等待完成')
        parsed = {}
        for entry in raw:
            value = candidate(entry, job['job_id'])
            if not value: continue
            cid = str(uuid.uuid5(uuid.NAMESPACE_URL, f'hr-source-candidate:{owner}:{job["id"]}:{value["geekId"]}'))
            value['id'] = cid; parsed[cid] = value
        if not parsed: raise ValueError('未获取到属于该职位的可用候选人，本次未调用 AI')
        for cid, value in parsed.items():
            db.execute('INSERT OR REPLACE INTO hr_sourcing_candidates VALUES(?,?,?,?,?,?)',
                       (cid, owner, job['id'], value['geekId'], dump(value), time.time()))
        snapshot = {'jobDescription': json.loads(job['body'])['description'], 'profile': job['profile'],
                    'candidates': [{'id': cid, 'evidence': value['evidenceText']} for cid, value in parsed.items()]}
        db.execute('INSERT INTO hr_sourcing_runs(id,owner,job,version,wanted,state,body,started,updated_at) VALUES(?,?,?,?,?,\'running\',?,?,?)',
                   (record, owner, job['id'], job['version'], wanted, dump(list(parsed.values())), time.time(), now()))
        pool.event(db, owner, actor, record, 'sourcing.search-request', None, {'job': job['id'], 'count': wanted, 'examined': len(parsed)})
    threading.Thread(target=search_worker, args=(owner, record, snapshot, cookie), daemon=True).start()
    return listing(user, body)


def search_worker(owner, record, snapshot, cookie):
    try:
        result = driver('sourcing-match', snapshot, cookie)
        rows = result.get('candidates')
        expected = {item['id'] for item in snapshot['candidates']}
        if not isinstance(rows, list) or len(rows) != len(expected) or {r.get('id') for r in rows} != expected:
            raise ValueError('candidate mismatch')
        if any(type(r.get('score')) not in (int, float) or not math.isfinite(r['score']) or not 0 <= r['score'] <= 100 for r in rows):
            raise ValueError('invalid score')
        error = ''
    except Exception:
        result, error = {}, 'AI 寻源分析未完成，请手动重试；未发送任何招呼'
    with pool.database() as db:
        schema(db)
        run = db.execute('SELECT * FROM hr_sourcing_runs WHERE id=? AND owner=?', (record, owner)).fetchone()
        if not run or run['state'] != 'running': return
        if not error:
            source = {v['id']: v for v in json.loads(run['body'])}
            ranked = sorted(result['candidates'], key=lambda r: -r['score'])[:run['wanted']]
            result['candidates'] = [{**r, 'name': source[r['id']]['name'], 'canGreet': source[r['id']]['canGreet']} for r in ranked]
            result['examined'] = len(source); result['referenceOnly'] = True
        db.execute('UPDATE hr_sourcing_runs SET state=?,result=?,error=?,updated_at=? WHERE id=?',
                   ('failed' if error else 'complete', dump(result), error, now(), record))


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
