"""Expose source orders without changing settlement accounting or sync workers."""
import gzip
import importlib.util
import io
import json
import sys
import zipfile

spec = importlib.util.spec_from_file_location(
    "latest", "/opt/heyi/releases/finance-group-shops-20261104/release-finance-group-shops.py")
latest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(latest)
core, storage = latest.core, latest.storage
ROOT, APP, FRONT = latest.ROOT, latest.APP, latest.FRONT
sql = latest.latest.sql
IMAGES = {APP: "ai-commerce-platform:finance-source-orders-20261105",
          FRONT: "ai-commerce-frontend:finance-source-orders-20261105"}
PREVIOUS = {APP: "ai-commerce-platform:finance-cost-dedupe-20261104",
            FRONT: "ai-commerce-frontend:finance-group-shops-20261104"}
FILES = ("index.html", "finance-workspace.js")
FAMILIES = ("FinanceRepository", "FinanceController")
PREFIX = "BOOT-INF/classes/com/yingdao/aicommerce/finance/"


def replaced(name):
    return any(name == PREFIX + family + ".class" or name.startswith(PREFIX + family + "$")
               for family in FAMILIES)


def config_hash(info):
    return core.sha(json.dumps(core.configuration(info), sort_keys=True).encode())


def build():
    qa = core.read_json("qa-passed.json")
    assert qa["databaseChecks"] >= 4 and qa["uiSuites"] >= 4
    acceptance = core.read_json("read-verification.json")
    assert acceptance["sourceOrders"] == 12 and acceptance["settledOctoberOrders"] == 4
    assert acceptance["accountingBucketsEqual"] and acceptance["productCostSummaryEqual"]
    before = {name: core.inspect(name) for name in (APP, FRONT)}
    assert all(before[name]["Config"]["Image"] == PREVIOUS[name] for name in before)
    original = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    expected = core.read_json("baseline-expected.json")
    entries = {"BOOT-INF/classes/" + str(p.relative_to(ROOT / "classes")): p.read_bytes()
               for p in (ROOT / "classes").rglob("*.class")}
    assert entries and all(replaced(name) for name in entries)
    folder = ROOT / "app-image"
    folder.mkdir()
    with zipfile.ZipFile(io.BytesIO(original)) as source, zipfile.ZipFile(folder / "app.jar", "w") as target:
        assert {n: core.sha(source.read(n)) for n in source.namelist() if replaced(n)} == expected
        target.comment = source.comment
        for item in source.infolist():
            if not replaced(item.filename):
                target.writestr(item, source.read(item.filename))
        for name, value in entries.items():
            target.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(original)) as source, zipfile.ZipFile(folder / "app.jar") as target:
        assert set(target.namelist()) == {n for n in source.namelist() if not replaced(n)} | set(entries)
        assert all(source.read(n) == target.read(n) for n in source.namelist() if not replaced(n))
    guard = json.loads(core.run("python3", "/usr/local/lib/heyi/compact-release-image.py",
                               "--prepare", before[APP]["Image"], IMAGES[APP] + "-base").decode())
    core.write_json(ROOT / "app-image-guard.json", guard)
    (folder / "app.jar").chmod(0o644)
    (folder / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGES[APP], str(folder))
    expected_front = core.read_json("frontend-baseline.json")
    for name in FILES:
        assert core.sha(latest.live_file(name)) == expected_front[name], "Concurrent frontend change"
    folder = ROOT / "frontend"
    files = {}
    for name in FILES:
        value = (folder / name).read_bytes()
        files[name] = core.sha(value)
        (folder / (name + ".gz")).write_bytes(gzip.compress(value, mtime=0))
        for p in (folder / name, folder / (name + ".gz")):
            p.chmod(0o644)
    guard = json.loads(core.run("python3", "/usr/local/lib/heyi/compact-release-image.py",
                               "--prepare", before[FRONT]["Image"], IMAGES[FRONT] + "-base").decode())
    core.write_json(ROOT / "frontend-image-guard.json", guard)
    (folder / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY "
        + " ".join(n for file in FILES for n in (file, file + ".gz"))
        + " /usr/share/nginx/html/\nRUN rm -f "
        + " ".join("/usr/share/nginx/html/" + file + ".br" for file in FILES) + "\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGES[FRONT], str(folder))
    core.write_json(ROOT / "prepared.json", {
        "sourceImages": {n: before[n]["Image"] for n in before},
        "sourceJarHash": core.sha(original), "jarHash": core.sha((ROOT / "app-image/app.jar").read_bytes()),
        "files": files, "sourceFiles": expected_front,
        "configurationHashes": {n: config_hash(before[n]) for n in before},
        "preservedFiles": {n: core.sha(latest.live_file(n)) for n in
                           ("finance-qianyi.js", "finance-costs.js", "finance-fee-exports.js", "finance-label-costs.js")},
        "unrelatedEntriesPreserved": True})
    print("Verified source-order artifacts prepared; accounting and sync class files preserved.", flush=True)


def verify():
    core.health(APP)
    receipt = core.read_json("prepared.json")
    for name in (APP, FRONT):
        info = core.inspect(name)
        assert info["State"]["Running"] and info["Config"]["Image"] == IMAGES[name]
        assert config_hash(info) == receipt["configurationHashes"][name]
        assert info["HostConfig"]["LogConfig"]["Type"] == "none"
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == receipt["jarHash"]
    for name, digest in receipt["files"].items():
        value = latest.live_file(name)
        assert core.sha(value) == digest
        assert gzip.decompress(latest.live_file(name + ".gz")) == value
        served = core.run("curl", "--noproxy", "*", "-fsS", "--max-time", "15",
                          "--resolve", "hoyee.org.cn:443:127.0.0.1",
                          "https://hoyee.org.cn/" + name + "?v=20261105-source-orders")
        assert core.sha(served) == digest
    for name, digest in receipt["preservedFiles"].items():
        assert core.sha(latest.live_file(name)) == digest
    for path, expected in (("/", "200"), ("/api/v1/finance/records?kind=ORDER&orderScope=ALL", "401"),
                           ("/api/v1/finance/export?view=ORDER&orderScope=ALL", "401")):
        code = core.run("curl", "--noproxy", "*", "-sS", "--max-time", "15",
                        "--resolve", "hoyee.org.cn:443:127.0.0.1", "-o", "/dev/null", "-w", "%{http_code}",
                        "https://hoyee.org.cn" + path).decode()
        assert code == expected
    print("Live artifacts, configuration, protected access and preserved components verified.", flush=True)


def deploy():
    assert not (ROOT / "activation.json").exists()
    receipt = core.read_json("prepared.json")
    before = {name: core.inspect(name) for name in (APP, FRONT)}
    assert all(before[n]["Image"] == receipt["sourceImages"][n] for n in before)
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == receipt["sourceJarHash"]
    assert all(core.sha(latest.live_file(n)) == receipt["sourceFiles"][n] for n in FILES)
    for table, condition in (("product_publish_workflows", "status IN ('PENDING','RUNNING')"),
                             ("finance_authorization_sessions", "status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)")):
        assert sql("SELECT COUNT(*) FROM " + table + " WHERE " + condition).strip() == "0"
    try:
        core.run("docker", "stop", "--timeout", "120", APP)
        storage.activate(before, APP, IMAGES[APP])
        core.health(APP)
        storage.activate(before, FRONT, IMAGES[FRONT])
        verify()
    except BaseException:
        storage.activate(before, APP, PREVIOUS[APP])
        core.health(APP)
        storage.activate(before, FRONT, PREVIOUS[FRONT])
        raise
    core.write_json(ROOT / "activation.json", {
        "images": IMAGES, "rollbackImages": PREVIOUS, "configurationPreserved": True,
        "schemaChanged": False, "persistentLogs": False, "accountingClassFilesPreserved": True})
    print("Source-order lookup deployed; settlement accounting and source records preserved.", flush=True)


def drain_deploy():
    storage.queue.deploy = deploy
    storage.queue.drain_deploy()


if __name__ == "__main__":
    {"build": build, "drain-deploy": drain_deploy, "verify": verify}[sys.argv[2]]()
