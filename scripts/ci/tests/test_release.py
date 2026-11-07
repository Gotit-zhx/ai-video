import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]))
import release
import java


class ReleaseTest(unittest.TestCase):
    def manifest(self):
        return {"schemaVersion":1,"repository":release.REPOSITORY,"version":"v2026.11.6.2",
            "source":{"commit":"a"*40,"tree":"b"*40,"ref":"refs/heads/release/2026.11.6.2-ops",
                      "inputs":{k:"a"*64 for k in ["app","frontend","unsupported","source","operations","migrations"]}},"sourceArchiveSha256":"f"*64,"sourceArchiveBytes":100,"sourceUnpackedBytes":1000,
            "validation":{"result":"passed","unexpectedSkips":0,"containerSmoke":"passed"},"images":{
                name:{"repository":"ghcr.io/gotit-zhx/ai-video/"+name,"manifestDigest":"sha256:"+"c"*64,
                      "configDigest":"sha256:"+"d"*64,"sourceCommit":"a"*40,"platform":"linux/amd64",
                      "sourceRef":"refs/heads/release/2026.11.6.2-ops","compressedBytes":100,"unpackedBytes":200,
                      "assetManifestSha256":"f"*64,"smokeAssets":{"app.js":"f"*64}}
                for name in ("app","frontend")}}
    def test_valid_manifest_and_gitflow(self):
        release.validate_manifest(self.manifest()); release.check_pr("main-tree","release/2026.11.6.2-ops")
        release.check_pr("develop","feature/devops")
    def test_reject_unknown_images_skips_and_failed_tests(self):
        for field,value in [("result","failed"),("unexpectedSkips",1)]:
            m=self.manifest();m["validation"][field]=value
            with self.assertRaises(ValueError):release.validate_manifest(m)
        m=self.manifest();m["images"]["app"]["repository"]="evil.example/app"
        with self.assertRaises(ValueError):release.validate_manifest(m)
    def test_gitflow_and_versions_fail_closed(self):
        for base,head in [("main-tree","feature/a"),("develop","main-tree"),("unknown","release/a")]:
            with self.assertRaises(ValueError):release.check_pr(base,head)
        for version in ["v1", "v1.2.3/../../x", "v1.2.3\n", "latest"]:
            m=self.manifest();m["version"]=version
            with self.assertRaises(ValueError):release.validate_manifest(m)
    def test_unsupported_components_are_not_silently_ignored(self):
        for p in ["services/pi-runtime/index.js","apps/hr-desktop/main.cjs","compose.yaml","unknown.bin"]:
            self.assertEqual("unsupported",release.component(p))
        self.assertEqual("source",release.component("docs/guides/operations.md"))
        self.assertEqual('operations',release.component('scripts/ci/release.py'))
        self.assertEqual('operations',release.component('ops/finance-ad-proxy/render.py'))
        self.assertEqual('operations',release.component('ops/finance-ad-proxy/heyi-finance-ad-proxy.service'))
        self.assertEqual('operations',release.component('ops/finance-ad-proxy/runtime.json'))
        self.assertEqual('app',release.component('src/main/resources/prompts/worker.md'))
        self.assertEqual('unsupported',release.component('services/pi-runtime/skills/worker/SKILL.md'))
        self.assertTrue(release.documentation_only(['docs/guides/cicd.md','README.md']))
        self.assertFalse(release.documentation_only(['src/main/resources/prompts/worker.md']))
    def test_required_test_skip_and_missing_report_fail(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)
            with self.assertRaises(ValueError):java.audit(p,["ExampleTest"])
            (p/"TEST-example.xml").write_text('<testsuite name="ExampleTest"><testcase classname="ExampleTest" name="required"><skipped/></testcase></testsuite>')
            with self.assertRaises(ValueError):java.audit(p,["ExampleTest"])
    def test_private_fixture_skip_is_explicit(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d);(p/"TEST-fixture.xml").write_text('<testsuite name="FinanceOrderChargesTest"><testcase classname="FinanceOrderChargesTest" name="suppliedWorkbookPreservesAllTwentyFiveRecords"><skipped/></testcase></testsuite>')
            result=java.audit(p,["FinanceOrderChargesTest"])
            self.assertEqual("passed",result["result"]);self.assertEqual(0,result["unexpectedSkips"])


if __name__=="__main__": unittest.main()
