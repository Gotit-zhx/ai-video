const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const {EventEmitter} = require('node:events');
const {spawn, execFile} = require('node:child_process');
const {directEnvironment} = require('./direct-network.cjs');

const ORIGIN = 'https://hoyee.org.cn';
const STOP = new Set(['ACCOUNT_RISK','ENVIRONMENT_RISK','IP_RESTRICTED','AUTH_REQUIRED','AUTH_EXPIRED',
  'TOKEN_REFRESH_FAILED','RATE_LIMITED','CAPTCHA_REQUIRED','SECURITY_VERIFICATION_REQUIRED',
  'REQUEST_BUDGET_EXHAUSTED','SESSION_POLICY_UNAVAILABLE','MANUAL_REVIEW_REQUIRED',
  'PC_LOGIN_RESTRICTED','LOGIN_RISK_CONTROL','SESSION_MISMATCH','SESSION_UNAVAILABLE','REQUESTS_PAUSED']);
function restriction(text) {
  const compact = text.replace(/\s+/g, '');
  if (/关闭了?(PC|电脑)端登录权限|(PC|电脑)端登录权限(已|被)?关闭/i.test(compact)) {
    const retry = text.match(/预计开启时间[：:]\s*(\d{4}-\d\d-\d\d\s+\d\d:\d\d:\d\d)/);
    return {code:'PC_LOGIN_RESTRICTED', retry_at:retry?.[1]?.replace(/\s+/g,' ') || '',
      reason:compact.includes('实名绑定的上一账号') && compact.includes('同步限制')
        ? 'REALNAME_LINKED_ACCOUNT' : ''};
  }
  for (const [word,code] of [['安全验证','SECURITY_VERIFICATION_REQUIRED'],
    ['访问环境异常','ENVIRONMENT_RISK'],['账号存在风险','ACCOUNT_RISK'],['IP地址存在异常行为','IP_RESTRICTED']]) {
    if (compact.includes(word)) return {code};
  }
  return null;
}
function restrictionMessage(hold) {
  return (hold.reason === 'REALNAME_LINKED_ACCOUNT'
    ? 'BOSS 提示实名关联的上一账号电脑端登录受限，当前账号同步受限。'
    : 'BOSS 连接已暂停，请在官方页面确认账号状态。')
    + (hold.retry_at ? `预计恢复时间：${hold.retry_at}（北京时间）。` : '')
    + '系统不会自动重新登录或重复发送。';
}
function response(data, status=200) {
  return new Response(JSON.stringify(data), {status, headers:{
    'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'}});
}
function terminate(child) {
  if (!child?.pid || child.exitCode !== null) return;
  if (process.platform === 'win32') execFile('taskkill.exe',['/PID',String(child.pid),'/T','/F'],()=>{});
  else {
    try {process.kill(-child.pid,'SIGTERM');} catch {}
    const kill=setTimeout(()=>{if(child.exitCode===null)try{process.kill(-child.pid,'SIGKILL');}catch{}},2000);
    kill.unref();child.once('close',()=>clearTimeout(kill));
  }
}
class Runtime {
  constructor(executable, network) {this.executable=executable; this.network=network; this.child=null;}
  async run(input, timeout=100000) {
    if (this.child) throw Error('本机正在执行另一项操作');
    return new Promise((resolve,reject)=>{
      const child=spawn(this.executable, [], {
        stdio:['pipe','pipe','ignore'], windowsHide:true, detached:process.platform!=='win32',
        env:directEnvironment(),
      });
      this.child=child;
      let output='', settled=false, failure=null;
      const finish=(error,value)=>{
        if (settled) return; settled=true; clearTimeout(timer);
        error ? reject(error) : resolve(value);
      };
      const timer=setTimeout(()=>{failure=Error('本机执行超时，已停止，未自动重试');terminate(child);},timeout);
      child.on('error',()=>{if(this.child===child)this.child=null;finish(Error('招聘运行时未就绪，请重新安装应用'));});
      child.stdout.setEncoding('utf8');
      child.stdout.on('data',chunk=>{
        output+=chunk;
        if(Buffer.byteLength(output)>8*1024*1024){failure=Error('返回内容过大，已停止');terminate(child);}
      });
      child.on('close',()=>{
        if(this.child===child)this.child=null;
        if(failure){finish(failure);return;}
        try {finish(null,JSON.parse(output));}
        catch {finish(Error('招聘运行时未返回完整结果，未自动重试'));}
      });
      child.stdin.on('error',()=>{});
      child.stdin.end(JSON.stringify({...input,network:this.network}));
    });
  }
  stop(){terminate(this.child);}
}
class Recruiter extends EventEmitter {
  constructor({directory, runtime, browser, authorize, clock=()=>Date.now()}) {
    super(); Object.assign(this,{directory,runtime,browser,authorize,clock});
    this.state={state:'idle',phase:'',error:'',failure_code:''};
    this.paused=false;this.busy=false;this.inFlight=null;this.loginTimer=null;
    this.owner='';this.accountRoot='';this.cdp='';this.events=[];this.persist=Promise.resolve();this.riskHold=null;this.generation=0;
  }
  async init(cdp) {
    this.cdp=cdp;
    await fs.mkdir(this.directory,{recursive:true,mode:0o700});
    try {
      const saved=JSON.parse(await fs.readFile(path.join(this.directory,'state.json'),'utf8'));
      this.owner=saved.owner||'';this.accountRoot=saved.accountRoot||'';
      this.riskHold=saved.hold||null;
      if(this.accountRoot && !this.accountRoot.startsWith(this.directory+path.sep)) throw Error('path');
      this.paused=true;
      this.state=saved.hold
        ? {state:'verification_pending',phase:'',failure_code:saved.hold.code,error:restrictionMessage(saved.hold),hold:saved.hold,...saved.hold}
        : {state:'verification_pending',phase:'',failure_code:'MANUAL_REVIEW_REQUIRED',
           error:'应用已重启，任务保持暂停。请手动连接并核对当前账号。'};
    } catch(error) {if(error.code!=='ENOENT') this.paused=true;}
    this.pollTimer=setInterval(()=>this.poll(),300000);
    this.pollTimer.unref();
  }
  snapshot(){return {...this.state,paused:this.paused,busy:this.busy,runtime:'local',events:this.events.slice(-15)};}
  event(code){this.events.push({at:this.clock(),code});this.events=this.events.slice(-100);this.emit('change',this.snapshot());}
  pause(code='USER_PAUSED'){
    this.generation++;this.paused=true;clearInterval(this.loginTimer);this.loginTimer=null;
    if(['pending','complete'].includes(this.state.state))this.state={...this.state,state:'verification_pending',phase:'',
      failure_code:'MANUAL_REVIEW_REQUIRED',error:code==='DIRECT_NETWORK_UNAVAILABLE'
        ?'网卡直连已中断，请检查有线或 Wi-Fi 网络后重新启动助手。'
        :'本机连接已暂停。需要时请手动连接。'};
    this.event(code);
  }
  save(hold) {
    const record={owner:this.owner,accountRoot:this.accountRoot,hold:hold||this.riskHold};
    this.persist=this.persist.catch(()=>{}).then(async()=>{
      const target=path.join(this.directory,'state.json');
      await fs.writeFile(target+'.tmp',JSON.stringify(record),{mode:0o600});
      await fs.rename(target+'.tmp',target);
    });
    return this.persist;
  }
  async checkOwner(){
    const user=await this.authorize();
    if(!user || user.platformAdmin!==true || user.organizationCode!=='heyi'
      || user.username!=='13800000000' || !user.roles?.includes('ADMIN')) throw Error('请先登录有招聘权限的赫逸管理员账号');
    const key=crypto.createHash('sha256').update(`${user.organizationCode}:${user.id||user.userId||user.username}`).digest('hex');
    if(this.owner && this.owner!==key) throw Error('本机已绑定另一位用户，请先断开原招聘连接');
    this.owner=key;
    return key;
  }
  async call(operation,extra={}) {
    const generation=this.generation;
    if(!['sync','clear','tracked','hold'].includes(operation))await this.checkOwner();
    if(this.disconnecting||generation!==this.generation)throw Error('连接状态已更改，本次操作未执行');
    if(this.busy) throw Error('本机正在处理另一项操作，请稍后再试');
    this.busy=true;this.emit('change',this.snapshot());
    const pending=(async()=>{
      try {
        const result=await this.runtime.run({operation,root:this.accountRoot,cdp:this.cdp,...extra});
        const code=result?.error?.code;
        if(STOP.has(code)) await this.hold({code});
        return result;
      } finally {this.busy=false;this.emit('change',this.snapshot());}
    })();
    this.inFlight=pending;
    try{return await pending;}finally{if(this.inFlight===pending)this.inFlight=null;}
  }
  async hold(hold){
    if(this.riskHold?.reason==='REALNAME_LINKED_ACCOUNT'&&!hold.reason)hold={...hold,...this.riskHold};
    this.riskHold=hold;
    clearInterval(this.loginTimer);this.loginTimer=null;this.paused=true;
    this.state={state:'verification_pending',phase:'',failure_code:hold.code,
      error:restrictionMessage(hold),hold,...hold};
    await this.save(hold);this.event(hold.code);
  }
  async start(){
    await this.checkOwner();
    if(this.disconnecting)throw Error('正在清除本机登录信息，请稍候');
    if(this.state.state==='pending') return this.state;
    if(this.busy) throw Error('当前操作尚未结束，请稍后再连接');
    this.paused=true;
    this.state={...this.state,state:'pending',phase:'qr',failure_code:'',error:''};
    try{await this.browser.open();}catch(error){
      this.state={...this.state,state:'failed',error:'BOSS 页面未打开，请检查本机网络',failure_code:'BROWSER_UNAVAILABLE'};
      this.event('BROWSER_UNAVAILABLE');throw error;
    }
    this.started=this.clock();this.scanning=false;
    this.loginTimer=setInterval(()=>this.observe(),2000);
    this.event('LOGIN_OPENED');return this.state;
  }
  async observe(){
    if(this.scanning || this.state.state!=='pending')return;
    this.scanning=true;
    const generation=this.generation;
    try {
      const sample=await this.browser.inspect();
      if(generation!==this.generation)return;
      const stop=restriction(sample.text||'');
      if(stop){await this.hold(stop);return;}
      if(this.clock()-this.started>300000){
        clearInterval(this.loginTimer);this.loginTimer=null;
        this.state={state:'failed',phase:'',error:'等待已结束，浏览器页面已保留；系统未自动重试',failure_code:'LOGIN_TIMEOUT'};
        this.event('LOGIN_TIMEOUT');return;
      }
      if(!sample.token?.cookies?.wt2){
        this.state.phase=/已扫码|扫描成功|请在app端确认|请在手机上确认|扫码成功/i.test(sample.text||'')?'scanned':'qr';
        return;
      }
      clearInterval(this.loginTimer);this.loginTimer=null;
      this.state.phase='verifying';this.event('LOGIN_VERIFICATION');
      // Reuse records only for the exact same browser session. A new login never
      // inherits another account's pending requests. Store no plaintext token.
      const sessionKey=crypto.createHash('sha256').update(this.owner+sample.token.cookies.wt2).digest('hex');
      this.accountRoot=path.join(this.directory,'accounts',this.owner,sessionKey);
      await fs.mkdir(this.accountRoot,{recursive:true,mode:0o700});
      await this.save();
      const synced=await this.call('sync',{token:sample.token});
      if(generation!==this.generation)return;
      if(!synced.ok)throw Error(synced.error?.message||'登录态尚未建立');
      if(this.riskHold){
        await this.call('hold',this.riskHold);
      }
      const verified=await this.call('verify');
      if(generation!==this.generation)return;
      if(!verified.ok){
        if(!STOP.has(verified.error?.code)) await this.hold({code:'MANUAL_REVIEW_REQUIRED'});
        return;
      }
      this.riskHold=null;this.state={state:'complete',phase:'',error:'',failure_code:''};
      this.paused=false;await this.save();this.event('CONNECTED');
      this.browser.connected();
    }catch{
      if(this.state.state==='pending'){
        clearInterval(this.loginTimer);this.loginTimer=null;
        this.state={state:'failed',phase:'',failure_code:'LOCAL_LOGIN_ERROR',
          error:'本机连接未完成，已保留页面。请查看 BOSS 页面提示，未自动重试。'};
        this.event('LOCAL_LOGIN_ERROR');
      }
    }finally{this.scanning=false;this.emit('change',this.snapshot());}
  }
  async poll(){
    if(this.state.state!=='complete'||this.paused||this.busy)return;
    try{await this.checkOwner();await this.call('poll-resumes');}
    catch{this.paused=true;this.event('BACKGROUND_PAUSED');}
  }
  async clearCredentials(){
    const accounts=path.join(this.directory,'accounts');
    const directories=async folder=>{
      try{return (await fs.readdir(folder,{withFileTypes:true})).filter(item=>item.isDirectory()&&!item.isSymbolicLink());}
      catch(error){if(error.code==='ENOENT')return [];throw error;}
    };
    for(const owner of await directories(accounts)){
      const ownerPath=path.join(accounts,owner.name);
      for(const account of await directories(ownerPath)){
        const data=path.join(ownerPath,account.name,'data');
        let stat;try{stat=await fs.lstat(data);}catch(error){if(error.code==='ENOENT')continue;throw error;}
        if(!stat.isDirectory()||stat.isSymbolicLink())throw Error('登录凭据目录异常，未确认清理');
        // Remove only saved auth material, including old account sessions.
        // Resume records, risk holds, budgets and audit trails remain intact.
        await fs.rm(path.join(data,'auth'),{recursive:true,force:true});
      }
    }
  }
  async disconnect(){
    if(this.disconnecting)return this.disconnecting;
    this.pause();
    const cleanup=(async()=>{
      if(this.inFlight){this.runtime.stop();await this.inFlight.catch(()=>{});}
      const results=await Promise.allSettled([this.browser.clear(),this.clearCredentials()]);
      if(results.some(result=>result.status==='rejected')){
        this.state={state:'verification_pending',phase:'',error:'本机登录信息尚未完全清除，请重试',failure_code:'LOCAL_CLEANUP_FAILED'};
        this.event('LOCAL_CLEANUP_FAILED');throw Error(this.state.error);
      }
      this.accountRoot='';
      this.state={state:'disconnected',phase:'',error:'',failure_code:''};
      await this.save();this.event('DISCONNECTED');
    })();
    this.disconnecting=cleanup;
    try{await cleanup;}finally{if(this.disconnecting===cleanup)this.disconnecting=null;}
  }
  async route(request){
    const url=new URL(request.url);
    if(url.origin!==ORIGIN || !url.pathname.startsWith('/api/v1/hr/'))return null;
    try{
      await this.checkOwner();
      const route=url.pathname.slice('/api/v1/hr/'.length),method=request.method;
      const ok=data=>response({success:true,data});
      if(route.startsWith('desktop/'))return null;
      if(method==='GET'&&route==='login/status')return ok(this.state);
      if(method==='GET'&&route==='status')return ok(this.state.state==='complete'&&!this.paused
        ? {ok:true,data:{authenticated:true,role:'recruiter',runtime:'local'}}
        : {ok:false,error:{code:this.state.state==='pending'?'LOGIN_PENDING':'RECRUITER_VERIFICATION_PENDING',message:this.state.error}});
      if(method==='POST'&&route==='login/start')return ok(await this.start());
      if(method==='POST'&&route==='login/logout'){await this.disconnect();return ok(this.state);}
      if(method==='POST'&&route==='login/refresh'){
        if(this.state.state!=='pending'||this.state.phase!=='qr')throw Error('当前登录不能刷新，请查看 BOSS 页面');
        await this.browser.refresh();return ok({refreshed:true});
      }
      if(method==='GET'&&route==='login/qr'){
        if(this.state.state!=='pending')return response({success:false,message:this.state.error},409);
        if(this.state.phase==='scanned'||this.state.phase==='verifying')return response({success:true,data:{phase:this.state.phase}},202);
        const png=await this.browser.qr();
        return new Response(png,{headers:{'Content-Type':'image/png','Cache-Control':'no-store'}});
      }
      if(method==='GET'&&route==='resumes'){
        if(!this.accountRoot)return ok([]);
        const tracked=await this.call('tracked');return ok(tracked.data||[]);
      }
      if(this.state.state!=='complete'||this.paused)throw Error(this.state.error||'本机任务已暂停，请先连接招聘账号');
      if(method==='POST'&&route==='resumes/request'){
        const input=await request.json();
        if(!/^[1-9]\d{0,19}$/.test(String(input.friendId)))throw Error('会话标识格式不正确');
        // The existing workspace asks for explicit confirmation before POST.
        const result=await this.call('request-resume',{friendId:String(input.friendId)});
        if(!result.ok)throw Error(result.error?.message||'未确认索取结果，请勿重复发送');
        return ok(result.data);
      }
      const download=route.match(/^resumes\/([1-9]\d{0,19})\/([1-9]\d{0,19})\/download$/);
      if(method==='GET'&&download){
        const result=await this.call('download-resume',{friendId:download[1],messageId:download[2]});
        if(!result.ok)throw Error('附件未能下载，记录已保留');
        const file=path.resolve(result.file),prefix=path.join(this.accountRoot,'downloads')+path.sep;
        if(!file.startsWith(prefix))throw Error('附件路径异常');
        const stat=await fs.stat(file);if(stat.size>25*1024*1024)throw Error('附件超过大小限制');
        const data=await fs.readFile(file);await fs.unlink(file);
        return new Response(data,{headers:{'Content-Type':'application/octet-stream',
          'Content-Disposition':`attachment; filename="resume${path.extname(file)}"`}});
      }
      if(method==='GET'){
        const allowed=new Set(['jobs','applications','candidates','recommendations','chats','resume']);
        const message=route.match(/^messages\/([1-9]\d{0,19})$/);
        if(!allowed.has(route)&&!message)throw Error('不支持的招聘操作');
        const input={};
        for(const name of ['page','jobId','keyword','geekId','securityId'])input[name]=url.searchParams.get(name)||'';
        if(message)input.friendId=message[1];
        const result=await this.call(message?'messages':route,input);
        return ok(result);
      }
      return response({success:false,message:'不支持的招聘操作'},405);
    }catch(error){return response({success:false,message:error.message||'本机操作未完成'},409);}
  }
  async close(){this.pause();clearInterval(this.pollTimer);this.runtime.stop();await this.persist;}
}
module.exports={ORIGIN,Runtime,Recruiter,restriction,restrictionMessage,terminate};
