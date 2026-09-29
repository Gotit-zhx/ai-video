"""Local-only visual QA with synthetic fixtures; never calls production APIs."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import sys
import tempfile
import urllib.parse

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT.parent / "scripts/boss-agent"))
import talent_pool
import recruitment_demands
import hr_departments
import passive_pool
TEMP = tempfile.TemporaryDirectory(prefix="heyi-pool-preview-")
talent_pool.DB = Path(TEMP.name) / "talents.sqlite3"
TEST_USER = {"id": "qa", "username": "13800000000", "organizationCode": "heyi",
             "platformAdmin": True, "roles": ["ADMIN"]}
HTML = """<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>招聘工作台 · 本地界面验收</title><link rel="stylesheet" href="/resend-theme.css"><link rel="stylesheet" href="/hr-workspace.css">
<style>
body{margin:0;background:var(--surface-soft);color:var(--ink);font-family:-apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif}
*{box-sizing:border-box} .qa-shell{display:grid;grid-template-columns:205px minmax(0,1fr)}
aside{padding:32px 20px;background:#0a0a0a;color:#fff;border-right:1px solid #242424;min-height:100vh}
aside strong{display:block;font-size:25px;letter-spacing:.08em;margin-bottom:40px}
nav a{display:block;text-decoration:none;color:#a5a5a5;padding:13px 15px;font-size:14px;border-radius:6px;margin:4px 0}
nav a.active{color:#fff;background:#262626;font-weight:650}
.qa-notice{margin:15px 30px 0;font-size:11px;color:var(--muted)}
@media(max-width:700px){.qa-shell{display:block}aside{min-height:0;padding:15px}aside strong{margin:0 0 10px;font-size:20px}nav{display:flex;overflow:auto}nav a{flex:none;padding:10px}.qa-notice{margin:12px 18px}}
</style><div class="qa-shell"><aside><strong>HOYEE</strong><nav>
<a href="#hr-overview" data-hr-route="overview">招聘工作台</a>
<a href="#hr-jobs" data-hr-route="jobs">职位管理</a>
<a href="#hr-resume-pool" data-hr-route="resume-pool">简历池</a>
<a href="#hr-speeches" data-hr-route="speeches">话术管理</a>
<a href="#hr-pool" data-hr-route="pool">储备人才库</a>
<a href="#hr-demands" data-hr-route="demands">招聘需求池</a>
<a href="#hr-departments" data-hr-route="departments">部门管理</a>
<a href="#hr-talent" data-hr-route="talent">人才寻源</a>
<a href="#hr-applications" data-hr-route="applications">投递申请</a>
<a href="#hr-chats" data-hr-route="chats">沟通记录</a>
<a href="#hr-resume" data-hr-route="resume">在线简历</a>
</nav></aside><main><p class="qa-notice">本地界面验收 · 以下为合成测试数据，未连接线上账号</p><section id="hr-page"></section></main></div>
<script>
const currentUser={platformAdmin:true,username:'13800000000'};
const qaParams=new URLSearchParams(location.search);
if(qaParams.get('theme')==='dark')document.documentElement.dataset.theme='dark';
let qaPaired=qaParams.get('helper')!=='offline';
let activeWorkspace='hr';function switchWorkspace(){activeWorkspace='hr'}
function handleSessionExpired(){}
const qaRequests=[];
let fixture;
async function api(url,options={}){
 if(['passive','speeches','resume-pool','pool'].some(k=>url.startsWith('/api/v1/hr/'+k))||url.startsWith('/api/v1/hr/demands')||url.startsWith('/api/v1/hr/departments')){
   const response=await fetch(url,options);const data=await response.json();
   if(!response.ok)throw Error(data.message);return data;
 }
 qaRequests.push({url,method:options.method||'GET'});
 if(options.method==='POST'){
   if(url.endsWith('/login/logout')){sessionStorage.setItem('qaLogin','disconnected');return {data:{state:'disconnected'}};}
   if(url.endsWith('/login/start')){sessionStorage.setItem('qaLogin','pending');return {data:{state:'pending'}};}
   throw Error('本地验收禁止发送请求');
 }
 const loginState=sessionStorage.getItem('qaLogin')||new URLSearchParams(location.search).get('login');
 if(loginState){
   if(loginState==='restricted'){
     const message='BOSS 官方暂时关闭了此账号的电脑端登录权限，已停止本次登录和自动请求。官方预计恢复时间：2026-09-29 13:44:43（北京时间）。请先在 BOSS App 中使用招聘功能；限制解除后刷新本页，再由本人重新扫码，并按官方提示确保手机与电脑在同一地点。预计时间不代表限制一定解除。';
     if(url.endsWith('/login/status'))return {data:{state:'verification_pending',phase:'',failure_code:'PC_LOGIN_RESTRICTED',retry_allowed:false,error:message}};
     return {data:{ok:false,error:{code:'RECRUITER_VERIFICATION_PENDING',message}}};
   }
   if(url.endsWith('/login/status'))return {data:{state:loginState,phase:new URLSearchParams(location.search).get('phase')||'verifying',error:'当前会话已过期（合成测试）'}};
   if(url.endsWith('/status'))return {data:{ok:false,error:{code:loginState==='disconnected'?'SESSION_DISCONNECTED':'RECRUITER_VERIFICATION_PENDING',message:'合成测试：登录状态待核验'}}};
 }
 if(url.endsWith('/login/status'))return {data:{state:'complete',accountKey:'a'.repeat(64),capabilities:['passive-resume']}};
 if(url.endsWith('/resumes'))return {data:[]};
 const key=url.split('/hr/')[1].split(/[/?]/)[0];
 return {data:structuredClone(fixture[key==='candidates'?'recommendations':key])};
}
window.HeyiHrTransport={
 connection:()=>({paired:qaPaired}),api,
 connect:async()=>{qaPaired=true;sessionStorage.setItem('qaLogin','idle');},
 disconnect:async()=>{qaPaired=false;sessionStorage.setItem('qaLogin','disconnected');return {cleared:true};},
 reset:()=>{qaPaired=false;}
};
</script><script src="/hr-pool.js"></script><script src="/hr-demands.js"></script><script src="/hr-departments.js"></script><script src="/hr-passive.js"></script><script src="/hr-workspace.js"></script><script>
fetch('/fixtures').then(r=>r.json()).then(value=>{
 fixture=value;HrWorkspace.enter(location.hash.replace('#hr-','')||'overview');
});
</script></html>"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path in ('/api/v1/hr/passive','/api/v1/hr/speeches','/api/v1/hr/resume-pool'):
            query={k:v[-1] for k,v in urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query).items()}
            return self.json_response(200,{'data':passive_pool.listing(TEST_USER,path.rsplit('/',1)[-1],query)})
        if path == "/api/v1/hr/departments":
            return self.json_response(200,{"data":hr_departments.listing(TEST_USER)})
        if path in ("/api/v1/hr/pool","/api/v1/hr/demands"):
            query={key: values[-1] for key,values in urllib.parse.parse_qs(urllib.parse.urlsplit(self.path).query, keep_blank_values=True).items()}
            action=recruitment_demands.listing if path.endswith("demands") else talent_pool.listing
            return self.json_response(200, {"success": True, "data": action(TEST_USER,query)})
        allowed = {"/hr-passive.js": ("text/javascript", ROOT / "hr-passive.js"), "/hr-workspace.js": ("text/javascript", ROOT / "hr-workspace.js"),
                   "/hr-pool.js": ("text/javascript", ROOT / "hr-pool.js"),
                   "/hr-demands.js": ("text/javascript", ROOT / "hr-demands.js"),
                   "/hr-departments.js": ("text/javascript", ROOT / "hr-departments.js"),
                   "/resend-theme.css": ("text/css", ROOT / "resend-theme.css"),
                   "/hr-workspace.css": ("text/css", ROOT / "hr-workspace.css"),
                   "/fixtures": ("application/json", ROOT / "test/fixtures/hr-workspace.json")}
        if path == "/":
            content, mime = HTML.encode(), "text/html"
        elif path in allowed:
            mime, file = allowed[path]
            content = file.read_bytes()
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header("Content-Type", mime + "; charset=utf-8")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(content)))
        self.end_headers()
        self.wfile.write(content)

    def json_response(self,status,body):
        data=json.dumps(body,ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type","application/json; charset=utf-8")
        self.send_header("Content-Length",str(len(data)))
        self.end_headers();self.wfile.write(data)

    def do_POST(self):
        if self.headers.get("Origin") != "http://127.0.0.1:18676":
            return self.json_response(403,{"message":"Local QA only"})
        if self.path not in ("/api/v1/hr/passive/save","/api/v1/hr/speeches/save","/api/v1/hr/resume-pool/review","/api/v1/hr/resume-pool/analyze","/api/v1/hr/pool/talents","/api/v1/hr/pool/projects","/api/v1/hr/demands/save","/api/v1/hr/demands/generate","/api/v1/hr/departments/save"):
            return self.json_response(404,{"message":"Unknown local fixture"})
        try:
            length=int(self.headers.get("Content-Length","0"))
            if not 0 < length <= 131072:raise ValueError("Invalid length")
            body=json.loads(self.rfile.read(length))
            handler={'/api/v1/hr/passive/save':passive_pool.save_rule,'/api/v1/hr/speeches/save':passive_pool.save_speech,'/api/v1/hr/resume-pool/review':passive_pool.review}.get(self.path)
            if handler:return self.json_response(200,{'data':handler(TEST_USER,body)})
            if self.path.endswith('/resume-pool/analyze'):
                return self.json_response(400,{'message':'本地验收不调用付费模型'})
            if self.path == "/api/v1/hr/departments/save":
                row=hr_departments.save(TEST_USER,body)
                return self.json_response(200,{"data":row})
            if self.path.endswith("generate"):
                result=recruitment_demands.generate(TEST_USER,body,"local-test-only")
                return self.json_response(200,{"success":True,"data":result})
            if self.path.endswith("/save"):
                data=recruitment_demands.save(TEST_USER,body,hr_departments.listing(TEST_USER)["items"])
            else:
                action=talent_pool.save_project if self.path.endswith("projects") else talent_pool.save
                data=action(TEST_USER,body)
            self.json_response(200,{"success":True,"data":data})
        except ValueError as error:
            self.json_response(400,{"message":str(error)})


if __name__ == "__main__":
    # Replace the provider boundary: local UI QA never calls a paid AI model.
    def fake_driver(snapshot,_cookie):
        return {"modelId":"测试驱动模型（合成结果）","portrait":{
            "summary":snapshot["position"]+"岗位人才画像（合成测试）",
            "mustHave":[snapshot["education"],"具备对应岗位的实操经验"],"niceToHave":["相关行业背景"],
            "experience":["独立承担项目目标并复盘"],"evaluationFocus":["用具体案例验证业务能力"]},
            "competencies":[{"name":name,"description":"能够独立完成岗位关键任务","level":"独立负责",
                "evidence":"以真实项目结果和工作样本验证"} for name in ["业务分析","项目执行","跨部门协作","结果复盘"]],
            "interviewQuestions":[{"competency":"业务分析","question":f"问题 {i+1}：请描述一次你负责的业务改进项目。",
                "followUps":["如何定义目标？","如何验证结果？"],"evaluationCriteria":"1分：无具体案例；3分：有行动和结果；5分：有证据和复盘",
                "strongAnswerSignals":["以数据验证结果","主动说明个人贡献"]} for i in range(6)]}
    with talent_pool.database() as db:
        passive_pool.schema(db)
        for number in range(2):
            body={'name':['陈同学','林同学'][number],'jobName':'电商运营','education':'本科','experience':'5 年','city':'广州',
                'jobDescription':'负责店铺运营与经营复盘','jobEducation':'本科','jobExperience':'3年','resumeText':'合成测试简历：五年运营工作经验，负责产品运营和转化分析。'*3,
                'extension':'docx','extractionNote':'本地合成数据，未连接真实候选人。','projectId':''}
            db.execute("INSERT INTO hr_resume_pool(id,owner,account,friend_id,rule_id,message_id,body,file_key,file_hash,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                (f'qa-{number}','heyi','a'*64,str(number+1),'qa-rule',str(number+100),json.dumps(body),'unused','hash',passive_pool.now(),passive_pool.now()))
    recruitment_demands.call_driver=fake_driver
    ThreadingHTTPServer(("127.0.0.1", 18676), Handler).serve_forever()
