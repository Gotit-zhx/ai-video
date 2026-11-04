"""Make fee matrices strictly native-currency without changing financial records."""
import gzip
import importlib.util
import io
import json
import sys
import zipfile

spec = importlib.util.spec_from_file_location("release",
    "/opt/heyi/releases/finance-fee-export-20261104/release-finance-fee-export.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)
core, ROOT, APP, FRONT = release.core, release.ROOT, release.APP, release.FRONT
release.IMAGES = {APP: "ai-commerce-platform:finance-fee-native-20261104",
                  FRONT: "ai-commerce-frontend:finance-fee-native-20261104"}


def build():
    assert core.read_json("qa-passed.json")["nativeCurrencyOnly"]
    prior = ROOT.parent / "finance-fee-export-20261104"
    expected = json.loads((prior / "prepared.json").read_text())
    expected["files"].update(json.loads((prior / "frontend-layout.json").read_text())["files"])
    before = {name: core.inspect(name) for name in release.IMAGES}
    assert before[APP]["Config"]["Image"] == "ai-commerce-platform:finance-fee-export-20261104"
    assert before[FRONT]["Config"]["Image"] == "ai-commerce-frontend:finance-fee-export-layout-20261104"
    old = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    assert core.sha(old) == expected["jarHash"]
    with zipfile.ZipFile(io.BytesIO(old)) as source:
        assert all(core.sha(source.read(name)) == digest for name, digest in expected["classes"].items())
    for file, digest in expected["files"].items():
        assert core.sha(core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)) == digest
    folder = ROOT / "app-image"
    folder.mkdir()
    entries = {"BOOT-INF/classes/" + str(p.relative_to(ROOT / "classes")): p.read_bytes()
               for p in (ROOT / "classes").rglob("*.class")}
    assert entries and all(release.replaced(name) for name in entries)
    assert all(prefix + ".class" in entries for prefix in release.PREFIXES)
    with zipfile.ZipFile(io.BytesIO(old)) as source, zipfile.ZipFile(folder / "app.jar", "w") as target:
        target.comment = source.comment
        for item in source.infolist():
            if not release.replaced(item.filename):
                target.writestr(item, source.read(item.filename))
        for name, value in entries.items():
            target.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(io.BytesIO(old)) as source, zipfile.ZipFile(folder / "app.jar") as target:
        assert set(target.namelist()) == {n for n in source.namelist() if not release.replaced(n)} | set(entries)
        assert all(source.read(n) == target.read(n) for n in source.namelist() if not release.replaced(n))
    (folder / "app.jar").chmod(0o644)
    frontend = ROOT / "frontend"
    hashes = {}
    for file in release.FILES:
        value = (frontend / file).read_bytes()
        hashes[file] = core.sha(value)
        (frontend / (file + ".gz")).write_bytes(gzip.compress(value, mtime=0))
        for name in (file, file + ".gz"):
            (frontend / name).chmod(0o644)
    for name, directory in ((APP, folder), (FRONT, frontend)):
        guard = json.loads(core.run("python3", "/usr/local/lib/heyi/compact-release-image.py",
            "--prepare", before[name]["Image"], release.IMAGES[name] + "-base").decode())
        core.write_json(ROOT / (name + "-image-guard.json"), guard)
        copy = "COPY app.jar /app/app.jar\n" if name == APP else (
            "COPY " + " ".join(p for f in release.FILES for p in (f, f + ".gz")) + " /usr/share/nginx/html/\n"
            + "RUN rm -f " + " ".join("/usr/share/nginx/html/" + f + ".br" for f in release.FILES) + "\n")
        (directory / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\n" + copy)
        core.run("docker", "build", "--pull=false", "-t", release.IMAGES[name], str(directory))
    core.write_json(ROOT / "prepared.json", {
        "sourceImages": {n: before[n]["Image"] for n in before}, "sourceJarHash": core.sha(old),
        "jarHash": core.sha((folder / "app.jar").read_bytes()), "files": hashes,
        "classes": {n: core.sha(v) for n, v in entries.items()}, "nativeCurrencyOnly": True,
        "unrelatedEntriesPreserved": True})
    print("Native-currency-only exports prepared over exact active artifacts.", flush=True)

def layout():
    assert not (ROOT / "frontend-layout.json").exists()
    before = {n: core.inspect(n) for n in release.IMAGES}
    assert all(before[n]["Config"]["Image"] == release.IMAGES[n] for n in before)
    prepared = core.read_json("prepared.json")
    files = ("index.html", "finance-workspace.css")
    folder = ROOT / "layout-input"
    hashes = {}
    for file in files:
        assert core.sha(core.run("docker", "exec", FRONT, "cat", "/usr/share/nginx/html/" + file)) == prepared["files"][file]
        value = (folder / file).read_bytes()
        hashes[file] = core.sha(value)
        (folder / (file + ".gz")).write_bytes(gzip.compress(value, mtime=0))
        for name in (file, file + ".gz"):
            (folder / name).chmod(0o644)
    image = "ai-commerce-frontend:finance-fee-native-layout-20261104"
    guard = json.loads(core.run("python3", "/usr/local/lib/heyi/compact-release-image.py",
        "--prepare", before[FRONT]["Image"], image + "-base").decode())
    core.write_json(ROOT / "frontend-layout-guard.json", guard)
    (folder / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY "
        + " ".join(p for f in files for p in (f, f + ".gz")) + " /usr/share/nginx/html/\nRUN rm -f "
        + " ".join("/usr/share/nginx/html/" + f + ".br" for f in files) + "\n")
    core.run("docker", "build", "--pull=false", "-t", image, str(folder))
    try:
        release.storage.activate(before, FRONT, image)
        core.write_json(ROOT / "frontend-layout.json", {"image": image, "files": hashes, "backendRestarted": False})
        release.verify()
        assert core.inspect(APP)["Id"] == before[APP]["Id"]
        assert core.configuration(core.inspect(FRONT)) == core.configuration(before[FRONT])
    except BaseException:
        (ROOT / "frontend-layout.json").unlink(missing_ok=True)
        release.storage.activate(before, FRONT, release.IMAGES[FRONT])
        raise
    print("Wrapped toolbar alignment retained across background refreshes.", flush=True)


if __name__ == "__main__":
    {"build": build, "drain-deploy": release.drain_deploy, "verify": release.verify, "layout": layout}[sys.argv[2]]()
