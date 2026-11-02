#!/usr/bin/env python3
"""Bounded local database backup. No application or diagnostic log files."""
import datetime
import fcntl
import gzip
import os
from pathlib import Path
import resource
import subprocess
import time

ROOT = Path("/opt/heyi/backups/mysql")
GIB = 1024 ** 3
MAX_ARCHIVE = 6 * GIB
RESERVE = 8 * GIB
KEEP = 2


def available():
    space = os.statvfs(ROOT)
    return space.f_bavail * space.f_frsize


def limit_archive():
    resource.setrlimit(resource.RLIMIT_FSIZE, (MAX_ARCHIVE, MAX_ARCHIVE))


def prune():
    completed = sorted(ROOT.glob("ai-commerce-*.sql.gz"), reverse=True)
    for old in completed[KEEP:]:
        old.unlink()


def main():
    os.umask(0o077)
    ROOT.mkdir(parents=True, exist_ok=True)
    with (ROOT / ".backup.lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return 0
        for stale in ROOT.glob("ai-commerce-*.sql.gz.tmp"):
            stale.unlink()
        prune()
        if available() < RESERVE + MAX_ARCHIVE:
            return 75
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        final = ROOT / ("ai-commerce-" + stamp + ".sql.gz")
        temporary = final.with_suffix(final.suffix + ".tmp")
        children = []
        try:
            with temporary.open("xb") as output:
                dump = subprocess.Popen(
                    ["docker", "exec", "ai-commerce-mysql", "sh", "-c",
                     'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysqldump --single-transaction '
                     '--quick --routines --events --triggers --no-tablespaces '
                     '-uroot "$MYSQL_DATABASE"'],
                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
                children.append(dump)
                compress = subprocess.Popen(
                    ["gzip", "-6"], stdin=dump.stdout, stdout=output,
                    stderr=subprocess.DEVNULL, preexec_fn=limit_archive)
                children.append(compress)
                dump.stdout.close()
                deadline = time.monotonic() + 3600
                while compress.poll() is None:
                    if available() < RESERVE or time.monotonic() > deadline:
                        return 75
                    time.sleep(2)
                if dump.wait(timeout=30) or compress.returncode or output.tell() == 0:
                    return 1
                output.flush()
                os.fsync(output.fileno())
            # Verify the full stream before replacing any previously valid backup.
            with gzip.open(temporary, "rb") as check:
                while check.read(1024 * 1024):
                    pass
            temporary.replace(final)
            prune()
            return 0
        finally:
            for child in children:
                if child.poll() is None:
                    child.terminate()
                    try:
                        child.wait(timeout=10)
                    except subprocess.TimeoutExpired:
                        child.kill()
                        child.wait()
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    raise SystemExit(main())
