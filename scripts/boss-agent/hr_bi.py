"""Owner-scoped HR reports from saved platform records; no external requests."""
from collections import Counter, defaultdict
import datetime as dt
import html
import io
import json
import math
import re
import zipfile
from zoneinfo import ZoneInfo

import talent_pool as pool

TZ = ZoneInfo("Asia/Shanghai")
STAGES = {"invited": "邀约", "first": "一面", "second": "二面", "onboarding": "待入职", "hired": "已入职", "departed": "已离职"}
STATUS = {"pending": "待审批", "approved": "通过", "rejected": "不通过", "sent": "已送达",
          "queued": "待发送", "preparing": "准备中", "sending": "发送中", "submitted": "等待回执",
          "uncertain": "待人工核对", "failed": "失败", "cancelled": "已取消", "complete": "完成",
          "running": "进行中", "idle": "未分析", "passed": "通过"}
METRIC_LABELS = {"demands":"招聘需求数","headcount":"需求编制人数","resumes":"收到简历份数","pending":"待审批简历份数",
    "approved":"通过简历份数","rejected":"未通过简历份数","talents":"入库人才数","firstReviews":"已填一面意见人次",
    "secondReviews":"已填二面意见人次","firstPassed":"一面通过人次","secondPassed":"二面通过人次",
    "onboarding":"待入职人数","joined":"已入职人数","greetings":"主动联系送达数","sourcingRuns":"寻源任务数",
    "examined":"寻源评估人次","qualified":"寻源符合条件人次","enabledRules":"启用被动邀约职位数",
    "aiComplete":"简历 AI 分析完成份数","aiFailed":"简历 AI 分析失败份数","speechTemplates":"话术模板数",
    "uncertain":"发送待核对数","notificationFailed":"通知发送失败数","averageScore":"平均参考匹配分",
    "resumeApprovalRate":"已审批简历通过率（%）","firstPassRate":"一面意见通过率（%）","secondPassRate":"二面意见通过率（%）"}
TREND_KEYS = ("resumes", "firstReviews", "secondReviews", "demands", "headcount", "examined",
              "qualified", "greetings", "onboarding", "joined", "notifications", "notificationSent")
TABLES = ("hr_departments", "recruitment_demands", "hr_passive_rules", "hr_resume_pool",
          "talents", "hr_interview_reviews", "hr_interview_handoffs", "hr_onboarding_records",
          "hr_onboarding_notices", "hr_sourcing_jobs", "hr_sourcing_runs", "hr_sourcing_candidates",
          "hr_sourcing_contacts", "hr_resume_commands", "hr_talent_messages", "hr_passive_events", "hr_speeches")


def day(value):
    try:
        if isinstance(value, (int, float)):
            return dt.datetime.fromtimestamp(value, TZ).date().isoformat() if value else ""
        instant = dt.datetime.fromisoformat(str(value).replace("Z", "+00:00"))
        if instant.tzinfo:
            instant = instant.astimezone(TZ)
        return instant.date().isoformat()
    except (ValueError, TypeError, OverflowError):
        return ""


def filters(query):
    today = dt.datetime.now(TZ).date()
    start = pool.text(query.get("from", (today-dt.timedelta(days=29)).isoformat()), 10)
    end = pool.text(query.get("to", today.isoformat()), 10)
    try:
        a, b = dt.date.fromisoformat(start), dt.date.fromisoformat(end)
        if a > b or (b-a).days > 3660:
            raise ValueError()
    except ValueError:
        raise ValueError("请选择有效的日期范围，每次最多统计 10 年") from None
    granularity = query.get("granularity", "day")
    if granularity not in ("day", "week", "month", "quarter", "halfyear", "year"):
        raise ValueError("统计周期不正确")
    return {"from": start, "to": end, "department": pool.text(query.get("department", ""), 100),
            "job": pool.text(query.get("job", ""), 300), "granularity": granularity}


def report(user, query):
    owner, _ = pool.context(user)
    selected = filters(query)
    with pool.database() as db:
        existing = {r[0] for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        raw = {}
        for table in TABLES:
            rows = db.execute("SELECT * FROM " + table + " WHERE owner=? LIMIT 100001", (owner,)).fetchall() if table in existing else []
            if len(rows) > 100000:
                raise ValueError("当前记录量超过报表上限，请联系管理员归档历史数据")
            raw[table] = [dict(r) for r in rows]
    body = lambda row: json.loads(row.get("body", "{}"))
    dept_map = {r["id"]: r["name"] for r in raw["hr_departments"]}
    talent_map = {r["id"]: r for r in raw["talents"]}
    resume_map = {r["id"]: r for r in raw["hr_resume_pool"]}
    sourcing_map = {r["id"]: r for r in raw["hr_sourcing_jobs"]}
    job_map, job_depts = {}, defaultdict(set)
    demand_map = {r["id"]: r for r in raw["recruitment_demands"]}
    for table in ("hr_sourcing_jobs", "hr_passive_rules", "recruitment_demands"):
        for row in raw[table]:
            value = body(row)
            account = row.get("account", value.get("jobAccountKey", ""))
            job_id = row.get("job_id", value.get("jobId", ""))
            if not account or not job_id:
                continue
            key = account + ":" + job_id
            job_map[key] = value.get("position") or value.get("jobName") or "未命名职位"
            if table == "recruitment_demands" and value.get("departmentId"):
                job_depts[key].add(value["departmentId"])
    talent_jobs = defaultdict(set)
    for row in raw["hr_resume_pool"]:
        value = body(row)
        if value.get("talentId") and value.get("jobId"):
            talent_jobs[value["talentId"]].add(row["account"] + ":" + value["jobId"])
    def job_department(key):
        return next(iter(job_depts[key])) if len(job_depts[key]) == 1 else ""
    def talent_scope(record):
        row = talent_map.get(record, {})
        keys = talent_jobs[record]
        key = next(iter(keys)) if len(keys) == 1 else ""
        return body(row).get("departmentId", "") or job_department(key), key
    def scope(when, department="", job=""):
        date = day(when)
        return (selected["from"] <= date <= selected["to"]
                and (not selected["department"] or selected["department"] == (department or "unassigned"))
                and (not selected["job"] or selected["job"] == job))
    def job_scope(row):
        job = sourcing_map.get(row.get("job", ""), {})
        key = job.get("account", "") + ":" + job.get("job_id", "") if job else ""
        return job_department(key), key
    datasets = {}
    summaries = defaultdict(Counter)
    trends = defaultdict(Counter)
    def aggregate(department, job, key, value=1):
        summaries[("department", department or "unassigned")][key] += value
        summaries[("job", job or "unassigned")][key] += value
    demands = []
    for row in raw["recruitment_demands"]:
        value = body(row); department = value.get("departmentId", "")
        key = value.get("jobAccountKey", "") + ":" + value.get("jobId", "")
        if not scope(row["created_at"], department, key):
            continue
        aggregate(department, key, "demands"); aggregate(department, key, "headcount", value.get("headcount", 0) or 0)
        trends[day(row["created_at"])]["demands"] += 1
        trends[day(row["created_at"])]["headcount"] += value.get("headcount", 0) or 0
        demands.append({"需求日期": value.get("date", ""), "岗位": value.get("position", ""),
            "部门": dept_map.get(department, value.get("department", "未关联")), "部门经理": value.get("manager", ""),
            "需求状态": row["status"], "编制人数": value.get("headcount", 0),
            "最低学历": value.get("education", ""), "最低工作年限": value.get("years", ""),
            "AI 状态": STATUS.get(row["ai_status"], row["ai_status"]), "创建日期": day(row["created_at"])})
    datasets["招聘需求"] = demands
    resumes = []
    for row in raw["hr_resume_pool"]:
        value = body(row); key = row["account"] + ":" + value.get("jobId", "")
        department = job_department(key)
        if not scope(row["created_at"], department, key):
            continue
        aggregate(department, key, "resumes"); aggregate(department, key, row["status"])
        trends[day(row["created_at"])]["resumes"] += 1
        result = json.loads(row["ai_result"])
        resumes.append({"姓名": value.get("name", "姓名待补充"), "职位": value.get("jobName", ""),
            "部门": dept_map.get(department, "未关联"), "来源": {"active":"主动邀约","boss_chat":"BOSS沟通"}.get(value.get("origin"), "被动邀约"),
            "审批状态": STATUS.get(row["status"], row["status"]), "学历": value.get("education", ""),
            "工作年限": value.get("experience", ""), "AI 状态": STATUS.get(row["ai_state"], row["ai_state"]),
            "参考匹配分": result.get("score", "") if row["ai_state"] == "complete" else "",
            "原件名称": value.get("originalFileName") or value.get("fileName", ""),
            "收到日期": day(row["created_at"]), "审批日期": day(value.get("reviewedAt")), "分析错误": row["ai_error"]})
    datasets["简历明细"] = resumes
    talents = []
    for row in raw["talents"]:
        department, key = talent_scope(row["id"])
        if not scope(row["created_at"], department, key):
            continue
        value = body(row)
        talents.append({"姓名": value["name"], "岗位": value.get("position", ""), "部门": dept_map.get(department, "未关联"),
            "阶段": STAGES.get(row["stage"], "未设置"), "学历": value.get("education", ""),
            "工作年限": value.get("experience", ""), "来源": value.get("source", ""), "入库日期": day(row["created_at"])})
    datasets["储备人才"] = talents
    reviews = []
    for row in raw["hr_interview_reviews"]:
        department, key = talent_scope(row["talent_id"])
        if not scope(row["created_at"], department, key):
            continue
        value = body(talent_map.get(row["talent_id"], {}))
        label = "一面" if row["round"] == "first" else "二面"
        aggregate(department, key, row["round"] + "Reviews")
        if row["outcome"] == "passed":
            aggregate(department, key, row["round"] + "Passed")
        trends[day(row["created_at"])][row["round"] + "Reviews"] += 1
        reviews.append({"姓名": value.get("name", ""), "职位": value.get("position", ""), "部门": dept_map.get(department, "未关联"),
            "轮次": label, "结果": "通过" if row["outcome"] == "passed" else "未通过",
            "面试意见": row["opinion"], "填写日期": day(row["created_at"])})
    datasets["面试意见"] = reviews
    onboardings = []
    for row in raw["hr_onboarding_records"]:
        department, key = talent_scope(row["talent_id"]); value = body(row)
        if not scope(row["updated_at"], department, key):
            continue
        if not value.get("departed"):
            aggregate(department, key, "joined" if value["joined"] else "onboarding")
            trends[day(row["updated_at"])]["joined" if value["joined"] else "onboarding"] += 1
        onboardings.append({"姓名": value["name"], "确认入职时间": value["startDate"], "入职岗位": value["position"],
            "归属主管": value["supervisor"], "归属部门": value["department"], "归属公司": value["company"],
            "固定薪资": value["fixedSalary"], "绩效薪资": value["performanceSalary"], "提成": value["commission"],
            "导师名称": value["mentorName"],
            "是否入职": "已离职" if value.get("departed") else "已入职" if value["joined"] else "未入职",
            "离职时间": day(value.get("departedAt")), "更新日期": day(row["updated_at"])})
    datasets["入职安排"] = onboardings
    runs = []
    for row in raw["hr_sourcing_runs"]:
        department, key = job_scope(row)
        if not scope(row["started"], department, key):
            continue
        result = json.loads(row["result"])
        trends[day(row["started"])]["examined"] += result.get("examined", 0)
        trends[day(row["started"])]["qualified"] += result.get("qualified", 0)
        runs.append({"职位": job_map.get(key, "未关联"), "部门": dept_map.get(department, "未关联"),
            "状态": STATUS.get(row["state"], row["state"]), "目标人数": row["wanted"],
            "已评估人次": result.get("examined", 0), "达到条件人次": result.get("qualified", 0),
            "待补充信息人次": result.get("needsInfo", 0), "低于条件人次": result.get("belowThreshold", 0),
            "停止原因": result.get("stopReason", ""), "错误": row["error"], "开始日期": day(row["started"])})
    datasets["寻源任务"] = runs
    contacts = []
    for row in raw["hr_sourcing_contacts"]:
        department, key = job_scope(row)
        if not scope(row["created_at"], department, key):
            continue
        value = body(row)
        contacts.append({"职位": job_map.get(key, "未关联"), "部门": dept_map.get(department, "未关联"),
            "候选人": value.get("name", ""), "状态": STATUS.get(row["status"], row["status"]),
            "日期": day(row["created_at"])})
        aggregate(department, key, "greetings")
        if row["status"] == "sent":
            trends[day(row["created_at"])]["greetings"] += 1
    datasets["主动联系"] = contacts
    for table, sheet in (("hr_interview_handoffs", "面试资料通知"), ("hr_onboarding_notices", "导师通知"),
                         ("hr_resume_commands", "简历婉拒"), ("hr_talent_messages", "邀约消息")):
        items = []
        for row in raw[table]:
            value = body(row)
            if table == "hr_resume_commands":
                source = resume_map.get(row["resume_id"], {})
                resume_value = body(source)
                key = source.get("account", "") + ":" + resume_value.get("jobId", "")
                department = job_department(key); name = resume_value.get("name", "")
            else:
                department, key = talent_scope(row["talent_id"])
                name = body(talent_map.get(row["talent_id"], {})).get("name", "")
            if not scope(row.get("created_at", row.get("updated_at")), department, key):
                continue
            items.append({"姓名": name, "职位": job_map.get(key, value.get("position", "未关联")),
                "部门": dept_map.get(department, "未关联"), "收件人": value.get("recipientName", ""),
                "轮次": ("二面" if row.get("round") == "second" else "一面") if table == "hr_interview_handoffs" else "",
                "状态": STATUS.get(row["status"], row["status"]), "错误": row.get("error", ""),
                "日期": day(row.get("created_at", row.get("updated_at")))})
            date = day(row.get("created_at", row.get("updated_at")))
            trends[date]["notifications"] += 1
            if row["status"] == "sent":
                trends[date]["notificationSent"] += 1
        datasets[sheet] = items
    events = []
    for row in raw["hr_passive_events"]:
        value = body(row); key = row["account"] + ":" + value.get("jobId", "")
        department = job_department(key)
        if not scope(row["created_at"], department, key):
            continue
        events.append({"日期": day(row["created_at"]), "职位": value.get("jobName", ""), "候选人": value.get("name", ""),
                       "流程事件": value.get("code", ""), "部门": dept_map.get(department, "未关联")})
    datasets["被动邀约事件"] = events
    speeches = [{"话术名称": r["name"], "用途": r["kind"], "启用": "是" if r["active"] else "否", "版本": r["version"],
                 "更新日期": day(r["updated_at"])} for r in raw["hr_speeches"]]
    datasets["话术目录"] = speeches
    datasets["部门目录"] = [{"部门":r["name"],"编码":r["code"],"主管":r["manager"],
                           "状态":"启用" if r["status"]=="ACTIVE" else "停用",
                           "上级部门":dept_map.get(r["parent_id"],"")} for r in raw["hr_departments"]]
    datasets["职位与自动邀约配置"] = []
    for row in raw["hr_passive_rules"]:
        key = row["account"]+":"+row["job_id"]; department = job_department(key); value = body(row)
        if ((selected["department"] and selected["department"] != (department or "unassigned"))
                or (selected["job"] and selected["job"] != key)):
            continue
        datasets["职位与自动邀约配置"].append({"职位":value.get("jobName",job_map.get(key,"")),
            "部门":dept_map.get(department,"未关联"),"被动邀约":"开启" if row["enabled"] else "关闭",
            "最低学历":value.get("education",""),"最低年限":value.get("experience",""),"更新时间":day(row["updated_at"])})
    matched = {}
    for run in sorted(raw["hr_sourcing_runs"], key=lambda row: row["updated_at"]):
        for item in json.loads(run["result"]).get("candidates", []):
            matched[item.get("id", "")] = item
    candidates = []
    for row in raw["hr_sourcing_candidates"]:
        department,key = job_scope(row); value = body(row)
        if not scope(row["read_at"],department,key):
            continue
        match = matched.get(row["id"], {})
        candidates.append({"候选人":value.get("name","姓名待补充"),"职位":job_map.get(key,"未关联"),
            "部门":dept_map.get(department,"未关联"),"参考匹配分":match.get("score",""),
            "匹配状态":match.get("matchState","待评估"),"读取日期":day(row["read_at"])})
    datasets["寻源候选人"] = candidates
    enabled_rules = [r for r in raw["hr_passive_rules"] if r["enabled"]
                     and (not selected["job"] or selected["job"] == r["account"]+":"+r["job_id"])
                     and (not selected["department"] or selected["department"] == (job_department(r["account"]+":"+r["job_id"]) or "unassigned"))]
    metrics = {
        "demands": len(demands), "headcount": sum(r["编制人数"] for r in demands),
        "resumes": len(resumes), "pending": sum(r["审批状态"] == "待审批" for r in resumes),
        "approved": sum(r["审批状态"] == "通过" for r in resumes), "rejected": sum(r["审批状态"] == "不通过" for r in resumes),
        "talents": len(talents), "firstReviews": sum(r["轮次"] == "一面" for r in reviews),
        "secondReviews": sum(r["轮次"] == "二面" for r in reviews),
        "firstPassed": sum(r["轮次"] == "一面" and r["结果"] == "通过" for r in reviews),
        "secondPassed": sum(r["轮次"] == "二面" and r["结果"] == "通过" for r in reviews),
        "onboarding": sum(r["是否入职"] == "未入职" for r in onboardings),
        "joined": sum(r["是否入职"] == "已入职" for r in onboardings),
        "greetings": sum(r["状态"] == "已送达" for r in contacts),
        "sourcingRuns": len(runs), "examined": sum(r["已评估人次"] for r in runs),
        "qualified": sum(r["达到条件人次"] for r in runs), "enabledRules": len(enabled_rules),
        "aiComplete": sum(r["AI 状态"] == "完成" for r in resumes),
        "aiFailed": sum(r["AI 状态"] == "失败" for r in resumes),
        "speechTemplates": len(speeches),
        "uncertain": sum(r["状态"] == "待人工核对" for name in ("主动联系", "面试资料通知", "导师通知", "简历婉拒", "邀约消息") for r in datasets[name]),
        "notificationFailed": sum(r["状态"] == "失败" for name in ("面试资料通知", "导师通知", "简历婉拒", "邀约消息") for r in datasets[name]),
    }
    scores = [r["参考匹配分"] for r in resumes if isinstance(r["参考匹配分"], (int, float))]
    metrics["averageScore"] = round(sum(scores)/len(scores), 1) if scores else None
    metrics["resumeApprovalRate"] = round(metrics["approved"]/(metrics["approved"]+metrics["rejected"])*100, 1) if metrics["approved"]+metrics["rejected"] else None
    for kind in ("first", "second"):
        metrics[kind+"PassRate"] = round(metrics[kind+"Passed"]/metrics[kind+"Reviews"]*100, 1) if metrics[kind+"Reviews"] else None
    def rows(kind):
        values = []
        for (dimension, key), counts in summaries.items():
            if dimension == kind:
                values.append({"name": (dept_map if kind == "department" else job_map).get(key, "未关联"),
                               "key": key, **{k: counts[k] for k in ("demands", "headcount", "resumes", "pending", "approved", "rejected",
                                "firstReviews", "firstPassed", "secondReviews", "secondPassed", "onboarding", "joined", "greetings")}})
        return sorted(values, key=lambda r: (-r["resumes"], r["name"]))
    a, b = dt.date.fromisoformat(selected["from"]), dt.date.fromisoformat(selected["to"])
    buckets = {}
    for i in range((b-a).days+1):
        current = a+dt.timedelta(days=i); mode = selected["granularity"]
        if mode == "week":
            iso = current.isocalendar(); label = f"{iso.year}-W{iso.week:02}"
        elif mode == "month":
            label = current.strftime("%Y-%m")
        elif mode == "quarter":
            label = f"{current.year}-Q{(current.month-1)//3+1}"
        elif mode == "halfyear":
            label = f"{current.year}-H{(current.month-1)//6+1}"
        elif mode == "year":
            label = str(current.year)
        else:
            label = current.isoformat()
        bucket = buckets.setdefault(label, Counter())
        bucket.update(trends[current.isoformat()])
    timeline = [{"date": label, **{k: counts[k] for k in TREND_KEYS}}
                for label, counts in buckets.items()]
    today = dt.datetime.now(TZ).date()
    aging = [{"name":label,"count":0} for label in ("0–3 天","4–7 天","8–14 天","15 天及以上")]
    for record in resumes:
        if record["审批状态"] == "待审批":
            age = max(0,(today-dt.date.fromisoformat(record["收到日期"])).days)
            aging[0 if age <= 3 else 1 if age <= 7 else 2 if age <= 14 else 3]["count"] += 1
    sources = [{"name": name, "count": sum(r["来源"] == name for r in resumes),
                "approved": sum(r["来源"] == name and r["审批状态"] == "通过" for r in resumes)}
               for name in ("主动邀约", "被动邀约", "BOSS沟通")]
    dashboard = {"filters": selected, "metrics": metrics, "departments": rows("department"), "jobs": rows("job"),
        "trend": timeline, "sources": sources, "aging": aging,
        "stages": [{"name": name, "count": sum(r["阶段"] == name for r in talents)} for name in STAGES.values()],
        "options": {"departments": [{"id": k, "name": v} for k,v in dept_map.items()],
                    "jobs": [{"id": k, "name": v} for k,v in job_map.items()]},
        "generatedAt": dt.datetime.now(TZ).isoformat(),
        "definitions": [
            "数据仅来自本企业平台已保存的招聘记录；报表不向 BOSS、钉钉或模型发起请求。",
            "需求/简历/储备人才按创建或入库日期统计；面试按意见提交日期；寻源按开始日期；入职安排按最后更新日期统计。",
            "审批通过率 = 通过 /（通过 + 不通过）；面试通过率 = 通过意见数 / 已提交意见数，均排除尚未处理的记录。",
            "简历按份计，储备人才与入职按人计；流程分布是所选入库期间人才的当前阶段，不代表逐层转化率。",
            "寻源统计为评估人次，同一人跨任务可能重复；参考匹配分仅统计已完成分析，不用于自动审批。",
            "部门和职位使用准确关联，无法唯一归属的记录列入未关联；筛选具体职位时不混入其他职位。",
            "部门、启用被动邀约和话术模板为当前配置快照；待核对发送不计为送达。导出包括汇总、统计口径和明细。",
            "日/周/月/季度/半年/年 K 为业务数量的周期汇总；周从周一开始，季度为自然季度，半年为 1–6 月及 7–12 月；首末周期按筛选日期截取。"
        ]}
    return dashboard, datasets


def dashboard(user, query):
    return report(user, query)[0]


def workbook(sheets):
    """Write typed inline strings, never formulas; no credentials or external links."""
    output = io.BytesIO()
    escape = lambda value: html.escape(re.sub(r"[\x00-\x08\x0b\x0c\x0e-\x1f]", "", str(value)))
    def column(number):
        result = ""
        while number:
            number, remainder = divmod(number-1, 26); result = chr(65+remainder)+result
        return result
    ns = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("[Content_Types].xml", '<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
          '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>'
          '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
          '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
          + "".join(f'<Override PartName="/xl/worksheets/sheet{i}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>' for i in range(1,len(sheets)+1))+'</Types>')
        z.writestr("_rels/.rels", '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>')
        z.writestr("xl/workbook.xml", f'<workbook xmlns="{ns}" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>'+
          "".join(f'<sheet name="{escape(name)}" sheetId="{i}" r:id="rId{i}"/>' for i,(name,_) in enumerate(sheets,1))+'</sheets></workbook>')
        z.writestr("xl/_rels/workbook.xml.rels", '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'+
          "".join(f'<Relationship Id="rId{i}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet{i}.xml"/>' for i in range(1,len(sheets)+1))+
          '<Relationship Id="styles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>')
        z.writestr("xl/styles.xml", f'<styleSheet xmlns="{ns}"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>'
          '<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>'
          '<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>'
          '<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>'
          '<cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs>'
          '<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>')
        for i,(_,rows) in enumerate(sheets,1):
            rows = rows or [["暂无记录"]]
            cells = []
            for n,row in enumerate(rows,1):
                content = []
                for j,value in enumerate(row,1):
                    ref = column(j)+str(n); style = ' s="1"' if n==1 else ""
                    if isinstance(value,(int,float)) and not isinstance(value,bool) and math.isfinite(value):
                        content.append(f'<c r="{ref}"{style}><v>{value}</v></c>')
                    else:
                        content.append(f'<c r="{ref}" t="inlineStr"{style}><is><t xml:space="preserve">{escape(value if value is not None else "")}</t></is></c>')
                cells.append(f'<row r="{n}">{"".join(content)}</row>')
            width = max(len(r) for r in rows); dimension = f'A1:{column(width)}{len(rows)}'
            z.writestr(f"xl/worksheets/sheet{i}.xml", f'<worksheet xmlns="{ns}"><dimension ref="{dimension}"/>'
              '<sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews>'
              f'<cols><col min="1" max="{width}" width="22" customWidth="1"/></cols><sheetData>{"".join(cells)}</sheetData>'
              f'<autoFilter ref="{dimension}"/></worksheet>')
    return output.getvalue()


def export(user, query):
    data, datasets = report(user, query)
    sheets = [("统计口径", [["项目","说明"], ["生成时间",data["generatedAt"]], *[[k,v] for k,v in data["filters"].items()],
                           *[["统计口径",text] for text in data["definitions"]]]),
              ("指标汇总", [["指标","值"], *[[METRIC_LABELS.get(k,k),v] for k,v in data["metrics"].items()]])]
    names = {"name":"名称","demands":"需求数","headcount":"需求编制","resumes":"简历份数","pending":"待审批","approved":"通过",
             "rejected":"不通过","firstReviews":"一面意见","firstPassed":"一面通过","secondReviews":"二面意见",
             "secondPassed":"二面通过","onboarding":"待入职","joined":"已入职","greetings":"主动联系记录",
             "date":"周期","count":"数量","examined":"评估人次","qualified":"符合条件人次",
             "notifications":"通知记录","notificationSent":"通知送达"}
    for key,title in (("departments","部门汇总"),("jobs","职位汇总"),("trend","周期趋势"),("sources","邀约来源"),("aging","待审批账龄")):
        records = data[key]
        columns = [k for k in records[0] if k != "key"] if records else ["名称"]
        sheets.append((title, [[names.get(k,k) for k in columns], *[[r.get(k,"") for k in columns] for r in records]]))
    for title,records in datasets.items():
        columns = list(records[0]) if records else ["暂无记录"]
        sheets.append((title, [columns, *[[r.get(k,"") for k in columns] for r in records]]))
    content = workbook(sheets)
    if len(content) > 25*1024*1024:
        raise ValueError("导出文件过大，请缩小时间或部门、职位范围")
    return content, "招聘BI报表-"+data["filters"]["from"]+"-"+data["filters"]["to"]+".xlsx"
