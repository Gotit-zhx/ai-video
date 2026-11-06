"""Compile only manifest-verified application files into a private installer."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.request

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[2] / "scripts/ci"))
from export_workbench import PIN, REQUIRED, digest, read_json, source_file
from windows_installation import APP_ID, RUNNING_MUTEX, SETUP_MUTEX

LANGUAGE_URL = ("https://raw.githubusercontent.com/jrsoftware/issrc/"
                "6ef32198ef1f7b7b375cd4b6b90896c2a58eb4c2/Files/Languages/ChineseSimplified.isl")
LANGUAGE_SHA256 = "e0b0b350e2245f3c5e65586dfe43d574f6e7f06f2261149aba284954b3fc9a8d"


def chinese_language(destination):
    # The compiler's stock install does not ship every upstream translation.
    # Pin the upstream 6.5+ translation; do not silently fall back to English.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(LANGUAGE_URL, timeout=30) as response:
        data = response.read(65537)
    if len(data) > 65536 or hashlib.sha256(data).hexdigest() != LANGUAGE_SHA256:
        raise ValueError("Pinned installer language differs")
    destination.write_bytes(data)


def payload(package):
    manifest_path = source_file(package, "heyi-package.json")
    manifest = read_json(manifest_path)
    files = manifest.get("files")
    if (manifest.get("schemaVersion") != 1 or manifest.get("experimental") is not True or
            manifest.get("realAccountTested") is not False or
            manifest.get("originalResourcesModified") is not False or
            not isinstance(files, dict) or not 1 <= len(files) <= 15000 or
            len({name.casefold() for name in files}) != len(files) or
            "heyi-package.json" in files or any(name not in files for name in REQUIRED)):
        raise ValueError("Verified candidate manifest required")
    for name, expected in PIN.items():
        if files.get("native-client/" + name) != expected:
            raise ValueError("Original application manifest differs")
    checked = []
    for name, expected in files.items():
        if (any(character in name for character in '\r\n"{}*?;') or
                not re.fullmatch(r"[a-f0-9]{64}", str(expected))):
            raise ValueError("Invalid installer payload entry")
        source = source_file(package, name)
        if digest(source) != expected:
            raise ValueError("Installer payload differs from manifest")
        checked.append((name, source, expected))
    checked.append(("heyi-package.json", manifest_path, digest(manifest_path)))
    return checked


def build(package, output, commit, compiler=None):
    if sys.platform != "win32":
        raise ValueError("Compile the installer on Windows")
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Exact source commit required")
    package = package.resolve(strict=True)
    output = output.absolute()
    if output.exists() or any((parent / ".git").exists() for parent in (output, *output.parents)):
        raise ValueError("Use a fresh output outside Git")
    entries = payload(package)
    compiler = compiler or shutil.which("ISCC.exe") or str(
        Path(os.environ.get("ProgramFiles(x86)", "")) / "Inno Setup 6/ISCC.exe")
    if not Path(compiler).is_file():
        raise ValueError("Inno Setup compiler required")
    version = read_json(HERE / "package.json")["version"]
    if not re.fullmatch(r"\d+\.\d+\.\d+", version):
        raise ValueError("Numeric application version required")
    output.mkdir(parents=True)
    stem = f"Ai-boss-Windows-Setup-{commit[:12]}"
    installer = output / (stem + ".exe")
    try:
        with tempfile.TemporaryDirectory(prefix="ai-boss-installer-") as temporary:
            staging = Path(temporary)
            language = staging / "ChineseSimplified.isl"
            chinese_language(language)
            definitions = {
                "AppIdentity": APP_ID, "RunningMutex": RUNNING_MUTEX, "SetupMutex": SETUP_MUTEX,
                "AppVersion": version, "OutputDirectory": str(output), "OutputName": stem,
                "BrandIcon": str(HERE / "assets/ai-boss.ico"),
                "ChineseLanguage": str(language),
            }
            preamble = "".join(f'#define {key} "{value.replace(chr(34), chr(34)*2)}"\n'
                               for key, value in definitions.items())
            (staging / "setup.iss").write_text(
                preamble + (HERE / "windows-installer.iss").read_text(encoding="utf-8"), encoding="utf-8-sig")
            lines = []
            for name, source, _ in entries:
                directory = str(Path(name).parent).replace("/", "\\")
                target = "{app}" + ("\\" + directory if directory != "." else "")
                escaped_source = str(source).replace('"', '""')
                lines.append(f'Source: "{escaped_source}"; DestDir: "{target}"; Flags: ignoreversion')
            (staging / "payload.iss").write_text("\n".join(lines), encoding="utf-8-sig")
            result = subprocess.run([compiler, "/Qp", str(staging / "setup.iss")],
                                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                    errors="replace", timeout=300)
            if result.returncode:
                # Compiler errors only; never export package contents or profiles.
                raise RuntimeError("Installer compilation failed:\n" + result.stdout[-3000:])
        for _, source, expected in entries:
            if digest(source) != expected:
                raise ValueError("Application changed during installer compilation")
        report = {"schemaVersion": 1, "commit": commit, "installer": installer.name,
                  "sha256": digest(installer), "bytes": installer.stat().st_size,
                  "packageManifestSha256": digest(package / "heyi-package.json"),
                  "manifestFiles": len(entries) - 1, "perUser": True,
                  "languageSha256": LANGUAGE_SHA256,
                  "profileFilesIncluded": False, "realAccountTested": False}
        (output / "installer.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        return report
    except BaseException:
        installer.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--compiler")
    args = parser.parse_args()
    print(json.dumps(build(args.package, args.output, args.commit, args.compiler)))
