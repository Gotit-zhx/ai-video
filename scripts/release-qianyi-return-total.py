"""Read-only Qianyi returned-goods totals over the exact live artifacts."""
import gzip
import importlib.util
import io
import json
import sys
import zipfile
from pathlib import Path

spec = importlib.util.spec_from_file_location("naming",
    "/opt/heyi/releases/product-sku-naming-20261104/release-product-sku-naming.py")
naming = importlib.util.module_from_spec(spec)
spec.loader.exec_module(naming)
core, storage = naming.core, naming.storage
ROOT, APP, FRONT = naming.ROOT, naming.APP, naming.FRONT
TAG = "qianyi-return-total-20261104"
IMAGES = {APP: "ai-commerce-platform:" + TAG, FRONT: "ai-commerce-frontend:" + TAG}
PREFIXES = ["BOOT-INF/classes/com/yingdao/aicommerce/finance/" + name for name in
            ("FinanceQianyiReturns", "FinanceQianyiReturnCostSummary")]
FILES = ("index.html", "finance-qianyi.js")


def replaced(name):
    return any(name == p + ".class" or name.startswith(p + "$") and name.endswith(".class")
               for p in PREFIXES)


def build():
    qa = core.read_json("qa-passed.json")
    assert qa["unitTests"] >= 37 and qa["databaseTests"] >= 3 and qa["uiSuites"] >= 2
    expected = core.read_json("baseline-expected.json")
    before = {n: core.inspect(n) for n in IMAGES}
    assert before[APP]["Config"]["Image"] == "ai-commerce-platform:sku-shared-cost-total-20261104"
    assert before[FRONT]["Config"]["Image"] == "ai-commerce-frontend:sku-shared-cost-total-layout-20261104"
    old = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    with zipfile.ZipFile(io.BytesIO(old)) as source:
        for name, digest in expected["classes"].items():
            assert core.sha(source.read(name)) == digest, name
    for file, digest in expected["files"].items():
        assert core.sha(core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)) == digest, file
    folder = ROOT / "app-image"
    folder.mkdir()
    entries = {"BOOT-INF/classes/" + str(p.relative_to(ROOT / "classes")): p.read_bytes()
               for p in (ROOT / "classes").rglob("*.class")}
    assert entries and all(replaced(n) for n in entries)
    assert all(p + ".class" in entries for p in PREFIXES)
    with zipfile.ZipFile(io.BytesIO(old)) as source, zipfile.ZipFile(folder / "app.jar", "w") as target:
        target.comment = source.comment
        for item in source.infolist():
            if not replaced(item.filename):
                target.writestr(item, source.read(item.filename))
        for name, value in entries.items():
            target.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(old)) as source, zipfile.ZipFile(folder / "app.jar") as target:
        assert set(target.namelist()) == {n for n in source.namelist() if not replaced(n)} | set(entries)
        assert all(source.read(n) == target.read(n) for n in source.namelist() if not replaced(n))
    (folder / "app.jar").chmod(0o644)
    frontend = ROOT / "frontend"
    hashes = {}
    for file in FILES:
        value = (frontend / file).read_bytes()
        hashes[file] = core.sha(value)
        (frontend / (file + ".gz")).write_bytes(gzip.compress(value, mtime=0))
        for name in (file, file + ".gz"):
            (frontend / name).chmod(0o644)
    for name, directory in ((APP, folder), (FRONT, frontend)):
        guard = json.loads(core.run("python3", "/usr/local/lib/heyi/compact-release-image.py",
            "--prepare", before[name]["Image"], IMAGES[name] + "-base").decode())
        core.write_json(ROOT / (name + "-image-guard.json"), guard)
        copy = "COPY app.jar /app/app.jar\n" if name == APP else (
            "COPY " + " ".join(p for f in FILES for p in (f, f + ".gz")) + " /usr/share/nginx/html/\n"
            + "RUN rm -f " + " ".join("/usr/share/nginx/html/" + f + ".br" for f in FILES) + "\n")
        (directory / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\n" + copy)
        core.run("docker", "build", "--pull=false", "-t", IMAGES[name], str(directory))
    core.write_json(ROOT / "prepared.json", {
        "sourceImages": {n: before[n]["Image"] for n in IMAGES}, "sourceJarHash": core.sha(old),
        "jarHash": core.sha((folder / "app.jar").read_bytes()), "files": hashes,
        "classes": {n: core.sha(v) for n, v in entries.items()}, "unrelatedEntriesPreserved": True})
    print("Two read-only return-cost classes and two frontend files prepared.", flush=True)


def verify():
    core.health(APP)
    receipt = core.read_json("prepared.json")
    images = dict(IMAGES)
    for n, image in images.items():
        c = core.inspect(n)
        assert c["Config"]["Image"] == image
        assert c["HostConfig"]["LogConfig"]["Type"] == "none"
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == receipt["jarHash"]
    assert naming.previous.month.priority.sql(
        "SELECT COUNT(*) FROM flyway_schema_history WHERE version='146' AND success=1").strip() == "1"
    for file, digest in receipt["files"].items():
        assert core.sha(core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)) == digest
        assert core.sha(core.run("curl", "--noproxy", "*", "-fsS", "--max-time", "10",
            "--resolve", "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/" + file)) == digest
    for endpoint in ("/api/v1/finance/qianyi/returns", "/api/v1/finance/qianyi/returns/export"):
        assert core.run("curl", "--noproxy", "*", "-sS", "--max-time", "10",
            "--resolve", "hoyee.org.cn:443:127.0.0.1", "-o", "/dev/null", "-w", "%{http_code}",
            "https://hoyee.org.cn" + endpoint).decode() == "401"
    print("Live artifacts, migration, health and access protection verified.", flush=True)


def deploy():
    assert not (ROOT / "activation.json").exists()
    before = {n: core.inspect(n) for n in IMAGES}
    prepared = core.read_json("prepared.json")
    assert all(before[n]["Image"] == prepared["sourceImages"][n] for n in IMAGES)
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["sourceJarHash"]
    for table, condition in (
        ("product_publish_workflows", "status='RUNNING' AND locked_until>UTC_TIMESTAMP(6)"),
        ("finance_authorization_sessions", "status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)"),
    ):
        assert naming.previous.month.priority.sql("SELECT COUNT(*) FROM " + table + " WHERE " + condition).strip() == "0"
    rollback = {n: before[n]["Config"]["Image"] for n in IMAGES}
    try:
        core.run("docker", "stop", "--timeout", "120", APP)
        storage.activate(before, APP, IMAGES[APP])
        core.health(APP)
        storage.activate(before, FRONT, IMAGES[FRONT])
        verify()
        assert all(core.configuration(core.inspect(n)) == core.configuration(before[n]) for n in IMAGES)
    except BaseException:
        for n in IMAGES:
            storage.activate(before, n, rollback[n])
        core.health(APP)
        raise
    core.write_json(ROOT / "activation.json", {
        "images": IMAGES, "rollbackImages": rollback, "configurationPreserved": True,
        "unrelatedJarEntriesPreserved": True, "persistentLogs": False})
    print("Read-only Qianyi returned-goods totals activated.", flush=True)


def drain_deploy():
    storage.queue.deploy = deploy
    storage.queue.drain_deploy()


if __name__ == "__main__":
    {"build": build, "drain-deploy": drain_deploy, "verify": verify}[sys.argv[2]]()
