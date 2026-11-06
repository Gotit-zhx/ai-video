"""Repair an observed marker-only Qianyi checkpoint failure over the verified date release."""
import importlib.util
import io
import json
import sys
import zipfile

spec = importlib.util.spec_from_file_location(
    "report", "/opt/heyi/releases/finance-report-basis-20261105/release-finance-report-basis.py")
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)
core, storage, ROOT, APP, FRONT = report.core, report.storage, report.ROOT, report.APP, report.FRONT
IMAGE = "ai-commerce-platform:finance-report-basis-resume-20261105"
PREVIOUS = "ai-commerce-platform:finance-report-basis-20261105"
ENTRY = "BOOT-INF/classes/com/yingdao/aicommerce/finance/FinanceQianyiWorker.class"


def build():
    qa = core.read_json("qa-passed.json")
    assert qa["unitTests"] == 127 and qa["mysqlReceiptCases"] == 10 and qa["mysqlPassed"]
    app = core.inspect(APP)
    assert app["Config"]["Image"] == PREVIOUS
    original = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    replacement = (ROOT / "FinanceQianyiWorker.class").read_bytes()
    folder = ROOT / "app-image"
    folder.mkdir(exist_ok=True)
    with zipfile.ZipFile(io.BytesIO(original)) as source, zipfile.ZipFile(folder / "app.jar", "w") as target:
        assert core.sha(source.read(ENTRY)) == core.read_json("baseline-worker.json")["sha256"]
        target.comment = source.comment
        for item in source.infolist():
            target.writestr(item, replacement if item.filename == ENTRY else source.read(item.filename))
    with zipfile.ZipFile(io.BytesIO(original)) as source, zipfile.ZipFile(folder / "app.jar") as target:
        assert source.namelist() == target.namelist()
        assert all(source.read(n) == target.read(n) for n in source.namelist() if n != ENTRY)
    guard = json.loads(core.run("python3", "/usr/local/lib/heyi/compact-release-image.py",
                               "--prepare", app["Image"], IMAGE + "-base").decode())
    core.write_json(ROOT / "image-guard.json", guard)
    (folder / "app.jar").chmod(0o644)
    (folder / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGE, str(folder))
    core.write_json(ROOT / "prepared.json", {
        "sourceImage": app["Image"], "sourceJarHash": core.sha(original),
        "jarHash": core.sha((folder / "app.jar").read_bytes()),
        "configurationHash": report.config_hash(app), "frontendContainerId": core.inspect(FRONT)["Id"],
        "unrelatedEntriesPreserved": True})
    print("Marker-only checkpoint fix built and unrelated entries verified.", flush=True)


def verify():
    core.health(APP)
    receipt = core.read_json("prepared.json")
    app = core.inspect(APP)
    assert app["Config"]["Image"] == IMAGE and app["State"]["Running"]
    assert app["HostConfig"]["LogConfig"]["Type"] == "none"
    assert report.config_hash(app) == receipt["configurationHash"]
    assert core.inspect(FRONT)["Id"] == receipt["frontendContainerId"]
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == receipt["jarHash"]


def deploy():
    assert not (ROOT / "activation.json").exists()
    before = {name: core.inspect(name) for name in (APP, FRONT)}
    prepared = core.read_json("prepared.json")
    assert before[APP]["Image"] == prepared["sourceImage"]
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["sourceJarHash"]
    for table, condition in (("product_publish_workflows", "status IN ('PENDING','RUNNING')"),
                             ("finance_authorization_sessions", "status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)")):
        assert report.sql("SELECT COUNT(*) FROM " + table + " WHERE " + condition).strip() == "0"
    try:
        core.run("docker", "stop", "--timeout", "120", APP)
        storage.activate(before, APP, IMAGE)
        verify()
    except BaseException:
        storage.activate(before, APP, PREVIOUS)
        core.health(APP)
        raise
    # Re-arm only confirmed marker-only failures. Real cursors and failures are
    # preserved; the normal worker owns success, clearing errors and publication.
    changed = report.sql("""
        UPDATE finance_qianyi_connections
        SET next_at=LEAST(next_at,UTC_TIMESTAMP(6))
        WHERE enabled=TRUE AND status='ERROR' AND checkpoint_json IS NOT NULL
          AND JSON_LENGTH(JSON_REMOVE(checkpoint_json,'$.sourceVersion','$.sourcesChanged'))=0;
        SELECT ROW_COUNT();
        """).strip()
    core.write_json(ROOT / "activation.json", {
        "image": IMAGE, "configurationPreserved": True, "frontendUnchanged": True,
        "checkpointsReset": False, "markerOnlyFailuresRearmed": int(changed),
        "persistentLogs": False})
    print("Qianyi marker-only recovery deployed; original cursors retained.", flush=True)


def drain_deploy():
    storage.queue.deploy = deploy
    storage.queue.drain_deploy()


if __name__ == "__main__":
    {"build": build, "drain-deploy": drain_deploy, "verify": verify}[sys.argv[2]]()
