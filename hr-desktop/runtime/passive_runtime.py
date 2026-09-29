"""Bounded opt-in workflow over the pinned SDK; durable before every write."""
import contextlib
import json
from pathlib import Path
import sqlite3
import time

REQUEST_TEXT = '方便发一份简历过来吗？'


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


def human_reply(messages, friend, since, excluded=()):
    """UID direction is authoritative. No heuristic on source=0 vs source=1."""
    incoming = []
    for item in messages:
        sender, target, body = item.get('from') or {}, item.get('to') or {}, item.get('body') or {}
        stamp = item.get('time')
        if (all(isinstance(v, dict) for v in (sender, target, body))
                and str(sender.get('uid')) == friend and str(target.get('uid', '')).isdecimal()
                and body.get('type') == 1 and isinstance(body.get('text'), str) and body['text'].strip()
                and isinstance(stamp, (int, float)) and not isinstance(stamp, bool)):
            incoming.append((str(target['uid']), stamp/1000 if stamp >= 1e12 else stamp))
    for item in messages:
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


def execute(c, root, request):
    operation = request['operation']
    with database(root) as db:
        if operation == 'passive-local':
            exports = [dict(row) for row in db.execute("SELECT * FROM people WHERE state='export' ORDER BY updated LIMIT 1")]
            reports = [dict(row) for row in db.execute("SELECT * FROM commands WHERE reported=0 ORDER BY rowid LIMIT 1")]
            return {'ok': True, 'data': {'exports': exports, 'reports': reports}}
        if operation == 'passive-ack':
            if request.get('friendId'):
                db.execute("UPDATE people SET state='stored' WHERE friend=? AND mid=? AND state='export'",
                           (request['friendId'], request.get('messageId')))
            if request.get('commandId'):
                db.execute('UPDATE commands SET reported=1 WHERE id=?', (request['commandId'],))
            return {'ok': True}
        if c.held_code() or c.DISCONNECTED.exists() or c.RESUME_AUTO_PAUSE.exists():
            return {'ok': False, 'error': {'code': c.held_code() or 'REQUESTS_PAUSED', 'message': '自动任务保持暂停'}}
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
            status = 'sent' if result.get('ok') is True and (result.get('data') or {}).get('sent') is True else 'uncertain'
            db.execute('UPDATE commands SET state=? WHERE id=?', (status, cid))
            return {'ok': True, 'data': {'status': status}}
        if operation != 'passive-step': raise ValueError('unsupported passive operation')
        rules = {r['id']: r for r in request.get('rules', []) if r.get('enabled') is True}
        # One phase per cycle; discovery and attachment reception alternate.
        old = db.execute("SELECT value FROM state WHERE key='phase'").fetchone()
        phase = int(old['value'])+1 if old else 0
        db.execute("INSERT OR REPLACE INTO state VALUES('phase',?)", (str(phase),)); db.commit()
        if phase % 2 == 0:
            c.RESUME_POLL_LIMIT = 1
            c.poll_resumes_once()
            if c.held_code(): return {'ok': False, 'error': {'code': c.held_code()}}
            with c.resume_db() as tracked:
                received = {r['friend_id']: dict(r) for r in tracked.execute("SELECT * FROM resume_requests WHERE state='received'")}
            candidate = next((r for r in db.execute("SELECT * FROM people WHERE state='requested' ORDER BY updated")
                              if r['friend'] in received), None)
            if candidate:
                mid = received[candidate['friend']]['message_id']
                # Retrying storage never downloads from BOSS again once file is durable.
                file = str(c.download_resume(candidate['friend'], mid))
                db.execute("UPDATE people SET state='export',file=?,mid=?,updated=? WHERE friend=?",
                           (file, mid, int(time.time()), candidate['friend']))
            return {'ok': True, 'data': {'phase': 'receive'}}
        if not rules: return {'ok': True, 'data': {'phase': 'idle'}}
        pending = next((r for r in db.execute("SELECT * FROM people WHERE state IN ('inspect','ready') ORDER BY updated")
                        if r['rule'] in rules), None)
        if pending:
            rule = rules[pending['rule']]; friend = pending['friend']
            detail = json.loads(pending['body'])
            if detail['enabledAt'] != rule['enabledAt']:
                db.execute("DELETE FROM people WHERE friend=? AND state IN ('inspect','ready')", (friend,))
                return {'ok': True}
            with c.resume_db() as tracked:
                existing = tracked.execute('SELECT state FROM resume_requests WHERE friend_id=?', (friend,)).fetchone()
            if existing:
                # Existing manual requests belong to the same account/candidate.
                db.execute("UPDATE people SET state='requested',updated=? WHERE friend=?", (int(time.time()), friend))
                return {'ok': True}
            if pending['state'] == 'inspect':
                result = c.cli_json('hr', 'chatmsg', friend, '--count', '100')
                if result.get('ok') is not True: return result
                excluded = {r[0] for r in db.execute('SELECT message FROM commands WHERE friend=?', (friend,))}
                ready = human_reply(c.resume_messages(result), friend, rule['enabledAt'], excluded)
                db.execute("UPDATE people SET state=?,updated=? WHERE friend=?", ('ready' if ready else 'seen', int(time.time()), friend))
            else:
                # Save association before c.request_resume persists its own
                # uncertainty ledger. Never auto-retry a failed/ambiguous send.
                c.prepare_recruiter_chat()
                db.execute("UPDATE people SET state='requested',updated=? WHERE friend=?", (int(time.time()), friend)); db.commit()
                c.request_resume(friend, retry_uncertain=False)
            return {'ok': True, 'data': {'phase': 'candidate'}}
        index_row = db.execute("SELECT value FROM state WHERE key='rule-index'").fetchone()
        index = int(index_row[0]) if index_row else 0
        ordered = sorted(rules); rule = rules[ordered[index % len(ordered)]]
        db.execute("INSERT OR REPLACE INTO state VALUES('rule-index',?)", (str(index+1),))
        key = 'page:'+rule['id']
        page_row = db.execute('SELECT value FROM state WHERE key=?', (key,)).fetchone()
        page = int(page_row[0]) if page_row else 1
        db.commit()
        result = c.cli_json('hr', 'chat', '--job-id', rule['jobId'], '--page', str(page))
        if result.get('ok') is not True: return result
        batch = rows(result)
        db.execute('INSERT OR REPLACE INTO state VALUES(?,?)', (key, str(page+1 if batch else 1)))
        for item in batch:
            friend = str(item.get('friendId') or item.get('friend_id') or '')
            if not friend.isdecimal() or not 1 <= len(friend) <= 20: continue
            info = item.get('geekCard') or item.get('geekInfo') or item.get('geekBaseInfo') or item
            def first(*keys):
                for source in (info, item):
                    for key in keys:
                        value=source.get(key)
                        if isinstance(value, str) and value.strip(): return value.strip()
                return ''
            metadata = {'name':first('geekName','name','nickname')[:100],
                        'education':first('geekDegree','degreeName','degreeDesc','education')[:100],
                        'experience':first('geekWorkYear','workYearDesc','work_years','experience')[:100],
                        'city':first('expectLocationName','locationName','city')[:100],
                        'company':first('companyName','company')[:200]}
            metadata.update(enabledAt=rule['enabledAt'])
            old = db.execute('SELECT * FROM people WHERE friend=?', (friend,)).fetchone()
            if not old:
                db.execute("INSERT INTO people(friend,rule,body,state,updated) VALUES(?,?,?,'inspect',?)",
                           (friend, rule['id'], json.dumps(metadata, ensure_ascii=False), int(time.time())))
            elif old['state'] == 'seen' and old['updated'] < time.time()-1800:
                db.execute("UPDATE people SET state='inspect',updated=? WHERE friend=?", (int(time.time()), friend))
        return {'ok': True, 'data': {'phase': 'discovery'}}
