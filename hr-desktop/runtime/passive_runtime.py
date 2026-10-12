"""Bounded opt-in workflow over the pinned SDK; durable before every write."""
import contextlib
import datetime
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import time
import uuid

REQUEST_TEXT = '方便发一份简历过来吗？'

def budget_delay(path, hard_limit, *, cost=1, queued=False, user_initiated=False):
    """Allocate existing capacity; the shared HTTP/CDP hard guard still applies."""
    if not Path(path).exists():
        return 0
    now = time.time()
    with contextlib.closing(sqlite3.connect(f'file:{path}?mode=ro', uri=True)) as db:
        times = [r[0] for r in db.execute('SELECT at FROM requests WHERE at>? ORDER BY at', (now-3600,))]
    # Passive list/receipt refreshes use the smaller pool. Already-triggered
    # conversations may use remaining capacity while retaining 20 for HR.
    limit = hard_limit if user_initiated else max(0, min(hard_limit-20, hard_limit if queued else 82))
    needed = len(times) + cost - limit
    if needed <= 0:
        return 0
    if needed > len(times):
        return 3600
    return max(30, times[needed-1]+3601-now)


@contextlib.contextmanager
def database(root):
    path = Path(root) / 'passive.sqlite3'
    with contextlib.closing(sqlite3.connect(path, timeout=10)) as db:
        db.row_factory = sqlite3.Row
        db.executescript('''
          CREATE TABLE IF NOT EXISTS state(key TEXT PRIMARY KEY,value TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS people(friend TEXT PRIMARY KEY,rule TEXT NOT NULL,body TEXT NOT NULL,
            state TEXT NOT NULL,updated INTEGER NOT NULL,file TEXT NOT NULL DEFAULT '',mid TEXT NOT NULL DEFAULT '');
          CREATE TABLE IF NOT EXISTS commands(id TEXT PRIMARY KEY,friend TEXT NOT NULL,message TEXT NOT NULL,
            state TEXT NOT NULL,reported INTEGER NOT NULL DEFAULT 0);
          CREATE TABLE IF NOT EXISTS events(id TEXT PRIMARY KEY,body TEXT NOT NULL,reported INTEGER NOT NULL DEFAULT 0);
          CREATE TABLE IF NOT EXISTS recovery_windows(
            friend TEXT PRIMARY KEY,rule TEXT NOT NULL,enabled_at INTEGER NOT NULL,
            since INTEGER NOT NULL,authorized_at INTEGER NOT NULL,reason TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS activity(
            friend TEXT PRIMARY KEY,job TEXT NOT NULL,mid TEXT NOT NULL,at REAL NOT NULL,
            retry_at REAL NOT NULL DEFAULT 0,done INTEGER NOT NULL DEFAULT 0);
          CREATE TABLE IF NOT EXISTS catchup(
            kind TEXT NOT NULL,target TEXT NOT NULL,page INTEGER NOT NULL DEFAULT 1,
            since REAL NOT NULL DEFAULT 0,started REAL NOT NULL,
            done INTEGER NOT NULL DEFAULT 0,retry_at REAL NOT NULL DEFAULT 0,
            fingerprint TEXT NOT NULL DEFAULT '',PRIMARY KEY(kind,target));
        ''')
        path.chmod(0o600)
        with db:
            yield db


def rows(result):
    data = result.get('data', {})
    if isinstance(data, list): return data
    if isinstance(data, dict):
        for key in ('friendList', 'result', 'list'):
            if isinstance(data.get(key), list): return data[key]
    return []


def discovery_complete(result):
    data = result.get('data', {})
    if not isinstance(data, dict):
        return False
    if 'hasMore' in data:
        return data['hasMore'] is False or data['hasMore'] == 0
    # Pinned SDK's filterByLabel endpoint returns the complete matching UID
    # index as {"result": [...]}, even when passed page=2. Verified against the
    # official page's allList$. Do not apply this to unknown/paginated shapes.
    return set(data) == {'result'} and isinstance(data['result'], list)


def human_reply(messages, friend, since, excluded=()):
    """UID direction is authoritative. No heuristic on source=0 vs source=1."""
    incoming = []
    for item in messages:
        if not isinstance(item, dict): continue
        sender, target, body = item.get('from') or {}, item.get('to') or {}, item.get('body') or {}
        stamp = item.get('time')
        if (all(isinstance(v, dict) for v in (sender, target, body))
                and str(sender.get('uid')) == friend and str(target.get('uid', '')).isdecimal()
                and body.get('type') == 1 and isinstance(body.get('text'), str) and body['text'].strip()
                and isinstance(stamp, (int, float)) and not isinstance(stamp, bool)):
            incoming.append((str(target['uid']), stamp/1000 if stamp >= 1e12 else stamp))
    for item in messages:
        if not isinstance(item, dict): continue
        sender, target, body = item.get('from') or {}, item.get('to') or {}, item.get('body') or {}
        if not all(isinstance(value, dict) for value in (sender, target, body)): continue
        timestamp = item.get('time')
        if isinstance(timestamp, bool) or not isinstance(timestamp, (float, int)): continue
        seconds = timestamp/1000 if timestamp >= 1e12 else timestamp
        text = body.get('text')
        if (seconds > since and seconds <= time.time()+60 and body.get('type') == 1
                and str(sender.get('uid', '')).isdecimal() and str(sender.get('uid')) != friend
                and str(target.get('uid')) == friend and isinstance(text, str) and text.strip()
                and text != REQUEST_TEXT and text not in excluded
                and any(recruiter == str(sender['uid']) and stamp <= seconds for recruiter, stamp in incoming)):
            return True
    return False


def seconds(value):
    if isinstance(value, bool): return 0
    if isinstance(value, str) and value.isdecimal(): value = int(value)
    if isinstance(value, (int, float)):
        return value/1000 if value >= 1e12 else value
    return 0


def incoming_trigger(messages, friend, since):
    """Only an actual candidate message in this opt-in window can start a flow."""
    valid = []
    for item in messages:
        if not isinstance(item, dict): continue
        sender, target, body = item.get('from') or {}, item.get('to') or {}, item.get('body') or {}
        if not all(isinstance(v, dict) for v in (sender, target, body)): continue
        stamp = seconds(item.get('time'))
        if (str(sender.get('uid')) == friend and str(target.get('uid', '')).isdecimal()
                and str(target['uid']) != friend and since < stamp <= time.time()+60
                and body.get('type') == 1 and isinstance(body.get('text'), str) and body['text'].strip()):
            valid.append(stamp)
    return max(valid, default=0)


def activity(item):
    # Used for queue priority only. Full message UIDs/timestamps authorize writes.
    for key in ('lastTS', 'lastTime', 'updateTime', 'time', 'timestamp'):
        stamp = seconds(item.get(key))
        if stamp: return stamp
    value = item.get('last_time')
    if isinstance(value, str):
        now = datetime.datetime.now()
        if re.fullmatch(r'\d{1,2}:\d{2}', value):
            try:
                return datetime.datetime.combine(now.date(), datetime.datetime.strptime(value, '%H:%M').time()).timestamp()
            except ValueError: return 0
        if value == '昨天':
            return datetime.datetime.combine(now.date()-datetime.timedelta(days=1), datetime.time()).timestamp()
        try:
            result = datetime.datetime.strptime(f'{now.year}-{value}', '%Y-%m-%d %H:%M')
            if result > now+datetime.timedelta(days=1): result = result.replace(year=now.year-1)
            return result.timestamp()
        except ValueError: pass
    return 0


def get_state(db, key, default='0'):
    row = db.execute('SELECT value FROM state WHERE key=?', (key,)).fetchone()
    return row['value'] if row else default


def put_state(db, key, value):
    db.execute('INSERT OR REPLACE INTO state VALUES(?,?)', (key, str(value)))


def trigger_since(db, friend, rule):
    # Only an explicitly recorded, candidate-specific recovery can look back
    # before opt-in. Account isolation comes from this session's database path.
    row = db.execute('SELECT * FROM recovery_windows WHERE friend=? AND rule=? AND enabled_at=?',
                     (friend, rule['id'], rule['enabledAt'])).fetchone()
    if row and row['reason'] and 0 < row['since'] <= row['authorized_at'] <= time.time()+60:
        return min(rule['enabledAt'], row['since'])
    return rule['enabledAt']


def history_enabled(rule):
    # Opening a job alone is never consent. Only an enabled passive rule for an
    # officially open job reaches this path. Active follow-up has its own scope.
    return rule.get('historyBackfill', True) is True and not rule.get('activeFriend')


def admit_history(db, friend, rule):
    """Durable, candidate/rule/enable-scoped permission to inspect retained history."""
    if not history_enabled(rule):
        return
    db.execute('''INSERT INTO recovery_windows VALUES(?,?,?,?,?,?)
      ON CONFLICT(friend) DO UPDATE SET rule=excluded.rule,enabled_at=excluded.enabled_at,
        since=excluded.since,authorized_at=excluded.authorized_at,reason=excluded.reason
      WHERE recovery_windows.rule=excluded.rule
        AND recovery_windows.enabled_at!=excluded.enabled_at''',
      (friend, rule['id'], rule['enabledAt'], 1, int(time.time()),
       '用户开启本职位被动邀约：补处理历史招呼及附件，保留防重复记录'))


def prepare_history(db, rules):
    """Admit each opted-in job once; retain cursors and all send/receipt ledgers."""
    for rule in rules.values():
        if not history_enabled(rule):
            continue
        token = str(rule['enabledAt'])
        started = 'history-started:' + rule['id']
        complete = 'history-complete:' + rule['id']
        if get_state(db, started, '') != token:
            put_state(db, started, token)
            db.execute('''INSERT OR REPLACE INTO catchup(kind,target,since,started)
              VALUES('job',?,1,?)''', (rule['id'], time.time()))
            for person in db.execute('SELECT * FROM people WHERE rule=?', (rule['id'],)).fetchall():
                detail = json.loads(person['body'])
                if (person['state'] != 'seen' or detail.get('jobId') != rule['jobId']
                        or detail.get('enabledAt') != rule['enabledAt']):
                    continue
                admit_history(db, person['friend'], rule)
                db.execute("UPDATE people SET state='inspect',updated=? WHERE friend=?",
                           (int(time.time()), person['friend']))
                event(db, 'HISTORY_BACKFILL_QUEUED', detail)
        elif get_state(db, complete, '') != token:
            # A closed job may have suspended its task. Reopen its saved cursor
            # without making any earlier confirmed operation eligible again.
            db.execute("UPDATE catchup SET done=0 WHERE kind='job' AND target=? AND since=1",
                       (rule['id'],))


def event(db, code, detail):
    value = {'id': uuid.uuid4().hex, 'code': code,
             'name': detail.get('name', ''), 'jobName': detail.get('jobName', ''),
             'jobId': detail.get('jobId', ''), 'ruleId': detail.get('ruleId', '')}
    db.execute('INSERT INTO events VALUES(?,?,0)', (value['id'], json.dumps(value, ensure_ascii=False)))


def status(db, phase, **extra):
    counts = {}
    for row in db.execute('SELECT rule,state,count(*) AS n FROM people GROUP BY rule,state'):
        counts.setdefault(row['rule'], {})[row['state']] = row['n']
    if phase == 'watching':
        if any(group.get('job_review') for group in counts.values()):
            phase = 'job_review'
        elif any(group.get('inspect_review') for group in counts.values()):
            phase = 'conversation_review'
    last = db.execute("SELECT MAX(updated) FROM people WHERE state IN "
                      "('reply_ready','ready','requested','export','stored')").fetchone()[0]
    progress = {row['rule']: row['last'] for row in db.execute(
        "SELECT rule,MAX(updated) AS last FROM people WHERE state IN "
        "('reply_ready','ready','requested','export','stored') GROUP BY rule")}
    histories = {}
    for row in db.execute("SELECT key,value FROM state WHERE key LIKE 'history-started:%'"):
        rule_id = row['key'].split(':', 1)[1]
        task = db.execute("SELECT page FROM catchup WHERE kind='job' AND target=?", (rule_id,)).fetchone()
        histories[rule_id] = {'complete': get_state(db, 'history-complete:'+rule_id, '') == row['value'],
                              'page': task['page'] if task else 1}
    value = {'phase': phase, 'checkedAt': int(time.time()), 'counts': counts,
             'lastProgressAt': last or 0, 'progressByRule': progress, 'historyByRule': histories, **extra}
    put_state(db, 'status', json.dumps(value))
    return {'ok': True, 'data': value}


def discover(c, db, rules, rule=None, page=1, capture=None):
    if rule is None:
        index = int(get_state(db, 'rule-index'))
        ordered = sorted(rules); rule = rules[ordered[index % len(ordered)]]
        put_state(db, 'rule-index', index+1)
    started = time.time()
    put_state(db, 'discoveredAt', started); db.commit()
    # Always revisit recent conversations; draining an entire historical queue
    # first used to postpone fresh messages by hours.
    result = c.cli_json('hr', 'chat', '--job-id', rule['jobId'], '--page', str(page))
    if result.get('ok') is not True: return result
    if capture is not None:
        capture.update(result=result)
    for position, item in enumerate(rows(result)):
        if not isinstance(item, dict): continue
        friend = str(item.get('friendId') or item.get('friend_id') or item.get('uid') or item.get('gid') or '')
        if not friend.isdecimal() or not 1 <= len(friend) <= 20: continue
        if rule.get('activeFriend') and friend != rule['activeFriend']: continue
        existing_person = db.execute('SELECT rule,body FROM people WHERE friend=?', (friend,)).fetchone()
        if existing_person and (existing_person['rule'] != rule['id']
                                or json.loads(existing_person['body']).get('jobId') != rule['jobId']):
            # A friend ID is account-wide. Never move an existing conversation
            # or its reply/request ledger into another job's queue.
            continue
        info = item.get('geekCard') or item.get('geekInfo') or item.get('geekBaseInfo') or item
        if not isinstance(info, dict): info = item
        def first(*keys):
            for source in (info, item):
                for key in keys:
                    value = source.get(key)
                    if isinstance(value, str) and value.strip(): return value.strip()
            return ''
        # The filter endpoint can return UID-only rows. When it does return
        # an encrypted position ID, never relabel another job's conversation.
        actual_job = first('encryptJobId', 'encJobId')
        if actual_job and actual_job != rule['jobId']:
            continue
        admit_history(db, friend, rule)
        detail = {'name': first('geekName', 'name', 'nickname')[:100],
                  'education': first('geekDegree', 'degreeName', 'degreeDesc', 'education')[:100],
                  'experience': first('geekWorkYear', 'workYearDesc', 'work_years', 'experience')[:100],
                  'city': first('expectLocationName', 'locationName', 'city')[:100],
                  'company': first('companyName', 'company')[:200], 'jobName': rule.get('jobName', ''),
                  'jobId': rule['jobId'], 'ruleId': rule['id'],
                  'enabledAt': rule['enabledAt'], 'activity': activity(item), 'position': position,
                  'unread': bool(item.get('unread') or item.get('unreadMsgCount') or item.get('newMsgCount'))}
        if rule.get('activeFriend'):
            previous = db.execute('SELECT body FROM people WHERE friend=?', (friend,)).fetchone()
            if previous:
                detail = {**json.loads(previous['body']), **detail}
        # Persist only a digest of the message summary, never the chat content.
        detail['fingerprint'] = hashlib.sha256(json.dumps(
            [item.get(k) for k in ('last_msg', 'last_time', 'lastTS', 'lastTime', 'updateTime',
                                  'lastMsgInfo', 'lastMessageInfo', 'unread', 'unreadMsgCount', 'newMsgCount')],
            ensure_ascii=False, sort_keys=True).encode()).hexdigest()
        old = db.execute('SELECT * FROM people WHERE friend=?', (friend,)).fetchone()
        old_detail = json.loads(old['body']) if old else {}
        fresh = not detail['activity'] or detail['activity'] >= trigger_since(db, friend, rule)-60
        if not old:
            db.execute("INSERT INTO people(friend,rule,body,state,updated) VALUES(?,?,?,?,?)",
                       (friend, rule['id'], json.dumps(detail, ensure_ascii=False), 'inspect' if fresh else 'seen', int(time.time())))
        elif old['state'] in ('seen', 'inspect', 'inspect_review', 'reply_ready', 'ready') and (
                old_detail.get('enabledAt') != rule['enabledAt']):
            # Do not carry readiness across a disable/re-enable.
            db.execute("UPDATE people SET rule=?,body=?,state=?,updated=? WHERE friend=?",
                       (rule['id'], json.dumps(detail, ensure_ascii=False), 'inspect' if fresh else 'seen', int(time.time()), friend))
        elif old['rule'] == rule['id'] and old['state'] in ('seen', 'inspect', 'inspect_review'):
            changed = detail['fingerprint'] != old_detail.get('fingerprint')
            if old['state'] == 'inspect_review':
                # An index refresh/unread toggle is not a reason to repeat a
                # rejected history request. Only newer activity reopens reads.
                if detail['activity'] <= max(old_detail.get('activity', 0),
                                             old_detail.get('inspectionFailedAt', 0)):
                    continue
            db.execute("UPDATE people SET body=?,state=?,updated=? WHERE friend=?",
                       (json.dumps(detail, ensure_ascii=False),
                        'inspect' if fresh and (changed or old['state'] == 'inspect') else 'seen',
                        int(time.time()), friend))
    if capture is None:
        since = max(rule['enabledAt'], float(get_state(db, 'discovery-complete:'+rule['id'], str(rule['enabledAt'])))-60)
        entries = rows(result)
        stamps = [activity(item) for item in entries if isinstance(item, dict)]
        complete = not entries or discovery_complete(result)
        complete = complete or bool(stamps and all(stamps) and min(stamps) < since)
        pending = db.execute("SELECT 1 FROM catchup WHERE kind='job' AND target=? AND done=0", (rule['id'],)).fetchone()
        if not pending:
            if complete:
                put_state(db, 'discovery-complete:'+rule['id'], started)
            elif get_state(db, 'reconnectId', ''):
                # A burst can fill page one even while connected. Continue the
                # unread/previously-read backlog rather than advance past it.
                db.execute("INSERT OR REPLACE INTO catchup(kind,target,page,since,started) VALUES('job',?,2,?,?)",
                           (rule['id'], since, started))
    return status(db, 'watching')


def receive(c, db, friend=None, reconnect=False):
    put_state(db, 'receivedAt', time.time()); db.commit()
    c.RESUME_POLL_LIMIT = 1
    with c.resume_db() as tracked:
        saved = tracked.execute("SELECT state FROM resume_requests WHERE friend_id=?", (friend,)).fetchone() if friend else None
    outcome = {'checked': True, 'more': False}
    if not saved or saved['state'] != 'received':
        if friend:
            options = {'friend_id': friend}
            if reconnect: options['reconnect'] = True
            outcome = c.poll_resumes_once(**options)
        else:
            outcome = c.poll_resumes_once()
    if c.held_code(): return {'ok': False, 'error': {'code': c.held_code()}}
    with c.resume_db() as tracked:
        received = {r['friend_id']: dict(r) for r in tracked.execute("SELECT * FROM resume_requests WHERE state='received'")}
    candidate = next((r for r in db.execute(
                      "SELECT * FROM people WHERE state IN ('requested','request_uncertain') "
                      "ORDER BY CASE WHEN friend=? THEN 0 ELSE 1 END,updated", (friend,))
                      if r['friend'] in received), None)
    if candidate:
        mid = received[candidate['friend']]['message_id']
        file = str(c.download_resume(candidate['friend'], mid))
        detail = json.loads(candidate['body'])
        with c.resume_db() as tracked:
            original = tracked.execute('SELECT original_name FROM resume_files WHERE friend_id=? AND message_id=?',
                                       (candidate['friend'], mid)).fetchone()
        if original:
            detail['originalFileName'] = original['original_name']
        db.execute("UPDATE people SET state='export',body=?,file=?,mid=?,updated=? WHERE friend=?",
                   (json.dumps(detail, ensure_ascii=False), file, mid, int(time.time()), candidate['friend']))
        event(db, 'RESUME_RECEIVED', detail)
    return status(db, 'receiving', receiptChecked=outcome.get('checked', False) if isinstance(outcome, dict) else True,
                  receiptMore=outcome.get('more', False) if isinstance(outcome, dict) else False)


def prepare_catchup(c, db, request, rules):
    token = request.get('reconnectId')
    if not isinstance(token, str) or not re.fullmatch(r'[a-zA-Z0-9-]{1,80}', token):
        return
    if get_state(db, 'reconnectId', '') == token:
        return
    now = time.time()
    # No sent/requested/exported ledger is reset. Reconnection only queues reads.
    unfinished_history = {row['target']: dict(row) for row in db.execute(
        "SELECT * FROM catchup WHERE kind='job' AND since=1 AND done=0")}
    db.execute('DELETE FROM catchup')
    with c.resume_db() as tracked:
        receipts = list(tracked.execute("SELECT friend_id FROM resume_requests WHERE state IN ('waiting','uncertain','received')"))
    for item in receipts:
        person = db.execute('SELECT state FROM people WHERE friend=?', (item['friend_id'],)).fetchone()
        if person and person['state'] in ('requested', 'request_uncertain'):
            db.execute("INSERT INTO catchup(kind,target,started) VALUES('receipt',?,?)", (item['friend_id'], now))
    for rule in rules.values():
        since = max(rule['enabledAt'], float(get_state(db, 'discovery-complete:'+rule['id'], str(rule['enabledAt'])))-60)
        history_pending = (history_enabled(rule) and
                           get_state(db, 'history-complete:'+rule['id'], '') != str(rule['enabledAt']))
        prior = unfinished_history.get(rule['id'], {}) if history_pending else {}
        db.execute("INSERT INTO catchup(kind,target,page,since,started,fingerprint) VALUES('job',?,?,?,?,?)",
                   (rule['id'], prior.get('page', 1), 1 if history_pending else since,
                    prior.get('started', now), prior.get('fingerprint', '')))
    put_state(db, 'reconnectId', token)
    put_state(db, 'catchupTurn', 'receipt')
    db.commit()


def catchup_step(c, db, rules, delay_for):
    """A durable, serialized pass over missed receipts and paginated greetings."""
    now = time.time()
    turn = get_state(db, 'catchupTurn', 'receipt')
    work = list(db.execute("SELECT * FROM catchup WHERE done=0 AND retry_at<=? "
                           "ORDER BY CASE WHEN kind=? THEN 0 ELSE 1 END,started,target", (now, turn)))
    for task in work:
        friend = task['target']
        if task['kind'] == 'receipt':
            person = db.execute('SELECT state FROM people WHERE friend=?', (friend,)).fetchone()
            with c.resume_db() as tracked:
                receipt = tracked.execute('SELECT state FROM resume_requests WHERE friend_id=?', (friend,)).fetchone()
                last = tracked.execute('SELECT last_polled_at FROM resume_poll_state WHERE friend_id=?', (friend,)).fetchone()
            if not person or person['state'] not in ('requested', 'request_uncertain') or not receipt:
                db.execute("UPDATE catchup SET done=1 WHERE kind='receipt' AND target=?", (friend,))
                continue
            interval = max(0, last[0]+30-now) if last and receipt['state'] != 'received' else 0
            delay = max(interval, delay_for(cost=4, queued=True))
            if delay:
                db.execute("UPDATE catchup SET retry_at=? WHERE kind='receipt' AND target=?", (now+delay, friend))
                continue
            result = receive(c, db, friend, reconnect=True)
            if result.get('ok') is True:
                value = result.get('data', {})
                done = value.get('receiptChecked') is True and not value.get('receiptMore')
                db.execute("UPDATE catchup SET done=?,retry_at=? WHERE kind='receipt' AND target=?", (done, now+30, friend))
        else:
            rule = rules.get(task['target'])
            if not rule:
                db.execute("UPDATE catchup SET done=1 WHERE kind='job' AND target=?", (task['target'],))
                continue
            delay = delay_for(cost=2, queued=True)
            if delay:
                db.execute("UPDATE catchup SET retry_at=? WHERE kind='job' AND target=?", (now+delay, rule['id']))
                continue
            captured = {}
            result = discover(c, db, rules, rule, page=task['page'], capture=captured)
            if result.get('ok') is True:
                entries = rows(captured['result'])
                # Never rely on unread badges: another client can mark a missed
                # greeting read. Message UIDs and opt-in time still gate writes.
                fingerprints = [str(row.get('friendId') or row.get('uid') or row.get('gid') or '') for row in entries]
                fingerprint = hashlib.sha256(json.dumps(fingerprints).encode()).hexdigest()
                old_page = bool(entries) and all(activity(row) and activity(row) < task['since'] for row in entries)
                explicit_end = discovery_complete(captured['result'])
                complete = not entries or old_page or explicit_end
                if fingerprint == task['fingerprint'] and entries and not complete:
                    # A provider repeating the same page is not a completed sync.
                    # Retain its cursor and back off rather than loop or skip it.
                    db.execute("UPDATE catchup SET retry_at=? WHERE kind='job' AND target=?", (now+300, rule['id']))
                else:
                    db.execute("UPDATE catchup SET page=page+1,done=?,fingerprint=?,retry_at=? WHERE kind='job' AND target=?",
                               (complete, fingerprint, now+10, rule['id']))
                    if complete:
                        put_state(db, 'discovery-complete:'+rule['id'], task['started'])
                        if task['since'] == 1:
                            put_state(db, 'history-complete:'+rule['id'], rule['enabledAt'])
        put_state(db, 'catchupTurn', 'job' if task['kind'] == 'receipt' else 'receipt')
        put_state(db, 'nextAt', now+10)
        return result
    return None


def remember_activity(db, hints):
    for hint in hints[:200] if isinstance(hints, list) else []:
        if not isinstance(hint, dict): continue
        friend, mid, job = hint.get('friend', ''), hint.get('mid', ''), hint.get('job', '')
        at = seconds(hint.get('at'))
        if (not isinstance(friend, str) or not re.fullmatch(r'[1-9]\d{0,19}', friend)
                or not isinstance(mid, str) or not re.fullmatch(r'[1-9]\d{0,29}', mid)
                or not isinstance(job, str) or len(job) > 180
                or not time.time()-180 <= at <= time.time()+60):
            continue
        old = db.execute('SELECT mid FROM activity WHERE friend=?', (friend,)).fetchone()
        if old and int(old['mid']) >= int(mid): continue
        db.execute('INSERT OR REPLACE INTO activity VALUES(?,?,?,?,0,0)', (friend, job, mid, at))
    db.execute('DELETE FROM activity WHERE done=1 AND at<?', (time.time()-7*86400,))
    db.commit()


def activity_step(c, db, rules, delay_for):
    """Hints schedule reads only. The official message checks still authorize writes."""
    now = time.time()
    candidates = list(db.execute(
        "SELECT a.* FROM activity a LEFT JOIN people p ON p.friend=a.friend "
        "WHERE a.done=0 AND a.retry_at<=? ORDER BY "
        "CASE WHEN p.state IN ('requested','request_uncertain') THEN 0 ELSE 1 END,a.at", (now,)))
    for hint in candidates:
        friend = hint['friend']
        person = db.execute('SELECT * FROM people WHERE friend=?', (friend,)).fetchone()
        if person and hint['job'] and json.loads(person['body']).get('jobId') != hint['job']:
            # A signal from another position must not wake the old position.
            db.execute('UPDATE activity SET done=1 WHERE friend=?', (friend,))
            continue
        with c.resume_db() as tracked:
            saved = tracked.execute('SELECT state FROM resume_requests WHERE friend_id=?', (friend,)).fetchone()
        if person and person['state'] in ('requested', 'request_uncertain') and saved and saved['state'] in ('waiting', 'uncertain', 'received'):
            with c.resume_db() as tracked:
                last = tracked.execute('SELECT last_polled_at FROM resume_poll_state WHERE friend_id=?', (friend,)).fetchone()
            interval = max(0, last[0]+30-now) if last and saved['state'] != 'received' else 0
            delay = max(interval, delay_for(cost=4, queued=True))
            if delay:
                db.execute('UPDATE activity SET retry_at=? WHERE friend=?', (now+delay, friend))
                continue
            result = receive(c, db, friend)
        else:
            rule = rules.get(person['rule']) if person else next(
                (rule for rule in rules.values() if rule['jobId'] == hint['job']), None)
            if not rule or (person and person['state'] not in ('seen', 'inspect', 'inspect_review')):
                db.execute('UPDATE activity SET done=1 WHERE friend=?', (friend,))
                continue
            if person:
                # Wake an already known conversation without another friend-list
                # fetch. A later chatmsg read rechecks sender, opt-in and direction.
                detail = json.loads(person['body'])
                if detail.get('enabledAt') == rule['enabledAt']:
                    if person['state'] == 'inspect_review' and hint['at'] <= detail.get('inspectionFailedAt', 0):
                        db.execute('UPDATE activity SET done=1 WHERE friend=?', (friend,))
                        continue
                    detail['activity'] = hint['at']
                    detail.pop('inspectionFailedAt', None)
                    detail.pop('inspectionErrorCode', None)
                    db.execute("UPDATE people SET state='inspect',body=? WHERE friend=?",
                               (json.dumps(detail, ensure_ascii=False), friend))
                    db.execute('UPDATE activity SET done=1 WHERE friend=?', (friend,))
                    continue
            delay = delay_for(cost=2, queued=True)
            if delay:
                db.execute('UPDATE activity SET retry_at=? WHERE friend=?', (now+delay, friend))
                continue
            result = discover(c, db, rules, rule=rule)
            # One official list read covers a burst of incoming candidates for
            # the same job. New signals arriving during the call stay in JS.
            if result.get('ok') is True:
                db.execute('UPDATE activity SET done=1 WHERE job=?', (rule['jobId'],))
        if result.get('ok') is True:
            db.execute('UPDATE activity SET done=1 WHERE friend=?', (friend,))
        put_state(db, 'activityStreak', int(get_state(db, 'activityStreak'))+1)
        put_state(db, 'nextAt', now+10)
        return result
    return None


def activity_status(db, result):
    if result.get('ok') is not True: return result
    pending = db.execute('SELECT count(*),min(retry_at) FROM activity WHERE done=0').fetchone()
    exports = db.execute("SELECT count(*) FROM people WHERE state='export'").fetchone()[0]
    catchup = db.execute('SELECT count(*),min(retry_at) FROM catchup WHERE done=0').fetchone()
    value = result.setdefault('data', {})
    value.update(activityPending=pending[0], activityRetryAt=pending[1] or 0, exportsPending=exports,
                 catchupPending=catchup[0], catchupRetryAt=catchup[1] or 0)
    if catchup[0] and value.get('phase') in ('watching', 'receiving', 'disabled'):
        value['phase'] = 'catching_up'
    put_state(db, 'status', json.dumps(value))
    return result


def step(c, db, request):
    configured = [r for r in request.get('rules', []) if r.get('enabled') is True]
    contacts = request.get('activeContacts', [])
    # Enabled job monitoring is part of the opted-in communication flow. It
    # must be able to discover new conversations before there is a reply row.
    availability = c.managed_job_statuses(queued=True) if configured or contacts else {'statuses': {}}
    if c.held_code():
        return {'ok': False, 'error': {'code': c.held_code()}}
    states = availability['statuses']
    rules = {r['id']: r for r in configured if states.get(r['jobId']) == '开放中'}
    contacts = [r for r in contacts if states.get(r.get('jobId')) == '开放中']
    jobs_paused = bool(configured or request.get('activeContacts')) and not (rules or contacts)
    if contacts:
        import active_sourcing
        active_rules, failure = active_sourcing.rules(c, db, contacts)
        if failure: return failure
        rules.update(active_rules)
    now = time.time()
    delay_for = getattr(c, 'passive_delay', lambda **kwargs: 0)
    prepare_catchup(c, db, request, rules)
    prepare_history(db, rules)
    remember_activity(db, request.get('activityHints', []))
    queued = any(row['rule'] in rules for row in db.execute(
        "SELECT rule FROM people WHERE state IN ('inspect','reply_ready','ready')"))
    # Continuous incoming traffic may not starve conversations already admitted.
    result = activity_step(c, db, rules, delay_for) if (
        not queued or int(get_state(db, 'activityStreak')) < 2) else None
    if result is not None: return result
    # Alternate recovery reads with admitted work. Neither an offline backlog
    # nor a continuous stream of new arrivals can starve the other lane.
    if not queued or get_state(db, 'lastLane', 'work') != 'maintenance':
        result = catchup_step(c, db, rules, delay_for)
        if result is not None:
            put_state(db, 'lastLane', 'maintenance')
            return result
    previous = json.loads(get_state(db, 'status', '{}'))
    if now < float(get_state(db, 'nextAt')) and previous.get('phase') != 'budget_wait' and not queued:
        return {'ok': True, 'data': previous}
    put_state(db, 'nextAt', now+30); db.commit()
    put_state(db, 'activityStreak', 0)
    def wait_budget(delay):
        put_state(db, 'nextAt', now+delay)
        return status(db, 'budget_wait', retryAt=int(now+delay))
    pending = [r for r in db.execute("SELECT * FROM people WHERE state IN ('inspect','reply_ready','ready')")
               if r['rule'] in rules]
    # A budget wait can make every maintenance deadline overdue. Always taking
    # discovery first then consumes each newly available slot indefinitely.
    # Alternate maintenance with queued work, persisting the turn across waits
    # and process restarts. Oldest-due maintenance also keeps receipt polling
    # from being starved by list refreshes.
    if not pending or get_state(db, 'lastLane', 'work') != 'maintenance':
        due = []
        # Active local observation needs no remote polling. Keep a slower safety
        # sweep, and automatically restore the shorter sweep when the chat page
        # is unavailable, filtered, empty or no longer observed.
        sweep = 300 if request.get('activityWatching') is True else 120
        discovered = float(get_state(db, 'discoveredAt'))
        if rules and now-discovered >= sweep:
            due.append((discovered, 'discover'))
        with c.resume_db() as tracked:
            waiting = tracked.execute("SELECT 1 FROM resume_requests WHERE state IN ('waiting','uncertain') LIMIT 1").fetchone()
        waiting = waiting or db.execute("SELECT 1 FROM people WHERE state IN ('requested','request_uncertain') LIMIT 1").fetchone()
        received = float(get_state(db, 'receivedAt'))
        if waiting and now-received >= sweep:
            due.append((received, 'receive'))
        if due:
            delays = []
            for _, operation in sorted(due):
                delay = delay_for(cost=2 if operation == 'discover' else 4, queued=not pending)
                if not delay:
                    put_state(db, 'lastLane', 'maintenance')
                    return discover(c, db, rules) if operation == 'discover' else receive(c, db)
                delays.append(delay)
            if not pending:
                return wait_budget(min(delays))
    def priority(row):
        detail = json.loads(row['body'])
        return (0 if row['state'] == 'ready' else 1 if row['state'] == 'reply_ready' else 2,
                -detail.get('activity', 0), not detail.get('unread', False), detail.get('position', 999),
                row['updated'], row['friend'])
    pending.sort(key=priority)
    if not pending: return status(db, 'watching' if rules else 'jobs_paused' if jobs_paused else 'disabled')
    person = pending[0]; friend = person['friend']; rule = rules[person['rule']]
    detail = json.loads(person['body'])
    if detail.get('jobId') != rule['jobId']:
        db.execute("UPDATE people SET state='job_review' WHERE friend=?", (friend,))
        event(db, 'JOB_CONVERSATION_MISMATCH', detail)
        return status(db, 'job_review')
    if detail.get('enabledAt') != rule['enabledAt']:
        db.execute("UPDATE people SET state='seen' WHERE friend=?", (friend,))
        return status(db, 'watching')
    with c.resume_db() as tracked:
        existing = tracked.execute('SELECT state FROM resume_requests WHERE friend_id=?', (friend,)).fetchone()
    if existing:
        state = ('requested' if existing['state'] in ('waiting', 'received')
                 else 'request_uncertain' if existing['state'] == 'uncertain' else 'request_failed')
        db.execute("UPDATE people SET state=?,updated=? WHERE friend=?", (state, int(now), friend))
        return status(db, 'receiving' if state == 'requested' else 'needs_review')
    recovering = trigger_since(db, friend, rule) < rule['enabledAt']
    verification = {'jobId': rule['jobId'], 'enabledAt': rule['enabledAt']}
    verify_history_job = (person['state'] == 'inspect' and recovering
                          and detail.get('historyJobVerified') != verification)
    delay = delay_for(cost=2 if verify_history_job else
                     {'inspect': 1, 'reply_ready': 3, 'ready': 5}[person['state']], queued=True)
    if delay:
        return wait_budget(delay)
    put_state(db, 'lastLane', 'work')
    if person['state'] == 'inspect':
        if verify_history_job:
            verified = c.sourcing_friend_details([friend])
            if verified.get('ok') is not True:
                if (verified.get('error') or {}).get('code') == 'FRIEND_DETAIL_FAILED' and not c.held_code():
                    db.execute("UPDATE people SET state='job_review' WHERE friend=?", (friend,))
                    event(db, 'JOB_BINDING_UNAVAILABLE', detail)
                    return status(db, 'job_review')
                return verified
            official = (verified.get('data') or {}).get('friendList') or []
            binding = next((row for row in official if isinstance(row, dict)
                            and str(row.get('friendId') or row.get('uid')) == friend), {})
            if str(binding.get('encryptJobId') or binding.get('encJobId') or '') != rule['jobId']:
                db.execute("UPDATE people SET state='job_review' WHERE friend=?", (friend,))
                event(db, 'JOB_CONVERSATION_MISMATCH', detail)
                return status(db, 'job_review')
            detail['historyJobVerified'] = verification
            db.execute('UPDATE people SET body=? WHERE friend=?',
                       (json.dumps(detail, ensure_ascii=False), friend))
            db.commit()
        result = c.cli_json('hr', 'chatmsg', friend, '--count', '100')
        if result.get('ok') is not True:
            if (result.get('error') or {}).get('code') == 'UNKNOWN' and not c.held_code():
                # This conversation has not been verified. Do not send, and do
                # not let the same rejected read starve other opted-in work.
                detail['inspectionFailedAt'] = now
                detail['inspectionErrorCode'] = 'UNKNOWN'
                db.execute("UPDATE people SET state='inspect_review',body=?,updated=? WHERE friend=?",
                           (json.dumps(detail, ensure_ascii=False), int(now), friend))
                event(db, 'CHAT_READ_UNAVAILABLE', detail)
                return status(db, 'conversation_review')
            return result
        messages = c.resume_messages(result)
        since = trigger_since(db, friend, rule)
        if c.adopt_received_resume(result, friend, since) is True:
            db.execute("UPDATE people SET state='requested',updated=? WHERE friend=?", (int(now), friend))
            db.execute("INSERT OR REPLACE INTO catchup(kind,target,started) VALUES('receipt',?,?)", (friend, now))
            return status(db, 'receiving')
        if c.adopt_resume_request(result, friend, since) is True:
            db.execute("UPDATE people SET state='requested',updated=? WHERE friend=?", (int(now), friend))
            db.execute("INSERT OR REPLACE INTO catchup(kind,target,started) VALUES('receipt',?,?)", (friend, now))
            event(db, 'RESUME_REQUEST_RESTORED', detail)
            return status(db, 'receiving')
        trigger = incoming_trigger(messages, friend, since)
        excluded = {r[0] for r in db.execute('SELECT message FROM commands WHERE friend=?', (friend,))}
        state = 'seen'
        if trigger:
            state = 'ready' if rule.get('activeFriend') or human_reply(messages, friend, since, excluded) else 'reply_ready'
            detail['triggerAt'] = trigger
            event(db, 'INCOMING_DETECTED', detail)
        db.execute("UPDATE people SET state=?,body=?,updated=? WHERE friend=?",
                   (state, json.dumps(detail, ensure_ascii=False), int(now), friend))
        return status(db, 'reply_queued' if state == 'reply_ready' else 'request_queued' if state == 'ready' else 'watching')
    if person['state'] == 'reply_ready':
        template = request.get('replyTemplate')
        if not template: return status(db, 'template_required')
        message = render_reply(template['content'], detail, rule)
    elif now < detail.get('replyAt', 0)+60:
        return status(db, 'request_queued')
    if now < float(get_state(db, 'nextWriteAt')):
        return status(db, 'send_wait')
    try:
        c.prepare_recruiter_chat()
    except RuntimeError as error:
        if str(error).startswith('请在应用的 BOSS 页面'):
            result = status(db, 'chat_required'); result['data']['needsChat'] = True
            return result
        raise
    if person['state'] == 'reply_ready':
        detail['reply'] = {'message': message, 'templateId': template['id'], 'templateVersion': template['version']}
        # Durable before write, including crashes/ambiguous responses. Never resend.
        put_state(db, 'nextWriteAt', now+60)
        db.execute("UPDATE people SET state='reply_uncertain',body=?,updated=? WHERE friend=?",
                   (json.dumps(detail, ensure_ascii=False), int(now), friend)); db.commit()
        try:
            with c.conversation_scope(friend, rule['jobId']):
                result = c.cli_json('hr', 'reply', friend, message)
        except Exception:
            result = {'ok': False}
        if c.conversation_scope_error():
            db.execute("UPDATE people SET state='job_review' WHERE friend=?", (friend,))
            event(db, 'JOB_CONVERSATION_MISMATCH', detail)
            return status(db, 'job_review')
        sent = result.get('ok') is True and (result.get('data') or {}).get('sent') is True
        if sent:
            detail['replyAt'] = int(time.time())
            db.execute("UPDATE people SET state='ready',body=?,updated=? WHERE friend=?",
                       (json.dumps(detail, ensure_ascii=False), int(time.time()), friend))
        event(db, 'AUTO_REPLY_SENT' if sent else 'AUTO_REPLY_UNCERTAIN', detail)
        if c.held_code(): return {'ok': False, 'error': {'code': c.held_code()}}
        return status(db, 'request_queued' if sent else 'needs_review')
    put_state(db, 'nextWriteAt', now+60)
    db.execute("UPDATE people SET state='request_uncertain',updated=? WHERE friend=?", (int(now), friend)); db.commit()
    try:
        with c.conversation_scope(friend, rule['jobId']):
            c.request_resume(friend, retry_uncertain=False)
    except Exception:
        if c.conversation_scope_error():
            db.execute("UPDATE people SET state='job_review' WHERE friend=?", (friend,))
            event(db, 'JOB_CONVERSATION_MISMATCH', detail)
            return status(db, 'job_review')
        event(db, 'RESUME_REQUEST_UNCERTAIN', detail)
        if c.held_code(): return {'ok': False, 'error': {'code': c.held_code()}}
        return status(db, 'needs_review')
    db.execute("UPDATE people SET state='requested',updated=? WHERE friend=?", (int(time.time()), friend))
    event(db, 'RESUME_REQUESTED', detail)
    return status(db, 'receiving')


def render_reply(content, detail, rule):
    values = {'姓名': detail.get('name') or '您好', '职位': rule.get('jobName') or '该'}
    result = re.sub(r'\{(姓名|职位)\}', lambda match: values[match[1]], content)
    if not result.strip() or len(result) > 1000: raise ValueError('回复话术长度不正确')
    return result


def execute(c, root, request):
    import active_sourcing
    operation = request['operation']
    with database(root) as db:
        active_sourcing.schema(db)
        if operation == 'passive-local':
            import boss_chat_receipts
            # Recover only from the existing local request ledger after a crash;
            # never dispatch an unconfirmed write again.
            uncertain = list(db.execute("SELECT friend,body FROM people WHERE state='request_uncertain'"))
            if uncertain:
                with c.resume_db() as tracked:
                    for person in uncertain:
                        saved = tracked.execute('SELECT state FROM resume_requests WHERE friend_id=?',
                                                (person['friend'],)).fetchone()
                        if saved:
                            state = ('requested' if saved['state'] in ('waiting', 'received')
                                     else 'request_uncertain' if saved['state'] == 'uncertain' else 'request_failed')
                            db.execute('UPDATE people SET state=? WHERE friend=?', (state, person['friend']))
                            if state == 'requested':
                                event(db, 'RESUME_REQUESTED', json.loads(person['body']))
            exports = [dict(row) for row in db.execute("SELECT * FROM people WHERE state='export' ORDER BY updated LIMIT 1")]
            exports += boss_chat_receipts.exports(db)
            reports = [dict(row) for row in db.execute("SELECT * FROM commands WHERE reported=0 ORDER BY rowid LIMIT 1")]
            events = [json.loads(row['body']) for row in db.execute('SELECT body FROM events WHERE reported=0 ORDER BY rowid LIMIT 10')]
            return {'ok': True, 'data': {'exports': exports, 'reports': reports, 'events': events, 'bindings': active_sourcing.local(db),
                                         'status': json.loads(get_state(db, 'status', '{}')),
                                         'dailyGreeting': active_sourcing.daily_usage(db, time.time())}}
        if operation == 'passive-ack':
            if request.get('contactId'):
                active_sourcing.schema(db)
                db.execute('UPDATE active_contacts SET bound=1 WHERE id=? AND friend=?', (request['contactId'], request.get('friendId')))
            if request.get('friendId'):
                import boss_chat_receipts
                boss_chat_receipts.acknowledge(db, request['friendId'], request.get('messageId'))
                db.execute("UPDATE people SET state='stored' WHERE friend=? AND mid=? AND state='export'",
                           (request['friendId'], request.get('messageId')))
            if request.get('commandId'):
                db.execute('UPDATE commands SET reported=1 WHERE id=?', (request['commandId'],))
            if request.get('eventId'):
                db.execute('UPDATE events SET reported=1 WHERE id=?', (request['eventId'],))
            return {'ok': True}
        if c.held_code() or c.DISCONNECTED.exists() or c.RESUME_AUTO_PAUSE.exists():
            return {'ok': False, 'error': {'code': c.held_code() or 'REQUESTS_PAUSED', 'message': '自动任务保持暂停'}}
        if operation == 'sourcing-ready': return active_sourcing.readiness(c, db, request.get('command'))
        if operation == 'sourcing-send': return active_sourcing.send(c, db, request['command'])
        if operation == 'passive-send':
            command = request['command']; cid = command['id']; friend = c.positive_id(command['friendId'])
            previous = db.execute('SELECT * FROM commands WHERE id=?', (cid,)).fetchone()
            if previous: return {'ok': True, 'data': {'status': previous['state']}}
            text = command['message']
            if not isinstance(text, str) or not 1 <= len(text) <= 1000: raise ValueError('invalid message')
            # A claimed command has exactly one SDK write attempt, even if this
            # process dies or the cloud acknowledgement is lost afterwards.
            db.execute("INSERT INTO commands VALUES(?,?,?,'uncertain',0)", (cid, friend, text))
            db.commit()
            c.prepare_recruiter_chat()
            result = c.cli_json('hr', 'reply', friend, text)
            send_status = 'sent' if result.get('ok') is True and (result.get('data') or {}).get('sent') is True else 'uncertain'
            db.execute('UPDATE commands SET state=? WHERE id=?', (send_status, cid))
            return {'ok': True, 'data': {'status': send_status}}
        if operation != 'passive-step': raise ValueError('unsupported passive operation')
        # The collector may finish this turn before the communication lane runs.
        # Persist every hint first: JS acknowledges it when this call succeeds.
        remember_activity(db, request.get('activityHints', []))
        if request.get('collectAllResumes') is True:
            import boss_chat_receipts
            # The receive-only lane also runs with no rules or with closed jobs.
            # Alternate with the original flow; human commands dispatch earlier.
            turn = get_state(db, 'chat-resumes-turn', 'collect')
            configured = {r['id']: r for r in request.get('rules', []) if r.get('enabled') is True}
            pending = list(db.execute("SELECT rule,body FROM people WHERE state IN ('reply_ready','ready')"))
            incoming = list(db.execute("SELECT job FROM activity WHERE done=0 AND retry_at<=?", (time.time(),)))
            open_jobs = c.managed_job_statuses(queued=True)['statuses'] if (pending or incoming) and configured else {}
            if c.held_code():
                return {'ok': False, 'error': {'code': c.held_code()}}
            priority = any(row['rule'] in configured
                           and json.loads(row['body']).get('jobId') == configured[row['rule']]['jobId']
                           and open_jobs.get(configured[row['rule']]['jobId']) == '开放中'
                           for row in pending)
            priority = priority or any(
                hint['job'] == rule['jobId'] and open_jobs.get(rule['jobId']) == '开放中'
                for hint in incoming for rule in configured.values())
            put_state(db, 'chat-resumes-turn', 'collect' if priority else 'flow' if turn == 'collect' else 'collect')
            if turn == 'collect' and not priority:
                collected = boss_chat_receipts.step(c, db, request)
                if collected is not None:
                    if collected.get('ok') is not True:
                        return collected
                    return activity_status(db, status(db, 'collecting_resumes'))
            else:
                boss_chat_receipts.schema(db)
                for hint in request.get('activityHints', [])[:200]:
                    if isinstance(hint, dict):
                        boss_chat_receipts.wake(db, hint.get('friend'))
        result = activity_status(db, step(c, db, request))
        if result.get('ok') is True and db.execute(
                "SELECT 1 FROM commands c JOIN active_contacts a ON a.id=c.id "
                "WHERE c.state='sent' AND c.reported=0 LIMIT 1").fetchone():
            result.setdefault('data', {})['greetingReceiptReady'] = True
        return result
