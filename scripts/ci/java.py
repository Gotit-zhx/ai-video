#!/usr/bin/env python3
"""One disposable schema and restricted user per DB test class, strict skip audit."""
import argparse
import hashlib
import json
import math
import os
import re
import secrets
import shutil
import subprocess
import time
import tarfile
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
    paths=list((ROOT / "src/test/java").rglob("*.java"))
    sources = {p.stem: p.read_text() for p in paths if p.name.endswith('Test.java')}
    if len(sources)!=sum(p.name.endswith('Test.java') for p in paths):raise ValueError('Duplicate test class names require explicit mapping')
    for path in paths:
        if not path.name.endswith('Test.java') and re.search(r'@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b',path.read_text()):
            raise ValueError('Unexecutable test inventory entry: '+path.name)
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
    cases = []; classes = set(); skips = []; failures = []; duration = 0.0
    for file in directory.glob("TEST-*.xml"):
        root = ET.parse(file).getroot()
        classes.add(root.attrib["name"].split(".")[-1])
        for case in root.iter("testcase"):
            key = case.attrib["classname"].split(".")[-1] + "#" + case.attrib["name"].split("(")[0]
            cases.append(key)
            seconds=float(case.attrib.get("time",0))
            if not math.isfinite(seconds) or seconds<0:raise ValueError("Invalid test duration")
            duration+=seconds
            if case.find("skipped") is not None:
                skips.append(key)
                if key not in OPTIONAL["allowedSkips"]: failures.append("Unexpected skip: " + key)
            if case.find("error") is not None or case.find("failure") is not None: failures.append("Failed: " + key)
    if set(expected) - classes: failures.append("Missing reports: " + ",".join(sorted(set(expected) - classes)))
    if not cases: failures.append("No test results")
    result = {"classes": len(classes), "tests": len(cases), "durationSeconds":duration, "allowedSkips": skips,
              "unexpectedSkips": sum(s not in OPTIONAL["allowedSkips"] for s in skips),
              "result": "failed" if failures else "passed", "failures": failures}
    (directory / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    if failures: raise ValueError("; ".join(failures))
    return result


def maven(classes, directory, env=None):
    directory.mkdir(parents=True, exist_ok=True)
    command = [os.environ.get("MVN", "mvn"), "-B", "-ntp", "-Dstyle.color=never",
               "-Dtest=" + ",".join(classes), "-DfailIfNoTests=true",
               "-Dci.reportsDirectory=" + str(directory), "surefire:test" if os.environ.get("HEYI_COMPILED_INPUT")=="true" else "test"]
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


def balanced_shards(classes, count, durations=None):
    if type(count) is not int or count<1:raise ValueError('Invalid shard count')
    durations=durations or {};weights={}
    for name in set(classes):
        value=durations.get(name,1)
        weights[name]=float(value) if isinstance(value,(int,float)) and math.isfinite(value) and 0<value<=600 else 1.0
    shards=[[] for _ in range(count)];loads=[0.0]*count
    for name in sorted(weights,key=lambda name:(-weights[name],name)):
        index=min(range(count),key=lambda i:(loads[i],len(shards[i]),i))
        shards[index].append(name);loads[index]+=weights[name]
    return shards


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


JAR='target/ai-commerce-platform-0.1.0-SNAPSHOT.jar'

def build_recipe_digest():
    import scope
    return scope.file_digest(['Dockerfile','pom.xml','maven-settings.xml','scripts/ci/build.py','scripts/ci/java.py',
                              'scripts/ci/runtime.json','scripts/ci/VerifyDependencies.java','scripts/ci/dependencies.sha256','.dockerignore',
                              'src/main/docker/runtime.json','src/main/docker/runtime.Dockerfile','src/main/docker/runtime-packages.lock','src/main/docker/runtime-bom.sha256'])


def compile_bundle():
    import release
    subprocess.run([os.environ.get('MVN','mvn'),'-B','-ntp','-DskipTests','package'],cwd=ROOT,check=True)
    executable=str(Path(os.environ['JAVA_HOME'])/'bin/java') if os.environ.get('JAVA_HOME') else 'java'
    subprocess.run([executable,'scripts/ci/VerifyDependencies.java',JAR,'scripts/ci/dependencies.sha256'],cwd=ROOT,check=True)
    files=[ROOT/JAR]+[p for name in ['classes','test-classes'] for p in (ROOT/'target'/name).rglob('*') if p.is_file()]
    if not all((ROOT/'target'/name).is_dir() for name in ['classes','test-classes']):raise ValueError('Missing compiled classes')
    if any(p.is_symlink() for p in files):raise ValueError('Symlink in compiled output')
    output=ROOT/'target/compiled';output.mkdir(parents=True,exist_ok=True)
    metadata={'tree':release.run('git','rev-parse','HEAD^{tree}'),'buildRecipeDigest':build_recipe_digest(),
              'compiledArtifactSha256':release.sha((ROOT/JAR).read_bytes()),
              'files':{str(p.relative_to(ROOT)):release.sha(p.read_bytes()) for p in sorted(files)}}
    archive=output/'compiled.tar.gz'
    with tarfile.open(archive,'w:gz') as tar:
        for p in sorted(files):tar.add(p,arcname=str(p.relative_to(ROOT)),recursive=False)
    metadata['archiveSha256']=release.sha(archive.read_bytes())
    (output/'build.json').write_text(json.dumps(metadata,sort_keys=True,indent=2)+'\n')
    return metadata


def restore_compiled(archive,root,metadata):
    """Verify all members before writing, including no links, duplicates or traversal."""
    import release
    with tarfile.open(archive,'r:gz') as tar:
        members=tar.getmembers();names=[m.name for m in members]
        if len(set(names))!=len(names) or set(names)!=set(metadata.get('files',{})):raise ValueError('Compiled inventory differs')
        if sum(m.size for m in members)>512*1024**2:raise ValueError('Compiled output too large')
        for member in members:
            name=member.name
            if not member.isfile() or member.size>256*1024**2 or name.startswith('/') or '..' in Path(name).parts or not (name==JAR or name.startswith(('target/classes/','target/test-classes/'))):
                raise ValueError('Unsafe compiled member')
            if release.sha(tar.extractfile(member).read())!=metadata['files'][name]:raise ValueError('Compiled member digest differs')
        for member in members:
            dest=root/member.name
            if any(p.is_symlink() for p in [dest,*dest.parents]):raise ValueError('Symlink in output path')
            dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(tar.extractfile(member).read())


def load_compiled(folder):
    import release
    metadata=json.loads((folder/'build.json').read_text())
    if metadata.get('buildRecipeDigest')!=build_recipe_digest() or metadata.get('tree')!=release.run('git','rev-parse','HEAD^{tree}'):
        raise ValueError('Compiled tree or packaging recipe differs')
    archive=folder/'compiled.tar.gz'
    if release.sha(archive.read_bytes())!=metadata.get('archiveSha256'):raise ValueError('Compiled archive differs')
    restore_compiled(archive,ROOT,metadata)
    if release.sha((ROOT/JAR).read_bytes())!=metadata.get('compiledArtifactSha256'):raise ValueError('Compiled JAR differs')
    return metadata


if __name__ == "__main__":
    p = argparse.ArgumentParser(); p.add_argument("mode", choices=("unit", "integration", "inventory", "compile", "restore")); p.add_argument("--shard", type=int, default=0); p.add_argument("--shards", type=int, default=1); p.add_argument("--classes", default="")
    a = p.parse_args(); sources, database = inventory()
    if a.mode == "compile":compile_bundle()
    elif a.mode == "restore":load_compiled(ROOT/"target/compiled")
    elif a.mode == "inventory": print(json.dumps(database, indent=2))
    elif a.mode == "unit":
        selected = sorted(set(sources) - set(database) - set(OPTIONAL["excludedClasses"]))
        print(json.dumps(maven(selected, ROOT / "target/ci-reports/unit")))
    else:
        required=sorted(database)
        plan=json.loads(os.environ['CI_PLAN']) if os.environ.get('CI_PLAN') else None
        if plan:
            import scope
            required=sorted({name for suite in plan['requiredSuites'] if suite=='all-db' or suite.endswith('-db') for name in scope.catalog()[suite]['classes']})
        if not 0<=a.shard<a.shards:raise ValueError('Invalid shard index')
        selected = balanced_shards(required,a.shards,(plan or {}).get('dbDurations'))[a.shard]
        if a.classes:
            requested=set(a.classes.split(","))
            if not requested.issubset(database):raise ValueError("Unknown required DB test")
            selected=[name for name in selected if name in requested]
        if not selected:
            if plan and required:
                print(json.dumps({'plannedEmptyShard':a.shard,'requiredClasses':len(required)}));raise SystemExit(0)
            raise ValueError("Empty DB shard")
        integration(selected, database, "db-"+str(a.shard))
