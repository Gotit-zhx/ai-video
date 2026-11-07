#!/usr/bin/env python3
"""One disposable schema and restricted user per DB test class, strict skip audit."""
import argparse
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
OPTIONAL = json.loads((ROOT / "scripts/ci/optional-tests.json").read_text())
# Database names are constrained by existing test annotations. Every class gets
# a freshly created schema and user, even where annotations require a fixed name.
CONTRACTS = {
    "FINANCE_QA_JDBC": "finance_qa_",
    "HEYI_KNOWLEDGE_TEST_URL": "heyi_knowledge_test",
    "HEYI_KNOWLEDGE_FILE_TEST_URL": "heyi_knowledge_file_test",
    "HEYI_VIDEO_CLAIM_TEST_URL": "heyi_video_claim_test",
    "HEYI_VIDEO_REVIEW_TEST_URL": "heyi_video_review_test",
    "HEYI_EXECUTION_TEST_URL": "heyi_execution_test",
    "HEYI_DRAPE_RECOVERY_TEST_URL": "heyi_drape_recovery_test",
    "HEYI_ARTWORK_LIBRARY_TEST_URL": "heyi_artwork_library_test",
    "HEYI_DESIGN_HTTP_DB": "heyi_design_http_test",
    "SKU_NAMING_TEST_DB": "sku_naming_test",
    "WATERMARK_QUEUE_TEST_DB": "watermark_queue_test",
    "HEYI_MAPPER_TEST_URL": "heyi_mapper_test",
    "HEYI_IDENTITY_RECOVERY_TEST_URL": "heyi_identity_recovery_test",
    "CAPTURE_RECOLLECT_TEST_DB": "capture_recollect_test",
    "MODULE_MIGRATION_URL": "module_isolation_test_ci",
    "HEYI_LOCAL_PHOTO_TEST_URL": "heyi_local_photo_test",
    "HEYI_DETAIL_STYLE_RECOVERY_TEST_URL": "heyi_detail_style_recovery_test",
    "HEYI_LAUNCH_TEST_URL": "heyi_launch_test",
    "HEYI_ARTWORK_RETRY_TEST_URL": "heyi_artwork_retry_test",
}


def inventory():
    sources = {p.stem: p.read_text() for p in (ROOT / "src/test/java").rglob("*Test.java")}
    database = {}
    for name, source in sources.items():
        current = source; seen = set()
        while True:
            env = re.search(r'@EnabledIfEnvironmentVariable\(\s*named\s*=\s*"([^"]+)"', current)
            if env:
                key = env[1]
                if key in CONTRACTS: database[name] = key
                elif name not in OPTIONAL["excludedClasses"]: raise ValueError("Unclassified conditional test: " + name)
                break
            parent = re.search(r'class\s+\w+\s+extends\s+(\w+)', current)
            if not parent or parent[1] not in sources: break
            if parent[1] in seen: raise ValueError("Test inheritance cycle")
            seen.add(parent[1]); current = sources[parent[1]]
    return sources, database


def audit(directory, expected):
    cases = []; classes = set(); skips = []; failures = []
    for file in directory.glob("TEST-*.xml"):
        root = ET.parse(file).getroot()
        classes.add(root.attrib["name"].split(".")[-1])
        for case in root.iter("testcase"):
            key = case.attrib["classname"].split(".")[-1] + "#" + case.attrib["name"].split("(")[0]
            cases.append(key)
            if case.find("skipped") is not None:
                skips.append(key)
                if key not in OPTIONAL["allowedSkips"]: failures.append("Unexpected skip: " + key)
            if case.find("error") is not None or case.find("failure") is not None: failures.append("Failed: " + key)
    if set(expected) - classes: failures.append("Missing reports: " + ",".join(sorted(set(expected) - classes)))
    if not cases: failures.append("No test results")
    result = {"classes": len(classes), "tests": len(cases), "allowedSkips": skips,
              "unexpectedSkips": sum(s not in OPTIONAL["allowedSkips"] for s in skips),
              "result": "failed" if failures else "passed", "failures": failures}
    (directory / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    if failures: raise ValueError("; ".join(failures))
    return result


def maven(classes, directory, env=None):
    directory.mkdir(parents=True, exist_ok=True)
    command = [os.environ.get("MVN", "mvn"), "-B", "-ntp", "-Dstyle.color=never",
               "-Dtest=" + ",".join(classes), "-DfailIfNoTests=true",
               "-Dci.reportsDirectory=" + str(directory), "test"]
    completed=subprocess.run(command, cwd=ROOT, env=env, timeout=600)
    result=audit(directory, classes)
    if completed.returncode: raise RuntimeError("Maven failed before completing validation")
    return result


def sql(container, text):
    subprocess.run(["docker", "exec", "-i", container, "mysql", "-uroot", "--batch"],
                   input=text.encode(), stdout=subprocess.DEVNULL, check=True)


def wait_for_mysql(container):
    # The image's initialization server answers socket pings while TCP is disabled.
    # Require the final network listener and an authenticated query before DDL.
    deadline=time.monotonic()+90
    while time.monotonic()<deadline:
        try:
            result=subprocess.run(['docker','exec',container,'mysql','--protocol=TCP',
                '-h127.0.0.1','-P3306','-uroot','--batch','--skip-column-names','-e','SELECT 1'],
                stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=5)
            if result.returncode==0 and result.stdout.strip()==b'1':return
        except subprocess.TimeoutExpired:
            pass
        time.sleep(1)
    raise RuntimeError('Disposable MySQL startup timed out')


def integration(classes, database, shard):
    runtime = json.loads((ROOT / "scripts/ci/runtime.json").read_text())
    suffix = secrets.token_hex(5); mysql = "heyi-ci-mysql-" + suffix; redis = "heyi-ci-redis-" + suffix
    def start(name, image, port, args):
        subprocess.run(["docker", "run", "-d", "--name", name, "--label", "org.heyi.owner=ci-"+suffix,
                        "--publish", "127.0.0.1::"+str(port), "--log-driver", "none", *args, image, *(["--skip-log-bin"] if port==3306 else [])], check=True, stdout=subprocess.DEVNULL)
        return subprocess.check_output(["docker", "port", name, str(port)], text=True).strip().rsplit(":", 1)[-1]
    try:
        port = start(mysql, runtime["mysqlImage"], 3306, ["-e", "MYSQL_ALLOW_EMPTY_PASSWORD=yes", "--tmpfs", "/var/lib/mysql:rw,size=3g"])
        redis_port = start(redis, runtime["redisImage"], 6379, [])
        wait_for_mysql(mysql)
        failures=[]
        for name in classes:
            user = "ci_"+secrets.token_hex(5); password = secrets.token_hex(24)
            key = database[name]; schema = CONTRACTS[key] + (secrets.token_hex(8) if key == "FINANCE_QA_JDBC" else "")
            sql(mysql, f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci; CREATE USER '{user}'@'%' IDENTIFIED BY '{password}'; GRANT ALL ON `{schema}`.* TO '{user}'@'%';")
            env = dict(os.environ)
            # Clear every DB contract before setting this class's only DB.
            for other in CONTRACTS: env.pop(other, None)
            query = "useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
            url = f"jdbc:mysql://127.0.0.1:{port}/{schema}"
            if key not in {"HEYI_DRAPE_RECOVERY_TEST_URL", "HEYI_IDENTITY_RECOVERY_TEST_URL", "HEYI_LOCAL_PHOTO_TEST_URL", "HEYI_DETAIL_STYLE_RECOVERY_TEST_URL", "HEYI_LAUNCH_TEST_URL", "HEYI_ARTWORK_RETRY_TEST_URL"}: url += "?" + query
            env[key] = url
            env.update(HEYI_TEST_DB_USER=user, HEYI_TEST_DB_PASSWORD=password, FINANCE_QA_USER=user, FINANCE_QA_PASSWORD=password,
                       HEYI_ARTWORK_LIBRARY_TEST_USER=user, HEYI_ARTWORK_LIBRARY_TEST_PASSWORD=password,
                       SKU_NAMING_TEST_USER=user, SKU_NAMING_TEST_PASSWORD=password,
                       MODULE_MIGRATION_USER=user, MODULE_MIGRATION_PASSWORD=password,
                       HEYI_DESIGN_HTTP_REDIS_PORT=redis_port)
            try:
                maven([name], ROOT / "target/ci-reports" / shard / name, env)
            except (ValueError,RuntimeError,subprocess.TimeoutExpired) as error:
                failures.append({"class":name,"error":type(error).__name__})
            finally:
                sql(mysql, f"DROP DATABASE `{schema}`; DROP USER '{user}'@'%';")
        if failures: raise RuntimeError("Required DB classes failed: "+json.dumps(failures))
    finally:
        for name in (mysql, redis):
            result=subprocess.run(["docker","inspect",name],capture_output=True,text=True)
            if result.returncode==0:
                info=json.loads(result.stdout)[0]
                if info["Config"].get("Labels",{}).get("org.heyi.owner")=="ci-"+suffix:
                    subprocess.run(["docker", "rm", "-f", "-v", info["Id"]], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,check=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser(); p.add_argument("mode", choices=("unit", "integration", "inventory")); p.add_argument("--shard", type=int, default=0); p.add_argument("--shards", type=int, default=1); p.add_argument("--classes", default="")
    a = p.parse_args(); sources, database = inventory()
    if a.mode == "inventory": print(json.dumps(database, indent=2))
    elif a.mode == "unit":
        selected = sorted(set(sources) - set(database) - set(OPTIONAL["excludedClasses"]))
        print(json.dumps(maven(selected, ROOT / "target/ci-reports/unit")))
        subprocess.run([os.environ.get("MVN","mvn"),"-B","-ntp","-DskipTests","package"],cwd=ROOT,check=True)
        java_executable=str(Path(os.environ["JAVA_HOME"])/"bin/java") if os.environ.get("JAVA_HOME") else "java"
        subprocess.run([java_executable,"scripts/ci/VerifyDependencies.java","target/ai-commerce-platform-0.1.0-SNAPSHOT.jar","scripts/ci/dependencies.sha256"],cwd=ROOT,check=True)
    else:
        selected = [name for i, name in enumerate(sorted(database)) if i % a.shards == a.shard]
        if a.classes:
            requested=set(a.classes.split(","))
            if not requested.issubset(database):raise ValueError("Unknown required DB test")
            selected=[name for name in selected if name in requested]
        if not selected: raise ValueError("Empty DB shard")
        integration(selected, database, "db-"+str(a.shard))
