"""Open one exact Feikua Lazada shop and reach Seller Center safely."""

from __future__ import annotations

import argparse
import datetime as dt
import importlib.util
import json
import os
import re
import shutil
import sys
import time
import traceback
import urllib.error
import urllib.request
import zipfile
from pathlib import Path
from urllib.parse import parse_qsl, urlencode, urlparse, urlsplit, urlunsplit

from selenium.common.exceptions import (
    ElementClickInterceptedException,
    ElementNotInteractableException,
    StaleElementReferenceException,
    WebDriverException,
)
from selenium.webdriver.common.by import By
from selenium.webdriver.common.keys import Keys
from selenium.webdriver.support.ui import WebDriverWait
from websocket import create_connection


RUNTIME_DIR = Path(
    os.environ.get(
        "LAZADA_BILL_RUNTIME_DIR",
        str(Path.home() / "AppData" / "Local" / "lazada-bill"),
    )
)
os.environ["TK_BILL_RUNTIME_DIR"] = str(RUNTIME_DIR)
TK_KERNEL_PATH = (
    Path(__file__).resolve().parents[2] / "tk-bill" / "scripts" / "feikua_store.py"
)


def load_connection_kernel():
    if not TK_KERNEL_PATH.exists():
        raise RuntimeError(f"The shared Feikua connection kernel was not found: {TK_KERNEL_PATH}")
    spec = importlib.util.spec_from_file_location("lazada_feikua_kernel", TK_KERNEL_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("Could not load the shared Feikua connection kernel.")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


kernel = load_connection_kernel()
ACTIVE_STORE_CONTEXT: dict[str, str] = {}


class InvalidCredentialsError(RuntimeError):
    """Raised only when Lazada explicitly rejects the submitted credentials."""


class NoLazadaFileError(RuntimeError):
    """Raised when an opened Lazada export menu contains no file option."""


class LazadaAccountDeactivatedError(RuntimeError):
    """Raised when Lazada confirms that the seller account/store is offline."""


INVALID_CREDENTIAL_PATTERNS = (
    r"\b(?:account|username|email|phone).{0,40}(?:password|credentials?).{0,40}"
    r"(?:incorrect|invalid|wrong|not correct)\b",
    r"\b(?:incorrect|invalid|wrong).{0,40}(?:password|credentials?)\b",
    r"\b(?:login|sign[\s-]?in).{0,30}(?:failed|unsuccessful)\b",
    r"(?:账号|帐号|账户|帐户).{0,12}(?:密码).{0,12}(?:错误|不正确|无效)",
    r"(?:密码).{0,12}(?:错误|不正确|无效)",
    r"(?:ชื่อบัญชี|บัญชี|อีเมล|เบอร์โทร).{0,40}(?:รหัสผ่าน).{0,40}(?:ไม่ถูกต้อง|ผิด)",
    r"(?:รหัสผ่าน).{0,20}(?:ไม่ถูกต้อง|ผิด)",
    r"(?:account|password).{0,40}(?:mali|salah|tidak benar)",
)


def is_invalid_credentials_text(text: str) -> bool:
    normalized = re.sub(r"\s+", " ", text or "").strip().casefold()
    return any(re.search(pattern, normalized, re.IGNORECASE) for pattern in INVALID_CREDENTIAL_PATTERNS)


def matching_chromedriver(core_version: str) -> Path:
    """Resolve every Chrome generation through the shared atomic driver cache."""
    return kernel.chromedriver_path(core_version)


def lazada_page_target(port: int) -> dict:
    deadline = time.monotonic() + 15
    observed_pages: set[str] = set()
    while time.monotonic() < deadline:
        try:
            targets = kernel.cdp_targets(port)
        except (
            OSError,
            TimeoutError,
            ValueError,
            urllib.error.URLError,
        ):
            time.sleep(0.3)
            continue
        pages = [
            item
            for item in targets
            if item.get("type") == "page" and is_lazada_url(item.get("url", ""))
        ]
        pages.sort(
            key=lambda item: (
                0
                if is_seller_center(item.get("url", ""))
                else 1
                if "/apps/seller/login" in item.get("url", "")
                else 2
            )
        )
        if pages:
            return pages[0]
        for item in targets:
            if item.get("type") != "page":
                continue
            parsed = urlparse(item.get("url", ""))
            if parsed.hostname:
                observed_pages.add(f"{parsed.hostname}{parsed.path}")
        time.sleep(0.3)
    observed = ", ".join(sorted(observed_pages)[:6]) or "(no regular page)"
    raise RuntimeError(
        "The Lazada page target was not found in the Feikua session after "
        f"15 seconds; observed: {observed}"
    )


def cdp_evaluate(port: int, expression: str):
    """Evaluate JavaScript in the current Lazada page without an Origin header."""
    target = lazada_page_target(port)
    socket = create_connection(
        target["webSocketDebuggerUrl"],
        suppress_origin=True,
        timeout=20,
    )
    try:
        command_id = 1
        socket.send(
            json.dumps(
                {
                    "id": command_id,
                    "method": "Runtime.evaluate",
                    "params": {
                        "expression": expression,
                        "returnByValue": True,
                        "awaitPromise": True,
                    },
                }
            )
        )
        while True:
            payload = json.loads(socket.recv())
            if payload.get("id") != command_id:
                continue
            if "error" in payload:
                raise RuntimeError(f"CDP Runtime.evaluate failed: {payload['error']}")
            result = payload.get("result", {}).get("result", {})
            if result.get("subtype") == "error":
                raise RuntimeError(
                    f"JavaScript evaluation failed: {result.get('description', '')}"
                )
            return result.get("value")
    finally:
        socket.close()


def cdp_command(port: int, method: str, params: dict | None = None):
    """Run one raw CDP command against the current Lazada page target."""
    target = lazada_page_target(port)
    socket = create_connection(
        target["webSocketDebuggerUrl"],
        suppress_origin=True,
        timeout=20,
    )
    try:
        socket.send(
            json.dumps(
                {
                    "id": 1,
                    "method": method,
                    "params": params or {},
                }
            )
        )
        while True:
            payload = json.loads(socket.recv())
            if payload.get("id") != 1:
                continue
            if "error" in payload:
                raise RuntimeError(f"CDP {method} failed: {payload['error']}")
            return payload.get("result", {})
    finally:
        socket.close()


def cdp_mouse_click(port: int, x: float, y: float) -> None:
    target = lazada_page_target(port)
    socket = create_connection(
        target["webSocketDebuggerUrl"],
        suppress_origin=True,
        timeout=20,
    )
    try:
        command_id = 0
        for event_type, extra in (
            ("mouseMoved", {}),
            ("mousePressed", {"button": "left", "clickCount": 1}),
            ("mouseReleased", {"button": "left", "clickCount": 1}),
        ):
            command_id += 1
            socket.send(
                json.dumps(
                    {
                        "id": command_id,
                        "method": "Input.dispatchMouseEvent",
                        "params": {
                            "type": event_type,
                            "x": float(x),
                            "y": float(y),
                            **extra,
                        },
                    }
                )
            )
            while True:
                payload = json.loads(socket.recv())
                if payload.get("id") == command_id:
                    if "error" in payload:
                        raise RuntimeError(
                            f"CDP mouse event failed: {payload['error']}"
                        )
                    break
    finally:
        socket.close()


def cdp_key_event(
    port: int,
    event_type: str,
    key: str,
    *,
    code: str | None = None,
    modifiers: int = 0,
) -> None:
    """Dispatch one trusted keyboard event to the active Lazada page."""
    target = lazada_page_target(port)
    socket = create_connection(
        target["webSocketDebuggerUrl"],
        suppress_origin=True,
        timeout=20,
    )
    try:
        socket.send(
            json.dumps(
                {
                    "id": 1,
                    "method": "Input.dispatchKeyEvent",
                    "params": {
                        "type": event_type,
                        "key": key,
                        "code": code or key,
                        "modifiers": modifiers,
                    },
                }
            )
        )
        while True:
            payload = json.loads(socket.recv())
            if payload.get("id") == 1:
                if "error" in payload:
                    raise RuntimeError(f"CDP keyboard event failed: {payload['error']}")
                return
    finally:
        socket.close()


def cdp_insert_text(port: int, text: str) -> None:
    """Insert text as a trusted browser input operation."""
    target = lazada_page_target(port)
    socket = create_connection(
        target["webSocketDebuggerUrl"],
        suppress_origin=True,
        timeout=20,
    )
    try:
        socket.send(
            json.dumps(
                {
                    "id": 1,
                    "method": "Input.insertText",
                    "params": {"text": text},
                }
            )
        )
        while True:
            payload = json.loads(socket.recv())
            if payload.get("id") == 1:
                if "error" in payload:
                    raise RuntimeError(f"CDP text input failed: {payload['error']}")
                return
    finally:
        socket.close()


def cdp_replace_focused_text(port: int, text: str) -> None:
    """Replace the text in the currently focused field using trusted input."""
    target = lazada_page_target(port)
    socket = create_connection(
        target["webSocketDebuggerUrl"],
        suppress_origin=True,
        timeout=20,
    )
    try:
        socket.send(
            json.dumps(
                {
                    "id": 1,
                    "method": "Input.dispatchKeyEvent",
                    "params": {
                        "type": "rawKeyDown",
                        "key": "a",
                        "code": "KeyA",
                        "windowsVirtualKeyCode": 65,
                        "nativeVirtualKeyCode": 65,
                        "modifiers": 2,
                        "commands": ["selectAll"],
                    },
                }
            )
        )
        while True:
            payload = json.loads(socket.recv())
            if payload.get("id") == 1:
                if "error" in payload:
                    raise RuntimeError(f"CDP select-all failed: {payload['error']}")
                break
    finally:
        socket.close()
    cdp_insert_text(port, text)


def cdp_safe_popup_close_target(port: int):
    return cdp_evaluate(
        port,
        """
        (() => {
          const shown = el => {
            const box = el.getBoundingClientRect();
            const style = getComputedStyle(el);
            return box.width > 0 && box.height > 0 &&
              style.visibility !== 'hidden' && style.display !== 'none' &&
              Number(style.opacity || 1) > 0;
          };
          const securityText = [
            'captcha','verification','verify','security','otp','one-time',
            'password','login','sms','验证码','验证','安全','密码','登录',
            'รหัสผ่าน','เข้าสู่ระบบ','ความปลอดภัย'
          ];
          const dialogs = [...document.querySelectorAll(
            "[role='dialog'],.next-dialog,.ant-modal,[class*='modal']," +
            "[class*='popup'],[class*='pop-up'],[class*='dialog']"
          )].filter(shown).reverse();
          for (const dialog of dialogs) {
            const text = (dialog.innerText || '').toLowerCase();
            const hasSecurityControl = !!dialog.querySelector(
              "input[type='password'],input[autocomplete='one-time-code']," +
              "input[name*='otp'],iframe[src*='captcha'],iframe[src*='challenge']," +
              "[class*='captcha'],[id*='captcha']"
            );
            const isExportHistory = !!dialog.querySelector("tbody tr");
            if (hasSecurityControl || isExportHistory ||
                securityText.some(word => text.includes(word))) {
              continue;
            }
            const controls = [...dialog.querySelectorAll(
              "button,[role='button'],a[href],[aria-label],[title],[class*='close']"
            )].filter(el => shown(el) && !el.disabled &&
              el.getAttribute('aria-disabled') !== 'true');
            const signalFor = el => [
              el.getAttribute('aria-label') || '',
              el.getAttribute('title') || '',
              el.getAttribute('data-testid') || '',
              el.className || '',
              el.innerText || ''
            ].join(' ').toLowerCase().replace(/\\s+/g, ' ').trim();
            let close = controls.find(el => {
              const signal = signalFor(el);
              return /(^|\\s)(close|dismiss|cancel|skip|later|not now)(\\s|$)/.test(signal) ||
                signal.includes('dialog-close') ||
                signal.includes('modal-close') ||
                signal.includes('popup-close') ||
                signal.includes('checkout') ||
                signal.includes('check it out') ||
                ['×','x','✕','✖','关闭','關閉'].includes((el.innerText || '').trim());
            });
            if (!close) {
              close = controls.find(el => {
                const signal = signalFor(el);
                return /(^|\\s)(ok|okay|confirm|continue|proceed|understood|got it)(\\s|$)/.test(signal) ||
                  ['知道了','我知道了','确认','確定','确定','继续','繼續',
                   'ตกลง','รับทราบ','ดำเนินการต่อ'].includes(
                    (el.innerText || '').trim()
                  );
              });
            }
            if (!close && controls.length === 1) close = controls[0];
            if (!close) continue;
            close.scrollIntoView({block:'center'});
            const box = close.getBoundingClientRect();
            return {
              x: box.left + box.width / 2,
              y: box.top + box.height / 2,
              signature: [
                dialog.className || '',
                (dialog.innerText || '').slice(0, 120)
              ].join('|')
            };
          }
          return null;
        })()
        """,
    )


def close_safe_popups_cdp(port: int, limit: int = 10) -> int:
    closed = 0
    previous_signature = ""
    repeated = 0
    for _ in range(limit):
        target = cdp_safe_popup_close_target(port)
        if not isinstance(target, dict):
            break
        signature = str(target.get("signature", ""))
        if signature and signature == previous_signature:
            repeated += 1
            if repeated >= 2:
                break
        else:
            repeated = 0
        previous_signature = signature
        cdp_mouse_click(port, float(target["x"]), float(target["y"]))
        closed += 1
        time.sleep(0.6)
    if closed:
        print(f"LAZADA_SAFE_POPUPS_CLOSED: {closed}", flush=True)
    return closed


def lazada_account_deactivated_cdp(port: int) -> bool:
    return bool(
        cdp_evaluate(
            port,
            """
            (() => {
              const path = location.pathname.toLowerCase();
              const text = (document.body?.innerText || '').toLowerCase();
              return path.includes('/apps/seller/account_health') &&
                (
                  text.includes('account has been deactivated') ||
                  text.includes('account has been disabled') ||
                  text.includes('store is now offline') ||
                  text.includes('账户停用') ||
                  text.includes('帐户已被停用') ||
                  text.includes('店铺当前处于下线状态') ||
                  text.includes('ร้านค้าของคุณถูกระงับ')
                );
            })()
            """,
        )
    )


def cdp_page_status(port: int) -> dict:
    value = cdp_evaluate(
        port,
        """
        (() => {
          const path = location.pathname.toLowerCase();
          const host = location.hostname.toLowerCase();
          const seller = host.includes('sellercenter') &&
            !['login','register','signup','landing'].some(x => path.includes(x));
          const verification = /captcha|verify|verification|otp|sms/i.test(location.href) ||
            !!document.querySelector(
              "iframe[src*='captcha'],iframe[src*='challenge']," +
              "input[autocomplete='one-time-code'],input[name*='otp']," +
              "[class*='captcha'],[id*='captcha']"
            );
          return {
            url: location.href,
            title: document.title,
            seller,
            verification,
            readyState: document.readyState
          };
        })()
        """,
    )
    return value if isinstance(value, dict) else {}


def cdp_form_diagnostics(port: int) -> dict:
    value = cdp_evaluate(
        port,
        """
        (() => {
          const shown = el => {
            const box = el.getBoundingClientRect();
            const style = getComputedStyle(el);
            return box.width > 0 && box.height > 0 &&
              style.visibility !== 'hidden' && style.display !== 'none';
          };
          const clean = text => (text || '').replace(/\\s+/g, ' ').trim().slice(0, 300);
          const inputs = [...document.querySelectorAll('input')].filter(shown).map(el => ({
            type: el.type || '',
            name: el.name || '',
            id: el.id || '',
            autocomplete: el.autocomplete || '',
            placeholder: clean(el.placeholder),
            hasValue: !!el.value,
            valueLength: (el.value || '').length,
            disabled: !!el.disabled
          }));
          const buttons = [...document.querySelectorAll(
            "button,input[type='submit'],[role='button']"
          )].filter(shown).map(el => ({
            tag: el.tagName,
            type: el.type || '',
            text: clean(el.innerText || el.value),
            disabled: !!el.disabled,
            className: clean(el.className)
          })).slice(0, 40);
          const notices = [...document.querySelectorAll(
            "[role='alert'],[class*='error'],[class*='notice'],[class*='message']"
          )].filter(shown).map(el => clean(el.innerText)).filter(Boolean).slice(0, 30);
          return {inputs, buttons, notices};
        })()
        """,
    )
    return value if isinstance(value, dict) else {}


def cdp_invalid_credentials_present(port: int) -> bool:
    if "/apps/seller/login" not in str(cdp_page_status(port).get("url", "")):
        return False
    diagnostics = cdp_form_diagnostics(port)
    notices = diagnostics.get("notices", [])
    return is_invalid_credentials_text(" ".join(str(item) for item in notices))


def cdp_focus_phone(port: int) -> bool:
    return bool(
        cdp_evaluate(
            port,
            """
            (() => {
              const shown = el => {
                const box = el.getBoundingClientRect();
                const style = getComputedStyle(el);
                return box.width > 0 && box.height > 0 &&
                  style.visibility !== 'hidden' && style.display !== 'none' &&
                  !el.disabled;
              };
              const inputs = [...document.querySelectorAll('input')].filter(shown);
              const signal = el => [
                el.type, el.name, el.id, el.autocomplete,
                el.placeholder, el.getAttribute('aria-label')
              ].filter(Boolean).join(' ').toLowerCase();
              const phone = inputs.find(el => /phone|mobile|tel/.test(signal(el))) ||
                inputs.find(el => ['tel','text',''].includes((el.type || '').toLowerCase()));
              if (!phone) return false;
              phone.scrollIntoView({block:'center'});
              phone.click();
              phone.focus();
              phone.dispatchEvent(new Event('focus', {bubbles:true}));
              return true;
            })()
            """,
        )
    )


def cdp_credentials_ready(port: int) -> bool:
    return bool(
        cdp_evaluate(
            port,
            """
            (() => {
              const phone = document.querySelector('#phone');
              const password = document.querySelector('#password');
              const confirm = document.querySelector('#confirmPassword');
              return !!phone && !!password && !!confirm &&
                !!phone.value && !!password.value;
            })()
            """,
        )
    )


def cdp_fill_confirmation_without_exposing_password(port: int) -> bool:
    return bool(
        cdp_evaluate(
            port,
            """
            (() => {
              const shown = el => {
                const box = el.getBoundingClientRect();
                return box.width > 0 && box.height > 0 && !el.disabled;
              };
              const phone = document.querySelector('#phone');
              const password = document.querySelector('#password');
              const confirm = document.querySelector('#confirmPassword');
              if (!phone || !phone.value || !password || !password.value || !confirm) {
                return false;
              }

              let node = password.parentElement;
              for (let depth = 0; node && depth < 4; depth++, node = node.parentElement) {
                const controls = [...node.querySelectorAll(
                  'button,[role="button"],[aria-label],[class*="eye"],[class*="password"] svg'
                )].filter(shown);
                if (controls.length) { controls[0].click(); break; }
              }

              const setter = Object.getOwnPropertyDescriptor(
                HTMLInputElement.prototype, 'value'
              ).set;
              const previous = confirm.value;
              setter.call(confirm, password.value);
              if (confirm._valueTracker) confirm._valueTracker.setValue(previous);
              confirm.dispatchEvent(new InputEvent('input', {
                bubbles:true,
                inputType:'insertText',
                data:null
              }));
              confirm.dispatchEvent(new Event('change', {bubbles:true}));
              confirm.focus();
              return confirm.value === password.value;
            })()
            """,
        )
    )


def cdp_click_primary_submit(port: int) -> bool:
    center = cdp_evaluate(
        port,
        """
            (() => {
              const shown = el => {
                const box = el.getBoundingClientRect();
                const style = getComputedStyle(el);
                return box.width > 0 && box.height > 0 && !el.disabled &&
                  style.visibility !== 'hidden' && style.display !== 'none';
              };
              const confirm = document.querySelector('#confirmPassword');
              const password = document.querySelector('#password');
              const phone = document.querySelector('#phone');
              if (!confirm || !password || !phone ||
                  !confirm.value || confirm.value !== password.value || !phone.value) {
                return false;
              }
              const root = confirm.closest('form') || document;
              const target = [...root.querySelectorAll(
                "button[type='submit'],input[type='submit']"
              )].find(shown);
              if (!target) return null;
              target.scrollIntoView({block:'center'});
              const box = target.getBoundingClientRect();
              return {x: box.left + box.width / 2, y: box.top + box.height / 2};
            })()
        """,
    )
    if not isinstance(center, dict):
        return False
    cdp_mouse_click(port, float(center["x"]), float(center["y"]))
    return True


def cdp_click_existing_account_login(port: int) -> bool:
    center = cdp_evaluate(
        port,
        """
        (() => {
          const shown = el => {
            const box = el.getBoundingClientRect();
            const style = getComputedStyle(el);
            return box.width > 0 && box.height > 0 &&
              style.visibility !== 'hidden' && style.display !== 'none';
          };
          const target = [...document.querySelectorAll(
            "a.to-login-link,a[href*='/apps/seller/login']"
          )].find(shown);
          if (!target) return null;
          target.scrollIntoView({block:'center'});
          const box = target.getBoundingClientRect();
          return {x: box.left + box.width / 2, y: box.top + box.height / 2};
        })()
        """,
    )
    if not isinstance(center, dict):
        return False
    cdp_mouse_click(port, float(center["x"]), float(center["y"]))
    return True


def cdp_login_credentials_ready(port: int) -> bool:
    return bool(
        cdp_evaluate(
            port,
            """
            (() => {
              const account = document.querySelector('#account,input[name="account"]');
              const password = document.querySelector('#password,input[name="password"]');
              return !!account && !!password && !!account.value && !!password.value;
            })()
            """,
        )
    )


def cdp_click_login_submit(port: int) -> bool:
    center = cdp_evaluate(
        port,
        """
        (() => {
          const shown = el => {
            const box = el.getBoundingClientRect();
            const style = getComputedStyle(el);
            return box.width > 0 && box.height > 0 && !el.disabled &&
              style.visibility !== 'hidden' && style.display !== 'none';
          };
          const account = document.querySelector('#account,input[name="account"]');
          const password = document.querySelector('#password,input[name="password"]');
          if (!account || !password || !account.value || !password.value) return null;
          const root = password.closest('form') || document;
          const target = [...root.querySelectorAll(
            "button[type='submit'],input[type='submit']"
          )].find(shown);
          if (!target) return null;
          target.scrollIntoView({block:'center'});
          const box = target.getBoundingClientRect();
          return {x: box.left + box.width / 2, y: box.top + box.height / 2};
        })()
        """,
    )
    if not isinstance(center, dict):
        return False
    cdp_mouse_click(port, float(center["x"]), float(center["y"]))
    return True


def complete_landing_login_cdp(port: int) -> tuple[str, dict]:
    deadline = time.monotonic() + 45
    status = {}
    while time.monotonic() < deadline:
        status = cdp_page_status(port)
        if status.get("seller"):
            print("LAZADA_SELLER_CENTER_ALREADY_OPEN", flush=True)
            close_safe_popups_cdp(port)
            status = cdp_page_status(port)
            return "ready", status
        if status.get("readyState") in {"interactive", "complete"}:
            break
        time.sleep(1)

    current_url = str(status.get("url", ""))
    if (
        not status.get("seller")
        and "/apps/register/" not in current_url
        and "/apps/seller/login" not in current_url
        and not status.get("verification")
    ):
        wait_cdp_value(
            port,
            "location.hostname.includes('lazada.') && "
            "(location.pathname.includes('/apps/seller/login') || "
            "location.pathname.includes('/apps/register/') || "
            "location.hostname.includes('sellercenter'))",
            timeout=10,
            interval=0.5,
            description="Lazada login or Seller Center URL",
        )
        status = cdp_page_status(port)

    if status.get("verification"):
        print("LAZADA_POST_LOGIN_VERIFICATION_PROBLEM", flush=True)
        return "problem", status
    if "/apps/register/" in str(status.get("url", "")):
        if not cdp_click_existing_account_login(port):
            raise RuntimeError("The Lazada registration page login link was not found.")
        print("LAZADA_REGISTRATION_PAGE_SKIPPED_TO_LOGIN", flush=True)
        transition_deadline = time.monotonic() + 30
        while time.monotonic() < transition_deadline:
            status = cdp_page_status(port)
            if status.get("seller"):
                print("LAZADA_SELLER_CENTER_READY", flush=True)
                close_safe_popups_cdp(port)
                status = cdp_page_status(port)
                return "ready", status
            if status.get("verification"):
                print("LAZADA_POST_LOGIN_VERIFICATION_PROBLEM", flush=True)
                return "problem", status
            if "/apps/seller/login" in str(status.get("url", "")):
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("Lazada did not open the existing-account login page.")

    if "/apps/seller/login" not in str(status.get("url", "")):
        raise RuntimeError("The page is neither Lazada Seller Center nor its login page.")

    login_deadline = time.monotonic() + 25
    while time.monotonic() < login_deadline:
        if cdp_login_credentials_ready(port):
            break
        time.sleep(0.5)
    else:
        raise RuntimeError("Feikua did not autofill the Lazada login form.")
    if not cdp_click_login_submit(port):
        raise RuntimeError("The Lazada login submit button was not found.")
    print("LAZADA_EXISTING_ACCOUNT_LOGIN_SUBMITTED_ONCE", flush=True)

    result_deadline = time.monotonic() + 90
    while time.monotonic() < result_deadline:
        status = cdp_page_status(port)
        if status.get("seller"):
            print("LAZADA_SELLER_CENTER_READY", flush=True)
            close_safe_popups_cdp(port)
            status = cdp_page_status(port)
            return "ready", status
        if status.get("verification"):
            print("LAZADA_POST_LOGIN_VERIFICATION_PROBLEM", flush=True)
            return "problem", status
        if cdp_invalid_credentials_present(port):
            raise InvalidCredentialsError("Lazada explicitly rejected the account or password.")
        time.sleep(1)
    raise RuntimeError("Lazada existing-account login did not reach Seller Center.")


def is_lazada_url(url: str) -> bool:
    host = (urlparse(url).hostname or "").casefold()
    return host == "lazada.com" or host.endswith(".lazada.com") or "lazada." in host


def is_seller_center(url: str) -> bool:
    parsed = urlparse(url)
    host = (parsed.hostname or "").casefold()
    path = parsed.path.casefold()
    if "sellercenter" not in host and "sellercenter" not in path:
        return False
    return not any(token in path for token in ("login", "register", "signup", "landing"))


def switch_to_lazada_page(driver) -> None:
    candidates = []
    for handle in list(driver.window_handles):
        try:
            driver.switch_to.window(handle)
            if is_lazada_url(driver.current_url):
                priority = (
                    0
                    if is_seller_center(driver.current_url)
                    else 1
                    if "/apps/seller/login" in driver.current_url
                    else 2
                )
                candidates.append((priority, handle))
        except Exception:
            continue
    if candidates:
        candidates.sort(key=lambda item: item[0])
        driver.switch_to.window(candidates[0][1])


def wait_document_ready(driver, timeout: int = 40) -> None:
    WebDriverWait(driver, timeout).until(
        lambda current: current.execute_script("return document.readyState")
        in {"interactive", "complete"}
    )


def visible_inputs(driver):
    result = []
    for element in driver.find_elements(By.CSS_SELECTOR, "input"):
        try:
            if element.is_displayed() and element.is_enabled():
                result.append(element)
        except StaleElementReferenceException:
            continue
    return result


def input_signal(element) -> str:
    values = [
        element.get_attribute("type") or "",
        element.get_attribute("name") or "",
        element.get_attribute("id") or "",
        element.get_attribute("autocomplete") or "",
        element.get_attribute("placeholder") or "",
        element.get_attribute("aria-label") or "",
    ]
    return " ".join(values).casefold()


def find_phone_input(driver):
    inputs = visible_inputs(driver)
    for element in inputs:
        signal = input_signal(element)
        if any(token in signal for token in ("phone", "mobile", "tel")):
            return element
    for element in inputs:
        if (element.get_attribute("type") or "").casefold() in {"tel", "text", ""}:
            return element
    return None


def password_candidates(driver):
    candidates = []
    for element in visible_inputs(driver):
        signal = input_signal(element)
        if "password" in signal or (element.get_attribute("type") or "").casefold() == "password":
            candidates.append(element)
    return candidates


def classify_password_inputs(driver):
    candidates = password_candidates(driver)
    if len(candidates) < 2:
        return None, None
    confirm = next(
        (
            element
            for element in candidates
            if any(
                token in input_signal(element)
                for token in ("confirm", "repeat", "retype", "again")
            )
        ),
        candidates[-1],
    )
    password = next((element for element in candidates if element.id != confirm.id), candidates[0])
    return password, confirm


def click_rendered(driver, element) -> None:
    driver.execute_script("arguments[0].scrollIntoView({block:'center'});", element)
    try:
        element.click()
    except (ElementNotInteractableException, ElementClickInterceptedException):
        driver.execute_script("arguments[0].click();", element)


def click_password_eye(driver, password_input) -> bool:
    script = """
    const input = arguments[0];
    let node = input.parentElement;
    for (let depth = 0; node && depth < 4; depth++, node = node.parentElement) {
      const controls = [...node.querySelectorAll(
        'button,[role="button"],[aria-label],[class*="eye"],[class*="password"] svg'
      )];
      const target = controls.find(el => {
        const box = el.getBoundingClientRect();
        return box.width > 0 && box.height > 0 && el !== input;
      });
      if (target) { target.click(); return true; }
    }
    return false;
    """
    return bool(driver.execute_script(script, password_input))


def focus_phone_and_wait_for_autofill(driver, timeout: int = 20):
    phone = WebDriverWait(driver, 20).until(lambda current: find_phone_input(current))
    click_rendered(driver, phone)
    driver.execute_script(
        "arguments[0].focus(); arguments[0].dispatchEvent(new Event('focus', {bubbles:true}));",
        phone,
    )

    def credentials_ready(current):
        current_phone = find_phone_input(current)
        password, confirm = classify_password_inputs(current)
        if current_phone is None or password is None or confirm is None:
            return False
        if not (current_phone.get_attribute("value") or ""):
            return False
        return (password.get_attribute("value") or "") != "" and (password, confirm)

    return WebDriverWait(driver, timeout).until(credentials_ready)


def fill_confirmation_inside_page(driver, password_input, confirm_input) -> None:
    click_password_eye(driver, password_input)
    password_value = password_input.get_attribute("value") or ""
    if not password_value:
        raise RuntimeError("Feikua did not autofill the Lazada password field.")
    click_rendered(driver, confirm_input)
    confirm_input.send_keys(Keys.CONTROL, "a")
    confirm_input.send_keys(password_value)
    driver.execute_script(
        """
        arguments[0].dispatchEvent(new Event('input', {bubbles:true}));
        arguments[0].dispatchEvent(new Event('change', {bubbles:true}));
        arguments[0].blur();
        """,
        confirm_input,
    )
    if (confirm_input.get_attribute("value") or "") != password_value:
        raise RuntimeError("The confirmation password field did not accept the saved password.")


def find_primary_submit(driver):
    forms = driver.find_elements(By.CSS_SELECTOR, "form")
    roots = forms or [driver]
    candidates = []
    for root in roots:
        for element in root.find_elements(
            By.CSS_SELECTOR,
            "button[type='submit'], input[type='submit'], button",
        ):
            try:
                if not element.is_displayed() or not element.is_enabled():
                    continue
                box = element.rect
                candidates.append((box.get("width", 0) * box.get("height", 0), element))
            except StaleElementReferenceException:
                continue
    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0], reverse=True)
    return candidates[0][1]


def verification_present(driver) -> bool:
    url = driver.current_url.casefold()
    if any(token in url for token in ("captcha", "verify", "verification", "otp", "sms")):
        return True
    selectors = (
        "iframe[src*='captcha'], iframe[src*='challenge'], "
        "input[autocomplete='one-time-code'], input[name*='otp'], "
        "[class*='captcha'], [id*='captcha']"
    )
    for element in driver.find_elements(By.CSS_SELECTOR, selectors):
        try:
            if element.is_displayed():
                return True
        except StaleElementReferenceException:
            continue
    return False


def selenium_safe_popup_close_target(driver):
    return driver.execute_script(
        """
        const shown = el => {
          const box = el.getBoundingClientRect();
          const style = getComputedStyle(el);
          return box.width > 0 && box.height > 0 &&
            style.visibility !== 'hidden' && style.display !== 'none' &&
            Number(style.opacity || 1) > 0;
        };
        const securityText = [
          'captcha','verification','verify','security','otp','one-time',
          'password','login','sms','验证码','验证','安全','密码','登录',
          'รหัสผ่าน','เข้าสู่ระบบ','ความปลอดภัย'
        ];
        const dialogs = [...document.querySelectorAll(
          "[role='dialog'],.next-dialog,.ant-modal,[class*='modal']," +
          "[class*='popup'],[class*='pop-up'],[class*='dialog']"
        )].filter(shown).reverse();
        for (const dialog of dialogs) {
          const text = (dialog.innerText || '').toLowerCase();
          const isExportHistory = !!dialog.querySelector("tbody tr");
          if (
            isExportHistory ||
            dialog.querySelector(
              "input[type='password'],input[autocomplete='one-time-code']," +
              "input[name*='otp'],iframe[src*='captcha'],iframe[src*='challenge']," +
              "[class*='captcha'],[id*='captcha']"
            ) ||
            securityText.some(word => text.includes(word))
          ) continue;
          const controls = [...dialog.querySelectorAll(
            "button,[role='button'],a[href],[aria-label],[title],[class*='close']"
          )].filter(el => shown(el) && !el.disabled &&
            el.getAttribute('aria-disabled') !== 'true');
          const signalFor = el => [
            el.getAttribute('aria-label') || '',
            el.getAttribute('title') || '',
            el.getAttribute('data-testid') || '',
            el.className || '',
            el.innerText || ''
          ].join(' ').toLowerCase().replace(/\\s+/g, ' ').trim();
          let close = controls.find(el => {
            const signal = signalFor(el);
            return /(^|\\s)(close|dismiss|cancel|skip|later|not now)(\\s|$)/.test(signal) ||
              signal.includes('dialog-close') ||
              signal.includes('modal-close') ||
              signal.includes('popup-close') ||
              signal.includes('checkout') ||
              signal.includes('check it out') ||
              ['×','x','✕','✖','关闭','關閉'].includes((el.innerText || '').trim());
          });
          if (!close) {
            close = controls.find(el => {
              const signal = signalFor(el);
              return /(^|\\s)(ok|okay|confirm|continue|proceed|understood|got it)(\\s|$)/.test(signal) ||
                ['知道了','我知道了','确认','確定','确定','继续','繼續',
                 'ตกลง','รับทราบ','ดำเนินการต่อ'].includes(
                  (el.innerText || '').trim()
                );
            });
          }
          if (!close && controls.length === 1) close = controls[0];
          if (close) return close;
        }
        return null;
        """
    )


def close_safe_popups_selenium(driver, limit: int = 10) -> int:
    closed = 0
    for _ in range(limit):
        target = selenium_safe_popup_close_target(driver)
        if target is None:
            break
        try:
            click_rendered(driver, target)
        except (ElementNotInteractableException, StaleElementReferenceException):
            break
        closed += 1
        time.sleep(0.6)
    if closed:
        print(f"LAZADA_SAFE_POPUPS_CLOSED: {closed}", flush=True)
    return closed


def complete_landing_login(driver) -> str:
    if is_seller_center(driver.current_url):
        print("LAZADA_SELLER_CENTER_ALREADY_OPEN", flush=True)
        close_safe_popups_selenium(driver)
        return "ready"

    if verification_present(driver):
        print("LAZADA_POST_LOGIN_VERIFICATION_PROBLEM", flush=True)
        return "problem"

    if "/apps/register/" in driver.current_url:
        login_link = WebDriverWait(driver, 20).until(
            lambda current: kernel.first_visible_enabled(
                current,
                By.CSS_SELECTOR,
                "a.to-login-link,a[href*='/apps/seller/login']",
            )
        )
        click_rendered(driver, login_link)
        print("LAZADA_REGISTRATION_PAGE_SKIPPED_TO_LOGIN", flush=True)
        WebDriverWait(driver, 30).until(
            lambda current: (
                switch_to_lazada_page(current) is None
                and (
                    is_seller_center(current.current_url)
                    or "/apps/seller/login" in current.current_url
                    or verification_present(current)
                )
            )
        )

    if is_seller_center(driver.current_url):
        print("LAZADA_SELLER_CENTER_READY", flush=True)
        close_safe_popups_selenium(driver)
        return "ready"
    if verification_present(driver):
        print("LAZADA_POST_LOGIN_VERIFICATION_PROBLEM", flush=True)
        return "problem"
    if "/apps/seller/login" not in driver.current_url:
        raise RuntimeError("The page is neither Lazada Seller Center nor its login page.")

    def filled_login_form(current):
        account = kernel.first_visible_enabled(
            current,
            By.CSS_SELECTOR,
            "#account,input[name='account']",
        )
        password = kernel.first_visible_enabled(
            current,
            By.CSS_SELECTOR,
            "#password,input[name='password']",
        )
        if (
            account is None
            or password is None
            or not (account.get_attribute("value") or "")
            or not (password.get_attribute("value") or "")
        ):
            return False
        return password

    password = WebDriverWait(driver, 25).until(filled_login_form)
    form = password.find_element(By.XPATH, "ancestor::form[1]")
    submit = next(
        (
            element
            for element in form.find_elements(
                By.CSS_SELECTOR,
                "button[type='submit'],input[type='submit']",
            )
            if element.is_displayed() and element.is_enabled()
        ),
        None,
    )
    if submit is None:
        raise RuntimeError("The Lazada login submit button was not found.")
    click_rendered(driver, submit)
    print("LAZADA_EXISTING_ACCOUNT_LOGIN_SUBMITTED_ONCE", flush=True)

    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        switch_to_lazada_page(driver)
        if is_seller_center(driver.current_url):
            print("LAZADA_SELLER_CENTER_READY", flush=True)
            close_safe_popups_selenium(driver)
            return "ready"
        if verification_present(driver):
            print("LAZADA_POST_LOGIN_VERIFICATION_PROBLEM", flush=True)
            return "problem"
        if "/apps/seller/login" in driver.current_url:
            notices = [
                element.text
                for element in driver.find_elements(
                    By.CSS_SELECTOR,
                    "[role='alert'],[class*='error'],[class*='notice'],[class*='message']",
                )
                if element.is_displayed() and element.text.strip()
            ]
            if is_invalid_credentials_text(" ".join(notices)):
                raise InvalidCredentialsError(
                    "Lazada explicitly rejected the account or password."
                )
        time.sleep(1)
    if verification_present(driver):
        print("LAZADA_POST_LOGIN_VERIFICATION_PROBLEM", flush=True)
        return "problem"
    raise RuntimeError("Lazada did not reach Seller Center within 90 seconds.")


def current_regular_url(port: int) -> str:
    for target in kernel.cdp_targets(port):
        if target.get("type") == "page" and is_lazada_url(target.get("url", "")):
            return target.get("url", "")
    return ""


def validate_iso_date(value: str) -> dt.date:
    try:
        return dt.date.fromisoformat(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError(
            f"invalid date {value!r}; expected YYYY-MM-DD"
        ) from error


def cdp_center_for(port: int, expression: str) -> dict:
    result = cdp_evaluate(
        port,
        f"""
        (() => {{
          const element = ({expression});
          if (!element) return null;
          element.scrollIntoView({{block: "center", inline: "nearest"}});
          const box = element.getBoundingClientRect();
          const style = getComputedStyle(element);
          if (
            box.width <= 1 || box.height <= 1 ||
            style.display === "none" || style.visibility === "hidden"
          ) return null;
          return {{
            x: box.left + box.width / 2,
            y: box.top + box.height / 2
          }};
        }})()
        """,
    )
    if not result:
        raise RuntimeError("The expected Lazada control is not visible.")
    return result


def cdp_click_expression(port: int, expression: str) -> None:
    point = cdp_center_for(port, expression)
    cdp_mouse_click(port, point["x"], point["y"])


def wait_cdp_click_expression(
    port: int,
    expression: str,
    *,
    timeout: int = 15,
    description: str = "Lazada control",
) -> None:
    """Wait for a rendered control before sending one real CDP click."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            point = cdp_center_for(port, expression)
        except RuntimeError:
            time.sleep(0.5)
            continue
        cdp_mouse_click(port, point["x"], point["y"])
        return
    raise RuntimeError(f"Timed out waiting for visible {description}.")


def wait_cdp_value(
    port: int,
    expression: str,
    *,
    timeout: int | None = 60,
    interval: float = 0.8,
    description: str = "Lazada page state",
):
    deadline = None if timeout is None else time.monotonic() + timeout
    last_value = None
    while deadline is None or time.monotonic() < deadline:
        last_value = cdp_evaluate(port, expression)
        if last_value:
            return last_value
        time.sleep(interval)
    raise RuntimeError(f"Timed out waiting for {description}; last value={last_value!r}")


def cdp_navigate_relative(port: int, path: str) -> None:
    if not path.startswith("/"):
        raise ValueError("Lazada navigation paths must be origin-relative.")
    cdp_evaluate(port, f"location.assign({json.dumps(path)})")


def cdp_apply_visible_range_values(
    port: int,
    start_date: dt.date,
    end_date: dt.date,
) -> bool:
    """Use the component's native setter only if trusted typing did not stick."""
    return bool(
        cdp_evaluate(
            port,
            f"""
            (() => {{
              const panel = document.querySelector(
                ".next-range-picker-body[aria-hidden='false']"
              );
              if (!panel) return false;
              const inputs = [...panel.querySelectorAll(
                ".next-range-picker-panel-header input"
              )];
              if (inputs.length !== 2) return false;
              const values = [{json.dumps(start_date.isoformat())}, {json.dumps(end_date.isoformat())}];
              const setter = Object.getOwnPropertyDescriptor(
                HTMLInputElement.prototype, 'value'
              ).set;
              for (let index = 0; index < 2; index += 1) {{
                const input = inputs[index];
                const previous = input.value;
                input.focus();
                setter.call(input, values[index]);
                if (input._valueTracker) input._valueTracker.setValue(previous);
                input.dispatchEvent(new InputEvent('input', {{bubbles: true, inputType: 'insertText'}}));
                input.dispatchEvent(new Event('change', {{bubbles: true}}));
              }}
              return inputs.map(input => input.value).join('|') === values.join('|');
            }})()
            """,
        )
    )


def cdp_apply_range_wrapper_values(
    port: int,
    wrapper_selector: str,
    start_date: dt.date,
    end_date: dt.date,
) -> bool:
    """Repair an auto-closed range picker through its rendered top inputs."""
    return bool(
        cdp_evaluate(
            port,
            f"""
            (() => {{
              const inputs = [...document.querySelectorAll(
                {json.dumps(wrapper_selector + ' input')}
              )];
              const values = [{json.dumps(start_date.isoformat())}, {json.dumps(end_date.isoformat())}];
              if (inputs.length !== 2) return false;
              const setter = Object.getOwnPropertyDescriptor(
                HTMLInputElement.prototype, 'value'
              ).set;
              for (let index = 0; index < 2; index += 1) {{
                const input = inputs[index];
                const previous = input.value;
                setter.call(input, values[index]);
                if (input._valueTracker) input._valueTracker.setValue(previous);
                input.dispatchEvent(new Event('input', {{bubbles: true}}));
                input.dispatchEvent(new Event('change', {{bubbles: true}}));
              }}
              return inputs.map(input => input.value).join('|') === values.join('|');
            }})()
            """,
        )
    )


def open_lazada_income_released(port: int) -> None:
    """Open My Income and select the stable released/transferred tab."""
    close_safe_popups_cdp(port)
    if "/portal/apps/finance/myIncome/index" not in str(
        cdp_evaluate(port, "location.href")
    ):
        try:
            wait_cdp_click_expression(
                port,
                "document.querySelector("
                "'#root_normal_rp_asc_v2_finance_rp_asc_v2_accountstatementnew'"
                ")",
                timeout=3,
                description="My Income sidebar entry",
            )
            wait_cdp_value(
                port,
                "location.href.includes('/portal/apps/finance/myIncome/index')",
                timeout=5,
                description="My Income sidebar navigation",
            )
        except RuntimeError:
            print("LAZADA_INCOME_DIRECT_ROUTE_FALLBACK", flush=True)
            cdp_navigate_relative(port, "/portal/apps/finance/myIncome/index")
        wait_cdp_value(
            port,
            "location.href.includes('/portal/apps/finance/myIncome/index')",
            timeout=45,
            description="My Income navigation",
        )
    wait_cdp_value(
        port,
        "!!document.querySelector("
        "\"[role='tab'][data-spm='d_income_overview_released']\""
        ")",
        timeout=45,
        description="released income tab",
    )
    active = cdp_evaluate(
        port,
        "document.querySelector("
        "\"[role='tab'][data-spm='d_income_overview_released']\""
        ").classList.contains('active')",
    )
    if not active:
        cdp_click_expression(
            port,
            "document.querySelector("
            "\"[role='tab'][data-spm='d_income_overview_released']\""
            ")",
        )
    wait_cdp_value(
        port,
        "!!(document.querySelector("
        "\"[role='tab'][data-spm='d_income_overview_released'].active\""
        ") && document.querySelector('#releasedCreateData'))",
        timeout=30,
        description="active released income panel",
    )


def wait_range_control_ready(port: int, wrapper_selector: str) -> None:
    """Wait until the rendered range control is initialized and interactive."""
    wait_cdp_value(
        port,
        f"""
        (() => {{
          const wrapper = document.querySelector({json.dumps(wrapper_selector)});
          if (!wrapper) return false;
          const inputs = [...wrapper.querySelectorAll('input')];
          if (inputs.length !== 2 || inputs.some(input => input.disabled)) return false;
          const visible = inputs.every(input => {{
            const box = input.getBoundingClientRect();
            return box.width > 1 && box.height > 1;
          }});
          const busy = [...document.querySelectorAll(
            ".next-loading-tip,.next-loading-indicator," +
            ".next-loading-fusion-reactor,.next-loading-mask"
          )].some(element => {{
            const box = element.getBoundingClientRect();
            const style = getComputedStyle(element);
            return box.width > 1 && box.height > 1 &&
              style.display !== 'none' && style.visibility !== 'hidden';
          }});
          return visible && !busy;
        }})()
        """,
        timeout=45,
        description=f"initialized range control {wrapper_selector}",
    )
    time.sleep(0.5)


def open_range_picker(port: int, wrapper_selector: str, description: str) -> list[dict]:
    """Open a Lazada range picker with real clicks after its control is ready."""
    wait_range_control_ready(port, wrapper_selector)
    last_error = None
    for _ in range(3):
        cdp_click_expression(
            port,
            f"document.querySelector({json.dumps(wrapper_selector + ' input')})",
        )
        try:
            return wait_cdp_value(
                port,
                """
                (() => {
                  const panel = document.querySelector(
                    ".next-range-picker-body[aria-hidden='false']"
                  );
                  if (!panel) return null;
                  const inputs = [...panel.querySelectorAll(
                    ".next-range-picker-panel-header input"
                  )];
                  if (inputs.length !== 2) return null;
                  return inputs.map(element => {
                    const box = element.getBoundingClientRect();
                    return {x: box.left + box.width / 2, y: box.top + box.height / 2};
                  });
                })()
                """,
                timeout=6,
                description=description,
            )
        except RuntimeError as error:
            last_error = error
            time.sleep(0.5)
    raise last_error or RuntimeError(f"Could not open {description}.")


def wait_entered_range_state(
    port: int,
    wrapper_selector: str,
    start_date: dt.date,
    end_date: dt.date,
    description: str,
) -> str:
    """Wait for the picker to stabilize after trusted keyboard input."""
    return str(
        wait_cdp_value(
            port,
            f"""
            (() => {{
              const expected = [
                {json.dumps(start_date.isoformat())},
                {json.dumps(end_date.isoformat())}
              ];
              const panel = document.querySelector(
                ".next-range-picker-body[aria-hidden='false']"
              );
              const panelValues = panel ? [...panel.querySelectorAll(
                ".next-range-picker-panel-header input"
              )].map(element => element.value) : [];
              if (panelValues.length === 2 &&
                  panelValues[0] === expected[0] &&
                  panelValues[1] === expected[1]) return 'panel';
              const applied = [...document.querySelectorAll(
                {json.dumps(wrapper_selector + ' input')}
              )].map(element => element.value);
              if (!panel && applied.length === 2 &&
                  applied[0] === expected[0] &&
                  applied[1] === expected[1]) return 'applied';
              return null;
            }})()
            """,
            timeout=12,
            description=description,
        )
    )


def set_released_income_date_range(
    port: int,
    start_date: dt.date,
    end_date: dt.date,
) -> None:
    if start_date > end_date:
        raise RuntimeError("start date must not be later than end date")
    if end_date > dt.date.today():
        raise RuntimeError("income export end date must not be in the future")

    inputs = open_range_picker(
        port, "#releasedCreateData", "released income date picker"
    )
    if len(inputs) != 2:
        raise RuntimeError("The Lazada released income date picker is incomplete.")
    # This old Lazada range picker can immediately commit and close after the
    # start input changes, restoring the end date to today.  Set the end first
    # so either automatic commit retains the requested inclusive range.
    requested_values = (start_date.isoformat(), end_date.isoformat())
    for index in (1, 0):
        point = inputs[index]
        value = requested_values[index]
        cdp_mouse_click(port, point["x"], point["y"])
        cdp_replace_focused_text(port, value)
        time.sleep(0.2)

    state = wait_entered_range_state(
        port,
        "#releasedCreateData",
        start_date,
        end_date,
        "stable released income date range",
    )
    if state == "applied":
        return
    cdp_click_expression(
        port,
        "document.querySelector("
        "\".next-range-picker-body[aria-hidden='false'] "
        ".next-date-picker-panel-footer button\""
        ")",
    )
    wait_cdp_value(
        port,
        f"""
        (() => {{
          const values = [...document.querySelectorAll(
            "#releasedCreateData input"
          )].map(element => element.value);
          return values[0] === {json.dumps(start_date.isoformat())} &&
            values[1] === {json.dumps(end_date.isoformat())} &&
            !document.querySelector(
              ".next-range-picker-body[aria-hidden='false']"
            );
        }})()
        """,
        timeout=20,
        description="applied released income date range",
    )


def open_download_menu_with_retry(
    port: int,
    button_expression: str,
    description: str,
    timeout: float = 120,
) -> bool:
    """Dismiss safe overlays and retry a real menu-button click while the page settles."""
    menu_visible_expression = """
    !![...document.querySelectorAll(".next-menu-item")].find(element => {
      const box = element.getBoundingClientRect();
      const style = getComputedStyle(element);
      return box.width > 1 && box.height > 1 &&
        style.display !== "none" && style.visibility !== "hidden";
    })
    """
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        close_safe_popups_cdp(port)
        try:
            cdp_click_expression(port, button_expression)
        except RuntimeError:
            pass
        attempt_deadline = min(deadline, time.monotonic() + 3)
        opened_without_items = 0
        while time.monotonic() < attempt_deadline:
            if cdp_evaluate(port, menu_visible_expression):
                return True
            button_opened = cdp_evaluate(
                port,
                f"!!({button_expression})?.classList.contains('opened')",
            )
            if button_opened:
                opened_without_items += 1
                if opened_without_items >= 5:
                    return False
            else:
                opened_without_items = 0
            time.sleep(0.4)
    raise RuntimeError(f"Timed out opening {description} after repeated real clicks.")


def ensure_export_history_open(
    port: int,
    button_expression: str,
    description: str,
) -> None:
    """Reopen an existing export-history dialog without submitting another job."""
    history_visible = """
    (() => {
      const shown = element => {
        const box = element.getBoundingClientRect();
        const style = getComputedStyle(element);
        return box.width > 1 && box.height > 1 &&
          style.display !== "none" && style.visibility !== "hidden";
      };
      return [...document.querySelectorAll("[role='dialog'],.next-dialog")]
        .filter(shown)
        .some(element => element.querySelector("tbody tr"));
    })()
    """
    if cdp_evaluate(port, history_visible):
        return
    if not open_download_menu_with_retry(
        port,
        button_expression,
        f"{description} menu",
        timeout=30,
    ):
        raise RuntimeError(f"Could not open the {description} menu.")
    cdp_click_expression(
        port,
        """
        [...document.querySelectorAll(".next-menu-item")].find(element => {
          const box = element.getBoundingClientRect();
          const style = getComputedStyle(element);
          const text = (element.innerText || "").trim().toLowerCase();
          return box.width > 1 && box.height > 1 &&
            style.display !== "none" && style.visibility !== "hidden" &&
            (text.includes("下载历史") || text.includes("download history"));
        })
        """,
    )
    wait_cdp_value(
        port,
        history_visible,
        timeout=20,
        interval=0.4,
        description=f"reopened {description}",
    )
    print(f"LAZADA_EXPORT_HISTORY_REOPENED: {description}", flush=True)


def start_released_income_excel_export(port: int) -> str:
    """Start exactly one Order details (Excel) export from the released panel."""
    if not open_download_menu_with_retry(
        port,
        """
        (() => {
          const panes = [...document.querySelectorAll(
            ".next-tabs-tabpane.active"
          )];
          return panes.at(-1)?.querySelector(".download button.next-menu-btn");
        })()
        """,
        "released income download menu",
    ):
        raise NoLazadaFileError("Lazada income has no downloadable file.")
    # The first item is structurally the Order details (Excel) option in every locale.
    cdp_click_expression(
        port,
        """
        [...document.querySelectorAll(".next-menu-item")].find(element => {
          const box = element.getBoundingClientRect();
          const style = getComputedStyle(element);
          return box.width > 1 && box.height > 1 &&
            style.display !== "none" && style.visibility !== "hidden";
        })
        """,
    )
    return wait_cdp_value(
        port,
        """
        (() => {
          const shown = element => {
            const box = element.getBoundingClientRect();
            const style = getComputedStyle(element);
            return box.width > 1 && box.height > 1 &&
              style.display !== "none" && style.visibility !== "hidden";
          };
          const dialog = [...document.querySelectorAll(
            "[role='dialog'],.next-dialog"
          )].filter(shown).find(element => element.querySelector("tbody tr"));
          return dialog?.querySelector("tbody tr td")?.innerText.trim() || null;
        })()
        """,
        timeout=20,
        interval=0.4,
        description="new released income export task id",
    )


def wait_latest_released_income_result(
    port: int,
    requested_task_id: str,
    timeout: int | None = None,
) -> dict:
    """Wait for the newest RELEASED Excel task in the automatically opened history."""
    expression = (
        """
        (() => {
          const shown = element => {
            const box = element.getBoundingClientRect();
            const style = getComputedStyle(element);
            return box.width > 1 && box.height > 1 &&
              style.display !== "none" && style.visibility !== "hidden";
          };
          const dialog = [...document.querySelectorAll(
            "[role='dialog'],.next-dialog"
          )].filter(shown).find(element => element.querySelector("tbody tr"));
          if (!dialog) return null;
          const row = dialog.querySelector("tbody tr");
          const cells = [...row.querySelectorAll("td")];
          const link = row.querySelector("a[href]");
          if (cells.length < 5) return null;
          const taskId = (cells[0].innerText || "").trim();
          if (taskId !== __PREVIOUS_TASK_ID__) return null;
          const filename = (cells[2].innerText || "").trim();
          const status = (cells[3].innerText || "").trim();
          if (!filename.includes("-RELEASED-") || !filename.endsWith(".xlsx")) {
            return null;
          }
          if (!status.toLowerCase().includes("finished") || !link) return null;
          link.scrollIntoView({block: "center"});
          const box = link.getBoundingClientRect();
          return {
            task_id: taskId,
            created_at: (cells[1].innerText || "").trim(),
            filename,
            status,
            href: link.href,
            x: box.left + box.width / 2,
            y: box.top + box.height / 2
          };
        })()
        """
        .replace("__PREVIOUS_TASK_ID__", json.dumps(requested_task_id))
    )
    deadline = None if timeout is None else time.monotonic() + timeout
    while True:
        ensure_export_history_open(
            port,
            """
            (() => {
              const panes = [...document.querySelectorAll(
                ".next-tabs-tabpane.active"
              )];
              return panes.at(-1)?.querySelector(
                ".download button.next-menu-btn"
              );
            })()
            """,
            "released income export history",
        )
        wait_seconds = 30
        if deadline is not None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError(
                    "Timed out waiting for latest released income Excel result."
                )
            wait_seconds = min(wait_seconds, remaining)
        try:
            return wait_cdp_value(
                port,
                expression,
                timeout=wait_seconds,
                interval=1.5,
                description="latest released income Excel result",
            )
        except RuntimeError:
            if deadline is not None and time.monotonic() >= deadline:
                raise
            close_export_history_dialog(port)


def _clean_lazada_signed_url(url: str) -> str:
    parts = urlsplit(url)
    clean_query = urlencode(
        [(key, value) for key, value in parse_qsl(parts.query) if key != "spm"]
    )
    return urlunsplit(
        (parts.scheme, parts.netloc, parts.path, clean_query, parts.fragment)
    )


def _download_signed_result_with_browser_session(
    port: int,
    url: str,
    destination: Path,
) -> None:
    attempt = 0
    while True:
        attempt += 1
        try:
            cookies = cdp_command(port, "Network.getAllCookies").get("cookies", [])
            cookie_header = "; ".join(
                f"{item['name']}={item['value']}"
                for item in cookies
                if "lazada." in item.get("domain", "")
            )
            referer = cdp_evaluate(port, "location.origin + '/'")
            request = urllib.request.Request(
                _clean_lazada_signed_url(url),
                headers={
                    "User-Agent": "Mozilla/5.0",
                    "Referer": referer,
                    "Cookie": cookie_header,
                },
            )
            with urllib.request.urlopen(request, timeout=90) as response:
                payload = response.read()
            destination.write_bytes(payload)
            return
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            if destination.exists() and destination.stat().st_size >= 100:
                return
            print(
                "LAZADA_READY_FILE_DOWNLOAD_RETRY: "
                f"attempt={attempt} error={type(error).__name__}; "
                "the same ready file will be retried in 30 seconds without "
                "resubmission or closing the shop.",
                flush=True,
            )
            time.sleep(30)


def download_released_income_result(
    port: int,
    result: dict,
    output_dir: Path,
    timeout: int = 25,
    marker: str = "LAZADA_INCOME_FILE_CONFIRMED",
) -> Path:
    output_dir.mkdir(parents=True, exist_ok=True)
    destination = output_dir / result["filename"]
    baseline = {
        path.name: (path.stat().st_size, path.stat().st_mtime_ns)
        for path in output_dir.iterdir()
        if path.is_file()
    }
    settings = {"behavior": "allow", "downloadPath": str(output_dir)}
    try:
        cdp_command(
            port,
            "Browser.setDownloadBehavior",
            {**settings, "eventsEnabled": True},
        )
    except RuntimeError:
        pass
    cdp_command(port, "Page.setDownloadBehavior", settings)

    # First perform the visible element click the user trained.
    cdp_mouse_click(port, result["x"], result["y"])
    deadline = time.monotonic() + timeout
    fast_handoff = time.monotonic() + 1.5
    stable_sizes: dict[str, int] = {}
    while time.monotonic() < deadline:
        candidates: list[Path] = []
        current_download_activity = False
        for partial in output_dir.glob("*.crdownload"):
            try:
                current = (partial.stat().st_size, partial.stat().st_mtime_ns)
            except FileNotFoundError:
                continue
            if baseline.get(partial.name) != current:
                current_download_activity = True
        for path in output_dir.glob("*.xlsx"):
            try:
                current = (path.stat().st_size, path.stat().st_mtime_ns)
            except FileNotFoundError:
                continue
            if baseline.get(path.name) != current:
                candidates.append(path)
                current_download_activity = True

        for candidate in sorted(
            candidates,
            key=lambda path: path.stat().st_mtime_ns,
            reverse=True,
        ):
            try:
                size = candidate.stat().st_size
                if size < 100 or stable_sizes.get(candidate.name) != size:
                    stable_sizes[candidate.name] = size
                    continue
                if not zipfile.is_zipfile(candidate):
                    continue
                with zipfile.ZipFile(candidate) as workbook:
                    if "xl/workbook.xml" not in workbook.namelist():
                        continue
            except (FileNotFoundError, PermissionError, zipfile.BadZipFile):
                continue
            destination = candidate
            break
        else:
            if (
                not current_download_activity
                and time.monotonic() >= fast_handoff
            ):
                _download_signed_result_with_browser_session(
                    port,
                    result["href"],
                    destination,
                )
                break
            time.sleep(0.2)
            continue
        break
    else:
        # Old customized Feikua cores can abort the browser write after the click.
        # Reuse the same signed result URL and the live browser cookies for delivery.
        _download_signed_result_with_browser_session(
            port,
            result["href"],
            destination,
        )

    if not destination.exists() or destination.stat().st_size < 100:
        raise RuntimeError("The Lazada income result was not downloaded completely.")
    if not zipfile.is_zipfile(destination):
        raise RuntimeError("The Lazada income result is not a valid XLSX archive.")
    with zipfile.ZipFile(destination) as workbook:
        if "xl/workbook.xml" not in workbook.namelist():
            raise RuntimeError("The Lazada income result does not contain a workbook.")
    print(f"{marker}: {destination}", flush=True)
    return destination


def close_export_history_dialog(port: int) -> bool:
    """Real-click the visible export-history X and confirm the modal is gone."""
    target = cdp_evaluate(
        port,
        """
        (() => {
          const shown = element => {
            const box = element.getBoundingClientRect();
            const style = getComputedStyle(element);
            return box.width > 1 && box.height > 1 &&
              style.display !== "none" && style.visibility !== "hidden";
          };
          const dialog = [...document.querySelectorAll(
            "[role='dialog'],.next-dialog"
          )].filter(shown).find(element => element.querySelector("tbody tr"));
          const close = dialog?.querySelector("a.next-dialog-close");
          if (!close || !shown(close)) return null;
          close.scrollIntoView({block: "center"});
          const box = close.getBoundingClientRect();
          return {
            x: box.left + box.width / 2,
            y: box.top + box.height / 2
          };
        })()
        """,
    )
    if not target:
        return False
    cdp_mouse_click(port, target["x"], target["y"])
    wait_cdp_value(
        port,
        """
        ![...document.querySelectorAll("[role='dialog'],.next-dialog")]
          .some(element => {
            const box = element.getBoundingClientRect();
            const style = getComputedStyle(element);
            return box.width > 1 && box.height > 1 &&
              style.display !== "none" && style.visibility !== "hidden" &&
              !!element.querySelector("tbody tr");
          })
        """,
        timeout=15,
        interval=0.4,
        description="closed export-history dialog",
    )
    print("LAZADA_EXPORT_HISTORY_DIALOG_CLOSED", flush=True)
    return True


def export_lazada_income_cdp(
    port: int,
    start_date: dt.date,
    end_date: dt.date,
    output_dir: Path,
) -> Path:
    preparation_attempt = 0
    while True:
        preparation_attempt += 1
        try:
            open_lazada_income_released(port)
            close_safe_popups_cdp(port)
            set_released_income_date_range(port, start_date, end_date)
            break
        except RuntimeError as error:
            print(
                "LAZADA_INCOME_PREPARATION_RETRY: "
                f"attempt={preparation_attempt} error={error}; retrying the "
                "same pre-submission step in 30 seconds without closing the shop.",
                flush=True,
            )
            time.sleep(30)
    requested_task_id = start_released_income_excel_export(port)
    result = wait_latest_released_income_result(port, requested_task_id)
    print(
        "LAZADA_INCOME_EXPORT_READY: "
        f"task_id={result['task_id']} filename={result['filename']}",
        flush=True,
    )
    output = download_released_income_result(port, result, output_dir)
    if not close_export_history_dialog(port):
        raise RuntimeError(
            "The Lazada income export history dialog could not be closed."
        )
    return output


def open_lazada_balance(port: int) -> None:
    """Open My Balance using its fixed sidebar identity."""
    close_safe_popups_cdp(port)
    if "/apps/balance" not in str(cdp_evaluate(port, "location.href")):
        try:
            wait_cdp_click_expression(
                port,
                "document.querySelector("
                "'#root_normal_rp_asc_v2_finance_rp_asc_v2_mybalance'"
                ")",
                timeout=3,
                description="My Balance sidebar entry",
            )
            wait_cdp_value(
                port,
                "location.href.includes('/apps/balance')",
                timeout=5,
                description="My Balance sidebar navigation",
            )
        except RuntimeError:
            print("LAZADA_BALANCE_DIRECT_ROUTE_FALLBACK", flush=True)
            cdp_navigate_relative(port, "/apps/balance")
    wait_cdp_value(
        port,
        "location.href.includes('/apps/balance') && "
        "!!document.querySelector('#transactionDateRange')",
        timeout=45,
        description="My Balance page",
    )
    close_safe_popups_cdp(port)


def set_balance_date_range(
    port: int,
    start_date: dt.date,
    end_date: dt.date,
) -> None:
    if start_date > end_date:
        raise RuntimeError("start date must not be later than end date")
    if end_date > dt.date.today():
        raise RuntimeError("balance export end date must not be in the future")
    inputs = open_range_picker(
        port, "#transactionDateRange", "balance date picker"
    )
    if len(inputs) != 2:
        raise RuntimeError("The Lazada balance date picker is incomplete.")
    # The range component may auto-commit after its first edit.  Update the
    # end first so it cannot restore the end date to today's default.
    requested_values = (start_date.isoformat(), end_date.isoformat())
    for index in (1, 0):
        point = inputs[index]
        value = requested_values[index]
        cdp_mouse_click(port, point["x"], point["y"])
        cdp_replace_focused_text(port, value)
        time.sleep(0.2)
    state = wait_entered_range_state(
        port,
        "#transactionDateRange",
        start_date,
        end_date,
        "stable balance date range",
    )
    if state == "applied":
        return
    cdp_click_expression(
        port,
        "document.querySelector("
        "\".next-range-picker-body[aria-hidden='false'] "
        ".next-date-picker-panel-footer button\""
        ")",
    )
    applied_expression = f"""
    (() => {{
      const values = [...document.querySelectorAll(
        "#transactionDateRange input"
      )].map(element => element.value);
      return values[0] === {json.dumps(start_date.isoformat())} &&
        values[1] === {json.dumps(end_date.isoformat())} &&
        !document.querySelector(
          ".next-range-picker-body[aria-hidden='false']"
        );
    }})()
    """
    wait_cdp_value(
        port,
        applied_expression,
        timeout=20,
        description="applied balance date range",
    )
    # The wrapper always carries a loading class; only its indicator/mask means busy.
    time.sleep(0.8)
    ready_expression = """
    (() => {
      const busy = [...document.querySelectorAll(
        ".next-loading-tip,.next-loading-indicator," +
        ".next-loading-fusion-reactor,.next-loading-mask"
      )].some(element => {
        const box = element.getBoundingClientRect();
        const style = getComputedStyle(element);
        return box.width > 1 && box.height > 1 &&
          style.display !== "none" && style.visibility !== "hidden";
      });
      const button = document.querySelector(
        ".search-export button.next-menu-btn"
      );
      if (!button) return false;
      const box = button.getBoundingClientRect();
      return !busy && !button.disabled && box.width > 1 && box.height > 1;
    })()
    """
    wait_cdp_value(
        port,
        ready_expression,
        timeout=45,
        description="loaded balance transaction list",
    )
    time.sleep(0.6)
    wait_cdp_value(
        port,
        ready_expression,
        timeout=10,
        description="stable balance transaction list",
    )


def start_balance_detail_excel_export(port: int) -> str:
    """Start exactly one transaction-details Excel export."""
    if not open_download_menu_with_retry(
        port,
        "document.querySelector('.search-export button.next-menu-btn')",
        "balance download menu",
    ):
        raise NoLazadaFileError("Lazada balance has no downloadable file.")
    # The first item is structurally Transaction details (Excel) in every locale.
    cdp_click_expression(
        port,
        """
        [...document.querySelectorAll(".next-menu-item")].find(element => {
          const box = element.getBoundingClientRect();
          const style = getComputedStyle(element);
          return box.width > 1 && box.height > 1 &&
            style.display !== "none" && style.visibility !== "hidden";
        })
        """,
    )
    return wait_cdp_value(
        port,
        """
        (() => {
          const shown = element => {
            const box = element.getBoundingClientRect();
            const style = getComputedStyle(element);
            return box.width > 1 && box.height > 1 &&
              style.display !== "none" && style.visibility !== "hidden";
          };
          const dialog = [...document.querySelectorAll(
            "[role='dialog'],.next-dialog"
          )].filter(shown).find(element => element.querySelector("tbody tr"));
          return dialog?.querySelector("tbody tr td")?.innerText.trim() || null;
        })()
        """,
        timeout=20,
        interval=0.4,
        description="new balance export task id",
    )


def wait_balance_detail_result(
    port: int,
    requested_task_id: str,
    start_date: dt.date,
    end_date: dt.date,
    timeout: int | None = None,
) -> dict:
    expression = (
        """
        (() => {
          const shown = element => {
            const box = element.getBoundingClientRect();
            const style = getComputedStyle(element);
            return box.width > 1 && box.height > 1 &&
              style.display !== "none" && style.visibility !== "hidden";
          };
          const dialog = [...document.querySelectorAll(
            "[role='dialog'],.next-dialog"
          )].filter(shown).find(element => element.querySelector("tbody tr"));
          if (!dialog) return null;
          const rows = [...dialog.querySelectorAll("tbody tr")];
          const row = rows.find(element => {
            const cell = element.querySelector("td");
            return (cell?.innerText || "").trim() === __TASK_ID__;
          });
          if (!row) return null;
          const cells = [...row.querySelectorAll("td")];
          const link = row.querySelector("a[href]");
          if (cells.length < 4 || !link) return null;
          const status = (cells[2].innerText || "").trim();
          if (!status.toLowerCase().includes("finished")) return null;
          link.scrollIntoView({block: "center"});
          const box = link.getBoundingClientRect();
          return {
            task_id: (cells[0].innerText || "").trim(),
            created_at: (cells[1].innerText || "").trim(),
            status,
            href: link.href,
            x: box.left + box.width / 2,
            y: box.top + box.height / 2
          };
        })()
        """
        .replace("__TASK_ID__", json.dumps(requested_task_id))
    )
    deadline = None if timeout is None else time.monotonic() + timeout
    while True:
        ensure_export_history_open(
            port,
            "document.querySelector('.search-export button.next-menu-btn')",
            "balance-detail export history",
        )
        wait_seconds = 30
        if deadline is not None:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError(
                    "Timed out waiting for requested balance-detail Excel result."
                )
            wait_seconds = min(wait_seconds, remaining)
        try:
            result = wait_cdp_value(
                port,
                expression,
                timeout=wait_seconds,
                interval=1.5,
                description="requested balance-detail Excel result",
            )
            break
        except RuntimeError:
            if deadline is not None and time.monotonic() >= deadline:
                raise
            close_export_history_dialog(port)
    result["filename"] = (
        f"balance_detail_{start_date.isoformat()}_{end_date.isoformat()}.xlsx"
    )
    return result


def export_lazada_balance_cdp(
    port: int,
    start_date: dt.date,
    end_date: dt.date,
    output_dir: Path,
) -> Path:
    preparation_attempt = 0
    while True:
        preparation_attempt += 1
        try:
            open_lazada_balance(port)
            close_safe_popups_cdp(port)
            set_balance_date_range(port, start_date, end_date)
            break
        except RuntimeError as error:
            print(
                "LAZADA_BALANCE_PREPARATION_RETRY: "
                f"attempt={preparation_attempt} error={error}; retrying the "
                "same pre-submission step in 30 seconds without closing the shop.",
                flush=True,
            )
            time.sleep(30)
    requested_task_id = start_balance_detail_excel_export(port)
    result = wait_balance_detail_result(
        port,
        requested_task_id,
        start_date,
        end_date,
    )
    print(
        "LAZADA_BALANCE_EXPORT_READY: "
        f"task_id={result['task_id']} filename={result['filename']}",
        flush=True,
    )
    output = download_released_income_result(
        port,
        result,
        output_dir,
        marker="LAZADA_BALANCE_FILE_CONFIRMED",
    )
    if not close_export_history_dialog(port):
        raise RuntimeError(
            "The Lazada balance export history dialog could not be closed."
        )
    return output


def export_lazada_both_cdp(
    port: int,
    start_date: dt.date,
    end_date: dt.date,
    income_output_dir: Path,
    balance_output_dir: Path,
) -> tuple[Path | None, Path | None]:
    """Check income first, then always check balance when income has no file."""
    income_output = None
    balance_output = None
    try:
        income_output = export_lazada_income_cdp(
            port, start_date, end_date, income_output_dir
        )
        print(f"LAZADA_INCOME_EXPORT_COMPLETE: {income_output}")
    except NoLazadaFileError:
        print("LAZADA_INCOME_NO_FILE", flush=True)

    try:
        balance_output = export_lazada_balance_cdp(
            port, start_date, end_date, balance_output_dir
        )
        print(f"LAZADA_BALANCE_EXPORT_COMPLETE: {balance_output}")
    except NoLazadaFileError:
        print("LAZADA_BALANCE_NO_FILE", flush=True)
    return income_output, balance_output


def emit_lazada_combined_result(
    income_output: Path | None,
    balance_output: Path | None,
) -> None:
    if income_output is not None and balance_output is not None:
        status = "LAZADA_BOTH_DOWNLOADS_COMPLETE"
    elif income_output is None and balance_output is None:
        status = "LAZADA_NO_FILES"
    else:
        status = "LAZADA_PARTIAL_DOWNLOAD_COMPLETE"
    print(
        json.dumps(
            {
                "status": status,
                "income_path": str(income_output) if income_output else None,
                "balance_path": str(balance_output) if balance_output else None,
            },
            ensure_ascii=False,
        )
    )


def print_status(driver, store: dict, base_url: str, port: int, core_version: str) -> None:
    print("LAZADA_SESSION_READY")
    print("shop_name:", store["shop_name"])
    print("shop_id:", store["shop_id"])
    print("feikua_base_url:", base_url)
    print("debugger_port:", port)
    print("core_version:", core_version)
    print("current_url:", driver.current_url)
    print("title:", driver.title)


def main() -> int:
    parser = argparse.ArgumentParser(description="Open one exact Feikua Lazada shop.")
    parser.add_argument("shop_name", help="Exact Feikua shop name")
    parser.add_argument("--connect-only", action="store_true", help="Reach Seller Center and keep it open")
    parser.add_argument("--inspect", action="store_true", help="Inspect the current page without navigation")
    parser.add_argument("--stop", action="store_true", help="Stop this exact shop session")
    parser.add_argument(
        "--export-income",
        action="store_true",
        help="Export released/transferred Order details (Excel)",
    )
    parser.add_argument(
        "--export-balance",
        action="store_true",
        help="Export My Balance transaction details (Excel)",
    )
    parser.add_argument(
        "--export-both",
        action="store_true",
        help="Export income then balance in one preserved CDP session",
    )
    parser.add_argument("--start-date", type=validate_iso_date)
    parser.add_argument("--end-date", type=validate_iso_date)
    parser.add_argument("--out-dir", type=Path)
    parser.add_argument("--income-out-dir", type=Path)
    parser.add_argument("--balance-out-dir", type=Path)
    args = parser.parse_args()
    if sum(
        (
            args.connect_only,
            args.inspect,
            args.stop,
            args.export_income,
            args.export_balance,
            args.export_both,
        )
    ) != 1:
        parser.error(
            "choose exactly one of --connect-only, --inspect, --stop, "
            "--export-income, --export-balance, or --export-both"
        )
    if (args.export_income or args.export_balance) and not all(
        (args.start_date, args.end_date, args.out_dir)
    ):
        parser.error(
            "exports require --start-date, --end-date, and --out-dir"
        )
    if args.export_both and not all(
        (args.start_date, args.end_date, args.income_out_dir, args.balance_out_dir)
    ):
        parser.error(
            "--export-both requires --start-date, --end-date, --income-out-dir, "
            "and --balance-out-dir"
        )

    print("Finding Feikua local service...", flush=True)
    base_url = kernel.find_base_url()
    print("Selecting exact shop...", flush=True)
    store = kernel.select_store(base_url, args.shop_name)
    ACTIVE_STORE_CONTEXT.update(
        {"base_url": base_url, "shop_id": str(store["shop_id"])}
    )
    if args.stop:
        kernel.stop_store(base_url, store["shop_id"])
        print("LAZADA_SESSION_STOPPED")
        return 0

    running = (
        kernel.discover_running_cdp_port(store["shop_id"])
        if store.get("status") != "idle"
        else None
    )
    if running:
        port, core_version = running
        print(f"Reusing running shop session on CDP port {port}...", flush=True)
    else:
        kernel.stop_other_running_stores(base_url, store["shop_id"])
        print("Starting isolated shop session...", flush=True)
        started = kernel.start_store(base_url, store)
        port, core_version = started["debugger_port"], started["core_version"]
        kernel.save_session(store["shop_id"], port, core_version)

    kernel.wait_for_cdp(port)
    store_url = current_regular_url(port)
    if not store_url:
        print("Waiting for Feikua validated store URL...", flush=True)
        store_url = kernel.read_validated_store_url(port)
    kernel.ensure_regular_tab(port, store_url)
    version_payload = kernel.request_json(f"http://127.0.0.1:{port}/json/version", timeout=5)
    browser_label = str(version_payload.get("Browser", ""))
    browser_match = re.search(r"/(\d+\.\d+\.\d+\.\d+)", browser_label)
    actual_core_version = browser_match.group(1) if browser_match else core_version
    if actual_core_version != core_version:
        print(
            "FEIKUA_CORE_VERSION_CORRECTED_FROM_CDP: "
            f"{core_version} -> {actual_core_version}",
            flush=True,
        )
    core_version = actual_core_version
    driver = None
    try:
        driver = kernel.attach(
            port,
            matching_chromedriver(core_version or "135.0.7049.115"),
        )
    except (
        WebDriverException,
        OSError,
        urllib.error.URLError,
        TimeoutError,
    ) as error:
        version_match = re.search(
            r"unrecognized Chrome version:\s*(\d+\.\d+\.\d+\.\d+)",
            str(error),
            re.IGNORECASE,
        )
        if version_match is not None:
            corrected_version = version_match.group(1)
            print(
                "FEIKUA_CORE_VERSION_CORRECTED_FROM_DRIVER_HANDSHAKE: "
                f"{core_version} -> {corrected_version}",
                flush=True,
            )
            core_version = corrected_version
            try:
                driver = kernel.attach(port, matching_chromedriver(core_version))
            except (
                WebDriverException,
                OSError,
                urllib.error.URLError,
                TimeoutError,
            ):
                driver = None

    if driver is None:
        print(
            "LAZADA_NATIVE_CDP_FALLBACK: controlling the customized Feikua core directly.",
            flush=True,
        )
        if args.inspect:
            status = cdp_page_status(port)
            print("LAZADA_SESSION_READY")
            print("shop_name:", store["shop_name"])
            print("shop_id:", store["shop_id"])
            print("feikua_base_url:", base_url)
            print("debugger_port:", port)
            print("core_version:", core_version)
            print("current_url:", status.get("url", ""))
            print("title:", status.get("title", ""))
            print("INSPECTED_SAFE_FORM_DIAGNOSTICS_BEGIN")
            print(json.dumps(cdp_form_diagnostics(port), ensure_ascii=False, indent=2))
            print("INSPECTED_SAFE_FORM_DIAGNOSTICS_END")
            return 0
        try:
            state, status = complete_landing_login_cdp(port)
        except InvalidCredentialsError:
            kernel.stop_store(base_url, store["shop_id"])
            print("LAZADA_INVALID_CREDENTIALS_CONNECTION_STOPPED", flush=True)
            print("LAZADA_MANUAL_EXPORT_REQUIRED", flush=True)
            return 20
        except Exception as error:
            kernel.stop_store(base_url, store["shop_id"])
            print(f"LAZADA_SELLER_HOME_NOT_REACHED: {error}", flush=True)
            print("LAZADA_SELLER_HOME_NOT_REACHED_CONNECTION_STOPPED", flush=True)
            print("LAZADA_MANUAL_EXPORT_REQUIRED", flush=True)
            return 25
        print("LAZADA_SESSION_READY")
        print("shop_name:", store["shop_name"])
        print("shop_id:", store["shop_id"])
        print("feikua_base_url:", base_url)
        print("debugger_port:", port)
        print("core_version:", core_version)
        print("current_url:", status.get("url", ""))
        print("title:", status.get("title", ""))
        if state != "ready":
            kernel.stop_store(base_url, store["shop_id"])
            print(
                f"LAZADA_SELLER_HOME_NOT_REACHED: state={state} "
                f"url={status.get('url', '')}",
                flush=True,
            )
            print("LAZADA_SELLER_HOME_NOT_REACHED_CONNECTION_STOPPED", flush=True)
            print("LAZADA_MANUAL_EXPORT_REQUIRED", flush=True)
            return 25
        if lazada_account_deactivated_cdp(port):
            raise LazadaAccountDeactivatedError(
                "Lazada confirmed that the seller account/store is deactivated."
            )
        if args.export_both:
            print(
                "LAZADA_EXPORT_EXECUTION_STARTED: "
                f"workflow=both range={args.start_date}..{args.end_date}",
                flush=True,
            )
            income_output, balance_output = export_lazada_both_cdp(
                port,
                args.start_date,
                args.end_date,
                args.income_out_dir,
                args.balance_out_dir,
            )
            emit_lazada_combined_result(income_output, balance_output)
            if income_output is None and balance_output is None:
                kernel.stop_store(base_url, store["shop_id"])
                print("LAZADA_NO_FILES_CONNECTION_STOPPED", flush=True)
                return 24
        elif args.export_income:
            print(
                "LAZADA_EXPORT_EXECUTION_STARTED: "
                f"workflow=income range={args.start_date}..{args.end_date}",
                flush=True,
            )
            output = export_lazada_income_cdp(
                port,
                args.start_date,
                args.end_date,
                args.out_dir,
            )
            print(f"LAZADA_INCOME_EXPORT_COMPLETE: {output}")
        elif args.export_balance:
            print(
                "LAZADA_EXPORT_EXECUTION_STARTED: "
                f"workflow=balance range={args.start_date}..{args.end_date}",
                flush=True,
            )
            output = export_lazada_balance_cdp(
                port,
                args.start_date,
                args.end_date,
                args.out_dir,
            )
            print(f"LAZADA_BALANCE_EXPORT_COMPLETE: {output}")
        else:
            print("LAZADA_TRAINING_CONNECTION_PRESERVED")
        return 0

    try:
        switch_to_lazada_page(driver)
        if not is_lazada_url(driver.current_url):
            driver.get(store_url)
        wait_document_ready(driver)
        if args.inspect:
            visible_text = driver.find_element(By.TAG_NAME, "body").text.strip()
            print_status(driver, store, base_url, port, core_version)
            print("INSPECTED_VISIBLE_TEXT_BEGIN")
            print(visible_text[:4000])
            print("INSPECTED_VISIBLE_TEXT_END")
            return 0

        try:
            state = complete_landing_login(driver)
        except InvalidCredentialsError:
            kernel.stop_store(base_url, store["shop_id"])
            print("LAZADA_INVALID_CREDENTIALS_CONNECTION_STOPPED", flush=True)
            print("LAZADA_MANUAL_EXPORT_REQUIRED", flush=True)
            return 20
        except Exception as error:
            kernel.stop_store(base_url, store["shop_id"])
            print(f"LAZADA_SELLER_HOME_NOT_REACHED: {error}", flush=True)
            print("LAZADA_SELLER_HOME_NOT_REACHED_CONNECTION_STOPPED", flush=True)
            print("LAZADA_MANUAL_EXPORT_REQUIRED", flush=True)
            return 25
        print_status(driver, store, base_url, port, core_version)
        if state != "ready":
            kernel.stop_store(base_url, store["shop_id"])
            print(
                f"LAZADA_SELLER_HOME_NOT_REACHED: state={state} "
                f"url={driver.current_url}",
                flush=True,
            )
            print("LAZADA_SELLER_HOME_NOT_REACHED_CONNECTION_STOPPED", flush=True)
            print("LAZADA_MANUAL_EXPORT_REQUIRED", flush=True)
            return 25
        if lazada_account_deactivated_cdp(port):
            raise LazadaAccountDeactivatedError(
                "Lazada confirmed that the seller account/store is deactivated."
            )
        if args.export_both:
            print(
                "LAZADA_EXPORT_EXECUTION_STARTED: "
                f"workflow=both range={args.start_date}..{args.end_date}",
                flush=True,
            )
            income_output, balance_output = export_lazada_both_cdp(
                port,
                args.start_date,
                args.end_date,
                args.income_out_dir,
                args.balance_out_dir,
            )
            emit_lazada_combined_result(income_output, balance_output)
            if income_output is None and balance_output is None:
                kernel.stop_store(base_url, store["shop_id"])
                print("LAZADA_NO_FILES_CONNECTION_STOPPED", flush=True)
                return 24
        elif args.export_income:
            print(
                "LAZADA_EXPORT_EXECUTION_STARTED: "
                f"workflow=income range={args.start_date}..{args.end_date}",
                flush=True,
            )
            output = export_lazada_income_cdp(
                port,
                args.start_date,
                args.end_date,
                args.out_dir,
            )
            print(f"LAZADA_INCOME_EXPORT_COMPLETE: {output}")
        elif args.export_balance:
            print(
                "LAZADA_EXPORT_EXECUTION_STARTED: "
                f"workflow=balance range={args.start_date}..{args.end_date}",
                flush=True,
            )
            output = export_lazada_balance_cdp(
                port,
                args.start_date,
                args.end_date,
                args.out_dir,
            )
            print(f"LAZADA_BALANCE_EXPORT_COMPLETE: {output}")
        else:
            print("LAZADA_TRAINING_CONNECTION_PRESERVED")
        return 0
    finally:
        driver.quit()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except LazadaAccountDeactivatedError as error:
        print(f"LAZADA_ACCOUNT_DEACTIVATED: {error}", flush=True)
        if ACTIVE_STORE_CONTEXT:
            kernel.stop_store(
                ACTIVE_STORE_CONTEXT["base_url"],
                ACTIVE_STORE_CONTEXT["shop_id"],
            )
            print("LAZADA_ACCOUNT_DEACTIVATED_CONNECTION_STOPPED", flush=True)
        print("LAZADA_MANUAL_EXPORT_REQUIRED", flush=True)
        raise SystemExit(26)
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        traceback.print_exc()
        raise SystemExit(1)
