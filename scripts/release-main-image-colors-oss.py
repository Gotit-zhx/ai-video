"""Reuse exact-image overlay safeguards for color slots, OSS archives and cover ordering."""
import importlib.util
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve()
library = HERE.with_name("release-core.py")
if not library.exists():
    library = HERE.with_name("release-banana-default.py")
spec = importlib.util.spec_from_file_location("image_overlay", library)
core = importlib.util.module_from_spec(spec)
spec.loader.exec_module(core)
core.TAG = "main-image-colors-oss-20261019"
core.BASE_COMMIT = "62333e7"
core.RELEASE_ENTRY = HERE
core.RUNTIME_FILES = ("server.js", "product-main-image-style.js", "image-operation-store.js")
core.PREFIXES = ["com/yingdao/aicommerce/" + name for name in (
    "product/application/ProductMainImageStylePolicy",
    "product/application/ProductMainImageAssets",
    "product/application/CommerceImageArchive",
    "product/application/CommerceDesignImageGateway",
    "product/application/CommerceImageOssMaintenance",
    "storage/OssStorageService")]
core.NEW_PREFIXES = {"com/yingdao/aicommerce/product/application/CommerceImageOssMaintenance"}
core.TESTS += ["product-main-image-style.test.mjs"]

if __name__ == "__main__":
    try:
        {"pack": core.pack, "build": core.build, "deploy": core.deploy,
         "verify": core.verify, "idle": core.idle}[core.MODE]()
    except subprocess.CalledProcessError as error:
        path = core.ROOT / ("failure-" + core.MODE + ".log")
        path.write_bytes(error.output or b"")
        path.chmod(0o600)
        print("Command failed; private diagnostics: " + str(path), file=sys.stderr)
        raise SystemExit(1)
