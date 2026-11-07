"""Durable deployment state machine, independently fault-tested with a fake host."""
import time
from .state import Busy, read


class Engine:
    def __init__(self, store, host):
        self.store, self.host = store, host

    def deploy(self, request, target, expected_version, rollback=False, restart=False):
        with self.store.exclusive():
            previous = read(self.store.path(request))
            if previous:
                if previous["target"]["version"] != target["version"] or previous["expectedVersion"] != expected_version or previous.get("rollback",False)!=rollback or previous.get("restart",False)!=restart:
                    raise ValueError("Idempotency key reused for a different request")
                if previous["phase"] in {"succeeded", "rolled_back", "aborted", "recovered"}: return previous
                raise Busy("Operation must be recovered before retry")
            if self.store.incomplete(): raise Busy("An interrupted operation needs recovery")
            baseline = self.store.active()
            if not baseline or baseline["version"] != expected_version:
                raise ValueError("Stale deployment baseline")
            op = {"id": request, "target": target, "baseline": baseline, "expectedVersion": expected_version,
                  "rollback": rollback, "restart":restart, "createdAt": int(time.time())}
            self.store.save(op, "preflight")
            gate = False; switched = False
            try:
                # All authenticity, migration, drift, image and capacity checks
                # run under the same lock again after the earlier prefetch.
                self.host.preflight(target, baseline, rollback=rollback, restart=restart)
                changed = [name for name in ("app", "frontend") if target["images"][name]["configDigest"] != baseline["images"][name]["configDigest"]]
                if restart: changed = ["app"]
                if changed:
                    # Write intent BEFORE acquiring the gate or touching containers.
                    self.store.save(op, "gating", changed=changed)
                    self.host.gate(request); gate = True
                    self.store.save(op, "draining")
                    self.host.drain(request)
                    self.store.save(op, "switching", mayHaveSwitched=True, safePointReached=True)
                    switched = True
                    self.host.activate(target, changed)
                    self.store.save(op, "verifying")
                    self.host.verify(target, maintenance=True)
                else:
                    self.host.verify(target, maintenance=False)
                self.store.save(op, "source_sync", sourceChanging=True)
                self.host.sync_source(target)
                # Persist the accepted target before reopening work. A crash
                # after ungating must finish acceptance, not interrupt new work.
                self.store.save(op, "restoring", accepted=True)
                self.store.set_active(target)
                if gate: self.host.ungate(request); gate = False
                self.host.verify(target, maintenance=False)
                return self.store.save(op, "succeeded", finishedAt=int(time.time()), switched=bool(changed))
            except Exception as error:
                # Only a short category is public: subprocess output may contain
                # runtime config, credentials, business data or private paths.
                self.store.save(op, "recovering", errorType=type(error).__name__)
                try:
                    if switched:
                        if not gate:
                            self.host.gate(request)
                            self.host.drain(request)
                        self.host.compatible(baseline)
                        self.host.activate(baseline, ["app", "frontend"])
                        self.host.verify(baseline, maintenance=True)
                    if op.get("sourceChanging"): self.host.sync_source(baseline)
                    self.host.ungate(request); gate = False
                    self.host.verify(baseline, maintenance=False)
                    self.store.set_active(baseline)
                    return self.store.save(op, "rolled_back" if switched else "aborted", finishedAt=int(time.time()))
                except Exception as recovery_error:
                    self.store.save(op, "needs_attention", errorType=type(recovery_error).__name__)
                    raise RuntimeError("Recovery needs operator attention; inspect doctor") from None

    def recover(self):
        with self.store.exclusive():
            results = []
            for op in self.store.incomplete():
                if op.get('adoption'):raise Busy('Recover the initial adoption through its persistent handler')
                baseline = op["baseline"]
                # A crash in ANY write-ahead phase can have happened after a
                # side effect. Restore the verified baseline unconditionally.
                try:
                    if op.get("accepted") and op["phase"] not in {"recovering","needs_attention"}:
                        target=op["target"]
                        try:
                            self.host.compatible(target)
                            self.host.verify(target,maintenance=self.host.owns_gate(op["id"]))
                            self.host.ungate(op["id"])
                            self.host.verify(target,maintenance=False)
                            self.store.set_active(target)
                            results.append(self.store.save(op,"succeeded",recovered=True,finishedAt=int(time.time())))
                            continue
                        except Exception as error:
                            # If accepted work was reopened, reacquire the gate
                            # and prove a fresh safe point before reverting it.
                            self.store.save(op,"recovering",errorType=type(error).__name__)
                    self.host.compatible(baseline)
                    if op.get("mayHaveSwitched") or op["phase"] in {"switching", "verifying", "restoring"}:
                        already_gated=self.host.owns_gate(op["id"])
                        self.host.gate(op["id"])
                        if not (already_gated and op.get("safePointReached") and not op.get("accepted")):
                            self.host.drain(op["id"])
                        self.host.activate(baseline, ["app", "frontend"])
                        self.host.verify(baseline, maintenance=True)
                    if op.get("sourceChanging"): self.host.sync_source(baseline)
                    self.host.ungate(op["id"])
                    self.host.verify(baseline, maintenance=False)
                    self.store.set_active(baseline)
                    results.append(self.store.save(op, "recovered", finishedAt=int(time.time())))
                except Exception as error:
                    self.store.save(op, "needs_attention", errorType=type(error).__name__)
                    raise RuntimeError("Recovery needs operator attention") from None
            return results
