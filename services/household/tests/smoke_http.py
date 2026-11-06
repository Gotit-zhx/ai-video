"""Run against an isolated QA database: python tests/smoke_http.py URL PASSWORD_FILE."""
import datetime
import http.cookiejar
import json
import pathlib
import sys
import urllib.error
import urllib.request

base = sys.argv[1].rstrip('/') + '/'
password = pathlib.Path(sys.argv[2]).read_text().strip()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def call(path, method='GET', body=None, expected=200):
    headers = {'X-Requested-With': 'HomeCare'}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    try:
        response = opener.open(urllib.request.Request(base + path, data=data, method=method, headers=headers), timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    raw = response.read()
    assert response.status == expected, (path, response.status, raw.decode())
    return json.loads(raw) if 'application/json' in response.headers.get('Content-Type', '') else raw.decode()


call('api/state', expected=401)
call('api/login', 'POST', {'password': password})
initial = call('api/state')
task = call('api/tasks', 'POST', {'title': '联调检查任务', 'area': '测试', 'period': 'bedtime', 'minutes': 3, 'frequency': 'daily', 'weekdays': [], 'essential': False, 'steps': ['第一项', '第二项']}, 201)['task']
date = initial['today']
first, second = (s['id'] for s in task['steps'])
try:
    call('api/progress', 'PUT', {'date': date, 'taskId': task['id'], 'stepId': first, 'done': True})
    current = call('api/state')
    assert current['progress'][task['id']]['steps'] == [first]
    assert current['history'][-1]['done'] == initial['history'][-1]['done']
    call('api/progress', 'PUT', {'date': date, 'taskId': task['id'], 'stepId': second, 'done': True})
    current = call('api/state')
    assert current['history'][-1]['done'] == initial['history'][-1]['done'] + 1
    call('api/skip', 'PUT', {'date': date, 'taskId': task['id'], 'skipped': True})
    assert call('api/state')['progress'][task['id']]['skipped'] is True
    call('api/progress', 'PUT', {'date': date, 'taskId': task['id'], 'stepId': first, 'done': True})
    assert call('api/state')['progress'][task['id']]['skipped'] is False
    edited = call('api/tasks/' + task['id'], 'PUT', {'steps': ['第一项', '第三项']})['task']
    assert edited['steps'][0]['id'] == first
    assert call('api/state')['history'][-1]['done'] == initial['history'][-1]['done']
    future = (datetime.date.fromisoformat(date) + datetime.timedelta(days=1)).isoformat()
    call('api/progress', 'PUT', {'date': future, 'taskId': task['id'], 'stepId': first, 'done': True}, 400)
    ics = call('api/reminders.ics')
    assert ics.count('BEGIN:VEVENT') == 2 and ics.count('BEGIN:VALARM') == 2
    assert 'Asia/Shanghai' in ics and base in ics.replace('\r\n ', '')
    export = call('api/export')
    assert 'auth' not in export and 'subscriptions' not in export and 'sessions' not in export
finally:
    call('api/tasks/' + task['id'], 'DELETE', {})
assert not any(t['id'] == task['id'] for t in call('api/state')['tasks'])
call('api/logout', 'POST', {})
call('api/state', expected=401)
print('PASS: authentication, partial/complete/skip/edit persistence, future-date rejection, calendar, export, archive, logout')
