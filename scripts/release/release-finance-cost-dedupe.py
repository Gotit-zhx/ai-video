"""Accept identical cost-import duplicates over the exact verified live runtime."""
import importlib.util
import io
import json
import sys
import zipfile

spec = importlib.util.spec_from_file_location(
    "latest", "/opt/heyi/releases/finance-fee-native-20261104/release-finance-fee-native.py")
latest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(latest)
core, storage = latest.core, latest.release.storage
ROOT, APP, FRONT = latest.ROOT, latest.APP, latest.FRONT
sql = latest.release.previous.sql
IMAGE = "ai-commerce-platform:finance-cost-dedupe-20261104"
PREVIOUS = "ai-commerce-platform:finance-fee-native-20261104"
PREFIX = "BOOT-INF/classes/com/yingdao/aicommerce/finance/FinanceCostWorkspace"


def replaced(name):
    return name == PREFIX + ".class" or name.startswith(PREFIX + "$") and name.endswith(".class")


def build():
    qa = core.read_json("qa-passed.json")
    assert qa["unitTests"] >= 17 and qa["sourceRows"] == 16460
    assert qa["parsedRows"] == 16459 and qa["uniquePrices"] == 164590
    assert qa["sourcePriceDifferences"] == 0
    app = core.inspect(APP)
    assert app["Config"]["Image"] == PREVIOUS
    original = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    previous = json.loads((ROOT.parent / "finance-fee-native-20261104" / "prepared.json").read_text())
    assert core.sha(original) == previous["jarHash"]
    expected = core.read_json("baseline-expected.json")
    folder = ROOT / "app-image"
    folder.mkdir()
    entries = {"BOOT-INF/classes/" + str(p.relative_to(ROOT / "classes")): p.read_bytes()
               for p in (ROOT / "classes").rglob("*.class")}
    assert entries and all(replaced(n) for n in entries)
    assert PREFIX + ".class" in entries
    with zipfile.ZipFile(io.BytesIO(original)) as source:
        assert {n: core.sha(source.read(n)) for n in source.namelist() if replaced(n)} == expected
    with zipfile.ZipFile(io.BytesIO(original)) as source, zipfile.ZipFile(folder / "app.jar", "w") as target:
        target.comment = source.comment
        for item in source.infolist():
            if not replaced(item.filename):
                target.writestr(item, source.read(item.filename))
        for name, value in entries.items():
            target.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(original)) as source, zipfile.ZipFile(folder / "app.jar") as target:
        assert set(target.namelist()) == {n for n in source.namelist() if not replaced(n)} | set(entries)
        assert all(source.read(n) == target.read(n) for n in source.namelist() if not replaced(n))
    (folder / "app.jar").chmod(0o644)
    guard = json.loads(core.run("python3", "/usr/local/lib/heyi/compact-release-image.py",
                               "--prepare", app["Image"], IMAGE + "-base").decode())
    core.write_json(ROOT / "image-guard.json", guard)
    (folder / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGE, str(folder))
    core.write_json(ROOT / "prepared.json", {
        "sourceImageId": app["Image"], "sourceJarHash": core.sha(original),
        "jarHash": core.sha((folder / "app.jar").read_bytes()),
        "classes": {n: core.sha(v) for n, v in entries.items()}, "unrelatedEntriesPreserved": True})
    print("Cost-import duplicate handling prepared over exact live classes.", flush=True)


def verify():
    core.health(APP)
    app = core.inspect(APP)
    assert app["Config"]["Image"] == IMAGE
    assert app["HostConfig"]["LogConfig"]["Type"] == "none"
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == core.read_json("prepared.json")["jarHash"]
    for path, expected in (("/", "200"), ("/api/v1/finance/costs/import", "401"),
                           ("/api/v1/finance/fee-exports", "401")):
        actual = core.run("curl", "--noproxy", "*", "-sS", "--max-time", "10",
                          "--resolve", "hoyee.org.cn:443:127.0.0.1", "-o", "/dev/null", "-w", "%{http_code}",
                          "https://hoyee.org.cn" + path).decode()
        assert actual == expected
    print("Live artifact, service health and import access protection verified.", flush=True)


def deploy():
    assert not (ROOT / "activation.json").exists()
    before = {n: core.inspect(n) for n in (APP, FRONT)}
    prepared = core.read_json("prepared.json")
    assert before[APP]["Image"] == prepared["sourceImageId"]
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["sourceJarHash"]
    for table, condition in (
        ("product_publish_workflows", "status IN ('PENDING','RUNNING')"),
        ("finance_authorization_sessions", "status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)"),
    ):
        assert sql("SELECT COUNT(*) FROM " + table + " WHERE " + condition).strip() == "0"
    try:
        core.run("docker", "stop", "--timeout", "120", APP)
        storage.activate(before, APP, IMAGE)
        verify()
        assert core.inspect(FRONT)["Id"] == before[FRONT]["Id"]
        assert all(core.configuration(core.inspect(n)) == core.configuration(before[n]) for n in before)
    except BaseException:
        storage.activate(before, APP, PREVIOUS)
        core.health(APP)
        raise
    core.write_json(ROOT / "activation.json", {
        "image": IMAGE, "rollbackImage": PREVIOUS, "configurationPreserved": True,
        "frontendUnchanged": True, "unrelatedEntriesPreserved": True,
        "schemaChanged": False, "persistentLogs": False})
    print("Identical cost-import duplicates are now accepted.", flush=True)


def drain_deploy():
    storage.queue.deploy = deploy
    storage.queue.drain_deploy()


if __name__ == "__main__":
    {"build": build, "drain-deploy": drain_deploy, "verify": verify}[sys.argv[2]]()
