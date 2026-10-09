"""Independent HR department directory. Never reads or changes system departments."""
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
            items.append({**view(row),"dingtalk": {
                "name":binding["name"],"mobileMasked":binding["mobile_mask"],
                "valid":bool(config and config["verified"] and binding["config_version"]==config["version"]
                             and row["status"]=="ACTIVE" and row["manager"]==binding["name"])
            } if binding else None})
        return {"items":items,"dingtalkConfig":dt.public_config(config)}


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
