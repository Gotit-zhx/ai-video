"""Receive attachments from every official conversation, without sending messages."""
import hashlib
import json
from pathlib import Path
import re
import time
import uuid

import passive_runtime as flow


def schema(db):
    db.executescript('''
      CREATE TABLE IF NOT EXISTS chat_resume_conversations(
        friend TEXT PRIMARY KEY,body TEXT NOT NULL DEFAULT '{}',
        latest_mid INTEGER NOT NULL DEFAULT 0,dirty INTEGER NOT NULL DEFAULT 1,
        fresh_before INTEGER NOT NULL DEFAULT 0,fresh_top INTEGER NOT NULL DEFAULT 0,
        dirty_again INTEGER NOT NULL DEFAULT 0,
        history_before INTEGER NOT NULL DEFAULT 0,history_done INTEGER NOT NULL DEFAULT 0,
        retry_at REAL NOT NULL DEFAULT 0,last_read REAL NOT NULL DEFAULT 0,activity_at REAL NOT NULL DEFAULT 0);
      CREATE TABLE IF NOT EXISTS chat_resume_receipts(
        friend TEXT NOT NULL,mid TEXT NOT NULL,rule TEXT NOT NULL,body TEXT NOT NULL,
        state TEXT NOT NULL DEFAULT 'found',file TEXT NOT NULL DEFAULT '',
        retry_at REAL NOT NULL DEFAULT 0,error TEXT NOT NULL DEFAULT '',
        PRIMARY KEY(friend,mid));
    ''')
    if 'signaled' not in {r[1] for r in db.execute('PRAGMA table_info(chat_resume_conversations)')}:
        db.execute('ALTER TABLE chat_resume_conversations ADD COLUMN signaled INTEGER NOT NULL DEFAULT 0')


def valid_id(value):
    value = str(value or '')
    return value if re.fullmatch(r'[1-9]\d{0,19}', value) else ''


def wake(db, friend):
    if valid_id(friend):
        db.execute("INSERT INTO chat_resume_conversations(friend,activity_at,signaled) VALUES(?,?,1) "
                   "ON CONFLICT(friend) DO UPDATE SET dirty=1,signaled=1,retry_at=0,activity_at=excluded.activity_at,"
                   "dirty_again=CASE WHEN fresh_before>0 THEN 1 ELSE dirty_again END", (friend, time.time()))


def discover(c, db):
    page = int(flow.get_state(db, 'chat-resumes-page', '1'))
    result = c.cli_json('hr', 'chat', '--page', str(page))
    if result.get('ok') is not True:
        flow.put_state(db, 'chat-resumes-discover-at', time.time()+3600)
        return result
    entries = flow.rows(result)
    ids = []
    for item in entries:
        if not isinstance(item, dict):
            continue
        friend = valid_id(item.get('friendId') or item.get('friend_id') or item.get('uid') or item.get('gid'))
        if not friend:
            continue
        ids.append(friend)
        activity_fields = [item.get(k) for k in ('last_msg', 'last_time', 'lastTS', 'lastTime', 'lastMsgInfo',
                                               'lastMessageInfo', 'updateTime')]
        fingerprint = hashlib.sha256(json.dumps(activity_fields, sort_keys=True).encode()).hexdigest()
        old = db.execute('SELECT body,last_read FROM chat_resume_conversations WHERE friend=?', (friend,)).fetchone()
        body = json.loads(old['body']) if old else {}
        # Some official list responses contain only UIDs. They cannot prove a
        # conversation unchanged; retain a slow safety rescan for those rows.
        changed = body.get('fingerprint') != fingerprint or (
            not any(value is not None and value != '' for value in activity_fields)
            and old and old['last_read'] < time.time()-1800)
        body['fingerprint'] = fingerprint
        db.execute('''INSERT INTO chat_resume_conversations(friend,body,activity_at) VALUES(?,?,?)
          ON CONFLICT(friend) DO UPDATE SET body=excluded.body,
          activity_at=MAX(activity_at,excluded.activity_at),
          dirty=CASE WHEN ? THEN 1 ELSE dirty END''',
                   (friend, json.dumps(body), flow.activity(item), changed))
    fingerprint = hashlib.sha256(json.dumps(ids).encode()).hexdigest()
    complete = not entries or flow.discovery_complete(result)
    if not complete and fingerprint == flow.get_state(db, 'chat-resumes-page-fingerprint', ''):
        # A repeated provider page is not permission to skip older conversations.
        flow.put_state(db, 'chat-resumes-discover-at', time.time()+3600)
    else:
        flow.put_state(db, 'chat-resumes-page', 1 if complete else page+1)
        flow.put_state(db, 'chat-resumes-page-fingerprint', '' if complete else fingerprint)
        flow.put_state(db, 'chat-resumes-discover-at', time.time()+(3600 if complete else 600))
    return result


def attachment_metadata(c, envelope, friend, binding):
    """Use the pinned SDK's own incoming-message and resume-card validation."""
    from boss_agent_cli.api.recruiter_resume import incoming_message, attachment_params, ResumeValidationError
    messages = c.resume_messages(envelope)
    for message in messages:
        if not isinstance(message, dict):
            continue
        mid = valid_id(message.get('mid'))
        if not mid:
            continue
        try:
            verified = incoming_message({'messages': messages}, int(friend), int(mid))
            attachment_params(verified)
        except ResumeValidationError:
            continue
        body = verified.get('body') or {}
        link = body.get('hyperLink') or {}
        filename = ''
        for source in (link, body):
            for key in ('fileName', 'filename', 'title', 'text'):
                value = source.get(key)
                if isinstance(value, str) and re.search(r'\.(pdf|docx?|png|jpe?g)$', value.strip(), re.I):
                    filename = re.sub(r'[\x00-\x1f\x7f]', '', value).replace('\\', '/').rsplit('/', 1)[-1][:240]
                    break
            if filename:
                break
        # Prefer an explicit message job ID; never infer from resume keywords.
        job = message.get('encryptJobId') or body.get('encryptJobId') or binding.get('encryptJobId') or binding.get('encJobId') or ''
        yield mid, {'origin': 'boss_chat', 'jobId': str(job)[:180],
                    'jobName': str(binding.get('jobName') or '')[:200],
                    'name': str(binding.get('name') or binding.get('geekName') or '')[:100],
                    'originalFileName': filename, 'receivedAt': flow.seconds(message.get('time'))}


def inspect(c, db, conversation):
    friend = conversation['friend']
    fresh = conversation['dirty'] or conversation['fresh_before']
    before = conversation['fresh_before'] if fresh else conversation['history_before']
    args = ['hr', 'chatmsg', friend, '--count', '100']
    if before:
        args += ['--max-msg-id', str(before)]
    result = c.cli_json(*args)
    if result.get('ok') is not True:
        db.execute('UPDATE chat_resume_conversations SET retry_at=? WHERE friend=?', (time.time()+300, friend))
        return result
    # Ordinary text messages require only the history read. Resolve the exact
    # official job binding only when an incoming attachment actually exists.
    # Reconnection/latest-page scans see attachments already archived. Resolve
    # the official binding only for genuinely new receipts.
    existing_mids = {row[0] for row in db.execute('SELECT mid FROM chat_resume_receipts WHERE friend=?', (friend,))}
    attachments = [(mid,meta) for mid,meta in attachment_metadata(c,result,friend,{}) if mid not in existing_mids]
    detail_result = c.sourcing_friend_details([friend]) if attachments else {'ok': True, 'data': {}}
    if c.held_code():
        return detail_result
    bindings = (detail_result.get('data') or {}).get('friendList', []) if detail_result.get('ok') is True else []
    binding = next((r for r in bindings if isinstance(r, dict)
                    and valid_id(r.get('friendId') or r.get('uid')) == friend), {})
    person = db.execute('SELECT * FROM people WHERE friend=?', (friend,)).fetchone()
    for mid, metadata in attachment_metadata(c, result, friend, binding):
        if mid in existing_mids:
            continue
        metadata['receiptPriority'] = bool(conversation['signaled'] and fresh and
            ((conversation['latest_mid'] and int(mid) > conversation['latest_mid']) or
             time.time()-3600 <= metadata['receivedAt'] <= time.time()+60))
        rule = 'boss-chat-'+friend
        if person:
            known = json.loads(person['body'])
            if known.get('jobId') == metadata['jobId'] and (
                    person['rule'].startswith('active-') or known.get('replyAt')
                    or person['state'] in ('requested', 'request_uncertain', 'export', 'stored')):
                rule = person['rule']
                metadata['origin'] = 'active' if rule.startswith('active-') else 'passive'
        db.execute('INSERT OR IGNORE INTO chat_resume_receipts(friend,mid,rule,body) VALUES(?,?,?,?)',
                   (friend, mid, rule, json.dumps(metadata, ensure_ascii=False)))
    mids = [int(valid_id(m.get('mid'))) for m in c.resume_messages(result)
            if isinstance(m, dict) and valid_id(m.get('mid'))]
    low, high = min(mids, default=0), max(mids, default=0)
    # Advancing cursors and queuing every attachment are one durable transaction.
    more = len(c.resume_messages(result)) >= 100 and low > 1 and (not before or low-1 < before)
    if fresh:
        top = max(conversation['fresh_top'], high)
        if conversation['latest_mid'] == 0:
            # First page starts a separate historical cursor, so new messages
            # can be inspected while old attachments continue to backfill.
            db.execute('''UPDATE chat_resume_conversations SET latest_mid=?,dirty=0,
              history_before=?,history_done=?,last_read=?,retry_at=0,signaled=0 WHERE friend=?''',
                       (top, low-1 if more else 0, not more, time.time(), friend))
        elif more and low > conversation['latest_mid']:
            db.execute('UPDATE chat_resume_conversations SET fresh_before=?,fresh_top=?,dirty=0,last_read=? WHERE friend=?',
                       (low-1, top, time.time(), friend))
        else:
            db.execute('''UPDATE chat_resume_conversations SET latest_mid=?,fresh_before=0,
              fresh_top=0,dirty=dirty_again,signaled=dirty_again,dirty_again=0,last_read=?,retry_at=0 WHERE friend=?''', (top, time.time(), friend))
    else:
        db.execute('UPDATE chat_resume_conversations SET history_before=?,history_done=?,last_read=?,retry_at=0 WHERE friend=?',
                   (low-1 if more else 0, not more, time.time(), friend))
    return result


def download(c, db, row):
    """The receipt ledger authorizes this exact incoming attachment, not a send."""
    c.RESUME_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    metadata = json.loads(row['body'])
    allowed = {'.pdf', '.docx', '.doc', '.png', '.jpg', '.jpeg'}
    suffix = metadata.get('downloadSuffix') or Path(metadata.get('originalFileName', '')).suffix.lower()
    if suffix not in allowed:
        suffix = '.pdf'
    output = c.RESUME_DIR / (uuid.uuid4().hex+suffix)
    result = c.cli_json('hr', 'download-resume', row['friend'], '--message-id', row['mid'], '--output', str(output))
    if result.get('ok') is True and output.is_file():
        db.execute("UPDATE chat_resume_receipts SET file=?,state='export',error='' WHERE friend=? AND mid=?",
                   (str(output), row['friend'], row['mid']))
        return result
    output.unlink(missing_ok=True)
    error = result.get('error') or {}
    match = re.search(r'扩展名不符，请使用 (\.[a-z]+)(?:\s|$)', error.get('message', ''))
    if error.get('code') == 'INVALID_PARAM' and match and match[1] in allowed and match[1] != suffix:
        metadata['downloadSuffix'] = match[1]
        db.execute("UPDATE chat_resume_receipts SET body=?,retry_at=?,error='FORMAT_RETRY_WAIT' WHERE friend=? AND mid=?",
                   (json.dumps(metadata,ensure_ascii=False),time.time()+60,row['friend'],row['mid']))
        # The next paced turn retries the SDK-confirmed suffix, never an
        # immediate loop of five downloads of the same attachment.
        return {'ok': True}
    db.execute('UPDATE chat_resume_receipts SET retry_at=?,error=? WHERE friend=? AND mid=?',
               (time.time()+3600,str(error.get('code') or 'DOWNLOAD_FAILED')[:80],row['friend'],row['mid']))
    return result


def step(c, db, request):
    schema(db)
    for hint in request.get('activityHints', [])[:200]:
        if isinstance(hint, dict):
            wake(db, hint.get('friend'))
    now = time.time()
    if request.get('reconnectId') != flow.get_state(db, 'chat-resumes-reconnect', ''):
        flow.put_state(db, 'chat-resumes-reconnect', request.get('reconnectId', ''))
        # Keep the historical list deadline across reconnects. New activity
        # wakes its own conversation without resetting this slow sweep.
        db.execute('UPDATE chat_resume_conversations SET dirty=1,retry_at=0')
    delay_for = getattr(c, 'passive_delay', lambda **kwargs: 0)
    operation = None
    due = float(flow.get_state(db, 'chat-resumes-discover-at'))
    receipt = db.execute("""SELECT * FROM chat_resume_receipts WHERE state='found' AND retry_at<=?
      ORDER BY CASE WHEN json_extract(body,'$.receiptPriority')=1 OR
        json_extract(body,'$.receivedAt') BETWEEN ? AND ? THEN 0 ELSE 1 END,rowid LIMIT 1""",
      (now,now-3600,now+60)).fetchone()
    # Alternate downloads, newest reads and historical reads; no five-page cap.
    lane = flow.get_state(db, 'chat-resumes-lane', 'history')
    conversation = db.execute('''SELECT * FROM chat_resume_conversations WHERE retry_at<=?
      AND (dirty=1 OR fresh_before>0 OR history_done=0)
      ORDER BY signaled DESC,CASE WHEN ?='history' AND dirty=0 AND fresh_before=0 THEN 0
        WHEN ?!='history' AND (dirty=1 OR fresh_before>0) THEN 0 ELSE 1 END,
        CASE WHEN ?!='history' THEN -activity_at ELSE 0 END,last_read,friend LIMIT 1''',
                              (now, lane, lane, lane)).fetchone()
    metadata = json.loads(receipt['body']) if receipt else {}
    urgent = (bool(metadata.get('receiptPriority')) or
              (now-3600 <= flow.seconds(metadata.get('receivedAt')) <= now+60)) if receipt else bool(conversation and conversation['signaled'])
    if conversation and conversation['signaled'] and not urgent:
        urgent = True
        operation, cost = 'inspect', 2
    elif receipt:
        operation, cost = 'download', 8
    elif conversation and conversation['signaled']:
        operation, cost = 'inspect', 2
    elif due <= now and flow.get_state(db, 'chat-resumes-last', '') != 'discover':
        operation, cost = 'discover', 2
    elif conversation:
        operation, cost = 'inspect', 2
    elif receipt:
        operation, cost = 'download', 8
    elif due <= now:
        operation, cost = 'discover', 2
    # Historical receipt reads must leave capacity for admitted replies and
    # resume requests, as well as the existing manual-operation reserve.
    interval = 30 if urgent else 600
    last_turn = float(flow.get_state(db, 'chat-resumes-last-request-at', '0'))
    if (operation is None or now < last_turn+interval
            or delay_for(cost=cost, queued=False, receiving=urgent, history=not urgent)):
        return None
    # Reserve before network I/O; restart, preemption or repeated activity
    # notifications cannot erase the collector's pacing deadline.
    flow.put_state(db, 'chat-resumes-last-request-at', now)
    db.commit()
    result = discover(c, db) if operation == 'discover' else download(c, db, receipt) if operation == 'download' else inspect(c, db, conversation)
    flow.put_state(db, 'chat-resumes-last', operation)
    flow.put_state(db, 'chat-resumes-lane', {'history': 'fresh', 'fresh': 'download', 'download': 'history'}[lane])
    return result


def urgent(db, *, attachments_only=False):
    """Local-only scheduling; closed jobs and absent passive rules are allowed."""
    schema(db)
    now = time.time()
    return bool(db.execute("SELECT 1 FROM chat_resume_receipts WHERE state='found' AND retry_at<=? LIMIT 1",
                           (now,)).fetchone() or not attachments_only and db.execute(
        "SELECT 1 FROM chat_resume_conversations WHERE signaled=1 AND retry_at<=? "
        "AND (dirty=1 OR fresh_before>0) LIMIT 1", (now,)).fetchone())


def exports(db):
    schema(db)
    return [dict(r) for r in db.execute("SELECT * FROM chat_resume_receipts WHERE state='export' ORDER BY rowid LIMIT 1")]


def acknowledge(db, friend, mid):
    schema(db)
    db.execute("UPDATE chat_resume_receipts SET state='stored' WHERE friend=? AND mid=? AND state='export'", (friend, mid))
