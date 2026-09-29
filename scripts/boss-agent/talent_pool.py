"""Platform-owned talent records. Never imports the CLI or contacts BOSS."""
import contextlib
import datetime
import json
import sqlite3
import uuid
from pathlib import Path

from desktop_bridge import identity

DB = Path("/opt/heyi/boss-agent/runtime/talent-pool.sqlite3")
STAGES = ("", "invited", "first", "second", "hired")
FIELDS = {"name": 100, "position": 200, "education": 100, "experience": 100,
          "phone": 100, "city": 100, "company": 200, "notes": 5000,
          "sourceId": 180, "source": 30}


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


def view(row):
    return {**json.loads(row["body"]), "id": row["id"], "projectId": row["project"],
            "stage": row["stage"], "version": row["version"],
            "createdAt": row["created_at"], "updatedAt": row["updated_at"]}


def event(db, owner, actor, record, action, before, after):
    db.execute("INSERT INTO talent_events(owner,actor,record_id,action,before_json,after_json,created_at) VALUES(?,?,?,?,?,?,?)",
               (owner, actor, record, action, json.dumps(before, ensure_ascii=False),
                json.dumps(after, ensure_ascii=False), datetime.datetime.now(datetime.timezone.utc).isoformat()))


def listing(user, query):
    owner, _ = context(user)
    project = text(query.get("project", ""), 100)
    stage = text(query.get("stage", "all"), 20)
    q = text(query.get("q", ""), 100)
    if stage not in (*STAGES, "all"):
        raise ValueError("招聘阶段不正确")
    try:
        page = max(1, int(query.get("page", "1")))
    except (ValueError, TypeError):
        raise ValueError("页码不正确")
    where, args = ["owner=?"], [owner]
    if project:
        where.append("project=?")
        args.append("" if project == "unassigned" else project)
    if q:
        where.append("body LIKE ? ESCAPE '\\'")
        args.append("%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%")
    with database() as db:
        clause = " AND ".join(where)
        counts = {s: 0 for s in STAGES}
        counts.update({r["stage"]: r["n"] for r in db.execute(
            "SELECT stage,COUNT(*) n FROM talents WHERE " + clause + " GROUP BY stage", args)})
        counts["all"] = sum(counts.values())
        if stage != "all":
            where.append("stage=?")
            args.append(stage)
        total = counts[stage]
        pages = max(1, (total + 19) // 20)
        page = min(page, pages)
        rows = db.execute("SELECT * FROM talents WHERE " + " AND ".join(where)
                          + " ORDER BY updated_at DESC,id LIMIT 20 OFFSET ?", [*args, (page-1)*20])
        items = [view(row) for row in rows]
        projects = [dict(row) for row in db.execute(
            "SELECT id,name,version FROM projects WHERE owner=? ORDER BY name,id", (owner,))]
    return {"items": items, "projects": projects, "counts": counts, "total": total, "page": page, "pages": pages}


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
        if project and not db.execute("SELECT 1 FROM projects WHERE owner=? AND id=?", (owner, project)).fetchone():
            raise ValueError("所选项目组不存在，请刷新后重试")
        old = db.execute("SELECT * FROM talents WHERE owner=? AND id=?", (owner, record)).fetchone()
        if not creating and not old:
            raise ValueError("人才记录不存在")
        if creating and old:
            # A lost HTTP response can be retried without creating another person.
            if json.loads(old["body"]) == clean and old["stage"] == stage and old["project"] == project:
                return view(old)
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
        result = view(db.execute("SELECT * FROM talents WHERE id=?", (record,)).fetchone())
        event(db, owner, actor, record, "talent.update" if old else "talent.create", view(old) if old else None, result)
    return result
