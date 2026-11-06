"""Release the verified cold financial queries and post-commit refresh changes.

Requires this release's baseline, provenance, QA receipts and compiled classes.
Runtime configuration and existing durable queues are retained. The installed
drain/restore chain owns and releases only this invocation's reservations.
"""
import datetime
import fcntl
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import shutil
import sys
import time
import uuid
import zipfile

os.umask(0o077)
ROOT = Path(sys.argv[1]).resolve()
spec = importlib.util.spec_from_file_location("project_sync_release",
    "/opt/heyi/releases/project-sync-20261106/release-project-sync.py")
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)
core, storage, sql = previous.core, previous.storage, previous.sql
APP, FRONT = "ai-commerce-app", "ai-commerce-frontend"
IMAGE = "ai-commerce-platform:finance-cold-queries-20261106"
VERSION = "2026.11.6.2"
FAMILIES = {"FinanceRepository", "FinanceCostReport", "FinanceService", "FinanceQianyi"}


def sha(value):
    return hashlib.sha256(value).hexdigest()


def read(name):
    return json.loads((ROOT / name).read_text())


def write(name, value):
    (ROOT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def replaced(name):
    return name.startswith("BOOT-INF/classes/com/yingdao/aicommerce/finance/") and name.endswith(".class") \
        and name.rsplit("/", 1)[-1].split("$")[0].removesuffix(".class") in FAMILIES


def require_qa():
    qa = read("qa-passed.json")
    assert qa["unitTests"] >= 35 and qa["mysqlTests"] >= 10
    assert qa["failures"] == 0 and qa["baselineBytecodeEqual"]
    assert qa["snapshotConsistency"] and qa["financialMetricsEqual"] and qa["backgroundRefreshAfterCommit"]
    assert qa["coldMonthsVerified"] >= 3
    assert not qa["schemaChanged"] and not qa["globalDatabaseSettingsChanged"]
    assert re.fullmatch(r"[a-f0-9]{40}", read("provenance.json")["sourceCommit"])


def check_baseline(before, baseline):
    assert before[APP]["Id"] == baseline["appContainerId"] and before[APP]["Image"] == baseline["appImageId"]
    assert before[FRONT]["Id"] == baseline["frontendContainerId"]
    assert previous.jar_hash() == baseline["jarHash"], "Concurrent JAR change"
    assert previous.config_hash(before[APP]) == baseline["configurationHash"]
    for name, identifier in baseline["unrelatedContainers"].items():
        assert core.inspect(name)["Id"] == identifier, "Concurrent maintenance detected: " + name


def preflight_compose(before):
    labels = before[APP]["Config"]["Labels"]
    command = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
               "-p", labels["com.docker.compose.project"]]
    paths = labels["com.docker.compose.project.config_files"].split(",")
    for name in paths:
        assert Path(name).is_file(), "Missing live Compose dependency"
        command += ["-f", name]
    override = ROOT / "compose-preflight.json"
    core.write_json(override, {"services": {labels["com.docker.compose.service"]: {"image": IMAGE, "logging": {"driver": "none"}}}})
    env = dict(os.environ, HR_FRONTEND_IMAGE=before[FRONT]["Config"]["Image"])
    config = json.loads(core.run(*command, "-f", str(override), "config", "--format", "json", env=env))
    selected = config["services"][labels["com.docker.compose.service"]]
    image = core.inspect(IMAGE)["Config"]
    expected = dict(item.split("=", 1) for item in image.get("Env", []) if "=" in item)
    for key, value in selected.get("environment", {}).items():
        if value is None:
            expected.pop(key, None)
        else:
            expected[key] = str(value)
    assert expected == dict(item.split("=", 1) for item in before[APP]["Config"].get("Env", []) if "=" in item), "Rendered Compose environment differs"
    for field, image_key in (("command", "Cmd"), ("entrypoint", "Entrypoint"), ("user", "User"), ("working_dir", "WorkingDir")):
        value = selected.get(field)
        if value is None:
            value = image.get(image_key)
        assert value == before[APP]["Config"].get(image_key), "Rendered process configuration differs: " + field
    write("compose-preflight-verification.json", {"files": len(paths), "environmentPreserved": True, "processConfigurationPreserved": True})


def prepare():
    require_qa()
    assert not (ROOT / "prepared.json").exists()
    assert shutil.disk_usage("/").free > 14.5 * 1024**3, "Insufficient build and backup headroom"
    baseline, provenance = read("baseline.json"), read("provenance.json")
    before = {name: core.inspect(name) for name in (APP, FRONT)}
    check_baseline(before, baseline)
    classes = {"BOOT-INF/classes/" + str(p.relative_to(ROOT / "probe/candidate")): p.read_bytes()
               for p in (ROOT / "probe/candidate").rglob("*.class")}
    assert classes and all(replaced(name) for name in classes)
    assert {name.rsplit("/", 1)[-1].split("$")[0].removesuffix(".class") for name in classes} == FAMILIES
    old_bytes = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    assert sha(old_bytes) == baseline["jarHash"]
    folder = ROOT / "app-image"
    folder.mkdir()
    with zipfile.ZipFile(io.BytesIO(old_bytes)) as old, zipfile.ZipFile(folder / "app.jar", "w") as new:
        assert {name: sha(old.read(name)) for name in old.namelist() if replaced(name)} == baseline["classHashes"]
        new.comment = old.comment
        for item in old.infolist():
            if not replaced(item.filename):
                new.writestr(item, old.read(item.filename))
        for name, content in classes.items():
            new.writestr(name, content, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(old_bytes)) as old, zipfile.ZipFile(folder / "app.jar") as new:
        assert set(new.namelist()) == {name for name in old.namelist() if not replaced(name)} | set(classes)
        assert all(new.read(name) == old.read(name) for name in old.namelist() if not replaced(name))
    del old_bytes
    guard_path = "/usr/local/lib/heyi/compact-release-image.py"
    assert sha(Path(guard_path).read_bytes()) == provenance["compactionHelperSha256"]
    guard = json.loads(core.run("python3", guard_path, "--prepare", before[APP]["Image"], IMAGE + "-38d2-base"))
    write("image-guard.json", guard)
    (folder / "app.jar").chmod(0o644)
    (folder / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY app.jar /app/app.jar\n"
        + "LABEL org.opencontainers.image.revision=" + provenance["sourceCommit"]
        + " org.opencontainers.image.version=" + VERSION + "\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGE, str(folder))
    preflight_compose(before)
    write("prepared.json", {"sourceCommit": provenance["sourceCommit"], "version": VERSION,
        "sourceImage": before[APP]["Image"], "sourceContainerId": before[APP]["Id"],
        "sourceJarHash": baseline["jarHash"], "jarHash": sha((folder / "app.jar").read_bytes()),
        "classes": {name: sha(value) for name, value in classes.items()},
        "configurationHash": baseline["configurationHash"], "frontendContainerId": before[FRONT]["Id"],
        "unrelatedJarEntriesPreserved": True, "schemaChanged": False})
    print("Four-family candidate prepared; all other JAR entries and runtime configuration preserved.", flush=True)


def verify():
    receipt = read("prepared.json")
    core.health(APP)
    app, front = core.inspect(APP), core.inspect(FRONT)
    assert app["State"]["Running"] and app["Config"]["Image"] == IMAGE
    assert app["Config"]["Labels"]["org.opencontainers.image.revision"] == receipt["sourceCommit"]
    assert app["Config"]["Labels"]["org.opencontainers.image.version"] == VERSION
    assert previous.jar_hash() == receipt["jarHash"]
    assert previous.config_hash(app) == receipt["configurationHash"]
    assert front["Id"] == receipt["frontendContainerId"]
    assert app["HostConfig"]["LogConfig"]["Type"] == "none" and not app.get("LogPath")
    assert not app["State"]["OOMKilled"] and app["RestartCount"] == 0
    checks = {}
    for path, expected in (("/", "200"), ("/actuator/health", "200"),
                           ("/api/v1/finance/overview", "401"),
                           ("/api/v1/finance/workspace/charges", "401")):
        status = core.run("curl", "--noproxy", "*", "-sS", "--max-time", "15", "--resolve",
            "hoyee.org.cn:443:127.0.0.1", "-o", "/dev/null", "-w", "%{http_code}", "https://hoyee.org.cn" + path).decode()
        assert status == expected, path
        checks[path] = status
    assert sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1") == "147"
    write("health-verification.json", {"observedAt": datetime.datetime.now().astimezone().isoformat(),
        "checks": checks, "jarHash": receipt["jarHash"], "imageId": app["Image"],
        "configurationPreserved": True, "frontendPreserved": True, "flywayVersion": 147})
    print("Live JAR, health, authentication, configuration and frontend identity verified.", flush=True)


def deploy():
    require_qa()
    assert not (ROOT / "activation.json").exists()
    baseline = read("baseline.json")
    before = {name: core.inspect(name) for name in (APP, FRONT)}
    check_baseline(before, baseline)
    preflight_compose(before)
    for table, condition in (("product_publish_workflows", "status IN ('PENDING','RUNNING')"),
                             ("finance_authorization_sessions", "status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)"),
                             ("finance_ad_accounts", "status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)"),
                             ("finance_sync_states", "status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)")):
        assert sql("SELECT EXISTS(SELECT 1 FROM " + table + " WHERE " + condition + " LIMIT 1)") == "0", "Request still active: " + table
    storage.base.release.idle()
    preserved = {name: core.inspect(name)["Id"] for name in baseline["unrelatedContainers"]}
    rollback = before[APP]["Config"]["Image"]
    write("cost-backlog.json", {"queuedBeforeRestart": int(sql("SELECT COUNT(*) FROM finance_order_cost_jobs")),
        "maxBatchSize": 100, "transactionTimeoutSeconds": 30, "durableQueueRetained": True,
        "atomicResultAndCompletion": True, "snapshotAfterPrimaryKeyClaim": True})
    try:
        core.run("docker", "stop", "--timeout", "120", APP)
        storage.activate(before, APP, IMAGE)
        verify()
        assert all(core.inspect(name)["Id"] == identifier for name, identifier in preserved.items())
    except BaseException:
        try:
            current = core.inspect(APP)
        except Exception:
            current = None
        if current is None or current["Id"] == before[APP]["Id"] or current["Config"]["Image"] == IMAGE:
            storage.activate(before, APP, rollback)
            core.health(APP)
        raise
    write("activation.json", {"version": VERSION, "sourceCommit": read("prepared.json")["sourceCommit"],
        "completedAt": datetime.datetime.now().astimezone().isoformat(), "image": IMAGE,
        "imageId": core.inspect(APP)["Image"], "rollbackImage": rollback,
        "unrelatedContainersPreserved": True, "configurationPreserved": True,
        "schemaChanged": False, "persistentLogsAdded": False, "syncCheckpointsReset": False})
    print("BI performance candidate activated; original durable work and unrelated services retained.", flush=True)


def drain_deploy():
    require_qa()
    lease = str(uuid.uuid4())
    write("account-drain.json", {"lease": lease})
    try:
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            sql("UPDATE finance_qianyi_accounts SET lease_token='" + lease + "',lease_until=DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 15 MINUTE) "
                "WHERE enabled=TRUE AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(6))")
            if sql("SELECT COUNT(*) FROM finance_qianyi_accounts WHERE enabled=TRUE AND (lease_token IS NULL OR lease_token<>'" + lease + "')") == "0":
                storage.queue.deploy = deploy
                storage.queue.drain_deploy()
                return
            time.sleep(1)
        raise RuntimeError("Qianyi accounts have not reached a safe checkpoint")
    finally:
        sql("UPDATE finance_qianyi_accounts SET lease_token=NULL,lease_until=NULL WHERE lease_token='" + lease + "'")
        write("account-drain-restored.json", {"ownReservationsReleased": True, "checkpointsChanged": False})
        print("This release's Qianyi account reservations released.", flush=True)


if __name__ == "__main__":
    with open("/run/lock/heyi-application-release.lock", "a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        {"prepare": prepare, "drain-deploy": drain_deploy, "verify": verify}[sys.argv[2]]()
