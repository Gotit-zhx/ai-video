"""Read the current enterprise's authorized directory; never send messages."""
import hashlib
import math
import re
import threading
import time

import dingtalk_interviews as dt
import talent_pool as pool

LOCK = threading.Lock()
CACHE = {}
TTL = 600
MAX_DEPARTMENTS = 2000
MAX_EMPLOYEES = 30000
MAX_CALLS = 2000
MAX_SECONDS = 240


def key_for(owner, config):
    digest = hashlib.sha256((config["app_key"] + config["secret"]).encode()).hexdigest()
    return owner, config["version"], digest


def sync(key, config, entry):
    """Traverse all visible departments and every employee page, with loop guards."""
    departments = {1: {"id": "1", "name": "公司直属人员", "parentId": ""}}
    employees = {}
    calls = 0
    started = time.monotonic()

    def read(path, body):
        nonlocal calls
        calls += 1
        if calls > MAX_CALLS or time.monotonic() - started > MAX_SECONDS:
            raise ValueError("通讯录读取耗时过长或规模超出上限，请缩小应用通讯录范围后刷新")
        return dt.api(path, body, dt.token(config)).get("result")

    def progress():
        with LOCK:
            entry.update(departmentCount=len(departments), employeeCount=len(employees))

    try:
        queue = [1]
        for dept_id in queue:
            if key_for(key[0], dt.read_config(key[0])) != key:
                raise pool.Conflict("钉钉应用已更新，请重新打开二面资料")
            children = read("topapi/v2/department/listsub", {"dept_id": dept_id, "language": "zh_CN"})
            if not isinstance(children, list):
                raise ValueError("钉钉部门列表格式异常，请手动刷新")
            for child in children:
                cid = child.get("dept_id") if isinstance(child, dict) else None
                if type(cid) is not int or cid < 1 or not isinstance(child.get("name"), str):
                    raise ValueError("钉钉部门信息不完整，请手动刷新")
                if cid in departments:
                    raise ValueError("钉钉部门列表出现重复或循环，请手动刷新")
                if len(departments) >= MAX_DEPARTMENTS:
                    raise ValueError("部门数量超出读取上限，请缩小应用通讯录范围")
                departments[cid] = {"id": str(cid), "name": pool.text(child["name"], 100),
                                    "parentId": str(dept_id)}
                queue.append(cid)
            cursor, seen = 0, set()
            while True:
                if cursor in seen:
                    raise ValueError("钉钉人员分页没有前进，请手动刷新")
                seen.add(cursor)
                result = read("topapi/v2/user/list", {"dept_id": dept_id, "cursor": cursor,
                                                     "size": 100, "language": "zh_CN"})
                if not isinstance(result, dict) or not isinstance(result.get("list"), list):
                    raise ValueError("钉钉人员列表格式异常，请手动刷新")
                for row in result["list"]:
                    uid = row.get("userid") if isinstance(row, dict) else None
                    if (not isinstance(uid, str) or not re.fullmatch(r"[\w@.+~-]{1,100}", uid)
                            or not isinstance(row.get("name"), str) or not row["name"].strip()):
                        raise ValueError("钉钉人员身份信息不完整，请手动刷新")
                    if uid not in employees:
                        if len(employees) >= MAX_EMPLOYEES:
                            raise ValueError("人员数量超出读取上限，请缩小应用通讯录范围")
                        employees[uid] = {"userId": uid, "name": pool.text(row["name"], 100),
                                          "departmentIds": []}
                    if str(dept_id) not in employees[uid]["departmentIds"]:
                        employees[uid]["departmentIds"].append(str(dept_id))
                progress()
                more = result.get("has_more")
                if type(more) is not bool:
                    raise ValueError("钉钉人员分页信息缺失，请手动刷新")
                if not more:
                    break
                cursor = result.get("next_cursor")
                if type(cursor) is not int or cursor < 0:
                    raise ValueError("钉钉人员分页游标异常，请手动刷新")
        for person in employees.values():
            person["department"] = " / ".join(departments[int(d)]["name"] for d in person["departmentIds"])
        with LOCK:
            entry.update(state="complete", items=sorted(employees.values(), key=lambda p: (p["name"], p["userId"])),
                         departments=list(departments.values()), syncedAt=time.time())
    except Exception as error:
        # Only our safe validation/API messages may reach the UI.
        message = str(error) if isinstance(error, (dt.DingError, ValueError)) else "通讯录暂时无法读取，请稍后手动刷新"
        with LOCK:
            entry.update(state="failed", error=message, items=[], departments=[], syncedAt=time.time())


def launch(key, config, entry):
    threading.Thread(target=sync, args=(key, config, entry), daemon=True,
                     name="dingtalk-directory").start()


def snapshot(user, query):
    """Internal full snapshot for department sync; no employee pages are skipped."""
    owner, _ = pool.context(user)
    config = dt.read_config(owner)
    if str(config["version"]) != str(query.get("configVersion", "")):
        raise pool.Conflict("钉钉应用已更新，请刷新后重试")
    key = key_for(owner, config)
    with LOCK:
        entry = CACHE.get(key)
        expired = entry and entry["state"] == "complete" and time.time() - entry["syncedAt"] >= TTL
        if not entry or (entry["state"] != "running" and (expired or query.get("refresh") == "1")):
            # Retain at most eight enterprises/configurations; active workers stay bounded.
            for old_key in list(CACHE):
                if old_key[0] == owner and old_key != key and CACHE[old_key]["state"] != "running":
                    CACHE.pop(old_key)
            while len(CACHE) >= 8:
                victim = next((k for k, v in CACHE.items() if v["state"] != "running"), None)
                if victim is None:
                    raise ValueError("通讯录正在读取，请稍后再试")
                CACHE.pop(victim)
            entry = {"state": "running", "items": [], "departments": [], "departmentCount": 0,
                     "employeeCount": 0, "syncedAt": 0, "error": ""}
            CACHE[key] = entry
            launch(key, config, entry)
        value = dict(entry)
    # Reject stale credentials even if the configuration changed during a read.
    if key_for(owner, dt.read_config(owner)) != key:
        raise pool.Conflict("钉钉应用已更新，请刷新后重试")
    return {**value, "configVersion": config["version"], "appKey": config["app_key"]}


def listing(user, query):
    keyword = pool.text(query.get("q", ""), 100).casefold()
    department = pool.text(query.get("departmentId", ""), 30)
    try:
        page = int(query.get("page", 1))
    except (ValueError, TypeError):
        raise ValueError("通讯录页码不正确") from None
    if not 1 <= page <= 100000:
        raise ValueError("通讯录页码不正确")
    value = snapshot(user, query)
    matches = [p for p in value["items"] if (not department or department in p["departmentIds"])
               and (not keyword or keyword in p["name"].casefold() or keyword in p["department"].casefold())]
    pages = max(1, math.ceil(len(matches) / 20))
    page = min(page, pages)
    return {**{k: value[k] for k in ("state", "error", "syncedAt", "departmentCount", "employeeCount")},
            "configVersion": value["configVersion"], "departments": value["departments"],
            "items": matches[(page-1)*20:page*20], "total": len(matches), "page": page, "pages": pages}
