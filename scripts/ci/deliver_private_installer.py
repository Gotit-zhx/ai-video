"""Use an expiring, single-object ticket; never accept a bucket credential."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import urllib.error
import urllib.parse
import urllib.request


def main():
    release = os.environ['PRIVATE_RELEASE']
    if not re.fullmatch(r'heyi-workbench-private-\d+-\d+', release):
        raise ValueError('Invalid private release')
    ticket = json.loads(os.environ['UPLOAD_TICKET'])
    commit, record = ticket['commit'], ticket['artifact']
    if not re.fullmatch(r'[a-f0-9]{40}', commit):
        raise ValueError('Invalid source commit')
    expected = f'Ai-boss-Windows-Setup-{commit[:12]}.exe'
    if (not re.fullmatch(r'[a-f0-9]{64}', str(record['sha256']))
            or type(record['size']) is not int or not 0 < record['size'] < 1024**3
            or record['name'] != expected
            or record['key'] != f"private-desktop-installers/{record['sha256']}/{expected}"):
        raise ValueError('Unexpected upload object')
    parts = ticket['parts']
    if not isinstance(parts, list) or not 1 <= len(parts) <= 100:
        raise ValueError('Invalid multipart ticket')
    urls = [ticket[k] for k in ('complete', 'abort', 'get', 'unsigned')]+parts
    for url in urls:
        parsed = urllib.parse.urlsplit(url)
        if (parsed.scheme != 'https' or parsed.hostname != 'oss.cloud.dayan.cloud'
                or urllib.parse.unquote(parsed.path) != '/test-hoyee/'+record['key']
                or parsed.username or parsed.password):
            raise ValueError('Unexpected upload destination')
        print('::add-mask::'+url, flush=True)
    info = json.loads(subprocess.check_output(
        ['gh', 'release', 'view', release, '--json', 'isDraft,targetCommitish'], text=True))
    if not info['isDraft'] or info['targetCommitish'] != commit:
        raise ValueError('Source must be the exact verified private draft')
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    with tempfile.TemporaryDirectory() as folder:
        root = Path(folder)
        subprocess.run(['gh', 'release', 'download', release, '--dir', str(root),
                        '--pattern', expected, '--pattern', 'verification.json'], check=True)
        proof = json.loads((root/'verification.json').read_text())
        source = root/expected
        with source.open('rb') as stream:
            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if (proof.get('commit') != commit or proof.get('packagedChecksPassed') is not True
                or proof.get('overallProbePassed') is not True
                or not proof['installer'].get('checks') or not all(proof['installer']['checks'].values())
                or source.stat().st_size != record['size'] or proof['installer']['sha256'] != record['sha256']
                or digest != record['sha256']):
            raise ValueError('Installer verification differs')
        try:
            xml = ET.Element('CompleteMultipartUpload')
            with source.open('rb') as stream:
                for number, url in enumerate(parts, 1):
                    data = stream.read(8*1024*1024)
                    if not data:
                        raise ValueError('Extra upload parts')
                    request = urllib.request.Request(url, data=data,
                        headers={'Content-Type': 'application/octet-stream'}, method='PUT')
                    with opener.open(request, timeout=120) as response:
                        etag = response.headers.get('ETag', '').strip('"')
                        if response.status != 200 or etag.lower() != hashlib.md5(data).hexdigest():
                            raise ValueError('Uploaded part checksum differs')
                    part = ET.SubElement(xml, 'Part')
                    ET.SubElement(part, 'PartNumber').text = str(number)
                    ET.SubElement(part, 'ETag').text = etag
                    print('Verified upload part', number, flush=True)
                if stream.read(1):
                    raise ValueError('Missing upload parts')
            request = urllib.request.Request(ticket['complete'], data=ET.tostring(xml),
                                             headers={'Content-Type': 'application/xml'}, method='POST')
            with opener.open(request, timeout=120) as response:
                result = response.read()
                if response.status != 200 or b'<Error>' in result:
                    raise ValueError('Multipart completion failed')
        except BaseException:
            try:
                opener.open(urllib.request.Request(ticket['abort'], method='DELETE'), timeout=20).close()
            except Exception:
                pass
            raise
        for method in ('GET', 'HEAD'):
            try:
                opener.open(urllib.request.Request(ticket['unsigned'], method=method), timeout=30)
                raise ValueError('Anonymous access must be denied')
            except urllib.error.HTTPError as error:
                if error.code not in (401, 403, 404):
                    raise ValueError('Unexpected anonymous storage status') from None
                error.close()
        sha, size = hashlib.sha256(), 0
        with opener.open(ticket['get'], timeout=180) as response:
            for data in iter(lambda: response.read(1024*1024), b''):
                sha.update(data); size += len(data)
        if size != record['size'] or sha.hexdigest() != record['sha256']:
            raise ValueError('Stored full installer checksum differs')
        result = {'schemaVersion': 1, 'commit': commit, 'platform': 'windows',
                  'artifact': {**record, 'private': True, 'verified': True}}
        output = root/'windows-oss.json'
        output.write_text(json.dumps(result, indent=2))
        subprocess.run(['gh', 'release', 'upload', release, str(output)], check=True)
        print(json.dumps({'verified': True, 'commit': commit, 'bytes': size}))


if __name__ == '__main__':
    try:
        main()
    except BaseException as error:
        # Signed capability URLs must never enter an exception traceback.
        print('Private transfer failed: '+type(error).__name__, flush=True)
        raise SystemExit(1)
