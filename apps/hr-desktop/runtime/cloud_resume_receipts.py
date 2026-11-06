"""Restore confirmed cloud receipts before collecting on another computer."""
import json
import re
import time


def restore(db, request):
    import boss_chat_receipts
    import passive_runtime as flow
    account = request.get('accountKey', '')
    cursor = request.get('nextCursor')
    receipts = request.get('receipts')
    if (not re.fullmatch(r'[a-f0-9]{64}', account) or type(cursor) is not int or cursor < 0
            or not isinstance(receipts, list) or len(receipts) > 200):
        raise ValueError('云端简历收取记录无效')
    bound = flow.get_state(db, 'cloud-receipt-account', '')
    if bound and bound != account:
        raise ValueError('云端简历收取记录不属于当前账号')
    aliases = request.get('ruleAliases', [])
    if not isinstance(aliases, list) or len(aliases) > 1000:
        raise ValueError('职位关联记录无效')
    for alias in aliases:
        if (not isinstance(alias, dict) or not all(isinstance(alias.get(k), str)
                and re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', alias[k]) for k in ('from', 'to'))
                or alias['from'] == alias['to'] or not isinstance(alias.get('jobId'), str)
                or not 1 <= len(alias['jobId']) <= 180):
            raise ValueError('职位关联记录无效')
    clean = []
    for row in receipts:
        if not isinstance(row, dict) or not all(
                isinstance(row.get(k), str) and re.fullmatch(r'[1-9]\d{0,19}', row[k])
                for k in ('friendId', 'messageId')):
            raise ValueError('云端简历消息标识无效')
        if (row.get('origin') not in ('active', 'passive', 'boss_chat')
                or not re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', str(row.get('id', '')))
                or not re.fullmatch(r'[a-zA-Z0-9_-]{1,80}', str(row.get('ruleId', '')))
                or any(not isinstance(row.get(k, ''), str) or len(row.get(k, '')) > limit
                       for k, limit in [('jobId', 180), ('jobName', 200), ('name', 100)])):
            raise ValueError('云端简历来源无效')
        clean.append(row)
    boss_chat_receipts.schema(db)
    for alias in aliases:
        rebind_rule(db, alias)
    for row in clean:
        friend, mid, rule = row['friendId'], row['messageId'], row['ruleId']
        metadata = {k: row.get(k, '') for k in ('origin', 'jobId', 'jobName', 'name')}
        metadata['cloudReceiptId'] = row.get('id', '')
        body = json.dumps(metadata, ensure_ascii=False)
        # The cloud acknowledgement, not a local file's existence, is the
        # authority for skipping this exact attachment on either collector.
        db.execute('''INSERT INTO chat_resume_receipts(friend,mid,rule,body,state)
            VALUES(?,?,?,?,'stored') ON CONFLICT(friend,mid) DO UPDATE SET
            state='stored',rule=excluded.rule,body=excluded.body,error='',retry_at=0''',
                   (friend, mid, rule, body))
        person = db.execute('SELECT * FROM people WHERE friend=?', (friend,)).fetchone()
        if not person:
            db.execute("INSERT INTO people(friend,rule,body,state,updated,mid) VALUES(?,?,?,'stored',?,?)",
                       (friend, rule, body, int(time.time()), mid))
        elif person['mid'] == mid or (person['state'] == 'stored' and person['mid']
                and json.loads(person['body']).get('cloudReceiptId') and int(mid) > int(person['mid'])) or (not person['mid'] and person['state'] in (
                'seen', 'inspect', 'inspect_review', 'reply_ready', 'ready', 'requested', 'request_uncertain')
                and json.loads(person['body']).get('jobId') == metadata['jobId']):
            # Preserve actual reply/request evidence while keeping the cloud's
            # authoritative source. Never overwrite a newer local attachment.
            merged = {**json.loads(person['body']), **metadata}
            db.execute("UPDATE people SET state='stored',rule=?,body=?,mid=? WHERE friend=?",
                       (rule, json.dumps(merged, ensure_ascii=False), mid, friend))
    flow.put_state(db, 'cloud-receipt-account', account)
    flow.put_state(db, 'cloud-receipt-cursor', cursor)
    # Recompute counts from durable states, not the previous poll's summary.
    previous = json.loads(flow.get_state(db, 'status', '{}'))
    result = flow.status(db, previous.get('phase', 'watching'), restoredReceipts=len(clean))
    result['data'] = {**previous, **result['data']}
    return flow.activity_status(db, result)


def rebind_rule(db, alias):
    """Keep Windows' in-flight conversation ledger after a verified merge."""
    old, new, job = alias['from'], alias['to'], alias['jobId']
    for row in db.execute('SELECT friend,body FROM people WHERE rule=?', (old,)).fetchall():
        body = json.loads(row['body'])
        if body.get('jobId') != job:
            continue
        if body.get('ruleId') == old:
            body['ruleId'] = new
        db.execute('UPDATE people SET rule=?,body=? WHERE friend=? AND rule=?',
                   (new, json.dumps(body, ensure_ascii=False), row['friend'], old))
        db.execute('UPDATE recovery_windows SET rule=? WHERE friend=? AND rule=?', (new, row['friend'], old))
    db.execute("UPDATE chat_resume_receipts SET rule=? WHERE rule=? AND json_extract(body,'$.jobId')=?",
               (new, old, job))
    task = db.execute("SELECT * FROM catchup WHERE kind='job' AND target=?", (old,)).fetchone()
    if task:
        existing = db.execute("SELECT 1 FROM catchup WHERE kind='job' AND target=?", (new,)).fetchone()
        if not existing:
            db.execute("UPDATE catchup SET target=? WHERE kind='job' AND target=?", (new, old))
        else:
            # Keep both cursors' unread range and the later cooldown. This is
            # read-only recovery, never a fresh authorization to send again.
            db.execute("""UPDATE catchup SET page=MIN(page,?),since=MIN(since,?),
                done=MIN(done,?),retry_at=MAX(retry_at,?),fingerprint=''
                WHERE kind='job' AND target=?""",
                       (task['page'], task['since'], task['done'], task['retry_at'], new))
            db.execute("DELETE FROM catchup WHERE kind='job' AND target=?", (old,))
    for prefix in ('history-started:', 'history-complete:', 'discovery-complete:'):
        db.execute('INSERT OR IGNORE INTO state(key,value) SELECT ?,value FROM state WHERE key=?',
                   (prefix+new, prefix+old))


def stored(db, friend, mid):
    if not db.execute("SELECT 1 FROM sqlite_master WHERE name='chat_resume_receipts'").fetchone():
        return False
    return bool(db.execute("""SELECT 1 FROM chat_resume_receipts
                           WHERE friend=? AND mid=? AND state='stored'
                           AND COALESCE(json_extract(body,'$.cloudReceiptId'),'')<>''""",
                           (str(friend), str(mid))).fetchone())
