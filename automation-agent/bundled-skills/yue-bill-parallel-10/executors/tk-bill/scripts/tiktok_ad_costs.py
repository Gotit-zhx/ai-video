"""TikTok Ads Manager cost-bill export workflow.

The functions in this module intentionally operate on an already attached
Feikua Selenium driver.  This keeps shop/account isolation owned by the
existing ``feikua_store`` connection kernel while allowing the advertising
workflow to be smoke-tested independently before it is added to the monthly
income workflow.
"""

from __future__ import annotations

import csv
import datetime as dt
import json
import os
import re
import shutil
import time
import urllib.parse
import zipfile
from collections.abc import Callable
from pathlib import Path

from selenium import webdriver
from selenium.common.exceptions import (
    ElementClickInterceptedException,
    ElementNotInteractableException,
    StaleElementReferenceException,
    TimeoutException,
    WebDriverException,
)
from selenium.webdriver.common.by import By
from selenium.webdriver.common.action_chains import ActionChains
from selenium.webdriver.support.ui import WebDriverWait
from urllib3.exceptions import ReadTimeoutError


ADS_MANAGER_HOST = "ads.tiktok.com"
ADS_COSTS_PANEL = '[data-testid="transaction-panel-KsTabItem-costs"]'
ADS_PAYMENTS_PANEL = '[data-testid="transaction-panel-KsTabItem-payments"]'
ADS_DATE_PICKER = '[data-testid^="panel-costs-TimeRange-"]'
ADS_EXPORT_BUTTON = '[data-testid="ttam-transaction-export-button"]'
ADS_TABLE = '[data-testid^="cost-content-Table-"]'
ACCOUNT_ID_RE = re.compile(r"\b(\d{15,20})\b")
WINDOWS_INVALID_RE = re.compile(r'[<>:"/\\|?*\x00-\x1f]')
DOWNLOAD_SUFFIXES = {".xlsx", ".csv", ".zip", ".xls"}


class AdsCostExportError(RuntimeError):
    """Raised when a hard Ads Manager workflow gate is not satisfied."""


class AdsCostVerificationRequired(AdsCostExportError):
    """Raised when the exact Feikua window needs human verification."""


class AdsCostLoginOtpRequired(AdsCostExportError):
    """Raised when Ads login requires an emailed or phoned one-time code."""


class AdsCostLoginCredentialsRejected(AdsCostExportError):
    """Raised when Ads Manager explicitly rejects the autofilled credentials."""


class AdsCostExportPendingError(AdsCostExportError):
    """Raised when Export may already have been clicked and cannot be repeated."""


class AdsCostStoreDisabled(AdsCostExportError):
    """Raised when Seller Center explicitly reports a disabled/invalid store."""


STORE_DISABLED_MARKERS = (
    "账号已停用",
    "帳號已停用",
    "店铺状态无效",
    "店鋪狀態無效",
    "account has been deactivated",
    "account is deactivated",
    "account disabled",
    "store status is invalid",
    "invalid store status",
)


def store_disabled_reason(driver: webdriver.Chrome) -> str | None:
    """Return the rendered terminal marker for a disabled TikTok store."""
    try:
        body = driver.find_element(By.TAG_NAME, "body")
        text = (body.text or "").casefold()
    except Exception:
        return None
    return next((marker for marker in STORE_DISABLED_MARKERS if marker in text), None)


def _raise_if_store_disabled(driver: webdriver.Chrome) -> None:
    marker = store_disabled_reason(driver)
    if marker:
        raise AdsCostStoreDisabled(
            f"TikTok Seller Center explicitly displayed disabled store state: {marker}"
        )


def emit_json(prefix: str, payload: dict) -> None:
    print(
        f"{prefix}: {json.dumps(payload, ensure_ascii=False, separators=(',', ':'))}",
        flush=True,
    )


def _click_rendered(driver: webdriver.Chrome, element) -> None:
    driver.execute_script("arguments[0].scrollIntoView({block: 'center'});", element)
    try:
        element.click()
    except (ElementClickInterceptedException, ElementNotInteractableException):
        driver.execute_script("arguments[0].click();", element)


def _visible_enabled(driver: webdriver.Chrome, selector: str):
    for element in driver.find_elements(By.CSS_SELECTOR, selector):
        try:
            if element.is_displayed() and element.is_enabled():
                return element
        except StaleElementReferenceException:
            continue
    return None


def _same_shape_challenge_visible(driver: webdriver.Chrome) -> bool:
    """Detect the TikTok Ads object-matching CAPTCHA before coordinate use."""
    try:
        body_text = driver.find_element(By.TAG_NAME, "body").text.casefold()
    except WebDriverException:
        return False
    return any(
        marker in body_text
        for marker in (
            "点击选中两个相同形状的物体",
            "选中两个相同形状",
            "select two objects with the same shape",
            "select two objects of the same shape",
            "select 2 objects that are the same shape",
        )
    )


def _click_same_shape_challenge_close(driver: webdriver.Chrome) -> bool:
    """Click only the visible challenge's own X, never the browser window."""
    # The SecSDK challenge can render in an isolated frame that normal DOM
    # selectors and WebDriver body text cannot traverse. Detect its prompt in
    # the accessibility tree and then bind the click to its exact close node.
    tree = {}
    try:
        tree = driver.execute_cdp_cmd("Accessibility.getFullAXTree", {})
        ax_names = [
            str((node.get("name") or {}).get("value") or "").strip().casefold()
            for node in tree.get("nodes", [])
        ]
        ax_challenge_visible = any(
            marker in name
            for name in ax_names
            for marker in (
                "点击选中两个相同形状的物体",
                "选中两个相同形状",
                "select two objects with the same shape",
                "select two objects of the same shape",
                "select 2 objects that are the same shape",
            )
        )
    except WebDriverException:
        ax_challenge_visible = False

    if not ax_challenge_visible and not _same_shape_challenge_visible(driver):
        return False

    try:
        for node in tree.get("nodes", []):
            name = str((node.get("name") or {}).get("value") or "").strip()
            role = str((node.get("role") or {}).get("value") or "").casefold()
            if name.casefold() not in {"关闭", "close"} or role not in {
                "button",
                "link",
            }:
                continue
            backend_id = node.get("backendDOMNodeId")
            if not backend_id:
                continue
            box = driver.execute_cdp_cmd(
                "DOM.getBoxModel", {"backendNodeId": backend_id}
            )
            quad = (box.get("model") or {}).get("border") or []
            if len(quad) != 8:
                continue
            x = sum(quad[0::2]) / 4
            y = sum(quad[1::2]) / 4
            for event_type in ("mousePressed", "mouseReleased"):
                params = {
                    "type": event_type,
                    "x": x,
                    "y": y,
                    "button": "left",
                    "clickCount": 1,
                }
                driver.execute_cdp_cmd("Input.dispatchMouseEvent", params)
            print("ADS_CAPTCHA_EXACT_CLOSE_CLICKED", flush=True)
            return True
    except WebDriverException:
        pass

    # Last resort for this exact, positively identified SecSDK layout. The X is
    # at the challenge card's top-right; ratios track the responsive viewport.
    try:
        viewport = driver.execute_script(
            "return {width: innerWidth, height: innerHeight};"
        )
        x = float(viewport["width"]) * 0.60
        y = float(viewport["height"]) * 0.366
        for event_type in ("mousePressed", "mouseReleased"):
            params = {
                "type": event_type,
                "x": x,
                "y": y,
                "button": "left",
                "clickCount": 1,
            }
            driver.execute_cdp_cmd("Input.dispatchMouseEvent", params)
        print("ADS_CAPTCHA_EXACT_COORDINATE_CLOSE_CLICKED", flush=True)
        return True
    except (KeyError, TypeError, ValueError, WebDriverException):
        return False


def _dismiss_slider_captcha(driver: webdriver.Chrome) -> bool:
    if _click_same_shape_challenge_close(driver):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if not _same_shape_challenge_visible(driver):
                print("ADS_CAPTCHA_CLOSED_AND_GONE", flush=True)
                return True
            time.sleep(0.25)
        print("ADS_CAPTCHA_CLOSE_NOT_CONFIRMED", flush=True)
        return False

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
              const slider = marker.matches(
                "iframe[src*='captcha' i],iframe[src*='challenge' i]," +
                "[class*='drag' i],[id*='drag' i],[class*='slider' i],[id*='slider' i]"
              ) || !!marker.querySelector(
                "[class*='drag' i],[id*='drag' i],[class*='slider' i],[id*='slider' i]"
              ) || hasPuzzlePrompt;
              if (!slider) continue;
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
                  "button[title*='close' i],[class*='close' i],[id*='close' i]," +
                  "[data-testid*='close' i],[data-icon*='close' i]"
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
                    const inTopRight =
                      centerX >= box.left + box.width * 0.68 &&
                      centerY <= box.top + Math.min(72, box.height * 0.24);
                    if (!inTopRight) return false;
                    const label = [
                      el.getAttribute('aria-label') || '',
                      el.getAttribute('title') || '',
                      el.textContent || ''
                    ].join(' ').trim().toLowerCase();
                    const explicitX = /(^|\\s)(x|×|✕)(\\s|$)/.test(label);
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
                        closeButton;
                  try {
                    target.focus?.();
                    for (const type of [
                      'pointerdown', 'mousedown', 'pointerup', 'mouseup'
                    ]) {
                      target.dispatchEvent(new MouseEvent(
                        type,
                        {bubbles: true, cancelable: true, view: window}
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
    # Some SecSDK builds ignore synthetic DOM click events even though the
    # exact close anchor is visible. Fall back to a genuine WebDriver pointer
    # click on that exact CAPTCHA control only.
    try:
        if _security_overlay_visible_raw(driver):
            close = _visible_enabled(
                driver,
                "#verify-bar-close,a.verify-bar-close,"
                "a[class*='verify-bar-close' i]",
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
        if not _security_overlay_visible_raw(driver):
            print("SLIDER_CAPTCHA_CLOSED_AND_GONE", flush=True)
            return True
        time.sleep(0.25)
    print("SLIDER_CAPTCHA_CLOSE_NOT_CONFIRMED", flush=True)
    return False


def _security_overlay_visible_raw(driver: webdriver.Chrome) -> bool:
    return bool(
        driver.execute_script(
            """
            return Array.from(document.querySelectorAll(
              '#captcha_container,[id*="captcha" i],[class*="captcha" i],' +
              '[id*="challenge" i],[class*="challenge" i],' +
              'iframe[src*="captcha" i],iframe[src*="challenge" i]'
            )).some((element) => {
              if (element === document.body ||
                  element === document.documentElement) return false;
              const style = getComputedStyle(element);
              const rect = element.getBoundingClientRect();
              const shown = style.display !== 'none' &&
                style.visibility !== 'hidden' &&
                Number(style.opacity || 1) > 0 &&
                rect.width > 0 && rect.height > 0;
              if (!shown) return false;
              const actualCaptcha = element.matches(
                '#captcha_container,.captcha_verify_container,' +
                '[class*="CaptchaWrapper" i],' +
                'iframe[src*="captcha" i],iframe[src*="challenge" i]'
              );
              const fixedOverlay = style.position === 'fixed' &&
                rect.width >= innerWidth * 0.8 &&
                rect.height >= innerHeight * 0.8;
              return fixedOverlay || (
                actualCaptcha && rect.width >= 200 && rect.height >= 150
              );
            });
            """
        )
    )


def _security_overlay_visible(driver: webdriver.Chrome) -> bool:
    _dismiss_slider_captcha(driver)
    return _security_overlay_visible_raw(driver)


def _verification_required(driver: webdriver.Chrome) -> bool:
    if _security_overlay_visible(driver):
        return True
    try:
        path = urllib.parse.urlparse(str(driver.current_url or "")).path
    except Exception:
        return False
    return bool(re.search(r"/(?:login|verify|captcha|challenge|security)(?:/|$)", path, re.I))


def _wait_document_ready(driver: webdriver.Chrome, timeout: int = 60) -> None:
    def ready(current: webdriver.Chrome) -> bool:
        try:
            state = current.execute_script(
                """
                const readyState = document.readyState || '';
                const bodyText = (document.body?.innerText || '').trim();
                const renderedShell = Boolean(document.querySelector(
                    'nav, aside, [role="navigation"], '
                    + '[data-tid="marketing"], [data-tid="4"], '
                    + '[data-tid="ads_creation"]'
                ));
                return {
                    nativeReady:
                        readyState === 'interactive' || readyState === 'complete',
                    renderedReady: bodyText.length > 0 && renderedShell
                };
                """
            )
            return bool(
                state
                and (state.get("nativeReady") or state.get("renderedReady"))
            )
        except TimeoutException:
            return False
        except WebDriverException as error:
            lowered = str(error).casefold()
            if (
                "script timeout" not in lowered
                and "timed out receiving message from renderer" not in lowered
            ):
                raise
            return False

    WebDriverWait(driver, timeout, poll_frequency=0.5).until(ready)


def _is_ads_manager(url: str) -> bool:
    parsed = urllib.parse.urlparse(url)
    return (
        parsed.scheme in {"http", "https"}
        and (parsed.hostname or "").casefold() == ADS_MANAGER_HOST
    )


def _ads_manager_login_page(driver: webdriver.Chrome) -> bool:
    """Return whether the Ads Manager target is an authentication page."""
    try:
        parsed = urllib.parse.urlparse(str(driver.current_url or ""))
    except (TimeoutException, WebDriverException) as error:
        if _is_transient_renderer_timeout(error):
            return False
        raise
    path = parsed.path.casefold()
    return path.startswith("/i18n/login") or path.startswith("/login")


def _ads_login_otp_visible(driver: webdriver.Chrome) -> bool:
    """Return whether Ads Manager is asking for a one-time verification code."""
    try:
        text = driver.find_element(By.TAG_NAME, "body").text.casefold()
    except Exception:
        return False
    markers = (
        "双重验证",
        "输入验证码",
        "verification code",
        "two-step verification",
        "two-factor authentication",
        "2-step verification",
        "รหัสยืนยัน",
    )
    return any(marker in text for marker in markers)


def _install_ads_login_response_probe(driver: webdriver.Chrome) -> None:
    """Observe only login response metadata; never capture submitted secrets."""
    driver.execute_script(
        """
        if (window.__codexAdsLoginProbeInstalled) return;
        window.__codexAdsLoginProbeInstalled = true;
        window.__codexAdsLoginResponses = [];
        const record = (url, status, body) => {
          if (!/account_login|login\\/v\\d|passport.*login/i.test(
              String(url || ''))) return;
          window.__codexAdsLoginResponses.push({
            status: Number(status || 0),
            body: String(body || '').slice(0, 6000)
          });
        };
        const nativeOpen = XMLHttpRequest.prototype.open;
        const nativeSend = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.open = function(method, url) {
          this.__codexLoginUrl = url;
          return nativeOpen.apply(this, arguments);
        };
        XMLHttpRequest.prototype.send = function() {
          this.addEventListener('load', () => {
            record(
              this.__codexLoginUrl || this.responseURL,
              this.status,
              this.responseText
            );
          }, {once: true});
          return nativeSend.apply(this, arguments);
        };
        const nativeFetch = window.fetch;
        window.fetch = async function() {
          const response = await nativeFetch.apply(this, arguments);
          try {
            const request = arguments[0];
            const url = typeof request === 'string' ? request : request?.url;
            const clone = response.clone();
            record(url, response.status, await clone.text());
          } catch (_) {}
          return response;
        };
        """
    )


def _ads_login_response_probe_state(driver: webdriver.Chrome) -> str | None:
    """Classify a captured login response without returning its raw payload."""
    try:
        responses = driver.execute_script(
            "return window.__codexAdsLoginResponses || [];"
        )
    except WebDriverException:
        return None
    for response in reversed(responses or []):
        text = str(response.get("body") or "").casefold()
        try:
            payload = json.loads(str(response.get("body") or "{}"))
        except (TypeError, ValueError, json.JSONDecodeError):
            payload = {}
        if (
            payload.get("error_code") == 1107
            and payload.get("verify_center_decision_conf")
        ):
            return "verification"
        if any(
            marker in text
            for marker in (
                "incorrect password",
                "wrong password",
                "invalid password",
                "password is incorrect",
                "password error",
                "密码错误",
                "账号或密码错误",
            )
        ):
            return "credentials_rejected"
        if any(
            marker in text
            for marker in (
                "verification code",
                "two-step verification",
                "two-factor authentication",
                "请输入验证码",
                "双重验证",
            )
        ):
            return "otp"
    return None


def _attempt_autofilled_ads_manager_login(
    driver: webdriver.Chrome,
    timeout: int = 120,
) -> str:
    """Submit Feikua-autofilled Ads Manager credentials without reading them.

    Return ``authenticated``, ``otp``, ``verification``, ``missing``, or
    ``failed``.
    The login form is submitted at most once per call.
    """
    if not _ads_manager_login_page(driver):
        return "authenticated"

    def visible(selector: str):
        resolved = []
        for element in driver.find_elements(By.CSS_SELECTOR, selector):
            try:
                if element.is_displayed():
                    resolved.append(element)
            except StaleElementReferenceException:
                continue
        return resolved

    # Ads Manager can initially show a login-method chooser. Select only an
    # email/password method; never click QR, phone, or unrelated account flows.
    password_inputs = visible('input[type="password"]')
    if not password_inputs:
        email_tokens = ("email", "e-mail", "邮箱", "อีเมล", "อีเมล์")
        for control in visible("button, [role='button'], a"):
            label = " ".join(
                (
                    control.text or "",
                    control.get_attribute("aria-label") or "",
                    control.get_attribute("title") or "",
                )
            ).strip().casefold()
            if any(token in label for token in email_tokens):
                _click_rendered(driver, control)
                print("TIKTOK_AD_COST_EMAIL_LOGIN_SELECTED", flush=True)
                break
        try:
            password_inputs = WebDriverWait(
                driver, 10, poll_frequency=0.25
            ).until(
                lambda current: visible('input[type="password"]') or None
            )
        except TimeoutException:
            print(
                "TIKTOK_AD_COST_AUTOFILLED_LOGIN_FORM_MISSING",
                flush=True,
            )
            return "missing"

    identity_inputs = visible(
        'input[type="email"], input[name="email"], '
        'input[name="username"], input[autocomplete="username"], '
        'input[type="text"]'
    )
    identity = next(
        (
            element
            for element in identity_inputs
            if (element.get_attribute("value") or "").strip()
        ),
        None,
    )
    password = next(
        (
            element
            for element in password_inputs
            if (element.get_attribute("value") or "").strip()
        ),
        None,
    )
    if identity is None or password is None:
        print(
            "TIKTOK_AD_COST_AUTOFILLED_CREDENTIALS_MISSING",
            flush=True,
        )
        return "missing"

    if _security_overlay_visible(driver):
        print(
            "TIKTOK_AD_COST_LOGIN_VERIFICATION_REQUIRED",
            flush=True,
        )
        return "verification"

    submit = None
    try:
        form = password.find_element(By.XPATH, "./ancestor::form[1]")
        submit = next(
            (
                button
                for button in form.find_elements(
                    By.CSS_SELECTOR,
                    'button[type="submit"], input[type="submit"]',
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
                    'button[type="submit"], input[type="submit"]'
                )
                if button.is_enabled()
            ),
            None,
        )
    if submit is None:
        # The current TikTok for Business email/password form renders its
        # primary "Log in" control as type="button", while the unrelated
        # "Log in with TikTok" control is type="submit".  Credentials have
        # already been confirmed as autofilled above, so target only the
        # email/password form's explicit primary-login control.
        submit = next(
            (
                button
                for button in visible(
                    "button.login-btn, button.login-form__submit, "
                    "button[data-e2e='login-button']"
                )
                if button.is_enabled()
                and (button.text or "").strip().casefold()
                in {"log in", "login", "登录", "登入"}
            ),
            None,
        )
    if submit is None:
        print(
            "TIKTOK_AD_COST_LOGIN_SUBMIT_MISSING",
            flush=True,
        )
        return "missing"

    _install_ads_login_response_probe(driver)
    _click_rendered(driver, submit)
    print("TIKTOK_AD_COST_AUTOFILLED_LOGIN_SUBMITTED", flush=True)
    deadline = time.monotonic() + timeout
    captcha_close_count = 0
    pending_resubmit_after_captcha = False
    invalid_markers = (
        "incorrect password",
        "wrong password",
        "invalid password",
        "password is incorrect",
        "密码错误",
        "รหัสผ่านไม่ถูกต้อง",
    )
    while time.monotonic() < deadline:
        if _ads_login_otp_visible(driver):
            print("TIKTOK_AD_COST_LOGIN_OTP_REQUIRED", flush=True)
            return "otp"
        probed_state = _ads_login_response_probe_state(driver)
        if probed_state == "otp":
            print("TIKTOK_AD_COST_LOGIN_OTP_REQUIRED", flush=True)
            return "otp"
        if probed_state == "credentials_rejected":
            print(
                "TIKTOK_AD_COST_LOGIN_PASSWORD_REJECTED",
                flush=True,
            )
            return "credentials_rejected"
        if (
            probed_state == "verification"
            and captcha_close_count >= 1
        ):
            print(
                "TIKTOK_AD_COST_LOGIN_VERIFICATION_REQUIRED",
                flush=True,
            )
            # The exact CAPTCHA X has already been closed once and the
            # resubmitted login immediately received the same server-side
            # verification decision. Treat that repeated decision as the
            # server's repeated verification decision as the user's terminal
            # "广告登录需要验证码" outcome instead of preserving/retrying.
            return "otp"
        if not _ads_manager_login_page(driver):
            print(
                "TIKTOK_AD_COST_AUTOFILLED_LOGIN_CONFIRMED",
                flush=True,
            )
            return "authenticated"
        if _dismiss_slider_captcha(driver):
            captcha_close_count += 1
            pending_resubmit_after_captcha = captcha_close_count < 3
            deadline = max(deadline, time.monotonic() + 90)
            print(
                "TIKTOK_AD_COST_LOGIN_SLIDER_CLOSED",
                flush=True,
            )
            time.sleep(1)
            continue
        if _security_overlay_visible_raw(driver):
            return "verification"
        try:
            body_text = driver.find_element(
                By.TAG_NAME, "body"
            ).text.casefold()
            if any(marker in body_text for marker in invalid_markers):
                print(
                    "TIKTOK_AD_COST_LOGIN_PASSWORD_REJECTED",
                    flush=True,
                )
                return "credentials_rejected"
        except Exception:
            pass
        if pending_resubmit_after_captcha:
            try:
                retry_submit = next(
                    (
                        button
                        for button in visible(
                            "button.login-btn, "
                            "button.login-form__submit, "
                            "button[data-e2e='login-button']"
                        )
                        if button.is_enabled()
                        and "loading" not in (
                            button.get_attribute("class") or ""
                        ).casefold()
                        and (button.text or "").strip().casefold()
                        in {"log in", "login", "鐧诲綍", "鐧诲叆", "登录"}
                    ),
                    None,
                )
                if retry_submit is not None:
                    _click_rendered(driver, retry_submit)
                    pending_resubmit_after_captcha = False
                    print(
                        "TIKTOK_AD_COST_LOGIN_RESUBMITTED_AFTER_CAPTCHA_CLOSE: "
                        f"attempt={captcha_close_count + 1}/3",
                        flush=True,
                    )
            except (
                ElementClickInterceptedException,
                ElementNotInteractableException,
                StaleElementReferenceException,
                WebDriverException,
            ):
                pass
        time.sleep(0.25)
    print("TIKTOK_AD_COST_AUTOFILLED_LOGIN_NOT_CONFIRMED", flush=True)
    return "failed"


def _ads_manager_account_id(*urls: str) -> str | None:
    """Extract the stable advertising account ID from official Ads URLs."""
    for url in urls:
        try:
            query = urllib.parse.parse_qs(
                urllib.parse.urlparse(url).query
            )
        except (TypeError, ValueError):
            continue
        for key in ("aadvid", "advertiser_id", "advertiserId"):
            for value in query.get(key, []):
                candidate = str(value).strip()
                if candidate.isdigit():
                    return candidate
    return None


def _is_transient_renderer_timeout(error: BaseException) -> bool:
    if isinstance(error, TimeoutException):
        return True
    if isinstance(error, StaleElementReferenceException):
        return True
    if isinstance(error, ReadTimeoutError):
        lowered = str(error).casefold()
        return (
            "localhost" in lowered
            or "127.0.0.1" in lowered
            or "read timed out" in lowered
        )
    if not isinstance(error, WebDriverException):
        return False
    lowered = str(error).casefold()
    return (
        "script timeout" in lowered
        or "timed out receiving message from renderer" in lowered
        or "node with given id does not belong to the document" in lowered
        or "cannot find context with specified id" in lowered
    )


def _official_ads_manager_link(driver: webdriver.Chrome):
    """Resolve the rendered official entry without localized text/classes."""
    try:
        anchors = driver.find_elements(
            By.CSS_SELECTOR,
            'a[href*="ads.tiktok.com"], a.ttam-link-T-im',
        )
    except (TimeoutException, WebDriverException, ReadTimeoutError) as error:
        if _is_transient_renderer_timeout(error):
            # Returning no match lets WebDriverWait poll the same DOM again.
            # Do not repeat the Shop ads click after a transient renderer stall.
            return None
        raise
    for anchor in anchors:
        try:
            href = (anchor.get_attribute("href") or "").strip()
            if (
                anchor.is_displayed()
                and anchor.is_enabled()
                and _is_ads_manager(href)
            ):
                return anchor
        except StaleElementReferenceException:
            continue
        except (
            TimeoutException,
            WebDriverException,
            ReadTimeoutError,
        ) as error:
            if _is_transient_renderer_timeout(error):
                return None
            raise
    return None


def _shop_ads_render_started(driver: webdriver.Chrome) -> bool:
    """Return whether the right-side Shop Ads content has begun rendering."""
    try:
        if not urllib.parse.urlparse(
            str(driver.current_url or "")
        ).path.startswith("/ads-creation"):
            return False
        return bool(
            driver.execute_script(
                """
                const shown = (element) => {
                  const style = getComputedStyle(element);
                  const rect = element.getBoundingClientRect();
                  return style.display !== 'none' &&
                    style.visibility !== 'hidden' &&
                    Number(style.opacity || 1) > 0 &&
                    rect.width > 4 && rect.height > 4;
                };
                const mainSelectors = [
                  'main', '[role="main"]', '#content', '#main-content',
                  '[class*="main-content" i]', '[class*="page-content" i]',
                  '[class*="content-wrapper" i]', '[class*="ads-creation" i]'
                ];
                if (!document.body) return false;
                let roots = mainSelectors.flatMap((selector) =>
                  Array.from(document.querySelectorAll(selector))
                ).filter(shown);
                if (!roots.length) roots = [document.body];
                const meaningfulSelector = [
                  'iframe[src]', 'img[src]', 'canvas', 'video', 'table',
                  'button', 'a[href]', '[data-testid]',
                  '[class*="skeleton" i]', '[class*="loading" i]',
                  '[class*="spinner" i]', '[role="progressbar"]'
                ].join(',');
                return roots.some((root) => {
                  if (!root) return false;
                  const nodes = Array.from(
                    root.querySelectorAll(meaningfulSelector)
                  );
                  const textNodes = Array.from(root.querySelectorAll(
                    'h1,h2,h3,h4,p,section,article,[role="alert"],' +
                    '[class*="card" i],[class*="panel" i]'
                  ));
                  return nodes.concat(textNodes).some((element) => {
                    if (!shown(element)) return false;
                    const rect = element.getBoundingClientRect();
                    const style = getComputedStyle(element);
                    if (style.position === 'fixed') return false;
                    // Exclude the persistent left navigation and black header.
                    if (rect.right <= 220 || rect.bottom <= 80) return false;
                    if (element.closest(
                      'aside,nav,[class*="sidebar" i],[class*="header" i]'
                    )) return false;
                    if (element.matches(meaningfulSelector)) return true;
                    return (element.innerText || '').trim().length >= 2;
                  });
                });
                """
            )
        )
    except (TimeoutException, WebDriverException, ReadTimeoutError) as error:
        if _is_transient_renderer_timeout(error):
            return False
        raise


def _current_url_is_ads_manager(driver: webdriver.Chrome) -> bool:
    try:
        return _is_ads_manager(str(driver.current_url or ""))
    except (TimeoutException, WebDriverException, ReadTimeoutError) as error:
        if _is_transient_renderer_timeout(error):
            return False
        raise


def _new_window_handles(
    driver: webdriver.Chrome,
    prior_handles: set[str],
) -> list[str]:
    try:
        return [
            handle
            for handle in driver.window_handles
            if handle not in prior_handles
        ]
    except (TimeoutException, WebDriverException, ReadTimeoutError) as error:
        if _is_transient_renderer_timeout(error):
            return []
        raise


def _settle_official_ads_manager_navigation(
    driver: webdriver.Chrome,
    prior_handles: set[str],
    official_href: str,
    *,
    initial_timeout: float = 15,
    new_window_timeout: float = 90,
) -> None:
    """Finish one official-link navigation without issuing another click."""
    try:
        WebDriverWait(
            driver,
            initial_timeout,
            poll_frequency=0.2,
        ).until(
            lambda current: (
                _current_url_is_ads_manager(current)
                or bool(_new_window_handles(current, prior_handles))
            )
        )
    except TimeoutException:
        # The official anchor was already validated before its single click.
        # Reuse only that href; never click Shop ads or the official link again.
        _navigate(driver, official_href)
    else:
        new_handles = _new_window_handles(driver, prior_handles)
        if new_handles:
            driver.switch_to.window(new_handles[-1])
            try:
                WebDriverWait(
                    driver,
                    new_window_timeout,
                    poll_frequency=0.2,
                ).until(_current_url_is_ads_manager)
            except TimeoutException:
                # TikTok can create the target tab as about:blank before its
                # redirect is committed. Navigate that same tab to the already
                # validated official href instead of repeating either click.
                _navigate(driver, official_href)
        elif not _current_url_is_ads_manager(driver):
            _navigate(driver, official_href)

    try:
        WebDriverWait(driver, 30, poll_frequency=0.2).until(
            _current_url_is_ads_manager
        )
    except TimeoutException as error:
        raise AdsCostExportError("TikTok Ads Manager was not reached.") from error
    _wait_document_ready(driver, 90)


def _navigate(driver: webdriver.Chrome, url: str, timeout: int = 90) -> None:
    driver.set_page_load_timeout(timeout)
    try:
        driver.get(url)
    except (TimeoutException, WebDriverException, ReadTimeoutError) as error:
        if not _is_transient_renderer_timeout(error):
            raise
        current_url = ""
        current_url_deadline = time.monotonic() + 30
        while time.monotonic() < current_url_deadline:
            try:
                current_url = str(driver.current_url or "")
            except (TimeoutException, WebDriverException, ReadTimeoutError) as probe_error:
                if not _is_transient_renderer_timeout(probe_error):
                    raise
                time.sleep(0.5)
                continue
            if current_url.startswith(("http://", "https://")):
                break
            time.sleep(0.5)
        if not current_url.startswith(("http://", "https://")):
            raise
        print(
            "TIKTOK_AD_COST_NAVIGATION_RESPONSE_TIMEOUT: navigation was "
            "dispatched; continuing explicit rendered-state checks without "
            "window.stop, refresh, or another click",
            flush=True,
        )
    try:
        _wait_document_ready(driver, timeout)
    except TimeoutException:
        current_url = str(driver.current_url or "")
        if not current_url.startswith(("http://", "https://")):
            raise
        print(
            "TIKTOK_AD_COST_NAVIGATION_TARGET_ALIVE: "
            "document readiness timed out; continuing explicit rendered-state "
            "checks without refresh",
            flush=True,
        )


def open_ads_costs_from_seller_center(
    driver: webdriver.Chrome,
    seller_center_url: str,
    fallback_account_id: str | None = None,
) -> None:
    """Follow Seller Center's official Shop ads link into Ads Manager Costs."""
    if not seller_center_url.startswith(("http://", "https://")):
        raise AdsCostExportError("A validated Seller Center URL is required.")
    if not urllib.parse.urlparse(seller_center_url).hostname:
        raise AdsCostExportError("The Seller Center URL has no host.")

    seller = urllib.parse.urlparse(seller_center_url)
    original_handle = driver.current_window_handle
    homepage_handle: str | None = None
    seller_handle: str | None = None
    shop_ads_handle: str | None = None
    shop_ads_handles: list[str] = []
    prior_ads_manager_handles: list[str] = []
    for handle in list(driver.window_handles):
        try:
            driver.switch_to.window(handle)
            current_url = str(driver.current_url or "")
            parsed = urllib.parse.urlparse(current_url)
        except Exception:
            continue
        if _is_ads_manager(current_url):
            prior_ads_manager_handles.append(handle)
        if parsed.hostname != seller.hostname:
            continue
        seller_handle = seller_handle or handle
        # `/ads-creation/creation` is the homepage "Create campaign" flow,
        # not the Shop ads dashboard entry used for billing.  Do not preserve
        # it as a successful Shop ads page.
        if (
            parsed.path.startswith("/ads-creation")
            and not parsed.path.startswith("/ads-creation/creation")
        ):
            shop_ads_handle = handle
            shop_ads_handles.append(handle)
        if parsed.path.rstrip("/") == "/homepage":
            homepage_handle = handle

    # A recovery wave must not keep opening a fresh Ads Manager tab on top of
    # every prior attempt.  Those duplicate tabs can make Selenium settle on a
    # stale document and also waste CPU.  Close only Ads Manager tabs inside
    # this exact isolated Feikua profile; preserve Seller Center and the Feikua
    # detection page, then open one clean official target below.
    if prior_ads_manager_handles:
        closed_ads_tabs = 0
        for handle in prior_ads_manager_handles:
            try:
                if handle not in driver.window_handles:
                    continue
                driver.switch_to.window(handle)
                driver.close()
                closed_ads_tabs += 1
            except Exception:
                continue
        print(
            "TIKTOK_AD_COST_DUPLICATE_ADS_TABS_CLOSED: "
            f"count={closed_ads_tabs}",
            flush=True,
        )

    # Preserve one rendered Shop ads tab and remove older duplicates created
    # by earlier recovery attempts.  This never closes the Feikua store window.
    if len(shop_ads_handles) > 1 and shop_ads_handle is not None:
        closed_shop_ads_tabs = 0
        for handle in shop_ads_handles:
            if handle == shop_ads_handle:
                continue
            try:
                if handle not in driver.window_handles:
                    continue
                driver.switch_to.window(handle)
                driver.close()
                closed_shop_ads_tabs += 1
            except Exception:
                continue
        print(
            "TIKTOK_AD_COST_DUPLICATE_SHOP_ADS_TABS_CLOSED: "
            f"count={closed_shop_ads_tabs}",
            flush=True,
        )
        seller_handle = shop_ads_handle
        if homepage_handle not in driver.window_handles:
            homepage_handle = None
    if original_handle not in driver.window_handles:
        original_handle = (
            shop_ads_handle
            or homepage_handle
            or seller_handle
            or driver.window_handles[0]
        )

    shop_ads_already_open = shop_ads_handle is not None
    if shop_ads_already_open:
        driver.switch_to.window(shop_ads_handle)
        print(
            "TIKTOK_AD_COST_EXISTING_SHOP_ADS_PAGE_REUSED: "
            "preserving current rendered content",
            flush=True,
        )
    elif homepage_handle is not None:
        driver.switch_to.window(homepage_handle)
    elif seller_handle is not None:
        driver.switch_to.window(seller_handle)
        home_anchor = _visible_enabled(
            driver, 'a.sidebar-item-link[href="/homepage"]'
        )
        if home_anchor is None:
            _navigate(driver, seller_center_url)
        else:
            home_href = (home_anchor.get_attribute("href") or "").strip()
            home_control = next(
                (
                    item
                    for item in home_anchor.find_elements(
                        By.CSS_SELECTOR, '[role="menuitem"]'
                    )
                    if item.is_displayed() and item.is_enabled()
                ),
                None,
            )
            _click_rendered(driver, home_control or home_anchor)
            try:
                WebDriverWait(driver, 30).until(
                    lambda current: (
                        urllib.parse.urlparse(current.current_url)
                        .path.rstrip("/")
                        == "/homepage"
                    )
                )
            except TimeoutException:
                if not home_href:
                    raise
                # Fallback only to the exact official Homepage href rendered
                # by this Seller Center session.
                _navigate(driver, home_href)
    else:
        driver.switch_to.window(original_handle)
        _navigate(driver, seller_center_url)
    _wait_document_ready(driver, 60)
    _raise_if_store_disabled(driver)

    wait = WebDriverWait(driver, 90, poll_frequency=0.25)

    def marketing_header(current):
        _raise_if_store_disabled(current)
        # `data-tid="marketing"` is shared by localized Seller Center shells.
        # Do not key the workflow to visible text such as "Marketing": the
        # cross-border multi-site shell can render the same menu as "广告营销".
        for group in current.find_elements(
            By.CSS_SELECTOR, '[data-tid="marketing"], [data-tid="4"]'
        ):
            try:
                if not group.is_displayed():
                    continue
                group_classes = {
                    item.strip()
                    for item in (group.get_attribute("class") or "").split()
                    if item.strip()
                }
                if "p-menu-item-header" in group_classes and group.is_enabled():
                    return group
                for header in group.find_elements(
                    By.CSS_SELECTOR, ".p-menu-item-header"
                ):
                    if header.is_displayed() and header.is_enabled():
                        return header
            except StaleElementReferenceException:
                continue
        return None

    def marketing_expanded(current):
        header = marketing_header(current)
        if header is None:
            return False
        try:
            return (
                header.get_attribute("aria-expanded") == "true"
                or _visible_enabled(
                    current, '[data-tid="ads_creation"]'
                )
                is not None
            )
        except StaleElementReferenceException:
            return False

    if not shop_ads_already_open:
        if _security_overlay_visible(driver):
            raise AdsCostExportError(
                "Seller Center requires human CAPTCHA verification before Marketing."
            )
        marketing = wait.until(marketing_header)
        # Sidebar DOM appears before its Vue click listeners finish mounting.
        # Re-resolve after a brief stable window rather than firing at the first
        # matching node and losing the only intended click.
        time.sleep(2)
        marketing = wait.until(marketing_header)
        if marketing.get_attribute("aria-expanded") != "true":
            clicked = driver.execute_script(
                """
                const title = arguments[0].querySelector('.p-menu-item-title');
                if (!title) return false;
                title.click();
                return true;
                """,
                marketing,
            )
            if not clicked:
                raise AdsCostExportError(
                    "The Seller Center Marketing menu click target was unavailable."
                )
            wait.until(marketing_expanded)
        print("TIKTOK_AD_COST_MARKETING_EXPANDED", flush=True)

    def seller_entry_state(current):
        _raise_if_store_disabled(current)
        try:
            if _security_overlay_visible(current):
                return "verification"
        except ReadTimeoutError as error:
            if _is_transient_renderer_timeout(error):
                return None
            raise
        # The Seller Center anchor has a Vue router handler that can cancel the
        # anchor's default click.  Its rendered menuitem is the actual control
        # users click and reliably dispatches the Shop ads navigation.
        try:
            return _visible_enabled(current, '[data-tid="ads_creation"]')
        except ReadTimeoutError as error:
            if _is_transient_renderer_timeout(error):
                return None
            raise

    if not shop_ads_already_open:
        entry_state = wait.until(seller_entry_state)
        if entry_state == "verification":
            raise AdsCostExportError(
                "Seller Center requires human CAPTCHA verification before Shop ads."
            )
        shop_ads = entry_state
        try:
            _click_rendered(driver, shop_ads)
        except ReadTimeoutError as error:
            if not _is_transient_renderer_timeout(error):
                raise
            # The local ChromeDriver response can time out after dispatching the
            # idempotent menu click. Observe the route before deciding to click
            # again; never apply this assumption to an Export action.
            print(
                "TIKTOK_AD_COST_SHOP_ADS_CLICK_RESPONSE_TIMEOUT: "
                "continuing rendered-route checks",
                flush=True,
            )
        # Multi-site Seller Center may first route through
        # `/ads-creation/welcome?mpa=1` and asynchronously replace it with the
        # dashboard. The loop below owns the complete 30-second post-click
        # observation window, so do not add a second route wait here.
        print("TIKTOK_AD_COST_SHOP_ADS_OPENED", flush=True)

    def official_link_or_disabled(current):
        _raise_if_store_disabled(current)
        return _official_ads_manager_link(current)

    def official_link_or_render_started(current):
        official = official_link_or_disabled(current)
        if official is not None:
            return ("official", official)
        if _shop_ads_render_started(current):
            return ("render_started", None)
        return None

    official_link = None
    # Seller Center occasionally renders only the authenticated shell after
    # the first Shop ads click. Re-click the exact same menu entry at a slow
    # interval until its official Ads Manager link is actually rendered.
    # There is intentionally no click-count deadline while the exact CDP
    # target remains alive; every iteration emits progress for the coordinator
    # watchdog. Never refresh or close a healthy session for blank SPA content.
    click_number = 1
    render_started = (
        shop_ads_already_open and _shop_ads_render_started(driver)
    )
    if render_started:
        print(
            "TIKTOK_AD_COST_SHOP_ADS_RENDER_STARTED: "
            "existing content detected; no menu click will be issued",
            flush=True,
        )
    render_started_at = time.monotonic() if render_started else None
    opened_via_checkpoint = False
    while official_link is None:
        if render_started:
            try:
                official_link = WebDriverWait(
                    driver, 30, poll_frequency=0.5
                ).until(official_link_or_disabled)
            except TimeoutException:
                print(
                    "TIKTOK_AD_COST_SHOP_ADS_RENDER_IN_PROGRESS: "
                    "waiting without another menu click",
                    flush=True,
                )
                if (
                    fallback_account_id
                    and render_started_at is not None
                    and time.monotonic() - render_started_at >= 180
                ):
                    # A prior audited checkpoint proves this exact advertising
                    # account was already discovered for this shop/date run.
                    # After three minutes of visible Seller Center rendering,
                    # use that official account ID to bypass only the stuck
                    # welcome shell. This does not refresh or re-click Shop ads.
                    fallback_href = (
                        "https://ads.tiktok.com/i18n/manage/campaign"
                        f"?aadvid={urllib.parse.quote(fallback_account_id)}"
                    )
                    prior_handles = set(driver.window_handles)
                    driver.switch_to.new_window("tab")
                    _navigate(driver, fallback_href)
                    _settle_official_ads_manager_navigation(
                        driver,
                        prior_handles,
                        fallback_href,
                    )
                    opened_via_checkpoint = True
                    print(
                        "TIKTOK_AD_COST_CHECKPOINT_ACCOUNT_ROUTE_USED: "
                        f"account_id={fallback_account_id}",
                        flush=True,
                    )
                    break
            continue
        try:
            observed = WebDriverWait(
                driver, 30, poll_frequency=0.5
            ).until(official_link_or_render_started)
        except TimeoutException:
            entry_state = seller_entry_state(driver)
            if entry_state == "verification":
                raise AdsCostExportError(
                    "Seller Center requires human CAPTCHA verification "
                    "before retrying Shop ads."
                )
            if not entry_state:
                print(
                    "TIKTOK_AD_COST_SHOP_ADS_CONTROL_STILL_LOADING: "
                    f"attempt={click_number}; waiting",
                    flush=True,
                )
                time.sleep(30)
                continue
            click_number += 1
            try:
                _click_rendered(driver, entry_state)
            except ReadTimeoutError as error:
                if not _is_transient_renderer_timeout(error):
                    raise
                print(
                    "TIKTOK_AD_COST_SHOP_ADS_RECLICK_RESPONSE_TIMEOUT: "
                    f"attempt={click_number}; continuing",
                    flush=True,
                )
            print(
                "TIKTOK_AD_COST_SHOP_ADS_RECLICKED: "
                f"attempt={click_number}; next_check_in=30s",
                flush=True,
            )
            continue
        if observed[0] == "official":
            official_link = observed[1]
            continue
        render_started = True
        render_started_at = time.monotonic()
        print(
            "TIKTOK_AD_COST_SHOP_ADS_RENDER_STARTED: "
            "stopping menu clicks and waiting for the official Ads Manager "
            "entry",
            flush=True,
        )
    if not opened_via_checkpoint:
        official_href = (official_link.get_attribute("href") or "").strip()
        if not _is_ads_manager(official_href):
            raise AdsCostExportError(
                "The official Seller Center TikTok Ads Manager link was not available."
            )
        prior_handles = set(driver.window_handles)
        _click_rendered(driver, official_link)
        _settle_official_ads_manager_navigation(
            driver,
            prior_handles,
            official_href,
        )
    print("TIKTOK_AD_COST_ADS_MANAGER_OPENED", flush=True)
    seller_recovery_handle = homepage_handle or seller_handle or original_handle

    # An Ads Manager target can be alive while actually showing its login
    # page. That is not slow Billing loading. Submit Feikua-autofilled
    # credentials first, then re-enter through the exact Seller Center
    # official link twice for bounded SSO recovery. Never print credentials.
    ads_login_attempts = 0

    def recover_ads_manager_login() -> bool:
        nonlocal ads_login_attempts
        if _ads_login_otp_visible(driver):
            raise AdsCostLoginOtpRequired("广告登录需要验证码")
        if not _ads_manager_login_page(driver):
            return True
        if ads_login_attempts >= 2:
            return False
        ads_login_attempts += 1
        result = _attempt_autofilled_ads_manager_login(driver)
        if result == "otp":
            raise AdsCostLoginOtpRequired("广告登录需要验证码")
        if result == "credentials_rejected":
            raise AdsCostLoginCredentialsRejected("广告账号密码错误")
        if result == "verification":
            raise AdsCostVerificationRequired(
                "TikTok Ads Manager login requires verification in the "
                "preserved exact Feikua shop session."
            )
        return result == "authenticated"

    recover_ads_manager_login()
    for sso_attempt in range(1, 3):
        if not _ads_manager_login_page(driver):
            break
        print(
            "TIKTOK_AD_COST_LOGIN_PAGE_DETECTED: "
            f"sso_attempt={sso_attempt}/2",
            flush=True,
        )
        if seller_recovery_handle not in driver.window_handles:
            break
        driver.switch_to.window(seller_recovery_handle)
        retry_link = WebDriverWait(
            driver, 60, poll_frequency=0.5
        ).until(official_link_or_disabled)
        retry_href = (retry_link.get_attribute("href") or "").strip()
        if not _is_ads_manager(retry_href):
            break
        retry_prior_handles = set(driver.window_handles)
        _click_rendered(driver, retry_link)
        _settle_official_ads_manager_navigation(
            driver,
            retry_prior_handles,
            retry_href,
        )
        print(
            "TIKTOK_AD_COST_SSO_REENTRY_COMPLETED: "
            f"attempt={sso_attempt}/2",
            flush=True,
        )
        time.sleep(5)
        recover_ads_manager_login()
    if _ads_manager_login_page(driver):
        raise AdsCostVerificationRequired(
            "TikTok Ads Manager automatic login and Seller Center SSO "
            "re-entry did not reach an authenticated Ads Manager page in "
            "the preserved exact Feikua shop session."
        )

    def billing_control(current):
        try:
            anchors = current.find_elements(By.CSS_SELECTOR, "a")
        except (TimeoutException, WebDriverException, ReadTimeoutError) as error:
            if _is_transient_renderer_timeout(error):
                return None
            raise
        for anchor in anchors:
            try:
                href = anchor.get_attribute("href") or ""
                if (
                    anchor.is_displayed()
                    and anchor.is_enabled()
                    and (
                        "/i18n/account/payment" in href
                        or anchor.text.strip()
                        in {"Billing", "结算", "結算", "การเรียกเก็บเงิน"}
                    )
                ):
                    return anchor
            except StaleElementReferenceException:
                continue
            except (TimeoutException, WebDriverException, ReadTimeoutError) as error:
                if _is_transient_renderer_timeout(error):
                    continue
                raise
        return None

    next_billing_notice = time.monotonic() + 30
    billing_recovery_deadline = time.monotonic() + 120
    direct_billing_attempted = False
    while True:
        if _ads_manager_login_page(driver):
            if recover_ads_manager_login():
                billing_recovery_deadline = time.monotonic() + 120
                next_billing_notice = time.monotonic() + 30
                continue
            raise AdsCostVerificationRequired(
                "TikTok Ads Manager returned to its login page while opening "
                "Billing and bounded automatic login was exhausted."
            )
        current_ads_url = str(driver.current_url or "")
        if "/i18n/account/payment" in current_ads_url:
            billing = None
            break
        billing = billing_control(driver)
        if billing is not None:
            break
        now = time.monotonic()
        if now >= billing_recovery_deadline:
            account_id = _ads_manager_account_id(
                current_ads_url,
                official_href,
            )
            if account_id and not direct_billing_attempted:
                direct_billing_attempted = True
                direct_billing_url = (
                    "https://ads.tiktok.com/i18n/account/payment"
                    f"?aadvid={account_id}"
                )
                print(
                    "TIKTOK_AD_COST_BILLING_DIRECT_ROUTE_RECOVERY: "
                    f"aadvid={account_id}",
                    flush=True,
                )
                _navigate(driver, direct_billing_url, timeout=120)
                billing_recovery_deadline = time.monotonic() + 120
                next_billing_notice = time.monotonic() + 30
                continue
            raise AdsCostExportError(
                "Ads Manager remained authenticated but exposed neither the "
                "Billing control nor a recoverable advertising account route."
            )
        if now >= next_billing_notice:
            print(
                "TIKTOK_AD_COST_BILLING_STILL_LOADING: Ads Manager target "
                "is alive; waiting without refresh or close",
                flush=True,
            )
            next_billing_notice = now + 30
        time.sleep(2)
    if billing is not None:
        _click_rendered(driver, billing)
    next_payment_notice = time.monotonic() + 30
    payment_deadline = time.monotonic() + 120
    direct_payment_attempted = False
    while True:
        if (
            "/i18n/account/payment" in driver.current_url
            or bool(
                driver.find_elements(
                    By.CSS_SELECTOR, ".finance-secondary-menu__item"
                )
            )
        ):
            break
        now = time.monotonic()
        if now >= payment_deadline:
            account_id = _ads_manager_account_id(
                str(driver.current_url or ""),
                official_href,
            )
            if account_id and not direct_payment_attempted:
                direct_payment_attempted = True
                print(
                    "TIKTOK_AD_COST_PAYMENT_DIRECT_ROUTE_RECOVERY: "
                    f"aadvid={account_id}",
                    flush=True,
                )
                _navigate(
                    driver,
                    "https://ads.tiktok.com/i18n/account/payment"
                    f"?aadvid={account_id}",
                    timeout=120,
                )
                payment_deadline = time.monotonic() + 120
                next_payment_notice = time.monotonic() + 30
                continue
            raise AdsCostExportError(
                "TikTok Ads Billing did not render after bounded direct-route "
                "recovery."
            )
        if now >= next_payment_notice:
            print(
                "TIKTOK_AD_COST_PAYMENT_STILL_LOADING: Billing was clicked; "
                "waiting without refresh or close",
                flush=True,
            )
            next_payment_notice = now + 30
        time.sleep(2)
    print("TIKTOK_AD_COST_BILLING_OPENED", flush=True)

    def transactions_control(current):
        # Some Ads Manager shells render the finance navigation as div
        # menuitems without hrefs.  Its transaction entry is the final item in
        # the stable `layout-menu-wrapper`; visible text is localized.
        for wrapper in current.find_elements(
            By.CSS_SELECTOR, '[data-testid="layout-menu-wrapper"]'
        ):
            try:
                if not wrapper.is_displayed():
                    continue
                items = [
                    item
                    for item in wrapper.find_elements(
                        By.CSS_SELECTOR, '[role="menuitem"]'
                    )
                    if item.is_displayed() and item.is_enabled()
                ]
                if items:
                    return items[-1]
            except StaleElementReferenceException:
                continue
        for control in current.find_elements(
            By.CSS_SELECTOR,
            ".finance-secondary-menu__item, button, a",
        ):
            try:
                if (
                    control.is_displayed()
                    and control.is_enabled()
                    and control.text.strip()
                    in {
                        "Transactions",
                        "交易",
                        "交易记录",
                        "交易活動",
                        "ธุรกรรม",
                    }
                ):
                    return control
            except StaleElementReferenceException:
                continue
        return None

    next_transactions_notice = time.monotonic() + 30
    transactions_deadline = time.monotonic() + 120
    direct_transactions_attempted = False
    while True:
        if "/i18n/account/transaction" in str(driver.current_url or ""):
            transactions = None
            break
        transactions = transactions_control(driver)
        if transactions is not None:
            break
        now = time.monotonic()
        if now >= transactions_deadline:
            account_id = _ads_manager_account_id(
                str(driver.current_url or ""),
                official_href,
            )
            if account_id and not direct_transactions_attempted:
                direct_transactions_attempted = True
                print(
                    "TIKTOK_AD_COST_TRANSACTIONS_DIRECT_ROUTE_RECOVERY: "
                    f"aadvid={account_id}",
                    flush=True,
                )
                _navigate(
                    driver,
                    "https://ads.tiktok.com/i18n/account/transaction"
                    f"?aadvid={account_id}",
                    timeout=120,
                )
                transactions_deadline = time.monotonic() + 120
                next_transactions_notice = time.monotonic() + 30
                continue
            raise AdsCostExportError(
                "TikTok Ads Transactions did not render after bounded "
                "direct-route recovery."
            )
        if now >= next_transactions_notice:
            print(
                "TIKTOK_AD_COST_TRANSACTIONS_STILL_LOADING: Billing shell is "
                "alive; waiting without refresh or close",
                flush=True,
            )
            next_transactions_notice = now + 30
        time.sleep(2)
    if transactions is not None:
        _click_rendered(driver, transactions)
    next_transaction_page_notice = time.monotonic() + 30
    transaction_page_deadline = time.monotonic() + 120
    direct_transaction_page_attempted = False
    while "/i18n/account/transaction" not in driver.current_url:
        now = time.monotonic()
        if now >= transaction_page_deadline:
            account_id = _ads_manager_account_id(
                str(driver.current_url or ""),
                official_href,
            )
            if account_id and not direct_transaction_page_attempted:
                direct_transaction_page_attempted = True
                print(
                    "TIKTOK_AD_COST_TRANSACTION_PAGE_DIRECT_ROUTE_RECOVERY: "
                    f"aadvid={account_id}",
                    flush=True,
                )
                _navigate(
                    driver,
                    "https://ads.tiktok.com/i18n/account/transaction"
                    f"?aadvid={account_id}",
                    timeout=120,
                )
                transaction_page_deadline = time.monotonic() + 120
                next_transaction_page_notice = time.monotonic() + 30
                continue
            raise AdsCostExportError(
                "TikTok Ads Transactions control was activated, but the "
                "transaction route did not load after bounded direct-route "
                "recovery."
            )
        if now >= next_transaction_page_notice:
            print(
                "TIKTOK_AD_COST_TRANSACTION_PAGE_STILL_LOADING: Transactions "
                "was clicked; waiting without refresh or close",
                flush=True,
            )
            next_transaction_page_notice = now + 30
        time.sleep(2)
    ensure_costs_tab(driver)
    print("TIKTOK_AD_COST_TRANSACTIONS_OPENED", flush=True)


def ensure_costs_tab(driver: webdriver.Chrome) -> None:
    """Click and verify the second (Costs) tab without localized text."""
    wait = WebDriverWait(driver, 60, poll_frequency=0.2)

    def costs_tab(current):
        container = next(
            (
                item
                for item in current.find_elements(
                    By.CSS_SELECTOR,
                    '[data-testid="shared-transaction-tab-container"]',
                )
                if item.is_displayed()
            ),
            None,
        )
        if container is None:
            return None
        # The shared transaction component exposes payments in slot 1 and
        # costs in slot 2 in every locale ("Costs" / "消耗" / ...).
        for element in container.find_elements(By.CSS_SELECTOR, 'span[slot="2"]'):
            if element.is_displayed() and element.is_enabled():
                return element
        return None

    tab = wait.until(costs_tab)
    _click_rendered(driver, tab)

    def costs_active(current) -> bool:
        try:
            costs_panel = current.find_element(By.CSS_SELECTOR, ADS_COSTS_PANEL)
            payments_panel = current.find_element(By.CSS_SELECTOR, ADS_PAYMENTS_PANEL)
            query = urllib.parse.parse_qs(
                urllib.parse.urlparse(current.current_url).query
            )
            return (
                query.get("tab") == ["2"]
                and not bool(
                    current.execute_script(
                        "return arguments[0].hasAttribute('hidden');",
                        costs_panel,
                    )
                )
                and bool(
                    current.execute_script(
                        "return arguments[0].hasAttribute('hidden');",
                        payments_panel,
                    )
                )
                and bool(current.find_elements(By.CSS_SELECTOR, ADS_DATE_PICKER))
            )
        except Exception:
            return False

    wait.until(costs_active)
    print("TIKTOK_AD_COST_COSTS_TAB_CONFIRMED", flush=True)


def _account_menu_open(driver: webdriver.Chrome) -> bool:
    return bool(
        driver.execute_script(
            """
            const popover = document.querySelector(
              '.account-manage-wrap .biz-popover-content'
            );
            if (!popover) return false;
            const rect = popover.getBoundingClientRect();
            const style = getComputedStyle(popover);
            return rect.width > 0 && rect.height > 0 &&
              style.display !== 'none' && style.visibility !== 'hidden';
            """
        )
    )


def _toggle_account_menu(driver: webdriver.Chrome) -> None:
    trigger = WebDriverWait(driver, 120, poll_frequency=0.5).until(
        lambda current: _visible_enabled(
            current, ".account-manage-wrap .biz-user-warp"
        )
    )
    _click_rendered(driver, trigger)


def _visible_account_rows(driver: webdriver.Chrome) -> list[dict[str, str | bool]]:
    return list(
        driver.execute_script(
            """
            return Array.from(
              document.querySelectorAll('.account-panel-info-item')
            ).map((row) => {
              const idText = row.querySelector('.title-info-id')?.innerText || '';
              const match = idText.match(/\\b(\\d{15,20})\\b/);
              return {
                accountId: match ? match[1] : '',
                accountName:
                  row.querySelector('.title-info-name')?.innerText?.trim() || '',
                selected: Boolean(
                  row.querySelector('.account-panel-info-item-title.selected')
                ),
              };
            }).filter((item) => item.accountId && item.accountName);
            """
        )
    )


def discover_ad_accounts(driver: webdriver.Chrome) -> list[dict[str, str]]:
    """Discover account rows, excluding section/group headings."""
    account_rows_deadline = time.monotonic() + 180
    next_account_rows_notice = time.monotonic() + 30
    while True:
        try:
            if not _account_menu_open(driver):
                _toggle_account_menu(driver)
            if _visible_account_rows(driver):
                break
        except Exception as error:
            if not _is_transient_renderer_timeout(error):
                raise
        now = time.monotonic()
        if now >= account_rows_deadline:
            raise TimeoutException(
                "TikTok advertising account list did not render within "
                "180 seconds"
            )
        if now >= next_account_rows_notice:
            print(
                "TIKTOK_AD_COST_ACCOUNT_LIST_STILL_LOADING: Ads Manager "
                "target is alive; waiting without refresh or close",
                flush=True,
            )
            next_account_rows_notice = now + 30
        time.sleep(2)
    accounts: dict[str, dict[str, str]] = {}
    unchanged = 0
    for _ in range(120):
        before = len(accounts)
        for row in _visible_account_rows(driver):
            account_id = str(row["accountId"])
            accounts[account_id] = {
                "accountId": account_id,
                "accountName": str(row["accountName"]),
            }
        scroll_state = driver.execute_script(
            """
            const candidates = [
              document.querySelector('.account-panel-wrapper'),
              document.querySelector('.account-panel'),
              document.querySelector('.biz-popover-content'),
            ].filter(Boolean);
            const scroller = candidates.find(
              (item) => item.scrollHeight > item.clientHeight + 2
            );
            if (!scroller) return {scrollable: false, bottom: true};
            const bottom =
              scroller.scrollTop + scroller.clientHeight >=
              scroller.scrollHeight - 2;
            if (!bottom) {
              scroller.scrollTop = Math.min(
                scroller.scrollHeight,
                scroller.scrollTop + Math.max(120, scroller.clientHeight * 0.8)
              );
              scroller.dispatchEvent(new Event('scroll', {bubbles: true}));
            }
            return {scrollable: true, bottom};
            """
        )
        unchanged = unchanged + 1 if len(accounts) == before else 0
        if bool(scroll_state.get("bottom")) and unchanged >= 2:
            break
        time.sleep(0.25)
    if _account_menu_open(driver):
        _toggle_account_menu(driver)
        WebDriverWait(driver, 10).until(lambda current: not _account_menu_open(current))
    if not accounts:
        raise AdsCostExportError("No selectable TikTok advertising accounts were found.")
    discovered = list(accounts.values())
    emit_json(
        "TIKTOK_AD_COST_ACCOUNTS_DISCOVERED_JSON",
        {"accounts": discovered, "accountCount": len(discovered)},
    )
    return discovered


def select_ad_account(
    driver: webdriver.Chrome,
    account_id: str,
    account_name: str,
) -> None:
    """Select one exact account ID and wait for Ads Manager to reload it."""
    if not _account_menu_open(driver):
        _toggle_account_menu(driver)
    WebDriverWait(driver, 120, poll_frequency=0.5).until(
        lambda current: _visible_account_rows(current) or None
    )

    for _ in range(150):
        selected = driver.execute_script(
            """
            const wanted = arguments[0];
            for (const row of document.querySelectorAll(
              '.account-panel-info-item'
            )) {
              const idText =
                row.querySelector('.title-info-id')?.innerText || '';
              const match = idText.match(/\\b(\\d{15,20})\\b/);
              if (!match || match[1] !== wanted) continue;
              const title = row.querySelector('.account-panel-info-item-title');
              const already = Boolean(title?.classList.contains('selected'));
              if (!already) {
                (row.querySelector('a') || title || row).click();
              }
              return {found: true, already};
            }
            const candidates = [
              document.querySelector('.account-panel-wrapper'),
              document.querySelector('.account-panel'),
              document.querySelector('.biz-popover-content'),
            ].filter(Boolean);
            const scroller = candidates.find(
              (item) => item.scrollHeight > item.clientHeight + 2
            );
            if (scroller) {
              const atBottom =
                scroller.scrollTop + scroller.clientHeight >=
                scroller.scrollHeight - 2;
              if (atBottom) scroller.scrollTop = 0;
              else {
                scroller.scrollTop = Math.min(
                  scroller.scrollHeight,
                  scroller.scrollTop + Math.max(120, scroller.clientHeight * 0.8)
                );
              }
              scroller.dispatchEvent(new Event('scroll', {bubbles: true}));
            }
            return {found: false, already: false};
            """,
            account_id,
        )
        if selected.get("found"):
            if selected.get("already") and _account_menu_open(driver):
                _toggle_account_menu(driver)
            break
        time.sleep(0.2)
    else:
        raise AdsCostExportError(
            f"Advertising account ID {account_id} was not found in the dropdown."
        )

    def account_active(current) -> bool:
        query = urllib.parse.parse_qs(
            urllib.parse.urlparse(current.current_url).query
        )
        if query.get("aadvid") != [account_id]:
            return False
        if _account_menu_open(current):
            return False
        # The selected name can be visually truncated/localized.  The exact
        # aadvid URL value plus a closed account menu is the stable identity
        # gate; the row itself was selected by exact numeric account ID above.
        return True

    WebDriverWait(driver, 90, poll_frequency=0.25).until(account_active)
    _wait_document_ready(driver, 60)
    emit_json(
        "TIKTOK_AD_COST_ACCOUNT_STARTED_JSON",
        {"accountId": account_id, "accountName": account_name},
    )


def _recover_account_transaction_route(
    driver: webdriver.Chrome,
    account_id: str,
    attempt: int,
) -> None:
    """Reload one failed account through its stable Costs route.

    A slow account switch can leave the account popover or date picker tied to
    the previous account.  Retrying the dropdown in that state causes every
    following account to fail.  The stable account ID route rebuilds only the
    current Transactions panel and does not repeat an Export click.
    """
    emit_json(
        "TIKTOK_AD_COST_ACCOUNT_ROUTE_RECOVERY_JSON",
        {
            "accountId": account_id,
            "attempt": attempt,
            "route": "transactions_costs",
        },
    )
    _navigate(
        driver,
        "https://ads.tiktok.com/i18n/account/transaction"
        f"?tab=2&aadvid={account_id}",
        timeout=120,
    )
    WebDriverWait(driver, 120, poll_frequency=0.5).until(
        lambda current: (
            "/i18n/account/transaction" in str(current.current_url or "")
            and _ads_manager_account_id(str(current.current_url or ""))
            == account_id
        )
    )


def _picker_state(driver: webdriver.Chrome) -> dict:
    return dict(
        driver.execute_script(
            """
            const picker = document.querySelector(
              '[data-testid^="panel-costs-TimeRange-"]'
            );
            if (!picker) return {};
            return {
              open: Boolean(picker.open),
              value: Array.isArray(picker.value)
                ? picker.value.map(String)
                : [],
            };
            """
        )
    )


def _open_cost_date_picker(driver: webdriver.Chrome) -> None:
    clicked = driver.execute_script(
        """
        function all(root, out = []) {
          for (const element of root.querySelectorAll('*')) {
            out.push(element);
            if (element.shadowRoot) all(element.shadowRoot, out);
          }
          return out;
        }
        const presenter = all(document).find((element) =>
          (element.getAttribute?.('data-testid') || '')
            .startsWith('base-pick-presenter-')
        );
        if (!presenter) return false;
        presenter.click();
        return true;
        """
    )
    if not clicked:
        raise AdsCostExportError("The advertising Costs date picker did not render.")
    WebDriverWait(driver, 15, poll_frequency=0.1).until(
        lambda current: bool(_picker_state(current).get("open"))
    )
    # ``open`` flips before the two calendar shadow trees finish hydrating.
    # Do not attempt date clicks until their month headers really exist.
    WebDriverWait(driver, 15, poll_frequency=0.1).until(
        lambda current: _calendar_headers(current) or None
    )


def _calendar_headers(driver: webdriver.Chrome) -> list[str]:
    return list(
        driver.execute_script(
            """
            const normalize = (value) =>
              (value || '').trim().replace(/\\s+/g, ' ');
            function all(root, out = []) {
              for (const element of root.querySelectorAll('*')) {
                out.push(element);
                if (element.shadowRoot) all(element.shadowRoot, out);
              }
              return out;
            }
            return all(document)
              .filter((element) =>
                element.classList?.contains('date-grid-header')
              )
              .filter((element) => {
                const rect = element.getBoundingClientRect();
                return rect.width > 0 && rect.height > 0;
              })
              .map((element) => normalize(element.innerText));
            """
        )
    )


def _month_from_label(label: str) -> dt.date:
    normalized = re.sub(r"\s+", " ", label).strip()
    for pattern in ("%B %Y", "%b %Y", "%Y %B", "%Y %b"):
        try:
            parsed = dt.datetime.strptime(normalized, pattern)
            return parsed.date().replace(day=1)
        except ValueError:
            pass

    year_match = re.search(r"(?<!\d)((?:19|20|21)\d{2})(?!\d)", normalized)
    if year_match:
        year = int(year_match.group(1))
        numeric = re.search(
            r"(?:^|[^\d])((?:1[0-2])|(?:0?[1-9]))\s*(?:月|월)",
            normalized,
        )
        if numeric:
            return dt.date(year, int(numeric.group(1)), 1)
        numeric = re.search(
            rf"{year}\s*(?:年|[-./])\s*((?:1[0-2])|(?:0?[1-9]))",
            normalized,
        )
        if numeric:
            return dt.date(year, int(numeric.group(1)), 1)

        month_tokens = {
            "一月": 1,
            "二月": 2,
            "三月": 3,
            "四月": 4,
            "五月": 5,
            "六月": 6,
            "七月": 7,
            "八月": 8,
            "九月": 9,
            "十月": 10,
            "十一月": 11,
            "十二月": 12,
            "มกราคม": 1,
            "กุมภาพันธ์": 2,
            "มีนาคม": 3,
            "เมษายน": 4,
            "พฤษภาคม": 5,
            "มิถุนายน": 6,
            "กรกฎาคม": 7,
            "สิงหาคม": 8,
            "กันยายน": 9,
            "ตุลาคม": 10,
            "พฤศจิกายน": 11,
            "ธันวาคม": 12,
        }
        for token, month in month_tokens.items():
            if token in normalized:
                return dt.date(year, month, 1)

    raise AdsCostExportError(
        f"Could not parse Ads Manager calendar month: {label!r}."
    )


def _click_calendar_arrow(driver: webdriver.Chrome, direction: str) -> None:
    icon_tag = (
        "KS-ICON-CHEVRON-LEFT"
        if direction == "left"
        else "KS-ICON-CHEVRON-RIGHT"
    )
    clicked = driver.execute_script(
        """
        const wanted = arguments[0];
        function all(root, out = []) {
          for (const element of root.querySelectorAll('*')) {
            out.push(element);
            if (element.shadowRoot) all(element.shadowRoot, out);
          }
          return out;
        }
        const parent = (element) =>
          element?.parentElement || element?.getRootNode()?.host || null;
        const icon = all(document).find((element) => {
          if (element.tagName !== wanted) return false;
          const rect = element.getBoundingClientRect();
          return rect.width > 0 && rect.height > 0;
        });
        if (!icon) return false;
        let control = icon;
        while (control && !control.tagName.startsWith('KS-ICON-BUTTON')) {
          control = parent(control);
        }
        (control || icon).click();
        return true;
        """,
        icon_tag,
    )
    if not clicked:
        raise AdsCostExportError(
            f"The advertising calendar {direction} navigation was unavailable."
        )


def _make_month_visible(driver: webdriver.Chrome, target: dt.date) -> None:
    target_month = target.replace(day=1)
    for _ in range(240):
        labels = _calendar_headers(driver)
        if not labels:
            raise AdsCostExportError("The advertising calendar has no month headers.")
        months = [_month_from_label(label) for label in labels]
        if target_month in months:
            return
        before = labels
        _click_calendar_arrow(
            driver, "left" if target_month < min(months) else "right"
        )
        WebDriverWait(driver, 10, poll_frequency=0.1).until(
            lambda current: _calendar_headers(current) != before
        )
    raise AdsCostExportError(
        f"The advertising calendar could not reach {target_month:%Y-%m}."
    )


def _click_calendar_date(driver: webdriver.Chrome, value: dt.date) -> None:
    target_month = value.replace(day=1)

    def rendered_target_label(current):
        for label in _calendar_headers(current):
            try:
                if _month_from_label(label) == target_month:
                    return label
            except AdsCostExportError:
                continue
        return None

    try:
        month_label = WebDriverWait(
            driver, 10, poll_frequency=0.1
        ).until(rendered_target_label)
    except TimeoutException as error:
        raise AdsCostExportError(
            f"The advertising calendar did not render {target_month:%Y-%m}."
        ) from error
    result = dict(
        driver.execute_script(
            """
            const day = String(arguments[0]);
            const month = arguments[1];
            const normalize = (value) =>
              (value || '').trim().replace(/\\s+/g, ' ');
            function all(root, out = []) {
              for (const element of root.querySelectorAll('*')) {
                out.push(element);
                if (element.shadowRoot) all(element.shadowRoot, out);
              }
              return out;
            }
            const parent = (element) =>
              element?.parentElement || element?.getRootNode()?.host || null;
            const header = all(document).find((element) =>
              element.classList?.contains('date-grid-header') &&
              normalize(element.innerText) === month &&
              element.getBoundingClientRect().width > 0
            );
            if (!header) return {ok: false, reason: 'month'};
            let grid = header;
            while (grid && !grid.classList?.contains('date-grid')) {
              grid = parent(grid);
            }
            if (!grid) return {ok: false, reason: 'grid'};
            const cell = all(grid).find((element) =>
              element.classList?.contains('date-grid-body__date-item') &&
              normalize(element.innerText || element.textContent) === day &&
              !element.classList.contains(
                'date-grid-body__date-item--outside-current-period'
              ) &&
              !element.classList.contains(
                'date-grid-body__date-item--disabled'
              )
            );
            if (!cell) return {ok: false, reason: 'date'};
            cell.click();
            return {ok: true};
            """,
            value.day,
            month_label,
        )
    )
    if not result.get("ok"):
        raise AdsCostExportError(
            f"Could not click advertising date {value.isoformat()} "
            f"({result.get('reason', 'unknown')})."
        )


def _confirm_cost_date_picker(driver: webdriver.Chrome) -> None:
    clicked = driver.execute_script(
        """
        function all(root, out = []) {
          for (const element of root.querySelectorAll('*')) {
            out.push(element);
            if (element.shadowRoot) all(element.shadowRoot, out);
          }
          return out;
        }
        const popup = all(document).find((element) => {
          if (!element.classList?.contains('picker-core__popup')) return false;
          const rect = element.getBoundingClientRect();
          return rect.width > 0 && rect.height > 0;
        });
        if (!popup) return false;
        const rendered = (element) => {
          const rect = element.getBoundingClientRect();
          const style = getComputedStyle(element);
          return rect.width > 0 && rect.height > 0 &&
            style.display !== 'none' && style.visibility !== 'hidden' &&
            !element.hasAttribute('disabled') &&
            element.getAttribute('aria-disabled') !== 'true';
        };
        const buttons = all(popup).filter((element) =>
          element.tagName.startsWith('KS-BUTTON') &&
          !element.tagName.includes('ICON-BUTTON') &&
          !element.classList?.contains('KsIconButton') &&
          rendered(element)
        );
        const labels = new Set([
          'Confirm', '确认', '確定', '確認', 'ยืนยัน'
        ]);
        const confirm = buttons.length === 1
          ? buttons[0]
          : buttons.find((element) =>
              labels.has(
                (element.innerText || element.textContent || '').trim()
              )
            );
        if (!confirm) return false;
        confirm.click();
        return true;
        """
    )
    if not clicked:
        raise AdsCostExportError(
            "The advertising date picker Confirm button was unavailable."
        )


def select_cost_date_range(
    driver: webdriver.Chrome,
    start_date: dt.date,
    end_date: dt.date,
) -> None:
    """Always reselect and confirm the exact range for the current account."""
    if start_date > end_date:
        raise AdsCostExportError("The advertising start date is after the end date.")
    WebDriverWait(driver, 60).until(
        lambda current: bool(current.find_elements(By.CSS_SELECTOR, ADS_DATE_PICKER))
    )
    state = _picker_state(driver)
    if state.get("open"):
        _confirm_cost_date_picker(driver)
        WebDriverWait(driver, 10).until(
            lambda current: not bool(_picker_state(current).get("open"))
        )

    _open_cost_date_picker(driver)
    _make_month_visible(driver, start_date)
    _click_calendar_date(driver, start_date)
    _make_month_visible(driver, end_date)
    _click_calendar_date(driver, end_date)
    _confirm_cost_date_picker(driver)

    expected = [start_date.isoformat(), end_date.isoformat()]
    WebDriverWait(driver, 30, poll_frequency=0.2).until(
        lambda current: (
            not bool(_picker_state(current).get("open"))
            and _picker_state(current).get("value") == expected
        )
    )
    actual = _picker_state(driver).get("value")
    if actual != expected:
        raise AdsCostExportError(
            f"Advertising date mismatch: expected {expected}, received {actual}."
        )
    emit_json(
        "TIKTOK_AD_COST_DATE_RANGE_CONFIRMED_JSON",
        {"startDate": expected[0], "endDate": expected[1]},
    )


def _reset_transient_cost_controls(driver: webdriver.Chrome) -> None:
    """Close an interrupted picker/menu before the next account attempt."""
    refresh_required = False
    try:
        if _picker_state(driver).get("open"):
            _confirm_cost_date_picker(driver)
            WebDriverWait(driver, 10, poll_frequency=0.2).until(
                lambda current: not bool(_picker_state(current).get("open"))
            )
    except Exception:
        refresh_required = True
    try:
        if _account_menu_open(driver):
            _toggle_account_menu(driver)
            WebDriverWait(driver, 10, poll_frequency=0.2).until(
                lambda current: not _account_menu_open(current)
            )
    except Exception:
        refresh_required = True
    if refresh_required:
        driver.refresh()
        _wait_document_ready(driver, 60)
        ensure_costs_tab(driver)


def wait_for_cost_result(
    driver: webdriver.Chrome,
    timeout: int | None = None,
) -> str:
    """Return only after an explicit rendered state.

    TikTok can leave the authenticated SPA shell and navigation visible while
    the main panel is blank for several minutes.  That is loading, not a
    terminal result, so the monthly runner waits in place by default.
    """
    def state(current) -> str | None:
        try:
            panel = current.find_element(By.CSS_SELECTOR, ADS_COSTS_PANEL)
            if current.execute_script(
                "return arguments[0].hasAttribute('hidden');",
                panel,
            ):
                return None
            rendered = current.execute_script(
                """
                const content = document.querySelector(
                  '[data-testid="ttam-cost-content"]'
                );
                if (!content) return null;
                function all(root, out = []) {
                  for (const element of root.querySelectorAll('*')) {
                    out.push(element);
                    if (element.shadowRoot) all(element.shadowRoot, out);
                  }
                  return out;
                }
                const nodes = all(content);
                const rendered = (element) => {
                  const rect = element.getBoundingClientRect();
                  const style = getComputedStyle(element);
                  return rect.width > 0 && rect.height > 0 &&
                    style.display !== 'none' &&
                    style.visibility !== 'hidden';
                };
                const noDataLabels = new Set([
                  'No data',
                  '暂无数据',
                  '无数据',
                  '没有数据',
                  '暫無資料',
                  '無資料',
                  'ไม่มีข้อมูล',
                ]);
                const explicitNoData = nodes.some((element) => {
                  const text =
                    (element.innerText || element.textContent || '').trim();
                  const marker = [
                    element.tagName || '',
                    element.getAttribute?.('data-testid') || '',
                    element.className?.toString?.() || '',
                  ].join(' ').toLowerCase();
                  return rendered(element) && (
                    noDataLabels.has(text) ||
                    /(?:^|[-_ ])(?:empty|no[-_ ]?data)(?:$|[-_ ])/.test(marker)
                  );
                });
                if (explicitNoData) return 'no_data';
                const table = nodes.find((element) =>
                  (element.getAttribute?.('data-testid') || '')
                    .startsWith('cost-content-Table-')
                );
                if (!table) return null;
                // ``nodes`` already includes every descendant shadow tree of
                // the Costs panel. Starting a second traversal at the custom
                // table host would miss the host's own shadowRoot.
                const rows = nodes.filter((element) => {
                  const testId = element.getAttribute?.('data-testid') || '';
                  if (
                    element.tagName !== 'TR' &&
                    !testId.includes('table-body-row')
                  ) return false;
                  const rect = element.getBoundingClientRect();
                  return rect.width > 0 && rect.height > 0;
                });
                return rows.length ? 'data' : null;
                """,
                ADS_COSTS_PANEL,
            )
            return str(rendered) if rendered in {"data", "no_data"} else None
        except (StaleElementReferenceException, ElementNotInteractableException):
            return None

    started_at = time.monotonic()
    next_notice_at = started_at + 30
    while True:
        _raise_if_store_disabled(driver)
        rendered_state = state(driver)
        if rendered_state:
            return rendered_state
        now = time.monotonic()
        if timeout is not None and now - started_at >= timeout:
            raise TimeoutException(
                f"TikTok cost result did not render within {timeout} seconds"
            )
        if now >= next_notice_at:
            print(
                "TIKTOK_AD_CONTENT_STILL_LOADING: authenticated shell is "
                "alive; waiting in place without refresh or close",
                flush=True,
            )
            next_notice_at = now + 30
        time.sleep(2)


def _valid_csv(path: Path) -> bool:
    try:
        raw = path.read_bytes()
        if len(raw) < 3:
            return False
        text = raw.decode("utf-8-sig")
        rows = list(csv.reader(text.splitlines()))
        return bool(rows and any(cell.strip() for cell in rows[0]))
    except (OSError, UnicodeDecodeError, csv.Error):
        return False


def _valid_zip(path: Path) -> bool:
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            if path.suffix.casefold() == ".xlsx":
                return "xl/workbook.xml" in names
            return any(
                Path(name).suffix.casefold() in {".xlsx", ".csv"}
                for name in names
            )
    except (OSError, zipfile.BadZipFile):
        return False


def valid_ad_cost_download(path: Path) -> bool:
    suffix = path.suffix.casefold()
    if not path.is_file() or path.stat().st_size <= 0:
        return False
    if suffix == ".xlsx":
        return _valid_zip(path)
    if suffix == ".zip":
        return _valid_zip(path)
    if suffix == ".csv":
        return _valid_csv(path)
    if suffix == ".xls":
        return path.stat().st_size >= 512
    return False


def _state_path(output_dir: Path) -> Path:
    return output_dir / ".tiktok-ad-cost-state.json"


def _new_state(
    shop_name: str,
    start_date: dt.date,
    end_date: dt.date,
) -> dict:
    return {
        "schemaVersion": 1,
        "shopName": shop_name,
        "startDate": start_date.isoformat(),
        "endDate": end_date.isoformat(),
        "accounts": {},
    }


def _load_state(
    output_dir: Path,
    shop_name: str,
    start_date: dt.date,
    end_date: dt.date,
) -> dict:
    expected = _new_state(shop_name, start_date, end_date)
    path = _state_path(output_dir)
    try:
        loaded = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        return expected
    if not isinstance(loaded, dict):
        return expected
    if any(
        loaded.get(key) != expected[key]
        for key in ("schemaVersion", "shopName", "startDate", "endDate")
    ):
        return expected
    if not isinstance(loaded.get("accounts"), dict):
        return expected
    return loaded


def _save_state(output_dir: Path, state: dict) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    destination = _state_path(output_dir)
    temporary = destination.with_name(
        f"{destination.name}.tmp-{os.getpid()}-{time.time_ns()}"
    )
    temporary.write_text(
        json.dumps(state, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    os.replace(temporary, destination)


def _resumable_account_result(
    result: object,
    account_id: str,
    account_name: str,
) -> dict | None:
    if not isinstance(result, dict) or result.get("accountId") != account_id:
        return None
    if result.get("status") == "no_data":
        resumed = dict(result)
        resumed["accountName"] = account_name
        return resumed
    if result.get("status") != "completed":
        return None
    raw_paths = result.get("filePaths")
    if not isinstance(raw_paths, list) or not raw_paths:
        return None
    paths = [Path(str(value)) for value in raw_paths]
    try:
        if not all(valid_ad_cost_download(path) for path in paths):
            return None
    except OSError:
        return None
    resumed = dict(result)
    resumed["accountName"] = account_name
    return resumed


def _safe_filename_part(value: str, limit: int = 72) -> str:
    cleaned = WINDOWS_INVALID_RE.sub("_", value).strip().rstrip(". ")
    cleaned = re.sub(r"\s+", " ", cleaned)
    if not cleaned:
        cleaned = "unnamed"
    if cleaned.upper() in {
        "CON", "PRN", "AUX", "NUL",
        *(f"COM{index}" for index in range(1, 10)),
        *(f"LPT{index}" for index in range(1, 10)),
    }:
        cleaned = f"_{cleaned}"
    return cleaned[:limit].rstrip(". ")


def _display_date(value: dt.date) -> str:
    """Render the compact date format used by advertising bill filenames."""
    return f"{value.year}.{value.month}.{value.day}"


def _download_candidates(directory: Path, known: set[Path]) -> list[Path]:
    result: list[Path] = []
    for path in directory.iterdir():
        if not path.is_file():
            continue
        resolved = path.resolve()
        if resolved in known or path.suffix.casefold() not in DOWNLOAD_SUFFIXES:
            continue
        result.append(resolved)
    return result


def _canonical_base_name(
    shop_name: str,
    account_name: str,
    start_date: dt.date,
    end_date: dt.date,
) -> str:
    return (
        f"{_safe_filename_part(shop_name)}+"
        f"{_safe_filename_part(account_name)}+"
        f"{_display_date(start_date)}-{_display_date(end_date)}"
    )


def _existing_canonical_paths(
    output_dir: Path,
    shop_name: str,
    account_name: str,
    start_date: dt.date,
    end_date: dt.date,
) -> list[Path]:
    base_name = _canonical_base_name(
        shop_name,
        account_name,
        start_date,
        end_date,
    )
    candidates: list[Path] = []
    if not output_dir.is_dir():
        return candidates
    for path in output_dir.iterdir():
        if not path.is_file() or path.suffix.casefold() not in DOWNLOAD_SUFFIXES:
            continue
        if path.stem != base_name and not path.stem.startswith(f"{base_name}+"):
            continue
        try:
            if valid_ad_cost_download(path):
                candidates.append(path.resolve())
        except OSError:
            continue
    return sorted(
        candidates,
        key=lambda path: (
            path.stem.casefold().endswith("+costtax"),
            path.name.casefold(),
        ),
    )


def _wait_for_staging_files(
    staging_dir: Path,
    timeout: int,
) -> list[Path]:
    last_signature: tuple[tuple[str, int], ...] = ()
    quiet_since = time.monotonic()
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not staging_dir.is_dir():
            time.sleep(0.5)
            continue
        partials = [
            path
            for path in staging_dir.iterdir()
            if path.is_file()
            and path.suffix.casefold() in {".crdownload", ".tmp"}
        ]
        candidates = _download_candidates(staging_dir, set())
        signature: tuple[tuple[str, int], ...] = tuple(
            sorted(
                (candidate.name, candidate.stat().st_size)
                for candidate in candidates
            )
        )
        if signature != last_signature:
            last_signature = signature
            quiet_since = time.monotonic()
        if (
            candidates
            and not partials
            and time.monotonic() - quiet_since >= 5
        ):
            try:
                if all(valid_ad_cost_download(path) for path in candidates):
                    return sorted(
                        candidates,
                        key=lambda path: (
                            path.stem.casefold().startswith("costtax_"),
                            path.name.casefold(),
                        ),
                    )
            except OSError:
                pass
        time.sleep(0.5)
    return []


def _finalize_staging_files(
    confirmed: list[Path],
    staging_dir: Path,
    output_dir: Path,
    shop_name: str,
    account_name: str,
    start_date: dt.date,
    end_date: dt.date,
) -> list[Path]:
    final_paths: list[Path] = []
    base_name = _canonical_base_name(
        shop_name,
        account_name,
        start_date,
        end_date,
    )
    for source in confirmed:
        source_stem = source.stem
        if source_stem.casefold().startswith("costtax_"):
            document_kind = "CostTax"
        elif source_stem.casefold().startswith("cost_"):
            document_kind = "Cost"
        else:
            document_kind = _safe_filename_part(source_stem, 40)
        suffix = source.suffix.casefold()
        # The primary Cost workbook follows the user's canonical name exactly.
        # A single Export click can also return CostTax, so only secondary
        # document kinds receive a suffix to keep both files without collision.
        kind_suffix = "" if document_kind == "Cost" else f"+{document_kind}"
        final_name = f"{base_name}{kind_suffix}{suffix}"
        final_path = output_dir / final_name
        if final_path.exists():
            if valid_ad_cost_download(final_path):
                source.unlink()
                final_paths.append(final_path.resolve())
                continue
            raise AdsCostExportError(
                f"The canonical advertising filename already exists but is invalid: "
                f"{final_path}"
            )
        shutil.move(str(source), str(final_path))
        if not valid_ad_cost_download(final_path):
            raise AdsCostExportError(
                "The renamed advertising export failed validation: "
                f"{final_path}"
            )
        final_paths.append(final_path.resolve())

    try:
        staging_dir.rmdir()
        staging_dir.parent.rmdir()
    except OSError:
        pass
    return final_paths


def resume_pending_cost_export(
    pending_result: dict,
    output_dir: Path,
    shop_name: str,
    account_name: str,
    start_date: dt.date,
    end_date: dt.date,
    timeout: int = 120,
) -> list[Path]:
    """Recover files from one recorded click intent without clicking again."""
    existing = _existing_canonical_paths(
        output_dir,
        shop_name,
        account_name,
        start_date,
        end_date,
    )
    if existing:
        return existing
    raw_staging = str(pending_result.get("stagingDir") or "").strip()
    if not raw_staging:
        return []
    staging_dir = Path(raw_staging)
    confirmed = _wait_for_staging_files(staging_dir, timeout)
    if not confirmed:
        return []
    return _finalize_staging_files(
        confirmed,
        staging_dir,
        output_dir,
        shop_name,
        account_name,
        start_date,
        end_date,
    )


def _completed_account_result(
    result: dict,
    file_paths: list[Path],
) -> dict:
    completed = dict(result)
    completed.pop("stagingDir", None)
    completed.pop("exportIntentAt", None)
    completed.pop("message", None)
    primary_file = next(
        (
            path
            for path in file_paths
            if not path.stem.casefold().endswith("+costtax")
        ),
        file_paths[0],
    )
    completed.update(
        {
            "status": "completed",
            "terminalCode": "TIKTOK_AD_COST_FILE_CONFIRMED",
            "filePath": str(primary_file),
            "filePaths": [str(path) for path in file_paths],
            "fileSize": primary_file.stat().st_size,
            "fileSizes": {
                str(path): path.stat().st_size
                for path in file_paths
            },
            "fileCount": len(file_paths),
        }
    )
    return completed


def download_current_cost_export(
    driver: webdriver.Chrome,
    output_dir: Path,
    shop_name: str,
    account_id: str,
    account_name: str,
    start_date: dt.date,
    end_date: dt.date,
    timeout: int = 120,
    on_export_intent: Callable[[Path], None] | None = None,
) -> list[Path]:
    """Click Export exactly once and confirm every file from that one click."""
    output_dir.mkdir(parents=True, exist_ok=True)
    staging_dir = output_dir / ".staging" / (
        f"{_safe_filename_part(account_id, 24)}-"
        f"{start_date.isoformat()}-{end_date.isoformat()}"
    )
    if staging_dir.exists():
        raise AdsCostExportPendingError(
            "A deterministic advertising staging directory already exists; "
            "the executor will not click Export again."
        )
    staging_dir.mkdir(parents=True, exist_ok=False)
    driver.execute_cdp_cmd(
        "Browser.setDownloadBehavior",
        {"behavior": "allow", "downloadPath": str(staging_dir)},
    )

    expected = [start_date.isoformat(), end_date.isoformat()]
    if _picker_state(driver).get("value") != expected:
        raise AdsCostExportError(
            "Advertising Export was blocked because the confirmed page dates "
            "do not equal the task dates."
        )
    export_button = WebDriverWait(driver, 30).until(
        lambda current: _visible_enabled(current, ADS_EXPORT_BUTTON)
    )
    if on_export_intent is not None:
        # Persist intent before the non-transactional browser click. A crash in
        # the tiny interval after this point is handled at-most-once: resume
        # waits for this staging directory and never clicks Export again.
        on_export_intent(staging_dir.resolve())
    _click_rendered(driver, export_button)
    emit_json(
        "TIKTOK_AD_COST_EXPORT_CLICKED_JSON",
        {
            "accountId": account_id,
            "accountName": account_name,
            "startDate": expected[0],
            "endDate": expected[1],
        },
    )
    print("TIKTOK_AD_COST_EXPORT_SINGLE_CLICK_ENFORCED", flush=True)

    confirmed = _wait_for_staging_files(staging_dir, timeout)
    if not confirmed:
        raise AdsCostExportPendingError(
            "Advertising Export may already have been clicked, but no validated "
            f"file appeared within {timeout} seconds. Resume will wait for the "
            "recorded staging directory and will not click again."
        )
    return _finalize_staging_files(
        confirmed,
        staging_dir,
        output_dir,
        shop_name,
        account_name,
        start_date,
        end_date,
    )


def export_all_ad_cost_accounts(
    driver: webdriver.Chrome,
    seller_center_url: str,
    shop_name: str,
    start_date: dt.date,
    end_date: dt.date,
    output_dir: Path,
) -> list[dict]:
    """Export every advertising account, continuing after explicit no-data."""
    output_dir = output_dir.resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    print(
        "TIKTOK_AD_COST_PHASE_STARTED: "
        f"range={start_date.isoformat()}..{end_date.isoformat()}",
        flush=True,
    )
    state = _load_state(
        output_dir,
        shop_name,
        start_date,
        end_date,
    )
    fallback_account_id = next(
        (
            str(account_id)
            for account_id, result in state.get("accounts", {}).items()
            if isinstance(result, dict)
            and result.get("status") not in {"completed", "no_data"}
            and str(account_id).isdigit()
        ),
        None,
    )
    open_ads_costs_from_seller_center(
        driver,
        seller_center_url,
        fallback_account_id=fallback_account_id,
    )
    accounts = discover_ad_accounts(driver)
    results: list[dict] = []
    state["discoveredAccounts"] = accounts
    _save_state(output_dir, state)

    for account in accounts:
        account_id = account["accountId"]
        account_name = account["accountName"]
        prior_result = state["accounts"].get(account_id)
        resumed = _resumable_account_result(
            prior_result,
            account_id,
            account_name,
        )
        if resumed is not None:
            results.append(resumed)
            emit_json("TIKTOK_AD_COST_ACCOUNT_RESUMED_JSON", resumed)
            continue
        if (
            isinstance(prior_result, dict)
            and prior_result.get("accountId") == account_id
            and prior_result.get("status") == "export_pending"
        ):
            pending_result = dict(prior_result)
            pending_result["accountName"] = account_name
            recovered_paths = resume_pending_cost_export(
                pending_result,
                output_dir,
                shop_name,
                account_name,
                start_date,
                end_date,
            )
            if not recovered_paths:
                results.append(pending_result)
                emit_json(
                    "TIKTOK_AD_COST_EXPORT_PENDING_JSON",
                    pending_result,
                )
                raise AdsCostExportPendingError(
                    "A prior Export click intent is still pending for account "
                    f"{account_id}; the executor will not click Export again."
                )
            recovered_result = _completed_account_result(
                pending_result,
                recovered_paths,
            )
            state["accounts"][account_id] = recovered_result
            _save_state(output_dir, state)
            results.append(recovered_result)
            emit_json(
                "TIKTOK_AD_COST_ACCOUNT_RESUMED_JSON",
                recovered_result,
            )
            continue
        result = {
            "accountId": account_id,
            "accountName": account_name,
            "startDate": start_date.isoformat(),
            "endDate": end_date.isoformat(),
            "status": "failed",
            "terminalCode": "TIKTOK_AD_COST_ACCOUNT_FAILED",
        }
        max_account_attempts = 3
        for account_attempt in range(1, max_account_attempts + 1):
            try:
                _reset_transient_cost_controls(driver)
                if account_attempt == 1:
                    select_ad_account(driver, account_id, account_name)
                else:
                    _recover_account_transaction_route(
                        driver,
                        account_id,
                        account_attempt,
                    )
                    emit_json(
                        "TIKTOK_AD_COST_ACCOUNT_STARTED_JSON",
                        {
                            "accountId": account_id,
                            "accountName": account_name,
                            "recoveryAttempt": account_attempt,
                        },
                    )
                ensure_costs_tab(driver)
                select_cost_date_range(driver, start_date, end_date)
                rendered_state = wait_for_cost_result(driver)
                if rendered_state == "no_data":
                    result.update(
                        {
                            "status": "no_data",
                            "terminalCode": "TIKTOK_AD_COST_NO_DATA",
                            "message": "Ads Manager explicitly displayed No data.",
                        }
                    )
                else:
                    def mark_export_intent(staging_dir: Path) -> None:
                        pending = dict(result)
                        pending.update(
                            {
                                "status": "export_pending",
                                "terminalCode": "TIKTOK_AD_COST_EXPORT_PENDING",
                                "stagingDir": str(staging_dir),
                                "exportIntentAt": dt.datetime.now(
                                    dt.timezone.utc
                                ).isoformat(),
                            }
                        )
                        state["accounts"][account_id] = pending
                        _save_state(output_dir, state)
                        emit_json(
                            "TIKTOK_AD_COST_EXPORT_INTENT_SAVED_JSON",
                            pending,
                        )

                    file_paths = download_current_cost_export(
                        driver,
                        output_dir,
                        shop_name,
                        account_id,
                        account_name,
                        start_date,
                        end_date,
                        on_export_intent=mark_export_intent,
                    )
                    result = _completed_account_result(result, file_paths)
                break
            except Exception as error:
                saved_result = state["accounts"].get(account_id)
                if (
                    isinstance(saved_result, dict)
                    and saved_result.get("status") == "export_pending"
                ):
                    result = dict(saved_result)
                    result["message"] = f"{type(error).__name__}: {error}"
                    break

                result["message"] = f"{type(error).__name__}: {error}"
                if _verification_required(driver):
                    result.update(
                        {
                            "status": "verification_pending",
                            "terminalCode": "TIKTOK_AD_COST_VERIFICATION_REQUIRED",
                        }
                    )
                    break

                if (
                    _is_transient_renderer_timeout(error)
                    and account_attempt < max_account_attempts
                ):
                    emit_json(
                        "TIKTOK_AD_COST_ACCOUNT_RETRY_JSON",
                        {
                            "accountId": account_id,
                            "accountName": account_name,
                            "attempt": account_attempt,
                            "nextAttempt": account_attempt + 1,
                            "reason": f"{type(error).__name__}: {error}",
                        },
                    )
                    continue

                try:
                    _reset_transient_cost_controls(driver)
                except Exception:
                    # Preserve the original per-account failure; the next
                    # account or coordinator retry will rebuild its route.
                    pass
                break
        results.append(result)
        emit_json("TIKTOK_AD_COST_ACCOUNT_RESULT_JSON", result)
        state["accounts"][account_id] = result
        _save_state(output_dir, state)
        if result["status"] == "export_pending":
            raise AdsCostExportPendingError(
                "Advertising Export has a recorded click intent for account "
                f"{account_id}; resume will recover files without another click."
            )
        if result["status"] == "verification_pending":
            raise AdsCostVerificationRequired(
                "TikTok Ads Manager requires human CAPTCHA verification; "
                "completed advertising accounts were checkpointed."
            )

    completed_count = sum(item["status"] == "completed" for item in results)
    no_data_count = sum(item["status"] == "no_data" for item in results)
    failed_count = sum(item["status"] == "failed" for item in results)
    phase_status = "completed" if failed_count == 0 else "partial"
    phase = {
        "status": phase_status,
        "accountCount": len(results),
        "completedCount": completed_count,
        "noDataCount": no_data_count,
        "failedCount": failed_count,
        "startDate": start_date.isoformat(),
        "endDate": end_date.isoformat(),
    }
    emit_json("TIKTOK_AD_COST_PHASE_COMPLETE_JSON", phase)
    if failed_count:
        failed_ids = ", ".join(
            item["accountId"] for item in results if item["status"] == "failed"
        )
        raise AdsCostExportError(
            f"Advertising cost export was incomplete for account IDs: {failed_ids}"
        )
    return results
