"""Open one Feikua shop through its dynamically assigned Selenium/CDP session."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import http.client
import http.server
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import threading
import time
import traceback
import urllib.error
import urllib.parse
import urllib.request
import zipfile
from collections.abc import Callable
from contextlib import contextmanager
from pathlib import Path

from selenium import webdriver
from selenium.webdriver.chrome.options import Options
from selenium.webdriver.chrome.service import Service
from selenium.webdriver.common.by import By
from selenium.webdriver.common.action_chains import ActionChains
from selenium.common.exceptions import (
    ElementNotInteractableException,
    StaleElementReferenceException,
    TimeoutException,
    WebDriverException,
)
from selenium.webdriver.support.ui import WebDriverWait
from websocket import WebSocketTimeoutException, create_connection

from tiktok_ad_costs import (
    AdsCostExportError,
    AdsCostLoginCredentialsRejected,
    AdsCostLoginOtpRequired,
    AdsCostStoreDisabled,
    AdsCostVerificationRequired,
    _ads_login_otp_visible,
    export_all_ad_cost_accounts,
    store_disabled_reason,
)


SKILL_DIR = Path(__file__).resolve().parent.parent
RUNTIME_DIR = Path(
    os.environ.get("TK_BILL_RUNTIME_DIR", str(Path.home() / "AppData" / "Local" / "tk-bill"))
)
DRIVERS_DIR = RUNTIME_DIR / "drivers"
DRIVERS_DIR = Path(
    os.environ.get("YUE_BILL_DRIVER_CACHE_DIR", str(DRIVERS_DIR))
)
BUILTIN_DRIVERS_DIR = SKILL_DIR / "drivers"
PORTS = range(10039, 11002, 13)
DEFAULT_OUTPUT_DIR = RUNTIME_DIR / "downloads"
STORE_STOP_WAIT_SECONDS = 45
STORE_URL_READY_WAIT_SECONDS = 120
STORE_START_MAX_REQUESTS = 5
STORE_START_RECOVERY_WAIT_SECONDS = 30
SELLER_CENTER_HOST_RE = re.compile(r"seller-[a-z]{2}\.tiktok\.com")
CROSS_BORDER_SELLER_CENTER_HOST = "seller.tiktokshopglobalselling.com"
SELLER_CENTER_PROFILE = os.environ.get(
    "TK_BILL_SELLER_CENTER_PROFILE", "regional"
).strip().casefold()
if SELLER_CENTER_PROFILE not in {"regional", "cross-border"}:
    raise RuntimeError(
        "TK_BILL_SELLER_CENTER_PROFILE must be 'regional' or 'cross-border'."
    )
ADVERTISEMENT_MODAL_KEYWORDS = (
    "advertisement", "campaign", "gmv max", "promotion", "voucher",
    "广告", "活动", "推广", "优惠券", "补贴", "โปรโมชั่น", "โฆษณา", "คูปอง", "แคมเปญ",
)
SAFE_ADVERTISEMENT_DISMISS_LABELS = {
    "close", "cancel", "not now", "skip", "later", "got it",
    "关闭", "取消", "暂不", "稍后", "跳过", "知道了", "好的",
    "ปิด", "ยกเลิก", "ภายหลัง", "ข้าม", "เข้าใจแล้ว",
}
SECURITY_MODAL_KEYWORDS = (
    "captcha", "challenge", "security", "verify", "verification", "login", "password",
    "验证码", "验证", "安全", "登录", "密码", "人机",
    "ยืนยัน", "รหัสผ่าน", "ความปลอดภัย", "แคปชา",
)


class NoDataForPeriodError(RuntimeError):
    """Raised when a submitted range yields no report row within the no-data window."""
SESSION_FILE = Path(
    os.environ.get("YUE_BILL_SESSION_FILE", str(RUNTIME_DIR / "sessions.json"))
)
SESSION_LOCK_PATH = Path(
    os.environ.get(
        "YUE_BILL_SESSION_LOCK_PATH",
        str(SESSION_FILE.with_name(f"{SESSION_FILE.name}.lock")),
    )
)
PARALLEL_MODE = os.environ.get("YUE_BILL_PARALLEL_MODE", "").strip() == "1"
FEIKUA_LOCK_PATH = Path(
    os.environ.get(
        "YUE_BILL_FEIKUA_LOCK_PATH",
        str(RUNTIME_DIR / "feikua-lifecycle.lock"),
    )
)
FEIKUA_START_ATTRIBUTION_LOCK_PATH = Path(
    os.environ.get(
        "YUE_BILL_FEIKUA_START_ATTRIBUTION_LOCK_PATH",
        str(FEIKUA_LOCK_PATH.with_name("feikua-start-attribution.lock")),
    )
)
FEIKUA_STOP_GENERATION_DIR = FEIKUA_LOCK_PATH.with_name(
    "feikua-stop-generations"
)
DRIVER_CACHE_LOCK_PATH = DRIVERS_DIR.parent / "chromedriver-cache.lock"
_LIFECYCLE_LOCK_DEPTH = 0
_START_ATTRIBUTION_LOCK_DEPTH = 0


@contextmanager
def _interprocess_file_lock(path: Path):
    """Lock one byte in a dedicated cross-process state lock file."""
    import msvcrt

    path.parent.mkdir(parents=True, exist_ok=True)
    handle = path.open("a+b")
    try:
        handle.seek(0, os.SEEK_END)
        if handle.tell() == 0:
            handle.write(b"\0")
            handle.flush()
        while True:
            try:
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                break
            except OSError:
                time.sleep(0.05)
        try:
            yield
        finally:
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
    finally:
        handle.close()


def _atomic_write_text(path: Path, text: str) -> None:
    """Publish complete state so concurrent readers never observe partial JSON."""
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(
        f".{path.name}.{os.getpid()}.{threading.get_ident()}.tmp"
    )
    try:
        with temporary.open("w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        try:
            temporary.unlink()
        except FileNotFoundError:
            pass


def _stop_generation_paths(shop_id: str) -> tuple[Path, Path]:
    key = hashlib.sha256(shop_id.encode("utf-8")).hexdigest()
    return (
        FEIKUA_STOP_GENERATION_DIR / f"{key}.json",
        FEIKUA_STOP_GENERATION_DIR / f"{key}.lock",
    )


def read_stop_generation(shop_id: str) -> int:
    marker_path, lock_path = _stop_generation_paths(shop_id)
    with _interprocess_file_lock(lock_path):
        try:
            payload = json.loads(marker_path.read_text(encoding="utf-8"))
            return int(payload.get("generation", 0))
        except (OSError, ValueError, TypeError):
            return 0


def publish_stop_intent(shop_id: str) -> int:
    """Publish stop intent before waiting for the shared lifecycle API lock."""
    marker_path, lock_path = _stop_generation_paths(shop_id)
    with _interprocess_file_lock(lock_path):
        try:
            payload = json.loads(marker_path.read_text(encoding="utf-8"))
            generation = int(payload.get("generation", 0)) + 1
        except (OSError, ValueError, TypeError):
            generation = 1
        _atomic_write_text(
            marker_path,
            json.dumps(
                {
                    "generation": generation,
                    "pid": os.getpid(),
                    "updatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
                },
                ensure_ascii=False,
            ),
        )
        return generation


def _assert_start_not_stopped(shop_id: str, captured_generation: int) -> None:
    current_generation = read_stop_generation(shop_id)
    if current_generation != captured_generation:
        raise RuntimeError(
            "Feikua start was cancelled by a concurrent exact-store stop "
            f"(generation {captured_generation} -> {current_generation})."
        )


@contextmanager
def feikua_lifecycle_lock():
    """Serialize Feikua start/stop requests while browser work remains parallel."""
    global _LIFECYCLE_LOCK_DEPTH
    if not PARALLEL_MODE or _LIFECYCLE_LOCK_DEPTH > 0:
        _LIFECYCLE_LOCK_DEPTH += 1
        try:
            yield
        finally:
            _LIFECYCLE_LOCK_DEPTH -= 1
        return

    import msvcrt

    FEIKUA_LOCK_PATH.parent.mkdir(parents=True, exist_ok=True)
    handle = FEIKUA_LOCK_PATH.open("a+b")
    try:
        handle.seek(0, os.SEEK_END)
        if handle.tell() == 0:
            handle.write(b"\0")
            handle.flush()
        while True:
            try:
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                break
            except OSError:
                time.sleep(0.2)
        _LIFECYCLE_LOCK_DEPTH = 1
        try:
            yield
        finally:
            _LIFECYCLE_LOCK_DEPTH = 0
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
    finally:
        handle.close()


@contextmanager
def feikua_start_attribution_lock():
    """Keep one start/recovery sequence attributable without blocking stops."""
    global _START_ATTRIBUTION_LOCK_DEPTH
    if not PARALLEL_MODE or _START_ATTRIBUTION_LOCK_DEPTH > 0:
        _START_ATTRIBUTION_LOCK_DEPTH += 1
        try:
            yield
        finally:
            _START_ATTRIBUTION_LOCK_DEPTH -= 1
        return

    import msvcrt

    FEIKUA_START_ATTRIBUTION_LOCK_PATH.parent.mkdir(parents=True, exist_ok=True)
    handle = FEIKUA_START_ATTRIBUTION_LOCK_PATH.open("a+b")
    try:
        handle.seek(0, os.SEEK_END)
        if handle.tell() == 0:
            handle.write(b"\0")
            handle.flush()
        while True:
            try:
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                break
            except OSError:
                time.sleep(0.2)
        _START_ATTRIBUTION_LOCK_DEPTH = 1
        try:
            yield
        finally:
            _START_ATTRIBUTION_LOCK_DEPTH = 0
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
    finally:
        handle.close()


@contextmanager
def driver_cache_lock():
    """Serialize shared ChromeDriver downloads and atomic cache publication."""
    import msvcrt

    DRIVER_CACHE_LOCK_PATH.parent.mkdir(parents=True, exist_ok=True)
    handle = DRIVER_CACHE_LOCK_PATH.open("a+b")
    try:
        handle.seek(0, os.SEEK_END)
        if handle.tell() == 0:
            handle.write(b"\0")
            handle.flush()
        while True:
            try:
                handle.seek(0)
                msvcrt.locking(handle.fileno(), msvcrt.LK_NBLCK, 1)
                break
            except OSError:
                time.sleep(0.2)
        try:
            yield
        finally:
            handle.seek(0)
            msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
    finally:
        handle.close()


def request_json(url: str, *, method: str = "GET", data: dict | None = None, timeout: int = 30):
    normalized_method = method.upper()
    attempts = 4 if normalized_method == "GET" else 1
    for attempt in range(1, attempts + 1):
        body = None if data is None else json.dumps(data).encode("utf-8")
        request = urllib.request.Request(
            url,
            data=body,
            method=normalized_method,
            headers={"Content-Type": "application/json"} if data is not None else {},
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt >= attempts:
                raise
        except (
            OSError,
            TimeoutError,
            ValueError,
            http.client.HTTPException,
            urllib.error.URLError,
        ):
            if attempt >= attempts:
                raise
        time.sleep(min(2.0, 0.25 * (2 ** (attempt - 1))))
    raise RuntimeError(f"JSON request retry loop ended unexpectedly: {url}")


def parse_export_dates(parser: argparse.ArgumentParser, start: str | None, end: str | None) -> tuple[dt.date, dt.date]:
    """Require a valid export range before opening any shop session."""
    if not start or not end:
        parser.error(
            "both --start-date and --end-date are required (YYYY-MM-DD). "
            "Example: --start-date 2026-07-01 --end-date 2026-07-23"
        )
    try:
        start_date = dt.date.fromisoformat(start)
        end_date = dt.date.fromisoformat(end)
    except ValueError:
        parser.error("--start-date and --end-date must use YYYY-MM-DD format.")
    if end_date < start_date:
        parser.error("--end-date must be on or after --start-date.")
    if (end_date - start_date).days > 89:
        parser.error("the export date range may contain at most 90 days.")
    return start_date, end_date


def find_base_url() -> str:
    deadline = time.monotonic() + (30 if PARALLEL_MODE else 6)
    scan_number = 0
    while True:
        scan_number += 1
        for port in PORTS:
            base_url = f"http://127.0.0.1:{port}"
            try:
                payload = request_json(
                    f"{base_url}/api/external/webdriver/stores",
                    timeout=2,
                )
                if payload.get("code") == 0:
                    if scan_number > 1:
                        print(
                            f"FEIKUA_LOCAL_SERVICE_RECOVERED_AFTER_SCAN_{scan_number}",
                            flush=True,
                        )
                    return base_url
            except (
                OSError,
                ValueError,
                http.client.HTTPException,
                urllib.error.URLError,
                TimeoutError,
            ):
                continue
        if time.monotonic() >= deadline:
            break
        time.sleep(0.5)
    raise RuntimeError("Feikua local WebDriver service was not found. Start and sign in to Feikua first.")


def select_store(base_url: str, shop_name: str) -> dict:
    stores = request_json(f"{base_url}/api/external/webdriver/stores")["data"]["stores"]
    matches = [store for store in stores if store["shop_name"] == shop_name]
    if matches:
        return matches[0]
    prefix_matches = [
        store
        for store in stores
        if store["shop_name"].casefold().startswith(shop_name.casefold())
        and len(store["shop_name"]) > len(shop_name)
        and store["shop_name"][len(shop_name)] in "-（("
    ]
    if len(prefix_matches) == 1:
        print(
            "FEIKUA_UNIQUE_PREFIX_MATCH: "
            f"{shop_name} -> {prefix_matches[0]['shop_name']}",
            flush=True,
        )
        return prefix_matches[0]
    suggestions = [store["shop_name"] for store in stores if shop_name.lower() in store["shop_name"].lower()]
    hint = "\n".join(suggestions[:10]) or "(no partial matches)"
    raise RuntimeError(f"Exact shop name not found: {shop_name}\nPossible matches:\n{hint}")


def stop_other_running_stores(base_url: str, selected_shop_id: str) -> None:
    """Close other Feikua store windows before a new export session starts."""
    if PARALLEL_MODE:
        print(
            "FEIKUA_PARALLEL_OTHER_STORES_PRESERVED: "
            f"selected_shop_id={selected_shop_id}",
            flush=True,
        )
        return
    stores = request_json(f"{base_url}/api/external/webdriver/stores")["data"]["stores"]
    for store in stores:
        if store["shop_id"] == selected_shop_id or store.get("status") == "idle":
            continue
        print(f"Waiting for previous shop to close: {store['shop_name']}", flush=True)
        saved = load_sessions().get(store["shop_id"])
        previous_cdp_port = int(saved["debugger_port"]) if saved else None
        if wait_for_store_idle(
            base_url,
            store["shop_id"],
            previous_cdp_port,
            2,
            raise_on_timeout=False,
        ):
            continue
        stop_store(base_url, store["shop_id"])


def store_by_id(
    base_url: str,
    shop_id: str,
    *,
    timeout: int = 30,
) -> dict | None:
    stores = request_json(
        f"{base_url}/api/external/webdriver/stores",
        timeout=timeout,
    )["data"]["stores"]
    return next((store for store in stores if store["shop_id"] == shop_id), None)


def wait_for_store_idle(
    base_url: str,
    shop_id: str,
    previous_cdp_port: int | None,
    timeout: float = STORE_STOP_WAIT_SECONDS,
    *,
    raise_on_timeout: bool = True,
) -> bool:
    """Return immediately once Feikua and the exact previous browser both stop."""
    deadline = time.monotonic() + timeout
    last_status = "missing"
    while time.monotonic() < deadline:
        current = store_by_id(base_url, shop_id)
        last_status = str(current.get("status", "")) if current is not None else "missing"
        cdp_closed = previous_cdp_port is None or not cdp_is_alive(previous_cdp_port)
        # A transiently missing row is not proof that Feikua stopped the shop;
        # only the explicit idle state plus a closed saved CDP is terminal.
        if last_status == "idle" and cdp_closed:
            forget_session(shop_id)
            print("FEIKUA_STORE_IDLE_CONFIRMED", flush=True)
            return True
        time.sleep(0.2)
    if raise_on_timeout:
        raise RuntimeError(
            "Feikua stop returned, but the exact shop did not become idle within "
            f"{timeout:g} seconds (status={last_status!r}, cdp_port={previous_cdp_port!r})."
        )
    return False


def _start_retry_delay(request_number: int) -> float:
    """Bound retries while giving completed workers time to release memory."""
    return min(12.0, 1.5 * (2 ** max(0, request_number - 1)))


def _start_store_unlocked(
    base_url: str,
    store: dict,
    stop_generation: int,
) -> dict:
    request_number = 0
    missing_checks = 0
    last_start_error: urllib.error.HTTPError | None = None
    last_ports_before_start: set[int] = set()
    while request_number < STORE_START_MAX_REQUESTS:
        _assert_start_not_stopped(store["shop_id"], stop_generation)
        current = store_by_id(base_url, store["shop_id"])
        current_status = str(current.get("status", "")) if current is not None else "missing"
        if current_status == "missing":
            missing_checks += 1
            if missing_checks > STORE_START_MAX_REQUESTS:
                if last_start_error is not None:
                    raise last_start_error
                raise RuntimeError(
                    "The exact Feikua shop temporarily disappeared from the "
                    f"store list after {missing_checks - 1} checks."
                )
            delay = _start_retry_delay(missing_checks)
            print(
                "FEIKUA_START_STORE_TEMPORARILY_MISSING: "
                f"waiting {delay:g}s before exact-store recheck "
                f"{missing_checks}/{STORE_START_MAX_REQUESTS}...",
                flush=True,
            )
            time.sleep(delay)
            continue
        missing_checks = 0
        saved = load_sessions().get(store["shop_id"])
        saved_cdp_port = int(saved["debugger_port"]) if saved else None
        if (
            current_status == "idle"
            and saved_cdp_port is not None
            and cdp_is_alive(saved_cdp_port)
        ):
            raise RuntimeError(
                "Feikua reports the exact shop idle while its saved CDP is "
                f"still alive on port {saved_cdp_port}; a new start request "
                "was suppressed."
            )
        if current_status != "idle":
            if last_start_error is not None:
                running = discover_running_cdp_port(
                    store["shop_id"],
                    allowed_ports=(
                        set(listening_ports()) - last_ports_before_start
                    ),
                )
                if running is not None:
                    _assert_start_not_stopped(
                        store["shop_id"],
                        stop_generation,
                    )
                    print("FEIKUA_START_RECOVERED_AFTER_HTTP_500", flush=True)
                    return {
                        "debugger_port": running[0],
                        "core_version": running[1],
                    }
                raise RuntimeError(
                    "The exact Feikua shop remained active after HTTP 500, "
                    "but its new CDP session could not be attributed; another "
                    "start request was suppressed."
                ) from last_start_error
            # A multi-site workbook can schedule the same Feikua profile in
            # consecutive rows.  The prior export has already requested close,
            # but Feikua may still report "running" for a few seconds.  Wait
            # for that exact session instead of failing the next site.
            print(
                f"Waiting for the selected shop to finish closing (status={current_status})...",
                flush=True,
            )
            saved = load_sessions().get(store["shop_id"])
            previous_cdp_port = int(saved["debugger_port"]) if saved else None
            if not wait_for_store_idle(
                base_url,
                store["shop_id"],
                previous_cdp_port,
                STORE_STOP_WAIT_SECONDS,
                raise_on_timeout=False,
            ):
                stop_store(
                    base_url,
                    store["shop_id"],
                    publish_intent=False,
                )
            continue

        if last_start_error is not None:
            # A late browser can appear after Feikua has already reverted the
            # API status to idle. Attribute it before considering another POST.
            running = discover_running_cdp_port(
                store["shop_id"],
                allowed_ports=(
                    set(listening_ports()) - last_ports_before_start
                ),
            )
            if running is not None:
                _assert_start_not_stopped(
                    store["shop_id"],
                    stop_generation,
                )
                print("FEIKUA_START_RECOVERED_AFTER_HTTP_500", flush=True)
                return {
                    "debugger_port": running[0],
                    "core_version": running[1],
                }

        payload: dict | None = None
        start_error: urllib.error.HTTPError | None = None
        locked_status = "idle"
        ports_before_start: set[int] = set()
        # Only the lifecycle API call and its port baseline are serialized.
        # Recovery polling/backoff stays outside the lock so completed workers
        # can issue stop requests and release browser memory.
        with feikua_lifecycle_lock():
            _assert_start_not_stopped(store["shop_id"], stop_generation)
            locked_current = store_by_id(base_url, store["shop_id"])
            locked_status = (
                str(locked_current.get("status", ""))
                if locked_current is not None
                else "missing"
            )
            if locked_status == "idle":
                locked_saved = load_sessions().get(store["shop_id"])
                locked_saved_port = (
                    int(locked_saved["debugger_port"])
                    if locked_saved
                    else None
                )
                if (
                    locked_saved_port is not None
                    and cdp_is_alive(locked_saved_port)
                ):
                    locked_status = "idle_with_live_saved_cdp"
                else:
                    ports_before_start = set(listening_ports())
                    request_number += 1
                    try:
                        payload = request_json(
                            f"{base_url}/api/external/webdriver/stores/start",
                            method="POST",
                            data={"shop_id": store["shop_id"]},
                            timeout=120,
                        )
                    except urllib.error.HTTPError as error:
                        start_error = error

        if locked_status != "idle":
            if locked_status == "idle_with_live_saved_cdp":
                raise RuntimeError(
                    "Feikua reports the exact shop idle while its saved CDP "
                    "is still alive; a new start request was suppressed."
                )
            # Another lifecycle transition won the race after the pre-check.
            # Re-evaluate it outside the lock rather than sending a second start.
            time.sleep(0.5)
            continue

        if start_error is not None:
            error = start_error
            if error.code not in {400, 500}:
                raise error
            # Feikua can transiently return HTTP 400/500 while a lifecycle
            # transition is settling, including after it has already launched
            # the isolated browser. Recover that exact live CDP session before
            # deciding whether another start request is necessary.
            last_ports_before_start = set(ports_before_start)
            recovery_deadline = time.monotonic() + STORE_START_RECOVERY_WAIT_SECONDS
            while time.monotonic() < recovery_deadline:
                _assert_start_not_stopped(
                    store["shop_id"],
                    stop_generation,
                )
                try:
                    store_by_id(
                        base_url,
                        store["shop_id"],
                        timeout=2,
                    )
                except (
                    OSError,
                    TimeoutError,
                    http.client.HTTPException,
                    urllib.error.URLError,
                ):
                    # Feikua's local service can briefly stop answering while
                    # the isolated Chrome process it just launched is becoming
                    # ready. Keep recovering the exact start request instead of
                    # failing the task or submitting a second start immediately.
                    pass
                running = discover_running_cdp_port(
                    store["shop_id"],
                    allowed_ports=set(listening_ports()) - ports_before_start,
                )
                if running is not None:
                    _assert_start_not_stopped(
                        store["shop_id"],
                        stop_generation,
                    )
                    print(
                        f"FEIKUA_START_RECOVERED_AFTER_HTTP_{error.code}",
                        flush=True,
                    )
                    return {
                        "debugger_port": running[0],
                        "core_version": running[1],
                    }
                time.sleep(0.2)
            last_start_error = error
            if request_number >= STORE_START_MAX_REQUESTS:
                raise error
            delay = _start_retry_delay(request_number)
            print(
                f"FEIKUA_START_HTTP_{error.code}_WITHOUT_SESSION: "
                "rechecking exact shop "
                f"before request {request_number + 1}/{STORE_START_MAX_REQUESTS} "
                f"after {delay:g}s...",
                flush=True,
            )
            time.sleep(delay)
            continue
        if payload is None:
            raise RuntimeError("Feikua start returned no payload.")
        if payload.get("code") != 0:
            raise RuntimeError(f"Feikua failed to start the shop: {payload}")
        _assert_start_not_stopped(store["shop_id"], stop_generation)
        return payload["data"]
    if last_start_error is not None:
        raise last_start_error
    raise RuntimeError("Feikua did not start the exact shop.")


def start_store(base_url: str, store: dict) -> dict:
    # Keep the port baseline and HTTP-500 recovery attributable to one start
    # sequence. Stop requests use only the shorter lifecycle lock and can pass
    # through while this sequence polls or backs off.
    with feikua_start_attribution_lock():
        stop_generation = read_stop_generation(store["shop_id"])
        return _start_store_unlocked(base_url, store, stop_generation)


def _load_sessions_unlocked() -> dict[str, dict]:
    try:
        return json.loads(SESSION_FILE.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return {}


def load_sessions() -> dict[str, dict]:
    with _interprocess_file_lock(SESSION_LOCK_PATH):
        return _load_sessions_unlocked()


def save_session(shop_id: str, cdp_port: int, core_version: str) -> None:
    with _interprocess_file_lock(SESSION_LOCK_PATH):
        sessions = _load_sessions_unlocked()
        sessions[shop_id] = {
            "debugger_port": cdp_port,
            "core_version": core_version,
        }
        _atomic_write_text(
            SESSION_FILE,
            json.dumps(sessions, ensure_ascii=False, indent=2),
        )


def forget_session(shop_id: str) -> None:
    with _interprocess_file_lock(SESSION_LOCK_PATH):
        sessions = _load_sessions_unlocked()
        if shop_id in sessions:
            sessions.pop(shop_id, None)
            _atomic_write_text(
                SESSION_FILE,
                json.dumps(sessions, ensure_ascii=False, indent=2),
            )


def listening_ports() -> list[int]:
    """Return local TCP listeners without scanning hundreds of dead ports."""
    try:
        result = subprocess.run(
            ["netstat", "-ano", "-p", "tcp"],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=10,
            check=False,
        )
    except (OSError, subprocess.SubprocessError):
        return []
    ports: set[int] = set()
    for line in result.stdout.splitlines():
        match = re.search(r"127\.0\.0\.1:(\d+)\s+\S+\s+LISTENING", line, re.IGNORECASE)
        if match:
            ports.add(int(match.group(1)))
    return sorted(ports)


def cdp_targets(port: int) -> list[dict]:
    with urllib.request.urlopen(f"http://127.0.0.1:{port}/json/list", timeout=1) as response:
        return json.load(response)


def cdp_is_alive(port: int) -> bool:
    try:
        return bool(request_json(f"http://127.0.0.1:{port}/json/version", timeout=1).get("webSocketDebuggerUrl"))
    except (OSError, urllib.error.URLError, TimeoutError, ValueError, http.client.HTTPException):
        return False


def discover_running_cdp_port(
    shop_id: str,
    allowed_ports: set[int] | None = None,
) -> tuple[int, str] | None:
    """Reuse the exact shop session; only use an unambiguous legacy fallback."""
    saved = load_sessions().get(shop_id)
    if (
        saved
        and (allowed_ports is None or int(saved["debugger_port"]) in allowed_ports)
        and cdp_is_alive(int(saved["debugger_port"]))
    ):
        return int(saved["debugger_port"]), str(saved.get("core_version", ""))

    candidates: list[tuple[int, str]] = []
    for port in listening_ports():
        if allowed_ports is not None and port not in allowed_ports:
            continue
        try:
            version = request_json(f"http://127.0.0.1:{port}/json/version", timeout=1)
            if not version.get("webSocketDebuggerUrl"):
                continue
            targets = cdp_targets(port)
            if any(
                target.get("type") == "page"
                and (
                    is_tiktok_seller_center(target.get("url", ""))
                    or target.get("url", "").startswith("chrome-extension://")
                )
                for target in targets
            ):
                browser = version.get("Browser", "")
                core_version = re.search(r"/(\d+\.\d+\.\d+\.\d+)", browser)
                candidates.append((port, core_version.group(1) if core_version else ""))
        except (
            OSError,
            urllib.error.URLError,
            TimeoutError,
            ValueError,
            KeyError,
            http.client.HTTPException,
        ):
            continue
    if len(candidates) == 1:
        return candidates[0]
    if candidates:
        raise RuntimeError(
            f"Shop is running but its session is not registered and {len(candidates)} "
            "TikTok CDP sessions were found. Do not guess; restart once or provide the saved CDP port."
        )
    return None


def _request_store_stop_unlocked(base_url: str, shop_id: str) -> None:
    """Request one exact stop; 409/500 mean Feikua is already transitioning."""
    current = store_by_id(base_url, shop_id)
    if current is None or current.get("status") == "idle":
        return
    try:
        payload = request_json(
            f"{base_url}/api/external/webdriver/stores/stop",
            method="POST",
            data={"shop_id": shop_id},
        )
        if payload.get("code") != 0:
            raise RuntimeError(f"Feikua failed to stop the shop: {payload}")
    except urllib.error.HTTPError as error:
        if error.code not in (409, 500):
            raise
        print(
            f"FEIKUA_STOP_TRANSITION_ACKNOWLEDGED: HTTP {error.code}",
            flush=True,
        )


def request_store_stop(base_url: str, shop_id: str) -> None:
    with feikua_lifecycle_lock():
        _request_store_stop_unlocked(base_url, shop_id)


def stop_store(
    base_url: str,
    shop_id: str,
    *,
    publish_intent: bool = True,
) -> None:
    if publish_intent:
        publish_stop_intent(shop_id)
    saved = load_sessions().get(shop_id)
    previous_cdp_port = int(saved["debugger_port"]) if saved else None
    if wait_for_store_idle(
        base_url,
        shop_id,
        previous_cdp_port,
        0.01,
        raise_on_timeout=False,
    ):
        return
    # Serialize only the stop API call. Waiting for Chrome/Feikua to become
    # idle must not block another worker from issuing its own start/stop call.
    request_store_stop(base_url, shop_id)
    wait_for_store_idle(base_url, shop_id, previous_cdp_port)


def wait_for_cdp(port: int) -> None:
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        try:
            if "Browser" in request_json(f"http://127.0.0.1:{port}/json/version", timeout=3):
                return
        except (OSError, urllib.error.URLError, TimeoutError):
            time.sleep(0.2)
    raise RuntimeError(f"CDP port {port} was not ready within 30 seconds.")


def browser_version_from_cdp(port: int) -> str:
    """Read the real browser version instead of trusting Feikua start metadata."""
    payload = request_json(f"http://127.0.0.1:{port}/json/version", timeout=10)
    browser = str(payload.get("Browser", ""))
    match = re.search(r"(?:^|/)(\d+\.\d+\.\d+\.\d+)(?:$|\s)", browser)
    if match is None:
        raise RuntimeError(
            f"CDP did not expose a four-part browser version: {browser!r}"
        )
    return match.group(1)


def read_url_bytes_with_retry(
    url: str,
    *,
    timeout: int = 45,
    attempts: int = 4,
) -> bytes:
    """Read an idempotent public artifact with bounded exponential retry."""
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(url, timeout=timeout) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            if error.code not in (429, 500, 502, 503, 504) or attempt >= attempts:
                raise
        except (
            OSError,
            TimeoutError,
            http.client.HTTPException,
            urllib.error.URLError,
        ):
            if attempt >= attempts:
                raise
        print(
            f"CHROMEDRIVER_DOWNLOAD_RETRY: attempt={attempt + 1}/{attempts}",
            flush=True,
        )
        time.sleep(min(8.0, 0.75 * (2 ** (attempt - 1))))
    raise RuntimeError(f"Artifact download retry loop ended unexpectedly: {url}")


def download_to_path_atomic(url: str, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f"{destination.name}.tmp.{os.getpid()}")
    try:
        temporary.write_bytes(read_url_bytes_with_retry(url))
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def extract_chromedriver_atomic(archive: Path, destination: Path) -> None:
    """Extract only chromedriver.exe, avoiding long notice-file paths on Windows."""
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f"{destination.name}.tmp.{os.getpid()}")
    try:
        with zipfile.ZipFile(archive) as contents:
            member = next(
                (
                    name
                    for name in contents.namelist()
                    if name.replace("\\", "/").casefold().endswith("/chromedriver.exe")
                    or name.casefold() == "chromedriver.exe"
                ),
                None,
            )
            if member is None:
                raise RuntimeError(
                    "The ChromeDriver archive did not contain chromedriver.exe."
                )
            with contents.open(member) as source, temporary.open("wb") as target:
                shutil.copyfileobj(source, target)
        os.replace(temporary, destination)
    finally:
        temporary.unlink(missing_ok=True)


def _chromedriver_path_unlocked(core_version: str) -> Path:
    major_text = core_version.split(".", 1)[0]
    if not major_text.isdigit():
        raise RuntimeError(f"Invalid Chrome version reported by CDP: {core_version!r}")

    # Chrome for Testing starts at 115. Older Feikua cores must use the legacy
    # ChromeDriver release service and win32 archive (which also runs on 64-bit
    # Windows).
    if int(major_text) < 115:
        release_url = (
            "https://chromedriver.storage.googleapis.com/"
            f"LATEST_RELEASE_{major_text}"
        )
        driver_version = read_url_bytes_with_retry(
            release_url,
        ).decode("ascii").strip()
        if not driver_version.startswith(f"{major_text}."):
            raise RuntimeError(
                "Legacy ChromeDriver service returned an invalid version: "
                f"{driver_version!r}"
            )
        legacy_driver = (
            DRIVERS_DIR
            / driver_version
            / "chromedriver-win32"
            / "chromedriver.exe"
        )
        if legacy_driver.exists():
            return legacy_driver
        legacy_driver.parent.mkdir(parents=True, exist_ok=True)
        archive = DRIVERS_DIR / f"chromedriver-{driver_version}.zip"
        url = (
            "https://chromedriver.storage.googleapis.com/"
            f"{driver_version}/chromedriver_win32.zip"
        )
        print(
            f"Downloading legacy ChromeDriver {driver_version} "
            f"for Chrome {major_text}...",
            flush=True,
        )
        download_to_path_atomic(url, archive)
        try:
            extract_chromedriver_atomic(archive, legacy_driver)
        finally:
            archive.unlink(missing_ok=True)
        return legacy_driver

    driver_version = core_version
    driver = DRIVERS_DIR / driver_version / "chromedriver-win64" / "chromedriver.exe"
    if driver.exists():
        return driver
    bundled_driver = BUILTIN_DRIVERS_DIR / driver_version / "chromedriver-win64" / "chromedriver.exe"
    if bundled_driver.exists():
        return bundled_driver

    # Chrome for Testing does not publish every ordinary Chrome patch build.
    # Resolve the latest driver for the browser's exact major.minor.build.
    build_key = ".".join(core_version.split(".")[:3])
    metadata_url = (
        "https://googlechromelabs.github.io/chrome-for-testing/"
        "latest-patch-versions-per-build-with-downloads.json"
    )
    builds = json.loads(
        read_url_bytes_with_retry(metadata_url).decode("utf-8")
    ).get("builds", {})
    build = builds.get(build_key)
    download_url = None
    if build is not None:
        driver_version = str(build["version"])
        download_url = next(
            (
                item["url"]
                for item in build.get("downloads", {}).get("chromedriver", [])
                if item.get("platform") == "win64"
            ),
            None,
        )
    driver = DRIVERS_DIR / driver_version / "chromedriver-win64" / "chromedriver.exe"
    if driver.exists():
        return driver
    archive = DRIVERS_DIR / f"chromedriver-{driver_version}.zip"
    target_dir = driver.parent.parent
    target_dir.mkdir(parents=True, exist_ok=True)
    url = download_url or (
        "https://storage.googleapis.com/chrome-for-testing-public/"
        f"{driver_version}/win64/chromedriver-win64.zip"
    )
    print(
        f"Downloading ChromeDriver {driver_version} for Chrome {core_version}...",
        flush=True,
    )
    download_to_path_atomic(url, archive)
    try:
        extract_chromedriver_atomic(archive, driver)
    finally:
        archive.unlink(missing_ok=True)
    if not driver.exists():
        raise RuntimeError("ChromeDriver was not found after download.")
    return driver


def chromedriver_path(core_version: str) -> Path:
    with driver_cache_lock():
        return _chromedriver_path_unlocked(core_version)


def activate_target(cdp_port: int, target_id: str) -> None:
    """Bring the real store tab to the front instead of leaving Feikua's extension visible."""
    url = f"http://127.0.0.1:{cdp_port}/json/activate/{urllib.parse.quote(target_id, safe='')}"
    with urllib.request.urlopen(url, timeout=3) as response:
        response.read()


def create_regular_tab(cdp_port: int, initial_url: str) -> dict:
    # Feikua initially exposes an extension tab, which ChromeDriver cannot attach to.
    # Start the attachable tab at the already-validated store URL to avoid flashing
    # an unrelated blank page before Selenium takes over.
    url = f"http://127.0.0.1:{cdp_port}/json/new?{urllib.parse.quote(initial_url, safe='')}"
    return request_json(url, method="PUT", timeout=8)


def ensure_regular_tab(cdp_port: int, initial_url: str) -> None:
    """Click Feikua's ready Open Store control, then focus the resulting tab."""
    targets = cdp_targets(cdp_port)
    regular_pages = [
        target
        for target in targets
        if target.get("type") == "page"
        and target.get("url", "").startswith(("http://", "https://"))
    ]
    preferred = next(
        (target for target in regular_pages if target.get("url") == initial_url),
        regular_pages[0] if regular_pages else None,
    )
    if preferred is None:
        if not click_extension_open_store(cdp_port):
            raise RuntimeError(
                "Feikua's Open Store control was not actionable after network detection."
            )
        deadline = time.monotonic() + 12
        while time.monotonic() < deadline:
            regular_pages = [
                target
                for target in cdp_targets(cdp_port)
                if target.get("type") == "page"
                and target.get("url", "").startswith(("http://", "https://"))
            ]
            preferred = next(
                (
                    target
                    for target in regular_pages
                    if target.get("url") == initial_url
                ),
                regular_pages[0] if regular_pages else None,
            )
            if preferred is not None:
                break
            time.sleep(0.2)
        if preferred is None:
            raise RuntimeError(
                "Feikua accepted Open Store but no regular store tab appeared."
            )
    target_id = str(preferred.get("id") or "")
    if target_id:
        activate_target(cdp_port, target_id)


def read_open_store_url(debugger_port: int) -> str:
    with urllib.request.urlopen(f"http://127.0.0.1:{debugger_port}/json/list", timeout=3) as response:
        targets = json.load(response)
    detection_page = next(
        (target for target in targets if target.get("type") == "page" and target.get("url", "").startswith("chrome-extension://")),
        None,
    )
    if detection_page is None:
        raise RuntimeError("Feikua's default detection page was not found.")
    socket = create_connection(detection_page["webSocketDebuggerUrl"], suppress_origin=True, timeout=3)
    try:
        socket.send(
            json.dumps(
                {
                    "id": 1,
                    "method": "Runtime.evaluate",
                    "params": {
                        "expression": """
                        (() => {
                          const open = document.querySelector('#open-store');
                          const detect = document.querySelector('#run-detect');
                          if (!open) return '';
                          const style = getComputedStyle(open);
                          const rect = open.getBoundingClientRect();
                          const detectPending = detect
                            && getComputedStyle(detect).display !== 'none';
                          const ready = !detectPending
                            && rect.width > 0 && rect.height > 0
                            && style.display !== 'none'
                            && style.visibility !== 'hidden'
                            && style.pointerEvents !== 'none'
                            && open.getAttribute('aria-disabled') !== 'true'
                            && /^https?:\\/\\//.test(open.href || '');
                          return ready ? open.href : '';
                        })()
                        """,
                        "returnByValue": True,
                    },
                }
            )
        )
        while True:
            payload = json.loads(socket.recv())
            if payload.get("id") == 1:
                url = payload["result"]["result"].get("value", "")
                break
    finally:
        socket.close()
    return url


def click_extension_open_store(debugger_port: int) -> bool:
    """Use Feikua's own post-detection navigation control instead of bypassing it."""
    targets = cdp_targets(debugger_port)
    detection_page = next(
        (
            target
            for target in targets
            if target.get("type") == "page"
            and target.get("url", "").startswith("chrome-extension://")
        ),
        None,
    )
    if detection_page is None:
        return False
    socket = create_connection(
        detection_page["webSocketDebuggerUrl"],
        suppress_origin=True,
        timeout=3,
    )
    try:
        socket.send(
            json.dumps(
                {
                    "id": 1,
                    "method": "Runtime.evaluate",
                    "params": {
                        "expression": """
                        (() => {
                          const open = document.querySelector('#open-store');
                          if (!open) return false;
                          open.click();
                          return true;
                        })()
                        """,
                        "returnByValue": True,
                    },
                }
            )
        )
        while True:
            payload = json.loads(socket.recv())
            if payload.get("id") == 1:
                return bool(payload["result"]["result"].get("value", False))
    finally:
        socket.close()


def read_validated_store_url(debugger_port: int) -> str:
    """Read the validated URL as soon as Feikua populates it."""
    deadline = time.monotonic() + STORE_URL_READY_WAIT_SECONDS
    last_url = ""
    while time.monotonic() < deadline:
        try:
            last_url = read_open_store_url(debugger_port)
            if last_url.startswith(("http://", "https://")):
                return last_url
        except (
            OSError,
            urllib.error.URLError,
            TimeoutError,
            WebSocketTimeoutException,
            RuntimeError,
        ):
            pass
        time.sleep(0.2)
    raise RuntimeError(
        "Feikua did not supply a valid store URL within "
        f"{STORE_URL_READY_WAIT_SECONDS} seconds: {last_url!r}"
    )


def attach(cdp_port: int, driver_path: Path) -> webdriver.Chrome:
    debugger_port = normalized_cdp_proxy_port(cdp_port)
    options = Options()
    # Feikua Seller Center SPAs can keep subresources loading after the usable
    # shell and data are already rendered.  Do not let ChromeDriver session
    # creation block on that background activity; each workflow performs its
    # own explicit rendered-state checks before interacting.
    options.page_load_strategy = "none"
    options.add_experimental_option("debuggerAddress", f"127.0.0.1:{debugger_port}")
    # Feikua legacy cores can report Browser.getVersion as a bare numeric
    # string instead of Chrome/<version>. ChromeDriver supports this hidden
    # compatibility switch and otherwise rejects the same-major browser as
    # "unrecognized Chrome version".
    service = Service(
        str(driver_path),
        service_args=["--disable-build-check"],
    )
    try:
        return webdriver.Chrome(service=service, options=options)
    except WebDriverException as error:
        if "unrecognized Chrome version" not in str(error):
            raise
        modern_candidates = list(
            BUILTIN_DRIVERS_DIR.glob(
                "*/chromedriver-win64/chromedriver.exe"
            )
        ) + list(
            DRIVERS_DIR.glob(
                "*/chromedriver-win64/chromedriver.exe"
            )
        )
        modern_candidates = [
            candidate
            for candidate in modern_candidates
            if candidate != driver_path
            and candidate.parents[1].name.split(".", 1)[0].isdigit()
            and int(candidate.parents[1].name.split(".", 1)[0]) >= 115
        ]
        if not modern_candidates:
            raise
        fallback = max(
            modern_candidates,
            key=lambda candidate: tuple(
                int(part) for part in candidate.parents[1].name.split(".")
            ),
        )
        print(
            "CHROMEDRIVER_BARE_VERSION_FALLBACK: "
            f"{driver_path} -> {fallback}",
            flush=True,
        )
        fallback_service = Service(
            str(fallback),
            service_args=["--disable-build-check"],
        )
        return webdriver.Chrome(service=fallback_service, options=options)


def normalized_cdp_proxy_port(cdp_port: int) -> int:
    """Normalize Feikua legacy /json/version responses for ChromeDriver."""
    version_url = f"http://127.0.0.1:{cdp_port}/json/version"
    with urllib.request.urlopen(version_url, timeout=5) as response:
        version_payload = json.loads(response.read().decode("utf-8"))
    browser_product = str(version_payload.get("Browser") or "").strip()
    if re.match(r"^[A-Za-z]+/\d+", browser_product):
        return cdp_port
    bare_version = re.search(r"\d+(?:\.\d+){1,3}", browser_product)
    if not bare_version:
        return cdp_port
    normalized_product = f"Chrome/{bare_version.group(0)}"

    class LegacyCdpProxyHandler(http.server.BaseHTTPRequestHandler):
        def proxy_websocket(self) -> None:
            upstream = socket.create_connection(("127.0.0.1", cdp_port), timeout=10)
            forwarded_headers = [
                f"GET {self.path} HTTP/1.1",
                f"Host: 127.0.0.1:{cdp_port}",
                "Upgrade: websocket",
                "Connection: Upgrade",
            ]
            for name in (
                "Sec-WebSocket-Key",
                "Sec-WebSocket-Version",
                "Sec-WebSocket-Extensions",
                "Sec-WebSocket-Protocol",
            ):
                value = self.headers.get(name)
                if value:
                    forwarded_headers.append(f"{name}: {value}")
            upstream.sendall(("\r\n".join(forwarded_headers) + "\r\n\r\n").encode("ascii"))

            response = bytearray()
            while b"\r\n\r\n" not in response:
                chunk = upstream.recv(4096)
                if not chunk:
                    raise RuntimeError("Legacy CDP WebSocket closed during handshake.")
                response.extend(chunk)
            header_end = response.index(b"\r\n\r\n") + 4
            self.connection.sendall(response[:header_end])
            if len(response) > header_end:
                self.connection.sendall(response[header_end:])

            def pump(source: socket.socket, destination: socket.socket) -> None:
                try:
                    while True:
                        data = source.recv(65536)
                        if not data:
                            break
                        destination.sendall(data)
                except OSError:
                    pass
                finally:
                    try:
                        destination.shutdown(socket.SHUT_WR)
                    except OSError:
                        pass

            client_to_upstream = threading.Thread(
                target=pump,
                args=(self.connection, upstream),
                daemon=True,
            )
            client_to_upstream.start()
            pump(upstream, self.connection)
            client_to_upstream.join(timeout=1)
            upstream.close()

        def rewrite_websocket_urls(self, payload):
            proxy_port = int(self.server.server_address[1])
            if isinstance(payload, dict):
                rewritten = {
                    key: self.rewrite_websocket_urls(value)
                    for key, value in payload.items()
                }
                websocket_url = rewritten.get("webSocketDebuggerUrl")
                if isinstance(websocket_url, str):
                    rewritten["webSocketDebuggerUrl"] = re.sub(
                        r"^ws://127\.0\.0\.1:\d+",
                        f"ws://127.0.0.1:{proxy_port}",
                        websocket_url,
                    )
                return rewritten
            if isinstance(payload, list):
                return [self.rewrite_websocket_urls(value) for value in payload]
            return payload

        def do_GET(self) -> None:
            if self.headers.get("Upgrade", "").casefold() == "websocket":
                try:
                    self.proxy_websocket()
                except Exception as error:
                    print(
                        f"FEIKUA_LEGACY_CDP_WEBSOCKET_PROXY_ERROR: {error}",
                        flush=True,
                    )
                return
            target_url = f"http://127.0.0.1:{cdp_port}{self.path}"
            try:
                with urllib.request.urlopen(target_url, timeout=10) as upstream:
                    body = upstream.read()
                    content_type = upstream.headers.get(
                        "Content-Type", "application/json"
                    )
                    if self.path.startswith("/json/version"):
                        payload = json.loads(body.decode("utf-8"))
                        payload["Browser"] = normalized_product
                        payload = self.rewrite_websocket_urls(payload)
                        body = json.dumps(payload).encode("utf-8")
                    elif self.path.startswith("/json"):
                        payload = json.loads(body.decode("utf-8"))
                        payload = self.rewrite_websocket_urls(payload)
                        body = json.dumps(payload).encode("utf-8")
                    self.send_response(upstream.status)
                    self.send_header("Content-Type", content_type)
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
            except Exception as error:
                body = str(error).encode("utf-8", errors="replace")
                self.send_response(502)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

        def log_message(self, _format: str, *_args) -> None:
            return

    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), LegacyCdpProxyHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    proxy_port = int(server.server_address[1])
    print(
        "FEIKUA_LEGACY_CDP_VERSION_PROXY: "
        f"{browser_product} -> {normalized_product} on {proxy_port}",
        flush=True,
    )
    return proxy_port


def first_visible_enabled(driver: webdriver.Chrome, by: str, value: str):
    """Return the rendered control, ignoring hidden responsive-menu duplicates."""
    for candidate in driver.find_elements(by, value):
        if candidate.is_displayed() and candidate.is_enabled():
            return candidate
    return None


def click_rendered(driver: webdriver.Chrome, element) -> None:
    driver.execute_script("arguments[0].scrollIntoView({block: 'center'});", element)
    try:
        element.click()
    except ElementNotInteractableException:
        # The Seller Center frequently keeps an inert duplicate beside the rendered control.
        driver.execute_script("arguments[0].click();", element)


def click_freshly_resolved(driver: webdriver.Chrome, resolver, timeout: int = 20) -> None:
    """Resolve and click a rendered node in one poll to survive Seller Center rerenders."""
    def resolve_and_click(current):
        try:
            element = resolver(current)
            if element is None or not element.is_displayed() or not element.is_enabled():
                return False
            click_rendered(current, element)
            return True
        except (ElementNotInteractableException, StaleElementReferenceException):
            return False

    WebDriverWait(driver, timeout, ignored_exceptions=(StaleElementReferenceException,)).until(resolve_and_click)


def is_transient_renderer_timeout(error: BaseException) -> bool:
    if isinstance(error, StaleElementReferenceException):
        return True
    lowered = str(error).casefold()
    return isinstance(error, TimeoutException) or (
        isinstance(error, WebDriverException)
        and (
            "script timeout" in lowered
            or "timed out receiving message from renderer" in lowered
            or "node with given id does not belong to the document" in lowered
            or "cannot find context with specified id" in lowered
        )
    )


def open_store(driver: webdriver.Chrome, store_url: str) -> dict[str, str]:
    # Some Feikua profiles need substantially longer to finish the first
    # Seller Center renderer navigation.  A 12-second limit caused otherwise
    # healthy Shopee stores to be closed before the export workflow started.
    driver.set_page_load_timeout(60)
    driver.set_script_timeout(60)
    current_url = ""
    try:
        current_url = str(driver.current_url or "")
    except TimeoutException:
        pass
    current_host = (urllib.parse.urlparse(current_url).hostname or "").casefold()
    store_host = (urllib.parse.urlparse(store_url).hostname or "").casefold()
    if current_url.startswith(("http://", "https://")) and current_host == store_host:
        # Feikua's own Open Store button has already navigated this tab. A
        # second driver.get() is redundant and can stall a low-memory renderer.
        print("FEIKUA_EXISTING_STORE_NAVIGATION_REUSED", flush=True)
    else:
        try:
            driver.get(store_url)
        except TimeoutException:
            # Stop only the incomplete navigation; the authenticated Feikua
            # browser remains open and can continue rendering the current tab.
            try:
                driver.execute_cdp_cmd("Page.stopLoading", {})
            except Exception:
                pass
            print("FEIKUA_STORE_NAVIGATION_TIMEOUT_RECOVERY", flush=True)

    def store_document_ready(current: webdriver.Chrome) -> bool:
        try:
            return bool(
                current.execute_script(
                    """
                    if (
                      document.readyState === 'interactive' ||
                      document.readyState === 'complete'
                    ) return true;
                    // Seller Center frequently leaves optional resources
                    // pending while its usable shell is already rendered.
                    // Start work as soon as real navigation and body content
                    // exist instead of wasting the full readyState timeout.
                    const body = document.body;
                    if (!body || (body.innerText || '').trim().length < 80) {
                      return false;
                    }
                    return Boolean(document.querySelector(
                      'nav,aside,[role="navigation"],' +
                      '[class*="menu" i],[class*="sidebar" i]'
                    ));
                    """
                )
            )
        except TimeoutException:
            # A renderer that is swapping the initial Seller Center document
            # can transiently time out one script command under memory pressure.
            # Do not stop the live navigation: slow Seller Center pages often
            # recover by themselves and later explicit state checks are the
            # authoritative readiness gates.
            return False
        except WebDriverException as error:
            if not is_transient_renderer_timeout(error):
                raise
            return False

    try:
        WebDriverWait(driver, 120, poll_frequency=0.5).until(
            store_document_ready
        )
    except TimeoutException:
        # Native document readiness is advisory.  If Feikua's exact tab still
        # owns the validated store host, continue to the explicit login,
        # verification, disabled-store, and Seller Center gates instead of
        # closing a healthy but slow window.
        live_url = str(driver.current_url or "")
        live_host = (
            urllib.parse.urlparse(live_url).hostname or ""
        ).casefold()
        if (
            live_url.startswith(("http://", "https://"))
            and live_host == store_host
        ):
            print(
                "FEIKUA_STORE_RENDERER_TARGET_ALIVE_AFTER_READY_TIMEOUT: "
                "continuing with explicit page-state checks",
                flush=True,
            )
        else:
            raise
    # The short timeout is only for leaving Feikua's extension page. Finance
    # routes can legitimately render longer and must not inherit that limit.
    driver.set_page_load_timeout(120)
    return {"url": driver.current_url, "title": driver.title}


def current_store_page(driver: webdriver.Chrome) -> dict[str, str]:
    return {"url": driver.current_url, "title": driver.title}


def requires_human_login(url: str) -> bool:
    """Identify authentication or platform-security pages without interacting with them."""
    path = urllib.parse.urlparse(url).path.lower()
    return any(marker in path for marker in ("login", "signin", "verify", "captcha", "challenge", "security"))


def dismiss_slider_captcha(driver: webdriver.Chrome) -> bool:
    """Close a visible slider CAPTCHA through its own explicit X/Close control."""
    clicked = bool(
        driver.execute_script(
            """
            const shown = (el) => {
              if (!el) return false;
              const style = getComputedStyle(el);
              const rect = el.getBoundingClientRect();
              return rect.width > 0 && rect.height > 0 &&
                style.display !== 'none' && style.visibility !== 'hidden' &&
                Number(style.opacity || 1) > 0;
            };
            const roots = [...document.querySelectorAll(
              "#captcha_container,[id*='captcha' i],[class*='captcha' i]," +
              "[id*='challenge' i],[class*='challenge' i]," +
              "iframe[src*='captcha' i],iframe[src*='challenge' i]"
            )].filter(shown);
            for (const marker of roots) {
              const markerText = (marker.innerText || marker.textContent || '')
                .trim().toLowerCase();
              const hasPuzzlePrompt = [
                '请完成下列验证后继续',
                '拖动完成上方拼图',
                'drag the slider',
                'complete the puzzle'
              ].some((text) => markerText.includes(text));
              const hasSlider = marker.matches(
                "iframe[src*='captcha' i],iframe[src*='challenge' i]," +
                "[class*='drag' i],[id*='drag' i],[class*='slider' i],[id*='slider' i]"
              ) || !!marker.querySelector(
                "[class*='drag' i],[id*='drag' i],[class*='slider' i],[id*='slider' i]"
              ) || hasPuzzlePrompt;
              if (!hasSlider) continue;
              const containers = [
                marker.closest("[role='dialog'],[class*='modal' i],[class*='popup' i]," +
                  "[class*='captcha' i],[id*='captcha' i],[class*='challenge' i]"),
                marker.parentElement,
                marker
              ].filter(Boolean);
              for (const container of [...new Set(containers)]) {
                let closeButton = [...container.querySelectorAll(
                  "a.verify-bar-close,a[class*='verify-bar-close' i]," +
                  "button[aria-label*='close' i],[role='button'][aria-label*='close' i]," +
                  "button[title*='close' i],[class*='close' i],[id*='close' i]"
                )].find((el) => shown(el) && !el.disabled);
                if (!closeButton) {
                  const box = container.getBoundingClientRect();
                  if (box.width < 250 || box.height < 220) continue;
                  const candidates = [...container.querySelectorAll(
                    "button,[role='button'],a,div,span,svg"
                  )].filter((el) => {
                    if (!shown(el) || el.disabled) return false;
                    const rect = el.getBoundingClientRect();
                    if (rect.width < 8 || rect.height < 8 ||
                        rect.width > 64 || rect.height > 64) return false;
                    const centerX = rect.left + rect.width / 2;
                    const centerY = rect.top + rect.height / 2;
                    const inTopRight = centerX >= box.left + box.width * 0.68 &&
                      centerY <= box.top + Math.min(72, box.height * 0.24);
                    if (!inTopRight) return false;
                    const label = [
                      el.getAttribute('aria-label') || '',
                      el.getAttribute('title') || '',
                      el.textContent || ''
                    ].join(' ').trim().toLowerCase();
                    const explicitX = /(^|\\s)(x|×|✕|✖)(\\s|$)/.test(label);
                    const simpleSvg = el.matches('svg') &&
                      el.querySelectorAll('path,line,polyline').length <= 4;
                    return explicitX || simpleSvg || !!el.querySelector(
                      "svg,[data-icon*='close' i],[class*='close' i]"
                    );
                  });
                  candidates.sort((left, right) => {
                    const a = left.getBoundingClientRect();
                    const b = right.getBoundingClientRect();
                    const da = Math.hypot(
                      box.right - (a.left + a.width / 2),
                      (a.top + a.height / 2) - box.top
                    );
                    const db = Math.hypot(
                      box.right - (b.left + b.width / 2),
                      (b.top + b.height / 2) - box.top
                    );
                    return da - db;
                  });
                  closeButton = candidates[0];
                }
                if (closeButton) {
                  const target =
                    closeButton.matches("button,[role='button'],a,[tabindex]")
                      ? closeButton
                      : closeButton.querySelector(
                          "a.verify-bar-close,a[class*='verify-bar-close' i]," +
                          "button,[role='button'],a,[tabindex]"
                        ) ||
                        closeButton.closest(
                          "button,[role='button'],a,[tabindex]"
                        ) ||
                        closeButton;
                  try {
                    for (const type of ['pointerdown', 'mousedown', 'pointerup', 'mouseup']) {
                      target.dispatchEvent(new MouseEvent(
                        type, {bubbles: true, cancelable: true, view: window}
                      ));
                    }
                    HTMLElement.prototype.click.call(target);
                  } catch (_) {
                    target.dispatchEvent(new MouseEvent(
                      'click', {bubbles: true, cancelable: true, view: window}
                    ));
                  }
                  return true;
                }
              }
            }
            return false;
            """
        )
    )
    if not clicked:
        return False
    # SecSDK may reject HTMLElement.click()/synthetic MouseEvents. Use a
    # genuine pointer click only on the verified CAPTCHA close anchor.
    try:
        close = next(
            (
                element
                for element in driver.find_elements(
                    By.CSS_SELECTOR,
                    "#verify-bar-close,a.verify-bar-close,"
                    "a[class*='verify-bar-close' i]",
                )
                if element.is_displayed() and element.is_enabled()
            ),
            None,
        )
        if close is not None:
            ActionChains(driver).move_to_element(close).click().perform()
            print(
                "SLIDER_CAPTCHA_COORDINATE_CLOSE_CLICKED",
                flush=True,
            )
    except (StaleElementReferenceException, WebDriverException):
        pass
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        still_visible = bool(
            driver.execute_script(
                """
                const shown = (el) => {
                  const style = getComputedStyle(el);
                  const rect = el.getBoundingClientRect();
                  return rect.width > 0 && rect.height > 0 &&
                    style.display !== 'none' && style.visibility !== 'hidden' &&
                    Number(style.opacity || 1) > 0;
                };
                return [...document.querySelectorAll(
                  "#captcha_container,[id*='captcha' i],[class*='captcha' i]," +
                  "[id*='challenge' i],[class*='challenge' i]," +
                  "iframe[src*='captcha' i],iframe[src*='challenge' i]"
                )].some((element) => {
                  if (!shown(element)) return false;
                  if (element === document.body || element === document.documentElement) {
                    return false;
                  }
                  const rect = element.getBoundingClientRect();
                  const isFrame = element.tagName === 'IFRAME';
                  const isCaptchaContainer = element.matches(
                    "#captcha_container,.captcha_verify_container," +
                    "[class*='CaptchaWrapper' i]," +
                    "iframe[src*='captcha' i],iframe[src*='challenge' i]"
                  );
                  return isFrame || (
                    isCaptchaContainer && rect.width >= 200 && rect.height >= 150
                  );
                });
                """
            )
        )
        if not still_visible:
            print("SLIDER_CAPTCHA_CLOSED_AND_GONE", flush=True)
            return True
        time.sleep(0.25)
    print("SLIDER_CAPTCHA_CLOSE_NOT_CONFIRMED", flush=True)
    return False


def verification_overlay_present(driver: webdriver.Chrome) -> bool:
    """Detect in-page CAPTCHA/security overlays that do not change the URL."""
    last_error: BaseException | None = None
    for attempt in range(2):
        try:
            return bool(
                driver.execute_script(
                    """
                    return Array.from(document.querySelectorAll(
                      "#captcha_container, [id*='captcha'], [id*='challenge'], [class*='challenge']"
                    )).some((element) => {
                      const style = window.getComputedStyle(element);
                      const rect = element.getBoundingClientRect();
                      const fixedOverlay = style.position === "fixed" &&
                                            rect.width >= window.innerWidth * 0.8 &&
                                            rect.height >= window.innerHeight * 0.8;
                      return fixedOverlay &&
                             style.display !== "none" &&
                             style.visibility !== "hidden" &&
                             Number(style.opacity || 1) > 0 &&
                             rect.width > 0 && rect.height > 0;
                    });
                    """
                )
            )
        except (TimeoutException, WebDriverException) as error:
            if not is_transient_renderer_timeout(error):
                raise
            last_error = error
            try:
                driver.execute_cdp_cmd("Page.stopLoading", {})
            except Exception:
                pass
            if attempt == 0:
                time.sleep(0.75)
    assert last_error is not None
    raise last_error


def verification_present(driver: webdriver.Chrome) -> bool:
    # Regional stores can redirect from a cross-border URL to
    # seller-<country>.tiktok.com after the browser is attached. Re-detect the
    # active host before deciding whether this is Seller Center or a login page.
    activate_tiktok_profile_for_url(driver.current_url)
    if is_tiktok_seller_center(driver.current_url):
        dismiss_slider_captcha(driver)
    return requires_human_login(driver.current_url) or verification_overlay_present(driver)


def attempt_autofilled_tiktok_email_login(driver: webdriver.Chrome) -> str:
    """Select email login and submit Feikua-autofilled credentials safely.

    Returns ``logged_in``, ``manual_verification``, or ``failed``.  Credential
    values are only checked for presence and are never printed.
    """
    if not requires_human_login(driver.current_url):
        return "logged_in"

    def visible(elements):
        resolved = []
        for element in elements:
            try:
                if element.is_displayed():
                    resolved.append(element)
            except StaleElementReferenceException:
                continue
        return resolved

    # Some regional login pages first show method choices.  Prefer a rendered
    # email choice, but do not click the separate TikTok-account login action.
    email_inputs = visible(
        driver.find_elements(
            By.CSS_SELECTOR,
            'input[type="email"], input[name="email"], '
            'input[class*="login-form-email"]',
        )
    )
    if not email_inputs:
        email_tokens = ("email", "e-mail", "邮箱", "อีเมล", "อีเมล์")
        for control in visible(
            driver.find_elements(By.CSS_SELECTOR, "button, [role='button'], a")
        ):
            label = " ".join(
                (
                    control.text or "",
                    control.get_attribute("aria-label") or "",
                    control.get_attribute("title") or "",
                )
            ).strip().casefold()
            if any(token in label for token in email_tokens) and "tiktok account" not in label:
                click_rendered(driver, control)
                print("TIKTOK_EMAIL_LOGIN_SELECTED", flush=True)
                break
        try:
            email_inputs = WebDriverWait(driver, 10).until(
                lambda current: visible(
                    current.find_elements(
                        By.CSS_SELECTOR,
                        'input[type="email"], input[name="email"], '
                        'input[class*="login-form-email"]',
                    )
                )
                or None
            )
        except TimeoutException:
            print("TIKTOK_EMAIL_LOGIN_FORM_NOT_AVAILABLE", flush=True)
            return "failed"
    else:
        print("TIKTOK_EMAIL_LOGIN_FORM_READY", flush=True)

    password_inputs = visible(
        driver.find_elements(
            By.CSS_SELECTOR,
            'input[type="password"][name="password"], '
            'input[class*="login-form-password"]',
        )
    )
    email_input = next(
        (element for element in email_inputs if (element.get_attribute("value") or "").strip()),
        None,
    )
    password_input = next(
        (element for element in password_inputs if (element.get_attribute("value") or "").strip()),
        None,
    )
    if email_input is None or password_input is None:
        print("TIKTOK_AUTOFILLED_CREDENTIALS_MISSING", flush=True)
        return "failed"

    if verification_overlay_present(driver):
        print("TIKTOK_LOGIN_CAPTCHA_PRESENT", flush=True)
        return "manual_verification"

    try:
        form = password_input.find_element(By.XPATH, "./ancestor::form[1]")
        submit = next(
            (
                button
                for button in form.find_elements(
                    By.CSS_SELECTOR, 'button[type="submit"], button[name="loginBtn"]'
                )
                if button.is_displayed() and button.is_enabled()
            ),
            None,
        )
    except Exception:
        submit = None
    if submit is None:
        submit = next(
            (
                button
                for button in visible(
                    driver.find_elements(
                        By.CSS_SELECTOR,
                        'button[type="submit"], button[name="loginBtn"]',
                    )
                )
                if button.is_enabled()
            ),
            None,
        )
    if submit is None:
        print("TIKTOK_EMAIL_LOGIN_SUBMIT_NOT_AVAILABLE", flush=True)
        return "failed"

    click_rendered(driver, submit)
    print("TIKTOK_EMAIL_LOGIN_SUBMITTED", flush=True)
    deadline = time.monotonic() + 30
    password_error_markers = (
        "incorrect password",
        "wrong password",
        "invalid password",
        "password is incorrect",
        "密码错误",
        "รหัสผ่านไม่ถูกต้อง",
    )
    while time.monotonic() < deadline:
        if not requires_human_login(driver.current_url):
            print("TIKTOK_EMAIL_LOGIN_CONFIRMED", flush=True)
            return "logged_in"
        if verification_overlay_present(driver):
            print("TIKTOK_LOGIN_CAPTCHA_PRESENT", flush=True)
            return "manual_verification"
        try:
            page_text = driver.find_element(By.TAG_NAME, "body").text.casefold()
            if any(marker in page_text for marker in password_error_markers):
                print("TIKTOK_EMAIL_LOGIN_PASSWORD_REJECTED", flush=True)
                return "failed"
        except Exception:
            pass
        time.sleep(0.25)
    print("TIKTOK_EMAIL_LOGIN_NOT_CONFIRMED", flush=True)
    return "failed"


def verification_interaction_ready(driver: webdriver.Chrome) -> bool:
    """Return true only after a visible verification page has usable controls.

    A CAPTCHA overlay is often inserted before its iframe or drag slider has
    finished loading.  Detecting the container alone must not start the human
    takeover deadline, otherwise a slow provider can consume the whole window
    before the user can actually drag the challenge.
    """
    return bool(
        driver.execute_script(
            """
            const visible = (element) => {
              const style = window.getComputedStyle(element);
              const rect = element.getBoundingClientRect();
              return style.display !== 'none' && style.visibility !== 'hidden' &&
                style.pointerEvents !== 'none' && rect.width >= 20 && rect.height >= 20;
            };
            const interactive = (element) => {
              if (!visible(element) || element.disabled || element.getAttribute('aria-disabled') === 'true') {
                return false;
              }
              if (element.tagName === 'IFRAME') {
                // A visible iframe with a source is the cross-origin CAPTCHA's
                // first usable surface; its contents cannot safely be inspected.
                return Boolean(element.getAttribute('src'));
              }
              return true;
            };
            const selectors = [
              'iframe[src]', 'input:not([type=hidden])', 'button', '[role=button]',
              '[role=slider]', '[class*=slider i]', '[id*=slider i]',
              '[class*=drag i]', '[id*=drag i]', '[data-testid*=captcha i]'
            ].join(',');
            if (document.readyState !== 'complete') return false;
            if (/login|signin|verify|captcha|challenge|security/i.test(location.pathname)) {
              return Array.from(document.querySelectorAll(selectors)).some(interactive);
            }
            return Array.from(document.querySelectorAll(
              '#captcha_container, [id*=captcha i], [id*=challenge i], [class*=challenge i]'
            )).filter(visible).some((overlay) =>
              Array.from(overlay.querySelectorAll(selectors)).some(interactive)
            );
            """
        )
    )


def install_human_activity_probe(driver: webdriver.Chrome) -> None:
    """Track real user input so an active CAPTCHA session is never auto-closed."""
    driver.execute_script(
        """
        (() => {
          if (window.__tkBillHumanActivityInstalled) return;
          window.__tkBillHumanActivityInstalled = true;
          window.__tkBillHumanActivity = false;
          const mark = (event) => {
            // HTMLElement.click() and other script-generated events are not
            // real manual takeover. Only trusted browser input may disable
            // the bounded automatic CAPTCHA-close path.
            if (event && event.isTrusted) {
              window.__tkBillHumanActivity = true;
            }
          };
          for (const eventName of [
            "mousemove", "mousedown", "mouseup", "pointermove", "pointerdown",
            "pointerup", "touchstart", "touchmove", "keydown", "input", "change"
          ]) {
            window.addEventListener(eventName, mark, {capture: true, passive: true});
            document.addEventListener(eventName, mark, {capture: true, passive: true});
          }
        })();
        """
    )


def human_activity_detected(driver: webdriver.Chrome) -> bool:
    return bool(driver.execute_script("return window.__tkBillHumanActivity === true;"))


def wait_for_human_takeover(driver: webdriver.Chrome, timeout: int = 45) -> bool:
    """Wait for an interactive challenge, then begin the human takeover timer."""
    install_human_activity_probe(driver)
    loading_notice_at = time.monotonic()
    while True:
        try:
            if not verification_present(driver):
                print("MANUAL_TAKEOVER_CONFIRMED")
                return True
            if verification_interaction_ready(driver):
                break
        except Exception:
            # Treat a lost browser connection as no confirmed manual takeover.
            return False
        if time.monotonic() - loading_notice_at >= 30:
            print(
                "VERIFICATION_LOADING: interactive CAPTCHA controls are not ready; "
                "the 45-second manual timer has not started.",
                flush=True,
            )
            loading_notice_at = time.monotonic()
        time.sleep(0.5)
    print(
        f"VERIFICATION_INTERACTIVE_READY: waiting {timeout} seconds; "
        "any mouse, drag, touch, or keyboard activity keeps this session open...",
        flush=True,
    )
    deadline = time.monotonic() + timeout
    active_session = False
    while time.monotonic() < deadline:
        try:
            if not verification_present(driver):
                print("MANUAL_TAKEOVER_CONFIRMED")
                return True
            if not active_session and human_activity_detected(driver):
                active_session = True
                print(
                    "HUMAN_ACTIVITY_DETECTED: automatic close disabled while manual verification is active.",
                    flush=True,
                )
        except Exception:
            # Treat a lost browser connection as no confirmed manual takeover.
            break
        time.sleep(0.25)
    if active_session:
        print("MANUAL_VERIFICATION_IN_PROGRESS: keeping the shop open.", flush=True)
        while True:
            try:
                if not verification_present(driver):
                    print("MANUAL_TAKEOVER_CONFIRMED")
                    return True
            except Exception:
                break
            time.sleep(0.25)
    print("MANUAL_TAKEOVER_TIMEOUT")
    return False


def is_tiktok_seller_center(url: str) -> bool:
    """Recognize only the Seller Center domain allowed by the active skill profile."""
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("http", "https"):
        return False
    hostname = (parsed.hostname or "").casefold()
    if SELLER_CENTER_PROFILE == "cross-border":
        return hostname == CROSS_BORDER_SELLER_CENTER_HOST
    return SELLER_CENTER_HOST_RE.fullmatch(hostname) is not None


def activate_tiktok_profile_for_url(url: str) -> None:
    """Use the actual Seller Center host when workbook platform labels disagree."""
    global SELLER_CENTER_PROFILE
    parsed = urllib.parse.urlparse(url)
    hostname = (parsed.hostname or "").casefold()
    detected: str | None = None
    if hostname == CROSS_BORDER_SELLER_CENTER_HOST:
        detected = "cross-border"
    elif SELLER_CENTER_HOST_RE.fullmatch(hostname) is not None:
        detected = "regional"
    if detected is not None and detected != SELLER_CENTER_PROFILE:
        print(
            "TIKTOK_PROFILE_AUTO_SWITCHED: "
            f"{SELLER_CENTER_PROFILE} -> {detected} ({hostname})",
            flush=True,
        )
        SELLER_CENTER_PROFILE = detected


def dismiss_advertisement_modal(driver: webdriver.Chrome) -> bool:
    """Close non-security modals only through an explicit X or promo acknowledgement."""
    for modal in driver.find_elements(By.CSS_SELECTOR, "div.core-modal-wrapper"):
        try:
            if not modal.is_displayed():
                continue
            modal_text = modal.text.casefold()
            if any(keyword in modal_text for keyword in SECURITY_MODAL_KEYWORDS):
                continue
            controls = [
                control
                for control in modal.find_elements(By.CSS_SELECTOR, "button, [role='button']")
                if control.is_displayed() and control.is_enabled()
            ]
            close_controls = [
                control
                for control in modal.find_elements(
                    By.CSS_SELECTOR,
                    "[aria-label='close' i], [aria-label='关闭' i], [title='close' i], "
                    "[title='关闭' i], .core-modal-close, .modal-close, [data-testid*='close' i]",
                )
                if control.is_displayed() and control.is_enabled()
            ]
            dismiss_control = close_controls[0] if close_controls else next(
                (
                    control
                    for control in controls
                    if control.text.strip().casefold() in SAFE_ADVERTISEMENT_DISMISS_LABELS
                    or control.get_attribute("aria-label").strip().casefold() in {"close", "关闭", "ปิด"}
                ),
                None,
            )
            if dismiss_control is None or (
                not close_controls
                and not any(keyword in modal_text for keyword in ADVERTISEMENT_MODAL_KEYWORDS)
            ):
                continue
            click_rendered(driver, dismiss_control)
            WebDriverWait(driver, 5).until(lambda current: not modal.is_displayed())
            print("TIKTOK_NON_SECURITY_MODAL_DISMISSED", flush=True)
            return True
        except (StaleElementReferenceException, ElementNotInteractableException):
            continue
    return False


def select_picker_date_range(driver: webdriver.Chrome, start_date: dt.date, end_date: dt.date) -> None:
    """Select the validated range in TikTok's visible two-month calendar."""
    wait = WebDriverWait(driver, 20)
    picker = wait.until(
        lambda current: next(
            (element for element in current.find_elements(By.CSS_SELECTOR, ".p-picker-range-container") if element.is_displayed()),
            None,
        )
    )

    def displayed_month(panel) -> dt.date:
        labels = panel.find_elements(By.CSS_SELECTOR, ".p-picker-header-label")
        values = [label.text.strip() for label in labels]
        numbers = [[int(value) for value in re.findall(r"\d+", text)] for text in values]
        year = next((items[0] for items in numbers if items and items[0] >= 1900), None)
        month = next((items[0] for items in numbers if items and 1 <= items[0] <= 12), None)
        if year is None or month is None:
            raise RuntimeError(f"Could not parse localized calendar header: {values}")
        if year > 2400:
            year -= 543
        return dt.date(year, month, 1)

    # Navigate with the left calendar panel until it contains the start date.
    for _ in range(240):
        panels = picker.find_elements(By.CSS_SELECTOR, ".p-panel-date")
        left_panel = panels[0]
        current_month = displayed_month(left_panel)
        target_month = start_date.replace(day=1)
        if current_month == target_month:
            break
        header_icons = left_panel.find_elements(By.CSS_SELECTOR, ".p-picker-header-icon:not(.p-picker-header-icon-hidden)")
        header_icons[1 if current_month > target_month else -1].click()
        wait.until(lambda current: displayed_month(current.find_elements(By.CSS_SELECTOR, ".p-panel-date")[0]) != current_month)
    else:
        raise RuntimeError("The export date picker could not reach the requested start month.")

    def click_date(value: dt.date) -> None:
        for panel in picker.find_elements(By.CSS_SELECTOR, ".p-panel-date"):
            if displayed_month(panel) == value.replace(day=1):
                panel.find_element(
                    By.XPATH,
                    f'.//div[contains(@class, "p-picker-cell-in-view") and '
                    f'not(contains(@class, "p-picker-cell-disabled"))]'
                    f'[.//div[normalize-space()="{value.day:02d}"]]',
                ).click()
                return
        raise RuntimeError(f"The export date picker did not display {value.isoformat()}.")

    click_date(start_date)
    click_date(end_date)


def confirm_and_submit_tiktok_export(driver: webdriver.Chrome) -> None:
    """Confirm the calendar range and submit the export request in the open popover."""
    wait = WebDriverWait(driver, 20)
    def visible_popovers(current):
        visible = []
        for popover in current.find_elements(By.CSS_SELECTOR, ".p-popover"):
            try:
                if popover.is_displayed():
                    visible.append(popover)
            except StaleElementReferenceException:
                continue
        return visible

    def calendar_popover(current):
        return next(
            (
                popover
                for popover in visible_popovers(current)
                if popover.find_elements(By.CSS_SELECTOR, ".p-picker-range-container")
            ),
            None,
        )

    def calendar_confirmation(current):
        popover = calendar_popover(current)
        if popover is None:
            return None
        buttons = [
            button
            for button in popover.find_elements(By.TAG_NAME, "button")
            if button.is_displayed() and button.is_enabled()
        ]
        return buttons[-1] if buttons else None

    click_freshly_resolved(driver, calendar_confirmation, timeout=5)

    def export_popover(current):
        # The Seller Center shell can keep unrelated assistant/marketing
        # popovers visible.  The export dialog is the one whose footer has a
        # secondary cancel action and a final primary action.  Selecting the
        # first arbitrary popover caused false "submitted" logs while the real
        # export dialog remained open.
        for popover in visible_popovers(current):
            if popover.find_elements(By.CSS_SELECTOR, ".p-picker-range-container"):
                continue
            buttons = [
                button
                for button in popover.find_elements(By.TAG_NAME, "button")
                if button.is_displayed() and button.is_enabled()
            ]
            if len(buttons) < 2:
                continue
            final_classes = (buttons[-1].get_attribute("class") or "").split()
            if "p-btn-primary" in final_classes:
                return popover
        return None

    def final_export_button(current):
        popover = export_popover(current)
        if popover is None:
            return None
        buttons = [button for button in popover.find_elements(By.TAG_NAME, "button")
                   if button.is_displayed() and button.is_enabled()]
        return buttons[-1] if buttons else None

    submitted_popover = wait.until(export_popover)
    click_freshly_resolved(driver, final_export_button)
    def export_dispatched(current) -> bool:
        try:
            return not submitted_popover.is_displayed()
        except StaleElementReferenceException:
            return True
    WebDriverWait(
        driver,
        20,
        ignored_exceptions=(StaleElementReferenceException,),
    ).until(export_dispatched)


def report_names_from_text(text: str) -> set[str]:
    return {
        line.strip()
        for line in text.splitlines()
        if line.strip().startswith("income_") and line.strip().endswith(".xlsx")
    }


def report_names(driver: webdriver.Chrome) -> set[str]:
    # On regional Seller Center pages, reading body.text forces layout of the
    # entire withdrawals table and can stall the next action after navigation.
    # Query only rendered report-name nodes instead; the report history popup
    # is the authoritative source for the same income_*.xlsx names.
    if SELLER_CENTER_PROFILE == "regional":
        names: set[str] = set()
        for element in driver.find_elements(
            By.XPATH,
            '//*[contains(normalize-space(), "income_") and '
            'contains(normalize-space(), ".xlsx")]',
        ):
            try:
                if element.is_displayed():
                    names.update(report_names_from_text(element.text))
            except StaleElementReferenceException:
                continue
        return names
    # Cross-border report names differ by finance tab (for example Statements
    # versus On hold). Resolve the rendered .xlsx leaf text instead of binding
    # the workflow to one localized report-name prefix.
    names: set[str] = set()
    for element in driver.find_elements(
        By.XPATH, '//*[contains(normalize-space(.), ".xlsx")]'
    ):
        try:
            if not element.is_displayed():
                continue
            text = element.text.strip()
            if text.endswith(".xlsx") and len(text) <= 300:
                names.add(text)
        except StaleElementReferenceException:
            continue
    return names


def report_created_at(report_name: str) -> dt.datetime | None:
    """Return the timezone-aware creation time encoded in a TikTok income report name."""
    match = re.fullmatch(
        r"income_(\d{14})\((?:UTC|GMT)([+-])(\d{1,2})(?::?(\d{2}))?\)\.xlsx",
        report_name,
    )
    if match is None:
        return None
    offset_minutes = int(match.group(3)) * 60 + int(match.group(4) or 0)
    if match.group(2) == "-":
        offset_minutes = -offset_minutes
    return dt.datetime.strptime(match.group(1), "%Y%m%d%H%M%S").replace(
        tzinfo=dt.timezone(dt.timedelta(minutes=offset_minutes))
    )


def valid_xlsx(path: Path) -> bool:
    """Confirm a completed workbook without relying on browser UI state."""
    try:
        with zipfile.ZipFile(path) as workbook:
            return "xl/workbook.xml" in workbook.namelist()
    except (OSError, zipfile.BadZipFile):
        return False


def confirmed_download(
    output_dir: Path,
    known_output_files: set[Path],
    selected_report: str | None,
    stable_sizes: dict[Path, int],
) -> Path | None:
    """Return this invocation's stable workbook as soon as Chrome finishes it."""
    # Never accept a background or delayed workbook before this invocation has
    # clicked the exact requested report row. A previous browser download can
    # otherwise arrive after the pre-submission snapshot and look "new".
    if selected_report is None:
        return None

    selected_name = Path(selected_report)
    selected_pattern = re.compile(
        rf"^{re.escape(selected_name.stem)}(?: ?\(\d+\))?"
        rf"{re.escape(selected_name.suffix)}$"
    )
    selected_created_at = report_created_at(selected_report)
    candidates: list[Path] = []
    timestamp_fallbacks: list[Path] = []
    for path in output_dir.glob("*.xlsx"):
        if not path.is_file():
            continue
        resolved = path.resolve()
        if resolved in known_output_files:
            continue
        if selected_pattern.fullmatch(resolved.name):
            candidates.append(resolved)
            continue
        # TikTok occasionally renders one creation second in Export History
        # but gives the downloaded workbook the following second. Keep the
        # exact-name rule primary; only accept another newly-created income
        # workbook when its encoded creation time is within two minutes of the
        # exact report row that was clicked.
        candidate_created_at = report_created_at(resolved.name)
        if (
            selected_created_at is not None
            and candidate_created_at is not None
            and abs(
                (candidate_created_at - selected_created_at).total_seconds()
            )
            <= 120
        ):
            timestamp_fallbacks.append(resolved)

    if not candidates and len(timestamp_fallbacks) == 1:
        candidates = timestamp_fallbacks

    for candidate in candidates:
        if (output_dir / f"{candidate.name}.crdownload").exists():
            continue
        size = candidate.stat().st_size
        if size <= 0 or stable_sizes.get(candidate) != size:
            stable_sizes[candidate] = size
            continue
        if valid_xlsx(candidate):
            return candidate
    return None


def download_latest_tiktok_export_once(
    driver: webdriver.Chrome,
    output_dir: Path,
    known_reports: set[str],
    submitted_after: dt.datetime | None,
    timeout: int | None = None,
    requested_report: str | None = None,
    expected_cross_border_range: tuple[dt.date, dt.date] | None = None,
) -> str:
    """Wait for this request's new report and download only that one."""
    output_dir.mkdir(parents=True, exist_ok=True)
    known_output_files = {
        file.resolve()
        for file in output_dir.glob("*.xlsx")
        if file.is_file()
    }
    driver.execute_cdp_cmd(
        "Browser.setDownloadBehavior",
        {"behavior": "allow", "downloadPath": str(output_dir)},
    )
    selected_report: str | None = None
    stable_sizes: dict[Path, int] = {}
    # This timer starts only after Export History has been explicitly opened
    # by ``open_cross_border_export_history``.  Therefore "no new row" here
    # means the history panel was actually inspected, not merely that the
    # finance page had not rendered a badge or popover yet.
    no_new_report_deadline = time.monotonic() + 30
    # Do not immediately toggle an already-open history popover during
    # existing-report recovery; its content intercepts the history button.
    last_history_refresh = time.monotonic()

    def regional_history_visible() -> bool:
        for popover in driver.find_elements(By.CSS_SELECTOR, ".p-popover"):
            try:
                if popover.is_displayed() and ".xlsx" in popover.text:
                    return True
            except StaleElementReferenceException:
                continue
        return False

    while True:
        downloaded = confirmed_download(
            output_dir, known_output_files, selected_report, stable_sizes
        )
        if downloaded is not None:
            print(f"TIKTOK_EXPORT_FILE_CONFIRMED: {downloaded}", flush=True)
            return downloaded.name
        if selected_report is not None:
            time.sleep(0.2)
            continue

        if SELLER_CENTER_PROFILE == "regional":
            now = time.monotonic()
            visible_history = regional_history_visible()
            if not visible_history or now - last_history_refresh >= 30:
                history_toggle = first_visible_enabled(
                    driver,
                    By.CSS_SELECTOR,
                    '[data-tid="finance.action.modules.export_history"]',
                )
                if history_toggle is not None:
                    if visible_history:
                        click_rendered(driver, history_toggle)
                        time.sleep(0.3)
                    click_rendered(driver, history_toggle)
                    last_history_refresh = now
                    time.sleep(0.5)

        visible_reports = report_names(driver)
        if expected_cross_border_range is not None:
            expected_start, expected_end = expected_cross_border_range
            visible_reports = {
                report
                for report in visible_reports
                if (
                    f"{expected_start:%Y/%m/%d}" in report
                    and f"{expected_end:%Y/%m/%d}" in report
                )
            }
        if requested_report is not None:
            candidates = [requested_report] if requested_report in visible_reports else []
        else:
            candidates = []
            for report in visible_reports - known_reports:
                created_at = report_created_at(report)
                if created_at is None:
                    # The pre-submission snapshot is the authoritative boundary.
                    # An unfamiliar regional time-zone label must not block a new report.
                    print(f"TIKTOK_REPORT_TIMEZONE_UNRECOGNIZED: {report}", flush=True)
                    candidates.append(report)
                else:
                    # The pre-submission report snapshot is the authoritative
                    # boundary.  A report can finish more than two minutes
                    # before its red history badge is noticed; rejecting it by
                    # a later wall-clock cutoff leaves a completed Download
                    # button stuck forever.
                    candidates.append(report)
            candidates.sort(reverse=True)
        for candidate in candidates:
            try:
                if SELLER_CENTER_PROFILE == "cross-border":
                    exact_nodes = driver.find_elements(
                        By.XPATH,
                        '//*[contains(normalize-space(.), ".xlsx")]',
                    )
                    matching_nodes = [
                        element
                        for element in exact_nodes
                        if element.is_displayed()
                        and candidate in element.text
                    ]
                    node = min(matching_nodes, key=lambda element: len(element.text), default=None)
                else:
                    exact_nodes = driver.find_elements(
                        By.XPATH, f'//*[normalize-space()="{candidate}"]'
                    )
                    node = next((element for element in exact_nodes if element.is_displayed()), None)
                if node is None:
                    continue
                row = node
                for _ in range(8):
                    if SELLER_CENTER_PROFILE == "cross-border":
                        row_reports = report_names_from_text(row.text)
                        # Never climb into a table/container that contains
                        # several exports: its first Download control may
                        # belong to an older row even though the requested
                        # filename is also somewhere in that container.
                        if row_reports and row_reports != {candidate}:
                            row = row.find_element(By.XPATH, "./parent::*")
                            continue
                    # The report-history action is not consistently rendered as a
                    # <button>: regional Seller Center has used <a> and role=button
                    # variants.  Resolve its stable action semantics within the
                    # exact report row, rather than waiting for a file that was
                    # never requested.
                    row_controls = [
                        control
                        for control in row.find_elements(
                            By.CSS_SELECTOR, "button, a, [role='button']"
                        )
                        if control.is_displayed() and control.is_enabled()
                    ]
                    controls = [
                        control
                        for control in row_controls
                        if (
                            "download" in " ".join(
                                (
                                    control.text or "",
                                    control.get_attribute("aria-label") or "",
                                    control.get_attribute("title") or "",
                                    control.get_attribute("data-tid") or "",
                                    control.get_attribute("class") or "",
                                )
                            ).casefold()
                            or (control.get_attribute("href") or "").strip()
                        )
                    ]
                    # A single rendered action in the exact report row is also
                    # unambiguous, even when the platform localizes the label.
                    if len(controls) == 1 or (not controls and len(row_controls) == 1):
                        control = controls[0] if controls else row_controls[0]
                        if candidate not in row.text:
                            continue
                        click_rendered(driver, control)
                        selected_report = candidate
                        print(
                            f"TIKTOK_EXPORT_DOWNLOAD_CLICKED: {candidate}",
                            flush=True,
                        )
                        break
                    row = row.find_element(By.XPATH, "./parent::*")
                if selected_report is not None:
                    break
            except Exception:
                continue
        if (
            requested_report is None
            and selected_report is None
            and time.monotonic() >= no_new_report_deadline
        ):
            raise NoDataForPeriodError(
                "Export was submitted and Export History was opened, but it "
                "contained no new report job for this request."
            )
        # Report creation is asynchronous and can take an arbitrary amount of
        # time after its row exists. Once the exact new row appears, keep
        # polling without a fixed deadline until its Download action and local
        # file are confirmed.
        time.sleep(0.2)


def open_cross_border_statements_page(
    driver: webdriver.Chrome, wait: WebDriverWait
) -> None:
    """Open the cross-border Statements tab through stable element attributes."""
    if "/finance/bills" not in driver.current_url:
        # The cross-border menu shell changed from a p-menu-item-header to a
        # p-menu-inline container.  Its destination contract remains the
        # stable /finance/bills route, so navigate to that route directly and
        # use the tab's ARIA identity below.  This is language-independent and
        # avoids waiting on a menu animation that may not expose aria-expanded.
        parsed = urllib.parse.urlparse(driver.current_url)
        bills_url = urllib.parse.urlunparse(
            (parsed.scheme, parsed.netloc, "/finance/bills", "", "", "")
        )
        driver.execute_script("window.location.assign(arguments[0]);", bills_url)
    wait.until(lambda current: "/finance/bills" in current.current_url)
    statements_tab = wait.until(
        lambda current: first_visible_enabled(
            current,
            By.CSS_SELECTOR,
            '[role="tab"][aria-controls$="-panel-2"]',
        )
    )
    if statements_tab.get_attribute("aria-selected") != "true":
        click_rendered(driver, statements_tab)
    wait.until(
        lambda current: (
            statements_tab.get_attribute("aria-selected") == "true"
            and first_visible_enabled(
                current,
                By.CSS_SELECTOR,
                '[data-tid="finance.action.modules.search_range"]',
            )
            is not None
        )
    )


def open_cross_border_export_history(
    driver: webdriver.Chrome, wait: WebDriverWait
) -> None:
    """Click Export History and confirm that its panel really opened."""
    def visible_history_panel(current) -> bool:
        for popover in current.find_elements(By.CSS_SELECTOR, ".p-popover"):
            try:
                if not popover.is_displayed():
                    continue
                # The export date dialog contains a primary confirmation
                # button and calendar controls. It is not Export History.
                has_calendar = any(
                    icon.is_displayed()
                    for icon in popover.find_elements(
                        By.CSS_SELECTOR, "svg.arco-icon-calendar"
                    )
                )
                if not has_calendar:
                    return True
            except StaleElementReferenceException:
                continue
        return False

    def history_badge(current) -> bool:
        history_toggle = first_visible_enabled(
            current,
            By.CSS_SELECTOR,
            '[data-tid="finance.action.modules.export_history"]',
        )
        if history_toggle is None:
            return False
        try:
            badge = history_toggle.find_element(
                By.XPATH,
                './ancestor::span[contains(@class, "p-badge")][1]'
                '//*[contains(@class, "p-badge-number")]',
            )
            return badge.is_displayed()
        except Exception:
            return False

    while True:
        history_toggle = first_visible_enabled(
            driver,
            By.CSS_SELECTOR,
            '[data-tid="finance.action.modules.export_history"]',
        )
        if history_toggle is not None:
            click_rendered(driver, history_toggle)
            try:
                WebDriverWait(driver, 10, poll_frequency=0.2).until(
                    visible_history_panel
                )
                print("TIKTOK_EXPORT_HISTORY_OPENED", flush=True)
                return
            except TimeoutException:
                print(
                    "TIKTOK_EXPORT_HISTORY_CLICK_NOT_CONFIRMED: retrying",
                    flush=True,
                )
        time.sleep(1)


def open_tiktok_withdrawals(
    driver: webdriver.Chrome,
    start_date: dt.date,
    end_date: dt.date,
    output_dir: Path = DEFAULT_OUTPUT_DIR,
    on_export_submitted: Callable[[set[str]], None] | None = None,
) -> dict[str, str]:
    """Open the active profile's finance report and export the requested date range."""
    # Use stable route/data hooks so Seller Center language does not affect the flow.
    wait = WebDriverWait(driver, 120)
    dismiss_advertisement_modal(driver)
    if SELLER_CENTER_PROFILE == "cross-border":
        open_cross_border_statements_page(driver, wait)
    elif "/finance/withdraw-new" not in driver.current_url:
        # Skip the Finance side-menu animation.  The route is a stable,
        # language-independent Seller Center contract; the export controls
        # below remain the readiness gate before any report action.
        parsed = urllib.parse.urlparse(driver.current_url)
        withdrawals_url = urllib.parse.urlunparse(
            (parsed.scheme, parsed.netloc, "/finance/withdraw-new", "", "", "")
        )
        driver.execute_script("window.location.assign(arguments[0]);", withdrawals_url)
    if SELLER_CENTER_PROFILE == "regional":
        wait.until(lambda current: "/finance/withdraw-new" in current.current_url)
    # The route can update before Chrome has installed the new document body.
    # Treat that short gap as slow rendering, not a terminal stage failure.
    body = WebDriverWait(driver, 30, poll_frequency=0.25).until(
        lambda current: (
            current.find_elements(By.TAG_NAME, "body") or None
        )
    )[0]
    visible_text = (body.text or "").casefold()
    account_disabled = any(
        marker in visible_text
        for marker in (
            "账号已停用",
            "account has been deactivated",
            "account is deactivated",
        )
    )
    no_results = any(
        marker in visible_text
        for marker in (
            "未找到搜索结果",
            "no search results found",
            "no results found",
        )
    )
    if account_disabled and no_results:
        raise NoDataForPeriodError(
            "Seller Center explicitly reports that the account is disabled "
            "and the statement table has no results."
        )
    known_reports = report_names(driver)

    visible_report_popovers = [
        popover
        for popover in driver.find_elements(By.CSS_SELECTOR, ".p-popover")
        if popover.is_displayed() and ".xlsx" in popover.text
    ]
    if visible_report_popovers:
        history_toggle = wait.until(
            lambda current: first_visible_enabled(
                current,
                By.CSS_SELECTOR,
                '[data-tid="finance.action.modules.export_history"]',
            )
        )
        click_rendered(driver, history_toggle)
        wait.until(
            lambda current: not any(
                popover.is_displayed()
                for popover in current.find_elements(By.CSS_SELECTOR, ".p-popover")
                if ".xlsx" in popover.text
            )
        )
        print("TIKTOK_EXPORT_HISTORY_POPOVER_CLOSED", flush=True)

    def primary_export_button(current):
        try:
            history = current.find_element(
                By.CSS_SELECTOR, '[data-tid="finance.action.modules.export_history"]'
            )
            action_bar = history.find_element(
                By.XPATH, './ancestor::div[contains(@class, "flex")][.//button][1]'
            )
            buttons = action_bar.find_elements(By.TAG_NAME, "button")
            return buttons[0] if buttons and buttons[0].is_displayed() and buttons[0].is_enabled() else None
        except Exception:
            return None

    export_button = wait.until(primary_export_button)
    click_rendered(driver, export_button)
    def date_range_trigger(current):
        # Scope the picker to the actual export dialog.  The withdrawals page
        # also has a main-table date filter; the former broad items-center
        # selector could click that filter and leave the export dialog blank.
        for popover in current.find_elements(By.CSS_SELECTOR, ".p-popover"):
            try:
                if not popover.is_displayed():
                    continue
                buttons = [
                    button
                    for button in popover.find_elements(By.TAG_NAME, "button")
                    if button.is_displayed()
                ]
                if len(buttons) < 2:
                    continue
                final_classes = (buttons[-1].get_attribute("class") or "").split()
                if "p-btn-primary" not in final_classes:
                    continue
                calendar_icons = [
                    icon
                    for icon in popover.find_elements(
                        By.CSS_SELECTOR, "svg.arco-icon-calendar"
                    )
                    if icon.is_displayed()
                ]
                if calendar_icons:
                    return calendar_icons[0].find_element(
                        By.XPATH,
                        './ancestor::div[contains(@class, "items-center")][1]',
                    )
            except StaleElementReferenceException:
                continue
        return None

    click_freshly_resolved(driver, date_range_trigger)
    select_picker_date_range(driver, start_date, end_date)
    print("TIKTOK_EXPORT_DATE_RANGE_SELECTED: confirming the calendar range...", flush=True)
    confirm_and_submit_tiktok_export(driver)
    if on_export_submitted is not None:
        on_export_submitted(set(known_reports))
    print(
        f"TIKTOK_EXPORT_SUBMITTED: {start_date.isoformat()} to {end_date.isoformat()}",
        flush=True,
    )
    # Both regional and cross-border Seller Center render completed exports
    # behind the same stable export-history control.  Open it explicitly after
    # submission; otherwise a regional page can wait forever while the report
    # exists in a closed popover.
    open_cross_border_export_history(driver, wait)
    # The filename embeds the Seller Center's UTC+8 generation time. Allow a
    # small clock skew, then reject lazily loaded historical report rows.
    submitted_after = dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=2)
    downloaded_name = download_latest_tiktok_export_once(
        driver,
        output_dir,
        known_reports,
        submitted_after,
        # Statements exports use income_<generation-time>.xlsx just like the
        # regional flow; the requested dates are not embedded in the filename.
        # The pre-submission history snapshot plus creation time identifies the
        # exact new report without relying on the former On-hold naming scheme.
        expected_cross_border_range=None,
    )
    return {"url": driver.current_url, "title": driver.title, "downloaded_name": downloaded_name}

    # Legacy text-based flow retained below for reference; the stable flow above returns first.
    wait = WebDriverWait(driver, 20)
    finance_xpath = (
        '//div[contains(@class, "p-menu-item-header")]'
        '[.//div[normalize-space()="Finance" or normalize-space()="财务"]]'
    )

    def expand_finance(current) -> bool:
        try:
            finance = current.find_element(By.XPATH, finance_xpath)
            if finance.get_attribute("aria-expanded") == "true":
                return True
            current.execute_script("arguments[0].scrollIntoView({block: 'center'});", finance)
            finance.click()
            return True
        except Exception:
            return False

    wait.until(expand_finance)
    wait.until(
        lambda current: current.find_element(By.XPATH, finance_xpath).get_attribute("aria-expanded") == "true"
    )
    withdrawals = wait.until(
        lambda current: current.find_element(By.CSS_SELECTOR, 'a.sidebar-item-link[href="/finance/withdraw-new"]')
    )
    driver.execute_script("arguments[0].scrollIntoView({block: 'center'});", withdrawals)
    withdrawals.click()
    wait.until(lambda current: "/finance/withdraw-new" in current.current_url)
    known_reports = report_names(driver)
    export_button = wait.until(
        lambda current: current.find_element(By.XPATH, '//button[normalize-space()="Export"]')
    )
    export_button.click()
    wait.until(
        lambda current: any(
            dialog.is_displayed()
            for dialog in current.find_elements(By.CSS_SELECTOR, '[role="dialog"], .p-modal, .p-popover')
        )
    )
    export_popover = wait.until(
        lambda current: current.find_element(
            By.XPATH,
            '//div[normalize-space()="Select a date range to export"]'
            '/ancestor::span[contains(@class, "p-popover")][1]',
        )
    )
    date_range_trigger = export_popover.find_element(
        By.XPATH,
        './/div[contains(@class, "items-center")][.//div[normalize-space()="Start Time"]]',
    )
    date_range_trigger.click()
    select_picker_date_range(driver, start_date, end_date)
    print("TIKTOK_EXPORT_DATE_RANGE_SELECTED: confirming the calendar range...", flush=True)
    # The filename embeds the Seller Center's UTC+8 generation time. Allow a
    # small clock skew, then reject lazily loaded historical report rows.
    submitted_after = dt.datetime.now() - dt.timedelta(minutes=2)
    confirm_and_submit_tiktok_export(driver)
    print(
        f"TIKTOK_EXPORT_SUBMITTED: {start_date.isoformat()} to {end_date.isoformat()}",
        flush=True,
    )
    downloaded_name = download_latest_tiktok_export_once(
        driver, output_dir, known_reports, submitted_after
    )
    return {"url": driver.current_url, "title": driver.title, "downloaded_name": downloaded_name}


def main() -> int:
    parser = argparse.ArgumentParser(description="Start one Feikua shop and open it through Selenium.")
    parser.add_argument("shop_name", help="Exact Feikua shop name")
    parser.add_argument("--stop", action="store_true", help="Stop this shop instead of starting it")
    parser.add_argument(
        "--discover-port",
        action="store_true",
        help="Report the active CDP port for this running shop without opening or changing it",
    )
    parser.add_argument(
        "--inspect",
        action="store_true",
        help="Report the current running shop page and visible status without navigation or export actions",
    )
    parser.add_argument(
        "--connect-only",
        action="store_true",
        help="Start or reuse the exact shop, open its platform page, and preserve the live session",
    )
    parser.add_argument(
        "--inspect-finance",
        action="store_true",
        help="Open the cross-border Financial summary link and inspect it without export actions",
    )
    parser.add_argument(
        "--inspect-unsettled",
        action="store_true",
        help="Open cross-border Statements and inspect it without export actions",
    )
    parser.add_argument(
        "--download-report",
        help="Download one exact already-exported income_*.xlsx report without submitting a new export",
    )
    parser.add_argument("--start-date", help="Required export start date in YYYY-MM-DD format")
    parser.add_argument("--end-date", help="Required export end date in YYYY-MM-DD format")
    parser.add_argument("--out", help="Download directory")
    parser.add_argument(
        "--ad-cost-out",
        help=(
            "After the original TikTok income stage reaches a terminal result, "
            "export every Ads Manager Costs account into this separate directory"
        ),
    )
    parser.add_argument(
        "--ad-costs-only",
        action="store_true",
        help=(
            "Resume only the appended Ads Manager Costs stage after the "
            "monthly coordinator has already confirmed the income stage"
        ),
    )
    args = parser.parse_args()
    if sum(
        (
            args.stop,
            args.discover_port,
            args.inspect,
            args.connect_only,
            args.inspect_finance,
            args.inspect_unsettled,
            bool(args.download_report),
        )
    ) > 1:
        parser.error(
            "--stop, --discover-port, --inspect, --connect-only, --inspect-finance, "
            "--inspect-unsettled, and "
            "--download-report cannot be combined."
        )
    if args.ad_cost_out and any(
        (
            args.stop,
            args.discover_port,
            args.inspect,
            args.connect_only,
            args.inspect_finance,
            args.inspect_unsettled,
            bool(args.download_report),
        )
    ):
        parser.error(
            "--ad-cost-out is only available in the normal date-ranged export "
            "flow and cannot be combined with a utility/inspection mode."
        )
    if args.ad_costs_only and not args.ad_cost_out:
        parser.error("--ad-costs-only requires --ad-cost-out.")
    if (
        not args.stop
        and not args.discover_port
        and not args.inspect
        and not args.connect_only
        and not args.inspect_finance
        and not args.inspect_unsettled
        and not args.download_report
    ):
        start_date, end_date = parse_export_dates(parser, args.start_date, args.end_date)
        print(f"EXPORT_DATE_RANGE: {start_date.isoformat()} to {end_date.isoformat()}", flush=True)
    print("Finding Feikua local service...", flush=True)
    base_url = find_base_url()
    print("Selecting exact shop...", flush=True)
    store = select_store(base_url, args.shop_name)
    if args.stop:
        print("Stopping shop session...", flush=True)
        stop_store(base_url, store["shop_id"])
        print("STOPPED")
        print("shop_name:", store["shop_name"])
        return 0
    if args.discover_port:
        if store["status"] == "idle":
            raise RuntimeError("Shop is idle; it has no active CDP session.")
        running = discover_running_cdp_port(store["shop_id"])
        if running is None:
            raise RuntimeError("Shop is running but no unambiguous TikTok CDP port was found.")
        cdp_port, core_version = running
        print("DISCOVERED_CDP_PORT:", cdp_port)
        print("shop_name:", store["shop_name"])
        print("shop_id:", store["shop_id"])
        print("core_version:", core_version)
        return 0
    if args.inspect or args.inspect_finance or args.inspect_unsettled:
        if store["status"] == "idle":
            raise RuntimeError("Shop is idle; it has no active browser page to inspect.")
        running = discover_running_cdp_port(store["shop_id"])
        if running is None:
            raise RuntimeError("Shop is running but no unambiguous TikTok CDP port was found.")
        cdp_port, core_version = running
        wait_for_cdp(cdp_port)
        inspect_store_url = read_validated_store_url(cdp_port)
        ensure_regular_tab(cdp_port, inspect_store_url)
        driver = attach(cdp_port, chromedriver_path(core_version or "135.0.7049.115"))
        try:
            if args.inspect_finance or args.inspect_unsettled:
                if SELLER_CENTER_PROFILE != "cross-border":
                    raise RuntimeError(
                        "--inspect-finance and --inspect-unsettled are only available "
                        "in cross-border mode."
                    )
                inspect_wait = WebDriverWait(driver, 30)
                if args.inspect_unsettled:
                    open_cross_border_statements_page(driver, inspect_wait)
                    open_cross_border_export_history(driver, inspect_wait)
                elif "/finance/bills" not in driver.current_url:
                    parsed = urllib.parse.urlparse(driver.current_url)
                    bills_url = urllib.parse.urlunparse(
                        (parsed.scheme, parsed.netloc, "/finance/bills", "", "", "")
                    )
                    driver.execute_script(
                        "window.location.assign(arguments[0]);", bills_url
                    )
                    inspect_wait.until(
                        lambda current: "/finance/bills" in current.current_url
                    )
                time.sleep(2)
                if args.inspect_finance:
                    overview_tab = WebDriverWait(driver, 30).until(
                        lambda current: first_visible_enabled(
                            current,
                            By.CSS_SELECTOR,
                            '[role="tab"][aria-controls$="-panel-0"]',
                        )
                    )
                    click_rendered(driver, overview_tab)
                    WebDriverWait(driver, 30).until(
                        lambda current: overview_tab.get_attribute("aria-selected") == "true"
                    )
                    time.sleep(2)
            if args.inspect_unsettled:
                time.sleep(2)
            visible_text = driver.find_element(By.TAG_NAME, "body").text.strip()
            print("INSPECTED_CURRENT_URL:", driver.current_url)
            print("INSPECTED_TITLE:", driver.title)
            print("INSPECTED_VERIFICATION_PRESENT:", verification_present(driver))
            print("INSPECTED_VISIBLE_TEXT_BEGIN")
            print(visible_text[:4000])
            print("INSPECTED_VISIBLE_TEXT_END")
            if SELLER_CENTER_PROFILE == "cross-border":
                finance_links = []
                for anchor in driver.find_elements(By.TAG_NAME, "a"):
                    href = (anchor.get_attribute("href") or "").strip()
                    if not href:
                        continue
                    lowered = href.casefold()
                    if not any(
                        keyword in lowered
                        for keyword in (
                            "finance",
                            "transaction",
                            "settlement",
                            "statement",
                            "balance",
                            "income",
                            "payout",
                        )
                    ):
                        continue
                    finance_links.append(
                        {
                            "text": anchor.text.strip()[:160],
                            "href": href,
                        }
                    )
                print("INSPECTED_CROSS_BORDER_FINANCE_LINKS_BEGIN")
                print(json.dumps(finance_links[:100], ensure_ascii=False, indent=2))
                print("INSPECTED_CROSS_BORDER_FINANCE_LINKS_END")
                controls = []
                for element in driver.find_elements(
                    By.CSS_SELECTOR,
                    "button, [role='button'], [role='tab'], [data-tid], "
                    "a[href*='/finance/bills']",
                ):
                    if not element.is_displayed():
                        continue
                    controls.append(
                        {
                            "tag": element.tag_name,
                            "text": element.text.strip()[:160],
                            "id": element.get_attribute("id") or "",
                            "class": element.get_attribute("class") or "",
                            "role": element.get_attribute("role") or "",
                            "href": element.get_attribute("href") or "",
                            "data_tid": element.get_attribute("data-tid") or "",
                            "aria_label": element.get_attribute("aria-label") or "",
                            "aria_controls": element.get_attribute("aria-controls") or "",
                            "aria_selected": element.get_attribute("aria-selected") or "",
                            "outer_html": (
                                (element.get_attribute("outerHTML") or "")[:1000]
                                if element.text.strip().casefold().startswith("see all")
                                else ""
                            ),
                        }
                    )
                print("INSPECTED_CROSS_BORDER_CONTROLS_BEGIN")
                print(json.dumps(controls[:150], ensure_ascii=False, indent=2))
                print("INSPECTED_CROSS_BORDER_CONTROLS_END")
        finally:
            driver.quit()
        return 0
    output_dir = Path(args.out).expanduser() if args.out else DEFAULT_OUTPUT_DIR
    running = discover_running_cdp_port(store["shop_id"]) if store["status"] != "idle" else None
    if running:
        cdp_port, core_version = running
        print(f"Reusing running shop session on CDP port {cdp_port}...", flush=True)
    else:
        stop_other_running_stores(base_url, store["shop_id"])
        print("Starting isolated shop session...", flush=True)
        started = start_store(base_url, store)
        cdp_port, core_version = started["debugger_port"], started["core_version"]
        save_session(store["shop_id"], cdp_port, core_version)
    try:
        print("Waiting for browser session...", flush=True)
        wait_for_cdp(cdp_port)
        actual_core_version = browser_version_from_cdp(cdp_port)
        if actual_core_version != core_version:
            print(
                "FEIKUA_CORE_VERSION_CORRECTED_FROM_CDP: "
                f"{core_version or '(missing)'} -> {actual_core_version}",
                flush=True,
            )
        core_version = actual_core_version
        save_session(store["shop_id"], cdp_port, core_version)
        if running:
            with urllib.request.urlopen(
                f"http://127.0.0.1:{cdp_port}/json/list", timeout=15
            ) as response:
                targets = json.load(response)
            store_url = next(
                (
                    target.get("url", "")
                    for target in targets
                    if target.get("type") == "page"
                    and (
                        is_tiktok_seller_center(target.get("url", ""))
                        or (
                            args.connect_only
                            and target.get("url", "").startswith(("http://", "https://"))
                        )
                    )
                ),
                "",
            )
            if not store_url:
                store_url = read_validated_store_url(cdp_port)
        else:
            print("Waiting for validated store URL...", flush=True)
            store_url = read_validated_store_url(cdp_port)
        # Feikua may start on an extension page; ChromeDriver needs a normal tab to attach.
        ensure_regular_tab(cdp_port, store_url)
        print("Opening store through Selenium...", flush=True)
        if not core_version:
            core_version = "135.0.7049.115"
        driver = attach(cdp_port, chromedriver_path(core_version))
    except Exception:
        request_store_stop(base_url, store["shop_id"])
        print("STOP_REQUESTED_AFTER_CONNECTION_FAILURE", flush=True)
        raise
    close_store = False
    seller_home_not_reached = False
    completed = False
    no_data_for_period = False
    export_submitted = False
    ad_cost_enabled = bool(args.ad_cost_out)
    ad_cost_completed = not ad_cost_enabled
    ad_cost_error: Exception | None = None
    ad_login_otp_required = False
    ad_login_credentials_rejected = False
    store_disabled = False
    manual_verification_pending = False
    submitted_after: dt.datetime | None = None
    known_reports_at_submission: set[str] = set()
    execution_error: Exception | None = None
    stage = "open_store"

    def mark_export_submitted(known_reports: set[str]) -> None:
        nonlocal export_submitted, submitted_after
        nonlocal known_reports_at_submission, stage
        export_submitted = True
        submitted_after = dt.datetime.now(dt.timezone.utc) - dt.timedelta(minutes=2)
        known_reports_at_submission = set(known_reports)
        stage = "report_generation_and_download"

    def run_appended_ad_cost_stage() -> None:
        """Run the optional regional ad phase after any income terminal path."""
        nonlocal ad_cost_completed, ad_cost_error, store_disabled
        nonlocal ad_login_otp_required, ad_login_credentials_rejected
        nonlocal manual_verification_pending, stage
        if (
            not ad_cost_enabled
            or ad_cost_completed
            or ad_cost_error is not None
            or manual_verification_pending
        ):
            return

        stage = "advertising_cost_export"
        try:
            ad_cost_output_dir = Path(args.ad_cost_out).expanduser()
            # TikTok can inject its full-screen challenge shortly after Seller
            # Center has rendered. Reuse the original human takeover kernel;
            # never automate or bypass the challenge.
            time.sleep(2)
            if _ads_login_otp_visible(driver):
                ad_login_otp_required = True
                print(
                    "TIKTOK_AD_LOGIN_OTP_REQUIRED: 广告登录需要验证码",
                    flush=True,
                )
                return
            if verification_present(driver):
                print(
                    "TIKTOK_AD_COST_MANUAL_VERIFICATION_REQUIRED: "
                    "complete the visible challenge in the exact Feikua shop "
                    "window.",
                    flush=True,
                )
                if not wait_for_human_takeover(driver, timeout=45):
                    manual_verification_pending = True
                    print(
                        "TIKTOK_AD_COST_MANUAL_VERIFICATION_PENDING_"
                        "WINDOW_PRESERVED",
                        flush=True,
                    )
            if manual_verification_pending:
                return

            try:
                ad_cost_results = export_all_ad_cost_accounts(
                    driver=driver,
                    seller_center_url=store_url,
                    shop_name=store["shop_name"],
                    start_date=start_date,
                    end_date=end_date,
                    output_dir=ad_cost_output_dir,
                )
            except Exception as error:
                if isinstance(error, AdsCostLoginCredentialsRejected):
                    ad_login_credentials_rejected = True
                    print(
                        "TIKTOK_AD_LOGIN_CREDENTIALS_REJECTED: "
                        "广告账号密码错误",
                        flush=True,
                    )
                    return
                if isinstance(error, AdsCostLoginOtpRequired):
                    ad_login_otp_required = True
                    print(
                        "TIKTOK_AD_LOGIN_OTP_REQUIRED: 广告登录需要验证码",
                        flush=True,
                    )
                    return
                if _ads_login_otp_visible(driver):
                    ad_login_otp_required = True
                    print(
                        "TIKTOK_AD_LOGIN_OTP_REQUIRED: 广告登录需要验证码",
                        flush=True,
                    )
                    return
                # A challenge can also arrive only after Marketing is opened.
                # Hand the same Feikua window to the user and retry the
                # checkpointed ad phase once after verification succeeds.
                lowered_error = str(error).casefold()
                try:
                    challenge_present = verification_present(driver)
                except Exception:
                    challenge_present = False
                transient_renderer_error = is_transient_renderer_timeout(error)
                if not (
                    isinstance(error, AdsCostVerificationRequired)
                    or transient_renderer_error
                    or challenge_present
                    or "verification" in lowered_error
                    or "captcha" in lowered_error
                    or "challenge" in lowered_error
                ):
                    raise
                if transient_renderer_error and not challenge_present:
                    print(
                        "TIKTOK_AD_COST_TRANSIENT_PHASE_RETRY: Ads Manager "
                        "target is alive; retrying the checkpoint without "
                        "claiming manual verification",
                        flush=True,
                    )
                else:
                    print(
                        "TIKTOK_AD_COST_MANUAL_VERIFICATION_REQUIRED: "
                        "complete the visible challenge in the exact Feikua "
                        "shop window.",
                        flush=True,
                    )
                    if not wait_for_human_takeover(driver, timeout=45):
                        manual_verification_pending = True
                        print(
                            "TIKTOK_AD_COST_MANUAL_VERIFICATION_PENDING_"
                            "WINDOW_PRESERVED",
                            flush=True,
                        )
                        return
                ad_cost_results = export_all_ad_cost_accounts(
                    driver=driver,
                    seller_center_url=store_url,
                    shop_name=store["shop_name"],
                    start_date=start_date,
                    end_date=end_date,
                    output_dir=ad_cost_output_dir,
                )

            ad_cost_completed = True
            print(
                "TIKTOK_AD_COST_APPENDED_PHASE_COMPLETE_JSON:",
                json.dumps(
                    {
                        "shopName": store["shop_name"],
                        "startDate": start_date.isoformat(),
                        "endDate": end_date.isoformat(),
                        "outputDir": str(ad_cost_output_dir.resolve()),
                        "results": ad_cost_results,
                    },
                    ensure_ascii=False,
                    separators=(",", ":"),
                ),
                flush=True,
            )
        except AdsCostStoreDisabled as error:
            store_disabled = True
            print(
                f"TIKTOK_STORE_DISABLED_DETECTED: {error}",
                flush=True,
            )
        except Exception as error:
            ad_cost_error = error
            print(
                "TIKTOK_STAGE_FAILED: advertising_cost_export: "
                f"{type(error).__name__}: {error}",
                flush=True,
            )

    try:
        try:
            opened = (
                current_store_page(driver)
                if running
                and (
                    is_tiktok_seller_center(driver.current_url)
                    or (
                        args.connect_only
                        and driver.current_url.startswith(("http://", "https://"))
                    )
                )
                else open_store(driver, store_url)
            )
            activate_tiktok_profile_for_url(opened["url"])
            disabled_marker = store_disabled_reason(driver)
            if disabled_marker:
                store_disabled = True
                print(
                    "TIKTOK_STORE_DISABLED_DETECTED: "
                    f"TikTok Seller Center displayed {disabled_marker}",
                    flush=True,
                )
                initial_verification_present = False
            else:
                initial_verification_present = verification_present(driver)
        except (TimeoutException, WebDriverException) as error:
            if not is_transient_renderer_timeout(error):
                raise
            # No execution marker or export click exists at this point. Rebind
            # ChromeDriver to the same exact Feikua CDP once; never restart the
            # shop and never create a second browser.
            print("FEIKUA_OPEN_STORE_SAME_CDP_REATTACH_RETRY", flush=True)
            try:
                driver.service.stop()
            except Exception:
                pass
            wait_for_cdp(cdp_port)
            driver = attach(cdp_port, chromedriver_path(core_version))
            opened = open_store(driver, store_url)
            activate_tiktok_profile_for_url(opened["url"])
            initial_verification_present = verification_present(driver)

        if store_disabled:
            pass
        elif initial_verification_present:
            if args.connect_only:
                print(
                    "MANUAL_VERIFICATION_REQUIRED_SESSION_PRESERVED: "
                    "complete it in the live shop window.",
                    flush=True,
                )
                opened = {"url": driver.current_url, "title": driver.title}
            elif args.ad_costs_only:
                print(
                    "TIKTOK_AD_COST_MANUAL_VERIFICATION_REQUIRED: "
                    "complete the visible challenge in the exact Feikua shop "
                    "window.",
                    flush=True,
                )
                if not wait_for_human_takeover(driver, timeout=45):
                    manual_verification_pending = True
                    print(
                        "TIKTOK_AD_COST_MANUAL_VERIFICATION_PENDING_"
                        "WINDOW_PRESERVED",
                        flush=True,
                    )
                opened = {"url": driver.current_url, "title": driver.title}
            else:
                login_status = attempt_autofilled_tiktok_email_login(driver)
                if login_status == "failed":
                    close_store = True
                elif login_status == "manual_verification":
                    print(
                        "TIKTOK_LOGIN_MANUAL_VERIFICATION_REQUIRED: "
                        "complete the visible slider in the exact Feikua shop window.",
                        flush=True,
                    )
                    if not wait_for_human_takeover(driver, timeout=120):
                        manual_verification_pending = True
                    else:
                        try:
                            WebDriverWait(driver, 60, poll_frequency=0.5).until(
                                lambda current: (
                                    is_tiktok_seller_center(current.current_url)
                                    or not verification_present(current)
                                )
                            )
                        except TimeoutException:
                            pass
                elif verification_present(driver):
                    close_store = True
                if not close_store:
                    opened = {"url": driver.current_url, "title": driver.title}
        activate_tiktok_profile_for_url(driver.current_url)
        remaining_verification = verification_present(driver)
        if (
            args.ad_costs_only
            and is_tiktok_seller_center(driver.current_url)
            and remaining_verification
            and not manual_verification_pending
        ):
            print(
                "TIKTOK_AD_COST_MANUAL_VERIFICATION_REQUIRED: "
                "closing only the visible challenge X; preserving the exact "
                "Feikua shop window.",
                flush=True,
            )
            if wait_for_human_takeover(driver, timeout=45):
                remaining_verification = verification_present(driver)
            else:
                manual_verification_pending = True
                print(
                    "TIKTOK_AD_COST_MANUAL_VERIFICATION_PENDING_"
                    "WINDOW_PRESERVED",
                    flush=True,
                )
        if (
            not args.connect_only
            and not manual_verification_pending
            and (
                close_store
                or not is_tiktok_seller_center(driver.current_url)
                or remaining_verification
            )
        ):
            seller_home_not_reached = True
            close_store = True
            opened = {"url": driver.current_url, "title": driver.title}
            print(
                "TIKTOK_SELLER_HOME_NOT_REACHED: "
                f"url={driver.current_url}",
                flush=True,
            )
        if args.download_report:
            if not is_tiktok_seller_center(opened["url"]):
                raise RuntimeError("The current page is not a TikTok Shop Seller Center page.")
            if SELLER_CENTER_PROFILE == "cross-border":
                recovery_wait = WebDriverWait(driver, 30)
                open_cross_border_statements_page(driver, recovery_wait)
                open_cross_border_export_history(driver, recovery_wait)
            else:
                recovery_wait = WebDriverWait(driver, 60)
                if "/finance/withdraw-new" not in driver.current_url:
                    parsed = urllib.parse.urlparse(driver.current_url)
                    withdrawals_url = urllib.parse.urlunparse(
                        (
                            parsed.scheme,
                            parsed.netloc,
                            "/finance/withdraw-new",
                            "",
                            "",
                            "",
                        )
                    )
                    driver.execute_script(
                        "window.location.assign(arguments[0]);", withdrawals_url
                    )
                    recovery_wait.until(
                        lambda current: "/finance/withdraw-new" in current.current_url
                    )
                if args.download_report not in report_names(driver):
                    history_toggle = recovery_wait.until(
                        lambda current: first_visible_enabled(
                            current,
                            By.CSS_SELECTOR,
                            '[data-tid="finance.action.modules.export_history"]',
                        )
                    )
                    click_rendered(driver, history_toggle)
                    recovery_wait.until(
                        lambda current: args.download_report in report_names(current)
                    )
            print(f"TIKTOK_EXISTING_EXPORT_DOWNLOAD_REQUESTED: {args.download_report}", flush=True)
            downloaded_name = download_latest_tiktok_export_once(
                driver,
                output_dir,
                set(),
                None,
                requested_report=args.download_report,
            )
            opened = {"url": driver.current_url, "title": driver.title, "downloaded_name": downloaded_name}
            completed = True
        elif args.connect_only:
            print("STORE_SESSION_READY", flush=True)
        elif (
            args.ad_costs_only
            and not close_store
            and not manual_verification_pending
            and is_tiktok_seller_center(opened["url"])
        ):
            print(
                "TIKTOK_AD_COSTS_ONLY_RESUME_STARTED: "
                f"range={start_date.isoformat()}..{end_date.isoformat()}",
                flush=True,
            )
            # In ad-only mode, the monthly coordinator has already validated
            # the original income terminal result. Mark the shared lifecycle
            # path complete without touching Finance, then resume checkpointed
            # advertising accounts only.
            completed = True
            run_appended_ad_cost_stage()
        elif (
            not args.ad_costs_only
            and not close_store
            and is_tiktok_seller_center(opened["url"])
        ):
            print(
                "TIKTOK_EXPORT_EXECUTION_STARTED: "
                f"range={start_date.isoformat()}..{end_date.isoformat()}",
                flush=True,
            )
            print("TIKTOK_SELLER_CENTER_DETECTED: opening Finance > Withdrawals...", flush=True)
            stage = "finance_navigation_and_export_submission"
            try:
                opened = open_tiktok_withdrawals(
                    driver,
                    start_date,
                    end_date,
                    output_dir,
                    on_export_submitted=mark_export_submitted,
                )
                completed = True
            except NoDataForPeriodError as error:
                no_data_for_period = True
                print(f"TIKTOK_NO_DATA_FOR_PERIOD: {error}", flush=True)
            if ad_cost_enabled and (completed or no_data_for_period):
                run_appended_ad_cost_stage()
    except Exception as error:
        execution_error = error
        print(
            f"TIKTOK_STAGE_FAILED: {stage}: {type(error).__name__}: {error}",
            flush=True,
        )
        if export_submitted:
            recovery_attempt = 0
            print(
                "TIKTOK_SUBMITTED_EXPORT_RECOVERY_STARTED: "
                "the slot remains occupied and the export will not be resubmitted.",
                flush=True,
            )
            while execution_error is not None:
                recovery_attempt += 1
                try:
                    try:
                        driver.service.stop()
                    except Exception:
                        pass
                    base_url = find_base_url()
                    current = select_store(base_url, args.shop_name)
                    recovered = (
                        discover_running_cdp_port(current["shop_id"])
                        if current.get("status") != "idle"
                        else None
                    )
                    if recovered is None:
                        print(
                            "TIKTOK_SUBMITTED_EXPORT_SESSION_RESTARTING: "
                            "recovering export history without resubmission.",
                            flush=True,
                        )
                        started = start_store(base_url, current)
                        cdp_port = int(started["debugger_port"])
                        core_version = str(started.get("core_version", ""))
                        save_session(current["shop_id"], cdp_port, core_version)
                    else:
                        cdp_port, core_version = recovered
                    wait_for_cdp(cdp_port)
                    actual_core_version = browser_version_from_cdp(cdp_port)
                    if actual_core_version:
                        core_version = actual_core_version
                    targets = cdp_targets(cdp_port)
                    store_url = next(
                        (
                            target.get("url", "")
                            for target in targets
                            if target.get("type") == "page"
                            and is_tiktok_seller_center(target.get("url", ""))
                        ),
                        "",
                    )
                    if not store_url:
                        store_url = read_validated_store_url(cdp_port)
                    ensure_regular_tab(cdp_port, store_url)
                    driver = attach(cdp_port, chromedriver_path(core_version))
                    opened = (
                        current_store_page(driver)
                        if is_tiktok_seller_center(driver.current_url)
                        else open_store(driver, store_url)
                    )
                    if not is_tiktok_seller_center(opened["url"]):
                        raise RuntimeError(
                            "Recovered session has not reached TikTok Seller Center."
                        )
                    recovery_wait = WebDriverWait(driver, 120)
                    if SELLER_CENTER_PROFILE == "cross-border":
                        open_cross_border_statements_page(driver, recovery_wait)
                    elif "/finance/withdraw-new" not in driver.current_url:
                        parsed = urllib.parse.urlparse(driver.current_url)
                        withdrawals_url = urllib.parse.urlunparse(
                            (
                                parsed.scheme,
                                parsed.netloc,
                                "/finance/withdraw-new",
                                "",
                                "",
                                "",
                            )
                        )
                        driver.execute_script(
                            "window.location.assign(arguments[0]);",
                            withdrawals_url,
                        )
                        recovery_wait.until(
                            lambda current_driver: (
                                "/finance/withdraw-new"
                                in current_driver.current_url
                            )
                        )
                    open_cross_border_export_history(driver, recovery_wait)
                    downloaded_name = download_latest_tiktok_export_once(
                        driver,
                        output_dir,
                        known_reports_at_submission,
                        submitted_after,
                    )
                    opened = {
                        "url": driver.current_url,
                        "title": driver.title,
                        "downloaded_name": downloaded_name,
                    }
                    completed = True
                    execution_error = None
                    print(
                        "TIKTOK_SUBMITTED_EXPORT_RECOVERED_WITHOUT_RESUBMISSION",
                        flush=True,
                    )
                    run_appended_ad_cost_stage()
                except Exception as recovery_error:
                    print(
                        "TIKTOK_SUBMITTED_EXPORT_RECOVERY_RETRY: "
                        f"attempt={recovery_attempt} "
                        f"{type(recovery_error).__name__}: {recovery_error}",
                        flush=True,
                    )
                    time.sleep(30)
    finally:
        # After Seller Center accepts an export, preserve the live browser if a
        # later error occurs. A pending report must be allowed to finish without
        # a fixed deadline; only a confirmed result or explicit terminal state
        # can close it.
        if args.connect_only:
            # Shopee uses connect-only to prepare Feikua, then attaches its own
            # platform executor to the same CDP port. Sending WebDriver.quit()
            # here can close the only store window before that handoff. Stop
            # only this temporary ChromeDriver service and leave Chrome intact.
            try:
                driver.service.stop()
            except Exception:
                pass
            print("STORE_SESSION_DETACHED_WITH_WINDOW_PRESERVED", flush=True)
        elif ad_login_otp_required or ad_login_credentials_rejected:
            driver.quit()
        elif manual_verification_pending:
            try:
                driver.service.stop()
            except Exception:
                pass
            print(
                "TIKTOK_AD_COST_MANUAL_VERIFICATION_SESSION_PRESERVED",
                flush=True,
            )
        elif ad_cost_error is not None:
            # Advertising navigation/download faults are recoverable from the
            # task checkpoint.  Detach this ChromeDriver only; never close the
            # exact Feikua shop window merely because Ads Manager stalled or
            # changed routes.
            try:
                driver.service.stop()
            except Exception:
                pass
            print(
                "TIKTOK_AD_COST_RECOVERABLE_SESSION_PRESERVED",
                flush=True,
            )
        elif (
            store_disabled
            or not export_submitted
            or completed
            or no_data_for_period
            or close_store
        ):
            driver.quit()
        else:
            print(
                "TIKTOK_EXPORT_SUBMITTED_SESSION_PRESERVED: "
                "awaiting manual recovery without closing the shop.",
                flush=True,
            )
    if ad_login_otp_required:
        stop_store(base_url, store["shop_id"])
        print("TIKTOK_AD_LOGIN_OTP_REQUIRED_CONNECTION_STOPPED")
        return 0
    if ad_login_credentials_rejected:
        stop_store(base_url, store["shop_id"])
        print("TIKTOK_AD_LOGIN_CREDENTIALS_REJECTED_CONNECTION_STOPPED")
        return 0
    if manual_verification_pending:
        print(
            "TIKTOK_AD_COST_MANUAL_VERIFICATION_PENDING: "
            "the exact Feikua shop window remains open.",
            flush=True,
        )
        return 23
    if store_disabled:
        print(
            "TikTok account/store is disabled or invalid; force-closing this "
            "exact Feikua shop session...",
            flush=True,
        )
        stop_store(base_url, store["shop_id"])
        print("TIKTOK_STORE_DISABLED_CONNECTION_STOPPED")
        return 0
    if close_store:
        print("Seller Center home was not reached; closing this shop session...", flush=True)
        stop_store(base_url, store["shop_id"])
        if seller_home_not_reached:
            print("TIKTOK_SELLER_HOME_NOT_REACHED_CONNECTION_STOPPED")
            return 24
        print("STOPPED_AFTER_LOGIN_TIMEOUT")
        return 0
    if ad_cost_error is not None:
        print(
            "Advertising cost stage failed after the original income stage; "
            "preserving this exact shop session for checkpointed recovery...",
            flush=True,
        )
        print("TIKTOK_AD_COST_RECOVERABLE_FAILURE")
        return 1
    if no_data_for_period:
        if ad_cost_enabled and not ad_cost_completed:
            print(
                "Income reached no-data but the appended advertising cost "
                "stage is not terminal; closing this exact shop session...",
                flush=True,
            )
            stop_store(base_url, store["shop_id"])
            print("STOPPED_AFTER_INCOMPLETE_AD_COST_STAGE")
            return 1
        print("No report was generated for the requested period; closing shop session...", flush=True)
        stop_store(base_url, store["shop_id"])
        print("TIKTOK_NO_DATA_FOR_PERIOD")
        return 0
    if execution_error is not None:
        if export_submitted:
            print(
                "TIKTOK_EXPORT_SUBMITTED_SESSION_PRESERVED: "
                "report generation or download recovery remains pending.",
                flush=True,
            )
            return 1
        print("Execution stage failed; closing shop session...", flush=True)
        stop_store(base_url, store["shop_id"])
        print("STOPPED_AFTER_STAGE_FAILURE")
        return 1
    if completed:
        if ad_cost_enabled and not ad_cost_completed:
            raise RuntimeError(
                "TikTok income reached a terminal state but the appended "
                "advertising cost stage did not."
            )
        # The confirmed workbook is the task result. Feikua may take 15+ seconds
        # to release a profile after acknowledging stop, so request the close
        # once and let the next handoff (or final explicit stop) observe the
        # exact idle/CDP event instead of turning a good export into a failure.
        print("Export complete; requesting shop close...", flush=True)
        request_store_stop(base_url, store["shop_id"])
        print("STOP_REQUESTED_AFTER_EXPORT")
    print("CONNECTED")
    print("shop_name:", store["shop_name"])
    print("shop_id:", store["shop_id"])
    print("feikua_base_url:", base_url)
    print("debugger_port:", cdp_port)
    print("core_version:", core_version)
    print("store_url:", store_url)
    print("current_url:", opened["url"])
    print("title:", opened["title"])
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        traceback.print_exc()
        raise SystemExit(1)
