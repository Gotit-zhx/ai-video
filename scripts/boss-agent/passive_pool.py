"""Opt-in per-job resume workflow. Human decisions only; never calls BOSS."""
import base64
import datetime
import hashlib
import json
import re
import threading
import time
import uuid
import urllib.request
import urllib.error

import talent_pool as pool

AI_URL = 'http://127.0.0.1:8080/api/v1/recruitment-ai/resume-match'
DEFAULT_REJECTION = '您好，感谢您对{职位}岗位的关注和耐心沟通。经过综合评估，您的经历与我们目前岗位的需求暂未完全匹配，本次先不安排后续面试。感谢您的理解，祝您求职顺利！'
KINDS = {'reject', 'invite', 'general'}


def now(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def dump(value): return json.dumps(value, ensure_ascii=False)
def uid(value):
    value = pool.text(value, 80)
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', value): raise ValueError('记录标识不正确')
    return value

def account(value):
    value = pool.text(value, 64)
    if not re.fullmatch(r'[a-f0-9]{64}', value): raise ValueError('请先连接并确认本机招聘账号')
    return value


def schema(db):
    db.executescript('''
    CREATE TABLE IF NOT EXISTS hr_speeches(
      id TEXT PRIMARY KEY,owner TEXT NOT NULL,name TEXT NOT NULL,kind TEXT NOT NULL,
      content TEXT NOT NULL,active INTEGER NOT NULL,version INTEGER NOT NULL,updated_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS hr_passive_rules(
      id TEXT PRIMARY KEY,owner TEXT NOT NULL,account TEXT NOT NULL,job_id TEXT NOT NULL,
      body TEXT NOT NULL,enabled INTEGER NOT NULL,enabled_at INTEGER NOT NULL,version INTEGER NOT NULL,
      updated_at TEXT NOT NULL,UNIQUE(owner,account,job_id));
    CREATE TABLE IF NOT EXISTS hr_resume_pool(
      id TEXT PRIMARY KEY,owner TEXT NOT NULL,account TEXT NOT NULL,friend_id TEXT NOT NULL,
      rule_id TEXT NOT NULL,message_id TEXT NOT NULL,body TEXT NOT NULL,file_key TEXT NOT NULL,
      file_hash TEXT NOT NULL,status TEXT NOT NULL DEFAULT 'pending',version INTEGER NOT NULL DEFAULT 1,
      ai_state TEXT NOT NULL DEFAULT 'idle',ai_result TEXT NOT NULL DEFAULT '{}',ai_error TEXT NOT NULL DEFAULT '',
      ai_job TEXT NOT NULL DEFAULT '',ai_at INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL,updated_at TEXT NOT NULL,
      UNIQUE(owner,account,friend_id,message_id));
    CREATE TABLE IF NOT EXISTS hr_resume_commands(
      id TEXT PRIMARY KEY,owner TEXT NOT NULL,account TEXT NOT NULL,resume_id TEXT NOT NULL UNIQUE,
      friend_id TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL,updated_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS hr_passive_events(
      id TEXT PRIMARY KEY,owner TEXT NOT NULL,account TEXT NOT NULL,body TEXT NOT NULL,created_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS hr_speech_defaults(
      owner TEXT PRIMARY KEY,speech_id TEXT NOT NULL);
    ''')
    # executescript commits implicitly. Start a fresh transaction for every caller.
    db.execute('BEGIN IMMEDIATE')


def speech(row):
    return {**dict(row), 'active': bool(row['active'])}


def rule(row):
    return {**json.loads(row['body']), 'id': row['id'], 'accountKey': row['account'],
            'jobId': row['job_id'], 'enabled': bool(row['enabled']), 'enabledAt': row['enabled_at'], 'version': row['version']}


def resume(row, commands=None):
    value = {**json.loads(row['body']), 'id': row['id'], 'status': row['status'], 'version': row['version'],
             'accountKey': row['account'], 'friendId': row['friend_id'], 'createdAt': row['created_at'],
             'aiState': row['ai_state'], 'aiResult': json.loads(row['ai_result']), 'aiError': row['ai_error'],
             'fileUrl': '/api/v1/hr/resume-pool/file?id=' + row['id']}
    if commands:
        value['rejection'] = commands.get(row['id'])
    return value


def expire_commands(db):
    # A lease/claim response can be lost while the connector stays alive.
    # Never return these commands to the send queue automatically.
    cutoff = (datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(minutes=10)).isoformat()
    db.execute("UPDATE hr_resume_commands SET status='uncertain',updated_at=? WHERE status='sending' AND updated_at<?", (now(), cutoff))


def listing(user, kind, query):
    owner, _ = pool.context(user)
    with pool.database() as db:
        schema(db)
        expire_commands(db)
        if kind == 'speeches':
            selected = db.execute('SELECT speech_id FROM hr_speech_defaults WHERE owner=?', (owner,)).fetchone()
            return {'items': [speech(r) | {'isDefault': bool(selected and selected['speech_id'] == r['id'])} for r in db.execute('SELECT * FROM hr_speeches WHERE owner=? ORDER BY updated_at DESC', (owner,))],
                    'suggestedRejection': DEFAULT_REJECTION}
        if kind == 'passive':
            return {'items': [rule(r) for r in db.execute('SELECT * FROM hr_passive_rules WHERE owner=?', (owner,))]}
        db.execute("UPDATE hr_resume_pool SET ai_state='failed',ai_error='分析等待超时，请手动重试' WHERE owner=? AND ai_state='running' AND ai_at<?", (owner, int(time.time())-360))
        clause, args = 'owner=?', [owner]
        if query.get('id'):
            clause += ' AND id=?'; args.append(uid(query['id']))
        elif query.get('status'):
            if query['status'] not in {'pending', 'approved', 'rejected'}: raise ValueError('审批状态不正确')
            clause += ' AND status=?'; args.append(query['status'])
        total = db.execute('SELECT COUNT(*) FROM hr_resume_pool WHERE '+clause, args).fetchone()[0]
        pages = max(1, (total+19)//20)
        page = min(pages, max(1, int(query.get('page', 1))))
        rows = list(db.execute('SELECT * FROM hr_resume_pool WHERE '+clause+' ORDER BY created_at DESC,id LIMIT 20 OFFSET ?', (*args, (page-1)*20)))
        commands = {r['resume_id']: {'id': r['id'], 'status': r['status'], 'updatedAt': r['updated_at'], **json.loads(r['body'])}
                    for r in db.execute('SELECT * FROM hr_resume_commands WHERE owner=?', (owner,))}
        items = [resume(r, commands) for r in rows]
        if not query.get('id'):
            for item in items: item.pop('resumeText', None)
        return {'items': items, 'total': total, 'page': page, 'pages': pages,
                'events': [json.loads(r['body']) | {'createdAt': r['created_at']} for r in db.execute('SELECT * FROM hr_passive_events WHERE owner=? ORDER BY created_at DESC LIMIT 30', (owner,))]}


def save_speech(user, body):
    owner, actor = pool.context(user)
    name, content, kind = pool.text(body.get('name'), 80), pool.text(body.get('content'), 1000), body.get('kind')
    if not name or not content or kind not in KINDS: raise ValueError('请填写名称、类型和话术正文')
    if set(re.findall(r'\{([^{}]+)\}', content)) - {'姓名', '职位'}: raise ValueError('模板仅支持 {姓名} 和 {职位}')
    record = uid(body['id']) if body.get('id') else str(uuid.uuid4())
    with pool.database() as db:
        schema(db)
        old = db.execute('SELECT * FROM hr_speeches WHERE id=? AND owner=?', (record, owner)).fetchone()
        if body.get('id') and not old: raise ValueError('话术不存在')
        if old and body.get('version') != old['version']: raise pool.Conflict('话术已修改，请刷新')
        version = old['version']+1 if old else 1
        active = body.get('active', True) is True
        if body.get('isDefault') is True and (kind != 'reject' or not active):
            raise ValueError('默认婉拒话术必须启用且类型为婉拒')
        db.execute('INSERT OR REPLACE INTO hr_speeches VALUES(?,?,?,?,?,?,?,?)', (record, owner, name, kind, content, int(body.get('active', True) is True), version, now()))
        previous = db.execute('SELECT speech_id FROM hr_speech_defaults WHERE owner=?', (owner,)).fetchone()
        if body.get('isDefault') is True:
            db.execute('INSERT OR REPLACE INTO hr_speech_defaults VALUES(?,?)', (owner, record))
        elif (body.get('isDefault') is False or not active or kind != 'reject') and previous and previous['speech_id'] == record:
            db.execute('DELETE FROM hr_speech_defaults WHERE owner=?', (owner,))
        selected = db.execute('SELECT speech_id FROM hr_speech_defaults WHERE owner=?', (owner,)).fetchone()
        if (previous['speech_id'] if previous else None) != (selected['speech_id'] if selected else None):
            pool.event(db, owner, actor, record, 'speech.default', dict(previous) if previous else None, dict(selected) if selected else {})
        result = speech(db.execute('SELECT * FROM hr_speeches WHERE id=?', (record,)).fetchone())
        result['isDefault'] = bool(selected and selected['speech_id'] == record)
        pool.event(db, owner, actor, record, 'speech.save', speech(old) if old else None, result)
        return result


def save_rule(user, body):
    owner, actor = pool.context(user)
    key, job_id = account(body.get('accountKey')), pool.text(body.get('jobId'), 180)
    if not job_id or not re.fullmatch(r'[\w~-]+', job_id): raise ValueError('职位标识不正确')
    enabled = body.get('enabled') is True
    clean = {k: pool.text(body.get(k, ''), limit) for k, limit in {'jobName': 200, 'description': 10000, 'education': 100, 'experience': 100, 'projectId': 80}.items()}
    if not clean['jobName']: raise ValueError('职位名称不能为空')
    with pool.database() as db:
        schema(db)
        old = db.execute('SELECT * FROM hr_passive_rules WHERE owner=? AND account=? AND job_id=?', (owner, key, job_id)).fetchone()
        if old and body.get('version') != old['version']: raise pool.Conflict('开关已变化，请刷新')
        if clean['projectId'] and not db.execute('SELECT 1 FROM projects WHERE owner=? AND id=?', (owner, clean['projectId'])).fetchone(): raise ValueError('项目组不存在')
        record, version = (old['id'], old['version']+1) if old else (str(uuid.uuid4()), 1)
        since = old['enabled_at'] if old and old['enabled'] and enabled else int(time.time())
        db.execute('INSERT OR REPLACE INTO hr_passive_rules VALUES(?,?,?,?,?,?,?,?,?)', (record, owner, key, job_id, dump(clean), int(enabled), since, version, now()))
        result = rule(db.execute('SELECT * FROM hr_passive_rules WHERE id=?', (record,)).fetchone())
        pool.event(db, owner, actor, record, 'passive.enable' if enabled else 'passive.disable', rule(old) if old else None, result)
        return result


def device_config(user, body):
    owner, _ = pool.context(user); key = account(body.get('accountKey'))
    with pool.database() as db:
        schema(db)
        expire_commands(db)
        return {'rules': [rule(r) for r in db.execute('SELECT * FROM hr_passive_rules WHERE owner=? AND account=? AND enabled=1', (owner, key))],
                'commands': [{**json.loads(r['body']), 'id': r['id'], 'friendId': r['friend_id']} for r in db.execute("SELECT * FROM hr_resume_commands WHERE owner=? AND account=? AND status='queued' ORDER BY updated_at LIMIT 1", (owner, key))]}


def ingest(user, body):
    import resume_storage
    owner, actor = pool.context(user); key = account(body.get('accountKey'))
    friend, mid, rule_id = str(body.get('friendId', '')), str(body.get('messageId', '')), uid(body.get('ruleId'))
    if not all(re.fullmatch(r'[1-9]\d{0,19}', x) for x in [friend, mid]): raise ValueError('简历会话标识无效')
    raw = base64.b64decode(body.get('fileBase64', ''), validate=True)
    extension = pool.text(body.get('extension', ''), 8).lower().lstrip('.')
    resume_storage.validate(raw, extension)
    digest = hashlib.sha256(raw).hexdigest()
    record = str(uuid.uuid5(uuid.NAMESPACE_URL, f'hr-resume:{owner}:{key}:{friend}:{mid}'))
    with pool.database() as db:
        schema(db)
        existing = db.execute('SELECT * FROM hr_resume_pool WHERE id=? AND owner=?', (record, owner)).fetchone()
        if existing:
            if existing['file_hash'] != digest: raise pool.Conflict('同一简历消息的文件内容发生变化')
            return {'id': record, 'stored': True}
        source = db.execute('SELECT * FROM hr_passive_rules WHERE id=? AND owner=? AND account=?', (rule_id, owner, key)).fetchone()
        if not source: raise ValueError('简历未关联当前账号的招聘职位')
        job = rule(source)
    # Retry-safe object key, private ACL. No arbitrary URLs accepted from clients.
    # Different bytes for the same message must never overwrite the object
    # referenced by a concurrent successful ingestion.
    object_id = str(uuid.uuid5(uuid.NAMESPACE_URL, record + ':' + digest))
    location = resume_storage.store(owner, object_id, raw, extension)
    extracted, extraction_note = resume_storage.extract(raw, extension)
    clean = {k: pool.text(body.get(k, ''), limit) for k, limit in {'name': 100, 'education': 100, 'experience': 100, 'city': 100, 'company': 200}.items()}
    clean.update(jobName=job['jobName'], jobDescription=job['description'], jobEducation=job['education'], jobExperience=job['experience'],
                 projectId=job['projectId'], fileName=f'简历-{record[:8]}.{extension}', extension=extension,
                 resumeText=extracted, extractionNote=extraction_note, size=len(raw))
    with pool.database() as db:
        schema(db)
        inserted = db.execute('''INSERT OR IGNORE INTO hr_resume_pool(id,owner,account,friend_id,rule_id,message_id,body,file_key,file_hash,created_at,updated_at)
                      VALUES(?,?,?,?,?,?,?,?,?,?,?)''', (record, owner, key, friend, rule_id, mid, dump(clean), location, digest, now(), now()))
        saved = db.execute('SELECT file_hash FROM hr_resume_pool WHERE id=? AND owner=?', (record, owner)).fetchone()
        if not saved or saved['file_hash'] != digest: raise pool.Conflict('同一简历消息的文件内容发生变化')
        if inserted.rowcount:
            pool.event(db, owner, actor, record, 'resume.receive', None, {'fileHash': digest, 'ruleId': rule_id})
    return {'id': record, 'stored': True}


def get_file(user, record):
    import resume_storage
    owner, _ = pool.context(user)
    with pool.database() as db:
        schema(db)
        row = db.execute('SELECT * FROM hr_resume_pool WHERE owner=? AND id=?', (owner, uid(record))).fetchone()
        if not row: raise ValueError('简历不存在')
        metadata, key = json.loads(row['body']), row['file_key']
    return resume_storage.download(owner, key), metadata['fileName'], metadata['extension']


def review(user, body):
    owner, actor = pool.context(user); record = uid(body.get('id')); action = body.get('action')
    if action not in {'approve', 'reject', 'cancel-send'}: raise ValueError('审批操作不正确')
    with pool.database() as db:
        schema(db)
        row = db.execute('SELECT * FROM hr_resume_pool WHERE owner=? AND id=?', (owner, record)).fetchone()
        if not row: raise ValueError('简历不存在')
        if action == 'cancel-send':
            changed = db.execute("UPDATE hr_resume_commands SET status='cancelled',updated_at=? WHERE owner=? AND resume_id=? AND status='queued'", (now(), owner, record)).rowcount
            if not changed: raise pool.Conflict('发送已开始或已完成，不能取消')
            pool.event(db, owner, actor, record, 'resume.reject.cancel', None, {})
            return {'cancelled': True}
        if row['status'] != 'pending': raise pool.Conflict('该简历已审批，请刷新查看结果')
        if row['version'] != body.get('version'): raise pool.Conflict('简历已更新，请刷新后审批')
        detail = json.loads(row['body']); detail['reviewedBy'] = actor; detail['reviewedAt'] = now()
        if action == 'approve':
            project = pool.text(body.get('projectId', detail.get('projectId', '')), 80)
            if project and not db.execute('SELECT 1 FROM projects WHERE owner=? AND id=?', (owner, project)).fetchone(): raise ValueError('项目组不存在')
            source = f"passive:{row['account']}:{row['friend_id']}"
            old = db.execute('SELECT * FROM talents WHERE owner=? AND source_id=?', (owner, source)).fetchone()
            talent_id = old['id'] if old else str(uuid.uuid4())
            value = json.loads(old['body']) if old else {k: '' for k in pool.FIELDS}
            value.update({k: detail.get(k, '') for k in ['name', 'education', 'experience', 'city', 'company']})
            value.update(position=detail['jobName'], source='passive', sourceId=source, notes='简历池审批通过；原简历记录：'+record)
            if old:
                db.execute("UPDATE talents SET project=?,stage='invited',body=?,version=version+1,updated_at=? WHERE id=? AND owner=?", (project, dump(value), now(), talent_id, owner))
            else:
                db.execute('INSERT INTO talents VALUES(?,?,?,?,?,?,?,?,?)', (talent_id, owner, project, 'invited', source, dump(value), 1, now(), now()))
            detail['talentId'] = talent_id
            pool.event(db, owner, actor, talent_id, 'talent.resume-approve', pool.view(old) if old else None, value | {'stage': 'invited'})
            status = 'approved'
        else:
            template = db.execute('''SELECT s.* FROM hr_speeches s JOIN hr_speech_defaults d
                ON d.speech_id=s.id AND d.owner=s.owner
                WHERE s.owner=? AND s.active=1 AND s.kind='reject' ''', (owner,)).fetchone()
            if not template: raise ValueError('请先在话术管理中设置并启用默认婉拒话术')
            # Resolve the configured template on the server at the human review
            # transaction. The client and AI cannot replace the outgoing text.
            values = {'姓名': detail.get('name') or '您好', '职位': detail['jobName']}
            message = re.sub(r'\{(姓名|职位)\}', lambda match: values[match[1]], template['content'])
            if len(message) > 1000: raise ValueError('替换后的婉拒话术过长，请在话术管理中缩短内容')
            command = str(uuid.uuid4())
            payload = {'message': message, 'name': detail.get('name', ''), 'jobName': detail['jobName'], 'reviewedBy': actor,
                       'templateId': template['id'], 'templateVersion': template['version'], 'templateName': template['name'], 'resumeId': record}
            db.execute('INSERT INTO hr_resume_commands VALUES(?,?,?,?,?,?,?,?)', (command, owner, row['account'], record, row['friend_id'], dump(payload), 'queued', now()))
            status = 'rejected'
        db.execute('UPDATE hr_resume_pool SET status=?,body=?,version=version+1,updated_at=? WHERE owner=? AND id=?', (status, dump(detail), now(), owner, record))
        pool.event(db, owner, actor, record, 'resume.'+action, {'status': row['status']}, detail | {'status': status})
        return {'id': record, 'status': status, 'talentId': detail.get('talentId'),
                'rejectionStatus': 'queued' if action == 'reject' else None}


def claim_command(user, body):
    owner, _ = pool.context(user); key = account(body.get('accountKey')); record = uid(body.get('id'))
    with pool.database() as db:
        schema(db)
        changed = db.execute("UPDATE hr_resume_commands SET status='sending',updated_at=? WHERE id=? AND owner=? AND account=? AND status='queued'", (now(), record, owner, key)).rowcount
        if not changed: return {'claimed': False}
        return {'claimed': True}


def device_report(user, body):
    owner, _ = pool.context(user); key = account(body.get('accountKey'))
    if body.get('commandId'):
        state = body.get('status')
        if state not in {'sent', 'uncertain', 'failed'}: raise ValueError('发送状态不正确')
        with pool.database() as db:
            schema(db)
            db.execute("UPDATE hr_resume_commands SET status=?,updated_at=? WHERE id=? AND owner=? AND account=? AND status IN ('sending','uncertain')", (state, now(), uid(body['commandId']), owner, key))
    event = body.get('event')
    if isinstance(event, dict):
        clean = {k: pool.text(event.get(k, ''), limit) for k, limit in {'id': 80, 'code': 80, 'name': 100, 'jobName': 200, 'message': 400}.items()}
        if clean['id']:
            with pool.database() as db:
                schema(db)
                db.execute('INSERT OR IGNORE INTO hr_passive_events VALUES(?,?,?,?,?)', (clean['id'], owner, key, dump(clean), now()))
    return {'recorded': True}


def analyze(user, body, cookie):
    owner, actor = pool.context(user); record = uid(body.get('id'))
    with pool.database() as db:
        schema(db)
        row = db.execute('SELECT * FROM hr_resume_pool WHERE owner=? AND id=?', (owner, record)).fetchone()
        if not row: raise ValueError('简历不存在')
        if row['ai_state'] == 'running': return {'id': record, 'aiState': 'running'}
        if row['ai_state'] == 'complete' and body.get('regenerate') is not True: return resume(row)
        if db.execute("SELECT 1 FROM hr_resume_pool WHERE owner=? AND ai_state='running' AND ai_at>?", (owner, int(time.time())-360)).fetchone(): raise pool.Conflict('已有简历正在分析，请稍后')
        detail = json.loads(row['body'])
        text = pool.text(body.get('resumeText', detail.get('resumeText', '')), 60000)
        if len(text) < 30: raise ValueError('未提取到足够正文，请查看原件并补充简历文字后分析')
        description = pool.text(body.get('jobDescription', detail.get('jobDescription', '')), 10000)
        if not description: raise ValueError('该职位缺少岗位职责，请补充职位资料后分析')
        job = str(uuid.uuid4()); snapshot = {'resumeText': text, 'jobName': detail['jobName'], 'jobDescription': description, 'education': detail['jobEducation'], 'experience': detail['jobExperience']}
        db.execute("UPDATE hr_resume_pool SET ai_state='running',ai_job=?,ai_at=?,ai_error='' WHERE owner=? AND id=?", (job, int(time.time()), owner, record))
        pool.event(db, owner, actor, record, 'resume.ai-request', None, {'jobId': job})
    threading.Thread(target=analyze_worker, args=(owner, record, job, snapshot, cookie), daemon=True).start()
    return {'id': record, 'aiState': 'running'}


def analyze_worker(owner, record, job, snapshot, cookie):
    state, value, error = 'failed', {}, ''
    try:
        request = urllib.request.Request(AI_URL, data=dump(snapshot).encode(), headers={'Content-Type': 'application/json', 'Cookie': cookie})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=320) as response:
            raw = response.read(512001)
            if len(raw) > 512000: raise ValueError('oversize')
            value = json.loads(raw)['data']
        if not isinstance(value.get('score'), (int, float)) or not 0 <= value['score'] <= 100: raise ValueError('invalid result')
        state = 'complete'
    except Exception:
        error = 'AI 分析未完成，请检查驱动模型配置或稍后手动重试；审批不受影响'
    with pool.database() as db:
        schema(db)
        db.execute("UPDATE hr_resume_pool SET ai_state=?,ai_result=?,ai_error=? WHERE owner=? AND id=? AND ai_job=? AND ai_state='running'", (state, dump(value), error, owner, record, job))


def recover():
    with pool.database() as db:
        schema(db)
        db.execute("UPDATE hr_resume_pool SET ai_state='failed',ai_error='服务已重启，请手动重新分析' WHERE ai_state='running'")
        db.execute("UPDATE hr_resume_commands SET status='uncertain' WHERE status='sending'")
