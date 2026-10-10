"""拾间: private, single-household chore tracking. Run one worker with threads."""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import logging
import os
import re
import secrets
import sqlite3
import threading
import time
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from flask import Flask, g, jsonify, make_response, request, send_from_directory
from werkzeug.middleware.proxy_fix import ProxyFix

try:
    from pywebpush import webpush, WebPushException
except ImportError:
    webpush = None
    WebPushException = Exception

try:
    from mailer import send_email, email_delivery_status
except ImportError:
    def email_delivery_status():
        return {"configured": False}

    def send_email(*_args, **_kwargs):
        raise RuntimeError("邮件服务尚未配置")

PERIODS = {"morning": "其他时段", "evening": "晚上集中收拾", "bedtime": "睡前检查"}
DEFAULT_SETTINGS = {
    "timezone": "Asia/Shanghai",
    "reminderTimes": {"morning": "20:30", "evening": "20:30", "bedtime": "23:00"},
    "remindersEnabled": False,
    "householdName": "我的家",
    "emailEnabled": False,
    "emailTo": "",
    "emailTime": "23:00",
}
SESSION_DAYS = 90
PASSWORD_ROUNDS = 600_000
COOKIE_NAME = "homecare_session"


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def new_id():
    return secrets.token_hex(12)


def seeds():
    rows = [
        ("床铺归位", "卧室", "evening", 2, False, ["被子铺开、枕头归位", "床上只留寝具，衣物放回衣柜或脏衣篮"]),
        ("洗漱台顺手收尾", "卫生间", "evening", 2, False, ["冲净台盆里的牙膏和毛发", "擦掉龙头、台面明显水渍，洗漱用品归位"]),
        ("餐具与水槽清零", "厨房", "evening", 8, True, ["剩菜密封冷藏，餐具洗净或放入洗碗机启动清洗", "滤网食物残渣倒进厨余垃圾，水槽冲净", "抹布洗净拧干并展开晾挂"]),
        ("灶台与餐桌擦净", "厨房", "evening", 5, False, ["等灶台冷却后擦去油点与汤汁", "餐桌杂物归位，桌面和边沿擦到无残渣", "桌下明显掉落的食物扫净"]),
        ("检查厨余与垃圾", "全屋", "evening", 3, True, ["检查厨余、异味或已满的垃圾袋，需要时扎紧带走", "检查垃圾桶底部有无渗漏，换上干净垃圾袋", "无需倒垃圾时，也确认袋口与桶边干净"]),
        ("地面与物品归位", "客厅", "bedtime", 5, True, ["用五分钟把衣物、杯子、快递和零散物品放回固定位置", "只处理看得到的毛发、碎屑，清出明天走路的通道", "给明天要带的钥匙与包找好固定位置"]),
        ("睡前卫生间检查", "卫生间", "bedtime", 2, False, ["地面明显积水擦干，毛巾展开晾好", "确认卫生纸、洗手液够用，缺的加入采购清单"]),
    ]
    result = []
    for title, area, period, minutes, essential, steps in rows:
        result.append(dict(id=new_id(), title=title, area=area, period=period, minutes=minutes,
                           frequency="daily", weekdays=[], essential=essential, enabled=True,
                           steps=[{"id": new_id(), "text": s} for s in steps]))
    for title, area, minutes, weekdays, steps in [
        ("马桶完整清洁", "卫生间", 8, [2], ["通风，按清洁剂说明刷洗马桶内壁和水线", "分别擦净按钮、圈盖、座圈和外壁，清洁工具冲净晾干", "只用一种清洁剂，含氯产品不与酸性产品混用"]),
        ("全屋吸尘与拖地", "全屋", 15, [5], ["先清理地面物品，再吸尘，覆盖床边、桌下与墙角", "用适合地板的方式拖地，地面不过湿", "清空尘盒、洗净拖布并展开晾干"]),
        ("换洗床品与毛巾", "卧室", 12, [6], ["拆下本周使用的床单、枕套与需更换的毛巾", "按洗标分类清洗并及时晾晒或烘干", "换上干净床品，晾干的织物再收纳"]),
    ]:
        result.append(dict(id=new_id(), title=title, area=area, period="evening", minutes=minutes,
                           frequency="weekly", weekdays=weekdays, essential=False, enabled=True,
                           steps=[{"id": new_id(), "text": s} for s in steps]))
    return result


def create_app(test_config=None):
    app = Flask(__name__, static_folder=None)
    app.config.update(
        DATA_DIR=os.environ.get("DATA_DIR", str(Path(__file__).parent / "data")),
        PUBLIC_URL=os.environ.get("PUBLIC_URL", "").rstrip("/") + ("/" if os.environ.get("PUBLIC_URL") else ""),
        HOMECARE_PASSWORD=os.environ.get("HOMECARE_PASSWORD"),
        TRUST_PROXY=os.environ.get("TRUST_PROXY", "0") == "1",
        SCHEDULER_ENABLED=os.environ.get("SCHEDULER_ENABLED", "1") == "1",
        MAX_CONTENT_LENGTH=64 * 1024,
        TESTING=False,
    )
    if test_config:
        app.config.update(test_config)
    if app.config["TRUST_PROXY"]:
        app.wsgi_app = ProxyFix(app.wsgi_app, x_for=1, x_proto=1, x_host=0, x_port=0, x_prefix=1)
    data_dir = Path(app.config["DATA_DIR"])
    data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    app.config["DATABASE"] = str(data_dir / "homecare.sqlite3")
    public_parts = urlsplit(app.config["PUBLIC_URL"])
    if app.config["PUBLIC_URL"] and (public_parts.scheme not in {"http", "https"} or not public_parts.netloc or public_parts.query or public_parts.fragment):
        raise ValueError("PUBLIC_URL 必须是包含协议的站点地址，可带路径前缀")
    cookie_path = (public_parts.path or "/").rstrip("/") + "/"

    def db():
        if "db" not in g:
            g.db = sqlite3.connect(app.config["DATABASE"], timeout=15)
            g.db.row_factory = sqlite3.Row
            g.db.execute("PRAGMA foreign_keys=ON")
            g.db.execute("PRAGMA busy_timeout=15000")
        return g.db

    @app.teardown_appcontext
    def close_db(_error):
        connection = g.pop("db", None)
        if connection is not None:
            connection.close()

    def settings():
        return json.loads(db().execute("SELECT value FROM meta WHERE key='settings'").fetchone()[0])

    def now_local():
        return datetime.now(ZoneInfo(settings()["timezone"]))

    def today():
        return now_local().date()

    def get_tasks(include_archived=False):
        rows = db().execute("SELECT data FROM tasks ORDER BY position,id").fetchall()
        return [t for row in rows if (t := json.loads(row[0]))["enabled"] or include_archived]

    def due(task, day):
        return task["frequency"] == "daily" or day.weekday() in task["weekdays"]

    def task_by_id(task_id, enabled=True):
        if not isinstance(task_id, str) or not re.fullmatch(r"[a-f0-9]{24}", task_id):
            raise ApiError("家务编号无效")
        row = db().execute("SELECT data FROM tasks WHERE id=?", (task_id,)).fetchone()
        if not row or (enabled and not json.loads(row[0])["enabled"]):
            raise ApiError("这项家务已归档或不存在", 404)
        return json.loads(row[0])

    def progress_for(day):
        result = {}
        for row in db().execute("SELECT task_id,step_id FROM progress WHERE date=? AND done=1", (day,)):
            result.setdefault(row["task_id"], {"steps": [], "skipped": False})["steps"].append(row["step_id"])
        for row in db().execute("SELECT task_id FROM skips WHERE date=? AND skipped=1", (day,)):
            result.setdefault(row["task_id"], {"steps": [], "skipped": False})["skipped"] = True
        return result

    def day_tasks(day):
        rows = db().execute("SELECT id,data FROM tasks WHERE (created_date<=? OR ?=?) AND (archived_date IS NULL OR archived_date>?) ORDER BY position,id", (day.isoformat(), day.isoformat(), today().isoformat(), day.isoformat())).fetchall()
        snapshots = {r["task_id"]: json.loads(r["data"]) for r in db().execute("SELECT task_id,data FROM day_tasks WHERE date=?", (day.isoformat(),))}
        tasks = [snapshots.get(r["id"], json.loads(r["data"])) for r in rows]
        return [{**t, "enabled": True} for t in tasks if due(t, day)]

    def history():
        output = []
        for n in range(6, -1, -1):
            day = today() - timedelta(days=n)
            progress = progress_for(day.isoformat())
            total = done = minutes = 0
            for task in day_tasks(day):
                p = progress.get(task["id"], {"steps": [], "skipped": False})
                if p["skipped"]:
                    continue
                total += 1
                if all(s["id"] in p["steps"] for s in task["steps"]):
                    done += 1
                    minutes += task["minutes"]
            output.append({"date": day.isoformat(), "done": done, "total": total, "minutes": minutes})
        return output

    def json_body():
        if not request.is_json:
            raise ApiError("请使用 JSON 格式提交", 415)
        body = request.get_json(silent=True)
        if not isinstance(body, dict):
            raise ApiError("提交内容格式不正确")
        return body

    def parse_day(value, writable=False):
        if not isinstance(value, str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
            raise ApiError("日期格式应为 YYYY-MM-DD")
        try:
            day = date.fromisoformat(value)
        except ValueError:
            raise ApiError("日期无效")
        if writable and not today() - timedelta(days=30) <= day <= today():
            raise ApiError("只能修改今天和过去 30 天的记录")
        return day

    def snapshot(day, task):
        db().execute("INSERT OR IGNORE INTO day_tasks(date,task_id,data) VALUES(?,?,?)", (day, task["id"], compact(task)))

    @app.errorhandler(ApiError)
    def api_error(error):
        response = jsonify(error=error.message)
        if error.status == 429:
            response.headers["Retry-After"] = "900"
        return response, error.status

    @app.errorhandler(413)
    def too_large(_error):
        return jsonify(error="提交内容太大"), 413

    @app.before_request
    def secure_requests():
        if request.path.startswith("/api/"):
            if request.method not in {"GET", "HEAD", "OPTIONS"}:
                if request.headers.get("X-Requested-With") != "HomeCare":
                    raise ApiError("请求来源无效，请刷新页面重试", 403)
                origin = request.headers.get("Origin")
                expected = public_parts.scheme + "://" + public_parts.netloc if app.config["PUBLIC_URL"] else request.host_url.rstrip("/")
                if origin is not None and origin != expected:
                    raise ApiError("仅接受本站请求", 403)
                if not request.is_json:
                    raise ApiError("请使用 JSON 格式提交", 415)
            if request.path != "/api/login":
                token = request.cookies.get(COOKIE_NAME, "")
                token_hash = hashlib.sha256(token.encode()).hexdigest()
                if not token or not db().execute("SELECT 1 FROM sessions WHERE token_hash=? AND expires>?", (token_hash, time.time())).fetchone():
                    raise ApiError("请先输入家庭访问密码", 401)
                g.session_hash = token_hash

    @app.after_request
    def headers(response):
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "same-origin"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=()"
        response.headers["Content-Security-Policy"] = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; font-src 'self'; worker-src 'self'; manifest-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
        if request.path.startswith("/api/"):
            response.headers["Cache-Control"] = "no-store"
            response.headers["Vary"] = "Cookie"
        if request.path in {"/", "/sw.js", "/index.html"}:
            response.headers["Cache-Control"] = "no-cache"
        return response

    @app.post("/api/login")
    def login():
        body = json_body()
        password = body.get("password")
        if not isinstance(password, str) or len(password) > 512:
            raise ApiError("请输入访问密码")
        connection = db()
        ip_hash = hashlib.sha256((request.remote_addr or "unknown").encode()).hexdigest()
        cutoff = time.time() - 900
        # Check and reserve the attempt in one transaction, including parallel logins.
        connection.execute("BEGIN IMMEDIATE")
        connection.execute("DELETE FROM login_attempts WHERE timestamp<?", (cutoff,))
        count = connection.execute("SELECT COUNT(*) FROM login_attempts WHERE ip_hash=?", (ip_hash,)).fetchone()[0]
        global_count = connection.execute("SELECT COUNT(*) FROM login_attempts").fetchone()[0]
        if count >= 8 or global_count >= 60:
            connection.rollback()
            raise ApiError("尝试次数较多，请 15 分钟后再试", 429)
        cursor = connection.execute("INSERT INTO login_attempts(ip_hash,timestamp) VALUES(?,?)", (ip_hash, time.time()))
        attempt_id = cursor.lastrowid
        connection.commit()
        auth = json.loads(connection.execute("SELECT value FROM meta WHERE key='auth'").fetchone()[0])
        digest = hashlib.pbkdf2_hmac("sha256", password.encode(), bytes.fromhex(auth["salt"]), auth["rounds"]).hex()
        if not hmac.compare_digest(digest, auth["hash"]):
            raise ApiError("访问密码不正确", 401)
        token = secrets.token_urlsafe(48)
        with connection:
            connection.execute("DELETE FROM login_attempts WHERE id=?", (attempt_id,))
            connection.execute("DELETE FROM sessions WHERE expires<?", (time.time(),))
            connection.execute("INSERT INTO sessions(token_hash,expires) VALUES(?,?)", (hashlib.sha256(token.encode()).hexdigest(), time.time() + SESSION_DAYS * 86400))
        response = jsonify(ok=True)
        response.set_cookie(COOKIE_NAME, token, max_age=SESSION_DAYS * 86400, httponly=True,
                            secure=public_parts.scheme == "https" or request.is_secure, samesite="Lax", path=cookie_path)
        return response

    @app.post("/api/logout")
    def logout():
        json_body()
        with db():
            db().execute("DELETE FROM sessions WHERE token_hash=?", (g.session_hash,))
        response = jsonify(ok=True)
        response.delete_cookie(COOKIE_NAME, path=cookie_path, httponly=True, secure=public_parts.scheme == "https" or request.is_secure, samesite="Lax")
        return response

    @app.get("/api/state")
    def state():
        selected = parse_day(request.args.get("date", today().isoformat()))
        return jsonify(today=today().isoformat(), date=selected.isoformat(), tasks=day_tasks(selected) if selected < today() else get_tasks(),
                       progress=progress_for(selected.isoformat()), settings=settings(), history=history(),
                       email={"supported": bool(email_delivery_status().get("configured"))},
                       push={"supported": bool(webpush and app.config.get("VAPID_PUBLIC_KEY")),
                             "publicKey": app.config.get("VAPID_PUBLIC_KEY", ""),
                             "subscriptions": db().execute("SELECT COUNT(*) FROM subscriptions").fetchone()[0]})

    def progress_input(body):
        day = parse_day(body.get("date"), writable=True)
        task = task_by_id(body.get("taskId"), enabled=day == today())
        if day < today():
            task = next((t for t in day_tasks(day) if t["id"] == task["id"]), None)
            if task is None:
                raise ApiError("这项家务不在当天计划中")
        if not due(task, day):
            raise ApiError("这项家务不在当天计划中")
        return day.isoformat(), task

    @app.put("/api/progress")
    def update_progress():
        body = json_body()
        day, task = progress_input(body)
        step_id = body.get("stepId")
        if not isinstance(step_id, str) or step_id not in {s["id"] for s in task["steps"]}:
            raise ApiError("该步骤已变更，请刷新家务列表")
        done = boolean(body.get("done"), "完成状态")
        with db():
            snapshot(day, task)
            db().execute("INSERT INTO progress(date,task_id,step_id,done,updated_at) VALUES(?,?,?,?,?) ON CONFLICT(date,task_id,step_id) DO UPDATE SET done=excluded.done,updated_at=excluded.updated_at", (day, task["id"], step_id, int(done), time.time()))
            if done:
                db().execute("INSERT INTO skips(date,task_id,skipped) VALUES(?,?,0) ON CONFLICT(date,task_id) DO UPDATE SET skipped=0", (day, task["id"]))
        return jsonify(ok=True)

    @app.put("/api/skip")
    def update_skip():
        body = json_body()
        day, task = progress_input(body)
        skipped = boolean(body.get("skipped"), "跳过状态")
        with db():
            snapshot(day, task)
            db().execute("INSERT INTO skips(date,task_id,skipped) VALUES(?,?,?) ON CONFLICT(date,task_id) DO UPDATE SET skipped=excluded.skipped", (day, task["id"], int(skipped)))
        return jsonify(ok=True)

    def validated_task(body, existing=None):
        allowed = {"title", "area", "period", "minutes", "frequency", "weekdays", "essential", "enabled", "steps"}
        if set(body) - allowed:
            raise ApiError("包含不支持的家务字段")
        base = dict(existing or {"area": "全屋", "period": "evening", "minutes": 5, "frequency": "daily", "weekdays": [], "essential": False, "enabled": True})
        base.update(body)
        base["title"] = short_text(base.get("title"), "家务名称", 80)
        base["area"] = short_text(base.get("area"), "区域", 30)
        if not isinstance(base["period"], str) or base["period"] not in PERIODS:
            raise ApiError("家务时段无效")
        if type(base["minutes"]) is not int or not 1 <= base["minutes"] <= 180:
            raise ApiError("预计时长应为 1 到 180 分钟")
        if not isinstance(base["frequency"], str) or base["frequency"] not in {"daily", "weekly"}:
            raise ApiError("重复频率无效")
        weekdays = base["weekdays"]
        if not isinstance(weekdays, list) or any(type(v) is not int or v not in range(7) for v in weekdays) or len(weekdays) > 7:
            raise ApiError("每周日期应为 0 到 6，周一为 0")
        base["weekdays"] = sorted(set(weekdays)) if base["frequency"] == "weekly" else []
        if base["frequency"] == "weekly" and not base["weekdays"]:
            raise ApiError("每周家务至少选择一天")
        for key in ("essential", "enabled"):
            base[key] = boolean(base[key], key)
        if "steps" in body or not existing:
            values = base.get("steps")
            if not isinstance(values, list) or not 1 <= len(values) <= 20:
                raise ApiError("每项家务需要 1 到 20 个步骤")
            texts = [short_text(v, "步骤", 200) for v in values]
            if len(set(texts)) != len(texts):
                raise ApiError("步骤内容不能重复")
            old_ids = {s["text"]: s["id"] for s in existing["steps"]} if existing else {}
            base["steps"] = [{"id": old_ids.get(v, new_id()), "text": v} for v in texts]
        base["id"] = existing["id"] if existing else new_id()
        return base

    @app.post("/api/tasks")
    def add_task():
        task = validated_task(json_body())
        with db():
            db().execute("BEGIN IMMEDIATE")
            if sum(t["enabled"] for t in get_tasks(True)) >= 80:
                raise ApiError("最多保留 80 项启用中的家务")
            position = db().execute("SELECT COALESCE(MAX(position),0)+1 FROM tasks").fetchone()[0]
            db().execute("INSERT INTO tasks(id,data,created_date,position,archived_date) VALUES(?,?,?,?,?)", (task["id"], compact(task), today().isoformat(), position, None if task["enabled"] else today().isoformat()))
        return jsonify(task=task), 201

    @app.put("/api/tasks/<task_id>")
    def edit_task(task_id):
        body = json_body()
        with db():
            db().execute("BEGIN IMMEDIATE")
            existing = task_by_id(task_id, enabled=False)
            task = validated_task(body, existing)
            # Freeze earlier plans before altering an item. Today's checklist follows edits.
            created = date.fromisoformat(db().execute("SELECT created_date FROM tasks WHERE id=?", (task_id,)).fetchone()[0])
            for n in range(1, 31):
                day = today() - timedelta(days=n)
                if day >= created:
                    snapshot(day.isoformat(), existing)
            db().execute("UPDATE day_tasks SET data=? WHERE date=? AND task_id=?", (compact(task), today().isoformat(), task_id))
            db().execute("UPDATE tasks SET data=?,archived_date=? WHERE id=?", (compact(task), None if task["enabled"] else today().isoformat(), task_id))
        return jsonify(task=task)

    @app.delete("/api/tasks/<task_id>")
    def archive_task(task_id):
        json_body()
        task = task_by_id(task_id, enabled=False)
        task["enabled"] = False
        with db():
            db().execute("UPDATE tasks SET data=?,archived_date=COALESCE(archived_date,?) WHERE id=?", (compact(task), today().isoformat(), task_id))
        return jsonify(ok=True)

    @app.put("/api/settings")
    def save_settings():
        body = json_body()
        if set(body) - set(DEFAULT_SETTINGS):
            raise ApiError("包含不支持的设置字段")
        with db():
            db().execute("BEGIN IMMEDIATE")
            value = settings()
            value.update(body)
            value["householdName"] = short_text(value["householdName"], "家庭名称", 40)
            if not isinstance(value["timezone"], str) or len(value["timezone"]) > 80:
                raise ApiError("时区无效")
            try:
                ZoneInfo(value["timezone"])
            except (ZoneInfoNotFoundError, ValueError):
                raise ApiError("请选择有效的 IANA 时区")
            times = value["reminderTimes"]
            if not isinstance(times, dict) or set(times) != set(PERIODS) or any(not isinstance(v, str) or not re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", v) for v in times.values()):
                raise ApiError("三个提醒时间都应为 HH:MM 格式")
            value["remindersEnabled"] = boolean(value["remindersEnabled"], "提醒开关")
            value["emailEnabled"] = boolean(value["emailEnabled"], "邮件开关")
            value["emailTo"] = validate_email(value["emailTo"], allow_empty=not value["emailEnabled"])
            if not isinstance(value["emailTime"], str) or not re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", value["emailTime"]):
                raise ApiError("邮件检查时间应为 HH:MM 格式")
            db().execute("UPDATE meta SET value=? WHERE key='settings'", (compact(value),))
        return jsonify(settings=value)

    @app.post("/api/push/subscribe")
    def subscribe():
        if not webpush or not app.config.get("VAPID_PUBLIC_KEY"):
            raise ApiError("此服务器暂不支持推送提醒", 503)
        body = validate_subscription(json_body())
        with db():
            if db().execute("SELECT COUNT(*) FROM subscriptions").fetchone()[0] >= 20 and not db().execute("SELECT 1 FROM subscriptions WHERE endpoint=?", (body["endpoint"],)).fetchone():
                raise ApiError("已达到 20 个提醒设备上限")
            db().execute("INSERT INTO subscriptions(endpoint,data,created_at) VALUES(?,?,?) ON CONFLICT(endpoint) DO UPDATE SET data=excluded.data", (body["endpoint"], compact(body), time.time()))
        return jsonify(ok=True)

    @app.post("/api/push/unsubscribe")
    def unsubscribe():
        endpoint = json_body().get("endpoint")
        if not isinstance(endpoint, str) or len(endpoint) > 4096:
            raise ApiError("订阅地址无效")
        with db():
            db().execute("DELETE FROM subscriptions WHERE endpoint=?", (endpoint,))
        return jsonify(ok=True)

    def send_push(title, body, scheduled=None, at_epoch=None):
        sent = 0
        if not webpush or not app.config.get("VAPID_PUBLIC_KEY"):
            return sent
        at_epoch = time.time() if at_epoch is None else at_epoch
        for row in db().execute("SELECT endpoint,data FROM subscriptions").fetchall():
            delivery_key = None
            if scheduled:
                delivery_key = (*scheduled, hashlib.sha256(row["endpoint"].encode()).hexdigest())
                with db():
                    db().execute("INSERT OR IGNORE INTO notification_deliveries(date,period,endpoint_hash,status,attempts,next_attempt,updated_at) VALUES(?,?,?,'pending',0,0,?)", (*delivery_key, at_epoch))
                    claimed = db().execute("UPDATE notification_deliveries SET status='sending',attempts=attempts+1,updated_at=? WHERE date=? AND period=? AND endpoint_hash=? AND status IN ('pending','failed') AND attempts<3 AND next_attempt<=?", (at_epoch, *delivery_key, at_epoch)).rowcount
                if not claimed:
                    continue
            try:
                extra = {"headers": {"Topic": hashlib.sha256(("-".join(scheduled)).encode()).hexdigest()[:32]}} if scheduled else {}
                webpush(subscription_info=json.loads(row["data"]),
                        data=compact({"title": title, "body": body, "url": app.config["PUBLIC_URL"] or "/"}),
                        vapid_private_key=app.config["VAPID_PRIVATE_KEY"],
                        vapid_claims={"sub": os.environ.get("VAPID_SUBJECT") or app.config["PUBLIC_URL"] or "mailto:homecare@example.com"},
                        ttl=900, timeout=10, **extra)
                sent += 1
                if delivery_key:
                    with db():
                        db().execute("UPDATE notification_deliveries SET status='sent',updated_at=? WHERE date=? AND period=? AND endpoint_hash=?", (at_epoch, *delivery_key))
            except Exception as error:
                status = getattr(getattr(error, "response", None), "status_code", None)
                if status in (404, 410):
                    with db():
                        db().execute("DELETE FROM subscriptions WHERE endpoint=?", (row["endpoint"],))
                if delivery_key:
                    with db():
                        db().execute("UPDATE notification_deliveries SET status=?,next_attempt=?,updated_at=? WHERE date=? AND period=? AND endpoint_hash=?", ("expired" if status in (404, 410) else "failed", at_epoch + 60, at_epoch, *delivery_key))
                app.logger.warning("推送投递失败（HTTP %s）", status or "网络错误")
        return sent

    @app.post("/api/push/test")
    def test_push():
        json_body()
        # Explicit tests are allowed while scheduled reminders remain disabled.
        with db():
            db().execute("BEGIN IMMEDIATE")
            last = db().execute("SELECT value FROM meta WHERE key='last_push_test'").fetchone()
            if last and time.time() - float(last[0]) < 30:
                raise ApiError("请稍等 30 秒再发送测试提醒", 429)
            db().execute("INSERT INTO meta(key,value) VALUES('last_push_test',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (str(time.time()),))
        sent = send_push("拾间 · 提醒已连上", "到时间时，提醒会带你从一个小步骤开始。")
        if not sent:
            raise ApiError("没有成功送达，请先允许此设备通知并检查网络", 503)
        return jsonify(ok=True, sent=sent)

    @app.post("/api/email/test")
    def test_email():
        json_body()
        config = settings()
        recipient = validate_email(config["emailTo"])
        if not email_delivery_status().get("configured"):
            raise ApiError("服务器尚未配置邮件投递服务", 503)
        with db():
            db().execute("BEGIN IMMEDIATE")
            last = db().execute("SELECT value FROM meta WHERE key='last_email_test'").fetchone()
            if last and time.time() - float(last[0]) < 30:
                raise ApiError("请稍等 30 秒再发送测试邮件", 429)
            db().execute("INSERT INTO meta(key,value) VALUES('last_email_test',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (str(time.time()),))
        try:
            send_email(recipient, "拾间 · 未完成家务邮件提醒已连上", "这是一封测试邮件。开启邮件提醒后，拾间将在设置的晚间检查时间检查当天家务；仍有未完成事项时才会发邮件。\n\n打开拾间：" + (app.config["PUBLIC_URL"] or request.url_root))
        except Exception:
            app.logger.warning("测试邮件投递失败")
            raise ApiError("测试邮件暂未送达，请稍后重试", 503)
        return jsonify(ok=True, sent=1)

    def pending_tasks(day, period=None):
        progress = progress_for(day.isoformat())
        result = []
        for task in get_tasks():
            p = progress.get(task["id"], {})
            if (period is not None and task["period"] != period) or not due(task, day) or p.get("skipped", False):
                continue
            remaining = [s["text"] for s in task["steps"] if s["id"] not in p.get("steps", [])]
            if remaining:
                result.append((task, remaining))
        return result

    def email_tick(config, local):
        if not config["emailEnabled"] or not config["emailTo"] or not email_delivery_status().get("configured"):
            return 0
        hour, minute = map(int, config["emailTime"].split(":"))
        scheduled_time = local.replace(hour=hour, minute=minute, second=0, microsecond=0)
        if not 0 <= (local - scheduled_time).total_seconds() < 600:
            return 0
        pending = pending_tasks(local.date())
        if not pending:
            return 0
        day, timestamp = local.date().isoformat(), local.timestamp()
        with db():
            db().execute("INSERT OR IGNORE INTO email_deliveries(date,status,attempts,next_attempt,updated_at) VALUES(?,'pending',0,0,?)", (day, timestamp))
            claimed = db().execute("UPDATE email_deliveries SET status='sending',attempts=attempts+1,updated_at=? WHERE date=? AND status IN ('pending','failed') AND attempts<3 AND next_attempt<=?", (timestamp, day, timestamp)).rowcount
        if not claimed:
            return 0
        text = [f"{config['householdName']}，{day} 的晚间家务检查：", f"还有 {len(pending)} 项未完成（按整项估计约 {sum(t['minutes'] for t, _ in pending)} 分钟）。今天不适用的事项已排除。", ""]
        for task, steps in pending:
            text.append(task["title"] + "：")
            text.extend("  □ " + step for step in steps)
            text.append("")
        text.extend(["忙的时候可以先做保底三件，不用一次做到完美。", "打开拾间继续：" + (app.config["PUBLIC_URL"] or "/"), "", "可在拾间的设置中关闭邮件提醒或调整检查时间。"])
        try:
            send_email(config["emailTo"], f"拾间 · 今晚还有 {len(pending)} 项家务未完成", "\n".join(text))
            with db():
                db().execute("UPDATE email_deliveries SET status='sent',updated_at=? WHERE date=?", (timestamp, day))
            return 1
        except Exception as error:
            with db():
                db().execute("UPDATE email_deliveries SET status=?,next_attempt=?,updated_at=? WHERE date=?", ("failed" if getattr(error, "retryable", True) else "rejected", timestamp + 60, timestamp, day))
            app.logger.warning("未完成家务邮件投递失败，将按配置进行有限重试")
            return 0

    def scheduler_tick(at=None):
        config = settings()
        local = at.astimezone(ZoneInfo(config["timezone"])) if at else now_local()
        sent = email_tick(config, local)
        if not config["remindersEnabled"]:
            return sent
        day = local.date().isoformat()
        for period, reminder_time in config["reminderTimes"].items():
            hour, minute = map(int, reminder_time.split(":"))
            scheduled_time = local.replace(hour=hour, minute=minute, second=0, microsecond=0)
            if not 0 <= (local - scheduled_time).total_seconds() < 600:
                continue
            pending = [t for t, _ in pending_tasks(local.date(), period=period)]
            if not pending or not db().execute("SELECT 1 FROM subscriptions LIMIT 1").fetchone():
                continue
            delivered = send_push("拾间 · " + PERIODS[period], f"还有 {len(pending)} 项，约 {sum(t['minutes'] for t in pending)} 分钟。忙的话，先做保底三件。", scheduled=(day, period), at_epoch=local.timestamp())
            with db():
                db().execute("INSERT INTO notification_runs(date,period,status,created_at,sent) VALUES(?,?,'checked',?,?) ON CONFLICT(date,period) DO UPDATE SET sent=notification_runs.sent+excluded.sent", (day, period, local.timestamp(), delivered))
            sent += delivered
        return sent

    app.extensions["homecare"] = {"db": db, "scheduler_tick": scheduler_tick, "send_push": send_push}

    @app.get("/api/reminders.ics")
    def calendar():
        config = settings()
        zone = ZoneInfo(config["timezone"])
        start = today()
        lines = ["BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//HomeCare//拾间//ZH", "CALSCALE:GREGORIAN", "METHOD:PUBLISH", "X-WR-CALNAME:拾间家务提醒", "X-WR-TIMEZONE:" + ical_escape(config["timezone"])]
        lines.extend(calendar_timezone(zone, start.year))
        for period, label in PERIODS.items():
            if not any(task["period"] == period for task in get_tasks()):
                continue
            tm = config["reminderTimes"][period].replace(":", "") + "00"
            lines.extend(["BEGIN:VEVENT", f"UID:homecare-{period}@local", "DTSTAMP:" + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"),
                          f"DTSTART;TZID={config['timezone']}:{start.strftime('%Y%m%d')}T{tm}", "DURATION:PT15M", "RRULE:FREQ=DAILY",
                          "SUMMARY:" + ical_escape("拾间 · " + label + "家务"), "DESCRIPTION:" + ical_escape("打开拾间，按步骤完成今天的家务。忙时先做保底三件。"),
                          "URL:" + ical_escape(app.config["PUBLIC_URL"] or request.url_root), "BEGIN:VALARM", "TRIGGER:PT0M", "ACTION:DISPLAY", "DESCRIPTION:" + ical_escape(label + "家务时间到了"), "END:VALARM", "END:VEVENT"])
        lines.append("END:VCALENDAR")
        response = make_response("\r\n".join(fold_ical(line) for line in lines) + "\r\n")
        response.headers["Content-Type"] = "text/calendar; charset=utf-8"
        response.headers["Content-Disposition"] = 'attachment; filename="homecare-reminders.ics"'
        return response

    @app.get("/api/export")
    def export():
        payload = {"version": 1, "exportedAt": datetime.now(timezone.utc).isoformat(), "settings": settings(), "tasks": get_tasks(True),
                   "progress": [dict(r) for r in db().execute("SELECT date,task_id AS taskId,step_id AS stepId,done,updated_at AS updatedAt FROM progress ORDER BY date,task_id,step_id")],
                   "skips": [dict(r) for r in db().execute("SELECT date,task_id AS taskId,skipped FROM skips ORDER BY date,task_id")],
                   "taskDates": [{"date": r["date"], "taskId": r["task_id"], "task": json.loads(r["data"])} for r in db().execute("SELECT * FROM day_tasks ORDER BY date,task_id")],
                   "taskLifecycles": [dict(r) for r in db().execute("SELECT id,created_date AS createdDate,archived_date AS archivedDate,position FROM tasks ORDER BY position")]}
        response = jsonify(payload)
        response.headers["Content-Disposition"] = 'attachment; filename="homecare-backup-' + today().isoformat() + '.json"'
        return response

    @app.get("/health")
    def health():
        db().execute("SELECT 1").fetchone()
        return jsonify(ok=True)

    @app.get("/")
    def index():
        return send_from_directory(Path(__file__).parent / "public", "index.html")

    @app.get("/<path:filename>")
    def static_asset(filename):
        if filename.startswith("api/"):
            return jsonify(error="接口不存在"), 404
        return send_from_directory(Path(__file__).parent / "public", filename)

    with app.app_context():
        connection = db()
        connection.execute("PRAGMA journal_mode=WAL")
        connection.executescript("""
        CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS tasks(id TEXT PRIMARY KEY,data TEXT NOT NULL,created_date TEXT NOT NULL,archived_date TEXT,position INTEGER NOT NULL);
        CREATE TABLE IF NOT EXISTS progress(date TEXT NOT NULL,task_id TEXT NOT NULL REFERENCES tasks(id),step_id TEXT NOT NULL,done INTEGER NOT NULL,updated_at REAL NOT NULL,PRIMARY KEY(date,task_id,step_id));
        CREATE TABLE IF NOT EXISTS skips(date TEXT NOT NULL,task_id TEXT NOT NULL REFERENCES tasks(id),skipped INTEGER NOT NULL,PRIMARY KEY(date,task_id));
        CREATE TABLE IF NOT EXISTS day_tasks(date TEXT NOT NULL,task_id TEXT NOT NULL REFERENCES tasks(id),data TEXT NOT NULL,PRIMARY KEY(date,task_id));
        CREATE TABLE IF NOT EXISTS sessions(token_hash TEXT PRIMARY KEY,expires REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS login_attempts(id INTEGER PRIMARY KEY,ip_hash TEXT NOT NULL,timestamp REAL NOT NULL);
        CREATE INDEX IF NOT EXISTS login_ip_time ON login_attempts(ip_hash,timestamp);
        CREATE TABLE IF NOT EXISTS subscriptions(endpoint TEXT PRIMARY KEY,data TEXT NOT NULL,created_at REAL NOT NULL);
        CREATE TABLE IF NOT EXISTS notification_runs(date TEXT NOT NULL,period TEXT NOT NULL,status TEXT NOT NULL,created_at REAL NOT NULL,sent INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(date,period));
        CREATE TABLE IF NOT EXISTS notification_deliveries(date TEXT NOT NULL,period TEXT NOT NULL,endpoint_hash TEXT NOT NULL,status TEXT NOT NULL,attempts INTEGER NOT NULL,next_attempt REAL NOT NULL,updated_at REAL NOT NULL,PRIMARY KEY(date,period,endpoint_hash));
        CREATE TABLE IF NOT EXISTS email_deliveries(date TEXT PRIMARY KEY,status TEXT NOT NULL,attempts INTEGER NOT NULL,next_attempt REAL NOT NULL,updated_at REAL NOT NULL);
        """)
        with connection:
            connection.execute("INSERT OR IGNORE INTO meta(key,value) VALUES('settings',?)", (compact(DEFAULT_SETTINGS),))
            saved_settings = settings()
            if set(DEFAULT_SETTINGS) - set(saved_settings):
                connection.execute("UPDATE meta SET value=? WHERE key='settings'", (compact({**DEFAULT_SETTINGS, **saved_settings}),))
            auth_row = connection.execute("SELECT value FROM meta WHERE key='auth'").fetchone()
            initial_password = app.config["HOMECARE_PASSWORD"]
            changed = False
            if auth_row and initial_password:
                auth_value = json.loads(auth_row[0])
                current_digest = hashlib.pbkdf2_hmac("sha256", initial_password.encode(), bytes.fromhex(auth_value["salt"]), auth_value["rounds"]).hex()
                changed = not hmac.compare_digest(current_digest, auth_value["hash"])
            if not auth_row or changed:
                if initial_password and (len(initial_password) < 12 or len(initial_password) > 512):
                    raise ValueError("HOMECARE_PASSWORD 应为 12 到 512 个字符")
                if not initial_password:
                    initial_password = secrets.token_urlsafe(24)
                    password_file = data_dir / "access-password.txt"
                    fd = os.open(password_file, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
                    with os.fdopen(fd, "w") as handle:
                        handle.write(initial_password + "\n")
                    os.chmod(password_file, 0o600)
                salt = secrets.token_bytes(24)
                auth_value = {"salt": salt.hex(), "rounds": PASSWORD_ROUNDS, "hash": hashlib.pbkdf2_hmac("sha256", initial_password.encode(), salt, PASSWORD_ROUNDS).hex()}
                connection.execute("INSERT INTO meta(key,value) VALUES('auth',?) ON CONFLICT(key) DO UPDATE SET value=excluded.value", (compact(auth_value),))
                connection.execute("DELETE FROM sessions")
            if not connection.execute("SELECT 1 FROM meta WHERE key='seeded'").fetchone():
                for position, task in enumerate(seeds()):
                    connection.execute("INSERT INTO tasks(id,data,created_date,position) VALUES(?,?,?,?)", (task["id"], compact(task), today().isoformat(), position))
                connection.execute("INSERT INTO meta(key,value) VALUES('seeded','1')")
        os.chmod(app.config["DATABASE"], 0o600)
        setup_vapid(app, data_dir)

    if app.config["SCHEDULER_ENABLED"] and not app.config["TESTING"]:
        def scheduler_loop():
            while True:
                try:
                    with app.app_context():
                        scheduler_tick()
                except Exception:
                    app.logger.exception("提醒调度暂时失败，下次将继续检查")
                time.sleep(20)
        threading.Thread(target=scheduler_loop, daemon=True, name="homecare-reminders").start()
    return app


class ApiError(Exception):
    def __init__(self, message, status=400):
        self.message = message
        self.status = status


def boolean(value, label):
    if type(value) is not bool:
        raise ApiError(label + "必须是布尔值")
    return value


def short_text(value, label, limit):
    if not isinstance(value, str) or not value.strip() or len(value.strip()) > limit or any(ord(c) < 32 for c in value):
        raise ApiError(f"{label}需要填写 1 到 {limit} 个有效字符")
    return value.strip()


def validate_email(value, allow_empty=False):
    if not isinstance(value, str) or len(value) > 254 or any(ord(c) < 33 or ord(c) > 126 for c in value):
        raise ApiError("请填写有效邮箱地址")
    if allow_empty and value == "":
        return value
    if not re.fullmatch(r"[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\.[A-Za-z]{2,63}", value) or ".." in value or value.startswith(".") or ".@" in value or len(value.split("@", 1)[0]) > 64:
        raise ApiError("请填写有效邮箱地址")
    return value


def validate_subscription(body):
    endpoint = body.get("endpoint")
    if not isinstance(endpoint, str) or len(endpoint) > 4096:
        raise ApiError("推送订阅地址无效")
    try:
        parts = urlsplit(endpoint)
        host = parts.hostname or ""
        valid_host = host in {"fcm.googleapis.com", "updates.push.services.mozilla.com", "web.push.apple.com"} or any(host.endswith(s) for s in (".push.services.mozilla.com", ".push.apple.com", ".notify.windows.com"))
        if parts.scheme != "https" or not valid_host or parts.port not in (None, 443) or parts.username or parts.password or parts.fragment:
            raise ValueError()
    except ValueError:
        raise ApiError("仅支持浏览器提供的安全推送地址")
    keys = body.get("keys")
    if not isinstance(keys, dict):
        raise ApiError("推送密钥无效")
    for name, expected_length in (("p256dh", 65), ("auth", 16)):
        value = keys.get(name)
        if not isinstance(value, str) or len(value) > 128 or not re.fullmatch(r"[A-Za-z0-9_-]+={0,2}", value):
            raise ApiError("推送密钥无效")
        try:
            decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
            if len(decoded) != expected_length or (name == "p256dh" and decoded[0] != 4):
                raise ValueError()
        except (ValueError, base64.binascii.Error):
            raise ApiError("推送密钥无效")
    return {"endpoint": endpoint, "keys": {key: keys[key] for key in ("p256dh", "auth")}}


def setup_vapid(app, data_dir):
    if not webpush:
        return
    try:
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        private_path = data_dir / "vapid-private.pem"
        if private_path.exists():
            private_key = serialization.load_pem_private_key(private_path.read_bytes(), password=None)
        else:
            private_key = ec.generate_private_key(ec.SECP256R1())
            pem = private_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
            fd = os.open(private_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as handle:
                handle.write(pem)
        os.chmod(private_path, 0o600)
        pub = private_key.public_key().public_bytes(serialization.Encoding.X962, serialization.PublicFormat.UncompressedPoint)
        app.config["VAPID_PUBLIC_KEY"] = base64.urlsafe_b64encode(pub).decode().rstrip("=")
        app.config["VAPID_PRIVATE_KEY"] = str(private_path)
    except Exception:
        app.logger.exception("未能初始化推送密钥；家务记录仍可正常使用")


def ical_escape(value):
    return value.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")


def fold_ical(value):
    lines, current, count = [], "", 0
    for char in value:
        width = len(char.encode("utf-8"))
        if count + width > 74:
            lines.append(current)
            current, count = " ", 1
        current += char
        count += width
    lines.append(current)
    return "\r\n".join(lines)


def calendar_timezone(zone, year):
    """Explicit observances cover DST transitions for the next five years."""
    def offset(value):
        seconds = int(value.total_seconds())
        sign = "+" if seconds >= 0 else "-"
        minutes = abs(seconds) // 60
        return f"{sign}{minutes // 60:02d}{minutes % 60:02d}"
    begin = datetime(year - 1, 1, 1, tzinfo=timezone.utc)
    end = datetime(year + 6, 1, 1, tzinfo=timezone.utc)
    previous = begin.astimezone(zone)
    lines = ["BEGIN:VTIMEZONE", "TZID:" + str(zone), "X-LIC-LOCATION:" + str(zone), "BEGIN:STANDARD", f"DTSTART:{year - 1}0101T000000", "TZOFFSETFROM:" + offset(previous.utcoffset()), "TZOFFSETTO:" + offset(previous.utcoffset()), "TZNAME:" + ical_escape(previous.tzname() or str(zone)), "END:STANDARD"]
    cursor = begin + timedelta(days=1)
    while cursor < end:
        current = cursor.astimezone(zone)
        if current.utcoffset() != previous.utcoffset():
            low, high = cursor - timedelta(days=1), cursor
            while (high - low).total_seconds() > 1:
                mid = low + timedelta(seconds=int((high - low).total_seconds()) // 2)
                if mid.astimezone(zone).utcoffset() == previous.utcoffset():
                    low = mid
                else:
                    high = mid
            at = high.astimezone(zone)
            component = "DAYLIGHT" if at.dst() else "STANDARD"
            wall_before = (high.replace(tzinfo=None) + previous.utcoffset()).strftime("%Y%m%dT%H%M%S")
            lines.extend(["BEGIN:" + component, "DTSTART:" + wall_before, "TZOFFSETFROM:" + offset(previous.utcoffset()), "TZOFFSETTO:" + offset(at.utcoffset()), "TZNAME:" + ical_escape(at.tzname() or str(zone)), "END:" + component])
        previous, cursor = current, cursor + timedelta(days=1)
    lines.append("END:VTIMEZONE")
    return lines


if os.environ.get("HOMECARE_NO_DEFAULT_APP") != "1":
    app = create_app()

if __name__ == "__main__":
    app.run(host=os.environ.get("HOMECARE_HOST", "127.0.0.1"), port=int(os.environ.get("HOMECARE_PORT", "8787")), threaded=True)
