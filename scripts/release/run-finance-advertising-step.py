"""Finish one already-prepared deployment even if the operator's SSH session closes."""
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import sys

os.umask(0o077)
root = Path(sys.argv[1]).resolve()
allowed = {
    "Original schedules restored; in-flight leases and checkpoints were not discarded.",
    "Qianyi reservations released; configuration and checkpoints preserved.",
    "Qianyi account reservations released.",
    "Live artifacts, gzip/HTTP, configuration, source revision, schema and access guards verified.",
    "Advertising release activated; unrelated services, schema and configuration preserved.",
}


def write(name, data):
    temporary = root / (name + ".new")
    temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n")
    os.replace(temporary, root / name)


result = {"startedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
          "runnerPid": os.getpid(), "phase": "drain-deploy", "messages": []}
try:
    if (root / "deployment-result.json").exists():
        raise RuntimeError("A deployment result already exists")
    process = subprocess.Popen([sys.executable, "-B", str(root / "release-finance-advertising.py"),
                                str(root), "drain-deploy"], stdin=subprocess.DEVNULL,
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    result["helperPid"] = process.pid
    write("deployment-running.json", result)
    pending = b""
    while chunk := process.stdout.read1(8192):
        pending += chunk
        while b"\n" in pending:
            line, pending = pending.split(b"\n", 1)
            value = line.decode("utf-8", errors="replace").strip()
            if value in allowed or re.fullmatch(r"Advertising release step failed: [A-Za-z0-9_]+", value):
                result["messages"] = (result["messages"] + [value])[-20:]
                write("deployment-running.json", result)
        # Unknown subprocess output can contain private context; never persist it.
        if len(pending) > 65536:
            pending = b""
    result["exitCode"] = process.wait()
except BaseException as error:
    result["exitCode"] = 1
    result["runnerErrorType"] = type(error).__name__
finally:
    result["completedAtUtc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    write("deployment-result.json", result)
