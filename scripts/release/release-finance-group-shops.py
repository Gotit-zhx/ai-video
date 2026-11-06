"""Release immediate finance group/shop cascading without restarting workers."""
import gzip
import importlib.util
import json
import sys

spec = importlib.util.spec_from_file_location(
    "latest", "/opt/heyi/releases/finance-cost-dedupe-20261104/release-finance-cost-dedupe.py")
latest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(latest)
core, storage = latest.core, latest.storage
ROOT, APP, FRONT = latest.ROOT, latest.APP, latest.FRONT
IMAGE = "ai-commerce-frontend:finance-group-shops-20261104"
PREVIOUS = "ai-commerce-frontend:finance-fee-native-layout-20261104"
FILES = ("index.html", "finance-workspace.js", "finance-label-costs.js", "finance-costs.js")
PRESERVED = ("finance-workspace.css", "finance-fee-exports.js", "finance-qianyi.js", "finance-charges.js")


def live_file(name):
    return core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + name)


def config_hash(info):
    return core.sha(json.dumps(core.configuration(info), sort_keys=True).encode())


def verify():
    receipt = core.read_json("prepared.json")
    app, front = core.inspect(APP), core.inspect(FRONT)
    assert app["Id"] == receipt["backendContainerId"], "Backend must not restart"
    assert app["State"]["Running"] and front["State"]["Running"]
    assert front["Config"]["Image"] == IMAGE
    for name, digest in receipt["files"].items():
        value = live_file(name)
        assert core.sha(value) == digest, name
        assert gzip.decompress(live_file(name + ".gz")) == value, name + ".gz"
    for name, digest in receipt["preservedFiles"].items():
        assert core.sha(live_file(name)) == digest, name
    for name in (APP, FRONT):
        info = core.inspect(name)
        assert config_hash(info) == receipt["configurationHashes"][name]
        assert info["HostConfig"]["LogConfig"]["Type"] == "none"
        assert not info.get("LogPath")
    for path, expected in (("/", "200"), ("/api/v1/finance/shops", "401")):
        status = core.run("curl", "--noproxy", "*", "-sS", "--max-time", "15",
                          "--resolve", "hoyee.org.cn:443:127.0.0.1", "-o", "/dev/null",
                          "-w", "%{http_code}", "https://hoyee.org.cn" + path).decode()
        assert status == expected, path
    for name, digest in receipt["files"].items():
        served = core.run("curl", "--noproxy", "*", "-fsS", "--max-time", "15",
                          "--resolve", "hoyee.org.cn:443:127.0.0.1",
                          "https://hoyee.org.cn/" + name + "?v=20261104-group-shops")
        assert core.sha(served) == digest, "HTTP " + name
    print("Frontend hashes, compressed assets, HTTP, configuration and unchanged backend verified.", flush=True)


def deploy():
    assert not (ROOT / "activation.json").exists()
    assert core.read_json("qa-passed.json")["uiSuites"] == 8
    expected = core.read_json("baseline-expected.json")
    before = {name: core.inspect(name) for name in (APP, FRONT)}
    assert before[APP]["Config"]["Image"] == latest.IMAGE
    assert before[FRONT]["Config"]["Image"] == PREVIOUS
    for name in FILES:
        assert core.sha(live_file(name)) == expected[name], "Concurrent change: " + name
    preserved = {name: core.sha(live_file(name)) for name in PRESERVED}
    folder = ROOT / "frontend"
    hashes = {}
    for name in FILES:
        value = (folder / name).read_bytes()
        hashes[name] = core.sha(value)
        (folder / (name + ".gz")).write_bytes(gzip.compress(value, mtime=0))
        for path in (folder / name, folder / (name + ".gz")):
            path.chmod(0o644)
    guard = json.loads(core.run("python3", "/usr/local/lib/heyi/compact-release-image.py",
                               "--prepare", before[FRONT]["Image"], IMAGE + "-base").decode())
    core.write_json(ROOT / "image-guard.json", guard)
    (folder / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY "
        + " ".join(name for file in FILES for name in (file, file + ".gz"))
        + " /usr/share/nginx/html/\nRUN rm -f "
        + " ".join("/usr/share/nginx/html/" + file + ".br" for file in FILES) + "\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGE, str(folder))
    core.write_json(ROOT / "prepared.json", {
        "sourceFrontendImageId": before[FRONT]["Image"],
        "backendContainerId": before[APP]["Id"], "backendImage": before[APP]["Config"]["Image"],
        "files": hashes, "preservedFiles": preserved,
        "configurationHashes": {name: config_hash(info) for name, info in before.items()}})
    try:
        storage.activate(before, FRONT, IMAGE)
        verify()
    except BaseException:
        storage.activate(before, FRONT, PREVIOUS)
        raise
    core.write_json(ROOT / "activation.json", {
        "image": IMAGE, "rollbackImage": PREVIOUS, "uiSuites": 8,
        "backendRestarted": False, "configurationPreserved": True,
        "persistentContainerLogs": False})
    print("Group/shop cascade deployed; financial workers and queues were untouched.", flush=True)


if __name__ == "__main__":
    {"deploy": deploy, "verify": verify}[sys.argv[2]]()
