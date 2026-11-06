"""Independent Feikua smoke runner for TikTok advertising cost exports."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import time
import traceback
from pathlib import Path

import feikua_store as kernel
from tiktok_ad_costs import export_all_ad_cost_accounts


def parse_date(parser: argparse.ArgumentParser, value: str, label: str) -> dt.date:
    try:
        return dt.date.fromisoformat(value)
    except ValueError:
        parser.error(f"{label} must use YYYY-MM-DD format.")


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Run only the TikTok Ads Manager cost workflow while reusing the "
            "existing exact-name Feikua connection kernel."
        )
    )
    parser.add_argument("shop_name", help="Exact Feikua shop name")
    parser.add_argument("--start-date", required=True)
    parser.add_argument("--end-date", required=True)
    parser.add_argument("--out", required=True)
    parser.add_argument(
        "--fresh-session",
        action="store_true",
        help="Stop and restart only this exact shop before the smoke run.",
    )
    parser.add_argument(
        "--keep-open",
        action="store_true",
        help="Detach ChromeDriver but preserve the exact Feikua shop window.",
    )
    args = parser.parse_args()
    start_date = parse_date(parser, args.start_date, "--start-date")
    end_date = parse_date(parser, args.end_date, "--end-date")
    if start_date > end_date:
        parser.error("--start-date cannot be after --end-date.")

    base_url = kernel.find_base_url()
    store = kernel.select_store(base_url, args.shop_name)
    if args.fresh_session and store.get("status") != "idle":
        print("AD_COST_SMOKE_STOPPING_EXACT_TEST_SHOP", flush=True)
        kernel.stop_store(base_url, store["shop_id"])
        store = kernel.select_store(base_url, args.shop_name)

    running = (
        kernel.discover_running_cdp_port(store["shop_id"])
        if store.get("status") != "idle"
        else None
    )
    if running is None:
        kernel.stop_other_running_stores(base_url, store["shop_id"])
        print("AD_COST_SMOKE_STARTING_EXACT_TEST_SHOP", flush=True)
        started = kernel.start_store(base_url, store)
        cdp_port = int(started["debugger_port"])
        core_version = str(started.get("core_version", ""))
        kernel.save_session(store["shop_id"], cdp_port, core_version)
    else:
        cdp_port, core_version = running
        print(
            f"AD_COST_SMOKE_REUSING_EXACT_TEST_SHOP: cdp={cdp_port}",
            flush=True,
        )

    driver = None
    success = False
    manual_verification_pending = False
    try:
        kernel.wait_for_cdp(cdp_port)
        actual_core_version = kernel.browser_version_from_cdp(cdp_port)
        if actual_core_version:
            core_version = actual_core_version
        kernel.save_session(store["shop_id"], cdp_port, core_version)
        seller_center_url = kernel.read_validated_store_url(cdp_port)
        kernel.ensure_regular_tab(cdp_port, seller_center_url)
        driver = kernel.attach(
            cdp_port,
            kernel.chromedriver_path(core_version or "135.0.7049.115"),
        )
        if not kernel.is_tiktok_seller_center(driver.current_url):
            kernel.open_store(driver, seller_center_url)
        kernel.activate_tiktok_profile_for_url(driver.current_url)
        # TikTok can inject its full-screen challenge shortly after the normal
        # Seller Center shell renders.  Give that async check time to settle so
        # it is not mistaken for a missing Shop ads menu.
        time.sleep(2)
        if kernel.verification_present(driver):
            print(
                "AD_COST_SMOKE_MANUAL_VERIFICATION_REQUIRED: "
                "complete the visible challenge in the exact Feikua window.",
                flush=True,
            )
            if not kernel.wait_for_human_takeover(driver, timeout=45):
                manual_verification_pending = True
                print(
                    "AD_COST_SMOKE_MANUAL_VERIFICATION_PENDING_WINDOW_PRESERVED",
                    flush=True,
                )
                return 23

        results = export_all_ad_cost_accounts(
            driver=driver,
            seller_center_url=seller_center_url,
            shop_name=store["shop_name"],
            start_date=start_date,
            end_date=end_date,
            output_dir=Path(args.out).expanduser(),
        )
        print(
            "AD_COST_SMOKE_COMPLETE_JSON:",
            json.dumps(
                {
                    "shopName": store["shop_name"],
                    "shopId": store["shop_id"],
                    "startDate": start_date.isoformat(),
                    "endDate": end_date.isoformat(),
                    "results": results,
                },
                ensure_ascii=False,
                separators=(",", ":"),
            ),
            flush=True,
        )
        success = True
        return 0
    except Exception as error:
        print(
            f"AD_COST_SMOKE_FAILED: {type(error).__name__}: {error}",
            flush=True,
        )
        traceback.print_exc()
        return 1
    finally:
        if driver is not None:
            if args.keep_open or manual_verification_pending:
                try:
                    driver.service.stop()
                except Exception:
                    pass
                print("AD_COST_SMOKE_DETACHED_WINDOW_PRESERVED", flush=True)
            else:
                try:
                    driver.quit()
                except Exception:
                    pass
        if not args.keep_open and not manual_verification_pending:
            try:
                kernel.request_store_stop(base_url, store["shop_id"])
                print(
                    "AD_COST_SMOKE_STOP_REQUESTED_AFTER_"
                    + ("SUCCESS" if success else "FAILURE"),
                    flush=True,
                )
            except Exception as stop_error:
                print(
                    "AD_COST_SMOKE_STOP_REQUEST_FAILED: "
                    f"{type(stop_error).__name__}: {stop_error}",
                    flush=True,
                )


if __name__ == "__main__":
    raise SystemExit(main())
