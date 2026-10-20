"""Additive finance BI rollout. Preserve every existing JAR entry, runtime, HR and theme."""
import importlib.util
import io
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
import zipfile

HERE = Path(__file__).resolve()
library = HERE.with_name("release-core.py")
if not library.exists():
    library = HERE.with_name("release-banana-default.py")
spec = importlib.util.spec_from_file_location("release_core", library)
core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(core)
ROOT, APP, FRONT = core.ROOT, core.APP, core.FRONT
TAG = "finance-bi-20261020"
FAMILIES = ("FinanceData", "FinanceRepository", "FinanceService", "FinanceWorkbook", "FinanceMarketplace",
            "FinanceSync", "FinanceImport", "FinanceAds", "FinanceController", "FinanceFx")
FILES = ("index.html", "app.js", "finance-workspace.js", "finance-workspace.css")
PACKAGE = "com/yingdao/aicommerce/finance/"
MIGRATION = "db/migration/V117__finance_bi.sql"

def prefix_for(family):
    return os.path.normpath(PACKAGE + family)

def replaced(name):
    return name == "BOOT-INF/classes/" + MIGRATION or any(name == "BOOT-INF/classes/" + prefix_for(family) + ".class"
               or name.startswith("BOOT-INF/classes/" + prefix_for(family) + "$") for family in FAMILIES)


def snapshot():
    ROOT.mkdir(mode=0o700, parents=True, exist_ok=True)
    if (ROOT / "snapshot.json").exists():
        baseline()
        for name, info in core.read_json("before-private.json").items():
            assert core.inspect(name)["Id"] == info["Id"], "Container changed: " + name
    before = {name: core.inspect(name) for name in (APP, FRONT)}
    core.write_json(ROOT / "before-private.json", before)
    previous = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    (ROOT / "previous.jar").write_bytes(previous)
    (ROOT / "previous.jar").chmod(0o600)
    (ROOT / "baseline-classes").mkdir(exist_ok=True)
    expected = {"classes": {}, "files": {}, "jar": core.sha(previous)}
    with zipfile.ZipFile(io.BytesIO(previous)) as jar:
        for name in jar.namelist():
            if replaced(name):
                data = jar.read(name)
                expected["classes"][name] = core.sha(data)
                target = ROOT / "baseline-classes" / name.removeprefix("BOOT-INF/classes/")
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
        assert not expected["classes"], "Finance package or migration already exists; review before replacing"
        spring = next(name for name in jar.namelist() if name.startswith("BOOT-INF/lib/spring-core-"))
        (ROOT / "spring-core.jar").write_bytes(jar.read(spring))
    for file in FILES:
        exists = subprocess.run(["docker", "exec", FRONT, "test", "-e", "/usr/share/nginx/html/" + file]).returncode == 0
        if exists:
            data = core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)
            target = ROOT / "baseline-frontend" / file
            target.parent.mkdir(exist_ok=True)
            target.write_bytes(data)
            expected["files"][file] = core.sha(data)
        else:
            expected["files"][file] = None
    core.write_json(ROOT / "snapshot.json", expected)
    with tarfile.open(ROOT / "baseline.tgz", "w:gz") as tar:
        for file in ("snapshot.json", "baseline-classes", "baseline-frontend", "spring-core.jar"):
            tar.add(ROOT / file, arcname=file)
    print("Captured affected class families and frontend files; production unchanged.")


def baseline():
    expected = core.read_json("snapshot.json")
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == expected["jar"], "Application changed since snapshot"
    for file, digest in expected["files"].items():
        if digest is None:
            core.run("docker", "exec", FRONT, "test", "!", "-e", "/usr/share/nginx/html/" + file)
        else:
            assert core.sha(core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)) == digest, file
    return expected


def pack():
    source = Path(sys.argv[3]).resolve()
    baseline_commit = sys.argv[4] if len(sys.argv) > 4 else "HEAD"
    expected = core.read_json("snapshot.json")
    assert not expected["classes"], "This release adds a new package only"
    manifest = {"sourceCommit": core.run("git", "-C", str(source), "rev-parse", "HEAD").decode().strip(),
                "classes": {}, "files": {}, "baselineAudit": "additive-package-and-migration"}
    for family in FAMILIES:
        prefix = source / "target/classes" / prefix_for(family)
        matches = list(prefix.parent.glob(prefix.name + ".class")) + list(prefix.parent.glob(prefix.name + "$*.class"))
        assert matches, family
        for file in matches:
            relative = file.relative_to(source / "target/classes")
            data = file.read_bytes()
            target = ROOT / "classes" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            manifest["classes"]["BOOT-INF/classes/" + str(relative)] = core.sha(data)
    migration = (source / "src/main/resources" / MIGRATION).read_bytes()
    target = ROOT / "classes" / MIGRATION
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(migration)
    manifest["classes"]["BOOT-INF/classes/" + MIGRATION] = core.sha(migration)
    for file in FILES:
        data = (source / "frontend" / file).read_bytes()
        old = expected["files"][file]
        if old is not None:
            committed = core.run("git", "-C", str(source), "show", baseline_commit + ":frontend/" + file)
            if file == "index.html":
                live = (ROOT / "baseline-frontend" / file).read_bytes()
                def without_hr_cache(text):
                    return re.sub(rb'[ \t]*<script src="/(hr-passive|hr-sourcing)\.js\?v=[^"]+" defer></script>',
                                  rb'<script src="/\1.js" defer></script>', text)
                assert without_hr_cache(committed) == without_hr_cache(live), "Unreviewed production HTML changes"
                # Retain the exact currently served HR asset references; this release
                # changes neither their contents nor their cache identities.
                for asset in (b"hr-passive", b"hr-sourcing"):
                    pattern = rb'<script src="/' + asset + rb'\.js\?v=[^"]+" defer></script>'
                    assert re.search(pattern, data).group() == re.search(pattern, live).group()
            else:
                assert core.sha(committed) == old, "Source baseline differs from served file: " + file
        target = ROOT / "frontend" / file
        target.parent.mkdir(exist_ok=True)
        target.write_bytes(data)
        manifest["files"][file] = core.sha(data)
    core.write_json(ROOT / "manifest.json", manifest)
    with tarfile.open(ROOT / "payload.tgz", "w:gz") as tar:
        for file in ("classes", "frontend", "manifest.json"):
            tar.add(ROOT / file, arcname=file)
    print("Packed only reviewed class families and frontend controls.")


def build():
    baseline()
    before = core.read_json("before-private.json")
    for name, info in before.items():
        assert core.inspect(name)["Id"] == info["Id"], "Container changed: " + name
    manifest = core.read_json("manifest.json")
    appdir = ROOT / "app-image"
    appdir.mkdir(exist_ok=False)
    with zipfile.ZipFile(ROOT / "previous.jar") as old, zipfile.ZipFile(appdir / "app.jar", "w") as new:
        new.comment = old.comment
        assert {name: core.sha(old.read(name)) for name in old.namelist() if replaced(name)} == core.read_json("snapshot.json")["classes"]
        for info in old.infolist():
            if not replaced(info.filename):
                new.writestr(info, old.read(info.filename))
        for name, digest in manifest["classes"].items():
            data = (ROOT / "classes" / name.removeprefix("BOOT-INF/classes/")).read_bytes()
            assert core.sha(data) == digest, name
            new.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(ROOT / "previous.jar") as old, zipfile.ZipFile(appdir / "app.jar") as new:
        unchanged = {name for name in old.namelist() if not replaced(name)}
        assert set(new.namelist()) == unchanged | set(manifest["classes"])
        assert all(old.read(name) == new.read(name) for name in unchanged)
    images = {}
    for name in (APP, FRONT):
        directory = appdir if name == APP else ROOT / "frontend-image"
        directory.mkdir(exist_ok=True)
        lines = ["FROM " + before[name]["Image"]]
        if name == APP:
            lines.append("COPY app.jar /app/app.jar")
        else:
            for file, digest in manifest["files"].items():
                assert core.sha((ROOT / "frontend" / file).read_bytes()) == digest
                shutil.copy2(ROOT / "frontend" / file, directory / file)
                lines.append("COPY " + file + " /usr/share/nginx/html/" + file)
        (directory / "Dockerfile").write_text("\n".join(lines) + "\n")
        images[name] = ("ai-commerce-platform" if name == APP else name) + ":" + TAG
        core.run("docker", "build", "-t", images[name], str(directory))
    core.write_json(ROOT / "prepared.json", {"images": images, "jarHash": core.sha((appdir / "app.jar").read_bytes()),
                                          "unrelatedJarEntriesPreserved": True})
    print("Built overlays on exact live images; all unrelated JAR entries preserved.")


def verify_images(images):
    for name, image in images.items():
        assert core.inspect(name)["Config"]["Image"] == image, name
    core.health(APP)
    for file, digest in core.read_json("manifest.json")["files"].items():
        data = core.run("curl", "--noproxy", "*", "-fsS", "--max-time", "10", "--resolve",
                        "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/" + file)
        assert core.sha(data) == digest, file
    assert core.sha(core.run("docker", "exec", APP, "cat", "/app/app.jar")) == core.read_json("prepared.json")["jarHash"]


def idle():
    core.idle()
    sql = """
        SELECT COUNT(*) FROM ai_video_generation_jobs
        WHERE status IN ('SUBMITTING','REVIEWING')
        OR (status IN ('QUEUED','RUNNING') AND
            (provider_task_id IS NULL OR provider_task_id=''
             OR next_poll_at > DATE_ADD(UTC_TIMESTAMP(), INTERVAL 120 SECOND)))
    """
    count = core.run("docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
                    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$MYSQL_DATABASE"',
                    data=sql.encode()).decode().splitlines()
    assert count == ["0"], "Video submission, review or polling lease is active; no restart"
    print("No active video request or polling lease; persisted provider jobs remain resumable.")


def backup_database():
    backup = ROOT / "database-before.sql.gz"
    if backup.exists():
        assert backup.stat().st_size > 1000
        return
    core.run("docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
             'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -NB "$MYSQL_DATABASE"',
             data=b"SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1;")
    import gzip
    command = ["docker", "exec", "ai-commerce-mysql", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction --skip-lock-tables --set-gtid-purged=OFF --no-tablespaces "$MYSQL_DATABASE"']
    with subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
        with gzip.open(backup, "wb") as output:
            shutil.copyfileobj(process.stdout, output, length=1024 * 1024)
        error = process.stderr.read()
        if process.wait() != 0:
            backup.unlink(missing_ok=True)
            raise RuntimeError("Database backup failed; deployment stopped")
    assert backup.stat().st_size > 1000
    backup.chmod(0o600)
    print("Created private database snapshot before additive migration.")

def deploy():
    assert not (ROOT / "activation.json").exists()
    baseline()
    prepared, before = core.read_json("prepared.json"), core.read_json("before-private.json")
    for name, info in before.items():
        assert core.inspect(name)["Id"] == info["Id"], "Container changed: " + name
    idle()
    backup_database()
    preserved = {item["Name"]: item["Id"] for item in json.loads(core.run("docker", "inspect", *core.run("docker", "ps", "-q").decode().split()))
                 if item["Name"].lstrip("/") not in before}
    rollback = {}
    for name, info in before.items():
        rollback[name] = ("ai-commerce-platform" if name == APP else name) + ":" + TAG + "-before"
        core.run("docker", "tag", info["Image"], rollback[name])
    try:
        core.activate(before, APP, prepared["images"][APP])
        core.health(APP)
        core.activate(before, FRONT, prepared["images"][FRONT])
        verify_images(prepared["images"])
        for name in before:
            assert core.configuration(core.inspect(name)) == core.configuration(before[name]), name + " configuration drift"
        assert all(core.inspect(name)["Id"] == identifier for name, identifier in preserved.items())
        core.write_json(ROOT / "activation.json", {"images": prepared["images"], "rollbackImages": rollback,
                "configurationPreserved": True, "preservedContainers": preserved, "databaseMigration": "117 (new finance tables only)"})
    except BaseException:
        for name in (APP, FRONT):
            core.activate(before, name, rollback[name])
        core.health(APP)
        raise
    verify()


def verify():
    activation = core.read_json("activation.json")
    verify_images(activation["images"])
    assert all(core.inspect(name)["Id"] == identifier for name, identifier in activation["preservedContainers"].items())
    core.write_json(ROOT / "verification.json", {"healthy": True, "servedHashesMatch": True,
        "unrelatedServicesPreserved": True, "databaseMigration": "117 (new finance tables only)"})
    print("Verified application health, HTTPS assets and unchanged runtime/HR services.")


def cleanup():
    verify()
    for folder in ("app-image", "frontend-image", "classes", "frontend", "baseline-classes", "baseline-frontend"):
        if (ROOT / folder).exists():
            shutil.rmtree(ROOT / folder)
    for file in ("previous.jar", "spring-core.jar", "baseline.tgz", "payload.tgz"):
        (ROOT / file).unlink(missing_ok=True)
    print("Removed duplicate build files; kept rollback images and release receipts.")


if __name__ == "__main__":
    try:
        {"snapshot": snapshot, "pack": pack, "build": build, "deploy": deploy, "verify": verify,
         "cleanup": cleanup, "idle": idle}[core.MODE]()
    except subprocess.CalledProcessError as failure:
        log = ROOT / ("failure-" + core.MODE + ".private.log")
        log.write_bytes(failure.output or b"")
        log.chmod(0o600)
        print("Command failed; private diagnostics saved to " + str(log), file=sys.stderr)
        raise SystemExit(1)
