"""Release the blank brush-cost import template and its two download links."""
import gzip
import importlib.util
import json
import sys

spec = importlib.util.spec_from_file_location(
    "latest", "/opt/heyi/releases/finance-source-orders-20261105/release-finance-source-orders.py")
latest = importlib.util.module_from_spec(spec)
spec.loader.exec_module(latest)
core, storage = latest.core, latest.storage
ROOT, APP, FRONT = latest.ROOT, latest.APP, latest.FRONT
IMAGE = "ai-commerce-frontend:finance-brush-template-20261105"
PREVIOUS = "ai-commerce-frontend:finance-source-orders-20261105"
TEXT_FILES = ("index.html", "finance-charges.js")
TEMPLATE = "assets/templates/finance-brush-cost-template.xlsx"
FILES = (*TEXT_FILES, TEMPLATE)
PRESERVED = ("finance-workspace.js", "finance-workspace.css", "finance-label-costs.js",
             "finance-costs.js", "finance-fee-exports.js", "finance-qianyi.js")


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
        if name in TEXT_FILES:
            assert gzip.decompress(live_file(name + ".gz")) == value
        served = core.run("curl", "--noproxy", "*", "-fsS", "--max-time", "15",
                          "--resolve", "hoyee.org.cn:443:127.0.0.1",
                          "https://hoyee.org.cn/" + name + "?v=20261105-brush-template")
        assert core.sha(served) == digest, "HTTP " + name
    for name, digest in receipt["preservedFiles"].items():
        assert core.sha(live_file(name)) == digest, name
    for name in (APP, FRONT):
        info = core.inspect(name)
        assert config_hash(info) == receipt["configurationHashes"][name]
        assert info["HostConfig"]["LogConfig"]["Type"] == "none"
        assert not info.get("LogPath")
    for path, expected in (("/", "200"), ("/api/v1/finance/charges", "401")):
        code = core.run("curl", "--noproxy", "*", "-sS", "--max-time", "15",
                        "--resolve", "hoyee.org.cn:443:127.0.0.1", "-o", "/dev/null",
                        "-w", "%{http_code}", "https://hoyee.org.cn" + path).decode()
        assert code == expected, path
    print("Template HTTP bytes, UI assets, unchanged backend and runtime configuration verified.", flush=True)


def deploy():
    assert not (ROOT / "activation.json").exists()
    qa = core.read_json("qa-passed.json")
    assert qa["uiSuitePassed"] and qa["existingParserPassed"] and qa["workbookReviewed"]
    before = {name: core.inspect(name) for name in (APP, FRONT)}
    assert before[APP]["Config"]["Image"] == latest.IMAGES[APP]
    assert before[FRONT]["Config"]["Image"] == PREVIOUS
    expected = core.read_json("baseline-expected.json")
    for name in TEXT_FILES:
        assert core.sha(live_file(name)) == expected[name], "Concurrent change: " + name
    preserved = {name: core.sha(live_file(name)) for name in PRESERVED}
    folder = ROOT / "frontend"
    hashes = {}
    for name in FILES:
        value = (folder / name).read_bytes()
        hashes[name] = core.sha(value)
        (folder / name).chmod(0o644)
        if name in TEXT_FILES:
            (folder / (name + ".gz")).write_bytes(gzip.compress(value, mtime=0))
            (folder / (name + ".gz")).chmod(0o644)
    guard = json.loads(core.run("python3", "/usr/local/lib/heyi/compact-release-image.py",
                               "--prepare", before[FRONT]["Image"], IMAGE + "-base").decode())
    core.write_json(ROOT / "image-guard.json", guard)
    (folder / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY "
        + " ".join(n for name in TEXT_FILES for n in (name, name + ".gz"))
        + " /usr/share/nginx/html/\nCOPY " + TEMPLATE + " /usr/share/nginx/html/" + TEMPLATE
        + "\nRUN rm -f " + " ".join("/usr/share/nginx/html/" + name + ".br" for name in TEXT_FILES) + "\n")
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
        "image": IMAGE, "rollbackImage": PREVIOUS, "backendRestarted": False,
        "configurationPreserved": True, "financialDataModified": False,
        "persistentContainerLogs": False})
    print("Brush-cost template deployed; finance workers, data and import rules unchanged.", flush=True)


if __name__ == "__main__":
    {"deploy": deploy, "verify": verify}[sys.argv[2]]()
