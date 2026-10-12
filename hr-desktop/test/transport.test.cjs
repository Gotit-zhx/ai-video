const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync(require('node:path').join(__dirname,'../../frontend/hr-desktop-bridge.js'),'utf8');
function fixture(mode='local'){
  const calls=[],storage=new Map(),timers=new Map(),events={};let timerId=0;
  const env={URL,AbortSignal,Date,Error,TypeError,JSON,sessionStorage:{
    getItem:key=>storage.get(key),setItem:(k,v)=>storage.set(k,v),removeItem:key=>storage.delete(key)},
    setTimeout(fn,ms){const id=++timerId;timers.set(id,{fn,ms});return id;},
    clearTimeout(id){timers.delete(id);},
    addEventListener(name,fn){events[name]=fn;},
    document:{visibilityState:'visible',addEventListener(name,fn){events[name]=fn;}},
    fetch:async(url,options)=>{
      calls.push({url,options});
      const json=data=>Response.json({success:true,data});
      if(url.endsWith('/transport'))return json({mode});
      if(url.endsWith('/health'))return json({app:'heyi-recruiter',protocol:1,version:'0.1.19'});
      if(url==='/api/v1/hr/desktop/pair')return json({ticket:'a'.repeat(43)});
      if(url==='http://127.0.0.1:17861/pair')return json({token:'local-token',expiresAt:env.Date.now()+1800000});
      return json({state:'idle'});
    }};
  env.window=env;vm.runInNewContext(source,env);
  const tick=async()=>{
    const first=timers.entries().next().value;
    assert.ok(first,'scheduled connection check');timers.delete(first[0]);await first[1].fn();
  };
  return {transport:env.HeyiHrTransport,calls,env,timers,events,tick};
}
test('unpaired online workspace never falls back to server HR execution',async()=>{
  const {transport,calls}=fixture();
  await assert.rejects(transport.api('/api/v1/hr/jobs'),/连接本机助手/);
  assert.deepEqual(calls.map(x=>x.url),['/api/v1/hr/desktop/transport']);
});
test('outdated or unidentified assistants stop before pairing and clearly request a process restart and upgrade',async()=>{
  for(const version of ['0.1.15','0.1.16','0.1.17','0.1.18',undefined,'unknown']){
    const {transport,env,calls,tick,timers}=fixture();const original=env.fetch;
    env.fetch=async(url,options)=>url.endsWith('/health')
      ?Response.json({success:true,data:{app:'heyi-recruiter',protocol:1,version}}):original(url,options);
    const result=await transport.diagnose();
    assert.equal(result.code,'ASSISTANT_UPDATE_REQUIRED');
    assert.match(result.message,/完全退出旧助手.*0\.1\.19/);
    await assert.rejects(transport.connect(),{code:'ASSISTANT_UPDATE_REQUIRED'});
    assert.equal(transport.connection().phase,'update-required');
    transport.startAutoConnect();await tick();
    assert.equal(transport.connection().phase,'update-required');assert.equal(timers.size,0);
    assert.ok(!calls.some(call=>call.url.endsWith('/pair')||call.url.includes('/login/')));
  }
});
test('newer assistant versions remain compatible',async()=>{
  for(const version of ['0.1.19','0.1.20','0.2.0','1.0.0']){
    const {transport,env}=fixture();const original=env.fetch;
    env.fetch=async(url,options)=>url.endsWith('/health')
      ?Response.json({success:true,data:{app:'heyi-recruiter',protocol:1,version}}):original(url,options);
    assert.equal((await transport.diagnose()).ok,true);
  }
});
test('cached pairing cannot execute jobs after an older running assistant is detected',async()=>{
  const {transport,env,calls,tick}=fixture();
  await transport.connect();const original=env.fetch;
  env.fetch=async(url,options)=>url.endsWith('/health')
    ?Response.json({success:true,data:{app:'heyi-recruiter',protocol:1,version:'0.1.15'}}):original(url,options);
  transport.startAutoConnect();await tick();
  await assert.rejects(transport.api('/api/v1/hr/jobs'),{code:'ASSISTANT_UPDATE_REQUIRED'});
  assert.ok(!calls.some(call=>call.url.endsWith('/jobs')));
  env.fetch=original;await transport.connect();
  assert.equal(transport.connection().paired,true);
});
test('first local permission failure explains the connection without exposing raw fetch errors or issuing a ticket',async()=>{
  const {transport,env,calls}=fixture();
  const original=env.fetch;
  env.fetch=async(url,options)=>{
    if(url.endsWith('/health'))throw new TypeError('Failed to fetch');
    return original(url,options);
  };
  await assert.rejects(transport.connect(),error=>{
    assert.equal(error.code,'LOCAL_CONNECTION_UNAVAILABLE');
    assert.match(error.message,/同一台电脑.*本地网络/);
    assert.doesNotMatch(error.message,/Failed to fetch/);
    return true;
  });
  assert.ok(!calls.some(call=>call.url.endsWith('/pair')||call.url.includes('/login/')));
});
test('denied loopback permission is distinct from an unavailable assistant; unsupported names do not imply denial',async()=>{
  const {transport,env}=fixture();const original=env.fetch;
  env.navigator={permissions:{query:async({name})=>{
    if(name==='loopback-network')throw new TypeError('Unsupported name');
    return {state:'denied'};
  }}};
  env.fetch=async(url,options)=>{
    if(url.endsWith('/health'))throw new TypeError('Failed to fetch');
    return original(url,options);
  };
  const result=await transport.diagnose();
  assert.equal(result.ok,false);assert.equal(result.stage,'local');
  assert.equal(result.code,'LOCAL_NETWORK_PERMISSION_DENIED');
  assert.match(result.message,/浏览器已禁止.*网站设置/);
});
test('manual first health check allows time for the permission prompt; background checks remain bounded',async()=>{
  const {transport,env,calls,tick}=fixture();
  env.AbortSignal={timeout:ms=>({deadline:ms})};
  await transport.connect();
  assert.equal(calls.find(call=>call.url.endsWith('/health')).options.signal.deadline,30000);
  calls.length=0;transport.startAutoConnect();await tick();
  assert.equal(calls.find(call=>call.url.endsWith('/health')).options.signal.deadline,4000);
});
test('connection diagnosis only reads platform mode and local health, never pairs, logs out or requests BOSS data',async()=>{
  const {transport,calls}=fixture();
  const result=await transport.diagnose();
  assert.equal(result.ok,true);
  assert.deepEqual(calls.map(call=>call.url),['/api/v1/hr/desktop/transport','http://127.0.0.1:17861/health']);
  assert.ok(calls.every(call=>!call.options.method||call.options.method==='GET'));
  assert.equal(transport.connection().paired,false);
});
test('platform network failure is not reported as a local network permission denial',async()=>{
  const {transport,env}=fixture();
  env.fetch=async()=>{throw new TypeError('Failed to fetch');};
  const result=await transport.diagnose();
  assert.equal(result.ok,false);assert.equal(result.stage,'platform');
  assert.equal(result.code,'PLATFORM_NETWORK_UNAVAILABLE');
  assert.match(result.message,/平台请求未完成/);
});
test('automatic checks pair once then read only local cached login state',async()=>{
  const {transport,calls,tick,timers}=fixture();
  transport.startAutoConnect();await tick();await tick();
  assert.equal(calls.filter(x=>x.url==='/api/v1/hr/desktop/pair').length,1);
  assert.equal(calls.find(x=>x.url==='/api/v1/hr/desktop/pair').options.headers['X-Heyi-Auto-Connect'],'1');
  assert.equal(calls.filter(x=>x.url.endsWith('/login/status')).length,2);
  assert.ok([...timers.values()].every(x=>x.ms>=25000&&x.ms<=35000));
  assert.ok(calls.every(x=>!x.url.endsWith('/login/start')&&!x.url.endsWith('/jobs')));
  assert.equal(transport.connection().paired,true);
});
test('brief recovery is quiet, persistent failure is visible, and real account holds are immediate',async()=>{
  const {transport,env,tick}=fixture();
  const fetch=env.fetch;let offline=false,restricted=false;
  env.fetch=async(url,options)=>{
    if(offline&&url.endsWith('/health'))throw new TypeError('offline');
    if(url.endsWith('/login/status'))return Response.json({success:true,data:restricted
      ?{state:'verification_pending',paused:true,reconnecting:false,failure_code:'ACCOUNT_RISK'}
      :{state:'complete',paused:false}});
    return fetch(url,options);
  };
  let now=Date.now();env.Date={now:()=>now};
  transport.startAutoConnect();await tick();
  offline=true;await tick();assert.equal(transport.connection().phase,'recovering');
  now+=16000;await tick();assert.equal(transport.connection().phase,'reconnecting');
  offline=false;await tick();assert.equal(transport.connection().phase,'connected');
  restricted=true;await tick();assert.equal(transport.connection().state.failure_code,'ACCOUNT_RISK');
  assert.equal(transport.connection().state.paused,true);
});
test('lease renews before expiry without opening BOSS or replaying work',async()=>{
  const {transport,calls,tick,env}=fixture();
  transport.startAutoConnect();await tick();
  const now=Date.now();env.Date={now:()=>now+29*60000};
  await tick();
  assert.equal(calls.filter(x=>x.url==='/api/v1/hr/desktop/pair').length,2);
  assert.equal(calls.filter(x=>x.url.endsWith('/login/start')).length,0);
});
test('offline assistant backs off and recovers without business calls',async()=>{
  const {transport,calls,tick,env,timers,events}=fixture();
  const fetch=env.fetch;env.fetch=async(url,options)=>{
    if(url.endsWith('/health'))throw new TypeError('offline');
    return fetch(url,options);
  };
  transport.startAutoConnect();await tick();
  assert.ok([...timers.values()][0].ms<2500);
  await tick();assert.ok([...timers.values()][0].ms>3000);
  assert.equal(transport.connection().paired,false);
  env.fetch=fetch;events.online();await tick();
  assert.equal(transport.connection().paired,true);
  assert.ok(!calls.some(x=>x.url.endsWith('/login/start')));
});
test('reset during pairing drops late credentials and stops reconnection',{timeout:5000},async()=>{
  const {transport,env,timers}=fixture();
  const fetch=env.fetch;let resolve;
  env.fetch=(url,options)=>url=== 'http://127.0.0.1:17861/pair'
    ?new Promise(r=>{resolve=r;}):fetch(url,options);
  const connecting=transport.connect();
  while(!resolve)await new Promise(r=>setImmediate(r));
  transport.reset();resolve(Response.json({success:true,data:{token:'late',expiresAt:Date.now()+60000}}));
  await assert.rejects(connecting,/取消/);
  assert.equal(transport.connection().paired,false);assert.equal(timers.size,0);
});
test('other-device authority stops automatic recovery, never steals the lease',async()=>{
  const {transport,env,tick,timers}=fixture();
  const fetch=env.fetch;env.fetch=(url,options)=>url==='http://127.0.0.1:17861/pair'
    ?Promise.resolve(Response.json({success:false,code:'DEVICE_IN_USE',message:'另一台电脑正在使用助手'},{status:409})):fetch(url,options);
  transport.startAutoConnect();await tick();
  assert.equal(transport.connection().phase,'device-in-use');assert.equal(timers.size,0);
});
test('hidden tabs defer first permission; paired background tabs still renew before expiry',async()=>{
  const {transport,env,calls,tick,events}=fixture();env.document.visibilityState='hidden';
  transport.startAutoConnect();await tick();
  assert.ok(!calls.some(x=>x.url.endsWith('/pair')));
  env.document.visibilityState='visible';events.focus();await tick();
  assert.equal(transport.connection().paired,true);
  env.document.visibilityState='hidden';
  const now=Date.now();env.Date={now:()=>now+29*60000};await tick();
  assert.equal(calls.filter(x=>x.url==='/api/v1/hr/desktop/pair').length,2);
  assert.equal(transport.connection().paired,true);
});
test('one pairing exchange for concurrent connect; business requests only loopback',async()=>{
  const {transport,calls}=fixture();
  await Promise.all([transport.connect(),transport.connect()]);
  await transport.api('/api/v1/hr/jobs');
  assert.equal(calls.filter(x=>x.url==='/api/v1/hr/desktop/pair').length,1);
  assert.equal(calls.at(-1).url,'http://127.0.0.1:17861/api/v1/hr/jobs');
  assert.equal(calls.at(-1).options.headers.Authorization,'Bearer local-token');
  assert.equal(calls.at(-1).options.credentials,'omit');
});
test('lost local write response is never replayed on cloud',async()=>{
  const {transport,calls,env}=fixture();
  await transport.connect();
  env.fetch=async(url)=>{calls.push({url});throw new TypeError('offline');};
  const before=calls.length;
  await assert.rejects(transport.api('/api/v1/hr/resumes/request',{method:'POST',body:'{}'}),/未代为执行/);
  assert.equal(calls.length,before+1);
  assert.equal(calls.at(-1).url,'http://127.0.0.1:17861/api/v1/hr/resumes/request');
});
test('logout reset discards local authority',async()=>{
  const {transport}=fixture();
  await transport.connect();transport.reset();
  await assert.rejects(transport.api('/api/v1/hr/jobs'),/连接本机助手/);
});
test('explicit disconnect needs confirmed local cleanup before dropping its credentials',async()=>{
  const {transport,env,calls}=fixture();
  await transport.connect();
  env.fetch=async(url,options)=>{calls.push({url,options});return Response.json({success:true,data:{state:'disconnected',cleared:true}});};
  assert.equal((await transport.disconnect()).cleared,true);
  assert.equal(calls.at(-1).url,'http://127.0.0.1:17861/api/v1/hr/login/logout');
  assert.equal(calls.at(-1).options.headers.Authorization,'Bearer local-token');
  assert.equal(transport.connection().paired,false);
});
test('failed cleanup retains its retry capability and never claims success',async()=>{
  const {transport,env}=fixture();await transport.connect();
  env.fetch=async()=>Response.json({success:true,data:{state:'complete'}});
  await assert.rejects(()=>transport.disconnect(),/没有确认/);
  assert.equal(transport.connection().paired,true);
  env.fetch=async()=>{throw new TypeError('offline');};
  await assert.rejects(()=>transport.disconnect(),/offline/);
  assert.equal(transport.connection().paired,true);
});
