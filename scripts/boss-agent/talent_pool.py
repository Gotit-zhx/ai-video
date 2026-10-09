"""Platform-owned talent records. Never imports the CLI or contacts BOSS."""
import contextlib
import datetime
import json
import sqlite3
import uuid
from pathlib import Path

from desktop_bridge import identity

DB = Path("/opt/heyi/boss-agent/runtime/talent-pool.sqlite3")
STAGES = ("", "invited", "first", "second", "onboarding", "hired")
FIELDS = {"name": 100, "position": 200, "education": 100, "experience": 100,
          "phone": 100, "city": 100, "company": 200, "notes": 5000,
          "sourceId": 180, "source": 30, "departmentId": 36}


class Conflict(ValueError):
    pass


@contextlib.contextmanager
def database():
    DB.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with contextlib.closing(sqlite3.connect(DB, timeout=10)) as db:
        db.row_factory = sqlite3.Row
        db.executescript("""
          CREATE TABLE IF NOT EXISTS projects (
            id TEXT PRIMARY KEY, owner TEXT NOT NULL, name TEXT NOT NULL,
            version INTEGER NOT NULL, UNIQUE(owner,name));
          CREATE TABLE IF NOT EXISTS talents (
            id TEXT PRIMARY KEY, owner TEXT NOT NULL, project TEXT NOT NULL,
            stage TEXT NOT NULL, source_id TEXT NOT NULL, body TEXT NOT NULL,
            version INTEGER NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL);
          CREATE UNIQUE INDEX IF NOT EXISTS talent_source ON talents(owner,source_id)
            WHERE source_id <> '';
          CREATE INDEX IF NOT EXISTS talent_filter ON talents(owner,project,stage);
          CREATE TABLE IF NOT EXISTS talent_events (
            id INTEGER PRIMARY KEY, owner TEXT NOT NULL, actor TEXT NOT NULL,
            record_id TEXT NOT NULL, action TEXT NOT NULL, before_json TEXT,
            after_json TEXT NOT NULL, created_at TEXT NOT NULL);
          CREATE TABLE IF NOT EXISTS hr_interview_reviews (
            owner TEXT NOT NULL,talent_id TEXT NOT NULL,round TEXT NOT NULL,
            outcome TEXT NOT NULL,opinion TEXT NOT NULL,request_id TEXT NOT NULL,
            actor TEXT NOT NULL,created_at TEXT NOT NULL,
            PRIMARY KEY(owner,talent_id,round));
          CREATE INDEX IF NOT EXISTS interview_review_filter
            ON hr_interview_reviews(owner,round,outcome,talent_id);
        """)
        DB.chmod(0o600)
        db.execute("BEGIN IMMEDIATE")
        try:
            yield db
            db.commit()
        except BaseException:
            db.rollback()
            raise


def context(user):
    checked = identity(user)
    return checked["organizationCode"], str(checked.get("id") or checked.get("userId") or checked["username"])


def text(value, limit):
    if not isinstance(value, str) or len(value) > limit:
        raise ValueError("字段内容格式不正确或超过长度限制")
    return value.strip()


def view(row, db=None):
    reviews = {} if db is None else {
        review["round"]: {"outcome": review["outcome"], "opinion": review["opinion"],
                          "createdAt": review["created_at"]}
        for review in db.execute("SELECT * FROM hr_interview_reviews WHERE owner=? AND talent_id=?",
                                 (row["owner"], row["id"]))}
    return {**json.loads(row["body"]), "id": row["id"], "projectId": row["project"],
            "stage": row["stage"], "version": row["version"],
            "createdAt": row["created_at"], "updatedAt": row["updated_at"], "interviewReviews": reviews}


def event(db, owner, actor, record, action, before, after):
    db.execute("INSERT INTO talent_events(owner,actor,record_id,action,before_json,after_json,created_at) VALUES(?,?,?,?,?,?,?)",
               (owner, actor, record, action, json.dumps(before, ensure_ascii=False),
                json.dumps(after, ensure_ascii=False), datetime.datetime.now(datetime.timezone.utc).isoformat()))


def department_for_resumes(db, owner, resumes, talent_id=""):
    """Resolve exact job/account links, or a confirmed interview demand."""
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if "recruitment_demands" not in tables:
        return ""
    demands = {r["id"]: json.loads(r["body"]) for r in db.execute(
        "SELECT id,body FROM recruitment_demands WHERE owner=?", (owner,))}
    if talent_id and "hr_interview_handoffs" in tables:
        sent = db.execute("SELECT body FROM hr_interview_handoffs WHERE owner=? AND talent_id=? AND status='sent' ORDER BY created_at ASC",
                          (owner, talent_id)).fetchone()
        if sent:
            return demands.get(json.loads(sent["body"]).get("demandId"), {}).get("departmentId", "")
    matches = set()
    for account, detail in resumes:
        if not account or not detail.get("jobId"):
            continue
        for demand in demands.values():
            if demand.get("jobAccountKey") == account and demand.get("jobId") == detail["jobId"]:
                matches.add(demand.get("departmentId", ""))
    return next(iter(matches)) if len(matches) == 1 else ""


def backfill(db, owner):
    """Fill stored source records locally; no BOSS, OSS or model requests."""
    import hr_departments
    import resume_storage
    hr_departments.schema(db)
    tables = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    if "hr_resume_pool" not in tables:
        return
    linked = {}
    for row in db.execute("SELECT * FROM hr_resume_pool WHERE owner=? AND status='approved' ORDER BY updated_at DESC,id", (owner,)):
        detail = json.loads(row["body"])
        if detail.get("talentId"):
            linked.setdefault(detail["talentId"], []).append((row["account"], detail))
    for row in db.execute("SELECT * FROM talents WHERE owner=?", (owner,)).fetchall():
        values = json.loads(row["body"])
        before = dict(values)
        resumes = linked.get(row["id"], [])
        for _, detail in resumes:
            facts = resume_storage.facts(detail.get("resumeText", ""))
            for field in ("education", "experience", "city", "company"):
                if not values.get(field):
                    values[field] = detail.get(field) or facts.get(field, "")
        if not values.get("departmentId"):
            department = department_for_resumes(db, owner, resumes, row["id"])
            if department and db.execute("SELECT 1 FROM hr_departments WHERE owner=? AND id=?", (owner, department)).fetchone():
                values["departmentId"] = department
        if values != before:
            db.execute("UPDATE talents SET body=?,version=version+1 WHERE owner=? AND id=?",
                       (json.dumps(values, ensure_ascii=False), owner, row["id"]))
            event(db, owner, "source-backfill", row["id"], "talent.source-backfill", before, values)


def listing(user, query):
    owner, _ = context(user)
    project = text(query.get("project", ""), 100)
    department = text(query.get("department", ""), 100)
    stage = text(query.get("stage", "all"), 20)
    outcome = text(query.get("outcome", "all"), 20)
    q = text(query.get("q", ""), 100)
    if stage not in (*STAGES, "all"):
        raise ValueError("招聘阶段不正确")
    if outcome not in ("all", "pending", "passed", "failed") or (outcome != "all" and stage not in ("first", "second")):
        raise ValueError("面试结果筛选不正确")
    try:
        page = max(1, int(query.get("page", "1")))
    except (ValueError, TypeError):
        raise ValueError("页码不正确")
    where, args = ["owner=?"], [owner]
    if project:
        where.append("project=?")
        args.append("" if project == "unassigned" else project)
    if department:
        where.append("COALESCE(json_extract(body,'$.departmentId'),'')=?")
        args.append("" if department == "unassigned" else department)
    if q:
        where.append("body LIKE ? ESCAPE '\\'")
        args.append("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
    with database() as db:
        backfill(db, owner)
        clause = " AND ".join(where)
        counts = {s: 0 for s in STAGES}
        counts.update({r["stage"]: r["n"] for r in db.execute(
            "SELECT stage,COUNT(*) n FROM talents WHERE " + clause + " GROUP BY stage", args)})
        counts["all"] = sum(counts.values())
        round_clause = """(stage=? OR EXISTS(SELECT 1 FROM hr_interview_reviews r
            WHERE r.owner=talents.owner AND r.talent_id=talents.id AND r.round=?))"""
        pending_clause = """stage=? AND NOT EXISTS(SELECT 1 FROM hr_interview_reviews r
            WHERE r.owner=talents.owner AND r.talent_id=talents.id AND r.round=?)"""
        pending_counts = {"invited": counts["invited"], "hired": counts["hired"]}
        for interview_round in ("first", "second"):
            pending_counts[interview_round] = db.execute(
                "SELECT COUNT(*) FROM talents WHERE " + clause + " AND " + pending_clause,
                [*args, interview_round, interview_round]).fetchone()[0]
            counts[interview_round] = db.execute(
                "SELECT COUNT(*) FROM talents WHERE " + clause + " AND " + round_clause,
                [*args, interview_round, interview_round]).fetchone()[0]
        outcome_counts = {"all": counts[stage], "pending": 0, "passed": 0, "failed": 0}
        if stage in ("first", "second"):
            where.append(round_clause)
            args.extend([stage, stage])
            scoped = " AND ".join(where)
            reviewed_clause = """EXISTS(SELECT 1 FROM hr_interview_reviews r
                WHERE r.owner=talents.owner AND r.talent_id=talents.id AND r.round=? AND r.outcome=?)"""
            outcome_counts["pending"] = db.execute("SELECT COUNT(*) FROM talents WHERE " + scoped +
                " AND " + pending_clause, [*args, stage, stage]).fetchone()[0]
            for value in ("passed", "failed"):
                outcome_counts[value] = db.execute("SELECT COUNT(*) FROM talents WHERE " + scoped +
                    " AND " + reviewed_clause, [*args, stage, value]).fetchone()[0]
            if outcome == "pending":
                where.append(pending_clause); args.extend([stage, stage])
            elif outcome != "all":
                where.append(reviewed_clause); args.extend([stage, outcome])
        elif stage != "all":
            where.append("stage=?")
            args.append(stage)
        total = outcome_counts[outcome]
        pages = max(1, (total + 19) // 20)
        page = min(page, pages)
        rows = db.execute("SELECT * FROM talents WHERE " + " AND ".join(where)
                          + " ORDER BY updated_at DESC,id LIMIT 20 OFFSET ?", [*args, (page-1)*20])
        items = [view(row, db) for row in rows]
        projects = [dict(row) for row in db.execute(
            "SELECT id,name,version FROM projects WHERE owner=? ORDER BY name,id", (owner,))]
        departments = [dict(row) for row in db.execute(
            "SELECT id,name,status FROM hr_departments WHERE owner=? ORDER BY name,id", (owner,))]
    return {"items": items, "projects": projects, "departments": departments, "counts": counts,
            "pendingCounts": pending_counts, "outcomeCounts": outcome_counts,
            "total": total, "page": page, "pages": pages}


def review_interview(user, body):
    """Record an HR decision once. First-round approval advances to round two."""
    owner, actor = context(user)
    if not isinstance(body, dict):
        raise ValueError("面试意见格式不正确")
    record = text(body.get("talentId", ""), 100)
    interview_round = text(body.get("round", ""), 20)
    outcome = text(body.get("outcome", ""), 20)
    opinion = text(body.get("opinion", ""), 5000)
    if interview_round not in ("first", "second") or outcome not in ("passed", "failed"):
        raise ValueError("请选择有效的面试轮次和结果")
    if not opinion:
        raise ValueError("请填写面试意见后选择通过或不通过")
    try:
        request_id = str(uuid.UUID(body.get("requestId", "")))
    except (ValueError, AttributeError, TypeError):
        raise ValueError("操作标识不正确")
    with database() as db:
        row = db.execute("SELECT * FROM talents WHERE owner=? AND id=?", (owner, record)).fetchone()
        if not row:
            raise ValueError("人才记录不存在")
        old = db.execute("SELECT * FROM hr_interview_reviews WHERE owner=? AND talent_id=? AND round=?",
                         (owner, record, interview_round)).fetchone()
        if old:
            if old["request_id"] == request_id and old["opinion"] == opinion and old["outcome"] == outcome:
                if interview_round == "second" and outcome == "passed":
                    import hr_onboarding
                    hr_onboarding.schema(db)
                    arrangement = db.execute("SELECT body FROM hr_onboarding_records WHERE owner=? AND talent_id=?",
                                              (owner, record)).fetchone()
                    submitted = hr_onboarding.validate(body.get("onboarding"))
                    if not arrangement or any(json.loads(arrangement["body"]).get(k) != v for k,v in submitted.items()):
                        raise Conflict("二面通过及入职安排已经保存，请到入职安排及跟进修改资料")
                return view(row, db)
            raise Conflict("该轮面试已记录结果，请刷新后查看意见")
        if row["stage"] != interview_round or row["version"] != body.get("version"):
            raise Conflict("人才阶段或资料已更新，请刷新后填写对应轮次意见")
        before = view(row, db)
        now = datetime.datetime.now(datetime.timezone.utc).isoformat()
        next_stage = "second" if interview_round == "first" and outcome == "passed" else interview_round
        if interview_round == "second" and outcome == "passed":
            import hr_onboarding
            arrangement = hr_onboarding.create(db, row, body.get("onboarding"))
            next_stage = "hired" if arrangement["joined"] else "onboarding"
        db.execute("INSERT INTO hr_interview_reviews VALUES(?,?,?,?,?,?,?,?)",
                   (owner, record, interview_round, outcome, opinion, request_id, actor, now))
        db.execute("UPDATE talents SET stage=?,version=version+1,updated_at=? WHERE owner=? AND id=?",
                   (next_stage, now, owner, record))
        result = view(db.execute("SELECT * FROM talents WHERE owner=? AND id=?", (owner, record)).fetchone(), db)
        event(db, owner, actor, record, "interview.review", before,
              {"round": interview_round, "outcome": outcome, "opinion": opinion, "stage": next_stage})
    return result


def save_project(user, body):
    owner, actor = context(user)
    if not isinstance(body, dict):
        raise ValueError("项目组格式不正确")
    name = text(body.get("name", ""), 80)
    if not name:
        raise ValueError("请输入项目组名称")
    record = text(body.get("id", ""), 100)
    with database() as db:
        old = db.execute("SELECT id,name,version FROM projects WHERE owner=? AND id=?", (owner, record)).fetchone()
        if record and not old:
            raise ValueError("项目组不存在")
        if old and body.get("version") != old["version"]:
            raise Conflict("项目组已更新，请刷新后重试")
        duplicate = db.execute("SELECT id FROM projects WHERE owner=? AND name=?", (owner, name)).fetchone()
        if duplicate and duplicate["id"] != record:
            raise Conflict("同名项目组已存在")
        record = record or str(uuid.uuid4())
        result = {"id": record, "name": name, "version": old["version"]+1 if old else 1}
        if old:
            db.execute("UPDATE projects SET name=?,version=? WHERE owner=? AND id=?",
                       (name, result["version"], owner, record))
        else:
            db.execute("INSERT INTO projects VALUES(?,?,?,1)", (record, owner, name))
        event(db, owner, actor, record, "project.update" if old else "project.create", dict(old) if old else None, result)
    return result


def save(user, body):
    owner, actor = context(user)
    if not isinstance(body, dict):
        raise ValueError("人才资料格式不正确")
    record = text(body.get("id", ""), 100)
    creating = not record
    create_id = text(body.get("createId", ""), 100)
    if creating and create_id:
        try:
            record = str(uuid.UUID(create_id))
        except ValueError:
            raise ValueError("新建请求标识不正确")
    clean = {key: text(body.get(key, ""), limit) for key, limit in FIELDS.items()}
    if not clean["name"]:
        raise ValueError("请输入候选人姓名")
    stage, project = text(body.get("stage", ""), 20), text(body.get("projectId", ""), 100)
    if stage not in STAGES:
        raise ValueError("招聘阶段不正确")
    with database() as db:
        import hr_departments
        hr_departments.schema(db)
        if clean["departmentId"] and not db.execute(
                "SELECT 1 FROM hr_departments WHERE owner=? AND id=?", (owner, clean["departmentId"])).fetchone():
            raise ValueError("所选部门不存在，请刷新后重试")
        if project and not db.execute("SELECT 1 FROM projects WHERE owner=? AND id=?", (owner, project)).fetchone():
            raise ValueError("所选项目组不存在，请刷新后重试")
        old = db.execute("SELECT * FROM talents WHERE owner=? AND id=?", (owner, record)).fetchone()
        if not creating and not old:
            raise ValueError("人才记录不存在")
        if creating and old:
            # A lost HTTP response can be retried without creating another person.
            if json.loads(old["body"]) == clean and old["stage"] == stage and old["project"] == project:
                return view(old, db)
            raise Conflict("该新建请求已经保存，请刷新人才库后编辑已有记录")
        if old and body.get("version") != old["version"]:
            raise Conflict("这份人才资料已被更新，请关闭编辑并刷新后再修改")
        source_id = old["source_id"] if old else clean["sourceId"]
        clean["sourceId"] = source_id
        if source_id and not old:
            duplicate = db.execute("SELECT * FROM talents WHERE owner=? AND source_id=?", (owner, source_id)).fetchone()
            if duplicate:
                raise Conflict("该候选人已在储备人才库，请在库内搜索后编辑")
        record = record or str(uuid.uuid4())
        now = datetime.datetime.now(datetime.timezone.utc).isoformat()
        version = old["version"]+1 if old else 1
        payload = json.dumps(clean, ensure_ascii=False)
        if old:
            db.execute("UPDATE talents SET project=?,stage=?,body=?,version=?,updated_at=? WHERE owner=? AND id=?",
                       (project, stage, payload, version, now, owner, record))
        else:
            db.execute("INSERT INTO talents VALUES(?,?,?,?,?,?,?,?,?)",
                       (record, owner, project, stage, source_id, payload, version, now, now))
        result = view(db.execute("SELECT * FROM talents WHERE id=?", (record,)).fetchone(), db)
        event(db, owner, actor, record, "talent.update" if old else "talent.create", view(old) if old else None, result)
    return result
