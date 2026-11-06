"""Remove reviewed obsolete build binaries without touching data or deployment configuration."""
import argparse
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import time


def command(*args):
    return subprocess.check_output(args, stderr=subprocess.STDOUT).decode()


def containers():
    return json.loads(command("docker", "inspect", *command("docker", "ps", "-aq").split()))


def identity(info):
    return [info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns]


def in_use():
    paths = set()
    for process in Path("/proc").glob("[0-9]*"):
        for entry in (process / "fd").glob("*"):
            try:
                target = entry.resolve(strict=True)
                if target.is_file():
                    paths.add(str(target))
            except (OSError, RuntimeError):
                pass
    return paths


def plan(root):
    current = containers()
    mounts = [Path(mount["Source"]) for value in current for mount in value["Mounts"]]
    opened = in_use()
    roots = [Path("/opt/heyi/releases"), Path("/opt/heyi/deployments")]
    roots.extend(path for path in Path("/root").iterdir()
                 if path.is_dir() and re.fullmatch(r"[a-z0-9-]+-2026092[23]", path.name))
    candidates = []
    for parent in roots:
        for directory, children, files in os.walk(parent, followlinks=False):
            children[:] = [name for name in children if not Path(directory, name).is_symlink()]
            for name in files:
                path = Path(directory, name)
                info = path.lstat()
                build_binary = path.suffix == ".jar" or (
                    path.suffix in {".tar", ".tgz"} and name.startswith(("app-hotfix", "pi-hotfix", "release-")))
                if (not stat.S_ISREG(info.st_mode) or not build_binary
                        or info.st_size < 5 * 1024 * 1024 or info.st_mtime > time.time() - 7 * 86400
                        or str(path) in opened or any(path == mount or mount in path.parents for mount in mounts)):
                    continue
                candidates.append({"path": str(path), "identity": identity(info),
                                   "blocks": info.st_blocks * 512, "links": info.st_nlink})
    result = {"createdAt": time.time(), "containers": {
        item["Name"]: {"id": item["Id"], "image": item["Image"], "startedAt": item["State"]["StartedAt"]}
        for item in current if item["State"]["Running"]}, "files": candidates}
    root.mkdir(mode=0o700, parents=True, exist_ok=False)
    (root / "plan.json").write_text(json.dumps(result, indent=2))
    (root / "plan.json").chmod(0o600)
    print(json.dumps({"files": len(candidates),
                      "logicalBytes": sum(row["identity"][2] for row in candidates),
                      "plan": str(root / "plan.json")}))


def apply(root):
    assert not (root / "complete.json").exists(), "Already applied"
    recorded = json.loads((root / "plan.json").read_text())
    assert time.time() - recorded["createdAt"] < 3600, "Plan expired"
    live = {item["Name"]: item for item in containers()}
    for name, before in recorded["containers"].items():
        assert live[name]["Id"] == before["id"] and live[name]["State"]["Running"]
    opened = in_use()
    for row in recorded["files"]:
        path = Path(row["path"])
        assert not path.is_symlink() and identity(path.stat()) == row["identity"], "Artifact changed"
        assert str(path) not in opened, "Artifact is open"
    before = os.statvfs("/")
    for row in recorded["files"]:
        Path(row["path"]).unlink()
    actions = {}
    for name, args in (
        ("apt", ("apt-get", "clean")),
        ("builder", ("docker", "builder", "prune", "--force", "--filter", "until=168h")),
        ("dangling-images", ("docker", "image", "prune", "--force", "--filter", "until=168h")),
    ):
        output = command(*args)
        (root / (name + ".log")).write_text(output)
        actions[name] = output.splitlines()[-1:] or ["completed"]
    after = os.statvfs("/")
    live = {item["Name"]: item for item in containers()}
    for name, previous in recorded["containers"].items():
        assert live[name]["Id"] == previous["id"] and live[name]["State"]["StartedAt"] == previous["startedAt"]
    result = {"filesDeleted": len(recorded["files"]),
              "freedBytes": (after.f_bavail - before.f_bavail) * after.f_frsize,
              "availableBytes": after.f_bavail * after.f_frsize,
              "allRunningContainersPreserved": True, "actions": actions,
              "businessDataDeleted": False, "databaseBackupsDeleted": False}
    (root / "complete.json").write_text(json.dumps(result, indent=2))
    print(json.dumps(result))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("plan", "apply"))
    parser.add_argument("report_root", type=Path)
    options = parser.parse_args()
    assert options.report_root.is_relative_to("/opt/heyi/diagnostics")
    {"plan": plan, "apply": apply}[options.mode](options.report_root)
