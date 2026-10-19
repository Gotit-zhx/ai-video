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
import hr_account_identity as identities

AI_URL = 'http://127.0.0.1:8080/api/v1/recruitment-ai/resume-match'
DEFAULT_REJECTION = '您好，感谢您对{职位}岗位的关注和耐心沟通。经过综合评估，您的经历与我们目前岗位的需求暂未完全匹配，本次先不安排后续面试。感谢您的理解，祝您求职顺利！'
KINDS = {'reject', 'invite', 'general', 'passive_reply', 'active_greet'}


def now(): return datetime.datetime.now(datetime.timezone.utc).isoformat()
def dump(value): return json.dumps(value, ensure_ascii=False)
def uid(value):
    value = pool.text(value, 80)
    if not re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', value): raise ValueError('记录标识不正确')
    return value

def account(value, owner=None):
    value = pool.text(value, 64)
    if not re.fullmatch(r'[a-f0-9]{64}', value): raise ValueError('请先连接并确认本机招聘账号')
    return identities.resolve(owner, value) if owner else value


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
    CREATE TABLE IF NOT EXISTS hr_talent_messages(
      id TEXT PRIMARY KEY,owner TEXT NOT NULL,account TEXT NOT NULL,talent_id TEXT NOT NULL,
      friend_id TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL,updated_at TEXT NOT NULL);
    CREATE INDEX IF NOT EXISTS hr_talent_message_queue ON hr_talent_messages(owner,account,status);
    CREATE TABLE IF NOT EXISTS hr_passive_events(
      id TEXT PRIMARY KEY,owner TEXT NOT NULL,account TEXT NOT NULL,body TEXT NOT NULL,created_at TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS hr_speech_defaults(
      owner TEXT PRIMARY KEY,speech_id TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS hr_passive_reply_defaults(
      owner TEXT PRIMARY KEY,speech_id TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS hr_job_availability(
      owner TEXT NOT NULL,account TEXT NOT NULL,body TEXT NOT NULL,checked_at REAL NOT NULL,
      PRIMARY KEY(owner,account));
    ''')
    # executescript commits implicitly. Start a fresh transaction for every caller.
    db.execute('BEGIN IMMEDIATE')
    identities.schema(db)
    if not db.execute("SELECT 1 FROM sqlite_master WHERE name='hr_resume_receipt_sync'").fetchone():
        db.execute('''CREATE TABLE hr_resume_receipt_sync(
            seq INTEGER PRIMARY KEY AUTOINCREMENT,resume_id TEXT NOT NULL UNIQUE)''')
        db.execute('''INSERT INTO hr_resume_receipt_sync(resume_id)
            SELECT id FROM hr_resume_pool ORDER BY rowid''')
        db.execute('''CREATE TRIGGER hr_resume_receipt_added AFTER INSERT ON hr_resume_pool
            BEGIN INSERT INTO hr_resume_receipt_sync(resume_id) VALUES(NEW.id); END''')


def speech(row):
    return {**dict(row), 'active': bool(row['active'])}


def rule(row):
    return {**json.loads(row['body']), 'id': row['id'], 'accountKey': row['account'],
            'jobId': row['job_id'], 'enabled': bool(row['enabled']), 'enabledAt': row['enabled_at'], 'version': row['version']}


def resume(row, commands=None):
    value = {'origin': 'passive', **json.loads(row['body']), 'id': row['id'], 'status': row['status'], 'version': row['version'],
             'accountKey': row['account'], 'friendId': row['friend_id'], 'createdAt': row['created_at'],
             'aiState': row['ai_state'], 'aiResult': json.loads(row['ai_result']), 'aiError': row['ai_error'],
             'fileUrl': '/api/v1/hr/resume-pool/file?id=' + row['id']}
    if commands:
        value['rejection'] = commands.get(row['id'])
    return value


def sync_jobs(user, body):
    """Persist a complete official desktop job snapshot, scoped to this account."""
    owner, _ = pool.context(user)
    key = account(body.get('accountKey'), owner)
    with pool.database() as db:
        schema(db)
        if body.get('invalid') is True:
            db.execute('DELETE FROM hr_job_availability WHERE owner=? AND account=?', (owner, key))
            return {'synced': False}
        snapshot = body.get('snapshot')
        if (not isinstance(snapshot, dict) or snapshot.get('hasMore') is not False
                or snapshot.get('source') != 'job-management' or not isinstance(snapshot.get('jobs'), list)
                or len(snapshot['jobs']) > 1000):
            raise ValueError('职位列表未完整读取，不能更新操作权限')
        states = {}
        for job in snapshot['jobs']:
            if not isinstance(job, dict):
                raise ValueError('职位状态格式不正确')
            identity = pool.text(job.get('encryptJobId'), 180)
            if not identity or identity in states:
                raise ValueError('职位标识缺失或重复')
            # Use the official numeric fields, never the caller's "canReview".
            audit, state = job.get('jobAuditStatus'), job.get('jobStatus')
            opened = (type(audit) is int and type(state) is int and audit != 6
                      and not (state == 0 and audit == 0) and state in (0, 2)
                      and audit in (0, 1, 3, 4) and job.get('statusDesc') == '开放中')
            states[identity] = {'open': opened, 'status': pool.text(job.get('statusDesc'), 40) or '状态待确认'}
        db.execute('INSERT OR REPLACE INTO hr_job_availability VALUES(?,?,?,?)',
                   (owner, key, dump(states), time.time()))
    return {'synced': True}


def job_availability(db, row):
    detail = json.loads(row['body'])
    job_id = detail.get('jobId', '')
    if not job_id:
        linked = db.execute('SELECT job_id FROM hr_passive_rules WHERE owner=? AND account=? AND id=?',
                            (row['owner'], row['account'], row['rule_id'])).fetchone()
        job_id = linked['job_id'] if linked else ''
    snapshot = db.execute('SELECT * FROM hr_job_availability WHERE owner=? AND account=?',
                          (row['owner'], row['account'])).fetchone()
    fresh = snapshot and 0 <= time.time()-snapshot['checked_at'] < 300
    state = json.loads(snapshot['body']).get(job_id, {}) if fresh else {}
    opened = bool(state.get('open'))
    return {'canReview': opened, 'jobStatus': state.get('status', '状态待确认'),
            'jobRestriction': '' if opened else '该职位未开放或状态尚未确认，仅可查看，不能通过或拒绝邀约。'}


def require_open_job(db, row):
    state = job_availability(db, row)
    if not state['canReview']:
        raise pool.Conflict(state['jobRestriction'])


def analysis_job(db, row):
    """Read current saved JD by exact tenant/account/job; no BOSS request."""
    detail = dict(json.loads(row['body']))
    job_id = detail.get('jobId', '')
    if not job_id:
        rule_row = db.execute("SELECT job_id FROM hr_passive_rules WHERE owner=? AND account=? AND id=?",
                              (row['owner'], row['account'], row['rule_id'])).fetchone()
        job_id = rule_row['job_id'] if rule_row else ''
    if not job_id:
        return detail
    detail['jobId'] = job_id
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if 'recruitment_demands' in tables:
        choices = [json.loads(r['body']) for r in db.execute(
            """SELECT body FROM recruitment_demands WHERE owner=?
              AND json_extract(body,'$.jobAccountKey')=? AND json_extract(body,'$.jobId')=?
              ORDER BY updated_at DESC""", (row['owner'], row['account'], job_id))]
        choices = [c for c in choices if c.get('jobDescription', '').strip()]
        if len({c['jobDescription'].strip() for c in choices}) > 1:
            detail['jobDescription'] = ''
            detail['jobLinkError'] = '同一职位关联了不同 JD，请在招聘需求池确认并保留正确的职位资料'
            return detail
        if choices:
            value = choices[0]
            detail.update(jobDescription=value['jobDescription'], jobEducation=value.get('education', detail.get('jobEducation', '')),
                          jobExperience=str(value.get('years', detail.get('jobExperience', ''))), jobSource='招聘需求池')
            return detail
    if 'hr_sourcing_jobs' in tables:
        source = db.execute("SELECT body FROM hr_sourcing_jobs WHERE owner=? AND account=? AND job_id=?",
                            (row['owner'], row['account'], job_id)).fetchone()
        value = json.loads(source['body']) if source else {}
        if value.get('description', '').strip():
            detail.update(jobDescription=value['description'], jobEducation=value.get('education', ''),
                          jobExperience=value.get('experience', ''), jobSource='对应职位 JD')
    return detail


def resume_identity(db, row, actor):
    """Backfill old records from already-extracted text; no OSS/BOSS/AI request."""
    import resume_storage
    detail = json.loads(row['body'])
    if detail.get('nameExtractionVersion') == resume_storage.NAME_VERSION:
        return row
    filename = detail.get('originalFileName') or detail.get('fileName', '')
    name, source = resume_storage.identity(filename, detail.get('resumeText', ''))
    before = detail.get('name', '')
    detail['nameExtractionVersion'] = resume_storage.NAME_VERSION
    # A human edit is authoritative; automatic migration never overwrites it.
    if name and detail.get('nameSource') != 'manual':
        detail.update(name=name, nameSource=source,
                      fileName=detail.get('originalFileName') or (filename if source == 'resume_filename'
                               else f'{name}-简历.{detail.get("extension", "pdf")}'))
    name = detail.get('name', '')
    db.execute('UPDATE hr_resume_pool SET body=?,version=version+1,updated_at=? WHERE id=? AND owner=?',
               (dump(detail), now(), row['id'], row['owner']))
    if name and name != before:
        pool.event(db, row['owner'], actor, row['id'], 'resume.name-extract', {'name': before}, {'name': name})
        source_id = f"passive:{row['account']}:{row['friend_id']}"
        talent = db.execute('SELECT * FROM talents WHERE owner=? AND source_id=?', (row['owner'], source_id)).fetchone()
        if talent:
            value = json.loads(talent['body'])
            if value.get('name', '') in ('', before):
                value['name'] = detail['name']
                db.execute('UPDATE talents SET body=?,version=version+1,updated_at=? WHERE id=? AND owner=?',
                           (dump(value), now(), talent['id'], row['owner']))
    return db.execute('SELECT * FROM hr_resume_pool WHERE id=? AND owner=?', (row['id'], row['owner'])).fetchone()


def expire_commands(db):
    # A lease/claim response can be lost while the connector stays alive.
    # Never return these commands to the send queue automatically.
    cutoff = (datetime.datetime.now(datetime.timezone.utc)-datetime.timedelta(minutes=10)).isoformat()
    db.execute("UPDATE hr_resume_commands SET status='uncertain',updated_at=? WHERE status='sending' AND updated_at<?", (now(), cutoff))
    db.execute("UPDATE hr_talent_messages SET status='uncertain',updated_at=? WHERE status='sending' AND updated_at<?", (now(), cutoff))


def listing(user, kind, query):
    owner, actor = pool.context(user)
    with pool.database() as db:
        schema(db)
        expire_commands(db)
        if kind == 'speeches':
            selected = db.execute('SELECT speech_id FROM hr_speech_defaults WHERE owner=?', (owner,)).fetchone()
            reply = db.execute('SELECT speech_id FROM hr_passive_reply_defaults WHERE owner=?', (owner,)).fetchone()
            defaults = {r['speech_id'] for r in (selected, reply) if r}
            return {'items': [speech(r) | {'isDefault': r['id'] in defaults} for r in db.execute('SELECT * FROM hr_speeches WHERE owner=? ORDER BY updated_at DESC', (owner,))],
                    'suggestedRejection': DEFAULT_REJECTION}
        if kind == 'passive':
            return {'items': [rule(r) for r in db.execute('SELECT * FROM hr_passive_rules WHERE owner=?', (owner,))]}
        db.execute("UPDATE hr_resume_pool SET ai_state='failed',ai_error='分析等待超时，请手动重试' WHERE owner=? AND ai_state='running' AND ai_at<?", (owner, int(time.time())-360))
        scope, scope_args = 'owner=?', [owner]
        origin = query.get('origin', '')
        if origin not in {'', 'active', 'passive', 'boss_chat'}: raise ValueError('简历来源不正确')
        if origin and not query.get('id'):
            scope += " AND COALESCE(json_extract(body,'$.origin'),'passive')=?"
            scope_args.append(origin)
        selected_job = pool.text(query.get('jobId', ''), 180)
        if selected_job and not query.get('id'):
            scope += " AND (json_extract(body,'$.jobId')=? OR rule_id IN (SELECT id FROM hr_passive_rules WHERE owner=? AND job_id=?))"
            scope_args += [selected_job, owner, selected_job]
        clause, args = scope, list(scope_args)
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
        items = [resume(resume_identity(db, r, actor), commands) | job_availability(db, r) for r in rows]
        if query.get('id'):
            items = [item | {k: v for k, v in analysis_job(db, row).items()
                            if k in ('jobId', 'jobDescription', 'jobEducation', 'jobExperience', 'jobSource', 'jobLinkError')}
                     for item, row in zip(items, rows)]
        if not query.get('id'):
            for item in items: item.pop('resumeText', None)
        counts = {state: 0 for state in ('pending', 'approved', 'rejected')}
        counts.update({r['status']: r['n'] for r in db.execute(
            'SELECT status,count(*) n FROM hr_resume_pool WHERE '+scope+' GROUP BY status', scope_args)})
        counts['all'] = sum(counts.values())
        jobs = {}
        for row in db.execute('''SELECT r.job_id,r.id,r.body,COUNT(p.id) n FROM hr_passive_rules r
            LEFT JOIN hr_resume_pool p ON p.rule_id=r.id AND p.owner=r.owner
            WHERE r.owner=? GROUP BY r.id ORDER BY r.updated_at DESC''', (owner,)):
            entry = jobs.setdefault(row['job_id'], {'id': row['job_id'], 'name': json.loads(row['body']).get('jobName') or '未命名职位', 'count': 0, 'ruleIds': []})
            entry['count'] += row['n']; entry['ruleIds'].append(row['id'])
        for row in db.execute("""SELECT rule_id,body,COUNT(*) n FROM hr_resume_pool
                WHERE owner=? AND json_extract(body,'$.origin') IN ('active','boss_chat')
                GROUP BY rule_id,json_extract(body,'$.jobId')""", (owner,)):
            detail = json.loads(row['body'])
            if not detail.get('jobId'):
                continue
            entry = jobs.setdefault(detail['jobId'], {'id': detail['jobId'], 'name': detail['jobName'], 'count': 0, 'ruleIds': []})
            entry['count'] += row['n']; entry['ruleIds'].append(row['rule_id'])
        # Job counts honor the selected origin and include legacy passive rows.
        for item in jobs.values():
            item['count'] = db.execute("""SELECT count(*) FROM hr_resume_pool WHERE owner=?
                AND (json_extract(body,'$.jobId')=? OR rule_id IN
                (SELECT id FROM hr_passive_rules WHERE owner=? AND job_id=?))
                AND (?='' OR COALESCE(json_extract(body,'$.origin'),'passive')=?)""",
                (owner, item['id'], owner, item['id'], origin, origin)).fetchone()[0]
        events = [json.loads(r['body']) | {'createdAt': r['created_at']} for r in
                  db.execute('SELECT * FROM hr_passive_events WHERE owner=? ORDER BY created_at DESC LIMIT 100', (owner,))]
        if selected_job:
            selected = jobs.get(selected_job, {})
            unique_name = sum(j['name'] == selected.get('name') for j in jobs.values()) == 1
            events = [event for event in events if event.get('jobId') == selected_job or (
                not event.get('jobId') and unique_name and event.get('jobName') == selected.get('name'))]
        return {'items': items, 'total': total, 'page': page, 'pages': pages, 'counts': counts,
                'jobs': list(jobs.values()), 'events': events[:30]}


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
        if body.get('isDefault') is True and (kind not in {'reject', 'passive_reply'} or not active):
            raise ValueError('默认话术必须启用且用于婉拒或被动邀约回复')
        db.execute('INSERT OR REPLACE INTO hr_speeches VALUES(?,?,?,?,?,?,?,?)', (record, owner, name, kind, content, int(body.get('active', True) is True), version, now()))
        defaults = set()
        for purpose, table in [('reject', 'hr_speech_defaults'), ('passive_reply', 'hr_passive_reply_defaults')]:
            previous = db.execute(f'SELECT speech_id FROM {table} WHERE owner=?', (owner,)).fetchone()
            if body.get('isDefault') is True and kind == purpose:
                db.execute(f'INSERT OR REPLACE INTO {table} VALUES(?,?)', (owner, record))
            elif (body.get('isDefault') is False or not active or kind != purpose) and previous and previous['speech_id'] == record:
                db.execute(f'DELETE FROM {table} WHERE owner=?', (owner,))
            selected = db.execute(f'SELECT speech_id FROM {table} WHERE owner=?', (owner,)).fetchone()
            if selected: defaults.add(selected['speech_id'])
            if (previous['speech_id'] if previous else None) != (selected['speech_id'] if selected else None):
                pool.event(db, owner, actor, record, 'speech.default', dict(previous) if previous else None,
                           {'purpose': purpose, **(dict(selected) if selected else {})})
        result = speech(db.execute('SELECT * FROM hr_speeches WHERE id=?', (record,)).fetchone())
        result['isDefault'] = record in defaults
        pool.event(db, owner, actor, record, 'speech.save', speech(old) if old else None, result)
        return result


def save_rule(user, body):
    owner, actor = pool.context(user)
    key, job_id = account(body.get('accountKey'), owner), pool.text(body.get('jobId'), 180)
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
    import hr_sourcing
    owner, _ = pool.context(user); key = account(body.get('accountKey'), owner)
    with pool.database() as db:
        schema(db)
        if body.get('identityOnly') is True:
            return {'accountKey': identities.identify(db, owner, key, body.get('identityKey'))}
        if body.get('receiptSync') is True:
            cursor = body.get('receiptCursor', 0)
            if type(cursor) is not int or cursor < 0:
                raise ValueError('简历同步位置无效')
            # One bounded page per turn. Clients must finish the initial
            # inventory before starting any BOSS collection operation.
            rows = db.execute('''SELECT s.seq AS receipt_seq,r.* FROM hr_resume_receipt_sync s
                JOIN hr_resume_pool r ON r.id=s.resume_id
                WHERE r.owner=? AND r.account=? AND s.seq>? ORDER BY s.seq LIMIT 201''',
                              (owner, key, cursor)).fetchall()
            values = []
            for row in rows[:200]:
                metadata = json.loads(row['body'])
                values.append({'friendId': row['friend_id'], 'messageId': row['message_id'],
                               'ruleId': row['rule_id'], 'id': row['id'],
                               'origin': metadata.get('origin', 'passive'),
                               'jobId': metadata.get('jobId', ''), 'jobName': metadata.get('jobName', ''),
                               'name': metadata.get('name', '')})
            return {'accountKey': key, 'receipts': values, 'hasMore': len(rows) > 200,
                    'nextCursor': rows[min(len(rows), 200)-1]['receipt_seq'] if rows else cursor,
                    'ruleAliases': [{'from': r['alias'], 'to': r['canonical'], 'jobId': r['job_id']}
                        for r in db.execute('''SELECT a.alias,a.canonical,r.job_id
                            FROM hr_account_record_aliases a JOIN hr_passive_rules r
                            ON r.id=a.canonical AND r.owner=a.owner
                            WHERE a.owner=? AND a.kind='rule' AND r.account=?''', (owner, key))]}
        expire_commands(db)
        if body.get('commandId'):
            record = uid(body['commandId'])
            hr_sourcing.schema(db)
            for table, kind in [('hr_talent_messages', 'invite'), ('hr_resume_commands', 'reject'),
                                ('hr_sourcing_contacts', 'active_greet')]:
                row = db.execute(f'SELECT * FROM {table} WHERE id=? AND owner=? AND account=?',
                                 (record, owner, key)).fetchone()
                if row:
                    return {'commands': [{**json.loads(row['body']), 'kind': kind,
                                          'id': row['id'], 'friendId': row['friend_id']}]
                                         if row['status'] == 'queued' else [],
                            'commandStatus': row['status']}
            return {'commands': [], 'commandStatus': 'unavailable'}
        reply = db.execute('''SELECT s.* FROM hr_speeches s JOIN hr_passive_reply_defaults d
            ON d.speech_id=s.id AND d.owner=s.owner
            WHERE s.owner=? AND s.active=1 AND s.kind='passive_reply' ''', (owner,)).fetchone()
        commands = [{**json.loads(r['body']), 'id': r['id'], 'friendId': r['friend_id'], 'queuedAt': r['updated_at']}
                    for table in ('hr_resume_commands', 'hr_talent_messages')
                    for r in db.execute(f"SELECT * FROM {table} WHERE owner=? AND account=? AND status='queued' ORDER BY updated_at LIMIT 1", (owner, key))]
        commands.sort(key=lambda value: (value.get('kind') != 'invite', value['queuedAt']))
        active = hr_sourcing.device_contacts(db, owner, key)
        # Old desktops must never claim an operation they do not understand.
        if 'active-sourcing' in body.get('capabilities', []):
            commands += [{**row, 'queuedAt': row['enabledAt']} for row in active if row['status'] == 'queued'
                         and (len(row.get('identifiers', {}).get('securityId', '')) <= 600
                              or 'sourcing-credentials-v2' in body.get('capabilities', []))]
        return {'collectAllResumes': 'all-chat-resumes-v1' in body.get('capabilities', []),
                'rules': [rule(r) for r in db.execute('SELECT * FROM hr_passive_rules WHERE owner=? AND account=? AND enabled=1', (owner, key))],
                'replyTemplate': ({'id': reply['id'], 'version': reply['version'], 'content': reply['content'],
                                   'name': reply['name']} if reply else None),
                'commands': commands[:1], 'activeContacts': [row for row in active if row['status'] == 'sent'
                    or (row['status'] == 'uncertain' and 'greeting-receipts-v1' in body.get('capabilities', []))]}


def ingest(user, body):
    import resume_storage
    import hr_sourcing
    owner, actor = pool.context(user); key = account(body.get('accountKey'), owner)
    friend, mid, rule_id = str(body.get('friendId', '')), str(body.get('messageId', '')), uid(body.get('ruleId'))
    if not all(re.fullmatch(r'[1-9]\d{0,19}', x) for x in [friend, mid]): raise ValueError('简历会话标识无效')
    raw = base64.b64decode(body.get('fileBase64', ''), validate=True)
    extension = pool.text(body.get('extension', ''), 8).lower().lstrip('.')
    resume_storage.validate(raw, extension)
    digest = hashlib.sha256(raw).hexdigest()
    record = str(uuid.uuid5(uuid.NAMESPACE_URL, f'hr-resume:{owner}:{key}:{friend}:{mid}'))
    with pool.database() as db:
        schema(db)
        # IDs created before account reconciliation remain valid for reviews,
        # talent records and download links. Deduplicate on the receipt itself.
        existing = db.execute('SELECT * FROM hr_resume_pool WHERE owner=? AND account=? AND friend_id=? AND message_id=?',
                              (owner, key, friend, mid)).fetchone()
        if existing:
            if existing['file_hash'] != digest: raise pool.Conflict('同一简历消息的文件内容发生变化')
            return {'id': existing['id'], 'stored': True}
        rule_id = identities.record(db, owner, 'rule', rule_id)
        if rule_id == 'boss-chat-'+friend and body.get('origin') == 'boss_chat':
            # Receiving a verified attachment is independent of enabling an
            # invitation rule. JD lookup still uses exact owner/account/job.
            job_id = pool.text(body.get('jobId', ''), 180)
            hr_sourcing.schema(db)
            saved = db.execute('SELECT body FROM hr_sourcing_jobs WHERE owner=? AND account=? AND job_id=?',
                               (owner, key, job_id)).fetchone()
            value = json.loads(saved['body']) if saved else {}
            job = {'jobId': job_id, 'jobName': value.get('jobName') or pool.text(body.get('jobName', ''), 200)
                   or ('职位待核实' if not job_id else 'BOSS 沟通职位'),
                   'description': value.get('description', ''), 'education': value.get('education', ''),
                   'experience': value.get('experience', ''), 'projectId': '', 'origin': 'boss_chat'}
            # A fresh computer has no local invitation ledger yet. Only exact
            # confirmed server evidence may upgrade this receipt's source.
            contacts = db.execute('''SELECT id FROM hr_sourcing_contacts
                WHERE owner=? AND account=? AND friend_id=? AND status='sent'
                AND json_extract(body,'$.jobId')=? LIMIT 2''', (owner, key, friend, job_id)).fetchall()
            if len(contacts) == 1:
                rule_id = 'active-'+contacts[0]['id']
                job = hr_sourcing.received_job(db, owner, key, rule_id, friend)
        else:
            job = hr_sourcing.received_job(db, owner, key, rule_id, friend)
        if not job:
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
    for field, value in resume_storage.facts(extracted).items():
        if not clean.get(field):
            clean[field] = value
    original = resume_storage.original_filename(body.get('originalFileName', ''))
    extracted_name, name_source = resume_storage.identity(original, extracted)
    if not extracted_name and not clean.get('name'):
        extracted_name = resume_storage.ocr_name(raw, extension)
        if extracted_name: name_source = 'resume_ocr'
    if extracted_name:
        clean.update(name=extracted_name, nameSource=name_source)
    clean.update(jobName=job['jobName'], jobDescription=job['description'], jobEducation=job['education'], jobExperience=job['experience'],
                 jobId=job['jobId'], origin=job.get('origin', 'passive'),
                 projectId=job['projectId'], originalFileName=original,
                 fileName=original or (f'{extracted_name}-简历.{extension}' if extracted_name else f'简历-{record[:8]}.{extension}'), extension=extension,
                 nameExtractionVersion=resume_storage.NAME_VERSION,
                 resumeText=extracted, extractionNote=extraction_note, size=len(raw))
    with pool.database() as db:
        schema(db)
        inserted = db.execute('''INSERT OR IGNORE INTO hr_resume_pool(id,owner,account,friend_id,rule_id,message_id,body,file_key,file_hash,created_at,updated_at)
                      VALUES(?,?,?,?,?,?,?,?,?,?,?)''', (record, owner, key, friend, rule_id, mid, dump(clean), location, digest, now(), now()))
        saved = db.execute('SELECT id,file_hash FROM hr_resume_pool WHERE owner=? AND account=? AND friend_id=? AND message_id=?',
                           (owner, key, friend, mid)).fetchone()
        if not saved or saved['file_hash'] != digest: raise pool.Conflict('同一简历消息的文件内容发生变化')
        if inserted.rowcount:
            pool.event(db, owner, actor, record, 'resume.receive', None, {'fileHash': digest, 'ruleId': rule_id})
    return {'id': saved['id'], 'stored': True}


def get_file(user, record):
    import resume_storage
    owner, _ = pool.context(user)
    with pool.database() as db:
        schema(db)
        record = identities.record(db, owner, 'resume', uid(record))
        row = db.execute('SELECT * FROM hr_resume_pool WHERE owner=? AND id=?', (owner, uid(record))).fetchone()
        if not row: raise ValueError('简历不存在')
        metadata, key = json.loads(row['body']), row['file_key']
    return resume_storage.download(owner, key), metadata['fileName'], metadata['extension']


def review(user, body):
    owner, actor = pool.context(user); record = uid(body.get('id')); action = body.get('action')
    if action not in {'approve', 'reject', 'cancel-send'}: raise ValueError('审批操作不正确')
    with pool.database() as db:
        schema(db)
        record = identities.record(db, owner, 'resume', record)
        row = db.execute('SELECT * FROM hr_resume_pool WHERE owner=? AND id=?', (owner, record)).fetchone()
        if not row: raise ValueError('简历不存在')
        if action == 'cancel-send':
            changed = db.execute("UPDATE hr_resume_commands SET status='cancelled',updated_at=? WHERE owner=? AND resume_id=? AND status='queued'", (now(), owner, record)).rowcount
            if not changed: raise pool.Conflict('发送已开始或已完成，不能取消')
            pool.event(db, owner, actor, record, 'resume.reject.cancel', None, {})
            return {'cancelled': True}
        if row['status'] != 'pending': raise pool.Conflict('该简历已审批，请刷新查看结果')
        if row['version'] != body.get('version'): raise pool.Conflict('简历已更新，请刷新后审批')
        require_open_job(db, row)
        detail = json.loads(row['body']); detail['reviewedBy'] = actor; detail['reviewedAt'] = now()
        if action == 'approve':
            import resume_storage
            for field, value in resume_storage.facts(detail.get('resumeText', '')).items():
                if not detail.get(field):
                    detail[field] = value
            project = pool.text(body.get('projectId', detail.get('projectId', '')), 80)
            if project and not db.execute('SELECT 1 FROM projects WHERE owner=? AND id=?', (owner, project)).fetchone(): raise ValueError('项目组不存在')
            source = f"passive:{row['account']}:{row['friend_id']}"
            old = db.execute('SELECT * FROM talents WHERE owner=? AND source_id=?', (owner, source)).fetchone()
            talent_id = old['id'] if old else str(uuid.uuid4())
            value = json.loads(old['body']) if old else {k: '' for k in pool.FIELDS}
            value.update({k: detail.get(k) or value.get(k, '') for k in ['name', 'education', 'experience', 'city', 'company']})
            value.update(position=detail['jobName'], source=detail.get('origin', 'passive'), sourceId=source, notes=value.get('notes') or '简历已通过审核，待安排邀约。')
            department = pool.text(body.get('departmentId', ''), 36)
            if department:
                import hr_departments
                hr_departments.schema(db)
                if not db.execute("SELECT 1 FROM hr_departments WHERE owner=? AND id=? AND status='ACTIVE'",
                                  (owner, department)).fetchone():
                    raise ValueError('所选部门不存在或已停用，请刷新后重试')
                value['departmentId'] = department
            elif not value.get('departmentId'):
                value['departmentId'] = pool.department_for_resumes(db, owner, [(row['account'], detail)])
            detail['departmentId'] = value.get('departmentId', '')
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
            payload = {'kind': 'reject', 'message': message, 'name': detail.get('name', ''), 'jobName': detail['jobName'], 'reviewedBy': actor,
                       'templateId': template['id'], 'templateVersion': template['version'], 'templateName': template['name'], 'resumeId': record}
            db.execute('INSERT INTO hr_resume_commands VALUES(?,?,?,?,?,?,?,?)', (command, owner, row['account'], record, row['friend_id'], dump(payload), 'queued', now()))
            status = 'rejected'
        db.execute('UPDATE hr_resume_pool SET status=?,body=?,version=version+1,updated_at=? WHERE owner=? AND id=?', (status, dump(detail), now(), owner, record))
        pool.event(db, owner, actor, record, 'resume.'+action, {'status': row['status']}, detail | {'status': status})
        return {'id': record, 'status': status, 'talentId': detail.get('talentId'),
                'rejectionStatus': 'queued' if action == 'reject' else None,
                'commandId': command if action == 'reject' else None, 'accountKey': row['account']}


def claim_command(user, body):
    import hr_sourcing
    owner, _ = pool.context(user); key = account(body.get('accountKey'), owner); record = uid(body.get('id'))
    with pool.database() as db:
        schema(db)
        active = hr_sourcing.claim(db, owner, key, record)
        if active is not None: return active
        invitation = db.execute("SELECT * FROM hr_talent_messages WHERE id=? AND owner=? AND account=? AND status='queued'", (record, owner, key)).fetchone()
        if invitation:
            talent = db.execute("SELECT * FROM talents WHERE id=? AND owner=?", (invitation['talent_id'], owner)).fetchone()
            if not talent or talent['stage'] != 'invited' or talent['source_id'] != f"passive:{key}:{invitation['friend_id']}":
                db.execute("UPDATE hr_talent_messages SET status='cancelled',updated_at=? WHERE id=?", (now(), record))
                return {'claimed': False}
            return {'claimed': bool(db.execute("UPDATE hr_talent_messages SET status='sending',updated_at=? WHERE id=? AND status='queued'", (now(), record)).rowcount)}
        command = db.execute("SELECT * FROM hr_resume_commands WHERE id=? AND owner=? AND account=? AND status='queued'",
                             (record, owner, key)).fetchone()
        if command:
            row = db.execute('SELECT * FROM hr_resume_pool WHERE id=? AND owner=? AND account=?',
                             (command['resume_id'], owner, key)).fetchone()
            if not row or not job_availability(db, row)['canReview']:
                return {'claimed': False}
        changed = db.execute("UPDATE hr_resume_commands SET status='sending',updated_at=? WHERE id=? AND owner=? AND account=? AND status='queued'", (now(), record, owner, key)).rowcount
        if not changed: return {'claimed': False}
        return {'claimed': True}


def device_report(user, body):
    import hr_sourcing
    owner, _ = pool.context(user); key = account(body.get('accountKey'), owner)
    if 'jobStatuses' in body:
        states, valid_for = body['jobStatuses'], body.get('validFor', 0)
        if (not isinstance(states, dict) or len(states) > 1000 or type(valid_for) not in (int, float)
                or not 0 <= valid_for <= 300 or any(not isinstance(k, str) or not k or len(k) > 180
                or not isinstance(v, str) or len(v) > 40 for k,v in states.items())):
            raise ValueError('本机职位状态不完整')
        with pool.database() as db:
            schema(db)
            values = {k:{'open':v=='开放中','status':v} for k,v in states.items()}
            db.execute('INSERT OR REPLACE INTO hr_job_availability VALUES(?,?,?,?)',
                       (owner, key, dump(values), time.time()-300+valid_for))
    if body.get('commandId'):
        state = body.get('status')
        if state not in {'sent', 'uncertain', 'failed'}: raise ValueError('发送状态不正确')
        with pool.database() as db:
            schema(db)
            hr_sourcing.report(db, owner, key, body)
            for table in ('hr_resume_commands', 'hr_talent_messages'):
                db.execute(f"UPDATE {table} SET status=?,updated_at=? WHERE id=? AND owner=? AND account=? AND status IN ('sending','uncertain')", (state, now(), uid(body['commandId']), owner, key))
    if body.get('contactId') or body.get('waitingContactId'):
        with pool.database() as db:
            schema(db)
            hr_sourcing.report(db, owner, key, body)
    event = body.get('event')
    if isinstance(event, dict):
        clean = {k: pool.text(event.get(k, ''), limit) for k, limit in {'id': 80, 'code': 80, 'name': 100, 'jobName': 200, 'jobId': 180, 'ruleId': 80, 'message': 400}.items()}
        if clean['id']:
            with pool.database() as db:
                schema(db)
                db.execute('INSERT OR IGNORE INTO hr_passive_events VALUES(?,?,?,?,?)', (clean['id'], owner, key, dump(clean), now()))
    return {'recorded': True}


def invitation_messages(user, body, write=False):
    owner, actor = pool.context(user)
    record = uid(body.get('talentId'))
    with pool.database() as db:
        schema(db); expire_commands(db)
        talent = db.execute('SELECT * FROM talents WHERE id=? AND owner=?', (record, owner)).fetchone()
        if not talent: raise ValueError('人才记录不存在')
        source = re.fullmatch(r'passive:([a-f0-9]{64}):([1-9]\d{0,19})', talent['source_id'])
        if write:
            if talent['stage'] != 'invited' or not source:
                raise ValueError('仅可向简历审批通过、处于邀约阶段的 BOSS 候选人发送')
            key, friend = source.groups()
            approved = db.execute("SELECT body FROM hr_resume_pool WHERE owner=? AND account=? AND friend_id=? AND status='approved'", (owner, key, friend)).fetchall()
            if not any(json.loads(r['body']).get('talentId') == record for r in approved):
                raise ValueError('未找到这位候选人的已通过简历，未创建发送任务')
            message = pool.text(body.get('message'), 1000)
            if not message: raise ValueError('请输入邀约消息')
            try: request_id = str(uuid.UUID(body.get('requestId', '')))
            except (ValueError, TypeError, AttributeError): raise ValueError('发送请求标识无效')
            command = str(uuid.uuid5(uuid.NAMESPACE_URL, f'hr-invite:{owner}:{request_id}'))
            old = db.execute('SELECT * FROM hr_talent_messages WHERE id=? AND owner=?', (command, owner)).fetchone()
            if old:
                if old['talent_id'] != record or json.loads(old['body'])['message'] != message:
                    raise pool.Conflict('这次发送已提交，请刷新发送记录')
                return {'id': command, 'status': old['status'], 'accountKey': key}
            if db.execute("SELECT 1 FROM hr_talent_messages WHERE owner=? AND talent_id=? AND status IN ('queued','sending','uncertain')", (owner, record)).fetchone():
                raise pool.Conflict('这位候选人有消息待发送或待核对，请先查看发送记录')
            detail = json.loads(talent['body'])
            payload = {'kind': 'invite', 'message': message, 'name': detail.get('name', ''),
                       'jobName': detail.get('position', ''), 'talentId': record, 'createdBy': actor}
            db.execute('INSERT INTO hr_talent_messages VALUES(?,?,?,?,?,?,?,?)',
                       (command, owner, key, record, friend, dump(payload), 'queued', now()))
            pool.event(db, owner, actor, record, 'talent.invite-message', None, {'commandId': command, 'message': message})
            return {'id': command, 'status': 'queued', 'accountKey': key}
        return {'items': [{**json.loads(row['body']), 'id': row['id'], 'status': row['status'], 'updatedAt': row['updated_at']}
                          for row in db.execute('SELECT * FROM hr_talent_messages WHERE owner=? AND talent_id=? ORDER BY updated_at DESC,id LIMIT 20', (owner, record))]}

def analyze(user, body, cookie):
    owner, actor = pool.context(user); record = uid(body.get('id'))
    with pool.database() as db:
        schema(db)
        record = identities.record(db, owner, 'resume', record)
        row = db.execute('SELECT * FROM hr_resume_pool WHERE owner=? AND id=?', (owner, record)).fetchone()
        if not row: raise ValueError('简历不存在')
        require_open_job(db, row)
        if row['ai_state'] == 'running': return {'id': record, 'aiState': 'running'}
        if row['ai_state'] == 'complete' and body.get('regenerate') is not True: return resume(row)
        if db.execute("SELECT 1 FROM hr_resume_pool WHERE owner=? AND ai_state='running' AND ai_at>?", (owner, int(time.time())-360)).fetchone(): raise pool.Conflict('已有简历正在分析，请稍后')
        detail = analysis_job(db, row)
        if detail.get('jobLinkError'):
            raise ValueError(detail['jobLinkError'])
        text = pool.text(body.get('resumeText', detail.get('resumeText', '')), 60000)
        if len(text) < 30: raise ValueError('未提取到足够正文，请查看原件并补充简历文字后分析')
        description = pool.text(body.get('jobDescription', '') or detail.get('jobDescription', ''), 10000)
        if not description: raise ValueError('该职位缺少岗位职责，请补充职位资料后分析')
        job = str(uuid.uuid4()); snapshot = {'resumeText': text, 'jobName': detail.get('jobName', ''), 'jobDescription': description, 'education': detail.get('jobEducation', ''), 'experience': detail.get('jobExperience', '')}
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
    except urllib.error.HTTPError as failure:
        messages = {
            'HR_AI_TIMEOUT': '驱动模型响应超时，请稍后手动重试',
            'HR_AI_TRUNCATED': '驱动模型返回内容不完整，请精简资料后重试',
            'HR_AI_INVALID_STREAM': '驱动模型响应中断，请稍后手动重试',
            'HR_AI_INVALID_JSON': '驱动模型未返回有效分析格式，请检查驱动模型后重试',
            'HR_AI_INVALID_RESULT': '驱动模型的匹配结果字段不完整，请手动重试',
            'HR_AI_UPSTREAM_HTTP': '驱动模型服务返回错误，请检查模型服务后重试',
            'HR_AI_CONNECTION': '驱动模型连接失败，请检查驱动模型配置',
        }
        error = 'AI 分析未完成，请检查驱动模型配置后手动重试；审批不受影响'
        if failure.code in (401, 403):
            error = '平台登录已失效或没有分析权限，请重新登录后手动分析'
        else:
            try:
                payload = json.loads(failure.read(8192))
                match = re.search(r'\[(HR_AI_[A-Z_]+)\]', str(payload.get('message', '')))
                if match and match[1] in messages:
                    error = messages[match[1]] + '；本次未自动重复调用'
            except (ValueError, TypeError, OSError):
                pass
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
        db.execute("UPDATE hr_talent_messages SET status='uncertain' WHERE status='sending'")
