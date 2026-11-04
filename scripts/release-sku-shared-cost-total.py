"""Exact live overlay for shared pattern SKU codes and filtered cost totals."""
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
TAG = "sku-shared-cost-total-20261104"
IMAGES = {APP: "ai-commerce-platform:" + TAG, FRONT: "ai-commerce-frontend:" + TAG}
PREFIXES = ["BOOT-INF/classes/com/yingdao/aicommerce/" + part for part in (
    "product/application/ProductSkuNaming", "product/application/ProductSkuNamingService",
    "finance/FinanceCostReport", "product/application/ProductPublicationSkuNaming",
    "product/application/ProductCaptureService", "product/application/ProductPublishWorkflowService",
    "product/domain/ProductCaptureSku", "product/application/provider/TikTokShopOfficialPublishingProvider",
    "product/application/provider/ShopeeOfficialPublishingProvider")]
FILES = ("index.html", "finance-costs.js", "finance-costs.css", "app.js", "product-bulk-upload.js")
MIGRATION = "BOOT-INF/classes/db/migration/V146__captured_pattern_shared_sku_codes.sql"


def replaced(name):
    return any(name == p + ".class" or name.startswith(p + "$") and name.endswith(".class")
               for p in PREFIXES)


def build():
    qa = core.read_json("qa-passed.json")
    assert qa["unitTests"] >= 85 and qa["databaseTests"] == 5 and qa["uiSuites"] >= 3
    assert qa["publicationStyleTests"] >= 7
    expected = core.read_json("baseline-expected.json")
    before = {n: core.inspect(n) for n in IMAGES}
    assert before[APP]["Config"]["Image"] == "ai-commerce-platform:product-sku-pattern-option-20261104"
    assert before[FRONT]["Config"]["Image"] == "ai-commerce-frontend:finance-qianyi-receipt-20261103"
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
    entries[MIGRATION] = (ROOT / "V146__captured_pattern_shared_sku_codes.sql").read_bytes()
    with zipfile.ZipFile(io.BytesIO(old)) as source, zipfile.ZipFile(folder / "app.jar", "w") as target:
        assert MIGRATION not in source.namelist()
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
    print("Nine class families, additive SKU catalog and five frontend files prepared.", flush=True)


def verify():
    core.health(APP)
    receipt = core.read_json("prepared.json")
    images = dict(IMAGES)
    if (ROOT / "frontend-layout.json").exists():
        layout = core.read_json("frontend-layout.json")
        images[FRONT] = layout["image"]
        receipt["files"].update(layout["files"])
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
    for endpoint in ("/api/v1/product-captures", "/api/v1/finance/workspace/costs/orders"):
        assert core.run("curl", "--noproxy", "*", "-sS", "--max-time", "10",
            "--resolve", "hoyee.org.cn:443:127.0.0.1", "-o", "/dev/null", "-w", "%{http_code}",
            "https://hoyee.org.cn" + endpoint).decode() == "401"
    print("Live artifacts, migration, health and access protection verified.", flush=True)


def layout():
    """Contain the existing wide cost table so the new total stays in the viewport."""
    assert (ROOT / "activation.json").exists() and not (ROOT / "frontend-layout.json").exists()
    before = {n: core.inspect(n) for n in IMAGES}
    assert before[FRONT]["Config"]["Image"] == IMAGES[FRONT]
    receipt = core.read_json("prepared.json")
    for file, digest in receipt["files"].items():
        assert core.sha(core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)) == digest
    folder = ROOT / "layout-front"
    files = ("index.html", "finance-costs.css")
    hashes = {}
    for file in files:
        value = (folder / file).read_bytes()
        hashes[file] = core.sha(value)
        (folder / (file + ".gz")).write_bytes(gzip.compress(value, mtime=0))
        (folder / file).chmod(0o644)
        (folder / (file + ".gz")).chmod(0o644)
    image = "ai-commerce-frontend:sku-shared-cost-total-layout-20261104"
    guard = json.loads(core.run("python3", "/usr/local/lib/heyi/compact-release-image.py",
        "--prepare", before[FRONT]["Image"], image + "-base").decode())
    core.write_json(ROOT / "frontend-layout-image-guard.json", guard)
    (folder / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY "
        + " ".join(p for f in files for p in (f, f + ".gz")) + " /usr/share/nginx/html/\n"
        + "RUN rm -f " + " ".join("/usr/share/nginx/html/" + f + ".br" for f in files) + "\n")
    core.run("docker", "build", "--pull=false", "-t", image, str(folder))
    try:
        storage.activate(before, FRONT, image)
        core.write_json(ROOT / "frontend-layout.json", {"image": image, "files": hashes,
            "rollbackImage": IMAGES[FRONT], "configurationPreserved": True, "backendRestarted": False})
        verify()
        assert core.configuration(core.inspect(FRONT)) == core.configuration(before[FRONT])
        assert core.inspect(APP)["Id"] == before[APP]["Id"]
    except BaseException:
        (ROOT / "frontend-layout.json").unlink(missing_ok=True)
        storage.activate(before, FRONT, IMAGES[FRONT])
        raise
    print("Cost layout width corrected; backend and synchronization remained running.", flush=True)


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
    print("Shared pattern codes and filtered cost totals activated.", flush=True)


def drain_deploy():
    storage.queue.deploy = deploy
    storage.queue.drain_deploy()


if __name__ == "__main__":
    {"build": build, "drain-deploy": drain_deploy, "verify": verify, "layout": layout}[sys.argv[2]]()
