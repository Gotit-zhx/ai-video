"""Explicit first-interview handoff to one employee. Never contacts BOSS."""
import hashlib
import html
import io
import json
import os
from pathlib import Path
import re
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zipfile

import talent_pool as pool
import passive_pool
import recruitment_demands as demands
import resume_storage
import hr_departments

KEY_FILE = Path("/opt/heyi/boss-agent/runtime/private/dingtalk.key")
MAX_PACKAGE = 20 * 1024 * 1024
TOKEN_LOCK = threading.Lock()
KEY_LOCK = threading.Lock()
TOKENS = {}
WORK_LOCK = threading.Lock()
WORKERS = set()
SEND_LOCK = threading.Lock()


class DingError(ValueError):
    pass


class UnknownSend(DingError):
    pass


def schema(db):
    hr_departments.schema(db)
    db.execute("""CREATE TABLE IF NOT EXISTS hr_dingtalk_config(
      owner TEXT PRIMARY KEY,app_key TEXT NOT NULL,secret TEXT NOT NULL,
      agent_id TEXT NOT NULL,version INTEGER NOT NULL,verified INTEGER NOT NULL DEFAULT 0)""")
    db.execute("""CREATE TABLE IF NOT EXISTS hr_dingtalk_recipients(
      owner TEXT NOT NULL,demand_id TEXT NOT NULL,user_id TEXT NOT NULL,name TEXT NOT NULL,
      config_version INTEGER NOT NULL,PRIMARY KEY(owner,demand_id))""")
    db.execute("""CREATE TABLE IF NOT EXISTS hr_interview_handoffs(
      id TEXT PRIMARY KEY,owner TEXT NOT NULL,talent_id TEXT NOT NULL UNIQUE,
      request_id TEXT NOT NULL,body TEXT NOT NULL,status TEXT NOT NULL,error TEXT NOT NULL DEFAULT '',
      task_id TEXT NOT NULL DEFAULT '',created_at REAL NOT NULL,updated_at REAL NOT NULL,
      checked_at REAL NOT NULL DEFAULT 0,attempt INTEGER NOT NULL DEFAULT 1)""")


def cipher():
    from cryptography.fernet import Fernet
    with KEY_LOCK:
        KEY_FILE.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        try:
            fd = os.open(KEY_FILE, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            pass
        else:
            with os.fdopen(fd, "wb") as out:
                out.write(Fernet.generate_key())
                out.flush()
                os.fsync(out.fileno())
        if KEY_FILE.is_symlink() or KEY_FILE.stat().st_mode & 0o077:
            raise ValueError("钉钉凭证加密文件权限不正确")
        return Fernet(KEY_FILE.read_bytes())


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def api(path, data, token="", content_type="application/json", sending=False):
    """Fixed official hosts, bounded reads, no redirects or upstream error echo."""
    url = ("https://api.dingtalk.com/v1.0/oauth2/accessToken" if path == "token"
           else "https://oapi.dingtalk.com/" + path + "?" +
           urllib.parse.urlencode({"access_token": token}))
    raw = data if isinstance(data, bytes) else json.dumps(data, ensure_ascii=False).encode()
    req = urllib.request.Request(url, data=raw, headers={"Content-Type": content_type})
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open(req, timeout=25) as response:
            body = response.read(512001)
            if len(body) > 512000:
                raise ValueError()
            result = json.loads(body)
        if not isinstance(result, dict):
            raise ValueError()
    except urllib.error.HTTPError as exc:
        # HTTP transport failure is not proof that an outgoing message was rejected.
        if sending:
            raise UnknownSend("钉钉发送结果待核对，系统不会自动重发") from None
        raise DingError("钉钉调用未完成，请检查应用凭证、权限和服务器出口白名单") from None
    except (OSError, ValueError, TimeoutError):
        kind = UnknownSend if sending else DingError
        raise kind("钉钉发送结果待核对，系统不会自动重发" if sending else
                   "钉钉连接失败，请检查配置或稍后手动重试") from None
    if path != "token" and str(result.get("errcode", "")) != "0":
        code = str(result.get("errcode", ""))
        if sending and not re.fullmatch(r"-?\d{1,10}", code):
            raise UnknownSend("钉钉未返回明确结果，系统不会自动重发")
        suffix = f"（错误码 {code}）" if re.fullmatch(r"-?\d{1,10}", code) else ""
        if path == "topapi/v2/user/getbymobile":
            raise DingError("手机号识别失败，请核对企业在职员工手机号，并开通“根据手机号获取成员基本信息权限”及对应通讯录范围" + suffix)
        raise DingError("钉钉未接受请求，请检查权限、可见范围、UserID 和发送限额" + suffix)
    return result


def token(config):
    digest = hashlib.sha256((config["app_key"] + config["secret"]).encode()).hexdigest()
    with TOKEN_LOCK:
        cached = TOKENS.get(digest)
        if cached and cached[1] > time.time():
            return cached[0]
        secret = cipher().decrypt(config["secret"].encode()).decode()
        result = api("token", {"appKey": config["app_key"], "appSecret": secret})
        value = result.get("accessToken")
        if not isinstance(value, str) or not value:
            raise DingError("应用凭证校验失败，请核对 Client ID 和 Client Secret")
        expires = result.get("expireIn", 7200)
        if type(expires) is not int or not 60 <= expires <= 86400:
            raise DingError("钉钉未返回有效的凭证有效期，请稍后手动重试")
        TOKENS.clear()
        TOKENS[digest] = (value, time.time() + min(7200, expires) - 60)
        return value


def read_config(owner):
    with pool.database() as db:
        schema(db)
        row = db.execute("SELECT * FROM hr_dingtalk_config WHERE owner=?", (owner,)).fetchone()
    if not row or not row["verified"]:
        raise ValueError("请先在钉钉设置中保存并校验企业内部应用")
    return dict(row)


def public_config(row):
    return {"appKey": row["app_key"], "agentId": row["agent_id"], "hasSecret": bool(row["secret"]),
            "version": row["version"], "verified": bool(row["verified"])} if row else {
                "appKey": "", "agentId": "", "hasSecret": False, "version": 0, "verified": False}


def settings(user):
    owner, _ = pool.context(user)
    with pool.database() as db:
        schema(db); demands.schema(db)
        row = db.execute("SELECT * FROM hr_dingtalk_config WHERE owner=?", (owner,)).fetchone()
        choices = []
        for demand in db.execute("SELECT * FROM recruitment_demands WHERE owner=? ORDER BY updated_at DESC", (owner,)):
            value = json.loads(demand["body"])
            recipient = recipient_for_demand(db, owner, demand["id"], value, row)
            choices.append({"id": demand["id"], "position": value["position"],
                            "department": value.get("department", ""), "manager": value.get("manager", ""),
                            "jobId": value.get("jobId", ""),
                            "recipient": {"userId": recipient["user_id"], "name": recipient["name"],
                                          "valid": recipient["valid"], "source": recipient["source"]}
                            if recipient else None})
        return {"config": public_config(row), "demands": choices}


def save_config(user, body):
    owner, actor = pool.context(user)
    key = pool.text(body.get("appKey", ""), 200)
    agent = pool.text(body.get("agentId", ""), 30)
    secret = pool.text(body.get("appSecret", ""), 500)
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,200}", key) or not re.fullmatch(r"[1-9]\d{0,18}", agent):
        raise ValueError("请填写 Client ID 和有效的 AgentId")
    with pool.database() as db:
        schema(db)
        old = db.execute("SELECT * FROM hr_dingtalk_config WHERE owner=?", (owner,)).fetchone()
        if body.get("version") != (old["version"] if old else 0):
            raise pool.Conflict("钉钉配置已更新，请重新打开设置")
        if not secret and (not old or old["app_key"] != key or old["agent_id"] != agent):
            raise ValueError("更换应用时，请同时填写 Client Secret")
        encrypted = cipher().encrypt(secret.encode()).decode() if secret else old["secret"]
    candidate = {"app_key": key, "secret": encrypted}
    token(candidate)  # Credential check only: no message or résumé upload.
    with pool.database() as db:
        schema(db)
        current = db.execute("SELECT * FROM hr_dingtalk_config WHERE owner=?", (owner,)).fetchone()
        if (current["version"] if current else 0) != body.get("version"):
            raise pool.Conflict("钉钉配置已更新，请重新打开设置")
        same = bool(current and current["app_key"] == key and current["agent_id"] == agent
                    and current["secret"] == encrypted)
        version = body["version"] if same else body["version"] + 1
        db.execute("INSERT OR REPLACE INTO hr_dingtalk_config VALUES(?,?,?,?,?,1)",
                   (owner, key, encrypted, agent, version))
        pool.event(db, owner, actor, owner, "dingtalk.config", None,
                   {"version": version, "verified": True})
    return {"config": public_config({"app_key": key, "agent_id": agent, "secret": encrypted,
                                    "version": version, "verified": 1})}


def employee(config, user_id):
    result = api("topapi/v2/user/get", {"userid": user_id, "language": "zh_CN"}, token(config)).get("result", {})
    if not isinstance(result, dict) or result.get("userid") != user_id or not result.get("name"):
        raise ValueError("无法核对负责人，请确认该员工属于当前企业且在应用通讯录权限范围内")
    return pool.text(result["name"], 100)


def department_identity(user, body):
    """Read one employee, return a short-lived proof; never store the full phone."""
    owner, _ = pool.context(user)
    record = pool.text(body.get("id", ""), 36)
    mobile = re.sub(r"[\s()-]", "", pool.text(body.get("mobile", ""), 30))
    if mobile.startswith("+86"):
        mobile = mobile[3:]
    if not re.fullmatch(r"1[3-9]\d{9}", mobile):
        raise ValueError("请填写负责人在企业钉钉通讯录中的 11 位手机号")
    config = read_config(owner)
    if config["version"] != body.get("configVersion"):
        raise pool.Conflict("钉钉应用已更新，请刷新部门后重新识别")
    with pool.database() as db:
        schema(db)
        department = db.execute("SELECT * FROM hr_departments WHERE owner=? AND id=?", (owner, record)).fetchone()
        if not department or department["status"] != "ACTIVE":
            raise ValueError("请先保存并启用该部门")
        if department["version"] != body.get("version"):
            raise pool.Conflict("部门已经更新，请刷新后重新识别")
    result = api("topapi/v2/user/getbymobile", {"mobile": mobile}, token(config)).get("result", {})
    user_id = result.get("userid", "") if isinstance(result, dict) else ""
    if not isinstance(user_id, str) or not re.fullmatch(r"[\w@.+~-]{1,100}", user_id):
        raise ValueError("未识别到当前企业的在职员工，请核对手机号和应用通讯录权限")
    name = employee(config, user_id)
    mask = mobile[:3] + "****" + mobile[-4:]
    proof = {"owner": owner, "departmentId": record, "departmentVersion": department["version"],
             "configVersion": config["version"], "userId": user_id, "name": name,
             "mobileMasked": mask, "issuedAt": time.time()}
    return {"name": name, "mobileMasked": mask,
            "identityProof": cipher().encrypt(json.dumps(proof, ensure_ascii=False).encode()).decode()}


def bind_department(user, body):
    owner, actor = pool.context(user)
    from cryptography.fernet import InvalidToken
    try:
        proof = json.loads(cipher().decrypt(pool.text(body.get("identityProof", ""), 4000).encode(), ttl=300))
    except (InvalidToken, ValueError, TypeError):
        raise ValueError("身份识别结果已失效，请重新输入手机号识别") from None
    if (not isinstance(proof, dict) or proof.get("owner") != owner
            or proof.get("departmentId") != body.get("id")):
        raise ValueError("身份识别结果与当前部门不一致，请重新识别")
    with pool.database() as db:
        schema(db)
        department = db.execute("SELECT * FROM hr_departments WHERE owner=? AND id=?",
                                (owner, proof["departmentId"])).fetchone()
        config = db.execute("SELECT * FROM hr_dingtalk_config WHERE owner=?", (owner,)).fetchone()
        if not config or not config["verified"] or config["version"] != proof["configVersion"]:
            raise pool.Conflict("钉钉应用已更新，请重新识别负责人")
        if (not department or department["status"] != "ACTIVE"
                or department["version"] != proof["departmentVersion"]):
            raise pool.Conflict("部门已经更新，请刷新后重新识别负责人")
        db.execute("INSERT OR REPLACE INTO hr_department_dingtalk VALUES(?,?,?,?,?,?)",
                   (owner, department["id"], proof["userId"], proof["name"], proof["mobileMasked"], config["version"]))
        db.execute("UPDATE hr_departments SET manager=?,version=version+1 WHERE owner=? AND id=?",
                   (proof["name"], owner, department["id"]))
        pool.event(db, owner, actor, department["id"], "hr_department.dingtalk_bind", None,
                   {"name": proof["name"], "mobileMasked": proof["mobileMasked"], "configVersion": config["version"]})
    return {"name": proof["name"], "mobileMasked": proof["mobileMasked"]}


def recipient_for_demand(db, owner, demand_id, value, config):
    """An exact department link owns routing. Never fall back to a different person."""
    department_id = value.get("departmentId", "")
    if department_id:
        department = db.execute("SELECT * FROM hr_departments WHERE owner=? AND id=?",
                                (owner, department_id)).fetchone()
        row = db.execute("SELECT * FROM hr_department_dingtalk WHERE owner=? AND department_id=?",
                         (owner, department_id)).fetchone()
        if not department or not row:
            return None
        valid = department["status"] == "ACTIVE" and department["manager"] == row["name"]
        source, binding_key = "department", f"department:{department_id}:{department['version']}"
    else:
        row = db.execute("SELECT * FROM hr_dingtalk_recipients WHERE owner=? AND demand_id=?",
                         (owner, demand_id)).fetchone()
        if not row:
            return None
        valid = True
        source, binding_key = "demand", ""
    return {**dict(row), "source": source, "bindingKey": binding_key,
            "valid": bool(valid and config and config["verified"] and row["config_version"] == config["version"])}


def save_recipient(user, body):
    owner, actor = pool.context(user)
    record = pool.text(body.get("demandId", ""), 100)
    user_id = pool.text(body.get("userId", ""), 100)
    if not re.fullmatch(r"[\w@.+~-]{1,100}", user_id):
        raise ValueError("请填写负责人的企业 UserID，不是手机号或昵称")
    config = read_config(owner)
    if body.get("configVersion") != config["version"]:
        raise pool.Conflict("钉钉应用已更新，请刷新设置")
    with pool.database() as db:
        schema(db); demands.schema(db)
        demand = db.execute("SELECT * FROM recruitment_demands WHERE owner=? AND id=?", (owner, record)).fetchone()
        if not demand:
            raise ValueError("招聘需求不存在，请先在招聘需求池关联具体职位")
        if json.loads(demand["body"]).get("departmentId"):
            raise ValueError("该岗位使用归属部门的负责人，请到部门管理通过手机号绑定")
    name = employee(config, user_id)
    with pool.database() as db:
        schema(db); demands.schema(db)
        current = db.execute("SELECT version FROM hr_dingtalk_config WHERE owner=?", (owner,)).fetchone()
        if not current or current["version"] != config["version"]:
            raise pool.Conflict("钉钉应用已更新，请刷新设置")
        if not db.execute("SELECT 1 FROM recruitment_demands WHERE owner=? AND id=?", (owner, record)).fetchone():
            raise ValueError("招聘需求不存在，请先在招聘需求池关联具体职位")
        db.execute("INSERT OR REPLACE INTO hr_dingtalk_recipients VALUES(?,?,?,?,?)",
                   (owner, record, user_id, name, config["version"]))
        pool.event(db, owner, actor, record, "dingtalk.recipient", None, {"name": name})
    return {"name": name, "userId": user_id}


def handoff_view(row):
    if not row:
        return None
    value = json.loads(row["body"])
    return {"id": row["id"], "status": row["status"], "error": row["error"],
            "recipient": value["recipientName"], "position": value["position"],
            "updatedAt": row["updated_at"], "attempt": row["attempt"]}


def preview(user, query):
    owner, _ = pool.context(user)
    record = pool.text(query.get("talentId", ""), 100)
    with pool.database() as db:
        passive_pool.schema(db); schema(db); demands.schema(db)
        talent = db.execute("SELECT * FROM talents WHERE owner=? AND id=?", (owner, record)).fetchone()
        if not talent:
            raise ValueError("人才不存在")
        resumes = []
        for row in db.execute("""SELECT * FROM hr_resume_pool WHERE owner=? AND status='approved'
          AND json_extract(body,'$.talentId')=? ORDER BY updated_at DESC LIMIT 50""", (owner, record)):
            value = json.loads(row["body"])
            if value.get("talentId") == record and value.get("jobId"):
                resumes.append({"id": row["id"], "filename": value["fileName"], "jobId": value["jobId"],
                                "account": row["account"], "jobName": value["jobName"]})
        choices = []
        config = db.execute("SELECT * FROM hr_dingtalk_config WHERE owner=?", (owner,)).fetchone()
        for row in db.execute("""SELECT * FROM recruitment_demands WHERE owner=? AND EXISTS (
          SELECT 1 FROM hr_resume_pool r WHERE r.owner=recruitment_demands.owner AND r.status='approved'
          AND json_extract(r.body,'$.talentId')=? AND r.account=json_extract(recruitment_demands.body,'$.jobAccountKey')
          AND json_extract(r.body,'$.jobId')=json_extract(recruitment_demands.body,'$.jobId'))
          ORDER BY updated_at DESC""", (owner, record)):
            value = json.loads(row["body"])
            matches = [r["id"] for r in resumes if r["jobId"] == value.get("jobId") and r["account"] == value.get("jobAccountKey")]
            if not matches:
                continue
            recipient = recipient_for_demand(db, owner, row["id"], value, config)
            questions = json.loads(row["ai_result"]).get("interviewQuestions", [])
            ready = row["ai_status"] == "complete" and row["ai_version"] == row["version"] and bool(questions)
            choices.append({"id": row["id"], "version": row["version"], "position": value["position"],
                            "department": value.get("department", ""), "resumeIds": matches,
                            "questionCount": len(questions), "questionsReady": ready,
                            "recipient": recipient["name"] if recipient else "",
                            "recipientId": recipient["user_id"] if recipient else "",
                            "recipientBindingKey": recipient["bindingKey"] if recipient else "",
                            "recipientReady": bool(recipient and recipient["valid"])})
        job = db.execute("SELECT * FROM hr_interview_handoffs WHERE owner=? AND talent_id=?", (owner, record)).fetchone()
    if job and job["status"] == "submitted":
        reconcile(owner, job["id"])
        with pool.database() as db:
            schema(db)
            job = db.execute("SELECT * FROM hr_interview_handoffs WHERE owner=? AND talent_id=?", (owner, record)).fetchone()
    return {"talentId": record, "name": pool.view(talent)["name"], "version": talent["version"],
            "stage": talent["stage"], "resumes": resumes, "demands": choices,
            "config": public_config(config), "handoff": handoff_view(job)}


def start(user, body):
    owner, actor = pool.context(user)
    record = pool.text(body.get("talentId", ""), 100)
    try:
        request_id = str(uuid.UUID(body.get("requestId", "")))
    except (ValueError, AttributeError, TypeError):
        raise ValueError("操作标识不正确")
    with pool.database() as db:
        passive_pool.schema(db); schema(db); demands.schema(db)
        old = db.execute("SELECT * FROM hr_interview_handoffs WHERE owner=? AND talent_id=?", (owner, record)).fetchone()
        if old and (old["request_id"] == request_id or old["status"] != "failed"):
            return handoff_view(old)
        if old and body.get("retryId") != old["id"]:
            raise pool.Conflict("本次发送未完成，请在发送记录中手动重试")
        talent = db.execute("SELECT * FROM talents WHERE owner=? AND id=?", (owner, record)).fetchone()
        resume = db.execute("SELECT * FROM hr_resume_pool WHERE owner=? AND id=? AND status='approved'",
                            (owner, pool.text(body.get("resumeId", ""), 100))).fetchone()
        demand = db.execute("SELECT * FROM recruitment_demands WHERE owner=? AND id=?",
                            (owner, pool.text(body.get("demandId", ""), 100))).fetchone()
        config = db.execute("SELECT * FROM hr_dingtalk_config WHERE owner=?", (owner,)).fetchone()
        if not talent or talent["stage"] != "invited" or talent["version"] != body.get("version"):
            raise pool.Conflict("人才阶段或资料已更新，请刷新人才卡片")
        if not resume or json.loads(resume["body"]).get("talentId") != record:
            raise ValueError("请选择该候选人已审批通过的简历")
        detail, jd = json.loads(resume["body"]), json.loads(demand["body"]) if demand else {}
        recipient = recipient_for_demand(db, owner, demand["id"], jd, config) if demand else None
        if not demand or detail.get("jobId") != jd.get("jobId") or resume["account"] != jd.get("jobAccountKey"):
            raise ValueError("简历与招聘需求必须属于同一账号的同一具体职位")
        questions = json.loads(demand["ai_result"]).get("interviewQuestions", [])
        if demand["version"] != body.get("demandVersion") or demand["ai_status"] != "complete" or demand["ai_version"] != demand["version"] or not questions:
            raise ValueError("请先在招聘需求池生成当前岗位的面试题库")
        if not config or not config["verified"] or config["version"] != body.get("configVersion"):
            raise ValueError("请先配置并校验钉钉应用，或刷新最新配置")
        if (not recipient or not recipient["valid"] or recipient["user_id"] != body.get("recipientId")
                or recipient["bindingKey"] != body.get("recipientBindingKey", "")):
            raise ValueError("负责人映射已变更或未配置，请在部门管理中重新核对钉钉绑定")
        snapshot = {"name": pool.view(talent)["name"], "position": jd["position"],
                    "department": jd.get("department", ""), "resumeId": resume["id"],
                    "resumeKey": resume["file_key"], "resumeHash": resume["file_hash"],
                    "resumeFilename": detail["fileName"], "extension": detail["extension"],
                    "jd": jd.get("jobDescription", ""), "questions": questions,
                    "demandId": demand["id"], "demandVersion": demand["version"],
                    "recipientId": recipient["user_id"], "recipientName": recipient["name"],
                    "recipientBindingKey": recipient["bindingKey"],
                    "configVersion": config["version"], "agentId": config["agent_id"],
                    "talentVersion": talent["version"]}
        job_id = old["id"] if old else str(uuid.uuid4())
        attempt = old["attempt"] + 1 if old else 1
        now = time.time()
        db.execute("""INSERT OR REPLACE INTO hr_interview_handoffs
          (id,owner,talent_id,request_id,body,status,created_at,updated_at,attempt)
          VALUES(?,?,?,?,?,'preparing',?,?,?)""",
                   (job_id, owner, record, request_id, json.dumps(snapshot, ensure_ascii=False), now, now, attempt))
        pool.event(db, owner, actor, record, "interview.start", None,
                   {"handoffId": job_id, "recipient": recipient["name"], "demandId": demand["id"]})
        result = handoff_view(db.execute("SELECT * FROM hr_interview_handoffs WHERE id=?", (job_id,)).fetchone())
    launch(owner, job_id)
    return result


def package(value, raw):
    resume_storage.validate(raw, value["extension"])
    if hashlib.sha256(raw).hexdigest() != value["resumeHash"]:
        raise ValueError("简历内容校验不一致，未发送")
    esc = lambda x: html.escape(str(x))
    blocks = []
    for index, question in enumerate(value["questions"], 1):
        if not isinstance(question, dict) or not question.get("question"):
            raise ValueError("岗位面试题库格式不完整，请重新生成")
        blocks.append(f'<section><h2>{index}. {esc(question.get("competency", ""))}</h2>'
                      f'<p>{esc(question["question"])}</p><h3>建议追问</h3><ul>' +
                      "".join(f"<li>{esc(x)}</li>" for x in question.get("followUps", [])) +
                      f'</ul><h3>评分标准</h3><p>{esc(question.get("evaluationCriteria", ""))}</p>'
                      '<h3>优秀答案信号</h3><ul>' +
                      "".join(f"<li>{esc(x)}</li>" for x in question.get("strongAnswerSignals", [])) + '</ul></section>')
    document = ('<!doctype html><html lang="zh"><meta charset="utf-8"><title>一面面试题库</title>'
                '<style>body{max-width:880px;margin:40px auto;padding:24px;font:16px/1.8 sans-serif;'
                'color:#171717}section{border-top:1px solid #ddd;padding:16px 0}h2{font-size:20px}</style>'
                f'<h1>{esc(value["position"])} · 一面面试题库</h1><p>候选人：{esc(value["name"])}'
                f' · 负责人：{esc(value["recipientName"])}</p>' + "".join(blocks) + '</html>')
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("候选人简历." + value["extension"], raw)
        z.writestr("一面面试题库.html", document.encode())
        z.writestr("岗位JD.txt", value["jd"].encode())
    content = buf.getvalue()
    if len(content) > MAX_PACKAGE:
        raise ValueError("一面资料包超过钉钉 20MB 上限，请压缩简历后重试")
    return content


def upload(config, value, content):
    boundary = "heyi" + uuid.uuid4().hex
    filename = re.sub(r'[^\w\u4e00-\u9fff.-]', "_", value["name"] + "-" + value["position"])[:90] + "-一面资料.zip"
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"type\"\r\n\r\nfile\r\n"
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"media\"; filename=\"{filename}\"\r\n"
            "Content-Type: application/zip\r\n\r\n").encode() + content + f"\r\n--{boundary}--\r\n".encode()
    result = api("media/upload", body, token(config), "multipart/form-data; boundary=" + boundary)
    media = result.get("media_id")
    if not isinstance(media, str) or not media:
        raise DingError("钉钉未返回文件标识，资料未发送")
    return media


def launch(owner, job_id):
    with WORK_LOCK:
        if job_id in WORKERS:
            return
        WORKERS.add(job_id)
    def run():
        try:
            deliver(owner, job_id)
        finally:
            with WORK_LOCK:
                WORKERS.discard(job_id)
    threading.Thread(target=run, daemon=True).start()


def deliver(owner, job_id):
    started = False
    try:
        with SEND_LOCK:
            with pool.database() as db:
                schema(db)
                row = db.execute("SELECT * FROM hr_interview_handoffs WHERE owner=? AND id=? AND status='preparing'",
                                 (owner, job_id)).fetchone()
                if not row:
                    return
                value = json.loads(row["body"])
            config = read_config(owner)
            if config["version"] != value["configVersion"]:
                raise ValueError("钉钉配置已变更，请核对负责人后手动重试")
            if employee(config, value["recipientId"]) != value["recipientName"]:
                raise ValueError("负责人姓名已变更，请在钉钉设置中重新校验")
            content = package(value, resume_storage.download(owner, value["resumeKey"]))
            media = upload(config, value, content)
            access_token = token(config)
            with pool.database() as db:
                schema(db); demands.schema(db)
                talent = db.execute("SELECT * FROM talents WHERE owner=? AND id=?", (owner, row["talent_id"])).fetchone()
                if not talent or talent["stage"] != "invited" or talent["version"] != value["talentVersion"]:
                    raise ValueError("人才资料已变化，未发送；请刷新后重试")
                current_config = db.execute("SELECT version FROM hr_dingtalk_config WHERE owner=?", (owner,)).fetchone()
                current_demand = db.execute("SELECT * FROM recruitment_demands WHERE owner=? AND id=?",
                                            (owner, value["demandId"])).fetchone()
                recipient = recipient_for_demand(db, owner, value["demandId"], json.loads(current_demand["body"]),
                                                config) if current_demand else None
                if (not current_config or current_config["version"] != value["configVersion"] or not current_demand
                        or current_demand["version"] != value["demandVersion"] or not recipient
                        or not recipient["valid"] or recipient["user_id"] != value["recipientId"]
                        or recipient["name"] != value["recipientName"]
                        or recipient["bindingKey"] != value.get("recipientBindingKey", "")):
                    raise ValueError("岗位或负责人配置已变化，未发送；请刷新后重试")
                changed = db.execute("UPDATE hr_interview_handoffs SET status='sending',updated_at=? WHERE id=? AND status='preparing'",
                                     (time.time(), job_id)).rowcount
                if not changed:
                    return
            started = True
            result = api("topapi/message/corpconversation/asyncsend_v2",
                         {"agent_id": int(value["agentId"]), "userid_list": value["recipientId"],
                          "to_all_user": False, "msg": {"msgtype": "file", "file": {"media_id": media}}},
                         access_token, sending=True)
            task = str(result.get("task_id", ""))
            if not re.fullmatch(r"\d{1,30}", task):
                raise UnknownSend("钉钉未返回可核对的发送任务，系统不会自动重发")
            with pool.database() as db:
                schema(db)
                db.execute("UPDATE hr_interview_handoffs SET status='submitted',task_id=?,updated_at=? WHERE id=? AND status='sending'",
                           (task, time.time(), job_id))
        reconcile(owner, job_id)
    except Exception as error:
        uncertain = started and (isinstance(error, UnknownSend) or not isinstance(error, (DingError, ValueError)))
        message = str(error) if isinstance(error, ValueError) else "资料发送未完成，请检查配置后手动重试"
        with pool.database() as db:
            schema(db)
            db.execute("""UPDATE hr_interview_handoffs SET status=?,error=?,updated_at=?
              WHERE id=? AND status IN ('preparing','sending')""",
                       ("uncertain" if uncertain else "failed", message[:400], time.time(), job_id))


def reconcile(owner, job_id):
    with pool.database() as db:
        schema(db)
        row = db.execute("SELECT * FROM hr_interview_handoffs WHERE owner=? AND id=? AND status='submitted'",
                         (owner, job_id)).fetchone()
        if not row or time.time() - row["checked_at"] < 15:
            return
        if time.time() - row["created_at"] > 23*3600:
            db.execute("UPDATE hr_interview_handoffs SET status='uncertain',error=? WHERE id=?",
                       ("钉钉回执查询时限已到，请人工核对；不会自动重发", job_id))
            return
        db.execute("UPDATE hr_interview_handoffs SET checked_at=? WHERE id=?", (time.time(), job_id))
        value = json.loads(row["body"])
    try:
        config = read_config(owner)
        if config["version"] != value["configVersion"]:
            return  # A different enterprise application must not query this task.
        result = api("topapi/message/corpconversation/getsendresult",
                     {"agent_id": int(value["agentId"]), "task_id": int(row["task_id"])},
                     token(config)).get("send_result", {})
        if not isinstance(result, dict):
            return
        uid = value["recipientId"]
        failed = uid in sum((result.get(key, []) for key in ("failed_user_id_list", "invalid_user_id_list")
                            if isinstance(result.get(key), list)), [])
        forbidden = any(isinstance(x, dict) and (x.get("userid") or x.get("user_id")) == uid
                        or x == uid for x in result.get("forbidden_list", []))
        success = uid in result.get("read_user_id_list", []) or uid in result.get("unread_user_id_list", [])
        if not (failed or forbidden or success):
            return
        with pool.database() as db:
            schema(db)
            changed = db.execute("""UPDATE hr_interview_handoffs SET status=?,error=?,updated_at=?
              WHERE owner=? AND id=? AND status='submitted'""",
                ("failed" if failed or forbidden else "sent",
                 "钉钉回执显示未送达，请检查负责人可见范围或发送限额" if failed or forbidden else "",
                 time.time(), owner, job_id)).rowcount
            if changed and success and not (failed or forbidden):
                talent = db.execute("SELECT * FROM talents WHERE owner=? AND id=?", (owner, row["talent_id"])).fetchone()
                if talent and talent["stage"] == "invited" and talent["version"] == value["talentVersion"]:
                    db.execute("UPDATE talents SET stage='first',version=version+1,updated_at=? WHERE owner=? AND id=?",
                               (pool.datetime.datetime.now(pool.datetime.timezone.utc).isoformat(), owner, row["talent_id"]))
                pool.event(db, owner, "dingtalk-receipt", row["talent_id"], "interview.delivered", None,
                           {"handoffId": job_id, "recipient": value["recipientName"]})
    except Exception:
        pass  # Receipt failures never resend or erase the known task ID.


def recover():
    """Restart conservatively: no process can prove an interrupted send failed."""
    with pool.database() as db:
        schema(db)
        db.execute("UPDATE hr_interview_handoffs SET status='failed',error=? WHERE status='preparing'",
                   ("服务更新中断了资料准备，尚未发出消息，请手动重试",))
        db.execute("UPDATE hr_interview_handoffs SET status='uncertain',error=? WHERE status='sending'",
                   ("服务更新时钉钉结果未确认，请人工核对；不会自动重发",))


def receipt_worker():
    """Only query already-known tasks; closing the UI must not stop receipts."""
    while True:
        try:
            with pool.database() as db:
                schema(db)
                rows = db.execute("""SELECT owner,id FROM hr_interview_handoffs
                  WHERE status='submitted' AND checked_at<? ORDER BY checked_at LIMIT 20""",
                                  (time.time()-60,)).fetchall()
            for row in rows:
                reconcile(row["owner"], row["id"])
        except Exception:
            pass
        threading.Event().wait(15)
