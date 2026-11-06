"""Frontend-only shop month status, local search, and single-line actions."""
import gzip
import importlib.util
import json
import os
from pathlib import Path
import sys

os.umask(0o077)
PREVIOUS = Path("/opt/heyi/releases/finance-sync-priority-20261103")
spec = importlib.util.spec_from_file_location("priority", PREVIOUS / "release-finance-sync-priority.py")
priority = importlib.util.module_from_spec(spec)
spec.loader.exec_module(priority)
core, storage = priority.core, priority.storage
ROOT, APP, FRONT = priority.ROOT, priority.APP, priority.FRONT
IMAGES = {APP: "ai-commerce-platform:finance-sync-priority-20261103",
          FRONT: "ai-commerce-frontend:finance-shop-month-20261103"}
FILES = {"index.html", "finance-workspace.js", "finance-workspace.css"}


def verify(prepared=None):
    priority.verify(IMAGES, prepared or core.read_json("prepared.json"))


def deploy():
    assert not (ROOT / "activation.json").exists(), "Release already activated"
    previous = json.loads((PREVIOUS / "prepared.json").read_text())
    assert previous["files"] == core.read_json("baseline-hashes.json")
    qa = core.read_json("qa-passed.json")
    assert qa["uiTests"] >= 4 and qa["browserLayout"] and qa["singleLineActions"]
    assert qa["monthBoundaries"] and qa["localFiltering"] and qa["shopCountsPreserved"]
    priority.verify(priority.IMAGES, previous)
    before = {name: core.inspect(name) for name in IMAGES}
    assert not core.run("docker", "image", "ls", "--quiet", IMAGES[FRONT]).strip()
    folder = ROOT / "frontend"
    assert {p.name for p in folder.iterdir()} == FILES
    hashes = {}
    for name in sorted(FILES):
        value = (folder / name).read_bytes()
        hashes[name] = core.sha(value)
        (folder / (name + ".gz")).write_bytes(gzip.compress(value, mtime=0))
        (folder / name).chmod(0o644)
        (folder / (name + ".gz")).chmod(0o644)
    prepared = {**previous, "files": hashes}
    guard = json.loads(core.run(
        "python3", "/usr/local/lib/heyi/compact-release-image.py", "--prepare",
        before[FRONT]["Image"], IMAGES[FRONT] + "-base").decode())
    core.write_json(ROOT / "image-guard.json", guard)
    files = [p for name in sorted(FILES) for p in (name, name + ".gz")]
    (folder / "Dockerfile").write_text(
        "FROM " + guard["baseImage"] + "\nCOPY " + " ".join(files) + " /usr/share/nginx/html/\n"
        + "RUN rm -f " + " ".join("/usr/share/nginx/html/" + name + ".br" for name in sorted(FILES)) + "\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGES[FRONT], str(folder))
    try:
        storage.activate(before, FRONT, IMAGES[FRONT])
        verify(prepared)
        assert core.inspect(APP)["Id"] == before[APP]["Id"], "Backend must remain running"
        for name in IMAGES:
            assert core.configuration(core.inspect(name)) == core.configuration(before[name])
        core.write_json(ROOT / "prepared.json", prepared)
        core.write_json(ROOT / "activation.json", {
            "images": IMAGES, "rollbackImage": before[FRONT]["Config"]["Image"],
            "configurationPreserved": True, "backendContainerUnchanged": True,
            "shopCountsPreserved": True, "persistentLogs": False})
    except BaseException:
        storage.activate(before, FRONT, before[FRONT]["Config"]["Image"])
        raise
    print("Shop month status, local search and single-line actions activated; backend unchanged.", flush=True)


if __name__ == "__main__":
    {"deploy": deploy, "verify": verify}[sys.argv[2]]()
