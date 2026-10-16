"""Export a verified, anonymous Windows candidate for a private repository only.

Only the build manifest's files are copied. Profiles, logs and the downloaded
original installer are deliberately outside this allowlist.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import zipfile

PIN = json.loads((Path(__file__).resolve().parents[2] /
                  "hr-desktop/client-workbench/windows-original.json").read_text(encoding="utf-8"))["files"]
BRAND = json.loads((Path(__file__).resolve().parents[2] /
                    "hr-desktop/client-workbench/assets/brand.json").read_text(encoding="utf-8"))
REQUIRED = (
    BRAND["windowsExecutable"],
    "native-client/resources/heyi/assets/brand.json",
    "native-client/resources/heyi/assets/ai-boss.png",
    "native-client/resources/heyi/assets/ai-boss-app.png",
    "native-client/resources/heyi/windows-launcher.cjs",
    "native-client/resources/heyi/windows-workbench.cjs",
    "native-client/resources/heyi-runtime/heyi-boss.exe",
)
CHECKS = (
    "ok", "actualOriginalWindows", "packagedEntryPoint", "singleVisibleWindow",
    "sidebarRoundTrip", "localFixturePaste", "loopbackBridge", "packagedWindowsWorker",
    "offlineNetworkRelay", "signedOutSnapshotRejected",
    "chatAction.genuineDeliveryFixture", "chatAction.singleInvocation",
    "chatAction.windowsAdapterPath", "chatAction.legacySenderUnused",
    "resumeAction.nativeHTTPExchangeFixture", "resumeAction.nativeOpeningCheck",
    "resumeAction.singleConfirmation", "resumeAction.windowsAdapterPath",
    "resumeAction.legacySenderUnused", "incomingActivity.incomingReplyAndAttachmentHints",
    "incomingActivity.observerRestored", "shutdown.nativeExitGuardInvoked",
    "shutdown.hostClosedBeforeNativeExit", "shutdown.platformSessionSaved",
)


def read_json(path):
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 3 * 1024 * 1024:
        raise ValueError("A bounded, ordinary JSON report is required")
    return json.loads(path.read_text(encoding="utf-8-sig"))


def field(value, name):
    for key in name.split("."):
        value = value.get(key) if isinstance(value, dict) else None
    return value


def source_file(package, name):
    parts = PurePosixPath(name).parts
    if (not isinstance(name, str) or not name or len(name) > 1024 or
            any(character in name for character in "\\:\0") or
            PurePosixPath(name).is_absolute() or not parts or
            name != "/".join(parts) or any(part in {".", ".."} for part in parts)):
        raise ValueError("Invalid package filename")
    source = package.joinpath(*parts)
    if any(parent.is_symlink() for parent in (source, *source.parents)):
        raise ValueError("Linked package files cannot be exported")
    if not source.is_file() or not source.resolve().is_relative_to(package.resolve()):
        raise ValueError("Package file missing or outside package")
    return source


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def export(root, output, event, commit):
    if event.get("repository", {}).get("private") is not True:
        raise ValueError("Private repository required; no public derived app artifact")
    if not re.fullmatch(r"[a-f0-9]{40}", commit):
        raise ValueError("Exact source commit required")
    package = root / "workbench"
    manifest_file = source_file(package, "heyi-package.json")
    manifest = read_json(manifest_file)
    files = manifest.get("files")
    if (manifest.get("schemaVersion") != 1 or manifest.get("experimental") is not True or
            manifest.get("realAccountTested") is not False or
            manifest.get("originalResourcesModified") is not False or not isinstance(files, dict) or
            not 1 <= len(files) <= 15000 or "heyi-package.json" in files or
            len({name.casefold() for name in files}) != len(files)):
        raise ValueError("Invalid candidate package manifest")
    for name in REQUIRED:
        if name not in files:
            raise ValueError("Candidate package is incomplete")
    for name, expected in PIN.items():
        if files.get("native-client/" + name) != expected:
            raise ValueError("Pinned original manifest differs")
    smoke = read_json(root / "windows-workbench-smoke.json")
    if (any(field(smoke, name) is not True for name in CHECKS) or
            any(smoke.get(name) != 0 for name in ("originalExitCode", "entryControllerExitCode",
                                                 "candidateMessagesSent")) or
            smoke.get("actualBossNetworkUsed") is not False or
            field(smoke, "shutdown.realAccountUsed") is not False or
            field(smoke, "incomingActivity.additionalBossRequests") != 0 or
            smoke.get("timedOut") is not False or smoke.get("terminationRequested") is not False):
        raise ValueError("Anonymous packaged Windows checks must pass before export")
    probe = read_json(root / "ci-result.json")
    checked = []
    for name, expected in files.items():
        if not isinstance(expected, str) or not re.fullmatch(r"[a-f0-9]{64}", expected):
            raise ValueError("Invalid package hash")
        checked.append((name, source_file(package, name), expected))
    checked.append(("heyi-package.json", manifest_file, digest(manifest_file)))
    output.mkdir(parents=True, exist_ok=False)
    archive = output / f"HeyiWorkbench-Windows-candidate-{commit[:12]}.zip"
    try:
        with zipfile.ZipFile(archive, "x", zipfile.ZIP_DEFLATED, compresslevel=6) as target:
            for name, source, expected in checked:
                actual = hashlib.sha256()
                with source.open("rb") as stream, target.open("HeyiWorkbench-Windows/" + name, "w") as entry:
                    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                        actual.update(chunk)
                        entry.write(chunk)
                if actual.hexdigest() != expected:
                    raise ValueError("Package file changed or differs from manifest")
            target.writestr("使用说明.txt",
                "Ai boss · Windows 候选验证版\n\n"
                "请完整解压，运行 HeyiWorkbench-Windows 文件夹中的「Ai boss.exe」。\n"
                "不要单独移动 EXE；它需要同目录的程序文件。\n"
                "更新时先正常退出旧工作台，不要退出 BOSS 或赫逸账号，也不要删除登录资料目录。\n"
                "本包通过了隔离整包检查，真实账号的完整招聘流程仍待验收。\n"
                "仅用于已授权的内部验证，请勿公开分发。\n")
        report = {"commit": commit, "archive": archive.name, "sha256": digest(archive),
                  "bytes": archive.stat().st_size, "manifestFiles": len(files),
                  "packagedChecksPassed": True, "realAccountTested": False,
                  "overallProbePassed": probe.get("ok") is True,
                  "checks": {name: field(smoke, name) for name in CHECKS},
                  "profileFilesIncluded": False}
        (output / "verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        return report
    except BaseException:
        archive.unlink(missing_ok=True)
        raise


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--event", type=Path, required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    print(json.dumps(export(args.root, args.output, read_json(args.event), args.commit)))
