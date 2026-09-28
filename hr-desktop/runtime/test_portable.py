"""Actual OS lock behavior, using a second process; no platform network."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import portable_lock

with tempfile.TemporaryDirectory() as directory:
    filename = Path(directory) / "account.lock"
    fd = os.open(filename, os.O_RDWR | os.O_CREAT, 0o600)
    portable_lock.flock(fd, portable_lock.LOCK_EX | portable_lock.LOCK_NB)
    code = (
        "import os,portable_lock,sys;"
        "fd=os.open(sys.argv[1],os.O_RDWR);"
        "portable_lock.flock(fd,portable_lock.LOCK_EX|portable_lock.LOCK_NB)"
    )
    command = [sys.executable, "-c", code, str(filename)]
    assert subprocess.run(command, cwd=Path(__file__).parent, capture_output=True).returncode != 0
    os.close(fd)
    result = subprocess.run(command, cwd=Path(__file__).parent, capture_output=True)
    assert result.returncode == 0, result.stderr.decode()
print("Portable process lock passed")
