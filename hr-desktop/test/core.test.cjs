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
