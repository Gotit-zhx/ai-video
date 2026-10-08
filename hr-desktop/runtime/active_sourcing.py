"""Explicit first contact over the pinned recruiter CLI; exact-ID follow-up only."""
import json
import re
import time
import datetime

DAILY_GREETING_LIMIT = 150
CHINA_TIME = datetime.timezone(datetime.timedelta(hours=8))


def schema(db):
    db.execute('''CREATE TABLE IF NOT EXISTS active_contacts(
      id TEXT PRIMARY KEY,body TEXT NOT NULL,state TEXT NOT NULL,friend TEXT NOT NULL DEFAULT '',
      bound INTEGER NOT NULL DEFAULT 0,next_at REAL NOT NULL DEFAULT 0,cursor INTEGER NOT NULL DEFAULT 0)''')
    db.execute('''CREATE TABLE IF NOT EXISTS active_greeting_attempts(
      contact TEXT PRIMARY KEY,day TEXT NOT NULL,attempted_at REAL NOT NULL)''')


def daily_usage(db, now):
    schema(db)
    day = datetime.datetime.fromtimestamp(now, CHINA_TIME)
    reset = (day+datetime.timedelta(days=1)).replace(hour=0,minute=0,second=0,microsecond=0)
    used = db.execute('SELECT count(*) FROM active_greeting_attempts WHERE day=?', (day.date().isoformat(),)).fetchone()[0]
    # Older uncertain/sent ledgers predate the counter. Include them once using
    # their durable approval time; never reset a day's usage on an app upgrade.
    for row in db.execute("SELECT id,body FROM active_contacts WHERE id NOT IN (SELECT contact FROM active_greeting_attempts) AND state IN ('uncertain','sent','existing_flow')"):
        approved = json.loads(row['body']).get('enabledAt', 0)
        if approved > 0:
            stamp = datetime.datetime.fromtimestamp(approved, CHINA_TIME).date().isoformat()
            db.execute('INSERT OR IGNORE INTO active_greeting_attempts VALUES(?,?,?)',(row['id'],stamp,approved))
            if stamp == day.date().isoformat(): used += 1
    return {'used':used,'limit':DAILY_GREETING_LIMIT,'remaining':max(0,DAILY_GREETING_LIMIT-used),
            'day':day.date().isoformat(),'resetAt':int(reset.timestamp())}


def readiness(c, db, command=None):
    from passive_runtime import get_state
    now = time.time()
    usage = daily_usage(db, now)
    def waiting(code, delay=0):
        return {'ok': True, 'data': {'allowed': False, 'retryAt': int(now+max(0, delay)),
                                   'reasonCode': code, 'dailyGreeting': usage}}
    if c.held_code():
        return waiting('RISK_PAUSED')
    if usage['remaining'] == 0:
        return waiting('DAILY_GREETING_LIMIT', usage['resetAt']-now)
    if command:
        if now-command.get('readAt', 0)>1800:
            return waiting('CARD_EXPIRED')
        quota = float(get_state(db, 'greetQuota:'+command.get('jobId', '')))
        if quota > now:
            return waiting('GREET_QUOTA', quota-now)
    # This command was individually approved by HR. Use the capacity reserved
    # for manual actions, while every SDK request retains the same hard guard.
    delay = getattr(c, 'passive_delay', lambda **kw: 0)(cost=5, queued=True, user_initiated=True)
    if delay > 0:
        return waiting('REQUEST_BUDGET_WAIT', delay)
    delay = float(get_state(db, 'nextWriteAt'))-now
    if delay > 0:
        return waiting('SEND_INTERVAL', delay)
    if command:
        jobs = c.managed_job_statuses(user_initiated=True)
        status = jobs['statuses'].get(command.get('jobId'))
        if status != '开放中':
            return waiting('JOB_STATUS_WAIT' if status is None else 'JOB_NOT_OPEN',
                           max(30, jobs.get('retryAt', now+300)-now))
    return {'ok': True, 'data': {'allowed': True, 'retryAt': 0, 'reasonCode': '', 'dailyGreeting': usage}}


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
    if (time.time()-command.get('readAt', 0)>1800 or not readiness(c, db, command)['data']['allowed']
            or time.time()<float(get_state(db, 'greetQuota:'+command['jobId']))):
        db.execute("INSERT INTO commands VALUES(?,?,?,'failed',0)", (cid, '', text))
        return {'ok': True, 'data': {'status': 'failed'}}
    usage = daily_usage(db, time.time())
    command = {**command, 'attemptedAt': time.time()}
    db.execute("INSERT INTO commands VALUES(?,?,?,'uncertain',0)", (cid, '', text))
    db.execute("INSERT INTO active_contacts(id,body,state) VALUES(?,?,'uncertain')", (cid, json.dumps(command, ensure_ascii=False)))
    db.execute('INSERT OR IGNORE INTO active_greeting_attempts VALUES(?,?,?)',
               (cid,usage['day'],time.time()))
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
    error = result.get('error') or {}
    details = error.get('details') or {}
    if (error.get('code') in {'ALREADY_GREETED', 'GREET_RESULT_UNKNOWN'}
            and isinstance(details, dict) and details.get('sent') is True
            and details.get('geek_id') == identifiers['geekId']
            and details.get('job_id') == command['jobId']):
        # A confirmed business send can coexist with a failed SDK cache write.
        state = 'sent'
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
        "SELECT * FROM active_contacts WHERE state IN ('sent','existing_flow') AND friend<>'' AND bound=0 LIMIT 5")]


def greeting_receipt(messages, friend, command, attempted):
    """Exact outgoing text, candidate UID, message ID and attempt window."""
    from passive_runtime import seconds
    receipts = []
    for item in messages:
        if not isinstance(item, dict):
            continue
        sender, target, body = item.get('from') or {}, item.get('to') or {}, item.get('body') or {}
        if not all(isinstance(value, dict) for value in (sender, target, body)):
            continue
        stamp, mid = seconds(item.get('time')), str(item.get('mid', ''))
        if (str(target.get('uid')) == friend and str(sender.get('uid', '')).isdecimal()
                and str(sender['uid']) != friend and body.get('type') == 1
                and body.get('text') == command['message'] and mid.isdecimal() and int(mid) > 0
                and attempted-5 <= stamp <= time.time()+60):
            receipts.append({'friendId': friend, 'messageId': mid, 'sentAt': stamp, 'source': 'chat_history'})
    return min(receipts, key=lambda value: value['sentAt']) if receipts else None


def rules(c, db, contacts):
    """One bounded reconciliation read per interval; no name-based associations."""
    from passive_runtime import rows, event, get_state, put_state
    schema(db)
    approved = {}
    recent = sorted((value for value in contacts if isinstance(value, dict)),
                    key=lambda value: (value.get('status') != 'uncertain', -value.get('attemptedAt', value.get('enabledAt', 0))))
    for value in recent[:200]:
        if not isinstance(value, dict) or value.get('status') not in {'sent', 'uncertain'}: continue
        cid = value.get('id', '')
        if not re.fullmatch(r'[a-zA-Z0-9-]{1,80}', cid): continue
        approved[cid] = value
        db.execute("INSERT OR IGNORE INTO active_contacts(id,body,state) VALUES(?,?,?)",
                   (cid, json.dumps(value, ensure_ascii=False), value['status']))
    now = time.time()
    pending = []
    for row in db.execute("SELECT * FROM active_contacts WHERE state IN ('sent','uncertain') AND friend='' AND next_at<=? ORDER BY next_at,id", (now,)):
        if row['id'] not in approved:
            continue
        if row['state'] == 'uncertain':
            attempt = db.execute('SELECT attempted_at FROM active_greeting_attempts WHERE contact=?', (row['id'],)).fetchone()
            attempted = attempt[0] if attempt else json.loads(row['body']).get('attemptedAt', 0)
            if not attempted or now-attempted > 86400:
                continue
        pending.append(row)
    pending.sort(key=lambda row: (row['state'] != 'uncertain', -json.loads(row['body']).get('attemptedAt', json.loads(row['body']).get('enabledAt', 0))))
    if pending:
        item = pending[0]; detail = json.loads(item['body'])
        uncertain = item['state'] == 'uncertain'
        if uncertain:
            attempt = db.execute('SELECT attempted_at FROM active_greeting_attempts WHERE contact=?', (item['id'],)).fetchone()
            attempted = attempt[0] if attempt else detail.get('attemptedAt', 0)
            if now < float(get_state(db, 'greetingReceiptNextAt')):
                pending = []
        delay = getattr(c, 'passive_delay', lambda **kw: 0)(
            cost=4 if uncertain else 3, queued=uncertain, **({'user_initiated': True} if uncertain else {}))
        if delay or c.held_code():
            pending = []
    if pending:
        # Reserve the cooldown before reads so a failure cannot create a tight loop.
        db.execute('UPDATE active_contacts SET next_at=? WHERE id=?', (now+300, item['id']))
        if uncertain:
            put_state(db, 'greetingReceiptNextAt', now+30)
        db.commit()
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
            if uncertain:
                history = c.cli_json('hr', 'chatmsg', friend, '--count', '100')
                if history.get('ok') is not True:
                    return {}, history
                receipt = greeting_receipt(c.resume_messages(history), friend, detail, attempted)
                if not receipt:
                    return {}, None
                detail['receipt'] = receipt
                db.execute("UPDATE active_contacts SET state='sent',body=? WHERE id=?",
                           (json.dumps(detail, ensure_ascii=False), item['id']))
                db.execute("INSERT INTO commands(id,friend,message,state,reported) VALUES(?,?,?,'sent',0) "
                           "ON CONFLICT(id) DO UPDATE SET friend=excluded.friend,state='sent',reported=0",
                           (item['id'], friend, detail['message']))
                event(db, 'ACTIVE_GREETING_CONFIRMED', detail)
            db.execute('UPDATE active_contacts SET friend=? WHERE id=?', (friend, item['id']))
            old = db.execute('SELECT rule FROM people WHERE friend=?', (friend,)).fetchone()
            if not old or old['rule'] == detail['ruleId']:
                detail.update(replyAt=detail['enabledAt'], origin='active', activeContactId=item['id'])
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
