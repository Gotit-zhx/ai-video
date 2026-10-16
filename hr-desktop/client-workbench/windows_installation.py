"""Stable installation identity; never change the existing account directories."""
import ctypes
from ctypes import wintypes

APP_ID = "cn.hoyee.bossworkbench.experimental"
RUNNING_MUTEX = "Local\\" + APP_ID + ".running"
SETUP_MUTEX = "Local\\" + APP_ID + ".setup"


class RunningGuard:
    """Keep Setup from replacing a live app; do not terminate other instances."""

    def __init__(self):
        self.api = ctypes.WinDLL("kernel32", use_last_error=True)
        self.api.CreateMutexW.argtypes = [wintypes.LPVOID, wintypes.BOOL, wintypes.LPCWSTR]
        self.api.CreateMutexW.restype = wintypes.HANDLE
        self.api.OpenMutexW.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.LPCWSTR]
        self.api.OpenMutexW.restype = wintypes.HANDLE
        self.api.CloseHandle.argtypes = [wintypes.HANDLE]
        self.api.CloseHandle.restype = wintypes.BOOL
        self.handle = None

    def __enter__(self):
        self.handle = self.api.CreateMutexW(None, False, RUNNING_MUTEX)
        if not self.handle:
            raise OSError("Cannot protect the running installation")
        updating = self.api.OpenMutexW(0x00100000, False, SETUP_MUTEX)
        if updating:
            self.api.CloseHandle(updating)
            self.__exit__(None, None, None)
            raise RuntimeError("Ai boss 正在安装或更新，请完成后再启动。")
        if ctypes.get_last_error() != 2:  # ERROR_FILE_NOT_FOUND: no installer.
            self.__exit__(None, None, None)
            raise OSError("Cannot verify installation availability")
        return self

    def __exit__(self, *_):
        if self.handle:
            self.api.CloseHandle(self.handle)
            self.handle = None
