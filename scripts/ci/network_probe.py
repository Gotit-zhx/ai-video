"""Opt-in, bounded public-network diagnostics. Never changes DNS or proxy settings."""
import argparse
import concurrent.futures
import json
import os
import re
import selectors
import subprocess
import threading
import time
import urllib.parse

MIB = 1024 ** 2
MAX_BYTES = 48 * MIB
MAX_SECONDS = 150
ALLOWED = {
    'githubApi': r'https://api\.github\.com/',
    'ghcr': r'https://ghcr\.io/v2/',
    'dockerHub': r'https://registry-1\.docker\.io/v2/',
    'githubRelease': r'https://github\.com/Gotit-zhx/ai-video/releases/download/candidate-[a-f0-9]{40}/source\.tar\.gz',
    'jdkRelease': r'https://github\.com/adoptium/temurin21-binaries/releases/download/jdk-[0-9.+%A-Fa-f]+/OpenJDK[0-9]+U-jdk_x64_linux_hotspot_[0-9._]+\.tar\.gz',
    'mavenBinary': r'https://repo\.maven\.apache\.org/maven2/org/apache/maven/apache-maven/[0-9.]+/apache-maven-[0-9.]+-bin\.tar\.gz',
}
RESULT_FIELDS = {'endpoint', 'attempt', 'endpointHost', 'finalHost', 'failureClass', 'bytes',
                 'httpStatus', 'durationMs', 'dnsMs', 'connectMs', 'tlsMs', 'firstByteMs',
                 'bytesPerSecond', 'complete', 'limitHit'}


def validate_endpoint(endpoint):
    name = endpoint.get('name'); url = endpoint.get('url', '')
    if (name not in ALLOWED or not re.fullmatch(ALLOWED[name], url)
            or type(endpoint.get('range')) is not bool):
        raise ValueError('Only configured public HTTPS endpoints are allowed')
    return endpoint


def classify_sample(exit_code, connected, tls_ok, received_bytes):
    if exit_code == 0: return 'none'
    if exit_code == 28:
        if not connected:return 'connect_timeout'
        return 'sample_incomplete' if tls_ok else 'tls_failed'
    if exit_code in (35, 51, 58, 60): return 'tls_failed'
    return 'unresolved'


def collect_process(process, maximum, deadline):
    selector = selectors.DefaultSelector(); errors = bytearray(); count = 0; limited = False
    for stream, kind in [(process.stdout, 'body'), (process.stderr, 'metadata')]:
        os.set_blocking(stream.fileno(), False); selector.register(stream, selectors.EVENT_READ, kind)
    try:
        while selector.get_map():
            remaining = deadline-time.monotonic()
            if remaining <= 0:
                limited = True; process.kill(); break
            for key, _ in selector.select(min(.2, remaining)):
                length = min(65536, maximum-count+1) if key.data == 'body' else 65536
                block = os.read(key.fileobj.fileno(), max(1, length))
                if not block:
                    selector.unregister(key.fileobj); continue
                if key.data == 'body':
                    count += len(block)
                    if count > maximum:
                        limited = True; process.kill(); break
                else:
                    errors.extend(block)
                    if len(errors) > MIB:
                        limited = True; process.kill(); break
            if limited: break
        process.wait(timeout=2)
    finally:
        if process.poll() is None: process.kill(); process.wait(timeout=2)
        selector.close(); process.stdout.close(); process.stderr.close()
    raw = errors.decode(errors='replace'); metadata = {}
    start = raw.find('{')
    if start >= 0:
        try: metadata = json.loads(raw[start:])
        except ValueError: pass
    return {'exit': process.returncode, 'bytes': count, 'metadata': metadata, 'limitHit': limited}


def probe_one(endpoint, maximum, deadline):
    start = time.monotonic(); duration = min(35, max(.1, deadline-start))
    args = ['curl', '--silent', '--show-error', '--location', '--proto', '=https',
            '--proto-redir', '=https', '--connect-timeout', '8', '--max-time', str(duration),
            '--max-filesize', str(maximum), '--output', '-', '--write-out', '%{stderr}%{json}']
    if endpoint['range']: args += ['--range', '0-'+str(maximum-1)]
    args.append(endpoint['url'])
    process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    outcome = collect_process(process, maximum, min(deadline, start+duration+.5))
    info = outcome['metadata']; code = outcome['exit']; size = min(maximum, outcome['bytes'])
    connected = info.get('time_connect', 0) > 0; tls_ok = info.get('time_appconnect', 0) > 0
    failure = classify_sample(code, connected, tls_ok, size)
    if outcome['limitHit']: failure = 'sample_incomplete'
    status = info.get('http_code', 0)
    allowed_status = {206} if endpoint['range'] else {200, 401}
    if code == 0 and status not in allowed_status: failure = 'http_error'
    result = {'endpoint': endpoint['name'], 'endpointHost': urllib.parse.urlsplit(endpoint['url']).hostname,
              'finalHost': urllib.parse.urlsplit(info.get('url_effective', '')).hostname,
              'failureClass': failure, 'bytes': size, 'httpStatus': status,
              'durationMs': round((time.monotonic()-start)*1000), 'limitHit': outcome['limitHit'],
              'complete': code == 0 and not outcome['limitHit'] and failure == 'none'}
    for field, key in [('dnsMs', 'time_namelookup'), ('connectMs', 'time_connect'),
                       ('tlsMs', 'time_appconnect'), ('firstByteMs', 'time_starttransfer')]:
        value = info.get(key)
        result[field] = round(value*1000) if type(value) in (int, float) and value >= 0 else None
    result['bytesPerSecond'] = info.get('speed_download', 0)
    return result


def run_probe(config, runner=None):
    endpoints = config.get('endpoints', []); attempts = config.get('attempts', 2)
    limit = config.get('maxBytes', MAX_BYTES); seconds = config.get('maxSeconds', MAX_SECONDS)
    if (not isinstance(endpoints, list) or not 1 <= len(endpoints) <= 3
            or type(attempts) is not int or not 1 <= attempts <= 2
            or type(limit) is not int or not 0 < limit <= MAX_BYTES
            or type(seconds) not in (int, float) or not 0 < seconds <= MAX_SECONDS):
        raise ValueError('Diagnostic budget exceeds the fixed bounds')
    for endpoint in endpoints: validate_endpoint(endpoint)
    deadline = time.monotonic()+seconds; lock = threading.Lock(); remaining = limit
    def one(item):
        nonlocal remaining
        endpoint, attempt = item
        with lock:
            cap = min(8*MIB if endpoint['range'] else 16384, remaining)
            if cap <= 0 or time.monotonic() >= deadline:
                return {'endpoint': endpoint['name'], 'attempt': attempt,
                        'failureClass': 'unresolved', 'complete': False, 'bytes': 0}
            remaining -= cap
        result = (runner or probe_one)(endpoint, cap, deadline)
        result = {k: v for k, v in result.items() if k in RESULT_FIELDS}
        result['attempt'] = attempt
        return result
    jobs = [(endpoint, attempt) for endpoint in endpoints for attempt in range(1, attempts+1)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(one, jobs))
    return {'schemaVersion': 1, 'repairPerformed': False, 'dnsComparison': 'unresolved',
            'maximumBytes': limit, 'maximumSeconds': seconds, 'samples': results}


def main():
    from pathlib import Path
    parser = argparse.ArgumentParser(); parser.add_argument('--mode', choices=('reachability', 'downloads'), default='reachability')
    parser.add_argument('--candidate'); parser.add_argument('--run', action='store_true')
    parser.add_argument('--dry-run', action='store_true'); args = parser.parse_args()
    if args.mode == 'reachability':
        endpoints = [{'name': name, 'url': url, 'range': False} for name, url in
                     [('githubApi', 'https://api.github.com/'), ('ghcr', 'https://ghcr.io/v2/'),
                      ('dockerHub', 'https://registry-1.docker.io/v2/')]]
    else:
        if not args.candidate or not re.fullmatch(r'[a-f0-9]{40}', args.candidate):
            parser.error('downloads requires an exact trusted candidate commit')
        runtime = json.loads((Path(__file__).with_name('runtime.json')).read_text())
        java = re.fullmatch(r'(\d+\.\d+\.\d+)\+(\d+)', runtime['java'])
        if not java: raise ValueError('Unsupported pinned JDK download layout')
        version, build = java.groups(); major = version.split('.')[0]; maven = runtime['maven']
        endpoints = [
            {'name':'githubRelease','url':'https://github.com/Gotit-zhx/ai-video/releases/download/candidate-'+args.candidate+'/source.tar.gz','range':True},
            {'name':'jdkRelease','url':'https://github.com/adoptium/temurin'+major+'-binaries/releases/download/jdk-'+version+'%2B'+build+'/OpenJDK'+major+'U-jdk_x64_linux_hotspot_'+version+'_'+build+'.tar.gz','range':True},
            {'name':'mavenBinary','url':'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/'+maven+'/apache-maven-'+maven+'-bin.tar.gz','range':True}]
    config = {'endpoints': endpoints}
    for endpoint in endpoints: validate_endpoint(endpoint)
    if not args.run or args.dry_run:
        print(json.dumps({'dryRun': True, 'endpoints': [x['name'] for x in endpoints],
                          'maximumBytes': MAX_BYTES, 'maximumSeconds': MAX_SECONDS, 'repairPerformed': False}))
    else:
        print(json.dumps(run_probe(config), sort_keys=True, indent=2))


if __name__ == '__main__': main()
