"""Publish the reviewed interview UI and connector; preserve all app containers."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import sys
import time

root = Path(sys.argv[1]).resolve()
expected = json.loads((root / "backend-expected.json").read_text())
manifest = json.loads((root / "backend-manifest.json").read_text())
bin_dir = Path("/opt/heyi/boss-agent/bin")
python = "/opt/heyi/boss-agent/venv/bin/python"
service = "heyi-boss-connector"
backup = root / "backend-rollback"
backup.mkdir(mode=0o700)
assert not (root / "backend-activation.json").exists()


def run(*args, **kwargs):
    return subprocess.check_output(args, stderr=subprocess.STDOUT, **kwargs)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


environment = run("systemctl", "show", service, "-p", "Environment", "--value").decode()
assert "APP_HR_DESKTOP_ONLY=1" in environment
assert Path("/opt/heyi/boss-agent/runtime/resume-auto-pause").exists()
assert shutil.disk_usage(root).free >= 2*1024**3
for name, digest in expected.items():
    assert (sha(bin_dir/name) if (bin_dir/name).exists() else None) == digest, "Backend drift: "+name
for name, digest in manifest.items():
    assert sha(root/"backend"/name) == digest
    run(python, "-m", "py_compile", str(root/"backend"/name))
run(python, "-c", "from cryptography.fernet import Fernet")

db_path = Path("/opt/heyi/boss-agent/runtime/talent-pool.sqlite3")
with sqlite3.connect(f"file:{db_path}?mode=ro", uri=True) as db:
    for table, condition in [
        ("recruitment_demands", "ai_status='running'"), ("hr_resume_pool", "ai_state='running'"),
        ("hr_sourcing_runs", "state='running'"), ("hr_sourcing_jobs", "profile_state='running'"),
        ("hr_sourcing_contacts", "status='sending'"), ("hr_talent_messages", "status='sending'"),
        ("hr_resume_commands", "status='sending'"),
        ("hr_interview_handoffs", "status IN ('preparing','sending')"),
        ("hr_onboarding_notices", "status IN ('preparing','sending')")]:
        if table in ("hr_interview_handoffs", "hr_onboarding_notices") and not db.execute(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", (table,)).fetchone():
            continue
        assert db.execute(f"SELECT COUNT(*) FROM {table} WHERE {condition}").fetchone()[0] == 0, "Active HR operation: "+table
    with sqlite3.connect(backup/"talent-pool.sqlite3") as destination:
        db.backup(destination)
(backup/"talent-pool.sqlite3").chmod(0o600)
reference = (bin_dir/"connector.py").stat()
for name in manifest:
    if (bin_dir/name).exists():
        shutil.copy2(bin_dir/name, backup/name)


def replace(source, name):
    temporary = bin_dir/("." + name + ".interview-release")
    shutil.copyfile(source, temporary)
    os.chown(temporary, reference.st_uid, reference.st_gid)
    temporary.chmod(reference.st_mode & 0o777)
    os.replace(temporary, bin_dir/name)


try:
    for name in manifest:
        replace(root/"backend"/name, name)
    run("systemctl", "restart", service)
    for attempt in range(20):
        if run("systemctl", "is-active", service).decode().strip() == "active":
            break
        time.sleep(1)
    # Verify the process stays alive after imports, schema creation and socket binding.
    time.sleep(2)
    assert run("systemctl", "is-active", service).decode().strip() == "active"
    assert all(sha(bin_dir/name) == digest for name, digest in manifest.items())
    run("python3", str(root/"release-front.py"), str(root))
except BaseException:
    for name in manifest:
        if (backup/name).exists():
            replace(backup/name, name)
        elif expected[name] is None:
            (bin_dir/name).unlink(missing_ok=True)
    run("systemctl", "restart", service)
    raise
(root/"backend-activation.json").write_text(json.dumps({"sha256": manifest, "service": service}))
print("Interview handoff published; backend and frontend verified; application services preserved")
