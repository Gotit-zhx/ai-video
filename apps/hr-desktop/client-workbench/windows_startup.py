"""Branded startup and activation for our exact native child, never other apps."""
import ctypes
from ctypes import wintypes
import json
import time


STAGES = {
    "verify-package": "正在检查安装文件…",
    "profile-isolation": "正在读取已有登录资料…",
    "original-startup": "正在启动客户端，请稍候…",
    "original-readiness": "正在准备客户端…",
    "install-workbench": "正在连接 Ai boss 工作台…",
    "detach-inspector": "正在完成启动…",
    "ready": "启动完成",
}


def read_current_status(file, launch_id):
    try:
        if file.stat().st_size > 32768:
            return {}
        status = json.loads(file.read_text(encoding="utf-8"))
        return status if isinstance(status, dict) and status.get("launchId") == launch_id else {}
    except (OSError, ValueError, TypeError):
        return {}


class StartupWindow:
    """Keep native startup windows hidden until our integrated shell is ready.

    The UI loop and WinEvent callback stay on this entry's main thread.
    PID plus HWND ownership is verified before every hide/foreground action.
    No process is killed, no profile is reset, and no account data is read.
    """
    def __init__(self, icon):
        import tkinter as tk
        from tkinter import ttk
        from windows_installation import APP_ID
        shell = ctypes.WinDLL("shell32")
        shell.SetCurrentProcessExplicitAppUserModelID.argtypes = [wintypes.LPCWSTR]
        shell.SetCurrentProcessExplicitAppUserModelID.restype = wintypes.LONG
        shell.SetCurrentProcessExplicitAppUserModelID(APP_ID)
        self.user = ctypes.WinDLL("user32", use_last_error=True)
        self.callback_type = ctypes.WINFUNCTYPE(
            None, wintypes.HANDLE, wintypes.DWORD, wintypes.HWND,
            wintypes.LONG, wintypes.LONG, wintypes.DWORD, wintypes.DWORD)
        self.enum_type = ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
        signatures = {
            "GetWindowThreadProcessId": ([wintypes.HWND, ctypes.POINTER(wintypes.DWORD)], wintypes.DWORD),
            "ShowWindow": ([wintypes.HWND, ctypes.c_int], wintypes.BOOL),
            "SetForegroundWindow": ([wintypes.HWND], wintypes.BOOL),
            "IsWindow": ([wintypes.HWND], wintypes.BOOL),
            "IsWindowVisible": ([wintypes.HWND], wintypes.BOOL),
            "IsIconic": ([wintypes.HWND], wintypes.BOOL),
            "GetClassNameW": ([wintypes.HWND, wintypes.LPWSTR, ctypes.c_int], ctypes.c_int),
            "EnumWindows": ([self.enum_type, wintypes.LPARAM], wintypes.BOOL),
            "SetWinEventHook": ([wintypes.DWORD, wintypes.DWORD, wintypes.HMODULE,
                                self.callback_type, wintypes.DWORD, wintypes.DWORD, wintypes.DWORD],
                               wintypes.HANDLE),
            "UnhookWinEvent": ([wintypes.HANDLE], wintypes.BOOL),
        }
        for name, (arguments, result) in signatures.items():
            function = getattr(self.user, name)
            function.argtypes, function.restype = arguments, result
        self.pid = None
        self.ready = False
        self.native_window_seen = False
        self.hidden_count = 0
        self.activation_count = 0
        self.observed_classes = set()
        self.enumerations = 0
        self.enumeration_errors = set()
        self.window = tk.Tk()
        self.window.title("Ai boss")
        self.window.configure(background="#ffffff")
        self.window.resizable(False, False)
        self.window.iconbitmap(default=str(icon))
        width, height = 430, 190
        x = (self.window.winfo_screenwidth() - width) // 2
        y = (self.window.winfo_screenheight() - height) // 2
        self.window.geometry(f"{width}x{height}+{x}+{y}")
        tk.Label(self.window, text="Ai boss", background="#ffffff", foreground="#111111",
                 font=("Segoe UI", 24, "bold")).pack(pady=(25, 10))
        self.message = tk.StringVar(value=STAGES["verify-package"])
        tk.Label(self.window, textvariable=self.message, background="#ffffff",
                 foreground="#555555", font=("Microsoft YaHei UI", 10)).pack()
        self.bar = ttk.Progressbar(self.window, mode="indeterminate", length=340)
        self.bar.pack(pady=18)
        self.bar.start(20)
        self.window.protocol("WM_DELETE_WINDOW", self.window.withdraw)
        self.callback = self.callback_type(self._shown)
        # OUTOFCONTEXT: no DLL injection. Ignore all windows outside our PID.
        self.hook = self.user.SetWinEventHook(0x8002, 0x8002, None, self.callback, 0, 0, 0)
        if not self.hook:
            self.window.destroy()
            raise OSError("Cannot protect the startup window")
        self.window.update()

    def _owned(self, hwnd):
        pid = wintypes.DWORD()
        return bool(self.pid and self.user.IsWindow(hwnd)
                    and self.user.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
                    and pid.value == self.pid)

    def _hide(self, hwnd, top_level=False):
        if not self.ready and self._owned(hwnd):
            name = ctypes.create_unicode_buffer(128)
            self.user.GetClassNameW(hwnd, name, len(name))
            # The numeric suffix is Chromium's window-class registration
            # counter, not a visibility/readiness contract. Only enumeration
            # of this child's top-level windows may mark readiness: EnumWindows
            # excludes Chromium's message-only helper windows.
            if top_level and len(self.observed_classes) < 32:
                self.observed_classes.add(name.value)
            if top_level and name.value in {"Chrome_WidgetWin_0", "Chrome_WidgetWin_1"}:
                self.native_window_seen = True
            if self.user.IsWindowVisible(hwnd):
                self.user.ShowWindow(hwnd, 0)
                self.hidden_count += 1

    def _shown(self, _hook, _event, hwnd, object_id, child_id, _thread, _time):
        if object_id == 0 and child_id == 0:
            self._hide(hwnd)

    def foreground(self, status):
        try:
            hwnd = int(status.get("windowHandle", "0"))
        except (ValueError, TypeError):
            return False
        if not self._owned(hwnd):
            return False
        self.user.ShowWindow(hwnd, 9 if self.user.IsIconic(hwnd) else 5)
        if not self.user.IsWindowVisible(hwnd):
            return False
        self.user.SetForegroundWindow(hwnd)
        self.activation_count += 1
        return True

    def tick(self, status, window_status, activate=False):
        pid = status.get("nativePid")
        if isinstance(pid, int) and pid > 0:
            self.pid = pid
        ready = status.get("stage") == "ready" and status.get("ok") is True
        if not self.ready and ready:
            # Stop intercepting SHOW before bringing up the final integrated HWND.
            self.ready = True
            if self.foreground(window_status):
                self.bar.stop()
                self.window.withdraw()
            else:
                self.ready = False
        if not self.ready:
            self.message.set(STAGES.get(status.get("stage"), "正在准备 Ai boss…"))
            if self.pid:
                callback = self.enum_type(lambda hwnd, _param: (self._hide(hwnd, top_level=True), True)[1])
                self.enumerations += 1
                if not self.user.EnumWindows(callback, 0):
                    self.enumeration_errors.add(ctypes.get_last_error())
        if activate:
            if self.ready:
                self.foreground(window_status)
            else:
                self.window.deiconify()
                self.window.lift()
        self.window.update()

    def close(self):
        if self.hook:
            self.user.UnhookWinEvent(self.hook)
            self.hook = None
        self.window.destroy()


def supervise(process, guard, profile_root, launch_id, ui):
    status_file = profile_root / "heyi-launcher-status.json"
    window_file = profile_root / "heyi-window-status.json"
    status = window_status = {}
    acknowledged = native_observed = False
    try:
        while process.poll() is None:
            activate = guard.activation_requested()
            if not ui.ready or activate:
                status = read_current_status(status_file, launch_id)
                window_status = read_current_status(window_file, launch_id)
            ui.tick(status, window_status, activate)
            if ui.native_window_seen and not native_observed:
                temporary = profile_root / "heyi-native-window-ready.json.tmp"
                temporary.write_text(json.dumps({
                    "launchId": launch_id, "nativePid": ui.pid, "ready": True,
                }), encoding="utf-8")
                temporary.replace(profile_root / "heyi-native-window-ready.json")
                native_observed = True
            if ui.ready and not acknowledged:
                temporary = profile_root / "heyi-entry-status.json.tmp"
                temporary.write_text(json.dumps({"launchId": launch_id, "ready": True}), encoding="utf-8")
                temporary.replace(profile_root / "heyi-entry-status.json")
                acknowledged = True
            time.sleep(.25 if ui.ready else .04)
        final_status = read_current_status(status_file, launch_id)
        result = {
            "startupWindow": True, "handoffCompleted": ui.ready,
            "nativeWindowReadinessObserved": native_observed,
            "nativeWindowsHeld": ui.hidden_count, "activations": ui.activation_count,
            "nativePidObserved": bool(ui.pid), "windowEnumerations": ui.enumerations,
            "ownedTopLevelClasses": sorted(ui.observed_classes),
            "windowEnumerationErrors": sorted(ui.enumeration_errors),
            "lastStage": final_status.get("stage"),
            "waitingFor": final_status.get("waitingFor"),
        }
        # Preserve bounded startup evidence for normal launches too. This
        # contains window classes/counts and fixed stage names, never cookies,
        # page titles, profile contents or vendor stdout.
        temporary = profile_root / "heyi-startup-diagnostic.json.tmp"
        try:
            temporary.write_text(json.dumps({"launchId": launch_id,
                "controllerExitCode": process.returncode, **result}), encoding="utf-8")
            temporary.replace(profile_root / "heyi-startup-diagnostic.json")
        except OSError:
            pass
        return process.returncode, result
    finally:
        ui.close()
