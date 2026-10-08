#!/usr/bin/env python3
"""Deduplicate immutable archived build artifacts, retaining every original path.

Dry run by default. Never use these paths as mutable build destinations after
deduplication: replace artifacts atomically when restoring or rebuilding them.
"""
import argparse
import hashlib
import json
import os
import stat
import time
import uuid
from collections import defaultdict
from pathlib import Path

ROOTS = (Path("/opt/heyi/releases"), Path("/opt/heyi/deployments"))
SUFFIXES = {".jar", ".zip", ".exe", ".dmg", ".gz"}


def fingerprint(info):
    return (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns,
            info.st_uid, info.st_gid, stat.S_IMODE(info.st_mode))


def deduplicate(roots, *, apply=False, minimum_bytes=5 * 1024 * 1024,
                older_than=None):
    cutoff = time.time() - 7 * 86400 if older_than is None else older_than
    sizes = defaultdict(list)
    for root in roots:
        if root.is_symlink():
            raise ValueError("Archive roots must not be symlinks")
        for folder, directories, files in os.walk(root, followlinks=False):
            directories[:] = [name for name in directories
                              if not (Path(folder) / name).is_symlink()]
            for name in files:
                path = Path(folder) / name
                info = path.lstat()
                if (not stat.S_ISREG(info.st_mode) or path.suffix.lower() not in SUFFIXES
                        or info.st_size < minimum_bytes or info.st_mtime > cutoff):
                    continue
                key = (info.st_dev, info.st_size, info.st_uid, info.st_gid,
                       stat.S_IMODE(info.st_mode))
                sizes[key].append((path, info))
    groups = defaultdict(list)
    for key, files in sizes.items():
        if len(files) < 2:
            continue
        for path, info in files:
            digest = hashlib.sha256()
            with path.open("rb") as source:
                for block in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(block)
            if fingerprint(path.lstat()) != fingerprint(info):
                raise RuntimeError("Archive changed during inspection")
            groups[(key, digest.digest())].append((path, info))
    replaced = reclaimed = 0
    for files in groups.values():
        keeper, original = files[0]
        for duplicate, before in files[1:]:
            if (before.st_dev, before.st_ino) == (original.st_dev, original.st_ino):
                continue
            # A pre-existing shared inode may have other names outside this scan;
            # do not claim or remove its storage.
            if before.st_nlink != 1:
                continue
            if (fingerprint(keeper.lstat()) != fingerprint(original)
                    or fingerprint(duplicate.lstat()) != fingerprint(before)):
                raise RuntimeError("Archive changed before replacement")
            if apply:
                temporary = duplicate.with_name("." + duplicate.name + ".dedup-" + uuid.uuid4().hex)
                try:
                    os.link(keeper, temporary, follow_symlinks=False)
                    if fingerprint(duplicate.lstat()) != fingerprint(before):
                        raise RuntimeError("Archive changed before replacement")
                    os.replace(temporary, duplicate)
                finally:
                    temporary.unlink(missing_ok=True)
            replaced += 1
            reclaimed += before.st_size
    return {"applied": apply, "duplicateFiles": replaced, "reclaimedBytes": reclaimed}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    options = parser.parse_args()
    if options.apply:
        # Reject archive paths in use as bind mounts, including stopped containers.
        import subprocess
        ids = subprocess.check_output(["docker", "ps", "-aq"], text=True).split()
        containers = json.loads(subprocess.check_output(["docker", "inspect", *ids])) if ids else []
        for container in containers:
            for mount in container.get("Mounts", []):
                source = Path(mount["Source"]).resolve()
                if any(source == root or root in source.parents or source in root.parents for root in ROOTS):
                    raise RuntimeError("A container mounts an archive root; manual review required")
    print(json.dumps(deduplicate(ROOTS, apply=options.apply)), flush=True)


if __name__ == "__main__":
    main()
