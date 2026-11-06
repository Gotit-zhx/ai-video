"""Identify legacy local ledgers by an exact archived message and its UID pair.

Never load old credentials or infer identity from names, jobs, cookies or IPs.
The current authenticated account supplies the single read-only history probe.
"""
import contextlib
import json
from pathlib import Path
import re
import sqlite3


def recover(c, root, recruiter):
    if not isinstance(recruiter, str) or not re.fullmatch(r'[1-9]\d{0,19}', recruiter):
        raise ValueError('Missing verified recruiter identity')
    current = Path(root).resolve()
    candidates = []
    for folder in sorted(current.parent.iterdir()):
        if (folder == current or folder.is_symlink() or not folder.is_dir()
                or not re.fullmatch(r'[a-f0-9]{64}', folder.name)
                or (folder / 'account-identity.json').exists()):
            continue
        file = folder / 'resumes.sqlite3'
        if not file.is_file():
            continue
        with contextlib.closing(sqlite3.connect(f'file:{file}?mode=ro', uri=True)) as db:
            witness = db.execute(
                "SELECT friend_id,COALESCE(message_id,CAST(baseline_mid AS TEXT)) "
                "FROM resume_requests WHERE CAST(COALESCE(message_id,baseline_mid) AS INTEGER)>0 "
                "ORDER BY CASE WHEN state='received' THEN 0 ELSE 1 END,updated_at DESC LIMIT 1").fetchone()
        if witness:
            candidates.append((folder, witness))
    if len(candidates) > 3:
        return {'ok': False, 'error': {'code': 'MANUAL_REVIEW_REQUIRED'}}
    matches = []
    for folder, (friend, mid) in candidates:
        delay = c.passive_delay(cost=1, queued=True)
        if delay:
            return {'ok': False, 'error': {'code': 'REQUEST_BUDGET_EXHAUSTED'}}
        result = c.cli_json('hr', 'chatmsg', friend, '--count', '20', '--max-msg-id', str(int(mid)+1))
        if result.get('ok') is not True:
            return result
        for message in c.resume_messages(result):
            sender, target = message.get('from') or {}, message.get('to') or {}
            if (str(message.get('mid')) == str(mid)
                    and {str(sender.get('uid')), str(target.get('uid'))} == {friend, recruiter}
                    and friend != recruiter):
                matches.append(folder)
                break
    if len(matches) > 1:
        return {'ok': False, 'error': {'code': 'MANUAL_REVIEW_REQUIRED'}}
    if matches and (current / 'resumes.sqlite3').exists():
        with contextlib.closing(sqlite3.connect(f'file:{current / "resumes.sqlite3"}?mode=ro', uri=True)) as db:
            if db.execute('SELECT 1 FROM resume_requests LIMIT 1').fetchone():
                # Combining two populated histories requires a separate merge;
                # never discard either account's send ledger during recovery.
                return {'ok': False, 'error': {'code': 'MANUAL_REVIEW_REQUIRED'}}
    if matches and (matches[0] / 'risk-hold.json').exists():
        # A new session cannot silently discard an old account's restriction.
        return {'ok': False, 'error': {'code': 'MANUAL_REVIEW_REQUIRED'}}
    return {'ok': True, 'data': {'accountKey': matches[0].name if matches else ''}}
