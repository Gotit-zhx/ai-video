"""Own windowless entry point. Vendor binaries and signatures stay intact."""
import ctypes
import os
from pathlib import Path
import subprocess
import sys
import uuid
from windows_installation import RunningGuard
from windows_startup import StartupWindow, supervise


def self_test_requested(arguments, environment):
    if not arguments:
        return False
    if arguments != ["--self-test"] or not all(environment.get(key) for key in (
            "HEYI_NATIVE_PROBE_ROOT", "HEYI_WORKBENCH_TEST_DATA", "HEYI_WORKBENCH_TEST_OUTPUT"
    )) or environment.get("HEYI_WORKBENCH_TEST_PORT") != "0":
        raise ValueError("Isolated workbench self-test required")
    return True


def main():
    if sys.platform != "win32":
        raise RuntimeError("Windows workbench entry requires Windows")
    frozen = getattr(sys, "frozen", False)
    root = Path(sys.executable).resolve().parent if frozen else Path(sys.argv[1]).resolve()
    self_test = self_test_requested(sys.argv[1:] if frozen else sys.argv[2:], os.environ)
    if self_test:
        report_file = Path(os.environ["HEYI_WORKBENCH_TEST_OUTPUT"])
        if report_file.exists() or report_file.is_symlink():
            raise ValueError("A fresh test output is required")
    executable = root / "native-client" / "boss-zhipin.exe"
    controller = root / "native-client" / "resources" / "heyi" / "windows-launcher.cjs"
    environment = {key: value for key, value in os.environ.items()
                   if key.upper() not in {"NODE_OPTIONS", "NODE_PATH", "ELECTRON_RUN_AS_NODE"}}
    environment["ELECTRON_RUN_AS_NODE"] = "1"
    with RunningGuard() as guard:
        if guard.already_running:
            guard.activate()
            return 0
        launch_id = uuid.uuid4().hex
        environment["HEYI_LAUNCH_ID"] = launch_id
        profile_root = Path(os.environ["HEYI_WORKBENCH_TEST_DATA"] if self_test else
                            Path(os.environ["LOCALAPPDATA"]) / "HeyiBossWorkbench")
        ui = StartupWindow(root / "native-client/resources/heyi/assets/ai-boss.ico")
        try:
            process = subprocess.Popen([str(executable), str(controller), str(root)] +
                                       (["--self-test"] if self_test else []),
                                       cwd=root, env=environment, stdin=subprocess.DEVNULL,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                       creationflags=subprocess.CREATE_NO_WINDOW)
        except Exception:
            ui.close()
            raise
        returncode, startup = supervise(process, guard, profile_root, launch_id, ui)
    if self_test:
        # Mark only a report returned by the controller. CI also requires this
        # exact windowless entry process to exit successfully.
        import json
        if report_file.is_file():
            report = json.loads(report_file.read_text(encoding="utf-8"))
            report["packagedEntryPoint"] = bool(frozen)
            report["entryControllerExitCode"] = returncode
            report["entryStartup"] = startup
            report_file.write_text(json.dumps(report, indent=2), encoding="utf-8")
    elif returncode:
        ctypes.windll.user32.MessageBoxW(
            None, "工作台启动未完成。请查看本机 HeyiBossWorkbench 目录中的启动状态，或联系管理员。",
            "Ai boss", 0x10)
    return returncode


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        if sys.platform == "win32" and "--self-test" not in sys.argv[1:]:
            message = str(error) if isinstance(error, RuntimeError) else "工作台启动未完成，请检查安装文件。"
            ctypes.windll.user32.MessageBoxW(None, message,
                                             "Ai boss", 0x10)
        raise SystemExit(1)
