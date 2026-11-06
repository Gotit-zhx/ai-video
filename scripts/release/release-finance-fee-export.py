"""Scoped asynchronous fee-matrix export release over the verified live artifacts."""
import gzip
import importlib.util
import io
import json
import sys
import zipfile

spec = importlib.util.spec_from_file_location("previous",
    "/opt/heyi/releases/qianyi-reverse-phrases-20261104/release-qianyi-reverse-phrases.py")
previous = importlib.util.module_from_spec(spec)
spec.loader.exec_module(previous)
core, storage = previous.core, previous.storage
ROOT, APP, FRONT = previous.ROOT, previous.APP, previous.FRONT
TAG = "finance-fee-export-20261104"
IMAGES = {APP: "ai-commerce-platform:" + TAG, FRONT: "ai-commerce-frontend:" + TAG}
PREFIXES = ["BOOT-INF/classes/com/yingdao/aicommerce/finance/" + name for name in
            ("FinanceService", "FinanceWorkbook", "FinanceFeeMatrix", "FinanceFeeExportJobs", "FinanceFeeExportController")]
FILES = ("index.html", "finance-workspace.js", "finance-workspace.css", "finance-fee-exports.js")


def replaced(name):
    return any(name == prefix + ".class" or name.startswith(prefix + "$") and name.endswith(".class")
               for prefix in PREFIXES)


def build():
    qa = core.read_json("qa-passed.json")
    assert qa["unitTests"] >= 77 and qa["uiSuites"] >= 3
    expected = core.read_json("baseline-expected.json")
    before = {name: core.inspect(name) for name in IMAGES}
    assert before[APP]["Config"]["Image"] == "ai-commerce-platform:qianyi-reverse-phrases-20261104"
    assert before[FRONT]["Config"]["Image"] == "ai-commerce-frontend:qianyi-return-total-20261104"
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
    assert entries and all(replaced(name) for name in entries)
    assert all(prefix + ".class" in entries for prefix in PREFIXES)
    with zipfile.ZipFile(io.BytesIO(old)) as source, zipfile.ZipFile(folder / "app.jar", "w") as target:
        target.comment = source.comment
        for item in source.infolist():
            if not replaced(item.filename):
                target.writestr(item, source.read(item.filename))
        for name, value in entries.items():
            target.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(old)) as source, zipfile.ZipFile(folder / "app.jar") as target:
        assert set(target.namelist()) == {name for name in source.namelist() if not replaced(name)} | set(entries)
        assert all(source.read(name) == target.read(name) for name in source.namelist() if not replaced(name))
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
        "sourceImages": {name: before[name]["Image"] for name in IMAGES}, "sourceJarHash": core.sha(old),
        "jarHash": core.sha((folder / "app.jar").read_bytes()), "files": hashes,
        "classes": {name: core.sha(value) for name, value in entries.items()}, "unrelatedEntriesPreserved": True})
    print("Five class families and four frontend files prepared; schema unchanged.", flush=True)


def verify():
    core.health(APP)
    receipt = core.read_json("prepared.json")
    images = dict(IMAGES)
    if (ROOT / "frontend-layout.json").exists():
        layout = core.read_json("frontend-layout.json")
        images[FRONT] = layout["image"]
        receipt["files"].update(layout["files"])
    for name, image in images.items():
        container = core.inspect(name)
        assert container["Config"]["Image"] == image
        assert container["HostConfig"]["LogConfig"]["Type"] == "none"
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == receipt["jarHash"]
    for file, digest in receipt["files"].items():
        assert core.sha(core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)) == digest
        assert core.sha(core.run("curl", "--noproxy", "*", "-fsS", "--max-time", "10",
            "--resolve", "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/" + file)) == digest
    for path, code in (("/", "200"), ("/api/v1/finance/fee-exports", "401"),
                       ("/api/v1/finance/fee-exports/unknown/download", "401")):
        assert core.run("curl", "--noproxy", "*", "-sS", "--max-time", "10",
            "--resolve", "hoyee.org.cn:443:127.0.0.1", "-o", "/dev/null", "-w", "%{http_code}",
            "https://hoyee.org.cn" + path).decode() == code
    print("Exact artifacts, service health and protected export/download routes verified.", flush=True)

def frontend_layout():
    assert (ROOT / "activation.json").exists()
    assert not (ROOT / "frontend-layout.json").exists()
    before = {name: core.inspect(name) for name in IMAGES}
    assert all(before[name]["Config"]["Image"] == IMAGES[name] for name in IMAGES)
    prepared = core.read_json("prepared.json")
    files = ("index.html", "finance-fee-exports.js")
    for file in files:
        assert core.sha(core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)) == prepared["files"][file]
    folder = ROOT / "layout-input"
    hashes = {}
    for file in files:
        value = (folder / file).read_bytes()
        hashes[file] = core.sha(value)
        (folder / (file + ".gz")).write_bytes(gzip.compress(value, mtime=0))
        for name in (file, file + ".gz"):
            (folder / name).chmod(0o644)
    image = "ai-commerce-frontend:finance-fee-export-layout-20261104"
    guard = json.loads(core.run("python3", "/usr/local/lib/heyi/compact-release-image.py",
        "--prepare", before[FRONT]["Image"], image + "-base").decode())
    core.write_json(ROOT / "frontend-layout-guard.json", guard)
    (folder / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY "
        + " ".join(p for f in files for p in (f, f + ".gz")) + " /usr/share/nginx/html/\nRUN rm -f "
        + " ".join("/usr/share/nginx/html/" + f + ".br" for f in files) + "\n")
    core.run("docker", "build", "--pull=false", "-t", image, str(folder))
    receipt = {"image": image, "files": hashes, "backendRestarted": False}
    try:
        storage.activate(before, FRONT, image)
        core.write_json(ROOT / "frontend-layout.json", receipt)
        verify()
        assert core.inspect(APP)["Id"] == before[APP]["Id"]
        assert core.configuration(core.inspect(FRONT)) == core.configuration(before[FRONT])
    except BaseException:
        (ROOT / "frontend-layout.json").unlink(missing_ok=True)
        storage.activate(before, FRONT, IMAGES[FRONT])
        raise
    print("Dropdown kept inside workspace boundaries; backend unchanged.", flush=True)


def deploy():
    assert not (ROOT / "activation.json").exists()
    before = {name: core.inspect(name) for name in IMAGES}
    prepared = core.read_json("prepared.json")
    assert all(before[name]["Image"] == prepared["sourceImages"][name] for name in IMAGES)
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == prepared["sourceJarHash"]
    for table, condition in (
        ("product_publish_workflows", "status IN ('PENDING','RUNNING')"),
        ("finance_authorization_sessions", "status='PROCESSING' AND expires_at>UTC_TIMESTAMP(6)"),
    ):
        assert previous.sql("SELECT COUNT(*) FROM " + table + " WHERE " + condition).strip() == "0"
    rollback = {name: before[name]["Config"]["Image"] for name in IMAGES}
    try:
        core.run("docker", "stop", "--timeout", "120", APP)
        storage.activate(before, APP, IMAGES[APP])
        core.health(APP)
        storage.activate(before, FRONT, IMAGES[FRONT])
        verify()
        assert all(core.configuration(core.inspect(name)) == core.configuration(before[name]) for name in IMAGES)
    except BaseException:
        for name in IMAGES:
            storage.activate(before, name, rollback[name])
        core.health(APP)
        raise
    core.write_json(ROOT / "activation.json", {
        "images": IMAGES, "rollbackImages": rollback, "configurationPreserved": True,
        "unrelatedEntriesPreserved": True, "schemaChanged": False, "persistentLogs": False})
    print("Asynchronous shop-column fee exports activated.", flush=True)


def drain_deploy():
    storage.queue.deploy = deploy
    storage.queue.drain_deploy()


if __name__ == "__main__":
    {"build": build, "drain-deploy": drain_deploy, "verify": verify, "frontend-layout": frontend_layout}[sys.argv[2]]()
