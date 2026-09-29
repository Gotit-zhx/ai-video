"""Recruitment planning and bounded, explicit AI generation. No BOSS operations."""
import datetime
import json
import threading
import time
import urllib.error
import urllib.request
import uuid

import talent_pool as pool

STATES = ("draft", "recruiting", "paused", "filled", "cancelled")
FIELDS = {"date": 10, "position": 200, "department": 120, "manager": 120, "departmentId": 36,
          "category": 100, "education": 50, "responsibilities": 10000, "requirements": 10000}
AI_URL = "http://127.0.0.1:8080/api/v1/recruitment-ai/generate"


def schema(db):
    db.execute("""CREATE TABLE IF NOT EXISTS recruitment_demands(
        id TEXT PRIMARY KEY, owner TEXT NOT NULL, body TEXT NOT NULL, status TEXT NOT NULL,
        version INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL,
        ai_status TEXT NOT NULL DEFAULT 'idle', ai_job TEXT NOT NULL DEFAULT '',
        ai_version INTEGER NOT NULL DEFAULT 0, ai_result TEXT NOT NULL DEFAULT '{}',
        ai_error TEXT NOT NULL DEFAULT '', ai_started REAL NOT NULL DEFAULT 0)""")
    db.execute("CREATE INDEX IF NOT EXISTS demands_owner ON recruitment_demands(owner,status,updated_at)")


def view(row):
    return {**json.loads(row["body"]), "id": row["id"], "version": row["version"],
            "status": row["status"], "createdAt": row["created_at"], "updatedAt": row["updated_at"],
            "aiStatus": row["ai_status"], "aiVersion": row["ai_version"], "aiError": row["ai_error"],
            "aiResult": json.loads(row["ai_result"]), "aiJob": row["ai_job"]}


def expire_jobs(db):
    db.execute("""UPDATE recruitment_demands SET ai_status='failed',ai_error=?
        WHERE ai_status='running' AND ai_started<?""",
               ("本次生成未及时完成，请确认模型状态后手动重试；系统未自动重复调用", time.time()-360))


def recover_interrupted():
    with pool.database() as db:
        schema(db)
        db.execute("""UPDATE recruitment_demands SET ai_status='failed',ai_error=?
            WHERE ai_status='running'""", ("服务已更新，本次生成中断，请手动重新生成",))


def listing(user, query):
    owner, _ = pool.context(user)
    q = pool.text(query.get("q", ""), 100)
    status = pool.text(query.get("status", "all"), 20)
    if status not in (*STATES, "all"):
        raise ValueError("需求状态不正确")
    page = max(1, int(query.get("page", "1")))
    where, args = ["owner=?"], [owner]
    if q:
        where.append("body LIKE ? ESCAPE '\\'")
        args.append("%"+q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")+"%")
    with pool.database() as db:
        schema(db)
        expire_jobs(db)
        if query.get("id"):
            record = pool.text(query["id"], 100)
            row = db.execute("SELECT * FROM recruitment_demands WHERE owner=? AND id=?", (owner, record)).fetchone()
            if not row:
                raise ValueError("需求不存在")
            return {"item": view(row)}
        counts = {s: 0 for s in STATES}
        counts.update({r["status"]: r["n"] for r in db.execute(
            "SELECT status,COUNT(*) n FROM recruitment_demands WHERE "+" AND ".join(where)+" GROUP BY status", args)})
        counts["all"] = sum(counts.values())
        if status != "all":
            where.append("status=?")
            args.append(status)
        total = counts[status]
        pages = max(1, (total+19)//20)
        page = min(page, pages)
        rows = db.execute("SELECT * FROM recruitment_demands WHERE "+" AND ".join(where)
                          +" ORDER BY updated_at DESC,id LIMIT 20 OFFSET ?", [*args, (page-1)*20]).fetchall()
        return {"items": [view(row) for row in rows], "counts": counts, "total": total, "page": page, "pages": pages}


def save(user, body, departments=None):
    owner, actor = pool.context(user)
    if not isinstance(body, dict):
        raise ValueError("需求格式不正确")
    clean = {key: pool.text(body.get(key, ""), limit) for key, limit in FIELDS.items()}
    if departments is not None:
        selected=next((row for row in departments if row["id"]==clean["departmentId"]),None)
        if not selected:
            raise ValueError("请从部门管理中选择有效的归属部门")
        clean["department"]=selected["name"]
        clean["manager"]=selected.get("manager","")
    if not all(clean[key] for key in ("date", "position", "department")):
        raise ValueError("请填写需求日期、招聘岗位和归属部门")
    try:
        datetime.date.fromisoformat(clean["date"])
        headcount = int(body.get("headcount", 1))
        years = float(body.get("years", 0))
    except (ValueError, TypeError):
        raise ValueError("需求日期、编制人数或工作年限不正确")
    if isinstance(body.get("headcount"), bool) or str(body.get("headcount", 1)) != str(headcount) or not 1 <= headcount <= 999 or not 0 <= years <= 60:
        raise ValueError("编制人数须为 1–999 的整数，工作年限须在 0–60 年之间")
    clean.update(headcount=headcount, years=years)
    status = pool.text(body.get("status", "draft"), 20)
    if status not in STATES:
        raise ValueError("需求状态不正确")
    record = pool.text(body.get("id", ""), 100)
    creating = not record
    if creating:
        try:
            record = str(uuid.UUID(body.get("createId", str(uuid.uuid4()))))
        except (ValueError, AttributeError, TypeError):
            raise ValueError("新建请求标识不正确")
    with pool.database() as db:
        schema(db)
        expire_jobs(db)
        old = db.execute("SELECT * FROM recruitment_demands WHERE owner=? AND id=?", (owner, record)).fetchone()
        if not creating and not old:
            raise ValueError("需求不存在")
        if departments is not None and selected["status"]!="ACTIVE" and (
                not old or json.loads(old["body"]).get("departmentId")!=clean["departmentId"]):
            raise ValueError("停用部门不能关联新的招聘需求")
        if creating and old:
            if json.loads(old["body"]) == clean and old["status"] == status:
                return view(old)
            raise pool.Conflict("该新建请求已保存，请刷新后编辑")
        if old and old["ai_status"] == "running":
            raise pool.Conflict("正在生成岗位分析，请等待本次生成结束后再修改")
        if old and body.get("version") != old["version"]:
            raise pool.Conflict("该招聘需求已被更新，请刷新后重新编辑")
        now = datetime.datetime.now(datetime.timezone.utc).isoformat()
        if old:
            ai_status = "stale" if old["ai_result"] != "{}" else "idle"
            db.execute("""UPDATE recruitment_demands SET body=?,status=?,version=version+1,
                updated_at=?,ai_status=?,ai_error='' WHERE owner=? AND id=?""",
                       (json.dumps(clean, ensure_ascii=False), status, now, ai_status, owner, record))
        else:
            db.execute("""INSERT INTO recruitment_demands(id,owner,body,status,version,created_at,updated_at)
                VALUES(?,?,?,?,1,?,?)""", (record, owner, json.dumps(clean, ensure_ascii=False), status, now, now))
        result = view(db.execute("SELECT * FROM recruitment_demands WHERE id=?", (record,)).fetchone())
        pool.event(db, owner, actor, record, "demand.update" if old else "demand.create", view(old) if old else None, result)
        return result


def call_driver(snapshot, cookie):
    payload = json.dumps({key: snapshot[key] for key in (*FIELDS, "headcount", "years")}, ensure_ascii=False).encode()
    request = urllib.request.Request(AI_URL, data=payload, headers={
        "Cookie": cookie, "Content-Type": "application/json", "Accept": "application/json"})
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(request, timeout=320) as response:
            raw = response.read(512001)
            if len(raw) > 512000:
                raise ValueError("模型返回内容过长，请精简职责和要求后重试")
            result = json.loads(raw).get("data")
    except urllib.error.HTTPError as error:
        # Never echo provider response bodies, cookies or keys into saved job errors.
        messages = {401: "平台登录已过期，请重新登录后生成", 403: "当前账号没有招聘 AI 权限",
                    409: "请在系统模型接入中配置并启用驱动模型",
                    429: "当前模型调用较多，请稍后手动重试",
                    504: "驱动模型响应超时，请检查模型服务后手动重试"}
        raise ValueError(messages.get(error.code, "驱动模型生成失败，请检查模型服务后手动重试")) from None
    if not isinstance(result, dict) or not isinstance(result.get("portrait"), dict) or not result.get("competencies") or not result.get("interviewQuestions"):
        raise ValueError("驱动模型返回不完整，请手动重试")
    return result


def finish(user, record, job, result=None, error=""):
    owner, actor = pool.context(user)
    with pool.database() as db:
        schema(db)
        row = db.execute("SELECT * FROM recruitment_demands WHERE owner=? AND id=?", (owner, record)).fetchone()
        if not row or row["ai_job"] != job or row["ai_status"] != "running":
            return
        state = "complete" if result is not None else "failed"
        if result is not None:
            result = {**result, "sourceVersion": row["ai_version"]}
        db.execute("""UPDATE recruitment_demands SET ai_status=?,ai_result=?,ai_error=?,updated_at=?
            WHERE owner=? AND id=?""", (state, json.dumps(result, ensure_ascii=False) if result is not None else row["ai_result"],
                                       error, datetime.datetime.now(datetime.timezone.utc).isoformat(), owner, record))
        pool.event(db, owner, actor, record, "demand.ai."+state, None,
                   {"job": job, "version": row["ai_version"], "modelId": (result or {}).get("modelId", ""), "error": error})


def worker(user, record, job, snapshot, cookie):
    try:
        result = call_driver(snapshot, cookie)
        finish(user, record, job, result=result)
    except ValueError as error:
        finish(user, record, job, error=str(error))
    except Exception:
        finish(user, record, job, error="驱动模型暂时不可用或响应超时，请检查后手动重试")


def generate(user, body, cookie):
    owner, actor = pool.context(user)
    if not isinstance(body, dict):
        raise ValueError("生成请求不正确")
    record = pool.text(body.get("id", ""), 100)
    with pool.database() as db:
        schema(db)
        expire_jobs(db)
        row = db.execute("SELECT * FROM recruitment_demands WHERE owner=? AND id=?", (owner, record)).fetchone()
        if not row:
            raise ValueError("请先保存招聘需求")
        if row["ai_status"] == "running":
            return view(row)
        if body.get("version") != row["version"]:
            raise pool.Conflict("需求已经更新，请刷新后再生成")
        if row["ai_status"] == "complete" and body.get("regenerate") is not True:
            return view(row)
        if db.execute("SELECT 1 FROM recruitment_demands WHERE owner=? AND ai_status='running'", (owner,)).fetchone():
            raise pool.Conflict("已有招聘需求正在生成，请等待完成")
        snapshot = view(row)
        if not snapshot["responsibilities"] or not snapshot["requirements"]:
            raise ValueError("请先填写岗位职责和任职要求")
        job = str(uuid.uuid4())
        db.execute("""UPDATE recruitment_demands SET ai_status='running',ai_job=?,ai_version=version,
            ai_error='',ai_started=? WHERE owner=? AND id=?""", (job, time.time(), owner, record))
        pool.event(db, owner, actor, record, "demand.ai.start", None, {"job": job, "version": row["version"]})
        result = view(db.execute("SELECT * FROM recruitment_demands WHERE id=?", (record,)).fetchone())
    try:
        threading.Thread(target=worker, args=(user, record, job, snapshot, cookie), daemon=True).start()
    except Exception:
        finish(user, record, job, error="生成任务未能启动，请手动重试")
        raise ValueError("生成任务未能启动，请手动重试")
    return result
