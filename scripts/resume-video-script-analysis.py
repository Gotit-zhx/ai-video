#!/usr/bin/env python3
"""Resume only explicitly listed interrupted scripts through their owner's session.

Server-side only. --ids-file must contain a JSON array of UUID strings. Uses the
existing MySQL/Redis container environment, never logs in, never writes database
rows directly, and never scans historical failed scripts. Private data stays in
memory. Output contains only sanitized per-script statuses and HTTP codes.
"""
import argparse
import base64
import json
from pathlib import Path
import re
import subprocess
import sys
import urllib.error
import urllib.request
import uuid

MYSQL = 'ai-commerce-mysql'
REDIS = 'ai-commerce-redis'
APP = 'ai-commerce-app'
BASE_URL = 'http://127.0.0.1:8080'
ACTIVE = {'QUEUED', 'EXTRACTING_FRAMES', 'AI_ANALYZING'}
KNOWN_STATUSES = ACTIVE | {'READY', 'FAILED', 'DRAFT'}
UUID_PATTERN = re.compile(rb'(?i)(?<![0-9a-f])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-f])')


class SafeError(RuntimeError):
    """Messages are fixed safe result codes, never raw subprocess/HTTP details."""


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def command(args, *, stdin=None):
    try:
        completed = subprocess.run(args, input=stdin, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired):
        raise SafeError('local_command_unavailable') from None
    if completed.returncode:
        raise SafeError('local_command_failed')
    return completed.stdout


def container_environment(container):
    try:
        inspection = json.loads(command(['docker', 'inspect', container]))[0]
        return dict(entry.split('=', 1) for entry in inspection['Config'].get('Env', []) if '=' in entry)
    except (ValueError, KeyError, IndexError, TypeError):
        raise SafeError('container_environment_unavailable') from None


def load_ids(path):
    try:
        values = json.loads(Path(path).read_text())
        if not isinstance(values, list) or not values or not all(isinstance(value, str) for value in values):
            raise ValueError()
        parsed = [str(uuid.UUID(value)) for value in values]
        if any(value.lower() != parsed[index] for index, value in enumerate(values)):
            raise ValueError()
    except (OSError, ValueError, TypeError):
        raise SafeError('ids_file_must_be_nonempty_json_array_of_canonical_uuids') from None
    return list(dict.fromkeys(parsed))


def check_database():
    mysql_environment = container_environment(MYSQL)
    app_environment = container_environment(APP)
    match = re.match(r'jdbc:mysql://[^/]+/([^?]+)', app_environment.get('DB_URL', ''))
    if not match or match.group(1) != mysql_environment.get('MYSQL_DATABASE'):
        raise SafeError('app_database_mismatch')


def scripts_by_id(ids):
    # IDs are validated UUIDs; this is the only database query and it is read-only.
    for value in ids:
        if str(uuid.UUID(value)) != value:
            raise SafeError('invalid_script_id')
    query = ('SELECT id, created_by, organization_id, status FROM video_scripts WHERE id IN (' +
             ','.join("'" + value + "'" for value in ids) + ');')
    shell = 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --batch --raw --skip-column-names "$MYSQL_DATABASE"'
    output = command(['docker', 'exec', '-i', MYSQL, 'sh', '-c', shell], stdin=query.encode())
    rows = {}
    try:
        for line in output.decode().splitlines():
            script_id, owner_id, organization_id, status = line.split('\t')
            if script_id not in ids:
                raise ValueError()
            rows[script_id] = {'id': script_id, 'owner': owner_id,
                               'organization': organization_id, 'status': status}
    except (ValueError, UnicodeError):
        raise SafeError('script_query_result_invalid') from None
    return rows


def existing_session_ids():
    environment = container_environment(REDIS)
    if not environment.get('REDIS_PASSWORD'):
        raise SafeError('redis_password_environment_missing')
    scan = 'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli --raw --scan --pattern "heyi:session:index:*"'
    keys = command(['docker', 'exec', REDIS, 'sh', '-c', scan]).splitlines()
    seen = set()
    for key in keys:
        if not key.startswith(b'heyi:session:index:'):
            continue
        # -x passes the index key over stdin, without putting private key text in argv.
        members = 'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli --raw -x SMEMBERS'
        serialized = command(['docker', 'exec', '-i', REDIS, 'sh', '-c', members], stdin=key)
        for match in UUID_PATTERN.finditer(serialized):
            session_id = match.group().decode('ascii').lower()
            if session_id not in seen:
                seen.add(session_id)
                yield session_id


def request(opener, path, cookie, method='GET'):
    headers = {'Cookie': 'HEYI_SESSION=' + cookie, 'Accept': 'application/json'}
    req = urllib.request.Request(BASE_URL + path, headers=headers,
                                 data=b'' if method == 'POST' else None, method=method)
    try:
        with opener.open(req, timeout=30) as response:
            code = response.status
            body = response.read(1024 * 1024)
        try:
            payload = json.loads(body)
        except (ValueError, UnicodeError):
            payload = None
        return code, payload
    except urllib.error.HTTPError as error:
        code = error.code
        error.close()
        return code, None
    except (OSError, urllib.error.URLError):
        return None, None


def owner_sessions(opener, owners):
    matches = {}
    for session_id in existing_session_ids():
        cookie = base64.b64encode(session_id.encode('ascii')).decode('ascii')
        code, payload = request(opener, '/api/v1/auth/me', cookie)
        if code != 200 or not isinstance(payload, dict) or not isinstance(payload.get('data'), dict):
            continue
        identity = payload['data']
        owner = (identity.get('userId'), identity.get('organizationId'))
        if owner in owners:
            matches[owner] = cookie
            if len(matches) == len(owners):
                break
    return matches


def safe_status(row):
    status = row.get('status') if row else None
    return status if status in KNOWN_STATUSES else 'UNKNOWN'


def resume(ids, dry_run=False):
    check_database()
    rows = scripts_by_id(ids)
    owners = {(row['owner'], row['organization']) for row in rows.values() if row['status'] == 'FAILED'}
    # Disable proxies and redirects so cookies can only reach the local application.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    sessions = owner_sessions(opener, owners) if owners else {}
    results = []
    for script_id in ids:
        row = rows.get(script_id)
        if row is None:
            results.append({'scriptId': script_id, 'result': 'not_found'})
            continue
        if row['status'] != 'FAILED':
            results.append({'scriptId': script_id, 'result': 'skipped', 'status': safe_status(row)})
            continue
        cookie = sessions.get((row['owner'], row['organization']))
        if not cookie:
            results.append({'scriptId': script_id, 'result': 'awaiting_owner_login', 'status': 'FAILED'})
            continue
        # Recheck ownership and status immediately before submitting; backend also
        # checks both ownership and FAILED status atomically for the retry itself.
        current = scripts_by_id([script_id]).get(script_id)
        if not current or current['owner'] != row['owner'] or current['organization'] != row['organization']:
            results.append({'scriptId': script_id, 'result': 'ownership_changed'})
            continue
        if current['status'] != 'FAILED':
            results.append({'scriptId': script_id, 'result': 'skipped', 'status': safe_status(current)})
            continue
        if dry_run:
            results.append({'scriptId': script_id, 'result': 'eligible_owner_session', 'status': 'FAILED'})
            continue
        code, _ = request(opener, f'/api/v1/video-scripts/{script_id}/retry', cookie, 'POST')
        # Never blindly repeat a POST after a timeout: read the resulting state.
        after = scripts_by_id([script_id]).get(script_id)
        status = safe_status(after)
        result = 'resume_submitted' if code is not None and 200 <= code < 300 else 'resume_not_confirmed'
        if code in (401, 403):
            result = 'owner_session_expired_or_forbidden'
        if code is None and status in ACTIVE | {'READY'}:
            result = 'resume_observed_after_timeout'
        results.append({'scriptId': script_id, 'result': result, 'httpStatus': code, 'status': status})
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ids-file', required=True, help='JSON array containing only this deployment\'s interrupted script UUIDs')
    parser.add_argument('--dry-run', action='store_true', help='Verify eligibility and existing owner sessions without retrying')
    args = parser.parse_args()
    try:
        results = resume(load_ids(args.ids_file), args.dry_run)
        print(json.dumps({'results': results}, ensure_ascii=False), flush=True)
        return 0 if all(row['result'] in {'skipped', 'eligible_owner_session', 'resume_submitted',
                                          'resume_observed_after_timeout'} for row in results) else 2
    except SafeError as error:
        print(json.dumps({'error': str(error)}), flush=True)
        return 1
    except Exception:
        # Do not leak a raw HTTP/Redis/MySQL response or cookie through a traceback.
        print(json.dumps({'error': 'unexpected_local_error'}), flush=True)
        return 1


if __name__ == '__main__':
    sys.exit(main())
