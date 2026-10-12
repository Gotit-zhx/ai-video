"""Activate reviewed HR code and verified OSS installer redirects atomically.

No Java, browser or user-session restart. Rollback data stays small: code,
configuration and an online SQLite snapshot, never duplicated installers.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import urllib.parse

root = Path(sys.argv[1]).resolve()
expected = json.loads((root / "expected-baseline.json").read_text())
manifest = json.loads((root / "oss-installers.json").read_text())
build = json.loads((root / "release-build.json").read_text())
assets = ("index.html", "hr-bi.js", "hr-pool.js", "hr-workspace.js", "hr-sourcing.js", "hr-passive.js",
          "hr-onboarding.js", "hr-workspace.css")
modules = ("passive_pool.py", "hr_sourcing.py", "resume_storage.py", "hr_bi.py")
container = "ai-commerce-frontend"
bin_dir = Path("/opt/heyi/boss-agent/bin")
downloads = Path("/opt/heyi/shared/hr-desktop-downloads")
backup = root / "rollback"
image_dir = root / "frontend-image"
backup.mkdir(exist_ok=True, mode=0o700)
image_dir.mkdir(exist_ok=True)


def run(*args, **kwargs):
    return subprocess.check_output(args, stderr=subprocess.STDOUT, **kwargs)


def inspect(name):
    return json.loads(run("docker", "inspect", name))[0]


def sha(data):
    return hashlib.sha256(data).hexdigest()


before = inspect(container)
preserved = {"ai-commerce-app": inspect("ai-commerce-app")["Id"]}
pi_before = inspect("heyi-pi-runtime")
pi_dir = root / "pi-image"
pi_dir.mkdir(exist_ok=True)
pi_source = run("docker", "exec", "heyi-pi-runtime", "cat", "/app/src/recruitment-analysis.js")
assert sha(pi_source) == expected["recruitment-analysis.js"], "Pi baseline changed"
(backup / "recruitment-analysis.js").write_bytes(pi_source)
shutil.copy2(root / "staged/pi/recruitment-analysis.js", pi_dir / "recruitment-analysis.js")
pi_image = "heyi-pi-runtime:hr-structured-20261012"
(pi_dir / "Dockerfile").write_text(
    "FROM " + pi_before["Image"] + "\nCOPY --chown=node:node recruitment-analysis.js /app/src/recruitment-analysis.js\n")
run("docker", "build", "-t", pi_image, str(pi_dir))
run("docker", "run", "--rm", "-v", str(root / "staged/pi-test") + ":/app/test:ro",
    pi_image, "node", "--test", "test/recruitment-analysis.test.mjs")
checksums = {}
for platform, stable, name in [
    ("mac", "Heyi-Recruiter-mac-arm64.dmg", f"Heyi-Recruiter-{build['version']}-mac-arm64.dmg"),
    ("windows", "Heyi-Recruiter-windows-x64-Setup.exe", f"Heyi-Recruiter-{build['version']}-windows-x64-Setup.exe"),
]:
    artifact = root / "artifacts" / name
    record = manifest[str(artifact)]
    assert record["verified"] and record["size"] == artifact.stat().st_size > 10_000_000
    assert record["sha256"] == sha(artifact.read_bytes())
    proof = json.loads((root / (platform + "-verified.json")).read_text())
    assert proof["artifacts"][name]["sha256"] == record["sha256"]
    assert proof["commit"] == build["commit"]
    url = urllib.parse.urlsplit(record["url"])
    assert url.scheme == "https" and url.netloc == "oss.cloud.dayan.cloud" and not url.query
    assert "/desktop-installers/" + record["sha256"] + "/" in url.path
    checksums[stable] = {key: record[key] for key in ("size", "sha256", "url")}

for name in (*assets, "nginx.conf"):
    path = "/etc/nginx/conf.d/default.conf" if name == "nginx.conf" else "/usr/share/nginx/html/" + name
    data = run("docker", "exec", container, "cat", path)
    assert sha(data) == expected[name], "Frontend baseline changed: " + name
    (backup / name).write_bytes(data)
    (image_dir / name).write_bytes(data if name == "nginx.conf" else (root / "staged/frontend" / name).read_bytes())
for name in modules:
    assert sha((bin_dir / name).read_bytes()) == expected[name], "HR baseline changed: " + name
    shutil.copy2(bin_dir / name, backup / name)
    run("/opt/heyi/boss-agent/venv/bin/python", "-m", "py_compile", str(root / "staged/python" / name))
nginx = (backup / "nginx.conf").read_text()
redirects = "\n".join(
    "    location = /downloads/" + name + " {\n"
    '        add_header Cache-Control "no-store" always;\n'
    "        return 302 " + record["url"] + ";\n    }\n"
    for name, record in checksums.items())
assert nginx.count("    location ^~ /downloads/ {") == 1
nginx = nginx.replace("    location ^~ /downloads/ {", redirects + "\n    location ^~ /downloads/ {")
(image_dir / "nginx.conf").write_text(nginx)
image = "ai-commerce-frontend:hr-priority-20261012"
(image_dir / "Dockerfile").write_text(
    "FROM " + before["Image"] + "\nCOPY " + " ".join(assets) + " /usr/share/nginx/html/\n"
    "COPY nginx.conf /etc/nginx/conf.d/default.conf\n")
run("docker", "build", "-t", image, str(image_dir))
network = next(iter(before["NetworkSettings"]["Networks"]))
run("docker", "run", "--rm", "--network", network, "--entrypoint", "nginx",
    "-v", "/etc/letsencrypt:/etc/letsencrypt:ro",
    "-v", "/opt/heyi/shared/certbot-webroot:/var/www/certbot:ro", image, "-t")
labels = before["Config"]["Labels"]
compose = ["docker", "compose", "--project-directory", labels["com.docker.compose.project.working_dir"],
           "-p", labels["com.docker.compose.project"]]
for file in labels["com.docker.compose.project.config_files"].split(","):
    compose += ["-f", file]
service = labels["com.docker.compose.service"]
override = root / "compose.priority.json"
override.write_text(json.dumps({"services": {service: {"image": image}}}, indent=2))
env = {**os.environ, "HR_FRONTEND_IMAGE": before["Config"]["Image"]}
run(*compose, "-f", str(override), "config", "-q", env=env)
pi_labels = pi_before["Config"]["Labels"]
pi_compose = ["docker", "compose", "--project-directory", pi_labels["com.docker.compose.project.working_dir"],
              "-p", pi_labels["com.docker.compose.project"]]
for file in pi_labels["com.docker.compose.project.config_files"].split(","):
    pi_compose += ["-f", file]
pi_override = root / "compose.structured.json"
pi_service = pi_labels["com.docker.compose.service"]
pi_override.write_text(json.dumps({"services": {pi_service: {"image": pi_image}}}))
run(*pi_compose, "-f", str(pi_override), "config", "-q")

db_path = "/opt/heyi/boss-agent/runtime/talent-pool.sqlite3"
with sqlite3.connect("file:" + db_path + "?mode=ro", uri=True) as db:
    for table, field, state in [("hr_resume_pool", "ai_state", "running"),
                                 ("hr_interview_handoffs", "status", "sending"),
                                 ("hr_talent_messages", "status", "sending"),
                                 ("hr_sourcing_contacts", "status", "sending"),
                                 ("hr_sourcing_jobs", "profile_state", "running"),
                                 ("hr_sourcing_runs", "state", "running"),
                                 ("recruitment_demands", "ai_status", "running"),
                                 ("hr_onboarding_notices", "status", "preparing"),
                                 ("hr_onboarding_notices", "status", "sending")]:
        assert db.execute(f"SELECT COUNT(*) FROM {table} WHERE {field}=?", (state,)).fetchone()[0] == 0, "HR operation in flight"
query = " UNION ALL ".join(
    f"SELECT '{table}',COUNT(*) FROM {table} WHERE status IN ('RUNNING','PROCESSING','GENERATING','ANALYZING','ANALYSIS_RUNNING')"
    for table in ("model_call_records", "video_scripts", "commerce_batch_executions", "product_image_processing_jobs"))
mysql = run("docker", "exec", "ai-commerce-mysql", "sh", "-c",
            'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -N "$MYSQL_DATABASE" -e "$1"', "query", query).decode()
assert all(int(line.split("\t")[1]) == 0 for line in mysql.splitlines()), "Pi operation in flight"
# Stop only the HR connector after verifying no writes or analyses in flight.
run("systemctl", "stop", "heyi-boss-connector")
try:
    with sqlite3.connect("file:" + db_path + "?mode=ro", uri=True) as db, sqlite3.connect(backup / "talent-pool.sqlite3") as out:
        db.backup(out)
    for name in modules:
        shutil.copy2(root / "staged/python" / name, bin_dir / name)
    migration = run("/opt/heyi/boss-agent/venv/bin/python", "-c", """
import sys,json
sys.path.insert(0,'/opt/heyi/boss-agent/bin')
import passive_pool,talent_pool
with talent_pool.database() as db:
    rows=db.execute("SELECT * FROM hr_resume_pool WHERE COALESCE(json_extract(body,'$.name'),'')=''").fetchall()
    recovered=0
    for row in rows:
        updated=passive_pool.resume_identity(db,row,'hr-identity-release-20261012')
        recovered+=bool(json.loads(updated['body']).get('name'))
    print(json.dumps({'missingBefore':len(rows),'recovered':recovered,'missingAfter':len(rows)-recovered}))
""")
    (root / "name-backfill.json").write_bytes(migration)
    run(*pi_compose, "-f", str(pi_override), "up", "-d", "--no-deps", "--no-build", "--pull", "never", pi_service)
    run("docker", "exec", "heyi-pi-runtime", "node", "-e",
        "(async()=>{for(let n=0;n<20;n++){try{const r=await fetch('http://127.0.0.1:8090/health');"
        "if((await r.json()).status==='UP')return}catch{}await new Promise(r=>setTimeout(r,500))}process.exit(1)})()")
    run("systemctl", "start", "heyi-boss-connector")
    run(*compose, "-f", str(override), "up", "-d", "--no-deps", "--no-build", "--pull", "never", service, env=env)
    run("docker", "exec", container, "nginx", "-t")
    run("systemctl", "is-active", "heyi-boss-connector")
    for name in assets:
        served = run("curl", "-fsS", "--max-time", "15", "--resolve", "hoyee.org.cn:443:127.0.0.1",
                     "https://hoyee.org.cn/" + name)
        assert sha(served) == sha((image_dir / name).read_bytes()), name
    for name, record in checksums.items():
        headers = run("curl", "-fsSI", "--max-time", "15", "--resolve", "hoyee.org.cn:443:127.0.0.1",
                      "https://hoyee.org.cn/downloads/" + name).decode()
        assert "302" in headers.splitlines()[0] and ("location: " + record["url"]).lower() in headers.lower()
    assert preserved == {name: inspect(name)["Id"] for name in preserved}
except Exception:
    for name in modules:
        shutil.copy2(backup / name, bin_dir / name)
    run("systemctl", "restart", "heyi-boss-connector")
    pi_override.write_text(json.dumps({"services": {pi_service: {"image": pi_before["Image"]}}}))
    run(*pi_compose, "-f", str(pi_override), "up", "-d", "--no-deps", "--no-build", pi_service)
    for name in (*assets, "nginx.conf"):
        shutil.copy2(backup / name, image_dir / name)
    rollback_image = image + "-rollback"
    run("docker", "build", "-t", rollback_image, str(image_dir))
    override.write_text(json.dumps({"services": {service: {"image": rollback_image}}}))
    run(*compose, "-f", str(override), "up", "-d", "--no-deps", "--no-build", service, env=env)
    raise
(downloads / "checksums.json").write_text(json.dumps(checksums, indent=2))
(root / "activation.json").write_text(json.dumps({
    "version": build["version"], "frontendImage": inspect(container)["Image"],
    "preservedContainers": preserved, "artifacts": checksums,
    "piImage": inspect("heyi-pi-runtime")["Image"],
    "active": True,
}, indent=2))
print("HR fixes and verified OSS downloads activated; Java and user sessions preserved")
