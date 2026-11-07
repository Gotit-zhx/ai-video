import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import promote
import test_release


class RetryReceipt(unittest.TestCase):
    def test_retry_appends_its_receipt_without_overwriting_the_original(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);folder=root/'target/receipt';folder.mkdir(parents=True)
            manifest=test_release.ReleaseTest().manifest();version=manifest['version'];data=json.dumps(manifest).encode()
            (folder/'release.json').write_bytes(data)
            receipt={'id':'a'*32,'phase':'succeeded','version':version};(folder/'receipt.json').write_text(json.dumps(receipt))
            calls=[]
            def run(args,**_):
                calls.append(args)
                if args[1:3]==['release','view']:return SimpleNamespace(returncode=0,stdout=b'{"assets":[{"name":"release.json"},{"name":"receipt.json"}]}')
                if args[1:3]==['release','download']:(Path(args[-1])/'release.json').write_bytes(data)
                return SimpleNamespace(returncode=0)
            with patch.object(promote.release,'ROOT',root),patch.object(promote.subprocess,'run',side_effect=run):promote.main('publish',version)
            uploads=[a for a in calls if a[1:3]==['release','upload']]
            self.assertEqual(1,len(uploads));self.assertEqual('receipt-'+'a'*32+'.json',Path(uploads[0][-1]).name)
            self.assertNotIn('--clobber',uploads[0]);self.assertFalse(any(a[1:3]==['release','create'] for a in calls))
    def test_retry_rejects_a_different_existing_manifest(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);folder=root/'target/receipt';folder.mkdir(parents=True)
            manifest=test_release.ReleaseTest().manifest();version=manifest['version'];(folder/'release.json').write_text(json.dumps(manifest))
            (folder/'receipt.json').write_text(json.dumps({'id':'a'*32,'phase':'succeeded','version':version}))
            def run(args,**_):
                if args[1:3]==['release','view']:return SimpleNamespace(returncode=0,stdout=b'{"assets":[]}')
                if args[1:3]==['release','download']:(Path(args[-1])/'release.json').write_text('{}')
                else:self.fail('No new asset may be uploaded after manifest drift')
                return SimpleNamespace(returncode=0)
            with patch.object(promote.release,'ROOT',root),patch.object(promote.subprocess,'run',side_effect=run):
                with self.assertRaises(RuntimeError):promote.main('publish',version)
