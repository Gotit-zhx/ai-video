"""Verify offline smoke evidence before writing backup-CI artifact checksums."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

root = Path(__file__).resolve().parents[2]
desktop = root / "hr-desktop"
release = desktop / "release"
smoke = json.loads((desktop / "test-results/packaged/smoke.json").read_text(encoding="utf-8"))
if not smoke.get("ok") or smoke.get("platform") != "win32" or not smoke.get("packaged"):
    raise SystemExit("Packaged smoke did not pass")
commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=root, text=True).strip()
expected = os.environ.get("CIRCLE_SHA1")
if expected and expected != commit:
    raise SystemExit("Checked-out commit differs from the requested pipeline")
version = json.loads((desktop / "package.json").read_text(encoding="utf-8"))["version"]
installers = list(release.glob(f"Heyi-Recruiter-{version}-windows-x64-Setup.exe"))
if len(installers) != 1:
    raise SystemExit("Expected exactly one Windows installer")
manifest = {
    "commit": commit, "version": version, "os": "Windows", "arch": "X64",
    "provider": "circleci", "buildUrl": os.environ.get("CIRCLE_BUILD_URL"),
    "artifacts": {},
}
for file in installers:
    digest = hashlib.sha256()
    with file.open("rb") as stream:
        for chunk in iter(lambda: stream.read(4 * 1024 * 1024), b""):
            digest.update(chunk)
    manifest["artifacts"][file.name] = {"sha256": digest.hexdigest(), "size": file.stat().st_size}
(release / "verified-Windows.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
with zipfile.ZipFile(release / "verified-Windows-qa.zip", "w", zipfile.ZIP_DEFLATED) as archive:
    for file in (desktop / "test-results").rglob("*"):
        if file.is_file():
            archive.write(file, file.relative_to(desktop / "test-results"))
print("Verified installer manifest and offline QA archive generated")
