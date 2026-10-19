import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import unittest
from unittest import mock
import urllib.error

import deliver_private_installer as delivery


class DeliveryTest(unittest.TestCase):
    def fixture(self):
        raw = b'fixture installer'
        commit = 'a'*40
        name = 'Ai-boss-Windows-Setup-'+commit[:12]+'.exe'
        digest = hashlib.sha256(raw).hexdigest()
        record = {'name': name, 'size': len(raw), 'sha256': digest,
                  'key': 'private-desktop-installers/'+digest+'/'+name}
        base = 'https://oss.cloud.dayan.cloud/test-hoyee/'+record['key']
        ticket = {'commit': commit, 'artifact': record, 'parts': [base+'?part=1'],
                  'complete': base+'?complete=1', 'abort': base+'?abort=1',
                  'get': base+'?read=1', 'unsigned': base}
        return raw, ticket

    def test_private_verified_installer_round_trip_uses_scoped_ticket_and_publishes_no_urls(self):
        raw, ticket = self.fixture()
        proof = {'commit': ticket['commit'], 'packagedChecksPassed': True, 'overallProbePassed': True,
                 'installer': {'sha256': ticket['artifact']['sha256'], 'checks': {'dataPreserved': True}}}
        uploads, calls = [], []

        def gh(args, **kwargs):
            if args[2] == 'download':
                root = Path(args[args.index('--dir')+1])
                (root/ticket['artifact']['name']).write_bytes(raw)
                (root/'verification.json').write_text(json.dumps(proof))
            elif args[2] == 'upload':
                uploads.append(json.loads(Path(args[-1]).read_text()))

        class Response(io.BytesIO):
            status = 200
            headers = {'ETag': hashlib.md5(raw).hexdigest()}

        def request(value, **kwargs):
            url = value if isinstance(value, str) else value.full_url
            method = 'GET' if isinstance(value, str) else value.method
            calls.append((method, url))
            if url == ticket['unsigned']:
                raise urllib.error.HTTPError(url, 403, 'Private', {}, None)
            if method == 'PUT':
                self.assertEqual(value.data, raw)
                self.assertEqual(value.get_header('Content-type'), 'application/octet-stream')
            return Response(raw if url == ticket['get'] else b'')

        with mock.patch.dict(os.environ, {'PRIVATE_RELEASE': 'heyi-workbench-private-1-1',
                                          'UPLOAD_TICKET': json.dumps(ticket)}), \
                mock.patch.object(delivery.subprocess, 'run', side_effect=gh), \
                mock.patch.object(delivery.subprocess, 'check_output',
                                  return_value=json.dumps({'isDraft': True, 'targetCommitish': ticket['commit']})), \
                mock.patch.object(delivery.urllib.request, 'build_opener',
                                  return_value=mock.Mock(open=request)), \
                contextlib.redirect_stdout(io.StringIO()):
            delivery.main()
        self.assertEqual(len(uploads), 1)
        self.assertTrue(uploads[0]['artifact']['private'])
        self.assertTrue(uploads[0]['artifact']['verified'])
        self.assertNotIn('https://', json.dumps(uploads))
        self.assertIn(('HEAD', ticket['unsigned']), calls)

    def test_unexpected_storage_host_or_object_is_rejected_before_any_network(self):
        for change in ('host', 'object', 'headers'):
            raw, ticket = self.fixture()
            ticket['get'] = ticket['get'].replace('oss.cloud.dayan.cloud', 'outside.invalid') if change == 'host' else ticket['get']
            if change == 'object':
                ticket['complete'] = 'https://oss.cloud.dayan.cloud/test-hoyee/another-object'
            if change == 'headers':
                ticket['artifact']['sha256'] = '../another-object'
            with mock.patch.dict(os.environ, {'PRIVATE_RELEASE': 'heyi-workbench-private-1-1',
                                              'UPLOAD_TICKET': json.dumps(ticket)}), \
                    mock.patch.object(delivery.subprocess, 'check_output') as gh, \
                    contextlib.redirect_stdout(io.StringIO()):
                with self.assertRaises(ValueError):
                    delivery.main()
                gh.assert_not_called()


if __name__ == '__main__':
    unittest.main()
