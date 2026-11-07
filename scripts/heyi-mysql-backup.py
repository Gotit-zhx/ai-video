#!/usr/bin/env python3
"""Bounded local database backup. No application or diagnostic log files."""
import datetime
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import resource
import signal
import subprocess
import time
import zlib

ROOT = Path("/opt/heyi/backups/mysql")
GIB = 1024 ** 3
MAX_ARCHIVE = 6 * GIB
RESERVE = 8 * GIB
KEEP = 2


def receipt(outcome, **fields):
    """A small status file, never SQL output, credentials or persistent logs."""
    path = ROOT / "status.json"
    try:
        previous = json.loads(path.read_text())
    except (FileNotFoundError, ValueError):
        previous = {}
    value = {"outcome": outcome, "finishedAt": int(time.time()), **fields}
    if outcome == "success":
        value["lastSuccess"] = {k: value[k] for k in ("finishedAt", "archive", "bytes", "sha256", "integrity")}
    elif previous.get("lastSuccess"):
        value["lastSuccess"] = previous["lastSuccess"]
    temporary = path.with_name(".status-" + str(os.getpid()) + ".tmp")
    with temporary.open("w") as output:
        os.chmod(temporary, 0o600)
        json.dump(value, output, sort_keys=True)
        output.write("\n"); output.flush(); os.fsync(output.fileno())
    temporary.replace(path)


class BackupFailure(Exception):
    def __init__(self, outcome, code):
        self.outcome, self.code = outcome, code


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
            receipt("lock_busy")
            return 0
        for stale in ROOT.glob("ai-commerce-*.sql.gz.tmp"):
            stale.unlink()
        prune()
        if available() < RESERVE + MAX_ARCHIVE:
            receipt("insufficient_space")
            return 75
        stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        final = ROOT / ("ai-commerce-" + stamp + ".sql.gz")
        temporary = final.with_suffix(final.suffix + ".tmp")
        children = []
        started = time.monotonic()
        deadline = started + 3600
        def interrupted(_signal, _frame):
            raise BackupFailure("timeout", 124)
        signal.signal(signal.SIGTERM, interrupted)
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
                while compress.poll() is None:
                    if available() < RESERVE:
                        raise BackupFailure("insufficient_space", 75)
                    if time.monotonic() > deadline:
                        raise BackupFailure("timeout", 124)
                    time.sleep(2)
                if dump.wait(timeout=30) or compress.returncode or output.tell() == 0:
                    raise BackupFailure("dump_failed", 1)
                output.flush()
                os.fsync(output.fileno())
            # Verify the full stream before replacing any previously valid backup.
            with gzip.open(temporary, "rb") as check:
                while check.read(1024 * 1024):
                    if time.monotonic() > deadline:
                        raise BackupFailure("timeout", 124)
            digest = hashlib.sha256()
            with temporary.open("rb") as check:
                while chunk := check.read(1024 * 1024):
                    digest.update(chunk)
                    if time.monotonic() > deadline:
                        raise BackupFailure("timeout", 124)
            temporary.replace(final)
            prune()
            receipt("success", archive=final.name, bytes=final.stat().st_size,
                    sha256=digest.hexdigest(), integrity="gzip_verified", durationSeconds=round(time.monotonic()-started))
            return 0
        except BackupFailure as error:
            receipt(error.outcome, durationSeconds=round(time.monotonic()-started))
            return error.code
        except (gzip.BadGzipFile, EOFError, zlib.error):
            receipt("integrity_failed", durationSeconds=round(time.monotonic()-started))
            return 1
        except subprocess.TimeoutExpired:
            receipt("timeout", durationSeconds=round(time.monotonic()-started))
            return 124
        except OSError:
            receipt("io_failed", durationSeconds=round(time.monotonic()-started))
            return 1
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
