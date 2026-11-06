"""Release the reviewed brush-source aggregate over the verified live artifacts.

Run on the production host with an isolated release directory and prepared QA
receipts. This helper deliberately reuses the installed activation/drain chain.
It is specific to this release; importing historical helpers does not rerun them.
"""
import datetime
import gzip
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import uuid
import zipfile

os.umask(0o077)
ROOT = Path(sys.argv[1]).resolve()
spec = importlib.util.spec_from_file_location(
    "storage_release",
    "/opt/heyi/releases/finance-storage-recovery-20261102/release-finance-storage-recovery.py")
storage = importlib.util.module_from_spec(spec)
spec.loader.exec_module(storage)
core, sql = storage.core, storage.base.sql
APP, FRONT = "ai-commerce-app", "ai-commerce-frontend"
VERSION = "2026.11.6"
IMAGES = {APP: "ai-commerce-platform:brush-source-summary-20261106",
          FRONT: "ai-commerce-frontend:brush-source-summary-20261106"}
FILES = ("index.html", "finance-charges.js")
PRESERVED = ("app.js", "finance-workspace.js", "finance-workspace.css", "finance-costs.js",
             "finance-label-costs.js", "finance-qianyi.js", "finance-fee-exports.js",
             "assets/templates/finance-brush-cost-template.xlsx")
PREFIX = "BOOT-INF/classes/com/yingdao/aicommerce/finance/FinanceOrderCharges"


def sha(value):
    return hashlib.sha256(value).hexdigest()


def read(name):
    return json.loads((ROOT / name).read_text())


def write(name, value):
    (ROOT / name).write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def replaced(name):
    return name == PREFIX + ".class" or name.startswith(PREFIX + "$") and name.endswith(".class")


def live_file(name):
    return core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + name)


def jar_hash():
    return core.run("docker", "exec", APP, "sha256sum", "/app/app.jar").decode().split()[0]


def configuration(info):
    host = info["HostConfig"]
    return {"runtime": core.configuration(info),
            "resources": {k: host.get(k) for k in ("Memory", "MemorySwap", "NanoCpus", "PidsLimit",
                                                   "CapAdd", "CapDrop", "SecurityOpt", "ReadonlyRootfs", "Ulimits")},
            "logging": host["LogConfig"]}


def config_hash(info):
    return sha(json.dumps(configuration(info), sort_keys=True).encode())


def require_qa():
    qa = read("qa-passed.json")
    assert qa["baselineBytecodeEqual"] and qa["unrelatedSourceClassesPreserved"]
    assert qa["mysqlCases"] >= 6 and qa["mysqlFailures"] == 0
    assert qa["uiSuites"] >= 3 and qa["parserPassed"] >= 2
    assert qa["schemaChanged"] is False
    assert len(read("provenance.json")["sourceCommit"]) == 40
    return qa


def prepare():
    require_qa()
    assert not (ROOT / "prepared.json").exists()
    provenance, baseline = read("provenance.json"), read("baseline.json")
    before = {n: core.inspect(n) for n in (APP, FRONT)}
    assert all(before[n]["Image"] == baseline["images"][n] for n in before), "Concurrent image change"
    assert jar_hash() == baseline["jarHash"], "Concurrent JAR change"
    previous = ROOT / "build/previous.jar"
    assert sha(previous.read_bytes()) == baseline["jarHash"]
    classes = {"BOOT-INF/classes/" + str(p.relative_to(ROOT / "build/classes")): p.read_bytes()
               for p in (ROOT / "build/classes").rglob("*.class")}
    assert classes and all(replaced(n) for n in classes)
    appdir = ROOT / "app-image"
    appdir.mkdir()
    with zipfile.ZipFile(previous) as old, zipfile.ZipFile(appdir / "app.jar", "w") as new:
        assert {n: sha(old.read(n)) for n in old.namelist() if replaced(n)} == baseline["classHashes"]
        new.comment = old.comment
        for item in old.infolist():
            if not replaced(item.filename):
                new.writestr(item, old.read(item.filename))
        for name, value in classes.items():
            new.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(previous) as old, zipfile.ZipFile(appdir / "app.jar") as new:
        assert set(new.namelist()) == {n for n in old.namelist() if not replaced(n)} | set(classes)
        assert all(old.read(n) == new.read(n) for n in old.namelist() if not replaced(n))
    guard_path = "/usr/local/lib/heyi/compact-release-image.py"
    assert sha(Path(guard_path).read_bytes()) == provenance["compactionHelperSha256"]
    guard = json.loads(core.run("python3", guard_path, "--prepare", before[APP]["Image"], IMAGES[APP] + "-base"))
    write("app-image-guard.json", guard)
    (appdir / "app.jar").chmod(0o644)
    labels = "\nLABEL org.opencontainers.image.revision=" + provenance["sourceCommit"] + " org.opencontainers.image.version=" + VERSION + "\n"
    (appdir / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY app.jar /app/app.jar" + labels)
    core.run("docker", "build", "--pull=false", "-t", IMAGES[APP], str(appdir))
    frontdir = ROOT / "frontend-image"
    frontdir.mkdir()
    files = {}
    for name in FILES:
        assert sha(live_file(name)) == baseline["frontendFiles"][name], "Concurrent frontend change"
        value = (ROOT / "build/frontend" / name).read_bytes()
        files[name] = sha(value)
        (frontdir / name).write_bytes(value)
        (frontdir / (name + ".gz")).write_bytes(gzip.compress(value, mtime=0))
        for p in (frontdir / name, frontdir / (name + ".gz")):
            p.chmod(0o644)
    guard = json.loads(core.run("python3", guard_path, "--prepare", before[FRONT]["Image"], IMAGES[FRONT] + "-base"))
    write("frontend-image-guard.json", guard)
    copied = " ".join(n for name in FILES for n in (name, name + ".gz"))
    removed = " ".join("/usr/share/nginx/html/" + name + ".br" for name in FILES)
    (frontdir / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY " + copied
        + " /usr/share/nginx/html/\nRUN rm -f " + removed + labels)
    core.run("docker", "build", "--pull=false", "-t", IMAGES[FRONT], str(frontdir))
    write("prepared.json", {"sourceCommit": provenance["sourceCommit"], "version": VERSION,
        "sourceImages": {n: before[n]["Image"] for n in before},
        "sourceContainerIds": {n: before[n]["Id"] for n in before},
        "sourceJarHash": baseline["jarHash"], "jarHash": sha((appdir / "app.jar").read_bytes()),
        "classes": {n: sha(v) for n, v in classes.items()}, "files": files,
        "sourceFiles": baseline["frontendFiles"],
        "preservedFiles": {n: sha(live_file(n)) for n in PRESERVED},
        "configurationHashes": {n: config_hash(info) for n, info in before.items()},
        "unrelatedJarEntriesPreserved": True, "schemaChanged": False})
    print("Candidate images prepared; baseline, class scope, unrelated entries and compaction verified.", flush=True)


def read_verify():
    receipt = read("prepared.json")
    jar = ROOT / "app-image/app.jar"
    assert sha(jar.read_bytes()) == receipt["jarHash"]
    runtime = ROOT / "build/verify-runtime"
    runtime.mkdir(exist_ok=True)
    runtime.chmod(0o755)
    with zipfile.ZipFile(jar) as archive:
        for name in archive.namelist():
            if name.endswith("/"):
                continue
            if name.startswith("BOOT-INF/classes/"):
                relative = "classes/" + name.removeprefix("BOOT-INF/classes/")
            elif name.startswith("BOOT-INF/lib/"):
                relative = "lib/" + name.removeprefix("BOOT-INF/lib/")
            else:
                continue
            target = runtime / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(name))
    for source in (ROOT / "build/verify-classes").rglob("*.class"):
        target = runtime / "verify-classes" / source.relative_to(ROOT / "build/verify-classes")
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source.read_bytes())
    for path in runtime.rglob("*"):
        path.chmod(0o755 if path.is_dir() else 0o644)
    values = dict(e.split("=", 1) for e in core.inspect(APP)["Config"]["Env"] if "=" in e)
    env = dict(os.environ)
    env.update({k: values[k] for k in ("DB_URL", "DB_USERNAME", "DB_PASSWORD")})
    body = core.run("docker", "run", "--rm", "--name", "project-sync-readonly-20261106",
        "--memory", "512m", "--cpus", "0.5", "--pids-limit", "64", "--read-only",
        "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--log-driver", "none",
        "--network", "web_sdk__default", "--tmpfs", "/tmp:rw,noexec,nosuid,size=64m",
        "--mount", "type=bind,src=" + str(runtime) + ",dst=/qa,readonly",
        "-e", "DB_URL", "-e", "DB_USERNAME", "-e", "DB_PASSWORD", "--entrypoint", "java",
        IMAGES[APP], "-cp", "/qa/verify-classes:/qa/classes:/qa/lib/*",
        "com.yingdao.aicommerce.finance.FinanceBrushSourceSummaryVerify", env=env)
    result = json.loads(body.decode().strip().splitlines()[-1])
    assert result["readOnly"] and result["aggregateChecksPassed"] and result["sourceGroupsVerified"] > 0
    result["observedAt"] = datetime.datetime.now().astimezone().isoformat()
    write("read-verification.json", result)
    print("Read-only production aggregate comparison passed; no source records written.", flush=True)


def verify():
    core.health(APP)
    receipt = read("prepared.json")
    for name in (APP, FRONT):
        info = core.inspect(name)
        assert info["State"]["Running"] and info["Config"]["Image"] == IMAGES[name]
        assert config_hash(info) == receipt["configurationHashes"][name]
        assert info["HostConfig"]["LogConfig"]["Type"] == "none" and not info.get("LogPath")
        assert not info["State"]["OOMKilled"] and info["RestartCount"] == 0
        assert info["Config"]["Labels"]["org.opencontainers.image.revision"] == receipt["sourceCommit"]
    assert jar_hash() == receipt["jarHash"]
    for name, digest in receipt["files"].items():
        value = live_file(name)
        assert sha(value) == digest and gzip.decompress(live_file(name + ".gz")) == value
        body = core.run("curl", "--noproxy", "*", "-fsS", "--max-time", "15", "--compressed",
            "--resolve", "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/" + name + "?v=20261106-brush-column-totals")
        assert sha(body) == digest
    for name, digest in receipt["preservedFiles"].items():
        assert sha(live_file(name)) == digest, "Unrelated frontend resource changed"
    for path, expected in (("/", "200"), ("/actuator/health", "200"),
                           ("/api/v1/finance/charges", "401"),
                           ("/api/v1/finance/records?kind=ORDER&orderScope=ALL", "401")):
        status = core.run("curl", "--noproxy", "*", "-sS", "--max-time", "15", "--resolve",
            "hoyee.org.cn:443:127.0.0.1", "-o", "/dev/null", "-w", "%{http_code}", "https://hoyee.org.cn" + path).decode()
        assert status == expected
    assert sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1") == "147"
    read_verify()
    print("Live artifacts, gzip/HTTP, configuration, source revision, schema and access guards verified.", flush=True)


def deploy():
    require_qa()
    assert not (ROOT / "activation.json").exists()
    receipt = read("prepared.json")
    before = {n: core.inspect(n) for n in (APP, FRONT)}
    assert all(before[n]["Id"] == receipt["sourceContainerIds"][n] and before[n]["Image"] == receipt["sourceImages"][n]
               for n in before), "Concurrent deployment detected"
    assert all(config_hash(before[n]) == receipt["configurationHashes"][n] for n in before)
    assert jar_hash() == receipt["sourceJarHash"]
    assert all(sha(live_file(n)) == receipt["sourceFiles"][n] for n in FILES)
    for table, condition in (("product_publish_workflows", "status IN ('PENDING','RUNNING')"),
                             ("finance_authorization_sessions", "status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)"),
                             ("finance_ad_accounts", "status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)"),
                             ("finance_sync_states", "status='RUNNING' AND lease_until>UTC_TIMESTAMP(6)"),
                             ("finance_order_cost_jobs", "1=1")):
        assert sql("SELECT EXISTS(SELECT 1 FROM " + table + " WHERE " + condition + " LIMIT 1)") == "0", "Finance request in progress: " + table
    storage.base.release.idle()
    preserved = {c["Name"]: c["Id"] for c in json.loads(core.run("docker", "inspect", *core.run("docker", "ps", "-q").decode().split()))
                 if c["Name"].lstrip("/") not in (APP, FRONT)}
    rollback = {n: before[n]["Config"]["Image"] for n in before}
    try:
        core.run("docker", "stop", "--timeout", "120", APP)
        storage.activate(before, APP, IMAGES[APP])
        core.health(APP)
        storage.activate(before, FRONT, IMAGES[FRONT])
        verify()
        assert all(core.inspect(n)["Id"] == identifier for n, identifier in preserved.items())
    except BaseException:
        storage.activate(before, APP, rollback[APP])
        core.health(APP)
        storage.activate(before, FRONT, rollback[FRONT])
        raise
    write("activation.json", {"version": VERSION, "sourceCommit": receipt["sourceCommit"],
        "completedAt": datetime.datetime.now().astimezone().isoformat(), "images": IMAGES,
        "imageIds": {n: core.inspect(n)["Image"] for n in before}, "rollbackImages": rollback,
        "configurationPreserved": True, "unrelatedContainersPreserved": True,
        "schemaChanged": False, "persistentLogsAdded": False, "syncCheckpointsReset": False})
    print("Brush-source aggregate deployed; unrelated services, schema and configuration preserved.", flush=True)


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
        raise RuntimeError("Qianyi account requests did not reach a safe checkpoint")
    finally:
        sql("UPDATE finance_qianyi_accounts SET lease_token=NULL,lease_until=NULL WHERE lease_token='" + lease + "'")
        write("account-drain-restored.json", {"ownReservationsReleased": True, "checkpointsChanged": False})
        print("Qianyi account reservations released.", flush=True)


if __name__ == "__main__":
    {"prepare": prepare, "read-verify": read_verify, "drain-deploy": drain_deploy, "verify": verify}[sys.argv[2]]()
