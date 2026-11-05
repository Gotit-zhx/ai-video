"""Release the Qianyi missing-logistics display without changing finance data."""
import gzip
import importlib.util
import sys

spec = importlib.util.spec_from_file_location(
    "frontend_release",
    "/opt/heyi/releases/finance-brush-template-20261105/release-finance-brush-template.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
core, storage = release.core, release.storage
ROOT, APP, FRONT = release.ROOT, release.APP, release.FRONT
IMAGE = "ai-commerce-frontend:qianyi-receipt-display-20261105"
PREVIOUS = "ai-commerce-frontend:finance-brush-original-template-20261105"
FILES = ("index.html", "finance-qianyi.js")
release.IMAGE = IMAGE
release.TEXT_FILES = FILES
release.FILES = FILES
release.PRESERVED = (
    "finance-workspace.js", "finance-workspace.css", "finance-label-costs.js",
    "finance-costs.js", "finance-fee-exports.js", "finance-charges.js",
    "assets/templates/finance-brush-cost-template.xlsx")


def verify():
    release.verify()
    code = core.run("curl", "--noproxy", "*", "-sS", "--max-time", "15",
                    "--resolve", "hoyee.org.cn:443:127.0.0.1", "-o", "/dev/null",
                    "-w", "%{http_code}",
                    "https://hoyee.org.cn/api/v1/finance/qianyi/returns").decode()
    assert code == "401"


def deploy():
    assert not (ROOT / "activation.json").exists()
    qa = core.read_json("qa-passed.json")
    assert qa["qianyiUiPassed"] and qa["embeddedRefundUiPassed"]
    before = {name: core.inspect(name) for name in (APP, FRONT)}
    assert before[APP]["Config"]["Image"] == "ai-commerce-platform:finance-source-orders-20261105"
    assert before[FRONT]["Config"]["Image"] == PREVIOUS
    expected = core.read_json("baseline-expected.json")
    for name in FILES:
        assert core.sha(release.live_file(name)) == expected[name], "Concurrent change: " + name
    preserved = {name: core.sha(release.live_file(name)) for name in release.PRESERVED}
    folder = ROOT / "frontend"
    hashes = {}
    for name in FILES:
        value = (folder / name).read_bytes()
        hashes[name] = core.sha(value)
        (folder / name).chmod(0o644)
        (folder / (name + ".gz")).write_bytes(gzip.compress(value, mtime=0))
        (folder / (name + ".gz")).chmod(0o644)
    import json
    guard = json.loads(core.run(
        "python3", "/usr/local/lib/heyi/compact-release-image.py",
        "--prepare", before[FRONT]["Image"], IMAGE + "-base").decode())
    core.write_json(ROOT / "image-guard.json", guard)
    (folder / "Dockerfile").write_text(
        "FROM " + guard["baseImage"] + "\nCOPY "
        + " ".join(n for name in FILES for n in (name, name + ".gz"))
        + " /usr/share/nginx/html/\nRUN rm -f "
        + " ".join("/usr/share/nginx/html/" + name + ".br" for name in FILES) + "\n")
    core.run("docker", "build", "--pull=false", "-t", IMAGE, str(folder))
    core.write_json(ROOT / "prepared.json", {
        "sourceFrontendImageId": before[FRONT]["Image"],
        "backendContainerId": before[APP]["Id"], "backendImage": before[APP]["Config"]["Image"],
        "files": hashes, "preservedFiles": preserved,
        "configurationHashes": {name: release.config_hash(info) for name, info in before.items()}})
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
    print("Qianyi display deployed; receipt classification and financial data unchanged.", flush=True)


if __name__ == "__main__":
    {"deploy": deploy, "verify": verify}[sys.argv[2]]()
