"""HR departments imported from DingTalk, preserving existing recruitment links."""
import hashlib
import json
import uuid

import talent_pool as pool


def schema(db):
    db.execute("""CREATE TABLE IF NOT EXISTS hr_departments (
        id TEXT PRIMARY KEY, owner TEXT NOT NULL, name TEXT NOT NULL, code TEXT NOT NULL,
        parent_id TEXT NOT NULL, manager TEXT NOT NULL, status TEXT NOT NULL,
        version INTEGER NOT NULL, UNIQUE(owner,name), UNIQUE(owner,code))""")
    db.execute("""CREATE TABLE IF NOT EXISTS hr_department_dingtalk (
        owner TEXT NOT NULL,department_id TEXT NOT NULL,user_id TEXT NOT NULL,name TEXT NOT NULL,
        mobile_mask TEXT NOT NULL,config_version INTEGER NOT NULL,
        PRIMARY KEY(owner,department_id))""")
    db.execute("""CREATE TABLE IF NOT EXISTS hr_department_sources (
        owner TEXT NOT NULL,department_id TEXT NOT NULL,app_key TEXT NOT NULL,
        source_id TEXT NOT NULL,source_name TEXT NOT NULL,config_version INTEGER NOT NULL,
        synced_at REAL NOT NULL,member_count INTEGER NOT NULL,
        PRIMARY KEY(owner,department_id),UNIQUE(owner,app_key,source_id))""")


def view(row):
    return {"id":row["id"],"name":row["name"],"code":row["code"],"parentId":row["parent_id"],
            "manager":row["manager"],"status":row["status"],"version":row["version"]}


def listing(user):
    import dingtalk_interviews as dt
    owner,_=pool.context(user)
    with pool.database() as db:
        schema(db);dt.schema(db)
        config=db.execute("SELECT * FROM hr_dingtalk_config WHERE owner=?",(owner,)).fetchone()
        items=[]
        for row in db.execute("SELECT * FROM hr_departments WHERE owner=? ORDER BY name,id",(owner,)):
            binding=db.execute("SELECT * FROM hr_department_dingtalk WHERE owner=? AND department_id=?",
                               (owner,row["id"])).fetchone()
            source=db.execute("SELECT * FROM hr_department_sources WHERE owner=? AND department_id=?",
                              (owner,row["id"])).fetchone()
            sourced=bool(source and config and source["app_key"]==config["app_key"]
                         and source["config_version"]==config["version"] and row["status"]=="ACTIVE")
            items.append({**view(row),"source":"dingtalk" if source else "legacy",
                "sourceName":source["source_name"] if source else "", "synced":sourced,
                "memberCount":source["member_count"] if sourced else 0,
                "syncedAt":source["synced_at"] if source else 0,
                "dingtalk": {
                "name":binding["name"],"mobileMasked":binding["mobile_mask"],
                "valid":bool(config and config["verified"] and binding["config_version"]==config["version"]
                             and row["status"]=="ACTIVE" and row["manager"]==binding["name"])
            } if binding else None})
        return {"items":items,"dingtalkConfig":dt.public_config(config)}


def source_for(db, owner, record, config):
    return db.execute("""SELECT * FROM hr_department_sources
        WHERE owner=? AND department_id=? AND app_key=? AND config_version=?""",
        (owner, record, config["app_key"], config["version"])).fetchone()


def synchronize(user, body):
    """Only a complete, current directory may create/update business departments."""
    import hr_dingtalk_directory as directory
    import dingtalk_interviews as dt
    owner, actor = pool.context(user)
    snapshot = directory.snapshot(user, {"configVersion": body.get("configVersion", ""),
                                         "refresh": "1" if body.get("refresh") is True else ""})
    state = {k:snapshot[k] for k in ("state","error","departmentCount","employeeCount","syncedAt","configVersion")}
    if snapshot["state"] != "complete":
        return state
    upstream = {r["id"]:r for r in snapshot["departments"]}
    memberships = {d:set() for d in upstream}
    names = {}
    for person in snapshot["items"]:
        names[person["userId"]] = person["name"]
        for source_id in person["departmentIds"]:
            if source_id in memberships:
                memberships[source_id].add(person["userId"])
    now = snapshot["syncedAt"]
    with pool.database() as db:
        schema(db);dt.schema(db)
        config = db.execute("SELECT * FROM hr_dingtalk_config WHERE owner=?", (owner,)).fetchone()
        if (not config or not config["verified"] or config["app_key"] != snapshot["appKey"]
                or config["version"] != snapshot["configVersion"]):
            raise pool.Conflict("钉钉应用已更新，请刷新后重新同步")
        last_sync=db.execute("""SELECT MAX(synced_at) FROM hr_department_sources
            WHERE owner=? AND app_key=? AND config_version=?""", (owner,config["app_key"],config["version"])).fetchone()[0]
        if last_sync and last_sync>now:
            raise pool.Conflict("钉钉部门已完成更新，请刷新后重试")
        old_rows = {r["id"]:r for r in db.execute("SELECT * FROM hr_departments WHERE owner=?", (owner,))}
        sources = {r["department_id"]:r for r in db.execute("SELECT * FROM hr_department_sources WHERE owner=?", (owner,))}
        linked = {r["source_id"]:r["department_id"] for r in sources.values() if r["app_key"] == config["app_key"]}
        records, labels, claimed = {}, {}, set()
        count_by_name = {}
        for dept in upstream.values():
            count_by_name[dept["name"]] = count_by_name.get(dept["name"],0)+1
        def label_for(dept):
            label = dept["name"]
            if count_by_name[label] > 1:
                parent=upstream.get(dept["parentId"])
                label=(parent["name"]+" / " if parent else "")+label
            return label[:120]
        for source_id, dept in upstream.items():
            record = linked.get(source_id)
            if not record and count_by_name[dept["name"]] == 1:
                # Adopt only an exact, unique legacy name, retaining its internal ID.
                candidates = [r["id"] for r in old_rows.values()
                              if r["id"] not in sources and r["name"] == dept["name"] and r["id"] not in claimed]
                if len(candidates) == 1:
                    record = candidates[0]
            record = record or str(uuid.uuid5(uuid.NAMESPACE_URL, "hr-dingtalk:"+owner+":"+config["app_key"]+":"+source_id))
            claimed.add(record);records[source_id]=record
        occupied={r["name"] for rid,r in old_rows.items() if rid not in claimed}
        for source_id,dept in upstream.items():
            label=label_for(dept)
            if label in labels.values() or label in occupied:
                # Identical department names in different branches remain distinct.
                suffix=" · "+source_id
                label=label[:120-len(suffix)]+suffix
                counter=1
                while label in labels.values() or label in occupied:
                    counter+=1;suffix=" · "+source_id+"-"+str(counter)
                    label=label_for(dept)[:120-len(suffix)]+suffix
            labels[source_id]=label
        # Temporarily vacate imported names so department renames/swaps stay atomic.
        for record in claimed:
            if record in old_rows:
                db.execute("UPDATE hr_departments SET name=? WHERE owner=? AND id=?",
                           ("__DING_SYNC_"+record,owner,record))
        changed=0
        for source_id, dept in upstream.items():
            record=records[source_id];old=old_rows.get(record)
            binding=db.execute("SELECT * FROM hr_department_dingtalk WHERE owner=? AND department_id=?",
                               (owner,record)).fetchone()
            valid=bool(binding and binding["config_version"]==config["version"]
                       and binding["user_id"] in memberships[source_id])
            manager=names[binding["user_id"]] if valid else ""
            if binding and not valid:
                db.execute("DELETE FROM hr_department_dingtalk WHERE owner=? AND department_id=?", (owner,record))
            elif valid and manager!=binding["name"]:
                db.execute("UPDATE hr_department_dingtalk SET name=? WHERE owner=? AND department_id=?", (manager,owner,record))
            code=old["code"] if old else "DING_"+hashlib.sha256(config["app_key"].encode()).hexdigest()[:10]+"_"+source_id
            value={"id":record,"name":labels[source_id],"code":code,"parentId":records.get(dept["parentId"],""),
                   "manager":manager,"status":"ACTIVE"}
            modified=not old or any(view(old)[k]!=v for k,v in value.items())
            if old:
                db.execute("""UPDATE hr_departments SET name=?,parent_id=?,manager=?,status='ACTIVE',
                    version=version+? WHERE owner=? AND id=?""",
                    (value["name"],value["parentId"],manager,int(modified),owner,record))
            else:
                db.execute("INSERT INTO hr_departments VALUES(?,?,?,?,?,?,?,1)",
                           (record,owner,value["name"],code,value["parentId"],manager,"ACTIVE"))
            db.execute("INSERT OR REPLACE INTO hr_department_sources VALUES(?,?,?,?,?,?,?,?)",
                       (owner,record,config["app_key"],source_id,dept["name"],config["version"],now,len(memberships[source_id])))
            if modified:
                changed+=1
                pool.event(db,owner,actor,record,"hr_department.dingtalk_sync",view(old) if old else None,value)
        for record,old in old_rows.items():
            if record not in claimed:
                # Keep IDs and historical references; absent/out-of-scope rows cannot route new work.
                if old["status"]!="DISABLED" or old["manager"]:
                    db.execute("UPDATE hr_departments SET status='DISABLED',manager='',version=version+1 WHERE owner=? AND id=?", (owner,record))
                    pool.event(db,owner,actor,record,"hr_department.dingtalk_unavailable",view(old),
                               {**view(old),"status":"DISABLED","manager":""})
                    changed+=1
                db.execute("DELETE FROM hr_department_dingtalk WHERE owner=? AND department_id=?", (owner,record))
    return {**state,"changed":changed,"items":listing(user)["items"]}


def members(user, query):
    import hr_dingtalk_directory as directory
    import dingtalk_interviews as dt
    owner,_=pool.context(user)
    config=dt.read_config(owner)
    if str(config["version"])!=str(query.get("configVersion","")):
        raise pool.Conflict("钉钉应用已更新，请刷新部门")
    record=pool.text(query.get("id",""),36)
    with pool.database() as db:
        schema(db)
        row=db.execute("SELECT * FROM hr_departments WHERE owner=? AND id=?", (owner,record)).fetchone()
        source=source_for(db,owner,record,config)
        if not row or row["status"]!="ACTIVE" or not source:
            raise ValueError("请先同步该钉钉部门")
    return directory.listing(user, {**query,"departmentId":source["source_id"]})


def save(user,body):
    owner,actor=pool.context(user)
    if not isinstance(body,dict):raise ValueError("部门信息格式不正确")
    clean={key:pool.text(body.get(key,""),limit) for key,limit in
           {"id":36,"name":120,"code":60,"parentId":36,"manager":120,"status":20}.items()}
    clean["code"]=clean["code"].upper()
    if not clean["name"] or not clean["code"] or clean["status"] not in ("ACTIVE","DISABLED"):
        raise ValueError("请填写部门名称、编码和状态")
    import re
    if not re.fullmatch(r"[A-Z0-9_-]{1,60}",clean["code"]):raise ValueError("部门编码仅支持英文、数字、下划线和短横线")
    record=clean["id"]
    with pool.database() as db:
        schema(db)
        old=db.execute("SELECT * FROM hr_departments WHERE owner=? AND id=?",(owner,record)).fetchone()
        if record and not old:raise ValueError("招聘部门不存在")
        if old and body.get("version")!=old["version"]:raise pool.Conflict("部门已经更新，请刷新后再编辑")
        if db.execute("SELECT 1 FROM hr_departments WHERE owner=? AND id<>? AND (name=? COLLATE NOCASE OR code=?)",
                      (owner,record,clean["name"],clean["code"])).fetchone():
            raise pool.Conflict("部门名称或编码已存在")
        parent=clean["parentId"]
        seen={record} if record else set()
        while parent:
            if parent in seen:raise ValueError("上级部门不能是自身或下级部门")
            seen.add(parent)
            row=db.execute("SELECT * FROM hr_departments WHERE owner=? AND id=?",(owner,parent)).fetchone()
            if not row:raise ValueError("所选上级部门不存在")
            parent=row["parent_id"]
        record=record or str(uuid.uuid4())
        if old:
            db.execute("""UPDATE hr_departments SET name=?,code=?,parent_id=?,manager=?,status=?,version=version+1
                WHERE owner=? AND id=?""",(clean["name"],clean["code"],clean["parentId"],clean["manager"],clean["status"],owner,record))
            if old["manager"]!=clean["manager"]:
                db.execute("DELETE FROM hr_department_dingtalk WHERE owner=? AND department_id=?",(owner,record))
        else:
            db.execute("INSERT INTO hr_departments VALUES(?,?,?,?,?,?,?,1)",
                       (record,owner,clean["name"],clean["code"],clean["parentId"],clean["manager"],clean["status"]))
        result=view(db.execute("SELECT * FROM hr_departments WHERE id=?",(record,)).fetchone())
        pool.event(db,owner,actor,record,"hr_department.update" if old else "hr_department.create",
                   view(old) if old else None,result)
    return result
