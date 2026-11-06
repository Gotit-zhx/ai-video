"""Crash-safe journal and a lock shared by CI, humans and recovery."""
import contextlib
import fcntl
import json
import os
import re
import time
from pathlib import Path

TERMINAL = {"succeeded", "rolled_back", "aborted", "recovered"}
REQUEST = re.compile(r"[a-f0-9]{32}\Z")


def atomic(path, value):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + "." + str(os.getpid()) + ".tmp")
    with temporary.open("x", encoding="utf-8") as f:
        os.chmod(temporary, 0o600)
        json.dump(value, f, sort_keys=True, separators=(",", ":")); f.write("\n")
        f.flush(); os.fsync(f.fileno())
    os.replace(temporary, path)
    fd = os.open(path.parent, os.O_RDONLY)
    try: os.fsync(fd)
    finally: os.close(fd)


def read(path, default=None):
    try: return json.loads(Path(path).read_text())
    except FileNotFoundError: return default


class Busy(RuntimeError): pass


class Store:
    def __init__(self, root, lock):
        self.root, self.lock = Path(root), Path(lock)

    @contextlib.contextmanager
    def exclusive(self):
        self.lock.parent.mkdir(parents=True, exist_ok=True)
        with self.lock.open("a") as f:
            try: fcntl.flock(f, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError: raise Busy("Another maintenance operation owns the shared lock") from None
            try: yield
            finally: fcntl.flock(f, fcntl.LOCK_UN)

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
