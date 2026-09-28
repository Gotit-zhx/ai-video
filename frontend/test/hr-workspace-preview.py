"""Local-only visual QA with synthetic fixtures; never calls production APIs."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
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
<a href="#hr-talent" data-hr-route="talent">人才寻源</a>
<a href="#hr-applications" data-hr-route="applications">投递申请</a>
<a href="#hr-chats" data-hr-route="chats">沟通记录</a>
<a href="#hr-resume" data-hr-route="resume">在线简历</a>
</nav></aside><main><p class="qa-notice">本地界面验收 · 以下为合成测试数据，未连接线上账号</p><section id="hr-page"></section></main></div>
<script>
const currentUser={platformAdmin:true,username:'13800000000'};
let activeWorkspace='hr';function switchWorkspace(){activeWorkspace='hr'}
function handleSessionExpired(){}
const qaRequests=[];
let fixture;
async function api(url,options={}){
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
 if(url.endsWith('/login/status'))return {data:{state:'complete'}};
 if(url.endsWith('/resumes'))return {data:[]};
 const key=url.split('/hr/')[1].split(/[/?]/)[0];
 return {data:structuredClone(fixture[key==='candidates'?'recommendations':key])};
}
</script><script src="/hr-workspace.js"></script><script>
fetch('/fixtures').then(r=>r.json()).then(value=>{
 fixture=value;HrWorkspace.enter(location.hash.replace('#hr-','')||'jobs');
});
</script></html>"""


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        allowed = {"/hr-workspace.js": ("text/javascript", ROOT / "hr-workspace.js"),
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


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", 18676), Handler).serve_forever()
