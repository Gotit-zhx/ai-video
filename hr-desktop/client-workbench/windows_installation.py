"""Stable installation identity; never change the existing account directories."""
import ctypes
from ctypes import wintypes

APP_ID = "cn.hoyee.bossworkbench.experimental"
RUNNING_MUTEX = "Local\\" + APP_ID + ".running"
SETUP_MUTEX = "Local\\" + APP_ID + ".setup"
ACTIVATE_EVENT = "Local\\" + APP_ID + ".activate"


class RunningGuard:
    """One entry process owns the installation; later clicks only wake it."""

    def __init__(self):
        self.api = ctypes.WinDLL("kernel32", use_last_error=True)
        self.api.CreateMutexW.argtypes = [wintypes.LPVOID, wintypes.BOOL, wintypes.LPCWSTR]
        self.api.CreateMutexW.restype = wintypes.HANDLE
        self.api.OpenMutexW.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.LPCWSTR]
        self.api.OpenMutexW.restype = wintypes.HANDLE
        self.api.CloseHandle.argtypes = [wintypes.HANDLE]
        self.api.CloseHandle.restype = wintypes.BOOL
        self.api.CreateEventW.argtypes = [wintypes.LPVOID, wintypes.BOOL, wintypes.BOOL, wintypes.LPCWSTR]
        self.api.CreateEventW.restype = wintypes.HANDLE
        self.api.SetEvent.argtypes = [wintypes.HANDLE]
        self.api.SetEvent.restype = wintypes.BOOL
        self.api.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        self.api.WaitForSingleObject.restype = wintypes.DWORD
        self.handle = None
        self.activation = None
        self.already_running = False

    def __enter__(self):
        self.handle = self.api.CreateMutexW(None, False, RUNNING_MUTEX)
        if not self.handle:
            raise OSError("Cannot protect the running installation")
        # Read immediately: OpenMutexW below overwrites this thread-local code.
        self.already_running = ctypes.get_last_error() == 183  # ERROR_ALREADY_EXISTS
        updating = self.api.OpenMutexW(0x00100000, False, SETUP_MUTEX)
        if updating:
            self.api.CloseHandle(updating)
            self.__exit__(None, None, None)
            raise RuntimeError("Ai boss 正在安装或更新，请完成后再启动。")
        if ctypes.get_last_error() != 2:  # ERROR_FILE_NOT_FOUND: no installer.
            self.__exit__(None, None, None)
            raise OSError("Cannot verify installation availability")
        self.activation = self.api.CreateEventW(None, False, False, ACTIVATE_EVENT)
        if not self.activation:
            self.__exit__(None, None, None)
            raise OSError("Cannot create the application activation event")
        return self

    def activate(self):
        if not self.api.SetEvent(self.activation):
            raise OSError("Cannot wake the running application")

    def activation_requested(self):
        return self.api.WaitForSingleObject(self.activation, 0) == 0

    def __exit__(self, *_):
        if self.activation:
            self.api.CloseHandle(self.activation)
            self.activation = None
        if self.handle:
            self.api.CloseHandle(self.handle)
            self.handle = None
