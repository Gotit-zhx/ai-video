import copy
import multiprocessing
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from heyi_ops.engine import Engine
from heyi_ops.state import Store, Busy, read


def release(version, image):
    return {"version": version, "images": {k: {"configDigest": image} for k in ("app", "frontend")}}


class Host:
    def __init__(self): self.marker = None; self.version = "v1.0.0"; self.sourceVersion="v1.0.0"; self.image="old"; self.backlog = 174000; self.fail = None; self.calls = []
    def check(self, name):
        self.calls.append(name)
        if self.fail == name: self.fail = None; raise RuntimeError("injected")
    def preflight(self, *args, **kw): self.check("preflight")
    def compatible(self, release): self.check("compatible")
    def gate(self, owner):
        if self.marker and self.marker != owner: raise Busy("Foreign gate")
        self.marker = owner
    def ungate(self, owner):
        if self.marker not in (None, owner): raise Busy("Foreign gate")
        self.marker = None
    def drain(self, owner): self.check("drain")
    def activate(self, target, changed): self.version = target["version"]; self.image=target["images"]["app"]["configDigest"]; self.check("activate")
    def verify(self, target, maintenance=False):
        self.check("verify"); assert self.image == target["images"]["app"]["configDigest"]
    def sync_source(self, target): self.sourceVersion=target["version"]; self.check("source")
    def owns_gate(self,owner):
        if self.marker not in {None,owner}:raise Busy("Foreign gate")
        return self.marker==owner


class EngineTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name); self.store = Store(root, root / "lock")
        self.old = release("v1.0.0", "old"); self.new = release("v1.0.1", "new")
        self.store.set_active(self.old); self.host = Host(); self.engine = Engine(self.store, self.host)
        self.request = "a"*32
    def test_duplicate_is_idempotent_and_backlog_is_retained(self):
        result = self.engine.deploy(self.request, self.new, self.old["version"])
        self.assertEqual("succeeded", result["phase"]); count = len(self.host.calls)
        self.engine.deploy(self.request, self.new, self.old["version"])
        self.assertEqual(count, len(self.host.calls)); self.assertIsNone(self.host.marker)
        self.assertEqual(174000, self.host.backlog)
    def test_stale_request_never_switches(self):
        with self.assertRaises(ValueError): self.engine.deploy(self.request, self.new, "v0.0.0")
        self.assertEqual([], self.host.calls)
    def test_preflight_failure_and_busy_business_leave_old_service(self):
        for failure in ("preflight", "drain"):
            with self.subTest(failure=failure):
                self.host.fail = failure
                result = self.engine.deploy(("b" if failure == "drain" else "a")*32, self.new, "v1.0.0")
                self.assertEqual("aborted", result["phase"]); self.assertEqual("v1.0.0", self.host.version)
                self.assertIsNone(self.host.marker)
    def test_bad_image_rolls_back_without_staging_dependency(self):
        self.host.fail = "verify"
        result = self.engine.deploy(self.request, self.new, "v1.0.0")
        self.assertEqual("rolled_back", result["phase"]); self.assertEqual("v1.0.0", self.host.version)
        self.assertIsNone(self.host.marker)
    def test_crash_during_switch_is_recovered_using_durable_baseline(self):
        self.store.save({"id":self.request,"baseline":self.old,"target":self.new,"expectedVersion":"v1.0.0"},"switching")
        self.host.marker = self.request; self.host.version = "v1.0.1"
        self.host.image="new"
        self.assertEqual("recovered", self.engine.recover()[0]["phase"])
        self.assertIsNone(self.host.marker); self.assertEqual("v1.0.0", self.host.version)
    def test_lock_serializes_other_processes(self):
        with self.store.exclusive():
            other = Store(self.store.root, self.store.lock)
            with self.assertRaises(Busy):
                with other.exclusive(): pass
    def test_foreign_marker_is_not_removed(self):
        self.host.marker = "f"*32
        with self.assertRaises(RuntimeError): self.engine.deploy(self.request, self.new, "v1.0.0")
        self.assertEqual("f"*32, self.host.marker)
    def test_changed_payload_cannot_reuse_idempotency_key(self):
        self.engine.deploy(self.request, self.new, "v1.0.0")
        with self.assertRaises(ValueError): self.engine.deploy(self.request, release("v1.0.2","other"), "v1.0.0")
    def test_activation_failure_after_side_effect_restores_previous_container(self):
        self.host.fail="activate"
        result=self.engine.deploy(self.request,self.new,"v1.0.0")
        self.assertEqual("rolled_back",result["phase"]);self.assertEqual("old",self.host.image)
        self.assertIsNone(self.host.marker)
    def test_accepted_crash_finishes_restoration_without_interrupting_new_work(self):
        self.host.image="new";self.host.version="v1.0.1";self.host.sourceVersion="v1.0.1"
        self.store.save({"id":self.request,"baseline":self.old,"target":self.new,"accepted":True,"mayHaveSwitched":True},"restoring")
        result=self.engine.recover()[0]
        self.assertEqual("succeeded",result["phase"]);self.assertTrue(result["recovered"])
        self.assertNotIn("activate",self.host.calls);self.assertNotIn("drain",self.host.calls)
    def test_docs_only_release_changes_source_without_switch_or_gate(self):
        result=self.engine.deploy(self.request,release("v1.0.1","old"),"v1.0.0")
        self.assertEqual("succeeded",result["phase"]);self.assertFalse(result["switched"])
        self.assertEqual("v1.0.1",self.host.sourceVersion);self.assertNotIn("activate",self.host.calls)
    def test_docs_source_failure_restores_source_pointer(self):
        self.host.fail="source"
        result=self.engine.deploy(self.request,release("v1.0.1","old"),"v1.0.0")
        self.assertEqual("aborted",result["phase"]);self.assertEqual("v1.0.0",self.host.sourceVersion)


if __name__ == "__main__": unittest.main()
