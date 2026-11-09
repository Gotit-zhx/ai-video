"""Shared deployment headroom; transfer and extraction budgets are additional."""
import shutil

GIB = 1024 ** 3
RESERVE_BYTES = 10 * GIB


def require_space(path, temporary_bytes):
    if type(temporary_bytes) is not int or temporary_bytes < 0:
        raise ValueError("Invalid temporary space budget")
    if shutil.disk_usage(path).free < RESERVE_BYTES + temporary_bytes:
        raise RuntimeError("Insufficient temporary space plus 10 GiB reserve")
