"""Read the official job-management list, including jobs with no conversations."""
import json
import os
from pathlib import Path
import tempfile
import time

URL = "https://www.zhipin.com/wapi/zpjob/job/data/list"
REFERER = "https://www.zhipin.com/web/frame/job_v2/list"
STATUS_TTL = 300


def save_statuses(root, result):
    """Account-local, complete official snapshots only; never trust UI rules."""
    path = Path(root) / "managed-job-statuses.json"
    now = time.time()
    valid = result.get("ok") is True
    rows = result.get("data", {}).get("jobs") if valid else None
    valid = valid and isinstance(rows, list) and result["data"].get("hasMore") is False
    values = {row["encryptJobId"]: row["statusDesc"] for row in rows} if valid else {}
    snapshot = {"statuses": values, "checkedAt": now,
                "validUntil": now + STATUS_TTL if valid else 0, "retryAt": now + STATUS_TTL}
    # Publish atomically; failed or incomplete reads invalidate older evidence.
    fd, temp = tempfile.mkstemp(prefix=".managed-jobs-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as output:
            json.dump(snapshot, output, ensure_ascii=False)
        os.replace(temp, path)
    finally:
        Path(temp).unlink(missing_ok=True)
    return snapshot


def availability(c, root, *, user_initiated=False):
    """One budgeted refresh per five minutes, fail closed without tight retries."""
    now = time.time()
    try:
        snapshot = json.loads((Path(root) / "managed-job-statuses.json").read_text(encoding="utf-8"))
        fresh = snapshot["checkedAt"] <= now < snapshot["validUntil"]
        if fresh:
            return {"statuses": snapshot["statuses"], "retryAt": snapshot["validUntil"]}
        if now < snapshot["retryAt"]:
            return {"statuses": {}, "retryAt": snapshot["retryAt"]}
    except (OSError, ValueError, KeyError, TypeError):
        pass
    delay = c.passive_delay(cost=2, queued=user_initiated, user_initiated=user_initiated)
    if delay:
        return {"statuses": {}, "retryAt": now + delay}
    # Reserve the refresh cooldown before network I/O, including process death.
    save_statuses(root, {"ok": False})
    try:
        snapshot = save_statuses(root, c.read_managed_jobs())
    except Exception:
        # Existing SDK risk/session holds remain authoritative. The caller
        # checks them before any receipt read or write. No fallback or replay.
        return {"statuses": {}, "retryAt": now + STATUS_TTL}
    return {"statuses": snapshot["statuses"], "retryAt": snapshot["retryAt"]}


def status(row):
    audit, state = row.get("jobAuditStatus"), row.get("jobStatus")
    if row.get("jobGiftExposureRights"): return "免费曝光中"
    if row.get("jobFreeProlongExposure"): return "延长曝光中"
    if audit == 6 or state == 0 and audit == 0: return "审核中"
    if state in (0, 2) and audit in (0, 1, 3, 4): return "开放中"
    if audit in (2, 8): return "审核不通过"
    if audit == 5: return "已失效"
    if audit == 9: return "待贵司审核"
    if state == 1: return "已关闭"
    if audit == 7: return "修改审核中"
    if state == 3: return "待开放"
    if row.get("hot") and row.get("freeExperience"): return "竞招职位体验中"
    return "状态待确认"


def failure(code="JOB_LIST_INCOMPLETE"):
    return {"ok": False, "error": {"code": code,
            "message": "职位列表未完整读取，已保留当前列表；请稍后刷新，系统没有自动重试"}}


def listing(client):
    jobs, seen, page = [], set(), 1
    while True:
        # The existing guarded SDK retains its session check, shared budget,
        # rate limit, audit, and stop-on-risk behavior for every page.
        raw = client._request("GET", URL,
                              params={"position": 0, "type": 0, "searchStr": "",
                                      "comId": "", "tagIdStr": "", "page": page},
                              extra_headers={"Referer": REFERER}, retry=False)
        if not isinstance(raw, dict) or raw.get("code") != 0:
            return failure()
        data = raw.get("zpData")
        if not isinstance(data, dict) or not isinstance(data.get("data"), list):
            return failure()
        added = 0
        for row in data["data"]:
            if not isinstance(row, dict) or not isinstance(row.get("encryptJobId"), str) or not row["encryptJobId"]:
                return failure()
            identity = row["encryptJobId"]
            if identity in seen: continue
            seen.add(identity)
            job = {key: row[key] for key in ("encryptJobId", "jobName", "salaryDesc", "experienceName",
                   "degreeName", "jobStatus", "jobAuditStatus") if key in row}
            job["address"] = row.get("addressShowText", "")
            job["statusDesc"] = status(row)
            jobs.append(job)
            added += 1
        more = data.get("hasMore")
        if more is not False and more is not True: return failure()
        if not more:
            return {"ok": True, "command": "recruiter-jobs-list",
                    "data": {"jobs": jobs, "hasMore": False, "total": len(jobs),
                             "pagesRead": page, "source": "job-management"}}
        if not added: return failure()
        page += 1
