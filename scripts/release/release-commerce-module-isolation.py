"""Narrow production overlay, disposable MySQL rehearsal, and schema-aware rollback."""
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
PLAN = json.loads((ROOT / "plan.json").read_text())
FRONT = "ai-commerce-frontend"
APP = "ai-commerce-app"
MYSQL = "ai-commerce-mysql"
TABLES = [
    "product_captures", "product_capture_images", "product_capture_skus",
    "product_capture_field_sources", "product_capture_videos", "product_listing_templates",
    "product_listing_logistics", "commerce_batches", "commerce_batch_items",
    "commerce_batch_artwork_jobs", "commerce_batch_executions", "commerce_batch_creative_plans",
    "commerce_batch_item_publish_options", "commerce_design_drafts",
    "commerce_capture_source_archives", "flyway_schema_history",
]
FIXTURE_TABLES = TABLES + ["organizations", "app_users", "shops"] + PLAN.get("additionalFixtureTables", [])
if PLAN.get("includeWatermarkReports", True):
    FIXTURE_TABLES += ["product_watermark_checks"]
NEW_TABLES = PLAN.get("newTables", ["commerce_source_copy_images", "commerce_source_copies", "commerce_material_library_entries"])
TEST_DB = "module_isolation_test_20261015"


def run(*args, data=None, env=None):
    return subprocess.check_output(args, input=data, stderr=subprocess.STDOUT, env=env)


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def sha(data):
    return hashlib.sha256(data).hexdigest()


def mysql(sql, database=None):
    name = database or inspect(MYSQL)["Config"]["Env"]
    if not isinstance(name, str):
        name = next(value.split("=", 1)[1] for value in name if value.startswith("MYSQL_DATABASE="))
    return run("docker", "exec", "-i", MYSQL, "sh", "-c",
               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --binary-mode -NB "$1"',
               "query", name, data=sql.encode() if isinstance(sql, str) else sql)


def dump(database, tables=None, schema=False):
    return run("docker", "exec", MYSQL, "sh", "-c",
               'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump -uroot --single-transaction '
               '--set-gtid-purged=OFF --skip-lock-tables --no-tablespaces "$@"',
               "dump", *(["--no-data"] if schema else []), database, *(tables or []))


def source_db():
    return next(value.split("=", 1)[1] for value in inspect(MYSQL)["Config"]["Env"]
                if value.startswith("MYSQL_DATABASE="))


def idle():
    queries = [
        "SELECT COUNT(*) FROM ai_video_task_items WHERE status IN ('GENERATING_MODEL','GENERATING_STORYBOARD','VIDEO_SUBMITTING')",
        "SELECT COUNT(*) FROM video_scripts WHERE status IN ('QUEUED','EXTRACTING_FRAMES','AI_ANALYZING')",
        "SELECT COUNT(*) FROM product_image_processing_jobs WHERE status IN ('PENDING','QUEUED','RUNNING','PROCESSING')",
        "SELECT COUNT(*) FROM product_publish_workflows WHERE status IN ('PENDING','QUEUED','RUNNING','PUBLISHING','PROCESSING_IMAGES')",
        "SELECT COUNT(*) FROM product_watermark_queue WHERE status IN ('QUEUED','RUNNING')",
        "SELECT COUNT(*) FROM commerce_batch_executions WHERE status IN ('QUEUED','RUNNING','PAUSING')",
    ]
    counts = [int(value) for value in mysql(";".join(queries)).decode().split()]
    assert not any(counts), "Business operations still active: " + str(counts)
    with sqlite3.connect("file:/opt/heyi/boss-agent/runtime/talent-pool.sqlite3?mode=ro", uri=True) as db:
        for table, condition in [
            ("recruitment_demands", "ai_status='running'"), ("hr_resume_pool", "ai_state='running'"),
            ("hr_sourcing_runs", "state='running'"), ("hr_sourcing_jobs", "profile_state='running'"),
            ("hr_sourcing_contacts", "status='sending'"), ("hr_talent_messages", "status='sending'"),
            ("hr_resume_commands", "status='sending'"), ("hr_interview_handoffs", "status IN ('preparing','sending')"),
            ("hr_onboarding_notices", "status IN ('preparing','sending')"),
        ]:
            assert db.execute(f"SELECT COUNT(*) FROM {table} WHERE {condition}").fetchone()[0] == 0, table


def prepare():
    baseline = ROOT / "live"
    baseline.mkdir(exist_ok=True)
    run("docker", "cp", APP + ":/app/app.jar", str(baseline / "app.jar"))
    expected = {"jar": sha((baseline / "app.jar").read_bytes())}
    for file in PLAN["frontend"]:
        live = run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)
        assert live == (ROOT / "baseline" / file).read_bytes(), "Frontend baseline differs: " + file
        (baseline / file).write_bytes(live)
        expected[file] = sha(live)
    with zipfile.ZipFile(baseline / "app.jar") as jar:
        directory = ROOT / "live-migrations"
        directory.mkdir(exist_ok=True)
        for name in jar.namelist():
            if name.startswith("BOOT-INF/classes/db/migration/") and name.endswith(".sql"):
                (directory / Path(name).name).write_bytes(jar.read(name))
        for file in PLAN["sqlMigrations"]:
            shutil.copy2(ROOT / "source/src/main/resources/db/migration" / file, directory / file)
        liveclasses = ROOT / "liveclasses"
        for prefix in PLAN["javaPrefixes"]:
            for name in jar.namelist():
                part = "BOOT-INF/classes/" + prefix
                if name == part + ".class" or name.startswith(part + "$"):
                    target = liveclasses / name.removeprefix("BOOT-INF/classes/")
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(jar.read(name))
    (ROOT / "expected.json").write_text(json.dumps(expected, indent=2))
    print("Exact production frontend and JAR baseline captured.")


def check_bytecode():
    with zipfile.ZipFile(ROOT / "live/app.jar") as jar:
        for prefix in PLAN["javaPrefixes"]:
            oldnames = {name.removeprefix("BOOT-INF/classes/") for name in jar.namelist()
                        if name == "BOOT-INF/classes/" + prefix + ".class"
                        or name.startswith("BOOT-INF/classes/" + prefix + "$")}
            if not oldnames:
                assert not (ROOT / "base/target/classes" / (prefix + ".class")).exists(), prefix
                continue
            base = ROOT / "base/target/classes"
            for name in sorted(oldnames):
                assert (base / name).exists(), "Missing baseline class: " + name
                if jar.read("BOOT-INF/classes/" + name) == (base / name).read_bytes():
                    continue
                cls = name.removesuffix(".class").replace("/", ".")
                def disassemble(location):
                    return run("docker", "run", "--rm", "-v", str(ROOT) + ":/work", "maven:3.9-eclipse-temurin-21",
                               "javap", "-c", "-p", "-classpath", "/work/" + location, cls)
                assert disassemble("liveclasses") == disassemble("base/target/classes"), "Live Java baseline differs: " + name
    (ROOT / "bytecode-verified.json").write_text(json.dumps({"verified": True}))
    print("All replaced production Java classes match the reviewed source baseline.")


def fixture():
    assert not (ROOT / "fixture-ready.json").exists()
    user = "module_isolation_test"
    password = secrets.token_hex(24)
    mysql(f"CREATE DATABASE {TEST_DB}; CREATE USER '{user}'@'%' IDENTIFIED BY '{password}'; "
          f"GRANT ALL ON {TEST_DB}.* TO '{user}'@'%';")
    mysql(dump(source_db(), schema=True), TEST_DB)
    data = dump(source_db(), FIXTURE_TABLES)
    mysql(data, TEST_DB)
    credentials = {"MODULE_MIGRATION_USER": user, "MODULE_MIGRATION_PASSWORD": password,
                   "MODULE_MIGRATION_URL": f"jdbc:mysql://{MYSQL}:3306/{TEST_DB}?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC",
                   "MODULE_MIGRATION_LOCATION": "filesystem:/release/live-migrations"}
    file = ROOT / "fixture-env.json"
    file.write_text(json.dumps(credentials))
    file.chmod(0o600)
    (ROOT / "fixture-ready.json").write_text(json.dumps({"database": TEST_DB, "rowsBytes": len(data)}))
    print("Disposable MySQL fixture created with a database-restricted test account.")


def test():
    network = next(iter(inspect(MYSQL)["NetworkSettings"]["Networks"]))
    env = dict(os.environ, **json.loads((ROOT / "fixture-env.json").read_text()))
    command = ["docker", "run", "--rm", "--cpus=2", "--memory=2g", "--network", network,
               "-v", str(ROOT) + ":/release", "-v", str(ROOT / "source") + ":/work",
               "-v", "/root/.m2:/root/.m2", "-w", "/work"]
    for key in json.loads((ROOT / "fixture-env.json").read_text()):
        command += ["-e", key]
    command += ["maven:3.9-eclipse-temurin-21", "mvn", "-B", "-s", "maven-settings.xml",
                "-Dtest=" + PLAN["tests"], "test"]
    output = run(*command, env=env)
    (ROOT / "tests.log").write_bytes(output)
    print(output.decode()[-6500:])
    (ROOT / "tests-passed.json").write_text(json.dumps({"passed": True}))


def reset_fixture():
    mysql("SET FOREIGN_KEY_CHECKS=0;" + "".join("DROP TABLE IF EXISTS " + table + ";" for table in NEW_TABLES)
          + "SET FOREIGN_KEY_CHECKS=1;", TEST_DB)
    mysql(dump(source_db(), TABLES), TEST_DB)
    (ROOT / "tests-passed.json").unlink(missing_ok=True)
    print("Only the disposable fixture was reset to the production schema baseline.")


def activate(before, name, tag):
    labels = before[name]["Config"]["Labels"]
    service = labels["com.docker.compose.service"]
    command = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
               "-p", labels["com.docker.compose.project"]]
    for file in labels["com.docker.compose.project.config_files"].split(","):
        command += ["-f", file]
    override = ROOT / ("compose." + name + ".json")
    override.write_text(json.dumps({"services": {service: {"image": tag}}}))
    env = dict(os.environ, HR_FRONTEND_IMAGE=before[FRONT]["Config"]["Image"])
    run(*command, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--pull", "never", service, env=env)


def health(name, expected=None):
    for _ in range(75):
        try:
            if name == APP:
                result = json.loads(run("docker", "exec", name, "curl", "-fsS", "--max-time", "2",
                                        "http://127.0.0.1:8080/actuator/health"))
                assert result.get("status") == "UP"
            else:
                served = run("curl", "--noproxy", "*", "-fsS", "--max-time", "4",
                             "--resolve", "hoyee.org.cn:443:127.0.0.1", "https://hoyee.org.cn/app.js")
                assert sha(served) == expected
            return
        except (subprocess.CalledProcessError, ValueError, AssertionError):
            time.sleep(1)
    raise RuntimeError(name + " failed health check")


def deploy():
    assert (ROOT / "tests-passed.json").exists() and (ROOT / "bytecode-verified.json").exists()
    assert not (ROOT / "activation.json").exists()
    assert shutil.disk_usage(ROOT).free >= 3 * 1024**3
    before = {name: inspect(name) for name in [APP, FRONT]}
    preserved = {item["Name"]: item["Id"] for item in json.loads(run("docker", "inspect", *run("docker", "ps", "-q").decode().split()))
                 if item["Name"].lstrip("/") not in before}
    expected = json.loads((ROOT / "expected.json").read_text())
    manifest = json.loads((ROOT / "manifest.json").read_text())
    assert sha(run("docker", "exec", APP, "cat", "/app/app.jar")) == expected["jar"]
    for file in PLAN["frontend"]:
        assert sha(run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)) == expected[file]
    changed = {}
    classes = ROOT / "source/target/classes"
    for prefix in PLAN["javaPrefixes"]:
        path = classes / prefix
        files = list(path.parent.glob(path.name + ".class")) + list(path.parent.glob(path.name + "$*.class"))
        assert files, prefix
        for file in files:
            changed["BOOT-INF/classes/" + str(file.relative_to(classes))] = file.read_bytes()
    for file in PLAN["sqlMigrations"]:
        changed["BOOT-INF/classes/db/migration/" + file] = (ROOT / "source/src/main/resources/db/migration" / file).read_bytes()
    def replaced(name):
        return name in changed or any(name == "BOOT-INF/classes/" + prefix + ".class"
                                      or name.startswith("BOOT-INF/classes/" + prefix + "$") for prefix in PLAN["javaPrefixes"])
    appdir = ROOT / "app-image"
    appdir.mkdir()
    with zipfile.ZipFile(ROOT / "live/app.jar") as old, zipfile.ZipFile(appdir / "app.jar", "w") as new:
        for info in old.infolist():
            if not replaced(info.filename):
                new.writestr(info, old.read(info.filename))
        for name, data in changed.items():
            new.writestr(name, data, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(ROOT / "live/app.jar") as old, zipfile.ZipFile(appdir / "app.jar") as new:
        assert all(old.read(name) == new.read(name) for name in old.namelist() if not replaced(name))
    tag = PLAN.get("tag", "module-isolation-20261015")
    assert all(character.isalnum() or character in "._-" for character in tag)
    tags = {APP: "ai-commerce-platform:" + tag, FRONT: "ai-commerce-frontend:" + tag}
    for name, directory in [(APP, appdir), (FRONT, ROOT / "frontend-image")]:
        directory.mkdir(exist_ok=True)
        lines = ["FROM " + before[name]["Image"]]
        if name == APP:
            lines += ["COPY app.jar /app/app.jar"]
        else:
            for file in PLAN["frontend"]:
                data = (ROOT / "staged" / file).read_bytes()
                assert sha(data) == manifest[file], file
                (directory / file).write_bytes(data)
                lines += ["COPY " + file + " /usr/share/nginx/html/" + file]
        (directory / "Dockerfile").write_text("\n".join(lines) + "\n")
        run("docker", "build", "-t", tags[name], str(directory))
    idle()
    backup = ROOT / "rollback"
    backup.mkdir(mode=0o700)
    # Stop only the application so the table backup and Flyway transition have one writer.
    run("docker", "stop", "--time", "60", APP)
    activated = []
    backed_up = False
    try:
        (backup / "database.sql").write_bytes(dump(source_db(), TABLES))
        (backup / "database.sql").chmod(0o600)
        backed_up = True
        for name in [APP, FRONT]:
            activated.append(name)
            activate(before, name, tags[name])
            health(name, manifest["app.js"] if name == FRONT else None)
            after = inspect(name)
            mounts = lambda value: sorted((m["Source"], m["Destination"], m["RW"]) for m in value["Mounts"])
            assert mounts(after) == mounts(before[name])
            assert sorted(after["Config"]["Env"]) == sorted(before[name]["Config"]["Env"])
        for file, digest in manifest.items():
            assert sha(run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)) == digest
        assert all(inspect(name)["Id"] == identifier for name, identifier in preserved.items())
        versions = PLAN.get("migrationVersions", ["113", "114"])
        assert all(version.isdigit() for version in versions)
        literals = ",".join("'" + version + "'" for version in versions)
        assert int(mysql("SELECT COUNT(*) FROM flyway_schema_history WHERE version IN (" + literals + ") AND success=1;").strip()) == len(versions)
        assert mysql("SELECT COUNT(*) FROM commerce_batch_items i JOIN product_captures c ON c.id=i.capture_id WHERE c.data_module<>'FISSION';").strip() == b"0"
        if "115" in versions:
            assert mysql("""SELECT COUNT(*) FROM product_capture_videos v
                JOIN commerce_source_copies c ON c.organization_id=v.organization_id AND c.capture_id=v.capture_id
                WHERE v.storage_video_id IS NULL;""").strip() == b"0"
        assert run("systemctl", "is-active", "heyi-boss-connector").strip() == b"active"
    except BaseException:
        try:
            run("docker", "stop", "--time", "30", APP)
        except subprocess.CalledProcessError:
            pass
        if backed_up:
            mysql("SET FOREIGN_KEY_CHECKS=0;" + "".join("DROP TABLE IF EXISTS " + table + ";" for table in NEW_TABLES)
                  + "SET FOREIGN_KEY_CHECKS=1;")
            mysql((backup / "database.sql").read_bytes())
        for name in [APP, FRONT]:
            activate(before, name, before[name]["Config"]["Image"])
        health(APP)
        raise
    (ROOT / "activation.json").write_text(json.dumps({
        "images": tags, "rollbackImages": {name: before[name]["Config"]["Image"] for name in before},
        "frontend": manifest, "java": {name: sha(data) for name, data in changed.items()},
        "preservedContainers": preserved, "unrelatedJarEntriesPreserved": True,
        "databaseBackup": str(backup / "database.sql"),
    }, indent=2))
    print("Deployed isolated commerce modules; services healthy; unrelated containers and JAR entries preserved.")


def cleanup():
    activation = json.loads((ROOT / "activation.json").read_text())
    assert all(inspect(name)["Config"]["Image"] == image for name, image in activation["images"].items())
    health(APP)
    if (ROOT / "fixture-ready.json").exists():
        mysql(f"DROP DATABASE IF EXISTS {TEST_DB}; DROP USER IF EXISTS 'module_isolation_test'@'%';")
    for name in ["fixture-env.json", "fixture-ready.json", "source.tar.gz", "base.tar.gz",
                 "release.tar.gz", "source-fix.tar.gz", "tests.tar.gz"]:
        (ROOT / name).unlink(missing_ok=True)
    for name in ["app-image", "frontend-image", "base", "liveclasses", "source/target"]:
        directory = ROOT / name
        if directory.exists():
            shutil.rmtree(directory)
    (ROOT / "rollback").chmod(0o700)
    (ROOT / "rollback/database.sql").chmod(0o600)
    (ROOT / "cleanup.json").write_text(json.dumps({"testDatabaseRemoved": True, "rollbackPreserved": True}))
    print("Disposable database, test account and build duplicates removed; rollback images and database backup retained.")


def verify():
    activation = json.loads((ROOT / "activation.json").read_text())
    health(APP)
    health(FRONT, activation["frontend"]["app.js"])
    assert all(inspect(name)["Id"] == identifier for name, identifier in activation["preservedContainers"].items())
    queries = {
        "migrations": "SELECT version,success FROM flyway_schema_history WHERE version IN ('113','114','115')",
        "capturesByModule": "SELECT data_module,COUNT(*) FROM product_captures GROUP BY data_module",
        "configurationsByModule": "SELECT data_module,COUNT(*) FROM product_listing_templates GROUP BY data_module",
        "privateSources": "SELECT COUNT(*) FROM commerce_source_copies",
        "frozenMaterials": "SELECT COUNT(*) FROM commerce_material_library_entries",
        "dependentProcessedImages": """SELECT COUNT(*) FROM product_capture_images i
            JOIN commerce_source_copies c ON c.capture_id=i.capture_id AND c.organization_id=i.organization_id
            WHERE i.processed_url LIKE '/api/v1/product-watermark/%'""",
    }
    if "115" in PLAN.get("migrationVersions", []):
        queries["copiedVideos"] = """SELECT COUNT(*) FROM product_capture_videos v
            JOIN commerce_source_copies c ON c.organization_id=v.organization_id AND c.capture_id=v.capture_id"""
        queries["unarchivedCopiedVideos"] = queries["copiedVideos"] + " WHERE v.storage_video_id IS NULL"
    results = {key: mysql(sql).decode().strip() for key, sql in queries.items()}
    assert results["dependentProcessedImages"] == "0"
    assert results.get("unarchivedCopiedVideos", "0") == "0"
    results["images"] = activation["images"]
    results["unrelatedContainersPreserved"] = len(activation["preservedContainers"])
    (ROOT / "verification.json").write_text(json.dumps(results, indent=2))
    print(json.dumps(results, indent=2))


try:
    {"prepare": prepare, "bytecode": check_bytecode, "fixture": fixture, "reset-fixture": reset_fixture,
     "test": test, "deploy": deploy, "cleanup": cleanup, "verify": verify}[MODE]()
except subprocess.CalledProcessError as error:
    print((error.output or b"").decode()[-12000:], file=sys.stderr)
    raise
