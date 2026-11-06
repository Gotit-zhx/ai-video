"""Separate explicitly named pure-white options in the existing SKU policy."""
import importlib.util
import io
import json
import sys
import zipfile
from pathlib import Path

PREVIOUS = Path("/opt/heyi/releases/product-sku-naming-20261104")
spec = importlib.util.spec_from_file_location("naming", PREVIOUS / "release-product-sku-naming.py")
naming = importlib.util.module_from_spec(spec)
spec.loader.exec_module(naming)
core, ROOT, APP = naming.core, naming.ROOT, naming.APP
naming.IMAGE = "ai-commerce-platform:product-sku-pattern-option-20261104"
ENTRY = "BOOT-INF/classes/com/yingdao/aicommerce/product/application/ProductSkuNaming.class"


def build():
    qa = core.read_json("qa.json")
    assert qa["unitTests"] == 75
    before = core.inspect(APP)
    assert before["Config"]["Image"] == "ai-commerce-platform:product-sku-naming-20261104"
    old = core.run("docker", "exec", APP, "cat", "/app/app.jar")
    with zipfile.ZipFile(io.BytesIO(old)) as source:
        assert core.sha(source.read(ENTRY)) == qa["baselineClassHash"]
    core.write_json(ROOT / "baseline.json", {
        "appImageId": before["Image"], "jarHash": core.sha(old),
        "classes": {ENTRY: qa["baselineClassHash"]}})
    folder = ROOT / "app-image"
    folder.mkdir()
    value = (ROOT / "classes" / ENTRY.removeprefix("BOOT-INF/classes/")).read_bytes()
    with zipfile.ZipFile(io.BytesIO(old)) as source, zipfile.ZipFile(folder / "app.jar", "w") as target:
        target.comment = source.comment
        for item in source.infolist():
            target.writestr(item, value if item.filename == ENTRY else source.read(item.filename))
    with zipfile.ZipFile(io.BytesIO(old)) as source, zipfile.ZipFile(folder / "app.jar") as target:
        assert source.namelist() == target.namelist()
        assert all(source.read(n) == target.read(n) for n in source.namelist() if n != ENTRY)
    (folder / "app.jar").chmod(0o644)
    guard = json.loads(core.run("python3", "/usr/local/lib/heyi/compact-release-image.py",
        "--prepare", before["Image"], naming.IMAGE + "-base").decode())
    core.write_json(ROOT / "image-guard.json", guard)
    (folder / "Dockerfile").write_text("FROM " + guard["baseImage"] + "\nCOPY app.jar /app/app.jar\n")
    core.run("docker", "build", "--pull=false", "-t", naming.IMAGE, str(folder))
    core.write_json(ROOT / "prepared.json", {
        "sourceImageId": before["Image"], "sourceJarHash": core.sha(old),
        "jarHash": core.sha((folder / "app.jar").read_bytes()),
        "classes": {ENTRY: core.sha(value)}, "unrelatedEntriesPreserved": True})
    print("One SKU policy class prepared; all unrelated entries byte-identical.", flush=True)


if __name__ == "__main__":
    {"build": build, "drain-deploy": naming.drain_deploy, "verify": naming.verify}[sys.argv[2]]()
