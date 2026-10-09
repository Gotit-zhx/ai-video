"""HR-owned onboarding arrangements and explicit, receipt-tracked mentor notices."""
import datetime
from decimal import Decimal, InvalidOperation
import json
import re
import threading
import time
import uuid

import talent_pool as pool
import dingtalk_interviews as dt

FIELDS = {"startDate": 10, "position": 200, "supervisor": 100, "department": 120,
          "company": 200, "fixedSalary": 30, "performanceSalary": 30,
          "commission": 200, "mentorName": 100}


def schema(db):
    dt.schema(db)
    db.execute("""CREATE TABLE IF NOT EXISTS hr_onboarding_records(
      talent_id TEXT PRIMARY KEY,owner TEXT NOT NULL,body TEXT NOT NULL,
      version INTEGER NOT NULL DEFAULT 1,updated_at TEXT NOT NULL)""")
    db.execute("""CREATE TABLE IF NOT EXISTS hr_onboarding_notices(
      id TEXT PRIMARY KEY,owner TEXT NOT NULL,talent_id TEXT NOT NULL UNIQUE,request_id TEXT NOT NULL,
      body TEXT NOT NULL,status TEXT NOT NULL,error TEXT NOT NULL DEFAULT '',task_id TEXT NOT NULL DEFAULT '',
      created_at REAL NOT NULL,updated_at REAL NOT NULL,checked_at REAL NOT NULL DEFAULT 0)""")


def defaults(db, talent):
    import hr_departments
    hr_departments.schema(db)
    value = json.loads(talent["body"])
    dept = db.execute("SELECT * FROM hr_departments WHERE owner=? AND id=?",
                      (talent["owner"], value.get("departmentId", ""))).fetchone()
    return {"name": value["name"], "position": value.get("position", ""),
            "department": dept["name"] if dept else "", "supervisor": dept["manager"] if dept else "",
            "company": "", "startDate": "", "fixedSalary": "", "performanceSalary": "0",
            "commission": "无", "mentorName": "", "joined": talent["stage"] == "hired"}


def validate(body):
    if not isinstance(body, dict):
        raise ValueError("请填写入职安排信息")
    value = {key: pool.text(body.get(key, ""), limit) for key, limit in FIELDS.items()}
    if any(not value[key] for key in FIELDS):
        raise ValueError("请填写入职时间、岗位、主管、部门、公司、薪资、提成和导师；无绩效或提成可填写 0 或无")
    try:
        if datetime.date.fromisoformat(value["startDate"]).isoformat() != value["startDate"]:
            raise ValueError()
    except ValueError:
        raise ValueError("确认入职时间格式不正确") from None
    for key in ("fixedSalary", "performanceSalary"):
        try:
            money = Decimal(value[key])
            if not money.is_finite() or money < 0 or money > 100000000 or money.as_tuple().exponent < -2:
                raise InvalidOperation()
        except InvalidOperation:
            raise ValueError("固定薪资和绩效薪资须为非负金额，最多两位小数") from None
        value[key] = format(money, "f")
    if type(body.get("joined")) is not bool:
        raise ValueError("请选择是否入职")
    value["joined"] = body["joined"]
    return value


def create(db, talent, body):
    schema(db)
    value = validate(body) | {"name": json.loads(talent["body"])["name"]}
    db.execute("""INSERT INTO hr_onboarding_records(talent_id,owner,body,updated_at)
      VALUES(?,?,?,?)""", (talent["id"], talent["owner"], json.dumps(value, ensure_ascii=False), now()))
    return value


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def notice_view(row):
    if not row:
        return None
    value = json.loads(row["body"])
    return {"id": row["id"], "status": row["status"], "error": row["error"],
            "recipient": value["recipientName"], "message": value["message"]}


def backfill(db, owner):
    for talent in db.execute("""SELECT t.* FROM talents t JOIN hr_interview_reviews r
      ON r.owner=t.owner AND r.talent_id=t.id AND r.round='second' AND r.outcome='passed'
      WHERE t.owner=? AND t.stage IN ('second','onboarding','hired') AND NOT EXISTS(
      SELECT 1 FROM hr_onboarding_records o WHERE o.owner=t.owner AND o.talent_id=t.id)""", (owner,)).fetchall():
        value = defaults(db, talent)
        db.execute("INSERT INTO hr_onboarding_records(talent_id,owner,body,updated_at) VALUES(?,?,?,?)",
                   (talent["id"], owner, json.dumps(value, ensure_ascii=False), now()))
        if talent["stage"] == "second":
            db.execute("UPDATE talents SET stage='onboarding',version=version+1,updated_at=? WHERE id=? AND owner=?",
                       (now(), talent["id"], owner))
        pool.event(db, owner, "onboarding-migration", talent["id"], "onboarding.backfill", None, {"stage": "onboarding"})


def listing(user, query):
    owner, _ = pool.context(user)
    with pool.database() as db:
        schema(db); backfill(db, owner)
        if query.get("talentId"):
            talent = db.execute("SELECT * FROM talents WHERE owner=? AND id=?", (owner, pool.text(query["talentId"], 100))).fetchone()
            if not talent:
                raise ValueError("人才不存在")
            return {"defaults": defaults(db, talent)}
        clause, args = "owner=?", [owner]
        status = query.get("status", "pending")
        if status not in ("pending", "joined", "all"):
            raise ValueError("入职状态不正确")
        if status != "all":
            clause += " AND json_extract(body,'$.joined')=?"; args.append(status == "joined")
        q = pool.text(query.get("q", ""), 100)
        if q:
            clause += " AND body LIKE ? ESCAPE '\\'"
            args.append("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
        total = db.execute("SELECT count(*) FROM hr_onboarding_records WHERE " + clause, args).fetchone()[0]
        pages = max(1, (total + 19)//20)
        page = min(pages, max(1, int(query.get("page", 1))))
        rows = db.execute("SELECT * FROM hr_onboarding_records WHERE " + clause + " ORDER BY updated_at DESC,talent_id LIMIT 20 OFFSET ?",
                          [*args, (page-1)*20]).fetchall()
        items = []
        for row in rows:
            notice = db.execute("SELECT * FROM hr_onboarding_notices WHERE owner=? AND talent_id=?",
                                (owner, row["talent_id"])).fetchone()
            items.append(json.loads(row["body"]) | {"talentId": row["talent_id"], "version": row["version"],
                         "notice": notice_view(notice)})
    return {"items": items, "total": total, "pages": pages, "page": page}


def save(user, body):
    owner, actor = pool.context(user)
    value = validate(body)
    with pool.database() as db:
        schema(db)
        row = db.execute("SELECT * FROM hr_onboarding_records WHERE owner=? AND talent_id=?",
                          (owner, pool.text(body.get("talentId", ""), 100))).fetchone()
        if not row:
            raise ValueError("入职安排不存在，二面通过后自动生成")
        if row["version"] != body.get("version"):
            raise pool.Conflict("入职安排已更新，请刷新后再保存")
        notice = db.execute("SELECT status FROM hr_onboarding_notices WHERE owner=? AND talent_id=?",
                             (owner, row["talent_id"])).fetchone()
        before = json.loads(row["body"])
        if notice and notice["status"] in ("preparing", "sending", "submitted", "uncertain"):
            raise pool.Conflict("导师通知正在发送或待核对，请先确认结果后再修改")
        if notice and notice["status"] == "sent" and (not value["joined"] or value["mentorName"] != before["mentorName"]):
            raise pool.Conflict("导师已收到入职通知，请保留已入职状态和导师身份")
        value["name"] = before["name"]
        db.execute("UPDATE hr_onboarding_records SET body=?,version=version+1,updated_at=? WHERE owner=? AND talent_id=?",
                   (json.dumps(value, ensure_ascii=False), now(), owner, row["talent_id"]))
        db.execute("UPDATE talents SET stage=?,version=version+1,updated_at=? WHERE owner=? AND id=?",
                   ("hired" if value["joined"] else "onboarding", now(), owner, row["talent_id"]))
        pool.event(db, owner, actor, row["talent_id"], "onboarding.update", before, value)
    return {"saved": True}


def recipients(user, query):
    owner, _ = pool.context(user)
    with pool.database() as db:
        schema(db)
        config = db.execute("SELECT * FROM hr_dingtalk_config WHERE owner=?", (owner,)).fetchone()
        people = dt.second_recipients(db, owner, config)
        return {"config": dt.public_config(config),
                "items": [{"userId": r["user_id"], "name": r["name"], "bindingKey": r["bindingKey"]}
                          for r in people]}


def identity(user, body):
    owner, _ = pool.context(user)
    record = pool.text(body.get("talentId", ""), 100)
    config = dt.read_config(owner)
    mobile = re.sub(r"[\s()-]", "", pool.text(body.get("mobile", ""), 30)).removeprefix("+86")
    if not re.fullmatch(r"1[3-9]\d{9}", mobile):
        raise ValueError("请填写导师在企业钉钉通讯录中的手机号")
    with pool.database() as db:
        schema(db)
        row = db.execute("SELECT * FROM hr_onboarding_records WHERE owner=? AND talent_id=?", (owner, record)).fetchone()
        if not row or row["version"] != body.get("version") or not json.loads(row["body"])["joined"]:
            raise pool.Conflict("请先保存已入职状态，再识别导师")
    result = dt.api("topapi/v2/user/getbymobile", {"mobile": mobile}, dt.token(config)).get("result", {})
    uid = result.get("userid", "") if isinstance(result, dict) else ""
    if not isinstance(uid, str) or not re.fullmatch(r"[\w@.+~-]{1,100}", uid):
        raise ValueError("未识别到企业员工，请核对手机号和通讯录权限")
    name = dt.employee(config, uid)
    if name != json.loads(row["body"])["mentorName"]:
        raise ValueError("识别姓名与填写的导师名称不一致，请核对导师资料")
    proof = {"owner": owner, "talentId": record, "version": row["version"], "configVersion": config["version"],
             "userId": uid, "name": name}
    return {"name": name, "userId": uid,
            "identityProof": dt.cipher().encrypt(json.dumps(proof, ensure_ascii=False).encode()).decode()}


def send(user, body):
    owner, actor = pool.context(user)
    record = pool.text(body.get("talentId", ""), 100)
    try:
        request_id = str(uuid.UUID(body.get("requestId", "")))
    except (ValueError, TypeError, AttributeError):
        raise ValueError("操作标识不正确")
    with pool.database() as db:
        schema(db)
        old = db.execute("SELECT * FROM hr_onboarding_notices WHERE owner=? AND talent_id=?", (owner, record)).fetchone()
        if old and (old["request_id"] == request_id or old["status"] != "failed"):
            return notice_view(old)
        if old and body.get("retryId") != old["id"]:
            raise pool.Conflict("请核对发送记录后手动重试")
        row = db.execute("SELECT * FROM hr_onboarding_records WHERE owner=? AND talent_id=?", (owner, record)).fetchone()
        if not row or row["version"] != body.get("version"):
            raise pool.Conflict("入职资料已变化，请刷新")
        value = json.loads(row["body"])
        if not value["joined"]:
            raise ValueError("请先确认并保存已入职状态")
        config = db.execute("SELECT * FROM hr_dingtalk_config WHERE owner=?", (owner,)).fetchone()
        if not config or not config["verified"] or config["version"] != body.get("configVersion"):
            raise ValueError("请先校验钉钉应用，或刷新最新配置")
        if body.get("identityProof"):
            from cryptography.fernet import InvalidToken
            try:
                proof = json.loads(dt.cipher().decrypt(pool.text(body["identityProof"], 4000).encode(), ttl=300))
            except (InvalidToken, ValueError, TypeError):
                raise ValueError("导师识别已过期，请重新识别") from None
            if (not isinstance(proof, dict) or proof.get("owner") != owner or proof.get("talentId") != record
                    or proof.get("version") != row["version"] or proof.get("configVersion") != config["version"]
                    or proof.get("userId") != body.get("recipientId")):
                raise ValueError("导师识别结果不匹配，请重新识别")
            person = {"user_id": proof["userId"], "name": proof["name"]}
        else:
            person = next((p for p in dt.second_recipients(db, owner, config)
                           if p["user_id"] == body.get("recipientId") and p["bindingKey"] == body.get("bindingKey")), None)
        if not person or person["name"] != value["mentorName"]:
            raise ValueError("请选择已验证且姓名与导师名称一致的钉钉员工")
        snapshot = {"recipientId": person["user_id"], "recipientName": person["name"],
                    "configVersion": config["version"], "agentId": config["agent_id"], "version": row["version"],
                    "message": f'{value["name"]} 已经入职，请准备好 {value["name"]} 的带教计划。'}
        job_id = old["id"] if old else str(uuid.uuid4())
        db.execute("""INSERT OR REPLACE INTO hr_onboarding_notices
          (id,owner,talent_id,request_id,body,status,created_at,updated_at) VALUES(?,?,?,?,?,'preparing',?,?)""",
                   (job_id, owner, record, request_id, json.dumps(snapshot, ensure_ascii=False), time.time(), time.time()))
        pool.event(db, owner, actor, record, "onboarding.notify", None, {"noticeId": job_id, "recipient": person["name"]})
        result = notice_view(db.execute("SELECT * FROM hr_onboarding_notices WHERE id=?", (job_id,)).fetchone())
    threading.Thread(target=deliver, args=(owner, job_id), daemon=True).start()
    return result


def deliver(owner, job_id):
    started = False
    try:
        with dt.SEND_LOCK:
            with pool.database() as db:
                schema(db)
                row = db.execute("SELECT * FROM hr_onboarding_notices WHERE owner=? AND id=? AND status='preparing'",
                                  (owner, job_id)).fetchone()
                if not row:
                    return
                value = json.loads(row["body"])
            config = dt.read_config(owner)
            if config["version"] != value["configVersion"] or dt.employee(config, value["recipientId"]) != value["recipientName"]:
                raise ValueError("钉钉配置或导师身份已变化，未发送")
            access = dt.token(config)
            with pool.database() as db:
                schema(db)
                current = db.execute("SELECT * FROM hr_onboarding_records WHERE owner=? AND talent_id=?",
                                      (owner, row["talent_id"])).fetchone()
                current_config = db.execute("SELECT version FROM hr_dingtalk_config WHERE owner=?", (owner,)).fetchone()
                if (not current or current["version"] != value["version"] or not json.loads(current["body"])["joined"]
                        or not current_config or current_config["version"] != value["configVersion"]):
                    raise ValueError("入职资料或应用已变化，未发送")
                if not db.execute("UPDATE hr_onboarding_notices SET status='sending',updated_at=? WHERE id=? AND status='preparing'",
                                   (time.time(), job_id)).rowcount:
                    return
            started = True
            result = dt.api("topapi/message/corpconversation/asyncsend_v2",
                            {"agent_id": int(value["agentId"]), "userid_list": value["recipientId"], "to_all_user": False,
                             "msg": {"msgtype": "text", "text": {"content": value["message"]}}}, access, sending=True)
            task = str(result.get("task_id", ""))
            if not re.fullmatch(r"\d{1,30}", task):
                raise dt.UnknownSend("钉钉未返回可核对的任务，系统不会自动重发")
            with pool.database() as db:
                schema(db)
                db.execute("UPDATE hr_onboarding_notices SET status='submitted',task_id=?,updated_at=? WHERE id=? AND status='sending'",
                           (task, time.time(), job_id))
        reconcile(owner, job_id)
    except Exception as error:
        uncertain = started and (isinstance(error, dt.UnknownSend) or not isinstance(error, (dt.DingError, ValueError)))
        with pool.database() as db:
            schema(db)
            db.execute("UPDATE hr_onboarding_notices SET status=?,error=?,updated_at=? WHERE id=? AND status IN ('preparing','sending')",
                       ("uncertain" if uncertain else "failed",
                        str(error)[:400] if isinstance(error, ValueError) else "导师通知未完成，请核对发送结果",
                        time.time(), job_id))


def reconcile(owner, job_id):
    with pool.database() as db:
        schema(db)
        row = db.execute("SELECT * FROM hr_onboarding_notices WHERE owner=? AND id=? AND status='submitted'",
                          (owner, job_id)).fetchone()
        if not row or time.time()-row["checked_at"] < 15:
            return
        if time.time()-row["created_at"] > 23*3600:
            db.execute("UPDATE hr_onboarding_notices SET status='uncertain',error=? WHERE id=?",
                       ("回执查询时限已到，请人工核对；不会自动重发", job_id))
            return
        db.execute("UPDATE hr_onboarding_notices SET checked_at=? WHERE id=?", (time.time(), job_id))
        value = json.loads(row["body"])
    try:
        config = dt.read_config(owner)
        if config["version"] != value["configVersion"]:
            return
        result = dt.api("topapi/message/corpconversation/getsendresult",
                        {"agent_id": int(value["agentId"]), "task_id": int(row["task_id"])}, dt.token(config)).get("send_result", {})
        if not isinstance(result, dict):
            return
        uid = value["recipientId"]
        failed = any(uid in result.get(key, []) for key in ("failed_user_id_list", "invalid_user_id_list"))
        forbidden = any(x == uid or isinstance(x, dict) and (x.get("userid") or x.get("user_id")) == uid
                        for x in result.get("forbidden_list", []))
        success = uid in result.get("read_user_id_list", []) or uid in result.get("unread_user_id_list", [])
        if failed or forbidden or success:
            with pool.database() as db:
                schema(db)
                db.execute("UPDATE hr_onboarding_notices SET status=?,error=?,updated_at=? WHERE id=? AND status='submitted'",
                           ("failed" if failed or forbidden else "sent",
                            "钉钉回执显示未送达，请核对导师可见范围及限额" if failed or forbidden else "", time.time(), job_id))
    except Exception:
        pass


def recover():
    with pool.database() as db:
        schema(db)
        for owner in db.execute("SELECT DISTINCT owner FROM hr_interview_reviews WHERE round='second' AND outcome='passed'").fetchall():
            backfill(db, owner["owner"])
        db.execute("UPDATE hr_onboarding_notices SET status='failed',error='服务已重启，请核对后手动重试' WHERE status='preparing'")
        db.execute("UPDATE hr_onboarding_notices SET status='uncertain',error='服务已重启，发送结果待核对，不会自动重发' WHERE status='sending'")


def receipt_worker():
    while True:
        try:
            with pool.database() as db:
                schema(db)
                rows = db.execute("SELECT owner,id FROM hr_onboarding_notices WHERE status='submitted' AND checked_at<? LIMIT 20",
                                   (time.time()-60,)).fetchall()
            for row in rows:
                reconcile(row["owner"], row["id"])
        except Exception:
            pass
        threading.Event().wait(15)
