"""Explicit first contact over the pinned recruiter CLI; exact-ID follow-up only."""
import json
import re
import time


def schema(db):
    db.execute('''CREATE TABLE IF NOT EXISTS active_contacts(
      id TEXT PRIMARY KEY,body TEXT NOT NULL,state TEXT NOT NULL,friend TEXT NOT NULL DEFAULT '',
      bound INTEGER NOT NULL DEFAULT 0,next_at REAL NOT NULL DEFAULT 0,cursor INTEGER NOT NULL DEFAULT 0)''')


def readiness(c, db, command=None):
    from passive_runtime import get_state
    delay = max(getattr(c, 'passive_delay', lambda **kw: 0)(cost=3, queued=True),
                float(get_state(db, 'nextWriteAt'))-time.time())
    return {'ok': True, 'data': {'allowed': delay <= 0, 'retryAt': int(time.time()+max(0, delay))}}


def send(c, db, command):
    from passive_runtime import put_state, get_state
    schema(db)
    cid, text = command.get('id', ''), command.get('message', '')
    if not re.fullmatch(r'[a-zA-Z0-9-]{1,80}', cid) or not isinstance(text, str) or not 1 <= len(text) <= 1000:
        raise ValueError('invalid greeting')
    previous = db.execute('SELECT state FROM commands WHERE id=?', (cid,)).fetchone()
    if previous: return {'ok': True, 'data': {'status': previous['state']}}
    identifiers = command.get('identifiers', {})
    if not all(isinstance(identifiers.get(k), str) and 1 <= len(identifiers[k]) <= (4096 if k == 'securityId' else 600)
               for k in ('geekId', 'expectId', 'lid', 'securityId')):
        raise ValueError('missing recruiter greeting parameters')
    # Match connector.security_identifier: long opaque credentials are literal
    # arguments, never truncated, decoded, interpolated into a shell, or logged.
    if not re.fullmatch(r"[A-Za-z0-9_~][A-Za-z0-9_~\-=]{0,4095}", identifiers['securityId']):
        raise ValueError('invalid recruiter access credential')
    if not re.fullmatch(r'[\w~-]{1,180}', command.get('jobId', '')): raise ValueError('invalid job')
    # If a claimed command expires while waiting for execution, do not send.
    if (time.time()-command.get('readAt', 0)>1800 or not readiness(c, db)['data']['allowed']
            or time.time()<float(get_state(db, 'greetQuota:'+command['jobId']))):
        db.execute("INSERT INTO commands VALUES(?,?,?,'failed',0)", (cid, '', text))
        return {'ok': True, 'data': {'status': 'failed'}}
    db.execute("INSERT INTO commands VALUES(?,?,?,'uncertain',0)", (cid, '', text))
    db.execute("INSERT INTO active_contacts(id,body,state) VALUES(?,?,'uncertain')", (cid, json.dumps(command, ensure_ascii=False)))
    put_state(db, 'nextWriteAt', time.time()+60)
    db.commit()
    args = ['hr', 'greet', '--geek-id', identifiers['geekId'], '--job-id', command['jobId'],
            '--expect-id', identifiers['expectId'], '--lid', identifiers['lid'],
            '--security-id', identifiers['securityId'], '--message', text, '--yes']
    if identifiers.get('suid'): args += ['--suid', identifiers['suid']]
    try:
        result = c.cli_json(*args)
    except Exception:
        result = {'ok': False}
    state = 'sent' if result.get('ok') is True and (result.get('data') or {}).get('sent') is True else 'uncertain'
    if (result.get('error') or {}).get('code') == 'GREET_LIMIT':
        # Do not spend remaining requests repeatedly probing exhausted rights.
        # Queued cards expire within 30 minutes and are never replayed tomorrow.
        put_state(db, 'greetQuota:'+command['jobId'], time.time()+86400)
        state = 'failed'
    db.execute('UPDATE commands SET state=? WHERE id=?', (state, cid))
    db.execute('UPDATE active_contacts SET state=? WHERE id=?', (state, cid))
    if c.held_code():
        db.commit()
        return {'ok': False, 'error': {'code': c.held_code(), 'message': '平台要求暂停，招呼结果保留待核对'}}
    return {'ok': True, 'data': {'status': state}}


def local(db):
    schema(db)
    return [{'contactId': r['id'], 'friendId': r['friend']} for r in db.execute(
        "SELECT * FROM active_contacts WHERE state='sent' AND friend<>'' AND bound=0 LIMIT 5")]


def rules(c, db, contacts):
    """One bounded reconciliation read per interval; no name-based associations."""
    from passive_runtime import rows, event
    schema(db)
    approved = {}
    for value in contacts[:200]:
        if not isinstance(value, dict) or value.get('status') != 'sent': continue
        cid = value.get('id', '')
        if not re.fullmatch(r'[a-zA-Z0-9-]{1,80}', cid): continue
        approved[cid] = value
        db.execute("INSERT OR IGNORE INTO active_contacts(id,body,state) VALUES(?,?,'sent')",
                   (cid, json.dumps(value, ensure_ascii=False)))
    pending = [r for r in db.execute("SELECT * FROM active_contacts WHERE state='sent' AND friend='' AND next_at<=? ORDER BY next_at,id", (time.time(),))
               if r['id'] in approved]
    if pending and not getattr(c, 'passive_delay', lambda **kw: 0)(cost=3, queued=False):
        item = pending[0]; detail = json.loads(item['body'])
        # Reserve the cooldown before reads so a failure cannot create a tight loop.
        db.execute('UPDATE active_contacts SET next_at=? WHERE id=?', (time.time()+300, item['id'])); db.commit()
        result = c.cli_json('hr', 'chat', '--job-id', detail['jobId'], '--page', '1')
        if result.get('ok') is not True: return {}, result
        ids = []
        for value in rows(result):
            friend = str(value.get('friendId') or value.get('friend_id') or value.get('uid') or value.get('gid') or '')
            if re.fullmatch(r'[1-9]\d{0,19}', friend) and friend not in ids: ids.append(friend)
        # Some SDK versions return UID-only chat lists. Use the SDK's documented
        # friend_detail method, guarded by the same network policy and budget.
        start = item['cursor'] if item['cursor'] < len(ids) else 0
        chosen = ids[start:start+40]
        db.execute('UPDATE active_contacts SET cursor=? WHERE id=?', (start+len(chosen), item['id']))
        friends = c.sourcing_friend_details(chosen) if chosen else {'ok': True, 'data': []}
        if friends.get('ok') is not True: return {}, friends
        matches = []
        for value in rows(friends):
            geek = str(value.get('encryptUid') or value.get('encryptGeekId') or '')
            job = str(value.get('encryptJobId') or value.get('encJobId') or '')
            friend = str(value.get('uid') or value.get('friendId') or '')
            if geek == detail['identifiers']['geekId'] and job == detail['jobId'] and re.fullmatch(r'[1-9]\d{0,19}', friend):
                matches.append(friend)
        if len(set(matches)) == 1:
            friend = matches[0]
            old = db.execute('SELECT rule FROM people WHERE friend=?', (friend,)).fetchone()
            if not old or old['rule'] == detail['ruleId']:
                detail.update(replyAt=detail['enabledAt'], origin='active', activeContactId=item['id'])
                db.execute('UPDATE active_contacts SET friend=? WHERE id=?', (friend, item['id']))
                db.execute("INSERT OR IGNORE INTO people(friend,rule,body,state,updated) VALUES(?,?,?,'inspect',?)",
                           (friend, detail['ruleId'], json.dumps(detail, ensure_ascii=False), int(time.time())))
                event(db, 'ACTIVE_CONTACT_LINKED', detail)
            else:
                # Preserve a previously admitted workflow and never send another
                # resume request merely to change its source classification.
                db.execute("UPDATE active_contacts SET state='existing_flow' WHERE id=?", (item['id'],))
    result = {}
    for row in db.execute("SELECT * FROM active_contacts WHERE state='sent' AND friend<>''"):
        if row['id'] not in approved: continue
        detail = json.loads(row['body'])
        result[detail['ruleId']] = {'id': detail['ruleId'], 'enabled': True, 'enabledAt': detail['enabledAt'],
                                   'jobId': detail['jobId'], 'jobName': detail['jobName'], 'activeFriend': row['friend']}
    return result, None
