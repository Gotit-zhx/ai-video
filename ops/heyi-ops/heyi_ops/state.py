"""Crash-safe journal and a lock shared by CI, humans and recovery."""
import contextlib
import fcntl
import json
import os
import re
import time
import tempfile
import stat
from pathlib import Path

TERMINAL = {"succeeded", "rolled_back", "aborted", "recovered"}
REQUEST = re.compile(r"[a-f0-9]{32}\Z")


def atomic(path, value):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    fd,name=tempfile.mkstemp(prefix=path.name+".",suffix=".tmp",dir=path.parent)
    temporary=Path(name)
    try:
        with os.fdopen(fd,"w",encoding="utf-8") as f:
            json.dump(value,f,sort_keys=True,separators=(",",":"));f.write("\n")
            f.flush();os.fsync(f.fileno())
        os.replace(temporary,path)
    finally:temporary.unlink(missing_ok=True)
    fd = os.open(path.parent, os.O_RDONLY)
    try: os.fsync(fd)
    finally: os.close(fd)


def read(path, default=None):
    try: return json.loads(Path(path).read_text())
    except FileNotFoundError: return default


class Busy(RuntimeError): pass


class Store:
    def __init__(self, root, lock, compatibility_lock=None):
        self.root, self.lock = Path(root), Path(lock)
        self.locks=[self.lock]
        if compatibility_lock and Path(compatibility_lock)!=self.lock:self.locks.append(Path(compatibility_lock))

    @contextlib.contextmanager
    def exclusive(self):
        with contextlib.ExitStack() as stack:
            for path in self.locks:
                path.parent.mkdir(parents=True,exist_ok=True)
                fd=os.open(path,os.O_CREAT|os.O_RDWR|os.O_NOFOLLOW|os.O_CLOEXEC,0o600)
                f=stack.enter_context(os.fdopen(fd,"a"));info=os.fstat(f.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_uid!=os.geteuid():raise Busy("Invalid maintenance lock ownership")
                try:fcntl.flock(f,fcntl.LOCK_EX|fcntl.LOCK_NB)
                except BlockingIOError:raise Busy("Another maintenance operation owns the shared lock") from None
            yield

    def path(self, request):
        if not REQUEST.fullmatch(request): raise ValueError("Invalid operation ID")
        return self.root / "operations" / (request + ".json")

    def save(self, operation, phase, **fields):
        operation.update(phase=phase, updatedAt=int(time.time()), **fields)
        atomic(self.path(operation["id"]), operation)
        return operation

    def active(self):
        return read(self.root / "active.json")

    def set_active(self, release):
        atomic(self.root / "active.json", release)

    def incomplete(self):
        return [r for p in sorted((self.root / "operations").glob("*.json"))
                if (r := read(p))["phase"] not in TERMINAL]
