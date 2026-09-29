const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const os=require('node:os');
const {Recruiter,restriction,ORIGIN}=require('../src/core.cjs');
const {LocalBridge}=require('../src/bridge.cjs');
const user={username:'13800000000',id:'fixture',organizationCode:'heyi',roles:['ADMIN'],platformAdmin:true};
async function fixture(t){
  const directory=await fs.mkdtemp(path.join(os.tmpdir(),'heyi-desktop-unit-'));
  const calls=[];
  const engine=new Recruiter({directory,authorize:async()=>user,
    runtime:{run:async input=>{calls.push(input);return {ok:true,data:[]};},stop(){}},
    browser:{open:async()=>{},inspect:async()=>({text:''}),clear:async()=>{},connected(){},qr:async()=>Buffer.from('png')}});
  await engine.init('http://127.0.0.1:9999');
  t.after(async()=>{await engine.close();await fs.rm(directory,{recursive:true,force:true});});
  return {engine,directory,calls};
}
test('official linked-account restriction preserves exact date',()=>{
  assert.deepEqual(restriction('实名绑定的上一账号关闭了 PC 端登录权限，当前实名认证账号也同步限制。预计开启时间：2026-09-29 13:44:05'),
    {code:'PC_LOGIN_RESTRICTED',reason:'REALNAME_LINKED_ACCOUNT',retry_at:'2026-09-29 13:44:05'});
});
test('risk holds survive disconnect, generic failures and restart',async t=>{
  const {engine,directory}=await fixture(t);
  await engine.hold({code:'PC_LOGIN_RESTRICTED',reason:'REALNAME_LINKED_ACCOUNT',retry_at:'2026-09-29 13:44:05'});
  await engine.hold({code:'AUTH_EXPIRED'});
  await engine.disconnect();
  const saved=JSON.parse(await fs.readFile(path.join(directory,'state.json')));
  assert.equal(saved.hold.reason,'REALNAME_LINKED_ACCOUNT');
  assert.equal(saved.hold.code,'PC_LOGIN_RESTRICTED');
  await engine.close();await engine.init(engine.cdp);
  assert.equal(engine.state.hold.reason,'REALNAME_LINKED_ACCOUNT');
  assert.equal(engine.paused,true);
});
test('paused or unconnected route cannot invoke SDK; non-HR route untouched',async t=>{
  const {engine,calls}=await fixture(t);
  assert.equal((await engine.route(new Request(ORIGIN+'/api/v1/hr/jobs'))).status,409);
  await engine.poll();assert.equal(calls.length,0);
  assert.equal(await engine.route(new Request(ORIGIN+'/api/v1/auth/me')),null);
  engine.authorize=async()=>({...user,platformAdmin:false});
  assert.equal((await engine.route(new Request(ORIGIN+'/api/v1/hr/login/status'))).status,409);
});
test('restriction visible in browser blocks sync and recruiter verification',async t=>{
  const {engine,calls}=await fixture(t);
  engine.browser.inspect=async()=>({text:'账号存在风险',token:{cookies:{wt2:'fake'}}});
  await engine.start();await engine.observe();
  assert.equal(engine.state.failure_code,'ACCOUNT_RISK');
  assert.equal(calls.length,0);
});
test('pausing while browser observation is in flight cannot continue login',async t=>{
  const {engine,calls}=await fixture(t);
  let resolve;
  engine.browser.inspect=()=>new Promise(r=>{resolve=r;});
  await engine.start();
  const observed=engine.observe();
  engine.pause();
  resolve({text:'',token:{cookies:{wt2:'fixture-token'}}});
  await observed;
  assert.equal(calls.length,0);
  assert.equal(engine.state.state,'verification_pending');
});
test('local pairing enforces Origin, Host, credentials and route allowlist',async t=>{
  const {engine,calls}=await fixture(t);
  const bridge=new LocalBridge({engine,authorize:async()=>user,pair:async()=>({expiresAt:Date.now()/1000+60})});
  await bridge.start(0);t.after(()=>bridge.close());
  const url=`http://127.0.0.1:${bridge.port}`;
  assert.equal((await fetch(url+'/health')).status,403);
  assert.equal((await fetch(url+'/health',{headers:{Origin:'https://evil.test'}})).status,403);
  const headers={Origin:ORIGIN,'Content-Type':'application/json'};
  assert.equal((await fetch(url+'/api/v1/hr/jobs',{headers})).status,428);
  const pair=await fetch(url+'/pair',{method:'POST',headers,body:JSON.stringify({ticket:'a'.repeat(43)})});
  const {token}= (await pair.json()).data;
  headers.Authorization='Bearer '+token;
  assert.equal((await fetch(url+'/api/v1/hr/login/status',{headers})).status,200);
  assert.equal((await fetch(url+'/api/v1/auth/me',{headers})).status,409);
  assert.equal((await fetch(url+'/api/v1/hr/desktop/redeem',{method:'POST',headers,body:'{}'})).status,409);
  assert.equal(calls.length,0);
});
test('logout waits for stopped work and deletes all stored credentials but retains records',async t=>{
  const {engine,directory,calls}=await fixture(t);
  const current=path.join(directory,'accounts','owner','current');
  const previous=path.join(directory,'accounts','owner','previous');
  for(const root of [current,previous]){
    await fs.mkdir(path.join(root,'data','auth'),{recursive:true});
    await fs.writeFile(path.join(root,'data','auth','session.enc'),'synthetic-credential');
    await fs.writeFile(path.join(root,'resumes.sqlite3'),'retained-records');
  }
  engine.accountRoot=current;
  let abort,clears=0,stops=0;
  engine.runtime={run:()=>new Promise((_resolve,reject)=>{abort=reject;}),stop(){stops++;abort(Error('cancelled'));}};
  engine.browser.clear=async()=>{clears++;};
  const pending=engine.call('jobs');pending.catch(()=>{});
  await new Promise(resolve=>setImmediate(resolve));
  await Promise.all([engine.disconnect(),engine.disconnect()]);
  await assert.rejects(pending,/cancelled/);
  assert.equal(clears,1);assert.equal(stops,1);assert.equal(engine.state.state,'disconnected');
  for(const root of [current,previous]){
    await assert.rejects(()=>fs.access(path.join(root,'data','auth')));
    assert.equal(await fs.readFile(path.join(root,'resumes.sqlite3'),'utf8'),'retained-records');
  }
  assert.equal(engine.accountRoot,'');assert.equal(engine.paused,true);
  assert.equal(calls.length,0,'Cleanup must not invoke BOSS or SDK logout');
});
test('failed browser cleanup still erases saved tokens and cannot report disconnected',async t=>{
  const {engine,directory}=await fixture(t);
  const auth=path.join(directory,'accounts','owner','session','data','auth');
  await fs.mkdir(auth,{recursive:true});await fs.writeFile(path.join(auth,'session.enc'),'synthetic');
  engine.browser.clear=async()=>{throw Error('disk busy');};
  await assert.rejects(()=>engine.disconnect(),/未完全清除/);
  await assert.rejects(()=>fs.access(auth));
  assert.equal(engine.state.failure_code,'LOCAL_CLEANUP_FAILED');
});
test('expired paired token authorizes cleanup only and is revoked after confirmation',async t=>{
  const {engine}=await fixture(t);let clears=0,releases=0;
  engine.browser.clear=async()=>{clears++;};
  const bridge=new LocalBridge({engine,authorize:async()=>{throw Error('lease expired');},
    release:async()=>{releases++;},pair:async()=>({expiresAt:Date.now()/1000+60})});
  await bridge.start(0);t.after(()=>bridge.close());
  bridge.token='valid-paired-capability';bridge.expiry=Date.now()-1;
  const headers={Origin:ORIGIN,Authorization:'Bearer '+bridge.token};
  const base=`http://127.0.0.1:${bridge.port}/api/v1/hr/`;
  assert.equal((await fetch(base+'jobs',{headers})).status,428);
  assert.equal((await fetch(base+'login/logout',{method:'POST',headers:{...headers,Authorization:'Bearer other'}})).status,428);
  const response=await fetch(base+'login/logout',{method:'POST',headers});
  assert.equal((await response.json()).data.cleared,true);
  assert.equal(clears,1);assert.equal(releases,1);assert.equal(bridge.token,'');
  assert.equal((await fetch(base+'login/status',{headers})).status,428);
});
test('renewing same-owner pairing keeps other browser tabs authorized and never calls SDK',async t=>{
  const {engine,calls}=await fixture(t);
  const bridge=new LocalBridge({engine,authorize:async()=>user,pair:async()=>({expiresAt:Date.now()/1000+1800})});
  await bridge.start(0);t.after(()=>bridge.close());
  const base=`http://127.0.0.1:${bridge.port}`,headers={Origin:ORIGIN,'Content-Type':'application/json'};
  const pair=async()=> (await (await fetch(base+'/pair',{method:'POST',headers,body:JSON.stringify({ticket:'a'.repeat(43)})})).json()).data;
  const first=await pair(),second=await pair();
  assert.equal(first.token,second.token);
  assert.equal((await fetch(base+'/api/v1/hr/login/status',{headers:{...headers,Authorization:'Bearer '+first.token}})).status,200);
  assert.equal(calls.length,0);
});
test('platform outage blocks polling without destroying BOSS session or bypassing risk pause',async t=>{
  const {engine,calls}=await fixture(t);
  engine.state={state:'complete'};engine.paused=false;
  engine.authorize=async()=>{throw Object.assign(Error('network'),{code:'PLATFORM_UNAVAILABLE'});};
  await engine.poll();
  assert.equal(engine.paused,false);assert.equal(calls.length,0);
  await engine.hold({code:'RATE_LIMITED'});
  engine.authorize=async()=>user;await engine.poll();
  assert.equal(engine.paused,true);assert.equal(calls.length,0);
});
test('logout cancels late login authorization and browser opening without starting a new observer',async t=>{
  const {engine}=await fixture(t);let authorize,open,opened=0;
  engine.authorize=()=>new Promise(resolve=>{authorize=resolve;});
  engine.browser.open=async()=>{opened++;};
  const pending=engine.start();
  await engine.disconnect();authorize(user);
  await assert.rejects(pending,/连接状态已更改/);
  assert.equal(opened,0);assert.equal(engine.state.state,'disconnected');
  engine.authorize=async()=>user;
  engine.browser.open=()=>new Promise(resolve=>{open=resolve;});
  const opening=engine.start();await new Promise(resolve=>setImmediate(resolve));
  await engine.disconnect();open();
  await assert.rejects(opening,/连接已取消/);
  assert.equal(engine.state.state,'disconnected');assert.equal(engine.loginTimer,null);
});
