"""Prepare or export a Shopee income report through a Feikua shop session."""

from __future__ import annotations

import argparse
import calendar
import datetime as dt
import io
import json
import os
import re
import sys
import time
import zipfile
from copy import copy
from pathlib import Path

from openpyxl import load_workbook
from selenium.common.exceptions import (
    ElementClickInterceptedException,
    ElementNotInteractableException,
    StaleElementReferenceException,
    TimeoutException,
    WebDriverException,
)
from selenium.webdriver.common.by import By
from selenium.webdriver.common.keys import Keys
from selenium.webdriver.support.ui import WebDriverWait


INCOME_PATH = "/portal/finance/income"
INCOME_LINK = f"a.sidebar-submenu-item-link[href='{INCOME_PATH}']"
BALANCE_PATH = "/portal/finance/wallet/shopeepay"
ORDER_PATH = "/portal/sale/order"
BALANCE_LINK = f"a.sidebar-submenu-item-link[href='{BALANCE_PATH}']"
DATE_TRIGGER = ".date-range .date-range-btn"
DATE_VALUE = ".date-range .date-range-value"
CUSTOM_DATE_OPTION = ".picker-wrapper .opts-panel > ul.options:last-child > li"
DEFAULT_WEEK_OPTION = (
    ".picker-wrapper .opts-panel > ul.options:first-child > li:first-child"
)
DATE_PANEL = ".eds-daterange-picker-panel.date-range-panel"
EXPORT_BUTTON = 'button[data-click="export_excel"]'


class InvalidShopeePasswordError(RuntimeError):
    """Shopee explicitly rejected the password already filled by Feikua."""


class MissingShopeePasswordError(RuntimeError):
    """Shopee requested a password but Feikua did not fill one."""


class ShopeePasswordVerificationNotCompletedError(RuntimeError):
    """Shopee kept the in-site password dialog open without an explicit rejection."""


class ShopeeSellerHomeNotReachedError(RuntimeError):
    """The isolated shop never reached authenticated Shopee Seller Center."""


class NoBalanceDataForPeriodError(RuntimeError):
    """Shopee created a terminal balance report record without a file."""


INVALID_PASSWORD_PATTERNS = (
    re.compile(r"\b(?:incorrect|wrong|invalid)\s+password\b", re.IGNORECASE),
    re.compile(r"\bpassword\s+(?:is\s+)?(?:incorrect|wrong|invalid)\b", re.IGNORECASE),
    re.compile(r"密码.{0,12}(?:错误|不正确|无效)"),
    re.compile(r"(?:错误|不正确|无效).{0,12}密码"),
    re.compile(r"รหัสผ่าน.{0,20}(?:ไม่ถูกต้อง|ผิด)", re.IGNORECASE),
    re.compile(r"(?:kata\s+sandi|password).{0,20}(?:salah|tidak\s+benar)", re.IGNORECASE),
    re.compile(r"mật\s+khẩu.{0,20}(?:sai|không\s+đúng)", re.IGNORECASE),
)
HISTORY_TOGGLE = 'button[data-click="generate_excel"]'
HISTORY_PANEL = '[data-testid="remote-component-report-export-history"]'
EXPORT_HISTORY_REFRESH_SECONDS = 30
DOWNLOAD_CONFIRM_RETRY_SECONDS = 30
MAX_HISTORY_DOWNLOAD_ATTEMPTS = 5
DOWNLOAD_STABLE_CONFIRM_SECONDS = 3.0
POST_VERIFICATION_PAGE_READY_SECONDS = 90
MAX_SECURITY_VERIFICATION_ROUNDS = 2
EXPORT_INTENT_HISTORY_CHECK_SECONDS = 5
BALANCE_APPLY_BUTTON = ".search-buttons button[type='submit']"
BALANCE_EXPORT_BUTTON = "button.report-exporter-button"
BALANCE_HISTORY_TOGGLE = (
    "button.report-exporter-toggle[data-click='latest_report']"
)
ORDER_EXPORT_BUTTON = "button.export.export-with-modal"
ORDER_EXPORT_MODAL = ".eds-modal__container"
ORDER_DATE_TRIGGER = f"{ORDER_EXPORT_MODAL} .eds-date-picker__input"
ORDER_DATE_PANEL = ".eds-daterange-picker-panel"
ORDER_EXPORT_SUBMIT = (
    f"{ORDER_EXPORT_MODAL} .eds-modal__footer "
    "button.eds-button--primary"
)
ORDER_HISTORY_TOGGLE = ".latest .eds-popover__ref button"
ORDER_HISTORY_PANEL = ".latest .export-container"
LOADING_INDICATORS = (
    ".eds-loading, .eds-loading-mask, .eds-loading-overlay, .eds-spinner, "
    "[class*='loading-mask'], [class*='loading-overlay'], "
    "[class*='loading-spinner'], [aria-busy='true']"
)
def load_shared_kernel():
    shared_scripts = Path(__file__).resolve().parents[2] / "tk-bill" / "scripts"
    if not (shared_scripts / "feikua_store.py").is_file():
        raise RuntimeError(f"Shared tk-bill kernel not found: {shared_scripts}")
    sys.path.insert(0, str(shared_scripts))
    from feikua_store import attach, chromedriver_path  # noqa: PLC0415

    return attach, chromedriver_path


def switch_to_shopee(driver, timeout: int = 30) -> None:
    """Wait for and select the Shopee tab in an exact legacy CDP session."""
    deadline = time.monotonic() + timeout
    fallback_handle = None
    while time.monotonic() < deadline:
        try:
            current_url = driver.current_url
            if (
                "accounts.shopee." in current_url
                or "seller.shopee." in current_url
            ):
                return
        except WebDriverException:
            pass
        try:
            handles = list(driver.window_handles)
        except WebDriverException:
            handles = []
        for handle in handles:
            try:
                driver.switch_to.window(handle)
                current_url = driver.current_url
                if (
                    "accounts.shopee." in current_url
                    or "/portal/finance/" in current_url
                ):
                    return
                if "seller.shopee." in current_url:
                    fallback_handle = handle
            except WebDriverException:
                continue
        if fallback_handle is not None:
            driver.switch_to.window(fallback_handle)
            return
        time.sleep(0.5)
    raise RuntimeError(
        "No Shopee Seller Center tab was found after waiting for the exact "
        "legacy Feikua CDP session to enumerate its targets."
    )


def rendered(driver, css: str):
    current = []
    for element in driver.find_elements(By.CSS_SELECTOR, css):
        try:
            if element.is_displayed() and element.is_enabled():
                current.append(element)
        except StaleElementReferenceException:
            continue
    return current


def first_rendered(driver, css: str):
    elements = rendered(driver, css)
    return elements[0] if elements else None


def click_rendered(driver, element) -> None:
    driver.execute_script(
        "arguments[0].scrollIntoView({block:'center', inline:'nearest'});", element
    )
    try:
        element.click()
    except (ElementClickInterceptedException, ElementNotInteractableException):
        driver.execute_script("arguments[0].click();", element)


def pointer_click_rendered(driver, element) -> None:
    """Use a genuine pointer click for legacy animated date-picker controls."""
    box = driver.execute_script(
        """
        let rect = arguments[0].getBoundingClientRect();
        if (
          rect.left < 0 || rect.top < 0 ||
          rect.right > innerWidth || rect.bottom > innerHeight
        ) {
          arguments[0].scrollIntoView({block:'center', inline:'nearest'});
          rect = arguments[0].getBoundingClientRect();
        }
        return {
          x: rect.left + rect.width / 2,
          y: rect.top + rect.height / 2
        };
        """,
        element,
    )
    x = float(box["x"])
    y = float(box["y"])
    driver.execute_cdp_cmd(
        "Input.dispatchMouseEvent",
        {"type": "mouseMoved", "x": x, "y": y},
    )
    driver.execute_cdp_cmd(
        "Input.dispatchMouseEvent",
        {
            "type": "mousePressed",
            "x": x,
            "y": y,
            "button": "left",
            "buttons": 1,
            "clickCount": 1,
        },
    )
    driver.execute_cdp_cmd(
        "Input.dispatchMouseEvent",
        {
            "type": "mouseReleased",
            "x": x,
            "y": y,
            "button": "left",
            "buttons": 0,
            "clickCount": 1,
        },
    )


def click_when_ready(driver, css: str, timeout: int = 20) -> None:
    def resolve_and_click(current):
        try:
            element = first_rendered(current, css)
            if element is None:
                return False
            click_rendered(current, element)
            return True
        except StaleElementReferenceException:
            return False

    WebDriverWait(driver, timeout).until(resolve_and_click)


def rendered_password_input(driver):
    try:
        return driver.execute_script(
            """
            return [...document.querySelectorAll("input[type='password']")]
              .find(element => {
                const box = element.getBoundingClientRect();
                const style = getComputedStyle(element);
                return box.width > 1 && box.height > 1 &&
                  style.display !== "none" && style.visibility !== "hidden";
              }) || null;
            """
        )
    except WebDriverException:
        return first_rendered(driver, "input[type='password']")


def password_modal(driver, password):
    modal = driver.execute_script(
        """
        let el = arguments[0];
        while (el && el !== document.body) {
          const role = el.getAttribute?.('role');
          const cls = String(el.className || '');
          if (role === 'dialog' || /modal|dialog/i.test(cls)) return el;
          el = el.parentElement;
        }
        return null;
        """,
        password,
    )
    if modal is None:
        raise RuntimeError("A password verification field appeared outside a modal.")
    return modal


def modal_reports_invalid_password(modal) -> bool:
    selectors = (
        "[role='alert'], [aria-live='assertive'], "
        ".eds-form-item__error-message, .eds-input__error, "
        "[class*='error-message'], [class*='error_message'], [class*='field-error']"
    )
    candidates = []
    try:
        for element in modal.find_elements(By.CSS_SELECTOR, selectors):
            if element.is_displayed():
                text = element.text.strip()
                if text:
                    candidates.append(text)
        modal_text = modal.text.strip()
        if modal_text:
            candidates.append(modal_text)
    except StaleElementReferenceException:
        return False
    return any(
        pattern.search(text)
        for text in candidates
        for pattern in INVALID_PASSWORD_PATTERNS
    )


def wait_for_password_verification_result(
    driver, timeout: int = 30, allow_remark_retry: bool = True
) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        password = rendered_password_input(driver)
        if password is None:
            return
        try:
            modal = password_modal(driver, password)
            if modal_reports_invalid_password(modal):
                raise InvalidShopeePasswordError(
                    "Shopee explicitly rejected the stored password."
                )
        except StaleElementReferenceException:
            pass
        time.sleep(0.2)
    remark_password = os.environ.get("SHOPEE_IN_SITE_PASSWORD", "")
    password = rendered_password_input(driver)
    if allow_remark_retry and remark_password and password is not None:
        replace_in_site_password(driver, password, remark_password)
        modal = password_modal(driver, password)
        primary = driver.execute_script(
            """
            const modal = arguments[0];
            return [...modal.querySelectorAll(
              "button.eds-button--primary,button[class*='primary'],button[type='submit']"
            )].find(button => {
              const box = button.getBoundingClientRect();
              const style = getComputedStyle(button);
              return !button.disabled && box.width > 1 && box.height > 1 &&
                style.display !== "none" && style.visibility !== "hidden";
            }) || null;
            """,
            modal,
        )
        if primary is not None:
            click_rendered(driver, primary)
            print("SHOPEE_REMARK_PASSWORD_TIMEOUT_RETRY_SUBMITTED", flush=True)
            wait_for_password_verification_result(
                driver,
                timeout=60,
                allow_remark_retry=False,
            )
            return
    raise ShopeePasswordVerificationNotCompletedError(
        "Shopee in-site password verification did not finish."
    )


def replace_in_site_password(driver, password, value: str) -> None:
    """Clear the rendered field through trusted keys, then enter the workbook value."""
    click_rendered(driver, password)
    password.send_keys(Keys.CONTROL, "a")
    password.send_keys(Keys.BACKSPACE)
    password.clear()
    password.send_keys(value)
    replaced = bool(
        driver.execute_script(
            "return arguments[0].value === arguments[1];",
            password,
            value,
        )
    )
    if not replaced:
        raise ShopeePasswordVerificationNotCompletedError(
            "Shopee in-site password could not be replaced cleanly."
        )


def click_optional_security_verification(driver) -> bool:
    """Submit the in-site password without exposing its value."""
    password = rendered_password_input(driver)
    if password is None:
        return False
    remark_password = os.environ.get("SHOPEE_IN_SITE_PASSWORD", "")
    if remark_password:
        replace_in_site_password(driver, password, remark_password)
        print("SHOPEE_REMARK_PASSWORD_FILLED", flush=True)
    password_ready = bool(
        driver.execute_script(
            "return Boolean(arguments[0].value);",
            password,
        )
    )
    if not password_ready:
        raise MissingShopeePasswordError(
            "Shopee requested in-site password verification, but no password "
            "was autofilled and no workbook remark password was available."
        )
    modal = password_modal(driver, password)
    primary = driver.execute_script(
        """
        const modal = arguments[0];
        return [...modal.querySelectorAll(
          "button.eds-button--primary,button[class*='primary'],button[type='submit']"
        )].find(button => {
          const box = button.getBoundingClientRect();
          const style = getComputedStyle(button);
          return !button.disabled && box.width > 1 && box.height > 1 &&
            style.display !== "none" && style.visibility !== "hidden";
        }) || null;
        """,
        modal,
    )
    if primary is None:
        raise RuntimeError(
            "Shopee requested password verification but no primary action was found."
        )
    click_rendered(driver, primary)
    return True


def handle_security_prompt(driver) -> bool:
    """Resolve Shopee's password gate whenever it appears during the workflow."""
    if rendered_password_input(driver) is None:
        return False
    click_optional_security_verification(driver)
    try:
        wait_for_password_verification_result(driver)
    except InvalidShopeePasswordError:
        remark_password = os.environ.get("SHOPEE_IN_SITE_PASSWORD", "")
        password = rendered_password_input(driver)
        if not remark_password or password is None:
            raise
        replace_in_site_password(driver, password, remark_password)
        modal = password_modal(driver, password)
        primary = driver.execute_script(
            """
            const modal = arguments[0];
            return [...modal.querySelectorAll(
              "button.eds-button--primary,button[class*='primary'],button[type='submit']"
            )].find(button => {
              const box = button.getBoundingClientRect();
              const style = getComputedStyle(button);
              return !button.disabled && box.width > 1 && box.height > 1 &&
                style.display !== "none" && style.visibility !== "hidden";
            }) || null;
            """,
            modal,
        )
        if primary is None:
            raise RuntimeError(
                "Shopee rejected the stored password and no retry action was found."
            )
        click_rendered(driver, primary)
        print("SHOPEE_REMARK_PASSWORD_RETRY_SUBMITTED", flush=True)
        wait_for_password_verification_result(driver)
    return True


def on_shopee_login_page(driver) -> bool:
    url = driver.current_url.casefold()
    return "accounts.shopee." in url or "/seller/login" in url


def login_page_reports_invalid_password(driver) -> bool:
    selectors = (
        "[role='alert'], [aria-live='assertive'], "
        "[class*='error-message'], [class*='error_message'], "
        "[class*='field-error'], [class*='form-item'][class*='error']"
    )
    messages = []
    for element in driver.find_elements(By.CSS_SELECTOR, selectors):
        try:
            text = (element.text or "").strip()
            if element.is_displayed() and text:
                messages.append(text)
        except StaleElementReferenceException:
            continue
    return any(
        pattern.search(message)
        for message in messages
        for pattern in INVALID_PASSWORD_PATTERNS
    )


def attempt_autofilled_login(driver) -> bool:
    """Submit Feikua-filled Shopee credentials without reading their values."""
    if not on_shopee_login_page(driver):
        return False
    ready_deadline = time.monotonic() + 60
    next_autofill_prompt = 0.0
    identity = None
    password = None
    submit = None
    while time.monotonic() < ready_deadline:
        password = first_rendered(driver, "input[type='password']")
        identity_candidates = rendered(
            driver,
            "input[type='email'], input[type='text'], input:not([type])",
        )
        identity = identity_candidates[0] if identity_candidates else None
        submit = first_rendered(driver, "button[type='submit']")
        if password is not None and submit is None:
            submit = driver.execute_script(
                """
                const password = arguments[0];
                const shown = (el) => {
                  const box = el.getBoundingClientRect();
                  const style = getComputedStyle(el);
                  return box.width > 0 && box.height > 0 &&
                    style.display !== 'none' && style.visibility !== 'hidden' &&
                    !el.disabled;
                };
                const passwordBox = password.getBoundingClientRect();
                const scope = password.closest('form') ||
                  password.closest("[class*='login' i]") || document;
                return [...scope.querySelectorAll(
                  "button,[role='button'],input[type='submit']"
                )].filter((el) => {
                  if (!shown(el)) return false;
                  const box = el.getBoundingClientRect();
                  return box.top >= passwordBox.bottom - 4 &&
                    box.width >= passwordBox.width * 0.7 &&
                    box.top <= passwordBox.bottom + 180;
                }).sort((left, right) => {
                  const a = left.getBoundingClientRect();
                  const b = right.getBoundingClientRect();
                  return a.top - b.top || b.width - a.width;
                })[0] || null;
                """,
                password,
            )
        if identity is not None and password is not None and submit is not None:
            credentials_ready = bool(
                driver.execute_script(
                    "return Boolean(arguments[0].value) && "
                    "Boolean(arguments[1].value);",
                    identity,
                    password,
                )
            )
            if credentials_ready:
                break
            if time.monotonic() >= next_autofill_prompt:
                # Some Feikua profiles fill only after the login fields receive
                # a real focus event. Prompt both fields without reading,
                # printing, or replacing either credential.
                click_rendered(driver, identity)
                click_rendered(driver, password)
                print("SHOPEE_AUTOFILL_FOCUS_PROMPTED", flush=True)
                next_autofill_prompt = time.monotonic() + 5
        time.sleep(0.25)
    else:
        raise ShopeeSellerHomeNotReachedError(
            "Shopee login page appeared, but Feikua did not provide "
            "autofilled credentials."
        )

    click_rendered(driver, submit)
    print("SHOPEE_AUTOFILLED_LOGIN_SUBMITTED", flush=True)
    result_deadline = time.monotonic() + 45
    while time.monotonic() < result_deadline:
        if login_page_reports_invalid_password(driver):
            raise InvalidShopeePasswordError(
                "Shopee explicitly rejected the Feikua-filled login password."
            )
        if not on_shopee_login_page(driver) and "seller.shopee." in driver.current_url:
            print("SHOPEE_AUTOFILLED_LOGIN_SUCCEEDED", flush=True)
            return True
        time.sleep(0.25)
    raise ShopeeSellerHomeNotReachedError(
        "Shopee autofilled login did not finish and no invalid-password "
        "message was shown."
    )


def ensure_shopee_seller_home(driver) -> None:
    """Accept only an authenticated Seller Center page before bill actions."""
    time.sleep(1)
    if on_shopee_login_page(driver):
        attempt_autofilled_login(driver)
    if on_shopee_login_page(driver) or "seller.shopee." not in driver.current_url:
        raise ShopeeSellerHomeNotReachedError(
            f"Shopee Seller Center home was not reached: {driver.current_url}"
        )
    print("SHOPEE_SELLER_HOME_REACHED", flush=True)


def on_income_page(driver) -> bool:
    return (
        INCOME_PATH in driver.current_url
        and first_rendered(driver, DATE_TRIGGER) is not None
    )


def navigate_shopee_finance_route(driver, path: str, marker: str) -> None:
    origin_match = re.match(r"^(https?://[^/]+)", driver.current_url)
    if origin_match is None:
        raise RuntimeError(f"Could not resolve Shopee origin from {driver.current_url}")
    driver.get(origin_match.group(1) + path)
    print(marker, flush=True)


def next_security_verification_round(current_round: int, context: str) -> int:
    """Bound repeated password gates so a worker cannot renew itself forever."""
    next_round = current_round + 1
    if next_round > MAX_SECURITY_VERIFICATION_ROUNDS:
        raise ShopeePasswordVerificationNotCompletedError(
            f"Shopee repeatedly requested password verification while {context}; "
            "the bounded verification budget was exhausted."
        )
    return next_round


def open_income(driver) -> bool:
    verification_clicked = False
    verification_round = 0
    direct_route_used = False
    time.sleep(1)
    attempt_autofilled_login(driver)
    if INCOME_PATH not in driver.current_url:
        try:
            click_when_ready(driver, INCOME_LINK)
        except TimeoutException:
            attempt_autofilled_login(driver)
            navigate_shopee_finance_route(
                driver,
                INCOME_PATH,
                "SHOPEE_INCOME_DIRECT_ROUTE_FALLBACK",
            )
            direct_route_used = True
    deadline = time.monotonic() + 30
    direct_route_at = time.monotonic() + 5
    while time.monotonic() < deadline:
        if rendered_password_input(driver) is not None:
            verification_round = next_security_verification_round(
                verification_round,
                "opening the income page",
            )
            verification_clicked = click_optional_security_verification(driver)
            wait_for_password_verification_result(driver)
            # Password verification can legitimately spend longer than the
            # original page-ready window.  Start a fresh readiness budget only
            # after the modal has closed so a successfully verified finance
            # page is not misclassified as a timeout.
            deadline = (
                time.monotonic() + POST_VERIFICATION_PAGE_READY_SECONDS
            )
            direct_route_at = time.monotonic() + 5
            direct_route_used = False
            continue
        if on_income_page(driver):
            return verification_clicked
        if (
            not direct_route_used
            and time.monotonic() >= direct_route_at
        ):
            navigate_shopee_finance_route(
                driver,
                INCOME_PATH,
                "SHOPEE_INCOME_DIRECT_ROUTE_FALLBACK",
            )
            direct_route_used = True
            # driver.get() is blocking and can consume the old budget under
            # memory pressure. The route gets its own full readiness window.
            deadline = (
                time.monotonic() + POST_VERIFICATION_PAGE_READY_SECONDS
            )
        time.sleep(0.25)
    raise RuntimeError(f"Shopee income page did not become ready: {driver.current_url}")


def open_income_history(driver) -> bool:
    """Open income history without requiring date controls used only for submission."""
    verification_clicked = False
    verification_round = 0
    attempt_autofilled_login(driver)
    if INCOME_PATH not in driver.current_url:
        navigate_shopee_finance_route(
            driver,
            INCOME_PATH,
            "SHOPEE_INCOME_HISTORY_DIRECT_ROUTE",
        )
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        if rendered_password_input(driver) is not None:
            verification_round = next_security_verification_round(
                verification_round,
                "opening income export history",
            )
            verification_clicked = click_optional_security_verification(driver)
            wait_for_password_verification_result(driver)
            navigate_shopee_finance_route(
                driver,
                INCOME_PATH,
                "SHOPEE_INCOME_HISTORY_POST_VERIFICATION_RELOAD",
            )
            # Reset only after the blocking navigation has returned.
            deadline = (
                time.monotonic() + POST_VERIFICATION_PAGE_READY_SECONDS
            )
            continue
        if INCOME_PATH in driver.current_url and (
            first_rendered(driver, HISTORY_TOGGLE) is not None
            or first_rendered(driver, HISTORY_PANEL) is not None
        ):
            return verification_clicked
        time.sleep(0.25)
    raise RuntimeError(
        "Shopee income export history did not become available: "
        f"{driver.current_url}"
    )


def on_balance_page(driver) -> bool:
    return (
        BALANCE_PATH in driver.current_url
        and first_rendered(driver, DATE_TRIGGER) is not None
        and first_rendered(driver, BALANCE_EXPORT_BUTTON) is not None
    )


def open_balance(driver) -> bool:
    verification_clicked = False
    verification_round = 0
    direct_route_used = False
    time.sleep(1)
    attempt_autofilled_login(driver)
    if BALANCE_PATH not in driver.current_url:
        try:
            click_when_ready(driver, BALANCE_LINK)
        except TimeoutException:
            attempt_autofilled_login(driver)
            navigate_shopee_finance_route(
                driver,
                BALANCE_PATH,
                "SHOPEE_BALANCE_DIRECT_ROUTE_FALLBACK",
            )
            direct_route_used = True
    deadline = time.monotonic() + 30
    direct_route_at = time.monotonic() + 5
    while time.monotonic() < deadline:
        if rendered_password_input(driver) is not None:
            verification_round = next_security_verification_round(
                verification_round,
                "opening the balance page",
            )
            verification_clicked = click_optional_security_verification(driver)
            wait_for_password_verification_result(driver)
            deadline = (
                time.monotonic() + POST_VERIFICATION_PAGE_READY_SECONDS
            )
            direct_route_at = time.monotonic() + 5
            direct_route_used = False
            continue
        if on_balance_page(driver):
            return verification_clicked
        if (
            not direct_route_used
            and time.monotonic() >= direct_route_at
        ):
            navigate_shopee_finance_route(
                driver,
                BALANCE_PATH,
                "SHOPEE_BALANCE_DIRECT_ROUTE_FALLBACK",
            )
            direct_route_used = True
            deadline = (
                time.monotonic() + POST_VERIFICATION_PAGE_READY_SECONDS
            )
        time.sleep(0.25)
    raise RuntimeError(f"Shopee balance page did not become ready: {driver.current_url}")


def date_panel_open(driver) -> bool:
    return first_rendered(driver, DATE_PANEL) is not None


def close_date_panel(driver) -> None:
    if not date_panel_open(driver):
        return
    event = {
        "key": "Escape",
        "code": "Escape",
        "windowsVirtualKeyCode": 27,
        "nativeVirtualKeyCode": 27,
    }
    driver.execute_cdp_cmd(
        "Input.dispatchKeyEvent",
        {"type": "keyDown", **event},
    )
    driver.execute_cdp_cmd(
        "Input.dispatchKeyEvent",
        {"type": "keyUp", **event},
    )
    WebDriverWait(driver, 5, poll_frequency=0.1).until(
        lambda current: not date_panel_open(current)
    )


def open_custom_date_panel(driver) -> None:
    if date_panel_open(driver):
        return
    trigger = WebDriverWait(driver, 20).until(
        lambda current: first_rendered(current, DATE_TRIGGER)
    )
    pointer_click_rendered(driver, trigger)
    option = WebDriverWait(driver, 10).until(
        lambda current: first_rendered(current, CUSTOM_DATE_OPTION)
    )
    # Chromium 96 profiles can expose the option before the slide animation
    # finishes, then silently ignore WebElement.click(). A pointer click after
    # the option has settled reaches the same path as a real operator click.
    time.sleep(0.35)
    pointer_click_rendered(driver, option)
    WebDriverWait(driver, 15).until(date_panel_open)


def calendar_grid_signature(driver, side: str) -> tuple[int, int, int]:
    root = first_rendered(
        driver,
        f".eds-daterange-picker-panel__body-{side}",
    )
    if root is None:
        raise RuntimeError(f"Could not read the {side} calendar body.")
    result = driver.execute_script(
        """
        const root = arguments[0];
        const labels = [...root.querySelectorAll(
          '.eds-picker-header__label.clickable'
        )].map(element => (element.textContent || '').trim());
        const yearText = labels.find(text => /^\\d{4}$/.test(text));
        const rows = [...root.querySelectorAll('.eds-date-table__row')];
        let dayOneColumn = null;
        const days = [];
        for (const row of rows) {
          const cells = [...row.children];
          for (let column = 0; column < cells.length; column += 1) {
            const cell = cells[column];
            if (
              cell.classList.contains('out-of-month')
            ) continue;
            const value = Number((cell.textContent || '').trim());
            if (!Number.isInteger(value) || value < 1 || value > 31) continue;
            days.push(value);
            if (value === 1) dayOneColumn = column;
          }
        }
        if (!yearText || dayOneColumn == null || days.length === 0) {
          return null;
        }
        return [Number(yearText), Math.max(...days), dayOneColumn];
        """,
        root,
    )
    if not result or len(result) != 3:
        raise RuntimeError(
            f"Could not derive the {side} calendar grid signature."
        )
    return int(result[0]), int(result[1]), int(result[2])


def panel_month_pair(driver) -> tuple[tuple[int, int], tuple[int, int]]:
    left_year, left_days, left_column = calendar_grid_signature(
        driver,
        "left",
    )
    right_year, right_days, right_column = calendar_grid_signature(
        driver,
        "right",
    )
    candidates: list[tuple[tuple[int, int], tuple[int, int]]] = []
    for left_month in range(1, 13):
        right_month = left_month % 12 + 1
        expected_right_year = left_year + (1 if left_month == 12 else 0)
        if expected_right_year != right_year:
            continue
        if calendar.monthrange(left_year, left_month)[1] != left_days:
            continue
        if calendar.monthrange(right_year, right_month)[1] != right_days:
            continue
        for week_start in (0, 6):
            expected_left_column = (
                dt.date(left_year, left_month, 1).weekday() - week_start
            ) % 7
            expected_right_column = (
                dt.date(right_year, right_month, 1).weekday() - week_start
            ) % 7
            if (
                expected_left_column == left_column
                and expected_right_column == right_column
            ):
                candidates.append(
                    (
                        (left_year, left_month),
                        (right_year, right_month),
                    )
                )
                break
    unique = list(dict.fromkeys(candidates))
    if len(unique) > 1:
        selected_range = (
            order_selected_range(driver)
            or selected_custom_range(driver)
            or balance_selected_range(driver)
        )
        if selected_range is not None:
            selected_months = {
                (selected_range[0].year, selected_range[0].month),
                (selected_range[1].year, selected_range[1].month),
            }
            narrowed = [
                candidate
                for candidate in unique
                if selected_months.issubset(set(candidate))
            ]
            if narrowed:
                unique = narrowed
    if len(unique) != 1:
        raise RuntimeError(
            "Could not uniquely derive the two calendar months from "
            f"their numeric grids: left={(left_year, left_days, left_column)} "
            f"right={(right_year, right_days, right_column)} "
            f"candidates={unique}"
        )
    return unique[0]


def panel_month(driver, side: str) -> tuple[int, int]:
    left, right = panel_month_pair(driver)
    return left if side == "left" else right


def calendar_months_ready(driver) -> bool:
    try:
        panel_month(driver, "left")
        panel_month(driver, "right")
        return True
    except (RuntimeError, StaleElementReferenceException):
        return False


def previous_month(driver) -> None:
    selector = (
        ".eds-daterange-picker-panel__body-left "
        ".eds-picker-header__prev:not(.disabled)"
    )
    buttons = rendered(driver, selector)
    if len(buttons) < 2:
        raise RuntimeError("Shopee previous-month control was not available.")
    before = panel_month(driver, "left")
    click_rendered(driver, buttons[-1])
    WebDriverWait(driver, 10).until(lambda current: panel_month(current, "left") != before)


def next_month(driver) -> None:
    selector = (
        ".eds-daterange-picker-panel__body-right "
        ".eds-picker-header__next:not(.disabled)"
    )
    buttons = rendered(driver, selector)
    if len(buttons) < 2:
        raise RuntimeError("Shopee next-month control was not available.")
    before = panel_month(driver, "left")
    click_rendered(driver, buttons[0])
    WebDriverWait(driver, 10).until(lambda current: panel_month(current, "left") != before)


def show_start_month(driver, target: dt.date) -> None:
    for _ in range(36):
        visible_month = panel_month(driver, "left")
        wanted = (target.year, target.month)
        if visible_month == wanted:
            return
        visible_index = visible_month[0] * 12 + visible_month[1]
        wanted_index = wanted[0] * 12 + wanted[1]
        if wanted_index < visible_index:
            previous_month(driver)
        else:
            next_month(driver)
    raise RuntimeError("The requested month was not reached within 36 steps.")


def click_calendar_date(driver, target: dt.date) -> None:
    for _ in range(3):
        try:
            side = next(
                (
                    candidate
                    for candidate in ("left", "right")
                    if panel_month(driver, candidate) == (target.year, target.month)
                ),
                None,
            )
            if side is None:
                raise RuntimeError(
                    "Shopee's two-month picker does not currently contain "
                    f"{target:%Y-%m}; use a range spanning at most adjacent months."
                )
            selector = (
                f".eds-daterange-picker-panel__body-{side} "
                ".eds-date-table__cell.normal:not(.disabled):not(.out-of-month)"
            )
            matches = [
                element
                for element in rendered(driver, selector)
                if element.text.strip() == str(target.day)
            ]
            if len(matches) != 1:
                raise RuntimeError(
                    f"Expected one selectable cell for {target}, found {len(matches)}"
                )
            pointer_click_rendered(driver, matches[0])
            return
        except StaleElementReferenceException:
            time.sleep(0.2)
    raise RuntimeError(f"Shopee calendar kept re-rendering before {target} could be clicked.")


def requested_range_in_url(driver, start: dt.date, end: dt.date) -> bool:
    token = f"dateRange={start:%Y%m%d}{end:%Y%m%d}"
    return token in driver.current_url or selected_custom_range(driver) == (start, end)


def parse_shopee_display_date(text: str, current_url: str) -> dt.date:
    """Parse the market-localized numeric date shown by Seller Centre."""
    # Philippines uses month/day while Thailand, Malaysia, Vietnam, Singapore,
    # and most other Shopee markets use day/month. Unambiguous dates still
    # succeed through the fallback format.
    formats = (
        ("%m/%d/%Y", "%d/%m/%Y")
        if "seller.shopee.ph" in current_url
        else ("%d/%m/%Y", "%m/%d/%Y")
    )
    for date_format in formats:
        try:
            return dt.datetime.strptime(text, date_format).date()
        except ValueError:
            continue
    raise ValueError(f"Unsupported Shopee display date: {text!r}")


def selected_custom_range(driver) -> tuple[dt.date, dt.date] | None:
    match = re.search(r"[?&]dateRange=(\d{8})(\d{8})(?:&|$)", driver.current_url)
    if match is not None:
        return (
            dt.datetime.strptime(match.group(1), "%Y%m%d").date(),
            dt.datetime.strptime(match.group(2), "%Y%m%d").date(),
        )
    value = first_rendered(driver, DATE_VALUE)
    if value is None:
        return None
    dates = re.findall(r"\b(\d{2}/\d{2}/\d{4})\b", value.text)
    if len(dates) != 2:
        return None
    return tuple(parse_shopee_display_date(text, driver.current_url) for text in dates)


def select_date_range(driver, start: dt.date, end: dt.date) -> None:
    if end < start:
        raise ValueError("End date must be on or after start date.")
    if end > dt.date.today():
        raise ValueError("Shopee does not allow a future income date.")
    month_span = (end.year - start.year) * 12 + end.month - start.month
    if month_span not in (0, 1):
        raise ValueError("Shopee training currently supports same or adjacent months.")
    # Reusing a live session may leave the requested custom range selected.
    # Avoid reopening that completed selection: clicking its start date again
    # can make Shopee immediately close and re-render the calendar.
    if requested_range_in_url(driver, start, end):
        return

    # With an existing custom range, Shopee may update one endpoint and close
    # the calendar after a single click. Re-read the URL and correct whichever
    # endpoint is still wrong until the exact requested range is selected.
    last_calendar_error: Exception | None = None
    for _ in range(4):
        if requested_range_in_url(driver, start, end):
            return
        current_range = selected_custom_range(driver)
        target = end if current_range and current_range[0] == start else start

        try:
            open_custom_date_panel(driver)
            WebDriverWait(driver, 15, poll_frequency=0.2).until(
                calendar_months_ready
            )
            show_start_month(driver, start)
            click_calendar_date(driver, target)
            time.sleep(0.2)

            if date_panel_open(driver):
                # A fresh range selection remains open after its first endpoint.
                click_calendar_date(driver, end)
                WebDriverWait(driver, 15, poll_frequency=0.2).until(
                    lambda current: not date_panel_open(current)
                )
            WebDriverWait(driver, 15, poll_frequency=0.2).until(
                lambda current: requested_range_in_url(current, start, end)
                or selected_custom_range(current) is not None
            )
        except (
            RuntimeError,
            StaleElementReferenceException,
            ElementNotInteractableException,
            ElementClickInterceptedException,
            TimeoutException,
        ) as exc:
            # Shopee can replace the whole calendar once just after it opens.
            # Re-read the URL on the next iteration so an accepted endpoint is
            # retained instead of restarting the range blindly.
            last_calendar_error = exc
            time.sleep(0.2)
            continue

    origin_match = re.match(r"^(https?://[^/]+)", driver.current_url)
    if origin_match is not None:
        direct_url = (
            origin_match.group(1)
            + INCOME_PATH
            + f"?type=2&dateRange={start:%Y%m%d}{end:%Y%m%d}"
        )
        driver.get(direct_url)
        try:
            WebDriverWait(driver, 30, poll_frequency=0.25).until(
                lambda current: requested_range_in_url(current, start, end)
                and first_rendered(current, DATE_TRIGGER) is not None
            )
            print("SHOPEE_INCOME_DATE_DIRECT_URL_FALLBACK", flush=True)
            return
        except TimeoutException as error:
            last_calendar_error = error

    raise RuntimeError(
        "Shopee did not retain the requested custom date range; "
        f"current URL: {driver.current_url}; last calendar error: {last_calendar_error}"
    )


def balance_selected_range(driver) -> tuple[dt.date, dt.date] | None:
    value = first_rendered(driver, DATE_VALUE)
    if value is None:
        return None
    matches = re.findall(r"\b(\d{2}/\d{2}/\d{4})\b", value.text)
    if len(matches) != 2:
        return None
    return tuple(
        parse_shopee_display_date(text, driver.current_url) for text in matches
    )


def select_balance_date_range(driver, start: dt.date, end: dt.date) -> None:
    if end < start:
        raise ValueError("End date must be on or after start date.")
    if end > dt.date.today():
        raise ValueError("Shopee does not allow a future balance date.")
    month_span = (end.year - start.year) * 12 + end.month - start.month
    if month_span not in (0, 1):
        raise ValueError("Shopee training currently supports same or adjacent months.")

    last_calendar_error: Exception | None = None
    for attempt in range(4):
        current_range = balance_selected_range(driver)
        if current_range == (start, end):
            close_date_panel(driver)
            return
        # Older Seller Centre builds (notably Chromium 96 profiles) can treat
        # a click on the new end date as the first endpoint of a fresh range.
        # Re-select both endpoints after the first few single-endpoint retries
        # so the legacy picker can commit 07/01..07/02 instead of silently
        # retaining 07/01..today.
        reselection_from_start = attempt >= 3
        target = end if current_range and current_range[0] == start else start
        try:
            open_custom_date_panel(driver)
            WebDriverWait(driver, 15, poll_frequency=0.2).until(
                calendar_months_ready
            )
            show_start_month(driver, start)
            click_calendar_date(driver, target)
            time.sleep(0.2)
            if reselection_from_start and date_panel_open(driver):
                # Legacy range pickers treat the first click on a new end date
                # as the start of a replacement range. Complete that state
                # machine explicitly: end -> start -> end.
                click_calendar_date(driver, start)
                time.sleep(0.2)
            if date_panel_open(driver):
                click_calendar_date(driver, end)
                WebDriverWait(driver, 15, poll_frequency=0.2).until(
                    lambda current: not date_panel_open(current)
                )
            WebDriverWait(driver, 15, poll_frequency=0.2).until(
                lambda current: balance_selected_range(current) == (start, end)
            )
            close_date_panel(driver)
            print(
                "SHOPEE_BALANCE_RANGE_CONFIRMED: "
                f"{start.isoformat()}..{end.isoformat()}",
                flush=True,
            )
            return
        except (
            RuntimeError,
            StaleElementReferenceException,
            ElementNotInteractableException,
            ElementClickInterceptedException,
            TimeoutException,
        ) as exc:
            last_calendar_error = exc
            print(
                "SHOPEE_BALANCE_DATE_RETRY: "
                f"attempt={attempt + 1}/4 current={balance_selected_range(driver)}",
                flush=True,
            )
            # The balance calendar is often replaced or left blank while the
            # finance page is still rendering. Give it time to settle before
            # reopening it instead of exhausting all retries immediately.
            time.sleep(0.75)
            continue
    final_range = balance_selected_range(driver)
    if final_range == (start, end):
        print(
            "SHOPEE_BALANCE_RANGE_CONFIRMED_AFTER_FINAL_RETRY: "
            f"{start.isoformat()}..{end.isoformat()}",
            flush=True,
        )
        return
    raise RuntimeError(
        "Shopee did not retain the requested balance date range; "
        f"current selection: {final_range}; "
        f"last calendar error: {last_calendar_error}"
    )


def order_report_filename(start: dt.date, end: dt.date) -> str:
    return (
        "Order.all.order_creation_date."
        f"{start:%Y%m%d}_{end:%Y%m%d}"
    )


def open_orders_page(driver) -> None:
    if ORDER_PATH not in driver.current_url:
        navigate_shopee_finance_route(
            driver,
            ORDER_PATH,
            "SHOPEE_ORDER_DIRECT_ROUTE",
        )
    WebDriverWait(driver, 90, poll_frequency=0.25).until(
        lambda current: (
            ORDER_PATH in current.current_url
            and first_rendered(current, ORDER_EXPORT_BUTTON) is not None
            and first_rendered(current, ORDER_HISTORY_TOGGLE) is not None
        )
    )
    modal = first_rendered(driver, ORDER_EXPORT_MODAL)
    if modal is not None:
        close_icons = rendered(
            driver,
            f"{ORDER_EXPORT_MODAL} .eds-modal__close",
        )
        if close_icons:
            click_rendered(driver, close_icons[-1])
            WebDriverWait(driver, 10, poll_frequency=0.2).until(
                lambda current: first_rendered(
                    current,
                    ORDER_EXPORT_MODAL,
                )
                is None
            )
    print("SHOPEE_ORDER_PAGE_READY", flush=True)


def order_history_open(driver) -> bool:
    return first_rendered(driver, ORDER_HISTORY_PANEL) is not None


def ensure_order_history_open(driver) -> None:
    if order_history_open(driver):
        return
    toggle = first_rendered(driver, ORDER_HISTORY_TOGGLE)
    if toggle is None:
        raise RuntimeError("Shopee order export-history button was not rendered.")
    click_rendered(driver, toggle)
    WebDriverWait(driver, 20, poll_frequency=0.2).until(order_history_open)
    print("SHOPEE_ORDER_HISTORY_OPEN", flush=True)


def order_report_rows(driver, filename: str):
    matches = []
    occurrence_by_name: dict[str, int] = {}
    for _ in range(3):
        try:
            for cell in rendered(
                driver,
                f"{ORDER_HISTORY_PANEL} .eds-table__cell.first-cell",
            ):
                report_name = cell.text.strip()
                report_path = Path(report_name)
                if not (
                    report_name == filename
                    or (
                        report_name.startswith(filename)
                        and report_name[len(filename) : len(filename) + 1]
                        in {".", "_", "-", "(", " "}
                        and report_path.suffix.casefold()
                        in {".zip", ".xlsx", ".xls"}
                    )
                ):
                    continue
                row = driver.execute_script(
                    """
                    return arguments[0].closest(
                      '.eds-table__row, [class*="eds-table__row"]'
                    );
                    """,
                    cell,
                )
                if row is not None:
                    occurrence = occurrence_by_name.get(report_name, 0) + 1
                    occurrence_by_name[report_name] = occurrence
                    matches.append(
                        (
                            f"{report_name}#{occurrence}",
                            report_name,
                            row,
                        )
                    )
            return matches
        except (StaleElementReferenceException, WebDriverException) as error:
            if isinstance(error, WebDriverException) and not any(
                marker in str(error).casefold()
                for marker in (
                    "node with given id does not belong to the document",
                    "no node with given id",
                    "stale element",
                    "detached",
                )
            ):
                raise
            matches = []
            occurrence_by_name = {}
            time.sleep(0.2)
    return []


def order_report_row(driver, filename: str):
    rows = order_report_rows(driver, filename)
    return rows[0][2] if rows else None


def order_selected_range(driver) -> tuple[dt.date, dt.date] | None:
    value = first_rendered(
        driver,
        f"{ORDER_EXPORT_MODAL} .eds-selector__inner",
    )
    if value is None:
        return None
    matches = re.findall(r"\b(\d{4})/(\d{2})/(\d{2})\b", value.text)
    if len(matches) != 2:
        return None
    return tuple(
        dt.date(int(year), int(month), int(day))
        for year, month, day in matches
    )


def order_date_panel_open(driver) -> bool:
    return first_rendered(driver, ORDER_DATE_PANEL) is not None


def click_order_calendar_date(driver, target: dt.date) -> None:
    side = next(
        (
            candidate
            for candidate in ("left", "right")
            if panel_month(driver, candidate)
            == (target.year, target.month)
        ),
        None,
    )
    if side is None:
        raise RuntimeError(
            "Shopee's order date picker does not contain "
            f"{target:%Y-%m}."
        )
    selector = (
        f".eds-daterange-picker-panel__body-{side} "
        ".eds-date-table__cell.normal:not(.disabled):not(.out-of-month)"
    )
    matches = [
        element
        for element in rendered(driver, selector)
        if element.text.strip() == str(target.day)
    ]
    if len(matches) != 1:
        raise RuntimeError(
            f"Expected one order-date cell for {target}, found {len(matches)}"
        )
    inners = matches[0].find_elements(
        By.CSS_SELECTOR,
        ".eds-date-table__cell-inner",
    )
    pointer_click_rendered(driver, inners[0] if inners else matches[0])


def select_order_date_range(
    driver,
    start: dt.date,
    end: dt.date,
) -> None:
    if end < start:
        raise ValueError("End date must be on or after start date.")
    if end > dt.date.today():
        raise ValueError("Shopee does not allow a future order date.")
    month_span = (end.year - start.year) * 12 + end.month - start.month
    if month_span not in (0, 1):
        raise ValueError(
            "Shopee order export supports a range within the same or "
            "adjacent calendar months."
        )
    last_error: Exception | None = None
    for attempt in range(5):
        current_range = order_selected_range(driver)
        if current_range == (start, end):
            return
        target = (
            end
            if current_range is not None
            and current_range[0] == start
            else start
        )
        try:
            if not order_date_panel_open(driver):
                trigger = WebDriverWait(
                    driver,
                    15,
                    poll_frequency=0.2,
                ).until(
                    lambda current: first_rendered(
                        current,
                        ORDER_DATE_TRIGGER,
                    )
                )
                pointer_click_rendered(driver, trigger)
            WebDriverWait(driver, 15, poll_frequency=0.2).until(
                calendar_months_ready
            )
            if not any(
                panel_month(driver, side) == (start.year, start.month)
                for side in ("left", "right")
            ):
                show_start_month(driver, start)
            click_order_calendar_date(driver, target)
            time.sleep(0.75)
            if order_date_panel_open(driver):
                if target != start:
                    click_order_calendar_date(driver, start)
                    time.sleep(0.75)
                if order_date_panel_open(driver):
                    click_order_calendar_date(driver, end)
            WebDriverWait(driver, 15, poll_frequency=0.2).until(
                lambda current: (
                    order_selected_range(current) == (start, end)
                    and not order_date_panel_open(current)
                )
            )
            print(
                "SHOPEE_ORDER_RANGE_CONFIRMED: "
                f"{start.isoformat()}..{end.isoformat()}",
                flush=True,
            )
            return
        except (
            RuntimeError,
            StaleElementReferenceException,
            ElementNotInteractableException,
            ElementClickInterceptedException,
            TimeoutException,
        ) as error:
            last_error = error
            print(
                "SHOPEE_ORDER_DATE_RETRY: "
                f"attempt={attempt + 1}/5 "
                f"current={order_selected_range(driver)}",
                flush=True,
            )
            time.sleep(0.75)
    raise RuntimeError(
        "Shopee did not retain the requested order date range; "
        f"current selection: {order_selected_range(driver)}; "
        f"last calendar error: {last_error}"
    )


def submit_order_export(
    driver,
    start: dt.date,
    end: dt.date,
) -> None:
    export_button = WebDriverWait(
        driver,
        30,
        poll_frequency=0.2,
    ).until(
        lambda current: first_rendered(
            current,
            ORDER_EXPORT_BUTTON,
        )
    )
    click_rendered(driver, export_button)
    WebDriverWait(driver, 20, poll_frequency=0.2).until(
        lambda current: first_rendered(current, ORDER_EXPORT_MODAL)
        is not None
    )
    select_order_date_range(driver, start, end)
    submit = WebDriverWait(
        driver,
        15,
        poll_frequency=0.2,
    ).until(
        lambda current: first_rendered(
            current,
            ORDER_EXPORT_SUBMIT,
        )
    )
    click_rendered(driver, submit)
    WebDriverWait(driver, 30, poll_frequency=0.2).until(
        lambda current: first_rendered(current, ORDER_EXPORT_MODAL)
        is None
    )
    print(
        "SHOPEE_ORDER_EXPORT_SUBMITTED: "
        f"{start.isoformat()}..{end.isoformat()}",
        flush=True,
    )
    try:
        WebDriverWait(driver, 5, poll_frequency=0.2).until(
            order_history_open
        )
    except TimeoutException:
        ensure_order_history_open(driver)


def prepare_order_export(
    driver,
    start: dt.date,
    end: dt.date,
    *,
    submit_if_missing: bool,
) -> str:
    open_orders_page(driver)
    filename = order_report_filename(start, end)
    ensure_order_history_open(driver)
    try:
        existing_rows = WebDriverWait(
            driver,
            30,
            poll_frequency=0.5,
        ).until(
            lambda current: order_report_rows(current, filename) or False
        )
    except TimeoutException:
        existing_rows = []
    if not existing_rows:
        if not submit_if_missing:
            raise RuntimeError(
                "The exact Shopee order report was not present for resume: "
                f"{filename}"
            )
        submit_order_export(driver, start, end)
    else:
        print(
            f"SHOPEE_ORDER_EXISTING_REPORT_REUSED: {filename}",
            flush=True,
        )
    return filename


def ready_order_reports_once(driver, filename: str):
    rows = order_report_rows(driver, filename)
    ready = []
    for key, report_name, row in rows:
        try:
            buttons = [
                button
                for button in row.find_elements(By.CSS_SELECTOR, "button")
                if button.is_displayed()
                and button.is_enabled()
            ]
        except (StaleElementReferenceException, WebDriverException):
            return [], bool(rows), False
        if buttons:
            ready.append((key, report_name, buttons[-1]))
    return ready, bool(rows), bool(rows) and len(ready) == len(rows)


def order_report_download_button(driver, filename: str):
    ready, _, _ = ready_order_reports_once(driver, filename)
    return ready[0][2] if ready else None


def wait_for_order_report(
    driver,
    filename: str,
    deadline: float,
    *,
    refresh_interval_seconds: float | None = None,
):
    processing_announced = False
    next_refresh_at: float | None = None
    transient_dom_messages = (
        "node with given id does not belong to the document",
        "no node with given id",
        "stale element",
        "detached",
    )
    while True:
        now = time.monotonic()
        if not processing_announced and now >= deadline:
            raise TimeoutException(
                "Shopee order report did not appear before the configured "
                f"deadline: {filename}"
            )
        if next_refresh_at is not None and now >= next_refresh_at:
            print(
                "SHOPEE_ORDER_HISTORY_REFRESHING: "
                f"interval={refresh_interval_seconds:g}s filename={filename}",
                flush=True,
            )
            try:
                driver.refresh()
                open_orders_page(driver)
                ensure_order_history_open(driver)
                print(
                    f"SHOPEE_ORDER_HISTORY_REOPENED: {filename}",
                    flush=True,
                )
            except (
                StaleElementReferenceException,
                TimeoutException,
            ) as error:
                print(
                    "SHOPEE_ORDER_HISTORY_REFRESH_RETRY: "
                    f"{type(error).__name__}",
                    flush=True,
                )
            except WebDriverException as error:
                if not any(
                    marker in str(error).casefold()
                    for marker in transient_dom_messages
                ):
                    raise
                print(
                    "SHOPEE_ORDER_HISTORY_REFRESH_RETRY: "
                    f"{type(error).__name__}",
                    flush=True,
                )
            next_refresh_at = (
                time.monotonic() + refresh_interval_seconds
            )
        try:
            if not order_history_open(driver):
                ensure_order_history_open(driver)
            ready, report_exists, all_ready = ready_order_reports_once(
                driver,
                filename,
            )
            if all_ready and ready:
                print(
                    "SHOPEE_ORDER_REPORT_READY: "
                    f"{filename} files={len(ready)}",
                    flush=True,
                )
                return ready[0][2]
            if report_exists:
                if not processing_announced:
                    print(
                        f"SHOPEE_ORDER_REPORT_PROCESSING: {filename}",
                        flush=True,
                    )
                    processing_announced = True
                    if refresh_interval_seconds is not None:
                        next_refresh_at = (
                            time.monotonic()
                            + refresh_interval_seconds
                        )
        except StaleElementReferenceException:
            pass
        except WebDriverException as error:
            if not any(
                marker in str(error).casefold()
                for marker in transient_dom_messages
            ):
                raise
        time.sleep(0.5)


def valid_order_report_file(path: Path) -> bool:
    try:
        size = path.stat().st_size
        if size <= 0:
            return False
        if path.suffix.casefold() in {".zip", ".xlsx"}:
            if not zipfile.is_zipfile(path):
                return False
            with zipfile.ZipFile(path) as archive:
                return (
                    archive.testzip() is None
                    and any(
                        not item.is_dir() and item.file_size > 0
                        for item in archive.infolist()
                    )
                )
        if path.suffix.casefold() == ".xls":
            return (
                path.read_bytes()[:8]
                == b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"
                or size >= 512
            )
    except (
        FileNotFoundError,
        PermissionError,
        zipfile.BadZipFile,
    ):
        return False
    return False


def find_valid_order_reports(
    folder: Path,
    filename: str,
) -> list[Path]:
    candidates = (
        sorted(
            (
                path
                for path in folder.glob(f"{filename}*")
                if path.suffix.casefold() in {".zip", ".xlsx", ".xls"}
            ),
            key=lambda path: path.stat().st_mtime_ns,
            reverse=True,
        )
        if folder.is_dir()
        else []
    )
    return [path for path in candidates if valid_order_report_file(path)]


def find_valid_order_report(
    folder: Path,
    filename: str,
) -> Path | None:
    reports = find_valid_order_reports(folder, filename)
    return reports[0] if reports else None


def confirmed_order_file_once(
    folder: Path,
    filename: str,
    before: dict[Path, tuple[int, int]],
    stable_sizes: dict[Path, tuple[int, float]],
) -> Path | None:
    candidates = (
        sorted(
            (
                path
                for path in folder.glob(f"{filename}*")
                if path.suffix.casefold() in {".zip", ".xlsx", ".xls"}
            ),
            key=lambda path: path.stat().st_mtime_ns,
            reverse=True,
        )
        if folder.is_dir()
        else []
    )
    for path in candidates:
        try:
            state = (path.stat().st_mtime_ns, path.stat().st_size)
            if state[1] <= 0 or before.get(path) == state:
                continue
            observation = stable_sizes.get(path)
            if observation is None or observation[0] != state[1]:
                stable_sizes[path] = (state[1], time.monotonic())
                continue
            if (
                time.monotonic() - observation[1]
                < DOWNLOAD_STABLE_CONFIRM_SECONDS
            ):
                continue
            if not valid_order_report_file(path):
                continue
            return path
        except (
            FileNotFoundError,
            PermissionError,
            zipfile.BadZipFile,
        ):
            continue
    return None


def confirmed_order_files_once(
    folder: Path,
    filename: str,
    before: dict[Path, tuple[int, int]],
    stable_sizes: dict[Path, tuple[int, float]],
) -> list[Path]:
    confirmed = []
    for path in find_valid_order_reports(folder, filename):
        try:
            state = (path.stat().st_mtime_ns, path.stat().st_size)
            if state[1] <= 0 or before.get(path) == state:
                continue
            observation = stable_sizes.get(path)
            if observation is None or observation[0] != state[1]:
                stable_sizes[path] = (state[1], time.monotonic())
                continue
            if (
                time.monotonic() - observation[1]
                < DOWNLOAD_STABLE_CONFIRM_SECONDS
            ):
                continue
            if valid_order_report_file(path):
                confirmed.append(path)
        except (
            FileNotFoundError,
            PermissionError,
            zipfile.BadZipFile,
        ):
            continue
    return confirmed


def download_order_reports(
    driver,
    shop_name: str,
    filename: str,
    timeout: int,
    *,
    refresh_interval_seconds: float | None = None,
) -> Path:
    download_folder = Path.home() / "Desktop" / "feikua" / shop_name
    deadline = time.monotonic() + timeout
    wait_for_order_report(
        driver,
        filename,
        deadline,
        refresh_interval_seconds=refresh_interval_seconds,
    )
    ready, _, all_ready = ready_order_reports_once(driver, filename)
    if not all_ready or not ready:
        raise RuntimeError(
            "Shopee order history changed before all generated files could "
            f"be collected: {filename}"
        )
    expected_count = len(ready)
    existing = find_valid_order_reports(download_folder, filename)
    if len(existing) >= expected_count:
        for path in existing[:expected_count]:
            print(f"SHOPEE_ORDER_LOCAL_FILE_REUSED: {path}", flush=True)
        return existing[:expected_count]

    before = file_snapshot(download_folder)
    stable_sizes: dict[Path, tuple[int, float]] = {}
    for attempt in range(1, MAX_HISTORY_DOWNLOAD_ATTEMPTS + 1):
        if attempt > 1:
            wait_for_order_report(
                driver,
                filename,
                deadline,
                refresh_interval_seconds=refresh_interval_seconds,
            )
            ready, _, all_ready = ready_order_reports_once(driver, filename)
            if not all_ready or len(ready) != expected_count:
                raise RuntimeError(
                    "Shopee order history file count changed during a "
                    f"same-row download retry: {filename}"
                )
        click_started_ns = time.time_ns()
        for key, report_name, button in ready:
            click_rendered(driver, button)
            print(
                "SHOPEE_ORDER_DOWNLOAD_CLICKED: "
                f"attempt={attempt}/{MAX_HISTORY_DOWNLOAD_ATTEMPTS} "
                f"key={key} filename={report_name}",
                flush=True,
            )
            time.sleep(0.2)
        retry_at = time.monotonic() + DOWNLOAD_CONFIRM_RETRY_SECONDS
        while (
            time.monotonic() < retry_at
            or active_partial_download(
                download_folder,
                click_started_ns,
            )
        ):
            reports = find_valid_order_reports(download_folder, filename)
            new_reports = []
            for path in reports:
                try:
                    state = (path.stat().st_mtime_ns, path.stat().st_size)
                except (FileNotFoundError, PermissionError):
                    continue
                if before.get(path) == state:
                    continue
                observation = stable_sizes.get(path)
                if observation is None or observation[0] != state[1]:
                    stable_sizes[path] = (state[1], time.monotonic())
                    continue
                if (
                    time.monotonic() - observation[1]
                    < DOWNLOAD_STABLE_CONFIRM_SECONDS
                ):
                    continue
                if valid_order_report_file(path):
                    new_reports.append(path)
            confirmed = list(
                dict.fromkeys(existing + new_reports)
            )
            if len(confirmed) >= expected_count:
                confirmed = confirmed[:expected_count]
                for path in confirmed:
                    print(
                        f"SHOPEE_ORDER_FILE_CONFIRMED: {path}",
                        flush=True,
                    )
                return confirmed
            time.sleep(0.2)
    raise RuntimeError(
        "Shopee order-history download did not create every stable valid "
        "report after bounded same-row retries; the export job was not "
        "resubmitted."
    )


def download_order_report(
    driver,
    shop_name: str,
    filename: str,
    timeout: int,
    *,
    refresh_interval_seconds: float | None = None,
) -> Path:
    return download_order_reports(
        driver,
        shop_name,
        filename,
        timeout,
        refresh_interval_seconds=refresh_interval_seconds,
    )[0]


def export_all_orders(
    driver,
    shop_name: str,
    start: dt.date,
    end: dt.date,
    *,
    submit_if_missing: bool,
    timeout: int,
) -> dict:
    filename = order_report_filename(start, end)
    download_folder = Path.home() / "Desktop" / "feikua" / shop_name
    recovered_paths = find_valid_order_reports(download_folder, filename)
    if recovered_paths:
        for path in recovered_paths:
            print(f"SHOPEE_ORDER_LOCAL_FILE_REUSED: {path}", flush=True)
        return {
            "status": "SHOPEE_ORDER_DOWNLOAD_COMPLETE",
            "url": driver.current_url,
            "start": start.isoformat(),
            "end": end.isoformat(),
            "order_filename": filename,
            "order_path": str(recovered_paths[0]),
            "order_paths": [str(path) for path in recovered_paths],
        }
    filename = prepare_order_export(
        driver,
        start,
        end,
        submit_if_missing=submit_if_missing,
    )
    paths = download_order_reports(
        driver,
        shop_name,
        filename,
        timeout,
        refresh_interval_seconds=(
            30.0 if (end - start).days >= 7 else None
        ),
    )
    return {
        "status": "SHOPEE_ORDER_DOWNLOAD_COMPLETE",
        "url": driver.current_url,
        "start": start.isoformat(),
        "end": end.isoformat(),
        "order_filename": filename,
        "order_path": str(paths[0]),
        "order_paths": [str(path) for path in paths],
    }


def current_week_range(today: dt.date) -> tuple[dt.date, dt.date]:
    return today - dt.timedelta(days=today.weekday()), today


def select_default_week(driver) -> None:
    if "dateRange=THIS_WEEK" in driver.current_url:
        return
    click_when_ready(driver, DATE_TRIGGER)
    click_when_ready(driver, DEFAULT_WEEK_OPTION)
    WebDriverWait(driver, 15).until(
        lambda current: "dateRange=THIS_WEEK" in current.current_url
    )


def wait_for_security_prompt(driver, timeout: float = 2.0) -> bool:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if rendered_password_input(driver) is not None:
            return True
        time.sleep(0.2)
    return False


def income_report_job_exists_after_click(
    driver,
    start: dt.date,
    end: dt.date,
) -> bool:
    """Check the exact-date history before permitting one blocked-click retry."""
    open_income_history(driver)
    suffix = f"{start:%Y%m%d}_{end:%Y%m%d}.xlsx"
    deadline = time.monotonic() + EXPORT_INTENT_HISTORY_CHECK_SECONDS
    history_seen = False
    while time.monotonic() < deadline:
        if first_rendered(driver, HISTORY_PANEL) is None:
            try:
                click_when_ready(driver, HISTORY_TOGGLE, timeout=1)
            except TimeoutException:
                time.sleep(0.2)
                continue
        history_seen = first_rendered(driver, HISTORY_PANEL) is not None
        if history_seen:
            for row in rendered(driver, f"{HISTORY_PANEL} .list-item"):
                try:
                    names = row.find_elements(By.CSS_SELECTOR, ".name")
                    if names and names[0].text.strip().endswith(suffix):
                        return True
                except StaleElementReferenceException:
                    continue
        time.sleep(0.2)
    if not history_seen:
        raise RuntimeError(
            "Shopee income Export History could not be inspected after the "
            "password-blocked click; Export will not be clicked again."
        )
    return False


def wait_for_income_ready(driver, start: dt.date, end: dt.date, timeout: int = 90) -> None:
    """Wait for the selected income view to finish loading before export."""
    default_week = (start, end) == current_week_range(dt.date.today())
    stable_since = None
    verification_round = 0
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if handle_security_prompt(driver):
            verification_round = next_security_verification_round(
                verification_round,
                "waiting for the income page to become export-ready",
            )
            # Security verification may consume the whole readiness window.
            # Give the verified page a fresh budget before judging its SPA state.
            deadline = time.monotonic() + timeout
            stable_since = None
            continue
        try:
            range_ready = (
                "dateRange=THIS_WEEK" in driver.current_url
                if default_week
                else requested_range_in_url(driver, start, end)
            )
            loading = any(
                element.is_displayed()
                for element in driver.find_elements(By.CSS_SELECTOR, LOADING_INDICATORS)
            )
            export_button = first_rendered(driver, EXPORT_BUTTON)
            ready = (
                INCOME_PATH in driver.current_url
                and driver.execute_script("return document.readyState") == "complete"
                and range_ready
                and not date_panel_open(driver)
                and rendered_password_input(driver) is None
                and not loading
                and export_button is not None
                and export_button.get_attribute("aria-disabled") != "true"
            )
        except StaleElementReferenceException:
            ready = False

        if ready:
            stable_since = stable_since or time.monotonic()
            # Shopee is a SPA: require a quiet, continuously ready window after
            # the URL/date change instead of trusting document.readyState alone.
            if time.monotonic() - stable_since >= 0.6:
                return
        else:
            stable_since = None
        time.sleep(0.2)
    raise TimeoutError("Shopee income page did not finish loading before export.")


def submit_export(driver, start: dt.date, end: dt.date) -> bool:
    """Explicitly submit the selected range, repeating after verification."""
    default_week = (start, end) == current_week_range(dt.date.today())
    verification_clicked = False
    try:
        wait_for_income_ready(driver, start, end)
    except TimeoutError:
        # Shopee's income SPA can leave the correct custom range in the URL
        # while the table and export controls remain on a stale render. Reload
        # that exact range once, then require the full readiness gate again.
        expected_url = driver.current_url
        if not requested_range_in_url(driver, start, end):
            raise
        print("SHOPEE_INCOME_READY_RELOAD_RETRY", flush=True)
        driver.get(expected_url)
        wait_for_income_ready(driver, start, end, timeout=90)
    if not default_week and not requested_range_in_url(driver, start, end):
        raise RuntimeError(
            "Shopee income range changed before export submission: "
            f"{driver.current_url}"
        )
    print(
        f"SHOPEE_INCOME_RANGE_VERIFIED: {start:%Y-%m-%d}..{end:%Y-%m-%d}",
        flush=True,
    )
    print("SHOPEE_INCOME_EXPORT_CLICK_INTENT_RECORDED", flush=True)
    click_when_ready(driver, EXPORT_BUTTON)
    print("SHOPEE_INCOME_EXPORT_BUTTON_CLICKED", flush=True)

    if wait_for_security_prompt(driver):
        verification_clicked = click_optional_security_verification(driver)
        wait_for_password_verification_result(driver)
        if income_report_job_exists_after_click(driver, start, end):
            print(
                "SHOPEE_INCOME_EXISTING_JOB_REUSED_AFTER_VERIFICATION",
                flush=True,
            )
            return verification_clicked
        print(
            "SHOPEE_INCOME_FIRST_CLICK_BLOCKED_NO_HISTORY_JOB: "
            "one explicit retry is allowed.",
            flush=True,
        )
        navigate_shopee_finance_route(
            driver,
            INCOME_PATH,
            "SHOPEE_INCOME_POST_VERIFICATION_RETRY_ROUTE",
        )
        open_income(driver)
        if default_week:
            select_default_week(driver)
        elif not requested_range_in_url(driver, start, end):
            select_date_range(driver, start, end)
        # The first click was consumed by verification. Export must always be
        # clicked explicitly after the selected range has been restored.
        wait_for_income_ready(driver, start, end)
        print("SHOPEE_INCOME_EXPORT_RETRY_INTENT_RECORDED", flush=True)
        click_when_ready(driver, EXPORT_BUTTON)
        print("SHOPEE_INCOME_EXPORT_BUTTON_CLICKED_AFTER_VERIFICATION", flush=True)
    return verification_clicked


def wait_for_balance_ready(
    driver, start: dt.date, end: dt.date, timeout: int = 120
) -> None:
    stable_since = None
    verification_round = 0
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if handle_security_prompt(driver):
            verification_round = next_security_verification_round(
                verification_round,
                "waiting for the balance page to become export-ready",
            )
            # Keep the balance readiness budget independent from time spent in
            # password verification, matching the income-page behavior above.
            deadline = time.monotonic() + timeout
            stable_since = None
            continue
        try:
            loading = any(
                element.is_displayed()
                for element in driver.find_elements(By.CSS_SELECTOR, LOADING_INDICATORS)
            )
            export_button = first_rendered(driver, BALANCE_EXPORT_BUTTON)
            ready = (
                BALANCE_PATH in driver.current_url
                and driver.execute_script("return document.readyState") == "complete"
                and balance_selected_range(driver) == (start, end)
                and not date_panel_open(driver)
                and rendered_password_input(driver) is None
                and not loading
                and export_button is not None
                and export_button.get_attribute("aria-disabled") != "true"
            )
        except StaleElementReferenceException:
            ready = False
        if ready:
            stable_since = stable_since or time.monotonic()
            if time.monotonic() - stable_since >= 0.6:
                return
        else:
            stable_since = None
        time.sleep(0.2)
    raise TimeoutError("Shopee balance page did not finish loading before export.")


BALANCE_TRANSACTION_TYPES = [
    101, 102, 2101, 2102, 402, 401, 404, 405, 460, 406, 407, 408,
    412, 415, 411, 461, 413, 414, 418, 419, 464, 463, 462, 465,
    468, 471, 472, 473, 477, 474, 478, 479, 2406, 2407, 502, 501,
    503, 450, 301, 505, 504, 302, 451, 203, 201, 4001, 4000, 452,
    458, 459, 801, 802, 803,
]


def shopee_balance_api(driver, path: str, payload: dict) -> dict:
    result = driver.execute_async_script(
        """
        const path = arguments[0];
        const payload = arguments[1];
        const done = arguments[arguments.length - 1];
        fetch(path, {
          method: "POST",
          credentials: "include",
          headers: {"content-type": "application/json"},
          body: JSON.stringify(payload)
        })
          .then(response => response.json())
          .then(done)
          .catch(error => done({error: -1, error_msg: String(error)}));
        """,
        path,
        payload,
    )
    if not isinstance(result, dict) or result.get("error") not in (0, None):
        raise RuntimeError(f"Shopee balance export API failed: {result}")
    return result


def existing_balance_report(driver, start: dt.date, end: dt.date) -> dict | None:
    result = shopee_balance_api(
        driver,
        "/api/v4/seller/local_wallet/get_wallet_transaction_reports",
        {
            "wallet_provider": 0,
            "page": 1,
            "limit": 10,
            "statuses": [1, 2, 3, 4, 5],
        },
    )
    expected = (
        f"my_balance_transaction_report.shopee."
        f"{start:%Y%m%d}_{end:%Y%m%d}.xlsx"
    )
    reports = (result.get("data") or {}).get("wallet_transaction_reports") or []
    return next(
        (report for report in reports if report.get("file_name") == expected),
        None,
    )


def balance_report_job_exists_after_click(
    driver,
    start: dt.date,
    end: dt.date,
) -> bool:
    """Poll the exact-date report API before permitting one blocked-click retry."""
    deadline = time.monotonic() + EXPORT_INTENT_HISTORY_CHECK_SECONDS
    while time.monotonic() < deadline:
        if existing_balance_report(driver, start, end) is not None:
            return True
        time.sleep(0.2)
    return False


def balance_report_has_no_file(driver, start: dt.date, end: dt.date) -> bool:
    report = existing_balance_report(driver, start, end)
    return report is not None and report.get("status") == 3


def submit_balance_export_api(driver, start: dt.date, end: dt.date) -> dict:
    result = shopee_balance_api(
        driver,
        "/api/v4/seller/local_wallet/export_wallet_transactions",
        {
            "wallet_provider": 0,
            "start_date": start.isoformat(),
            "end_date": end.isoformat(),
            "transaction_types": BALANCE_TRANSACTION_TYPES,
            "file_type": 1,
            "client_type": 1,
        },
    )
    report = (result.get("data") or {}).get("wallet_transaction_report")
    if not isinstance(report, dict):
        raise RuntimeError(f"Shopee balance export API returned no report: {result}")
    return report


def submit_balance_export(
    driver, start: dt.date, end: dt.date
) -> bool:
    existing = existing_balance_report(driver, start, end)
    if existing is not None:
        if existing.get("status") == 3:
            raise NoBalanceDataForPeriodError(
                "Shopee balance report reached terminal status without a file."
            )
        print(
            "SHOPEE_BALANCE_EXISTING_REPORT_REUSED: "
            f"{existing.get('file_name')}",
            flush=True,
        )
        return False
    # Prefer the rendered UI, but legacy Seller Centre builds can permanently
    # swallow the animated "Select Date" option. The authenticated export API
    # takes explicit ISO start/end fields and is therefore a safe, auditable
    # recovery once bounded UI attempts have failed.
    try:
        select_balance_date_range(driver, start, end)
        click_when_ready(driver, BALANCE_APPLY_BUTTON)
        print(
            f"SHOPEE_BALANCE_APPLY_CLICKED: {start:%Y-%m-%d}..{end:%Y-%m-%d}",
            flush=True,
        )
        wait_for_balance_ready(driver, start, end)
    except (RuntimeError, TimeoutError, TimeoutException) as error:
        report = submit_balance_export_api(driver, start, end)
        if int(report.get("status", 0) or 0) == 3:
            raise NoBalanceDataForPeriodError(
                "Shopee balance export API confirmed no file for the period."
            ) from error
        print(
            "SHOPEE_BALANCE_API_FALLBACK_SUBMITTED: "
            f"{start:%Y-%m-%d}..{end:%Y-%m-%d} "
            f"file={report.get('file_name')}",
            flush=True,
        )
        return False
    print("SHOPEE_BALANCE_EXPORT_CLICK_INTENT_RECORDED", flush=True)
    click_when_ready(driver, BALANCE_EXPORT_BUTTON)
    print("SHOPEE_BALANCE_EXPORT_BUTTON_CLICKED", flush=True)

    verification_clicked = False
    if wait_for_security_prompt(driver):
        verification_clicked = click_optional_security_verification(driver)
        wait_for_password_verification_result(driver)
        if balance_report_job_exists_after_click(driver, start, end):
            print(
                "SHOPEE_BALANCE_EXISTING_JOB_REUSED_AFTER_VERIFICATION",
                flush=True,
            )
            return verification_clicked
        print(
            "SHOPEE_BALANCE_FIRST_CLICK_BLOCKED_NO_HISTORY_JOB: "
            "one explicit retry is allowed.",
            flush=True,
        )
        open_balance(driver)
        select_balance_date_range(driver, start, end)
        click_when_ready(driver, BALANCE_APPLY_BUTTON)
        print(
            "SHOPEE_BALANCE_APPLY_CLICKED_AFTER_VERIFICATION: "
            f"{start:%Y-%m-%d}..{end:%Y-%m-%d}",
            flush=True,
        )
        wait_for_balance_ready(driver, start, end)
        print("SHOPEE_BALANCE_EXPORT_RETRY_INTENT_RECORDED", flush=True)
        click_when_ready(driver, BALANCE_EXPORT_BUTTON)
        print("SHOPEE_BALANCE_EXPORT_BUTTON_CLICKED_AFTER_VERIFICATION", flush=True)

    return verification_clicked


def ensure_balance_history_open(driver) -> None:
    while first_rendered(driver, HISTORY_PANEL) is None:
        try:
            click_when_ready(driver, BALANCE_HISTORY_TOGGLE)
            WebDriverWait(driver, 10).until(
                lambda current: first_rendered(current, HISTORY_PANEL) is not None
            )
        except TimeoutException:
            time.sleep(1)
    print("SHOPEE_BALANCE_HISTORY_PANEL_OPENED", flush=True)


def expected_balance_report_row(driver, start: dt.date, end: dt.date):
    stem = f"my_balance_transaction_report.shopee.{start:%Y%m%d}_{end:%Y%m%d}"
    expected_names = {f"{stem}.xlsx", f"{stem}.zip"}
    first_match = None
    for row in rendered(driver, f"{HISTORY_PANEL} .list-item:not(.list-head)"):
        try:
            names = row.find_elements(By.CSS_SELECTOR, ".name")
            if names:
                filename = names[0].text.strip()
                if filename in expected_names:
                    if first_match is None:
                        first_match = (row, filename)
                    buttons = [
                        button
                        for button in row.find_elements(
                            By.CSS_SELECTOR, "button[data-click='download']"
                        )
                        if button.is_displayed() and button.is_enabled()
                    ]
                    if buttons:
                        return row, filename
        except StaleElementReferenceException:
            # The history list can re-render while a report changes from
            # Processing to Download. Resolve the rows again on the next poll.
            continue
    return first_match or (None, f"{stem}.xlsx")


def wait_for_balance_report(driver, start: dt.date, end: dt.date, timeout: int):
    last_history_click = 0.0
    verification_round = 0
    while True:
        if handle_security_prompt(driver):
            verification_round = next_security_verification_round(
                verification_round,
                "waiting for the submitted balance report",
            )
            # An Export intent already exists. Verification may reopen the page,
            # but must never create a second server-side report from this loop.
            open_balance(driver)
            print(
                "SHOPEE_BALANCE_EXPORT_INTENT_PRESERVED_AFTER_VERIFICATION",
                flush=True,
            )
            last_history_click = 0.0
            continue
        if first_rendered(driver, HISTORY_PANEL) is None:
            now = time.monotonic()
            if now - last_history_click >= 2.0:
                try:
                    click_when_ready(driver, BALANCE_HISTORY_TOGGLE, timeout=1)
                    last_history_click = now
                except TimeoutException:
                    pass
            time.sleep(0.2)
            continue
        row, filename = expected_balance_report_row(driver, start, end)
        if row is not None:
            try:
                buttons = [
                    button
                    for button in row.find_elements(
                        By.CSS_SELECTOR, "button[data-click='download']"
                    )
                    if button.is_displayed() and button.is_enabled()
                ]
            except StaleElementReferenceException:
                time.sleep(0.2)
                continue
            if buttons:
                print(
                    f"SHOPEE_BALANCE_REPORT_READY: {filename}",
                    flush=True,
                )
                return buttons[0], filename
        # Report creation is asynchronous on Shopee's server. Keep the popup
        # open and poll its current DOM without toggling, resubmitting, or
        # timing out while Shopee is still generating the requested report.
        time.sleep(0.2)


def download_balance_report(
    driver,
    shop_name: str,
    start: dt.date,
    end: dt.date,
    timeout: int,
) -> tuple[Path, str]:
    download_button, filename = wait_for_balance_report(
        driver, start, end, timeout
    )
    download_folder = Path.home() / "Desktop" / "feikua" / shop_name
    before = file_snapshot(download_folder)
    click_rendered(driver, download_button)
    print(f"SHOPEE_BALANCE_DOWNLOAD_CLICKED: {filename}", flush=True)
    path = wait_for_download(download_folder, filename, before)
    print(f"SHOPEE_BALANCE_FILE_CONFIRMED: {path}", flush=True)
    return path, filename


def export_balance(
    driver,
    shop_name: str,
    start: dt.date,
    end: dt.date,
    timeout: int,
) -> dict:
    try:
        verification_clicked = submit_balance_export(driver, start, end)
    except NoBalanceDataForPeriodError:
        print("SHOPEE_BALANCE_NO_DATA_FOR_PERIOD", flush=True)
        return {
            "status": "SHOPEE_BALANCE_NO_DATA_FOR_PERIOD",
            "url": driver.current_url,
            "start": start.isoformat(),
            "end": end.isoformat(),
            "verification_clicked": False,
            "filename": None,
            "path": None,
        }
    path, filename = download_balance_report(
        driver, shop_name, start, end, timeout
    )
    return {
        "status": "SHOPEE_BALANCE_DOWNLOAD_COMPLETE",
        "url": driver.current_url,
        "start": start.isoformat(),
        "end": end.isoformat(),
        "verification_clicked": verification_clicked,
        "filename": filename,
        "path": str(path),
    }


def resume_balance_download(
    driver,
    shop_name: str,
    start: dt.date,
    end: dt.date,
    timeout: int,
) -> dict:
    path, filename = download_balance_report(
        driver, shop_name, start, end, timeout
    )
    return {
        "status": "SHOPEE_BALANCE_DOWNLOAD_COMPLETE",
        "url": driver.current_url,
        "start": start.isoformat(),
        "end": end.isoformat(),
        "verification_clicked": False,
        "filename": filename,
        "path": str(path),
    }


def ensure_history_open(driver) -> None:
    while first_rendered(driver, HISTORY_PANEL) is None:
        try:
            click_when_ready(driver, HISTORY_TOGGLE)
            WebDriverWait(driver, 10).until(
                lambda current: first_rendered(current, HISTORY_PANEL) is not None
            )
        except TimeoutException:
            time.sleep(1)
    print("SHOPEE_INCOME_HISTORY_PANEL_OPENED", flush=True)


def expected_report_row(driver, start: dt.date, end: dt.date):
    suffix = f"{start:%Y%m%d}_{end:%Y%m%d}.xlsx"
    first_match = None
    for row in rendered(driver, f"{HISTORY_PANEL} .list-item"):
        try:
            names = row.find_elements(By.CSS_SELECTOR, ".name")
            if names and names[0].text.strip().endswith(suffix):
                filename = names[0].text.strip()
                if first_match is None:
                    first_match = (row, filename)
                buttons = [
                    button
                    for button in row.find_elements(By.CSS_SELECTOR, "button")
                    if button.is_displayed() and button.is_enabled()
                ]
                if buttons:
                    return row, filename
        except StaleElementReferenceException:
            # Shopee replaces history-row DOM nodes as generation progresses.
            # Treat a stale node as a normal poll miss, not a terminal failure.
            continue
    return first_match or (None, None)


def wait_for_report(driver, start: dt.date, end: dt.date, timeout: int):
    last_history_click = 0.0
    verification_round = 0
    while True:
        if handle_security_prompt(driver):
            verification_round = next_security_verification_round(
                verification_round,
                "waiting for the submitted income report",
            )
            # The initial click may already have been accepted. Resume history
            # inspection only; re-clicking here can generate duplicate exports.
            open_income_history(driver)
            print(
                "SHOPEE_INCOME_EXPORT_INTENT_PRESERVED_AFTER_VERIFICATION",
                flush=True,
            )
            last_history_click = 0.0
            continue
        if first_rendered(driver, HISTORY_PANEL) is None:
            now = time.monotonic()
            if now - last_history_click >= 2.0:
                try:
                    click_when_ready(driver, HISTORY_TOGGLE, timeout=1)
                    last_history_click = now
                except TimeoutException:
                    pass
            time.sleep(0.2)
            continue
        row, filename = expected_report_row(driver, start, end)
        if row is not None:
            try:
                buttons = [
                    button
                    for button in row.find_elements(By.CSS_SELECTOR, "button")
                    if button.is_displayed() and button.is_enabled()
                ]
            except StaleElementReferenceException:
                time.sleep(0.2)
                continue
            if buttons:
                print(f"SHOPEE_INCOME_REPORT_READY: {filename}", flush=True)
                return row, buttons[-1], filename
        # Read-only polling keeps the browser available for the user and must
        # not time out while Shopee is still generating the requested report.
        time.sleep(0.2)


def file_snapshot(folder: Path) -> dict[Path, tuple[int, int]]:
    if not folder.is_dir():
        return {}
    return {
        path: (path.stat().st_mtime_ns, path.stat().st_size)
        for pattern in ("*.xlsx", "*.zip")
        for path in folder.glob(pattern)
    }


def active_partial_download(folder: Path, click_started_ns: int) -> bool:
    """Return whether Chrome still has a download created by this click."""
    if not folder.is_dir() or click_started_ns <= 0:
        return False
    for pattern in ("*.crdownload", "*.tmp"):
        for path in folder.glob(pattern):
            try:
                if path.stat().st_mtime_ns >= click_started_ns - 2_000_000_000:
                    return True
            except (FileNotFoundError, PermissionError):
                continue
    return False


def latest_valid_workbook(folder: Path, patterns: tuple[str, ...]) -> Path | None:
    """Recover an exact-date workbook already confirmed in this shop folder."""
    if not folder.is_dir():
        return None
    candidates = sorted(
        {
            path
            for pattern in patterns
            for path in folder.glob(pattern)
            if path.is_file()
        },
        key=lambda path: path.stat().st_mtime_ns,
        reverse=True,
    )
    for path in candidates:
        try:
            if path.stat().st_size <= 0 or not zipfile.is_zipfile(path):
                continue
            with zipfile.ZipFile(path) as workbook:
                if "xl/workbook.xml" in workbook.namelist():
                    return path
        except (FileNotFoundError, PermissionError, zipfile.BadZipFile):
            continue
    return None


def merge_balance_workbook_parts(
    workbook_payloads: list[bytes],
    destination: Path,
) -> Path:
    """Merge Shopee's paginated balance XLSX parts into one verified workbook."""
    if not workbook_payloads:
        raise ValueError("Shopee balance ZIP did not contain any XLSX parts.")
    if len(workbook_payloads) == 1:
        destination.write_bytes(workbook_payloads[0])
        return destination

    workbooks = [
        load_workbook(io.BytesIO(payload), data_only=False)
        for payload in workbook_payloads
    ]
    base = workbooks[0]
    for additional in workbooks[1:]:
        if additional.sheetnames != base.sheetnames:
            raise RuntimeError(
                "Shopee balance workbook parts have different worksheet layouts."
            )
        for sheet_name in base.sheetnames:
            target = base[sheet_name]
            source = additional[sheet_name]
            header_row = next(
                (
                    row_number
                    for row_number in range(1, min(source.max_row, 100) + 1)
                    if str(source.cell(row_number, 1).value or "").strip()
                    in {"Date", "日期"}
                ),
                None,
            )
            if header_row is None:
                raise RuntimeError(
                    f"Shopee balance part has no transaction header: {sheet_name}"
                )

            # Sum the per-part summary totals and transaction counts.
            summary_rows = {
                str(target.cell(row_number, 1).value or "").strip(): row_number
                for row_number in range(1, min(target.max_row, header_row) + 1)
            }
            for source_row in range(1, header_row):
                label = str(source.cell(source_row, 1).value or "").strip()
                target_row = summary_rows.get(label)
                if label in {
                    "Total Money In",
                    "Total Money Out",
                    "进账总额",
                    "支出总额",
                } and target_row:
                    for column in (5, 7):
                        left = target.cell(target_row, column).value or 0
                        right = source.cell(source_row, column).value or 0
                        target.cell(target_row, column).value = left + right

            for source_row in range(header_row + 1, source.max_row + 1):
                target_row = target.max_row + 1
                target.row_dimensions[target_row].height = (
                    source.row_dimensions[source_row].height
                )
                for column in range(1, source.max_column + 1):
                    source_cell = source.cell(source_row, column)
                    target_cell = target.cell(target_row, column, source_cell.value)
                    if source_cell.has_style:
                        target_cell._style = copy(source_cell._style)
                    if source_cell.number_format:
                        target_cell.number_format = source_cell.number_format
                    if source_cell.hyperlink:
                        target_cell._hyperlink = copy(source_cell.hyperlink)

    destination.parent.mkdir(parents=True, exist_ok=True)
    base.save(destination)
    return destination


def confirmed_download_once(
    folder: Path,
    filename: str,
    before: dict[Path, tuple[int, int]],
    stable_sizes: dict[Path, tuple[int, float]],
) -> Path | None:
    """Return the requested new stable workbook without blocking navigation."""
    requested = Path(filename)
    stem = requested.stem
    candidates = (
        sorted(
            folder.glob(f"{stem}*{requested.suffix}"),
            key=lambda path: path.stat().st_mtime_ns,
            reverse=True,
        )
        if folder.is_dir()
        else []
    )
    for path in candidates:
        state = (path.stat().st_mtime_ns, path.stat().st_size)
        if state[1] <= 0 or before.get(path) == state:
            continue
        stable_observation = stable_sizes.get(path)
        if (
            stable_observation is None
            or stable_observation[0] != state[1]
        ):
            stable_sizes[path] = (state[1], time.monotonic())
            continue
        if (
            time.monotonic() - stable_observation[1]
            < DOWNLOAD_STABLE_CONFIRM_SECONDS
        ):
            continue
        try:
            if path.suffix.casefold() == ".zip":
                with zipfile.ZipFile(path) as archive:
                    workbook_names = [
                        name
                        for name in archive.namelist()
                        if name.casefold().endswith(".xlsx")
                        and not name.endswith("/")
                    ]
                    if not workbook_names:
                        continue
                    workbook_names.sort()
                    workbook_payloads = [
                        archive.read(workbook_name)
                        for workbook_name in workbook_names
                    ]
                for workbook_payload in workbook_payloads:
                    with zipfile.ZipFile(io.BytesIO(workbook_payload)) as workbook:
                        if "xl/workbook.xml" not in workbook.namelist():
                            raise zipfile.BadZipFile(
                                "Shopee balance ZIP contained an invalid XLSX part."
                            )
                extracted = folder / f"{path.stem}.xlsx"
                merge_balance_workbook_parts(workbook_payloads, extracted)
                return extracted
            if not zipfile.is_zipfile(path):
                continue
            with zipfile.ZipFile(path) as workbook:
                if "xl/workbook.xml" not in workbook.namelist():
                    continue
        except (FileNotFoundError, PermissionError, zipfile.BadZipFile):
            continue
        return path
    return None


def wait_for_download(
    folder: Path,
    filename: str,
    before: dict[Path, tuple[int, int]],
    timeout: int = 60,
) -> Path:
    stable_sizes: dict[Path, tuple[int, float]] = {}
    while True:
        path = confirmed_download_once(folder, filename, before, stable_sizes)
        if path is not None:
            return path
        time.sleep(0.2)


def export_income(
    driver,
    shop_name: str,
    start: dt.date,
    end: dt.date,
    timeout: int,
) -> dict:
    state = start_income_download(
        driver, shop_name, start, end, timeout
    )
    path = wait_for_download(
        state["download_folder"],
        state["filename"],
        state["before"],
    )
    return {
        "status": "SHOPEE_INCOME_DOWNLOAD_COMPLETE",
        "url": driver.current_url,
        "start": start.isoformat(),
        "end": end.isoformat(),
        "verification_clicked": state["verification_clicked"],
        "filename": state["filename"],
        "path": str(path),
    }


def start_income_download(
    driver,
    shop_name: str,
    start: dt.date,
    end: dt.date,
    timeout: int,
) -> dict:
    if (start, end) == current_week_range(dt.date.today()):
        select_default_week(driver)
    else:
        select_date_range(driver, start, end)
    verification_clicked = submit_export(driver, start, end)
    row, download_button, filename = wait_for_report(driver, start, end, timeout)
    download_folder = Path.home() / "Desktop" / "feikua" / shop_name
    before = file_snapshot(download_folder)
    click_rendered(driver, download_button)
    print(f"SHOPEE_INCOME_DOWNLOAD_CLICKED: {filename}", flush=True)
    return {
        "verification_clicked": verification_clicked,
        "filename": filename,
        "download_folder": download_folder,
        "before": before,
    }


def refresh_page_without_timeout(driver) -> None:
    try:
        driver.refresh()
    except TimeoutException:
        try:
            driver.execute_script("window.stop();")
        except Exception:
            pass
    while True:
        try:
            if driver.execute_script("return document.readyState") in (
                "interactive",
                "complete",
            ):
                return
        except Exception:
            pass
        time.sleep(0.5)


def refresh_income_export_history(driver) -> bool:
    """Refresh the income page once and reopen its history without resubmitting."""
    verification_clicked = open_income_history(driver)
    refresh_page_without_timeout(driver)
    verification_clicked = open_income_history(driver) or verification_clicked
    ensure_history_open(driver)
    print("SHOPEE_INCOME_HISTORY_REFRESHED", flush=True)
    return verification_clicked


def refresh_balance_export_history(driver) -> bool:
    """Refresh the balance page once and reopen its history without resubmitting."""
    verification_clicked = open_balance(driver)
    refresh_page_without_timeout(driver)
    verification_clicked = open_balance(driver) or verification_clicked
    ensure_balance_history_open(driver)
    print("SHOPEE_BALANCE_HISTORY_REFRESHED", flush=True)
    return verification_clicked


def refresh_order_export_history(driver) -> None:
    """Refresh the orders page and reopen export history without resubmitting."""
    open_orders_page(driver)
    try:
        driver.refresh()
    except TimeoutException:
        pass
    open_orders_page(driver)
    ensure_order_history_open(driver)
    print("SHOPEE_ORDER_HISTORY_REFRESHED", flush=True)


def ready_income_report_once(driver, start: dt.date, end: dt.date):
    row, filename = expected_report_row(driver, start, end)
    if row is None:
        return None, filename, False
    try:
        buttons = [
            button
            for button in row.find_elements(By.CSS_SELECTOR, "button")
            if button.is_displayed() and button.is_enabled()
        ]
    except StaleElementReferenceException:
        return None, filename, True
    return (buttons[-1] if buttons else None), filename, True


def ready_balance_report_once(driver, start: dt.date, end: dt.date):
    row, filename = expected_balance_report_row(driver, start, end)
    if row is None:
        return None, filename
    try:
        buttons = [
            button
            for button in row.find_elements(
                By.CSS_SELECTOR, "button[data-click='download']"
            )
            if button.is_displayed() and button.is_enabled()
        ]
    except StaleElementReferenceException:
        return None, filename
    return (buttons[0] if buttons else None), filename


def export_both(
    driver_holder: list,
    shop_name: str,
    start: dt.date,
    end: dt.date,
    timeout: int,
    reconnect,
    submit_jobs: bool = True,
    include_orders: bool = False,
) -> dict:
    driver = driver_holder[0]
    verification_clicked = False
    balance_no_data = False
    order_filename = None
    if submit_jobs:
        # Submit every required server-side job first. Do not block a later
        # submission behind an earlier asynchronous report generation.
        if (start, end) == current_week_range(dt.date.today()):
            select_default_week(driver)
        else:
            select_date_range(driver, start, end)
        verification_clicked = submit_export(driver, start, end)
        print(
            f"SHOPEE_INCOME_EXPORT_SUBMITTED: {start:%Y-%m-%d} to {end:%Y-%m-%d}",
            flush=True,
        )

        verification_clicked = open_balance(driver) or verification_clicked
        try:
            verification_clicked = (
                submit_balance_export(driver, start, end) or verification_clicked
            )
            print(
                f"SHOPEE_BALANCE_EXPORT_SUBMITTED: "
                f"{start:%Y-%m-%d} to {end:%Y-%m-%d}",
                flush=True,
            )
        except NoBalanceDataForPeriodError:
            balance_no_data = True
            print("SHOPEE_BALANCE_NO_DATA_FOR_PERIOD", flush=True)
        if include_orders:
            order_filename = prepare_order_export(
                driver,
                start,
                end,
                submit_if_missing=True,
            )
            print(
                "SHOPEE_OFFICIAL_WAREHOUSE_THREE_JOB_SUBMISSION_COMPLETE",
                flush=True,
            )
    else:
        print(
            "SHOPEE_RESUMING_SUBMITTED_EXPORTS_WITHOUT_RESUBMISSION",
            flush=True,
        )
        if include_orders:
            order_filename = order_report_filename(start, end)
            local_order_folder = (
                Path.home() / "Desktop" / "feikua" / shop_name
            )
            if not find_valid_order_reports(
                local_order_folder,
                order_filename,
            ):
                order_filename = prepare_order_export(
                    driver,
                    start,
                    end,
                    submit_if_missing=False,
                )

    download_folder = Path.home() / "Desktop" / "feikua" / shop_name
    income_filename = None
    balance_filename = None
    income_before: dict[Path, tuple[int, int]] = {}
    balance_before: dict[Path, tuple[int, int]] = {}
    income_stable: dict[Path, tuple[int, float]] = {}
    balance_stable: dict[Path, tuple[int, float]] = {}
    income_path = None
    balance_path = None
    income_clicked = False
    balance_clicked = False
    income_click_attempts = 0
    balance_click_attempts = 0
    income_last_click_at = 0.0
    balance_last_click_at = 0.0
    income_click_started_ns = 0
    balance_click_started_ns = 0
    income_no_data = False
    income_missing_history_cycles = 0
    order_paths: list[Path] = []
    order_before: dict[Path, tuple[int, int]] = {}
    order_stable: dict[Path, tuple[int, float]] = {}
    order_clicked = False
    order_click_attempts = 0
    order_last_click_at = 0.0
    order_click_started_ns = 0
    order_expected_count = 0
    cycle = 0
    if not submit_jobs:
        date_suffix = f"{start:%Y%m%d}_{end:%Y%m%d}"
        income_path = latest_valid_workbook(
            download_folder,
            (f"Income.*{date_suffix}*.xlsx",),
        )
        balance_path = latest_valid_workbook(
            download_folder,
            (f"my_balance_transaction_report.shopee.{date_suffix}*.xlsx",),
        )
        if income_path is not None:
            print(f"SHOPEE_INCOME_FILE_RECOVERED: {income_path}", flush=True)
        if balance_path is not None:
            print(f"SHOPEE_BALANCE_FILE_RECOVERED: {balance_path}", flush=True)
        if include_orders and order_filename is not None:
            order_paths = find_valid_order_reports(
                download_folder,
                order_filename,
            )
            for path in order_paths:
                print(f"SHOPEE_ORDER_FILE_RECOVERED: {path}", flush=True)

    while (
        (income_path is None and not income_no_data)
        or (balance_path is None and not balance_no_data)
        or (include_orders and not order_paths)
    ):
        cycle += 1
        print(f"SHOPEE_EXPORT_HISTORY_REFRESH_CYCLE: {cycle}", flush=True)

        try:
            income_retry_due = (
                income_clicked
                and income_path is None
                and income_filename is not None
                and income_click_attempts < MAX_HISTORY_DOWNLOAD_ATTEMPTS
                and (
                    time.monotonic() - income_last_click_at
                    >= DOWNLOAD_CONFIRM_RETRY_SECONDS
                )
                and not active_partial_download(
                    download_folder, income_click_started_ns
                )
            )
            if (
                income_path is None
                and not income_no_data
                and (not income_clicked or income_retry_due)
            ):
                verification_clicked = (
                    refresh_income_export_history(driver) or verification_clicked
                )
                income_button, candidate_name, income_job_exists = ready_income_report_once(
                    driver, start, end
                )
                if income_job_exists:
                    income_missing_history_cycles = 0
                else:
                    income_missing_history_cycles += 1
                    if income_missing_history_cycles >= 2:
                        income_no_data = True
                        print(
                            "SHOPEE_INCOME_NO_DATA_FOR_PERIOD: export was "
                            "submitted, but Export History has no matching job.",
                            flush=True,
                        )
                if income_button is not None and candidate_name is not None:
                    income_filename = candidate_name
                    if income_click_attempts == 0:
                        income_before = file_snapshot(download_folder)
                    income_click_started_ns = time.time_ns()
                    click_rendered(driver, income_button)
                    income_clicked = True
                    income_click_attempts += 1
                    income_last_click_at = time.monotonic()
                    print(
                        f"SHOPEE_INCOME_REPORT_READY: {income_filename}",
                        flush=True,
                    )
                    print(
                        f"SHOPEE_INCOME_DOWNLOAD_CLICKED: {income_filename}",
                        flush=True,
                    )
                    if income_click_attempts > 1:
                        print(
                            "SHOPEE_INCOME_HISTORY_DOWNLOAD_RETRIED: "
                            f"attempt={income_click_attempts}/"
                            f"{MAX_HISTORY_DOWNLOAD_ATTEMPTS} "
                            f"filename={income_filename}",
                            flush=True,
                        )

            balance_retry_due = (
                balance_clicked
                and balance_path is None
                and balance_filename is not None
                and balance_click_attempts < MAX_HISTORY_DOWNLOAD_ATTEMPTS
                and (
                    time.monotonic() - balance_last_click_at
                    >= DOWNLOAD_CONFIRM_RETRY_SECONDS
                )
                and not active_partial_download(
                    download_folder, balance_click_started_ns
                )
            )
            if (
                balance_path is None
                and not balance_no_data
                and (not balance_clicked or balance_retry_due)
            ):
                verification_clicked = (
                    refresh_balance_export_history(driver) or verification_clicked
                )
                balance_button, candidate_name = ready_balance_report_once(
                    driver, start, end
                )
                if balance_button is not None and candidate_name is not None:
                    balance_filename = candidate_name
                    if balance_click_attempts == 0:
                        balance_before = file_snapshot(download_folder)
                    balance_click_started_ns = time.time_ns()
                    click_rendered(driver, balance_button)
                    balance_clicked = True
                    balance_click_attempts += 1
                    balance_last_click_at = time.monotonic()
                    print(
                        f"SHOPEE_BALANCE_REPORT_READY: {balance_filename}",
                        flush=True,
                    )
                    print(
                        f"SHOPEE_BALANCE_DOWNLOAD_CLICKED: {balance_filename}",
                        flush=True,
                    )
                    if balance_click_attempts > 1:
                        print(
                            "SHOPEE_BALANCE_HISTORY_DOWNLOAD_RETRIED: "
                            f"attempt={balance_click_attempts}/"
                            f"{MAX_HISTORY_DOWNLOAD_ATTEMPTS} "
                            f"filename={balance_filename}",
                            flush=True,
                        )
                elif balance_report_has_no_file(driver, start, end):
                    balance_no_data = True
                    print("SHOPEE_BALANCE_NO_DATA_FOR_PERIOD", flush=True)

            order_retry_due = (
                order_clicked
                and not order_paths
                and order_click_attempts < MAX_HISTORY_DOWNLOAD_ATTEMPTS
                and (
                    time.monotonic() - order_last_click_at
                    >= DOWNLOAD_CONFIRM_RETRY_SECONDS
                )
                and not active_partial_download(
                    download_folder,
                    order_click_started_ns,
                )
            )
            if (
                include_orders
                and not order_paths
                and order_filename is not None
                and (not order_clicked or order_retry_due)
            ):
                refresh_order_export_history(driver)
                ready_orders, order_exists, all_orders_ready = (
                    ready_order_reports_once(driver, order_filename)
                )
                if order_exists and not all_orders_ready:
                    print(
                        "SHOPEE_ORDER_REPORT_PROCESSING: "
                        f"{order_filename}",
                        flush=True,
                    )
                if all_orders_ready and ready_orders:
                    if order_expected_count == 0:
                        order_expected_count = len(ready_orders)
                    elif len(ready_orders) != order_expected_count:
                        raise RuntimeError(
                            "Shopee order history file count changed after "
                            "download readiness was established."
                        )
                    if order_click_attempts == 0:
                        order_before = file_snapshot(download_folder)
                    order_click_started_ns = time.time_ns()
                    for key, report_name, button in ready_orders:
                        click_rendered(driver, button)
                        print(
                            "SHOPEE_ORDER_DOWNLOAD_CLICKED: "
                            f"key={key} filename={report_name}",
                            flush=True,
                        )
                        time.sleep(0.2)
                    order_clicked = True
                    order_click_attempts += 1
                    order_last_click_at = time.monotonic()
        except WebDriverException as error:
            signal = str(error).casefold()
            if not any(
                marker in signal
                for marker in (
                    "unable to receive message from renderer",
                    "disconnected",
                    "not connected to devtools",
                )
            ):
                raise
            try:
                driver.service.stop()
            except Exception:
                pass
            driver = reconnect()
            driver_holder[0] = driver
            switch_to_shopee(driver)
            print(
                "SHOPEE_RENDERER_REATTACHED_DURING_EXPORT_WAIT",
                flush=True,
            )
            time.sleep(2)
            continue

        refresh_deadline = time.monotonic() + EXPORT_HISTORY_REFRESH_SECONDS
        while time.monotonic() < refresh_deadline:
            if income_clicked and income_path is None and income_filename is not None:
                income_path = confirmed_download_once(
                    download_folder,
                    income_filename,
                    income_before,
                    income_stable,
                )
                if income_path is not None:
                    print(
                        f"SHOPEE_INCOME_FILE_CONFIRMED: {income_path}",
                        flush=True,
                    )
            if balance_clicked and balance_path is None and balance_filename is not None:
                balance_path = confirmed_download_once(
                    download_folder,
                    balance_filename,
                    balance_before,
                    balance_stable,
                )
                if balance_path is not None:
                    print(
                        f"SHOPEE_BALANCE_FILE_CONFIRMED: {balance_path}",
                        flush=True,
                    )
            if (
                include_orders
                and order_clicked
                and not order_paths
                and order_filename is not None
            ):
                confirmed_orders = confirmed_order_files_once(
                    download_folder,
                    order_filename,
                    order_before,
                    order_stable,
                )
                if (
                    order_expected_count > 0
                    and len(confirmed_orders) >= order_expected_count
                ):
                    order_paths = confirmed_orders[:order_expected_count]
                    for path in order_paths:
                        print(
                            f"SHOPEE_ORDER_FILE_CONFIRMED: {path}",
                            flush=True,
                        )
            if (
                (income_path is not None or income_no_data)
                and (balance_path is not None or balance_no_data)
                and (not include_orders or bool(order_paths))
            ):
                break
            time.sleep(0.2)

        if (
            income_clicked
            and income_path is None
            and income_click_attempts >= MAX_HISTORY_DOWNLOAD_ATTEMPTS
            and (
                time.monotonic() - income_last_click_at
                >= DOWNLOAD_CONFIRM_RETRY_SECONDS
            )
            and not active_partial_download(
                download_folder, income_click_started_ns
            )
        ):
            print(
                "SHOPEE_INCOME_DOWNLOAD_DELIVERY_STALLED: "
                f"attempts={income_click_attempts} filename={income_filename}",
                flush=True,
            )
            raise RuntimeError(
                "Shopee income history download did not create a local file "
                "after bounded same-row retries; the export job was not "
                "resubmitted."
            )
        if (
            balance_clicked
            and balance_path is None
            and balance_click_attempts >= MAX_HISTORY_DOWNLOAD_ATTEMPTS
            and (
                time.monotonic() - balance_last_click_at
                >= DOWNLOAD_CONFIRM_RETRY_SECONDS
            )
            and not active_partial_download(
                download_folder, balance_click_started_ns
            )
        ):
            print(
                "SHOPEE_BALANCE_DOWNLOAD_DELIVERY_STALLED: "
                f"attempts={balance_click_attempts} filename={balance_filename}",
                flush=True,
            )
            raise RuntimeError(
                "Shopee balance history download did not create a local file "
                "after bounded same-row retries; the export job was not "
                "resubmitted."
            )
        if (
            include_orders
            and order_clicked
            and not order_paths
            and order_click_attempts >= MAX_HISTORY_DOWNLOAD_ATTEMPTS
            and (
                time.monotonic() - order_last_click_at
                >= DOWNLOAD_CONFIRM_RETRY_SECONDS
            )
            and not active_partial_download(
                download_folder,
                order_click_started_ns,
            )
        ):
            print(
                "SHOPEE_ORDER_DOWNLOAD_DELIVERY_STALLED: "
                f"attempts={order_click_attempts} filename={order_filename}",
                flush=True,
            )
            raise RuntimeError(
                "Shopee order history downloads did not create every local "
                "file after bounded same-row retries; the export job was not "
                "resubmitted."
            )

    if income_path is not None:
        income_no_data = False
    if balance_path is not None:
        balance_no_data = False
    if income_path is not None and balance_path is not None:
        result_status = "SHOPEE_BOTH_DOWNLOADS_COMPLETE"
    elif income_no_data and balance_no_data:
        result_status = "SHOPEE_NO_FILES_FOR_PERIOD"
    elif income_no_data or balance_no_data:
        result_status = "SHOPEE_PARTIAL_DOWNLOAD_COMPLETE"
    else:
        raise RuntimeError(
            "Shopee export wait ended without a file or no-data terminal "
            "result for every required section."
        )
    return {
        "status": result_status,
        "url": driver.current_url,
        "start": start.isoformat(),
        "end": end.isoformat(),
        "verification_clicked": verification_clicked,
        "income_filename": income_filename,
        "income_path": str(income_path) if income_path is not None else None,
        "income_no_data": income_no_data,
        "balance_filename": balance_filename,
        "balance_path": str(balance_path) if balance_path is not None else None,
        "balance_no_data": balance_no_data,
        "order_filename": order_filename,
        "order_path": str(order_paths[0]) if order_paths else None,
        "order_paths": [str(path) for path in order_paths],
    }


def parse_date(value: str) -> dt.date:
    try:
        return dt.date.fromisoformat(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("Use YYYY-MM-DD dates.") from error


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--core", required=True)
    parser.add_argument("--shop-name")
    parser.add_argument(
        "--workflow",
        choices=(
            "income",
            "balance-export",
            "balance-download",
            "both",
            "both-resume",
            "orders",
        ),
        default="income",
    )
    parser.add_argument(
        "--official-warehouse-orders",
        action="store_true",
        help=(
            "After income and balance are terminal, export all orders for "
            "a workbook row explicitly marked 官方仓."
        ),
    )
    parser.add_argument("--start", type=parse_date)
    parser.add_argument("--end", type=parse_date)
    parser.add_argument("--timeout", type=int, default=600)
    args = parser.parse_args()
    if (args.start is None) != (args.end is None):
        parser.error("--start and --end must be supplied together.")
    if args.start is not None and not args.shop_name:
        parser.error("--shop-name is required for an export.")

    attach, chromedriver_path = load_shared_kernel()
    driver_holder = [attach(args.port, chromedriver_path(args.core))]
    try:
        driver = driver_holder[0]
        switch_to_shopee(driver)
        ensure_shopee_seller_home(driver)
        if args.start is not None:
            print(
                "SHOPEE_EXPORT_EXECUTION_STARTED: "
                f"workflow={args.workflow} range={args.start.isoformat()}.."
                f"{args.end.isoformat()}",
                flush=True,
            )
        if args.workflow in ("both", "both-resume"):
            if args.start is None:
                parser.error("Combined export requires --start and --end.")
            verification_clicked = (
                open_income(driver)
                if args.workflow == "both"
                else open_income_history(driver)
            )
            result = export_both(
                driver_holder,
                args.shop_name,
                args.start,
                args.end,
                args.timeout,
                lambda: attach(args.port, chromedriver_path(args.core)),
                submit_jobs=args.workflow == "both",
                include_orders=args.official_warehouse_orders,
            )
            result["verification_clicked"] = (
                result["verification_clicked"] or verification_clicked
            )
            if (
                args.official_warehouse_orders
                and not result.get("order_paths")
            ):
                order_result = export_all_orders(
                    driver_holder[0],
                    args.shop_name,
                    args.start,
                    args.end,
                    submit_if_missing=True,
                    timeout=args.timeout,
                )
                result.update(
                    {
                        "order_filename": order_result["order_filename"],
                        "order_path": order_result["order_path"],
                        "order_paths": order_result["order_paths"],
                    }
                )
        elif args.workflow == "orders":
            if args.start is None:
                parser.error("Order export requires --start and --end.")
            result = export_all_orders(
                driver,
                args.shop_name,
                args.start,
                args.end,
                submit_if_missing=True,
                timeout=args.timeout,
            )
        elif args.workflow in ("balance-export", "balance-download"):
            if args.start is None:
                parser.error("Balance export requires --start and --end.")
            verification_clicked = open_balance(driver)
            if args.workflow == "balance-export":
                result = export_balance(
                    driver,
                    args.shop_name,
                    args.start,
                    args.end,
                    args.timeout,
                )
            else:
                result = resume_balance_download(
                    driver,
                    args.shop_name,
                    args.start,
                    args.end,
                    args.timeout,
                )
            result["verification_clicked"] = (
                result["verification_clicked"] or verification_clicked
            )
        else:
            verification_clicked = open_income(driver)
            if args.start is None:
                open_custom_date_panel(driver)
                result = {
                    "status": "SHOPEE_INCOME_DATE_PICKER_READY",
                    "url": driver.current_url,
                    "verification_clicked": verification_clicked,
                    "date_panel": DATE_PANEL,
                }
            else:
                result = export_income(
                    driver,
                    args.shop_name,
                    args.start,
                    args.end,
                    args.timeout,
                )
                result["verification_clicked"] = (
                    result["verification_clicked"] or verification_clicked
                )
        print(json.dumps(result, ensure_ascii=False))
    finally:
        driver_holder[0].quit()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except InvalidShopeePasswordError:
        print("SHOPEE_INVALID_PASSWORD", flush=True)
        raise SystemExit(22)
    except MissingShopeePasswordError:
        print("SHOPEE_MISSING_PASSWORD", flush=True)
        raise SystemExit(23)
    except ShopeeSellerHomeNotReachedError as error:
        print(f"SHOPEE_SELLER_HOME_NOT_REACHED: {error}", flush=True)
        raise SystemExit(24)
    except ShopeePasswordVerificationNotCompletedError as error:
        print(f"SHOPEE_PASSWORD_VERIFICATION_NOT_COMPLETED: {error}", flush=True)
        raise SystemExit(25)
