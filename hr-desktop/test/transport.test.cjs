const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs');
const source=fs.readFileSync(require('node:path').join(__dirname,'../../frontend/hr-desktop-bridge.js'),'utf8');
function fixture(mode='local'){
  const calls=[],storage=new Map();
  const env={URL,AbortSignal,Date,Error,TypeError,JSON,sessionStorage:{
    getItem:key=>storage.get(key),setItem:(k,v)=>storage.set(k,v),removeItem:key=>storage.delete(key)},
    fetch:async(url,options)=>{
      calls.push({url,options});
      const json=data=>Response.json({success:true,data});
      if(url.endsWith('/transport'))return json({mode});
      if(url.endsWith('/health'))return json({app:'heyi-recruiter',protocol:1});
      if(url==='/api/v1/hr/desktop/pair')return json({ticket:'a'.repeat(43)});
      if(url==='http://127.0.0.1:17861/pair')return json({token:'local-token',expiresAt:Date.now()+60000});
      return json({state:'idle'});
    }};
  env.window=env;vm.runInNewContext(source,env);
  return {transport:env.HeyiHrTransport,calls,env};
}
test('unpaired online workspace never falls back to server HR execution',async()=>{
  const {transport,calls}=fixture();
  await assert.rejects(transport.api('/api/v1/hr/jobs'),/连接本机助手/);
  assert.deepEqual(calls.map(x=>x.url),['/api/v1/hr/desktop/transport']);
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
