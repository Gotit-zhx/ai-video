import json
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import candidate


class InitialBaseline(unittest.TestCase):
    def test_missing_receipt_fails_when_bootstrap_is_not_enabled(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(candidate.release,'ROOT',Path(folder)),patch.dict(candidate.os.environ,{'INITIAL_ADOPTION':'false'}),patch.object(candidate.subprocess,'run',return_value=SimpleNamespace(returncode=1)):
            with self.assertRaises(ValueError):candidate.baseline_for('v1.0.0')
    def test_initial_reuse_requires_the_formal_parent_and_tag_check(self):
        value={'source':{'commit':'b'*40,'ref':'refs/heads/hotfix/1.0.0'}};calls=[]
        def run(args,**kwargs):
            calls.append(args)
            if args[:3]==['gh','release','download']:
                if args[3]=='v1.0.0':return SimpleNamespace(returncode=1)
                self.assertEqual('candidate-'+'b'*40,args[3])
                (Path(args[args.index('--dir')+1])/'release.json').write_text(json.dumps(value))
            return SimpleNamespace(returncode=0)
        with tempfile.TemporaryDirectory() as folder,patch.object(candidate.release,'ROOT',Path(folder)),patch.dict(candidate.os.environ,{'INITIAL_ADOPTION':'true'}),patch.object(candidate.release,'run',return_value='a'*40+' '+'b'*40),patch.object(candidate.release,'validate_manifest',side_effect=lambda x:x),patch.object(candidate.release,'check_tag') as check,patch.object(candidate.subprocess,'run',side_effect=run):
            self.assertEqual(value,candidate.baseline_for('v1.0.0'));check.assert_called_once_with('v1.0.0',value)
        self.assertIn('--source-ref',calls[-1]);self.assertIn('b'*40,calls[-1])
    def test_invalid_formal_merge_never_downloads_an_arbitrary_candidate(self):
        with tempfile.TemporaryDirectory() as folder,patch.object(candidate.release,'ROOT',Path(folder)),patch.dict(candidate.os.environ,{'INITIAL_ADOPTION':'true'}),patch.object(candidate.release,'run',return_value='a'*40),patch.object(candidate.subprocess,'run',return_value=SimpleNamespace(returncode=1)) as run:
            with self.assertRaises(ValueError):candidate.baseline_for('v1.0.0')
            self.assertEqual(1,run.call_count)

class QualificationTests(unittest.TestCase):
    def test_expected_head_rejected_before_api_access(self):
        with patch.object(candidate.release,'run',return_value='a'*40),patch.object(candidate.evidence,'fetch_verified') as fetch:
            with self.assertRaises(ValueError):candidate.qualify('b'*40,'123','1')
            fetch.assert_not_called()
    def test_artifact_recipe_must_match_validation(self):
        with self.assertRaises(ValueError):candidate.check_compiled_proof({'build':{'archiveSha256':'a'*64}}, {'archiveSha256':'b'*64})
