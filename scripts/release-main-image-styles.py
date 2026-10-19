"""Exact production overlays for optional main-image styles; no model or shop calls."""
import gzip
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import sqlite3
import subprocess
import sys
import time
import zipfile

ROOT = Path(sys.argv[1]).resolve()
MODE = sys.argv[2]
APP, PI, FRONT, MYSQL = "ai-commerce-app", "heyi-pi-runtime", "ai-commerce-frontend", "ai-commerce-mysql"
TEST_DB, TEST_USER = "main_image_style_test_20261019", "main_image_style_test"
MIGRATION = "V116__product_main_image_styles.sql"
PREFIXES = [
    "product/application/CommerceDesignImageGateway", "product/application/ProductPublishWorkflowService",
    "product/application/ProductPublishWorkflowProcessor", "product/application/ProductCaptureService",
    "product/application/provider/ShopeeOfficialPublishingProvider",
    "product/application/provider/TikTokShopOfficialPublishingProvider",
    "shop/infrastructure/MarketplaceImageDownloader", "config/SecurityConfig",
    "product/application/ProductMainImageStylePolicy", "product/application/ProductMainImageStyleVision",
    "product/application/ProductMainImageTemplateService", "product/application/ProductMainImageStyleService",
    "product/application/ProductMainImageAssets", "product/web/ProductMainImageTemplateController",
    "product/web/ProductMainImageResultController",
]
FILES = {
    PI: ["src/server.js", "src/product-main-image-style.js"],
    FRONT: ["index.html", "app.js", "product-bulk-upload.js", "product-main-image-styles.js", "product-main-image-styles.css"],
}


def run(*args, data=None, env=None):
    return subprocess.check_output(args, input=data, stderr=subprocess.STDOUT, env=env)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read_json(path):
    return json.loads(path.read_text())


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2))
    path.chmod(0o600)


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def source_db():
    return next(value.split("=", 1)[1] for value in inspect(MYSQL)["Config"]["Env"] if value.startswith("MYSQL_DATABASE="))


def mysql(sql, database=None):
    return run("docker", "exec", "-i", MYSQL, "sh", "-c",
               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --binary-mode -NB "$1"',
               "query", database or source_db(), data=sql.encode() if isinstance(sql, str) else sql)


def dump_args(*extra):
    return ["docker", "exec", MYSQL, "sh", "-c",
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction '
            '--set-gtid-purged=OFF --skip-lock-tables --no-tablespaces "$@"', "dump", *extra]


def remote_path(name, file):
    return ("/app/" if name == PI else "/usr/share/nginx/html/") + file


def replaced(name):
    return name == "BOOT-INF/classes/db/migration/" + MIGRATION or any(
        name == "BOOT-INF/classes/com/yingdao/aicommerce/" + prefix + ".class"
        or name.startswith("BOOT-INF/classes/com/yingdao/aicommerce/" + prefix + "$") for prefix in PREFIXES)


def pack():
    source = Path(sys.argv[3]).resolve()
    payload = ROOT / "payload"
    payload.mkdir(exist_ok=False)
    expected = {"jar": sha((ROOT / "live/app.jar").read_bytes()), "files": {}}
    manifest = {"classes": {}, "files": {}, "sourceCommit": run("git", "-C", str(source), "rev-parse", "HEAD").decode().strip()}
    audit_live, audit_base = ROOT / "selected-live", ROOT / "selected-base"
    audit_live.mkdir()
    audit_base.mkdir()
    with zipfile.ZipFile(ROOT / "live/app.jar") as jar:
        for name in jar.namelist():
            if replaced(name):
                assert name.endswith(".class"), "V116 already exists in baseline"
                relative = name.removeprefix("BOOT-INF/classes/")
                for directory, data in [(audit_live, jar.read(name)), (audit_base, (ROOT / "baseline-classes" / relative).read_bytes())]:
                    target = directory / relative
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(data)
        core_name = next(name for name in jar.namelist() if name.startswith("BOOT-INF/lib/spring-core-"))
        core = ROOT / Path(core_name).name
        core.write_bytes(jar.read(core_name))
    java = str(Path(os.environ["JAVA_HOME"]) / "bin/java")
    print(run(java, "--class-path", str(core), str(source / "scripts/ci/ProductionClassAudit.java"),
              str(audit_live), str(audit_base), str(ROOT / "selected-baseline.sha256")).decode())
    for prefix in PREFIXES:
        path = source / "target/classes/com/yingdao/aicommerce" / prefix
        classes = list(path.parent.glob(path.name + ".class")) + list(path.parent.glob(path.name + "$*.class"))
        assert classes, prefix
        for file in classes:
            relative = str(file.relative_to(source / "target/classes"))
            target = payload / "classes" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(file, target)
            manifest["classes"][relative] = sha(file.read_bytes())
    for name, files in FILES.items():
        folder = "pi-runtime" if name == PI else "frontend"
        for file in files:
            old = ROOT / "live" / folder / file
            expected["files"][name + "/" + file] = sha(old.read_bytes()) if old.exists() else None
            target = payload / name / file
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source / folder / file, target)
            manifest["files"][name + "/" + file] = sha(target.read_bytes())
    shutil.copy2(source / "src/main/resources/db/migration" / MIGRATION, payload / MIGRATION)
    manifest["migration"] = sha((payload / MIGRATION).read_bytes())
    for file in ["scripts/release-main-image-styles.py", "scripts/ci/MainImageStyleMigrationCheck.java"]:
        shutil.copy2(source / file, payload / Path(file).name)
    write_json(payload / "expected.json", expected)
    write_json(payload / "manifest.json", manifest)
    write_json(payload / "bytecode-verified.json", {"verified": True, "allReplacedClassesMatch": True})
    print("Narrow payload prepared; all replaced baseline class families match production.")


def idle():
    conditions = {
        "ai_video_task_items": "status IN ('GENERATING_MODEL','GENERATING_STORYBOARD','VIDEO_SUBMITTING')",
        "video_scripts": "status IN ('QUEUED','EXTRACTING_FRAMES','AI_ANALYZING')",
        "product_image_processing_jobs": "status IN ('PENDING','QUEUED','RUNNING','PROCESSING')",
        "product_publish_workflows": "status IN ('PENDING','QUEUED','RUNNING','PUBLISHING','PROCESSING_IMAGES')",
        "product_watermark_queue": "status IN ('QUEUED','RUNNING')",
        "commerce_batch_executions": "status IN ('QUEUED','RUNNING','PAUSING')",
    }
    counts = [int(value) for value in mysql(";".join("SELECT COUNT(*) FROM " + table + " WHERE " + condition
                                                   for table, condition in conditions.items())).decode().split()]
    assert not any(counts), "Active commerce/video operations; release deferred"
    with sqlite3.connect("file:/opt/heyi/boss-agent/runtime/talent-pool.sqlite3?mode=ro", uri=True) as db:
        for table, condition in [
            ("recruitment_demands", "ai_status='running'"), ("hr_resume_pool", "ai_state='running'"),
            ("hr_sourcing_runs", "state='running'"), ("hr_sourcing_jobs", "profile_state='running'"),
            ("hr_sourcing_contacts", "status='sending'"), ("hr_talent_messages", "status='sending'"),
            ("hr_resume_commands", "status='sending'"), ("hr_interview_handoffs", "status IN ('preparing','sending')"),
            ("hr_onboarding_notices", "status IN ('preparing','sending')"),
        ]:
            assert db.execute("SELECT COUNT(*) FROM " + table + " WHERE " + condition).fetchone()[0] == 0, table


def baseline():
    expected = read_json(ROOT / "expected.json")
    assert sha(run("docker", "exec", APP, "cat", "/app/app.jar")) == expected["jar"], "Production JAR changed"
    for key, digest in expected["files"].items():
        name, file = key.split("/", 1)
        if digest is None:
            run("docker", "exec", name, "test", "!", "-e", remote_path(name, file))
        else:
            assert sha(run("docker", "exec", name, "cat", remote_path(name, file))) == digest, key
    assert mysql("SELECT version FROM flyway_schema_history WHERE success=1 ORDER BY installed_rank DESC LIMIT 1").strip() == b"115"
    return expected


def build():
    assert (ROOT / "bytecode-verified.json").exists()
    baseline()
    manifest = read_json(ROOT / "manifest.json")
    directory = ROOT / "app-image"
    directory.mkdir(exist_ok=False)
    live = ROOT / "live/app.jar"
    assert sha(live.read_bytes()) == read_json(ROOT / "expected.json")["jar"]
    changes = {}
    for name, digest in manifest["classes"].items():
        data = (ROOT / "classes" / name).read_bytes()
        assert sha(data) == digest, name
        changes["BOOT-INF/classes/" + name] = data
    migration = (ROOT / MIGRATION).read_bytes()
    assert sha(migration) == manifest["migration"]
    changes["BOOT-INF/classes/db/migration/" + MIGRATION] = migration
    with zipfile.ZipFile(live) as old, zipfile.ZipFile(directory / "app.jar", "w") as new:
        new.comment = old.comment
        for info in old.infolist():
            if not replaced(info.filename):
                new.writestr(info, old.read(info.filename))
        for name, data in changes.items():
            new.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(live) as old, zipfile.ZipFile(directory / "app.jar") as new:
        untouched = {name for name in old.namelist() if not replaced(name)}
        assert set(new.namelist()) == untouched | set(changes)
        assert all(old.read(name) == new.read(name) for name in untouched)
    write_json(ROOT / "jar-verified.json", {"unrelatedEntriesPreserved": True, "sha256": sha((directory / "app.jar").read_bytes())})
    print("Candidate JAR built; every unrelated class, library and migration is byte-for-byte preserved.")


def rehearse():
    assert not (ROOT / "rehearsal.json").exists()
    baseline()
    assert mysql("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name='" + TEST_DB + "'").strip() == b"0"
    password = secrets.token_hex(24)
    env = dict(os.environ, STYLE_MIGRATION_USER=TEST_USER, STYLE_MIGRATION_PASSWORD=password,
               STYLE_MIGRATION_URL=f"jdbc:mysql://{MYSQL}:3306/{TEST_DB}?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC")
    created = False
    try:
        mysql(f"CREATE DATABASE {TEST_DB};")
        created = True
        mysql(f"CREATE USER '{TEST_USER}'@'%' IDENTIFIED BY '{password}'; GRANT ALL ON {TEST_DB}.* TO '{TEST_USER}'@'%';")
        mysql(run(*dump_args("--no-data", source_db())), TEST_DB)
        mysql(run(*dump_args(source_db(), "flyway_schema_history")), TEST_DB)
        extracted = ROOT / "rehearsal"
        with zipfile.ZipFile(ROOT / "app-image/app.jar") as jar:
            for name in jar.namelist():
                if name.startswith(("BOOT-INF/classes/", "BOOT-INF/lib/")) and not name.endswith("/"):
                    target = extracted / name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(jar.read(name))
        network = next(iter(inspect(MYSQL)["NetworkSettings"]["Networks"]))
        command = ["docker", "run", "--rm", "--cpus=2", "--memory=1g", "--network", network, "-v", str(ROOT) + ":/release:ro"]
        for name in ["STYLE_MIGRATION_URL", "STYLE_MIGRATION_USER", "STYLE_MIGRATION_PASSWORD"]:
            command += ["-e", name]
        output = run(*command, "maven:3.9-eclipse-temurin-21", "java", "--class-path",
                     "/release/rehearsal/BOOT-INF/classes:/release/rehearsal/BOOT-INF/lib/*",
                     "/release/MainImageStyleMigrationCheck.java", env=env)
        (ROOT / "rehearsal.log").write_bytes(output)
        write_json(ROOT / "rehearsal.json", {"passed": True, "migration": "116", "customerDataCopied": False})
        print("Isolated MySQL rehearsal passed: V116, repeat validation, constraints, idempotency, JSON and cascade.")
    finally:
        if created:
            mysql(f"DROP DATABASE IF EXISTS {TEST_DB}; DROP USER IF EXISTS '{TEST_USER}'@'%';")
        shutil.rmtree(ROOT / "rehearsal", ignore_errors=True)


def activate(before, name, tag):
    labels = before[name]["Config"]["Labels"]
    service = labels["com.docker.compose.service"]
    command = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
               "-p", labels["com.docker.compose.project"]]
    for file in labels["com.docker.compose.project.config_files"].split(","):
        command += ["-f", file]
    override = ROOT / ("compose." + name + ".json")
    write_json(override, {"services": {service: {"image": tag}}})
    env = dict(os.environ, HR_FRONTEND_IMAGE=before[FRONT]["Config"]["Image"])
    run(*command, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--pull", "never", service, env=env)


def health(name, frontend_hash=None):
    for _ in range(90):
        try:
            if name == APP:
                response = run("docker", "exec", name, "curl", "-fsS", "--max-time", "2", "http://127.0.0.1:8080/actuator/health")
                assert json.loads(response).get("status") == "UP"
            elif name == PI:
                response = run("docker", "exec", name, "node", "-e",
                               "fetch('http://127.0.0.1:8090/health').then(r=>r.json()).then(r=>process.stdout.write(JSON.stringify(r)))")
                assert json.loads(response).get("status") == "UP"
            else:
                response = run("curl", "--noproxy", "*", "-fsS", "--max-time", "4", "--resolve",
                               "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/app.js")
                assert sha(response) == frontend_hash
            return
        except (subprocess.CalledProcessError, ValueError, AssertionError):
            time.sleep(1)
    raise RuntimeError(name + " health check failed")


def configuration(info):
    return (sorted(info["Config"]["Env"]), sorted((m["Source"], m["Destination"], m["RW"]) for m in info["Mounts"]),
            sorted(info["HostConfig"]["PortBindings"].items()) if info["HostConfig"]["PortBindings"] else [],
            sorted(info["NetworkSettings"]["Networks"]))


def deploy():
    assert read_json(ROOT / "rehearsal.json")["passed"] and read_json(ROOT / "jar-verified.json")["unrelatedEntriesPreserved"]
    assert not (ROOT / "activation.json").exists()
    assert shutil.disk_usage(ROOT).free >= 2 * 1024**3
    expected = baseline()
    idle()
    manifest = read_json(ROOT / "manifest.json")
    before = {name: inspect(name) for name in [APP, PI, FRONT]}
    preserved = {item["Name"]: item["Id"] for item in json.loads(run("docker", "inspect", *run("docker", "ps", "-q").decode().split()))
                 if item["Name"].lstrip("/") not in before}
    write_json(ROOT / "before-private.json", before)
    tags = {APP: "ai-commerce-platform:main-image-styles-20261019", PI: "heyi-pi-runtime:main-image-styles-20261019",
            FRONT: "ai-commerce-frontend:main-image-styles-20261019"}
    for name in before:
        directory = ROOT / ("app-image" if name == APP else name + "-image")
        directory.mkdir(exist_ok=True)
        lines = ["FROM " + before[name]["Image"]]
        if name == APP:
            assert sha((directory / "app.jar").read_bytes()) == read_json(ROOT / "jar-verified.json")["sha256"]
            lines += ["COPY app.jar /app/app.jar"]
        else:
            for file in FILES[name]:
                data = (ROOT / name / file).read_bytes()
                assert sha(data) == manifest["files"][name + "/" + file]
                target = directory / file
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                lines += ["COPY " + file + " " + remote_path(name, file)]
        (directory / "Dockerfile").write_text("\n".join(lines) + "\n")
        run("docker", "build", "-t", tags[name], str(directory))
    baseline()
    idle()
    backup = ROOT / "rollback"
    backup.mkdir(mode=0o700, exist_ok=False)
    run("docker", "stop", "--time", "60", APP)
    activated = []
    try:
        with (backup / "database.sql.gz").open("wb") as output, gzip.GzipFile(fileobj=output, mode="wb") as compressed:
            process = subprocess.Popen(dump_args(source_db()), stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            shutil.copyfileobj(process.stdout, compressed)
            errors = process.stderr.read()
            assert process.wait() == 0, "Database backup failed; production remains unchanged"
        (backup / "database.sql.gz").chmod(0o600)
        for name in [PI, APP, FRONT]:
            activated.append(name)
            activate(before, name, tags[name])
            health(name, manifest["files"][FRONT + "/app.js"] if name == FRONT else None)
            assert configuration(inspect(name)) == configuration(before[name]), name + " configuration drift"
        for key, digest in manifest["files"].items():
            name, file = key.split("/", 1)
            assert sha(run("docker", "exec", name, "cat", remote_path(name, file))) == digest
        assert all(inspect(name)["Id"] == identifier for name, identifier in preserved.items())
        assert mysql("SELECT COUNT(*) FROM flyway_schema_history WHERE version='116' AND success=1").strip() == b"1"
        assert run("systemctl", "is-active", "heyi-boss-connector").strip() == b"active"
    except BaseException:
        # V116 only adds tables and broadens a CHECK. Preserve any data instead of destructive DB rollback.
        failures = []
        for name in [APP, PI, FRONT]:
            try:
                activate(before, name, before[name]["Config"]["Image"])
                health(name, expected["files"][FRONT + "/app.js"] if name == FRONT else None)
            except Exception:
                failures.append(name)
        write_json(ROOT / "rollback-status.json", {"restored": not failures, "needsAttention": failures, "schemaRetained": True})
        raise
    write_json(ROOT / "activation.json", {
        "images": tags, "rollbackImages": {name: info["Config"]["Image"] for name, info in before.items()},
        "manifest": manifest, "preservedContainers": preserved, "configurationPreserved": True,
        "unrelatedJarEntriesPreserved": True, "databaseBackup": str(backup / "database.sql.gz"),
    })
    print("Production deployed; app, runtime and frontend healthy; environment, mounts and unrelated containers preserved.")


def verify():
    activation = read_json(ROOT / "activation.json")
    for name, image in activation["images"].items():
        assert inspect(name)["Config"]["Image"] == image
        health(name, activation["manifest"]["files"][FRONT + "/app.js"] if name == FRONT else None)
    assert all(inspect(name)["Id"] == identifier for name, identifier in activation["preservedContainers"].items())
    assert mysql("SELECT COUNT(*) FROM flyway_schema_history WHERE version='116' AND success=1").strip() == b"1"
    for file in FILES[FRONT]:
        data = run("curl", "--noproxy", "*", "-fsS", "--max-time", "6", "--resolve",
                   "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/" + file)
        assert sha(data) == activation["manifest"]["files"][FRONT + "/" + file], file
    for path in ["/api/v1/product-main-image-templates", "/api/v1/product-main-image-results/release-check"]:
        status = run("curl", "--noproxy", "*", "-sS", "-o", "/dev/null", "-w", "%{http_code}", "--resolve",
                     "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn" + path).decode()
        assert status == "401", "Unauthenticated style endpoint must reject access"
    write_json(ROOT / "verification.json", {"healthy": True, "migration": "116", "servedHashesMatch": True,
                                          "unauthenticatedAccessRejected": True, "paidCallsMade": False})
    print("Verified HTTPS assets, V116, service health, access protection and preserved services; no paid calls made.")


def cleanup():
    verify()
    for name in ["app-image", PI + "-image", FRONT + "-image", "classes"]:
        shutil.rmtree(ROOT / name, ignore_errors=True)
    print("Removed duplicate build files; rollback images and private database backup retained.")


try:
    {"pack": pack, "build": build, "rehearse": rehearse, "deploy": deploy, "verify": verify, "cleanup": cleanup}[MODE]()
except subprocess.CalledProcessError as error:
    # Docker/Compose output may contain configuration. Keep diagnostics private on the deployment host.
    log = ROOT / ("failure-" + MODE + ".log")
    log.write_bytes(error.output or b"")
    log.chmod(0o600)
    print("Command failed; private diagnostics: " + str(log), file=sys.stderr)
    raise SystemExit(1)
