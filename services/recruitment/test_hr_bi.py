import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest import mock
import xml.etree.ElementTree as ET
import zipfile
from http.server import ThreadingHTTPServer
import urllib.error
import urllib.request

import connector
import hr_bi as bi
import hr_departments
import passive_pool
import recruitment_demands
import talent_pool as pool

USER={"id":"hr","username":"13800000000","organizationCode":"heyi","platformAdmin":True,"roles":["ADMIN"]}


class HrBiTest(unittest.TestCase):
    def setUp(self):
        folder=tempfile.TemporaryDirectory();self.addCleanup(folder.cleanup)
        patch=mock.patch.object(pool,"DB",Path(folder.name)/"pool.db");patch.start();self.addCleanup(patch.stop)
        with pool.database() as db:
            hr_departments.schema(db);passive_pool.schema(db);recruitment_demands.schema(db)
            db.execute("INSERT INTO hr_departments VALUES('dept','heyi','研发','DEV','','主管','ACTIVE',1)")
            for record,owner,key,job,status,date in [
                ("r1","heyi","a"*64,"job1","approved","2026-10-01T00:00:00Z"),
                ("r2","heyi","a"*64,"job1","pending","2026-10-02T00:00:00Z"),
                ("r3","heyi","b"*64,"job1","rejected","2026-10-02T00:00:00Z"),
                ("foreign","other","a"*64,"job1","approved","2026-10-02T00:00:00Z")]:
                db.execute("""INSERT INTO hr_resume_pool(id,owner,account,friend_id,rule_id,message_id,body,file_key,file_hash,
                  status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'key','hash',?,?,?)""",
                  (record,owner,key,record,"rule",record,json.dumps({"name":record,"jobId":job,"jobName":"算法",
                    "origin":"active" if record=="r1" else "passive","fileName":"简历.pdf"}),status,date,date))
            db.execute("""INSERT INTO recruitment_demands(id,owner,body,status,version,created_at,updated_at)
              VALUES('d1','heyi',?,'recruiting',1,'2026-10-01','2026-10-01')""",(json.dumps({
              "jobId":"job1","jobAccountKey":"a"*64,"position":"算法","departmentId":"dept","headcount":2}),))
        self.query={"from":"2026-10-01","to":"2026-10-31"}

    def test_dashboard_counts_sources_unprocessed_and_rates_from_saved_owned_records(self):
        data=bi.dashboard(USER,self.query)
        self.assertEqual(data["metrics"]["resumes"],3)
        self.assertEqual(data["metrics"]["pending"],1)
        self.assertEqual(data["metrics"]["resumeApprovalRate"],50.0)
        self.assertEqual(data["metrics"]["headcount"],2)
        self.assertEqual(data["sources"][0]["count"],1)
        self.assertEqual(sum(x["resumes"] for x in data["departments"]),3)

    def test_boss_chat_source_is_included_in_statistics_and_export(self):
        with pool.database() as db:
            row=db.execute("SELECT body FROM hr_resume_pool WHERE id='r2'").fetchone()
            value=json.loads(row['body']);value['origin']='boss_chat'
            db.execute("UPDATE hr_resume_pool SET body=? WHERE id='r2'",(json.dumps(value),))
        data=bi.dashboard(USER,self.query)
        source=next(r for r in data['sources'] if r['name']=='BOSS沟通')
        self.assertEqual(source['count'],1)
        self.assertEqual(data['metrics']['resumes'],3)
        _,records=bi.report(USER,self.query)
        self.assertTrue(any(r['来源']=='BOSS沟通' for r in records['简历明细']))
        content,_=bi.export(USER,self.query)
        with zipfile.ZipFile(io.BytesIO(content)) as packet:
            self.assertTrue(any('BOSS沟通' in packet.read(name).decode()
                                for name in packet.namelist() if name.startswith('xl/worksheets/') and name.endswith('.xml')))

    def test_departed_employee_is_not_counted_as_active_joined_and_export_keeps_history(self):
        import hr_onboarding
        value={"name":"fixture","startDate":"2026-10-01","position":"算法","supervisor":"主管","department":"研发",
               "company":"fixture","fixedSalary":"1","performanceSalary":"0","commission":"无","mentorName":"导师",
               "joined":True,"departed":True,"departedAt":"2026-10-09T00:00:00Z"}
        with pool.database() as db:
            hr_onboarding.schema(db)
            db.execute("INSERT INTO talents VALUES('talent','heyi','','departed','',?,1,'2026-10-01','2026-10-09')",
                       (json.dumps({"name":"fixture","departmentId":"dept"}),))
            db.execute("INSERT INTO hr_onboarding_records VALUES('talent','heyi',?,1,'2026-10-09')",(json.dumps(value),))
        data=bi.dashboard(USER,self.query)
        self.assertEqual(data["metrics"]["joined"],0)
        self.assertEqual(data["metrics"]["onboarding"],0)
        self.assertEqual(next(s["count"] for s in data["stages"] if s["name"]=="已离职"),1)
        _,datasets=bi.report(USER,self.query)
        self.assertEqual(datasets["入职安排"][0]["是否入职"],"已离职")

    def test_exact_department_job_account_and_date_filters_are_shared_with_export(self):
        scoped=self.query|{"job":"a"*64+":job1","department":"dept"}
        data,datasets=bi.report(USER,scoped)
        self.assertEqual(data["metrics"]["resumes"],2)
        self.assertEqual(len(datasets["简历明细"]),2)
        narrowed=bi.dashboard(USER,scoped|{"from":"2026-10-02"})
        self.assertEqual(narrowed["metrics"]["resumes"],1)
        self.assertIsNone(narrowed["metrics"]["resumeApprovalRate"])
        self.assertEqual(bi.dashboard(USER,self.query|{"department":"unassigned"})["metrics"]["resumes"],1)
        with self.assertRaises(ValueError):
            bi.dashboard(USER|{"organizationCode":"other"},self.query)

    def test_all_six_k_periods_preserve_totals_and_use_calendar_boundaries(self):
        for mode,label in [("day","2026-10-01"),("week","2026-W40"),("month","2026-10"),
                           ("quarter","2026-Q4"),("halfyear","2026-H2"),("year","2026")]:
            with self.subTest(mode=mode):
                data=bi.dashboard(USER,self.query|{"granularity":mode})
                self.assertEqual(sum(r["resumes"] for r in data["trend"]),3)
                self.assertEqual(data["trend"][0]["date"],label)
        data=bi.dashboard(USER,{"from":"2026-12-31","to":"2027-01-02","granularity":"week"})
        self.assertEqual([r["date"] for r in data["trend"]],["2026-W53"])

    def test_export_is_a_valid_private_multi_sheet_workbook_with_no_formula_execution(self):
        content,filename=bi.export(USER,self.query|{"granularity":"month"})
        self.assertTrue(filename.endswith(".xlsx"))
        with zipfile.ZipFile(io.BytesIO(content)) as packet:
            self.assertIsNone(packet.testzip())
            for name in packet.namelist():
                if name.endswith(".xml") or name.endswith(".rels"):
                    ET.fromstring(packet.read(name))
            workbook=packet.read("xl/workbook.xml").decode()
            self.assertIn("部门汇总",workbook);self.assertIn("简历明细",workbook);self.assertIn("入职安排",workbook)
        dangerous=bi.workbook([("资料",[["字段"],["=HYPERLINK(\"https://bad\", \"click\")"],["+1"],["@command"]])])
        with zipfile.ZipFile(io.BytesIO(dangerous)) as packet:
            xml=packet.read("xl/worksheets/sheet1.xml").decode()
            self.assertNotIn("<f>",xml);self.assertIn('t="inlineStr"',xml)

    def test_report_never_contacts_boss_dingtalk_or_ai_and_rejects_bad_ranges(self):
        import dingtalk_interviews as ding
        with mock.patch.object(ding,"api",side_effect=AssertionError("No network")):
            bi.dashboard(USER,self.query)
        for changes in ({"from":"bad"},{"to":"2026-09-01"},{"granularity":"invalid"},{"from":"2000-01-01"}):
            with self.assertRaises(ValueError):bi.dashboard(USER,self.query|changes)

    def test_http_export_is_authorized_private_and_onboarding_writes_check_origin(self):
        class Handler(connector.HttpHandler):
            def authorized(self):
                if self.headers.get("Cookie") != "session=test":
                    return 401
                self.desktop_user=USER
                return 200
            def log_message(self,*args):
                pass
        server=ThreadingHTTPServer(("127.0.0.1",0),Handler)
        self.addCleanup(server.server_close);self.addCleanup(server.shutdown)
        threading.Thread(target=server.serve_forever,daemon=True).start()
        url=f"http://127.0.0.1:{server.server_port}/api/v1/hr"
        opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with mock.patch.dict(connector.os.environ,{"APP_HR_DESKTOP_ONLY":"1"}),mock.patch.object(connector,"execute") as boss:
            with self.assertRaises(urllib.error.HTTPError) as denied:
                opener.open(url+"/bi/export?from=2026-10-01&to=2026-10-31")
            self.assertEqual(denied.exception.code,401)
            request=urllib.request.Request(url+"/bi/export?from=2026-10-01&to=2026-10-31",
                                           headers={"Cookie":"session=test"})
            with opener.open(request) as response:
                self.assertIn("spreadsheetml",response.headers["Content-Type"])
                self.assertEqual(response.headers["Cache-Control"],"private, no-store")
                self.assertIn("attachment",response.headers["Content-Disposition"])
                with zipfile.ZipFile(io.BytesIO(response.read())) as packet:
                    self.assertIsNone(packet.testzip())
            request=urllib.request.Request(url+"/onboarding/save",data=b"{}",
                headers={"Cookie":"session=test","Content-Type":"application/json","Origin":"https://evil.example"})
            with self.assertRaises(urllib.error.HTTPError) as denied:opener.open(request)
            self.assertEqual(denied.exception.code,403)
            boss.assert_not_called()
