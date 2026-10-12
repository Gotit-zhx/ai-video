const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const os=require('node:os');
const {Recruiter,restriction,ORIGIN}=require('../src/core.cjs');
const {LocalBridge}=require('../src/bridge.cjs');
const user={username:'13800000000',id:'fixture',organizationCode:'heyi',roles:['ADMIN'],platformAdmin:true};
test('manual invitation bypasses queued reads and background fairness, with one claim and one send',async t=>{
  const {engine,directory,calls}=await fixture(t),key='a'.repeat(64),id='11111111-1111-4111-8111-111111111111';
  engine.accountRoot=path.join(directory,'accounts',key);engine.state={state:'complete'};engine.paused=false;
  let release;engine.runtime.run=async input=>{
    calls.push(input);
    if(input.operation==='tracked')await new Promise(resolve=>{release=resolve;});
    return {ok:true,data:{status:'sent'}};
  };
  const cloud=[];
  engine.passiveCloud=async(route,body)=>{
    cloud.push({route,body});
    if(route==='passive-config')return {commands:[{id,kind:'invite',friendId:'123',message:'已确认邀约'}]};
    if(route==='passive-claim')return {claimed:true};
    return {};
  };
  const current=engine.call('tracked');while(!release)await new Promise(r=>setImmediate(r));
  engine.operations.foregroundRuns=4;
  const read=engine.call('jobs'),background=engine.call('passive-local',{}, {background:true});
  const send=engine.dispatchInvitation({accountKey:key,commandId:id});
  const duplicate=engine.dispatchInvitation({accountKey:key,commandId:id});
  release();const results=await Promise.all([current,read,background,send,duplicate]);
  assert.equal(calls[1].operation,'passive-send');
  assert.equal(calls.filter(row=>row.operation==='passive-send').length,1);
  assert.equal(cloud.filter(row=>row.route==='passive-claim').length,1);
  assert.equal(cloud[0].body.commandId,id);
  assert.equal(results[3].status,'sent');assert.equal(results[4].verified,true);
});
test('manual dispatch retains a verified local receipt when cloud reporting is unavailable',async t=>{
  const {engine,directory}=await fixture(t),key='a'.repeat(64),id='11111111-1111-4111-8111-111111111111';
  engine.accountRoot=path.join(directory,'accounts',key);engine.state={state:'complete'};engine.paused=false;
  engine.runtime.run=async()=>({ok:true,data:{status:'sent'}});
  engine.passiveCloud=async route=>{
    if(route==='passive-config')return {commands:[{id,kind:'invite',friendId:'123',message:'邀约'}]};
    if(route==='passive-claim')return {claimed:true};
    if(route==='passive-report')throw Error('cloud offline');
  };
  assert.equal((await engine.dispatchInvitation({accountKey:key,commandId:id})).verified,true);
  await assert.rejects(engine.dispatchInvitation({accountKey:'b'.repeat(64),commandId:id}),/当前招聘账号/);
});
test('urgent dispatch yields only an interruptible background read and never interrupts a write',async()=>{
  const {OperationQueue}=require('../src/operation-queue.cjs');
  for(const interruptible of [false,true]){
    const queue=new OperationQueue(),order=[];
    let release,interrupts=0;
    const running=queue.add(()=>new Promise(resolve=>{release=()=>{order.push('current');resolve();};}),
      {background:true,interrupt:interruptible?()=>{interrupts++;release();}:undefined});
    const manual=queue.add(async()=>{order.push('send');},{urgent:true});
    if(!interruptible){assert.deepEqual(order,[]);release();}
    await Promise.all([running,manual]);
    assert.equal(interrupts,interruptible?1:0);assert.deepEqual(order,['current','send']);
  }
});
test('HR permission authorizes a private desktop owner without allowing another user to reuse it',async t=>{
  const {engine}=await fixture(t);
  const hr={username:'15800000001',userId:'hr-a',organizationCode:'heyi',roles:['HR_DEPARTMENT'],
    platformAdmin:false,permissions:['hr:access']};
  engine.authorize=async()=>hr;
  const owner=await engine.checkOwner();assert.match(owner,/^[a-f0-9]{64}$/);
  engine.authorize=async()=>({...hr,userId:'hr-b'});
  await assert.rejects(engine.checkOwner(),/另一位用户/);
  engine.authorize=async()=>({...hr,permissions:[]});
  await assert.rejects(engine.checkOwner(),/人力资源权限/);
});
test('official job availability is synchronized before cloud commands and reused within one minute',async t=>{
  const {engine,directory}=await fixture(t),sent=[];
  engine.accountRoot=path.join(directory,'accounts','a'.repeat(64));engine.state={state:'complete'};engine.paused=false;
  let reads=0;
  engine.runtime.run=async input=>{
    if(input.operation==='job-statuses'){reads++;return {ok:true,data:{statuses:{job1:'开放中',job2:'已关闭'},retryAt:Date.now()/1000+200}};}
    return {ok:true,data:{}};
  };
  engine.passiveCloud=async(route,body)=>{sent.push({route,body});return route==='passive-config'?{commands:[]} :{};};
  await engine.poll();await engine.poll();
  assert.equal(reads,1);
  assert.deepEqual(sent[0].body.jobStatuses,{job1:'开放中',job2:'已关闭'});
  assert.ok(sent[0].body.validFor>0&&sent[0].body.validFor<=200);
  assert.ok(sent.findIndex(x=>x.route==='passive-config')>0);
});
test('a greeting receipt is reported and bound in the same poll before the next timer turn',async t=>{
  const {engine,calls,directory}=await fixture(t);
  engine.accountRoot=path.join(directory,'accounts','a'.repeat(64));engine.state={state:'complete'};engine.paused=false;
  const cloud=[];let reconciled=false;
  engine.runtime.run=async input=>{
    calls.push(input);
    if(input.operation==='passive-step'){reconciled=true;return {ok:true,data:{greetingReceiptReady:true}};}
    if(input.operation==='passive-local'&&reconciled)return {ok:true,data:{
      reports:[{id:'c1',state:'sent'}],bindings:[{contactId:'c1',friendId:'123'}]}};
    return {ok:true,data:{}};
  };
  engine.passiveCloud=async(route,body)=>{
    cloud.push({route,body});
    if(route==='passive-config'){
      assert.ok(body.capabilities.includes('greeting-receipts-v1'));
      return {commands:[],activeContacts:[{id:'c1',status:'uncertain'}]};
    }
    return {};
  };
  await engine.poll();
  const sent=cloud.findIndex(x=>x.route==='passive-report'&&x.body.commandId==='c1'&&x.body.status==='sent');
  const bound=cloud.findIndex(x=>x.route==='passive-report'&&x.body.contactId==='c1'&&x.body.friendId==='123');
  assert.ok(sent>=0&&bound>sent,'Publish confirmed status before the account-scoped binding');
  assert.equal(calls.filter(x=>x.operation==='passive-step').length,1);
  assert.equal(calls.some(x=>['sourcing-send','passive-send'].includes(x.operation)),false);
});
test('active greeting checks budget before claim and uses its own operation with account-scoped binding reports',async t=>{
  const {engine,calls,directory}=await fixture(t);
  engine.accountRoot=path.join(directory,'accounts','a'.repeat(64));engine.state={state:'complete'};engine.paused=false;
  const cloud=[];let available=false;
  engine.runtime.run=async input=>{
    calls.push(input);
    if(input.operation==='sourcing-ready')return {ok:true,data:{allowed:available,reasonCode:'REQUEST_BUDGET_WAIT',retryAt:12345}};
    if(input.operation==='sourcing-send')return {ok:true,data:{status:'sent'}};
    return {ok:true,data:{bindings:[{contactId:'c1',friendId:'123'}]}};
  };
  engine.passiveCloud=async(route,body)=>{
    cloud.push({route,body});
    if(route==='passive-config')return {commands:[{id:'c1',kind:'active_greet',message:'已确认话术'}]};
    if(route==='passive-claim')return {claimed:true};
    return {};
  };
  await engine.poll();
  assert.equal(cloud.some(x=>x.route==='passive-claim'),false);
  assert.ok(cloud.some(x=>x.route==='passive-report'&&x.body.waitingContactId==='c1'
    &&x.body.reasonCode==='REQUEST_BUDGET_WAIT'&&x.body.retryAt===12345));
  assert.ok(calls.some(x=>x.operation==='passive-step'),'Waiting greetings must not block existing resume follow-up');
  assert.ok(cloud.some(x=>x.route==='passive-report'&&x.body.contactId==='c1'&&x.body.accountKey==='a'.repeat(64)));
  available=true;await engine.poll();
  assert.equal(calls.filter(x=>x.operation==='sourcing-send').length,1);
  assert.equal(calls.filter(x=>x.operation==='passive-send').length,0);
  assert.ok(cloud.some(x=>x.route==='passive-report'&&x.body.commandId==='c1'&&x.body.status==='sent'));
});
test('verified same recruiter retains cloud account and catches up after a fresh cookie login',async t=>{
  const {engine,calls,directory}=await fixture(t);
  engine.passiveCloud=async()=>({rules:[]});
  engine.runtime.run=async input=>{calls.push(input);return {ok:true,data:{}};};
  engine.browser.inspect=async()=>({text:'',accountId:'123',token:{cookies:{wt2:'first'},user_agent:'fixture'}});
  await engine.start();await engine.observe();
  assert.equal(engine.state.state,'complete');
  const first=engine.accountRoot;
  assert.ok(engine.reconnectId);assert.ok(engine.activityTimer);
  await engine.disconnect();
  engine.browser.inspect=async()=>({text:'',accountId:'123',token:{cookies:{wt2:'second'},user_agent:'fixture'}});
  await engine.start();await engine.observe();
  assert.equal(engine.accountRoot,first);
  assert.equal(calls.filter(call=>call.operation==='recover-account').length,1,'Bound accounts need no historical probe');
  await engine.disconnect();
  engine.browser.inspect=async()=>({text:'',accountId:'456',token:{cookies:{wt2:'third'},user_agent:'fixture'}});
  await engine.start();await engine.observe();
  assert.notEqual(engine.accountRoot,first,'A different recruiter cannot consume the old account queue');
});
test('cookie alone cannot select account history or start verification',async t=>{
  const {engine,calls}=await fixture(t);
  engine.browser.inspect=async()=>({text:'',token:{cookies:{wt2:'cookie-only'}}});
  await engine.start();await engine.observe();
  assert.equal(engine.state.state,'pending');assert.equal(calls.length,0);
});
test('incoming while a runtime operation runs is retained, coalesced, and isolated on pause',async t=>{
  const {engine,calls,directory}=await fixture(t);
  engine.accountRoot=path.join(directory,'accounts','a'.repeat(64));engine.state={state:'complete'};
  engine.passiveCloud=async()=>({rules:[]});
  const first={friend:'123',mid:'10',job:'j',at:Date.now()/1000};
  const newer={...first,mid:'11'};
  let release,started;
  const start=new Promise(resolve=>{started=resolve;});
  engine.runtime.run=async input=>{
    calls.push(input);
    if(input.operation==='passive-step'){started();await new Promise(resolve=>{release=resolve;});}
    return {ok:true,data:{}};
  };
  engine.notifyActivity([first]);engine.notifyActivity([first]);
  assert.equal(engine.activity.size,1);
  const pending=engine.poll();await start;
  engine.notifyActivity([newer]);await engine.poll();
  assert.equal(calls.filter(c=>c.operation==='passive-step').length,1);
  release();await pending;
  assert.equal(engine.activity.get('123').mid,'11','In-flight ack must not discard a newer incoming message');
  assert.ok(engine.activityTimer,'Work resumes after the running operation finishes');
  engine.pause();
  assert.equal(engine.activity.size,0);assert.equal(engine.activityTimer,null);
  engine.notifyActivity([newer]);assert.equal(engine.activity.size,0);
});
test('persisted activity and downloaded exports schedule a follow-up without waiting for the batch',async t=>{
  const {engine,directory}=await fixture(t);
  engine.accountRoot=path.join(directory,'accounts','a'.repeat(64));engine.state={state:'complete'};
  engine.passiveCloud=async()=>({rules:[]});
  engine.runtime.run=async()=>({ok:true,data:{activityPending:1,activityRetryAt:Date.now()/1000+40,exportsPending:1}});
  await engine.poll();
  assert.ok(engine.activityTimer);
  await engine.hold({code:'ACCOUNT_RISK'});
  assert.equal(engine.activityTimer,null);
});
test('passive outbox requires an atomic cloud claim and never retries lost sends',async t=>{
  const {engine,calls,directory}=await fixture(t);
  engine.accountRoot=path.join(directory,'accounts','a'.repeat(64));engine.state={state:'complete'};engine.paused=false;
  const cloudCalls=[];
  engine.passiveCloud=async(route,body)=>{
    cloudCalls.push(route);
    if(route==='passive-config')return {rules:[],commands:[{id:'c1',friendId:'123',message:'default text'}]};
    if(route==='passive-claim')return {claimed:false};
    return {};
  };
  engine.runtime.run=async input=>{calls.push(input);return {ok:true,data:{reports:[],exports:[]}};};
  await engine.poll();
  assert.deepEqual(cloudCalls,['passive-config','passive-claim']);
  assert.equal(calls.some(call=>call.operation==='passive-send'),false);
  engine.paused=true;await engine.poll();assert.equal(cloudCalls.length,2);
});
test('disconnect during passive config fetch cannot claim or send a rejection',async t=>{
  const {engine,calls,directory}=await fixture(t);
  engine.accountRoot=path.join(directory,'accounts','a'.repeat(64));engine.state={state:'complete'};engine.paused=false;
  engine.runtime.run=async input=>{calls.push(input);return {ok:true,data:{reports:[],exports:[]}};};
  engine.passiveCloud=async route=>{
    assert.equal(route,'passive-config');engine.pause();
    return {commands:[{id:'c1',friendId:'123',message:'text'}]};
  };
  await engine.poll();assert.equal(calls.some(call=>call.operation==='passive-send'),false);
});
test('passive reply template and durable events reach the right account; status remains local',async t=>{
  const {engine,calls,directory}=await fixture(t);
  engine.accountRoot=path.join(directory,'accounts','a'.repeat(64));engine.state={state:'complete'};engine.paused=false;
  const sent=[],template={id:'s1',version:2,content:'可以沟通下'};
  engine.passiveCloud=async(route,body)=>{
    sent.push({route,body});
    if(route==='passive-config')return {rules:[{id:'r1',enabled:true}],replyTemplate:template,commands:[]};
    return {};
  };
  engine.runtime.run=async input=>{
    calls.push(input);
    return input.operation==='passive-local'
      ?{ok:true,data:{events:[{id:'e1',code:'AUTO_REPLY_SENT'}]}}
      :{ok:true,data:{phase:'request_queued',counts:{r1:{ready:1}}}};
  };
  await engine.poll();
  assert.equal(sent.find(c=>c.route==='passive-report').body.accountKey,'a'.repeat(64));
  assert.equal(calls.find(c=>c.operation==='passive-ack').eventId,'e1');
  assert.deepEqual(calls.find(c=>c.operation==='passive-step').replyTemplate,template);
  const previous=calls.length;
  const status=await (await engine.route(new Request(ORIGIN+'/api/v1/hr/login/status'))).json();
  assert.equal(status.data.passive.phase,'request_queued');
  assert.equal(calls.length,previous,'status must not query BOSS');
});
test('chat preparation occurs only for ready opted-in work and never after pause',async t=>{
  const {engine,directory}=await fixture(t);
  engine.accountRoot=path.join(directory,'accounts','a'.repeat(64));engine.state={state:'complete'};engine.paused=false;
  engine.passiveCloud=async()=>({rules:[{id:'r1',enabled:true}]});
  let navigations=0;
  engine.browser.chat=async()=>{navigations++;};
  engine.runtime.run=async input=>input.operation==='passive-step'
    ?{ok:true,data:{phase:'chat_required',needsChat:true}}:{ok:true,data:{}};
  await engine.poll();await engine.poll();assert.equal(navigations,1);
  engine.chatPreparedAt=0;
  engine.runtime.run=async input=>{
    if(input.operation==='passive-step')engine.pause();
    return {ok:true,data:{needsChat:true}};
  };
  await engine.poll();assert.equal(navigations,1);
});
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
async function loggedIn(t){
  const value=await fixture(t);
  value.engine.browser.inspect=async()=>({text:'',accountId:'123',token:{cookies:{wt2:'saved-session'},user_agent:'fixture'}});
  await value.engine.start();await value.engine.observe();
  assert.equal(value.engine.state.state,'complete');
  value.calls.length=0;
  return value;
}
test('restart and sleep resume the same verified local account without login, sync or BOSS probes',async t=>{
  const {engine,calls}=await loggedIn(t);
  const account=engine.accountRoot;
  await engine.close();await engine.init(engine.cdp);await engine.reconnect();
  assert.equal(engine.state.state,'complete');assert.equal(engine.paused,false);
  assert.equal(engine.accountRoot,account);
  assert.deepEqual(calls.map(c=>c.operation),['resume-local']);
  calls.length=0;engine.pause('COMPUTER_SLEEP');engine.recoveryAt=0;await engine.reconnect();
  assert.equal(engine.state.state,'complete');
  assert.deepEqual(calls.map(c=>c.operation),['resume-local']);
});
test('temporary session loss auto-recovers; actual restrictions and explicit pause remain stopped',async t=>{
  const {engine,calls}=await loggedIn(t);
  await engine.hold({code:'SESSION_UNAVAILABLE'});await engine.reconnect();
  assert.equal(engine.riskHold,null);assert.equal(engine.state.state,'complete');
  calls.length=0;
  await engine.hold({code:'ACCOUNT_RISK'});await engine.reconnect();
  assert.equal(engine.riskHold.code,'ACCOUNT_RISK');assert.equal(calls.length,0);
  engine.riskHold=null;engine.pause();await engine.save();
  await engine.close();await engine.init(engine.cdp);await engine.reconnect();
  assert.equal(engine.autoReconnect,false);assert.equal(calls.length,0);
});
test('recovery never adopts a changed recruiter or resumes after logout',async t=>{
  const {engine,calls}=await loggedIn(t);
  engine.pause('COMPUTER_SLEEP');
  engine.browser.inspect=async()=>({text:'',accountId:'456',token:{cookies:{wt2:'other-session'}}});
  await engine.reconnect();
  assert.equal(engine.riskHold.code,'SESSION_MISMATCH');
  assert.equal(calls.length,0);
  await engine.disconnect();calls.length=0;engine.recoveryAt=0;await engine.reconnect();
  assert.equal(engine.state.state,'disconnected');assert.equal(calls.length,0);
});
test('manual pause during a local reconnect discards late recovery without a runtime request',async t=>{
  const {engine,calls}=await loggedIn(t);
  engine.pause('COMPUTER_SLEEP');
  let release;engine.browser.restore=()=>new Promise(resolve=>{release=resolve;});
  const recovering=engine.reconnect();
  while(!release)await new Promise(resolve=>setImmediate(resolve));
  engine.pause();release();await recovering;
  assert.equal(engine.paused,true);assert.equal(calls.length,0);
});
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
test('JD read stays on the local runtime and passes the selected position ID',async t=>{
  const {engine,calls,directory}=await fixture(t);
  engine.accountRoot=path.join(directory,'accounts','a'.repeat(64));engine.state={state:'complete'};engine.paused=false;
  const status=await (await engine.route(new Request(ORIGIN+'/api/v1/hr/login/status'))).json();
  assert.ok(status.data.capabilities.includes('job-jd'));
  assert.equal(calls.length,0,'Status is local only');
  const result=await engine.route(new Request(ORIGIN+'/api/v1/hr/jobs?jobId=selected-job-2'));
  assert.equal(result.status,200);
  assert.equal(calls.at(-1).operation,'jobs');assert.equal(calls.at(-1).jobId,'selected-job-2');
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
test('background failure backs off without invalidating login or rebuilding catch-up; real risk still pauses',async t=>{
  const {engine}=await fixture(t);
  engine.state={state:'complete'};engine.paused=false;
  let now=Date.now(),runs=0;engine.clock=()=>now;engine.reconnectId='original-cursor';
  engine.runtime.run=async()=>{runs++;throw Error('unknown result');};
  await engine.poll();
  assert.equal(engine.paused,false);assert.equal(engine.state.state,'complete');
  assert.equal(engine.passiveStatus.phase,'retry_wait');assert.equal(engine.backgroundRetryAt,now+30000);
  assert.equal(engine.reconnectId,'original-cursor');
  await engine.poll();assert.equal(runs,1,'No tight retry loop');
  now+=30000;await engine.poll();assert.equal(engine.backgroundRetryAt,now+60000);
  assert.equal(engine.events.some(e=>e.code==='CONNECTION_RESTORED'),false);
  now+=60000;engine.runtime.run=async()=>({ok:false,error:{code:'ACCOUNT_RISK'}});
  await engine.poll();assert.equal(engine.paused,true);assert.equal(engine.riskHold.code,'ACCOUNT_RISK');
});
test('foreground read waits for current background operation and does not pause either workflow',async t=>{
  const {engine,calls}=await fixture(t);
  engine.state={state:'complete'};
  let finish;engine.runtime.run=async input=>{
    calls.push(input);if(input.operation==='poll-resumes')await new Promise(resolve=>{finish=resolve;});
    return {ok:true,data:[]};
  };
  const background=engine.poll();while(!finish)await new Promise(r=>setImmediate(r));
  const read=engine.route(new Request(ORIGIN+'/api/v1/hr/jobs'));
  await new Promise(r=>setImmediate(r));assert.equal(calls.length,1);assert.equal(engine.operations.pending,1);
  finish();await background;
  assert.equal((await read).status,200);
  assert.deepEqual(calls.map(x=>x.operation),['poll-resumes','jobs']);
  assert.equal(engine.paused,false);assert.equal(engine.state.state,'complete');
});
test('identical concurrent reads share one result; queued foreground reads go before background work',async t=>{
  const {engine,calls}=await fixture(t);
  let finish;engine.runtime.run=async input=>{
    calls.push(input);if(input.operation==='tracked')await new Promise(resolve=>{finish=resolve;});
    return {ok:true,data:{operation:input.operation}};
  };
  const first=engine.call('tracked');
  while(!finish)await new Promise(r=>setImmediate(r));
  const background=engine.call('passive-local',{}, {background:true});
  const a=engine.call('jobs',{jobId:'same'}),b=engine.call('jobs',{jobId:'same'});
  finish();const results=await Promise.all([first,background,a,b]);
  assert.deepEqual(calls.map(x=>x.operation),['tracked','jobs','passive-local']);
  assert.deepEqual(results[2],results[3]);assert.equal(engine.busy,false);
});
test('cancelling one shared reader preserves the other; all cancelled queued writes never start',async t=>{
  const {engine,calls}=await fixture(t);
  let finish;engine.runtime.run=async input=>{
    calls.push(input);if(input.operation==='tracked')await new Promise(resolve=>{finish=resolve;});
    return {ok:true,data:[]};
  };
  const running=engine.call('tracked');while(!finish)await new Promise(r=>setImmediate(r));
  const aborted=new AbortController(),writeAbort=new AbortController();
  const cancelled=engine.call('jobs',{}, {signal:aborted.signal});
  const kept=engine.call('jobs');
  const write=engine.call('request-resume',{friendId:'123'},{signal:writeAbort.signal});
  const checks=[assert.rejects(cancelled,/取消/),assert.rejects(write,/取消/)];
  aborted.abort();writeAbort.abort();finish();await Promise.all([running,kept,...checks]);
  assert.deepEqual(calls.map(x=>x.operation),['tracked','jobs']);
});
test('pause invalidates queued writes without replaying the current operation',async t=>{
  const {engine,calls}=await fixture(t);
  let finish;engine.runtime.run=async input=>{calls.push(input);return new Promise(resolve=>{finish=resolve;});};
  const running=engine.call('tracked');while(!finish)await new Promise(r=>setImmediate(r));
  const write=engine.call('request-resume',{friendId:'123'});
  const checks=[assert.rejects(running,/连接状态已更改/),assert.rejects(write,/取消/)];
  engine.pause();finish({ok:true});await Promise.all(checks);
  assert.equal(calls.length,1);assert.equal(engine.busy,false);
});
test('a returned safety hold cancels waiting requests before they reach the runtime',async t=>{
  const {engine,calls}=await fixture(t);
  let finish;engine.runtime.run=async input=>{calls.push(input);return new Promise(resolve=>{finish=resolve;});};
  const running=engine.call('jobs');while(!finish)await new Promise(r=>setImmediate(r));
  const queued=engine.call('request-resume',{friendId:'123'});
  const check=assert.rejects(queued,/取消/);
  finish({ok:false,error:{code:'SECURITY_VERIFICATION_REQUIRED'}});
  await running;await check;
  assert.equal(calls.length,1);assert.equal(engine.paused,true);
});
test('same-owner lease renewal works while the SDK is busy without interrupting or calling it',async t=>{
  const {engine,calls}=await fixture(t);engine.busy=true;
  const bridge=new LocalBridge({engine,authorize:async()=>user,pair:async()=>({expiresAt:Date.now()/1000+1800})});
  await bridge.start(0);t.after(()=>bridge.close());
  const reply=await fetch(`http://127.0.0.1:${bridge.port}/pair`,{method:'POST',
    headers:{Origin:ORIGIN,'Content-Type':'application/json'},body:JSON.stringify({ticket:'a'.repeat(43)})});
  assert.equal(reply.status,200);assert.equal(engine.busy,true);assert.equal(calls.length,0);
});
test('safety hold arriving during authorization prevents runtime dispatch',async t=>{
  const {engine,calls}=await fixture(t);
  let authorize;engine.authorize=()=>new Promise(resolve=>{authorize=resolve;});
  const pending=engine.call('request-resume',{friendId:'123'});
  while(!authorize)await new Promise(r=>setImmediate(r));
  const check=assert.rejects(pending,/连接状态已更改/);
  await engine.hold({code:'ACCOUNT_RISK'});authorize(user);await check;
  assert.equal(calls.length,0);
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
