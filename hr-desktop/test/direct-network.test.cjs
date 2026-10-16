const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const os=require('node:os');
const path=require('node:path');
const vm=require('node:vm');
const {spawn}=require('node:child_process');
const {DirectEgress,directEnvironment}=require('../src/direct-network.cjs');

function startupFixture(t,ready){
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'heyi-relay-startup-'));
  const marker=path.join(root,'ready');
  const info={port:54321,secret:'x'.repeat(32),adapter:{address:'127.0.0.1',index:1}};
  let child,closed;
  const module={exports:{}};
  vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../src/direct-network.cjs'),'utf8'),{
    module,process,clearTimeout,setImmediate,clearImmediate,
    setTimeout:(callback,delay)=>setTimeout(callback,delay===20000?80:delay),
    require:name=>name==='node:child_process'?{spawn:()=>{
      child=spawn(process.execPath,['-e',`
        ${ready?`process.stdout.write(${JSON.stringify(JSON.stringify(info)+'\n')},
          ()=>require('node:fs').writeFileSync(${JSON.stringify(marker)},'ready'));`:''}
        process.stdin.resume();`],{stdio:['pipe','pipe','ignore']});
      closed=new Promise(resolve=>child.once('close',resolve));
      return child;
    }}:require(name),
  });
  const egress=new module.exports.DirectEgress('own-fixture');
  t.after(async()=>{
    if(child&&child.exitCode===null&&child.signalCode===null)child.kill();
    await closed;
    fs.rmSync(root,{recursive:true,force:true});
  });
  return {egress,marker,closed:()=>closed};
}

test('a buffered worker handshake survives a blocked main thread beyond the startup deadline',async t=>{
  const {egress,marker}=startupFixture(t,true);
  const starting=egress.start();
  // Block only this isolated test process, until the actual child has flushed
  // the handshake. This reproduces a synchronous OS encryption/keychain call.
  const deadline=Date.now()+5000,waiter=new Int32Array(new SharedArrayBuffer(4));
  while(!fs.existsSync(marker)&&Date.now()<deadline)Atomics.wait(waiter,0,0,5);
  Atomics.wait(waiter,0,0,150);
  assert.equal(fs.existsSync(marker),true,'The real worker must have written first');
  assert.equal((await starting).port,54321);
  await egress.close();
});
test('a silent worker still times out and cleanup resolves after signal termination',async t=>{
  const {egress,closed}=startupFixture(t,false);
  await assert.rejects(egress.start(),/未找到可用的直联网卡/);
  await closed();
  assert.notEqual(egress.child.signalCode,null);
  let timer;
  try{
    const resolved=await Promise.race([egress.close().then(()=>true),
      new Promise(resolve=>{timer=setTimeout(()=>resolve(false),250);})]);
    assert.equal(resolved,true,'A finished signal termination must not wait for a second close event');
  }finally{clearTimeout(timer);}
});

test('environment strips all inherited proxy forms but preserves unrelated settings',()=>{
  assert.deepEqual(directEnvironment({HTTPS_PROXY:'secret',http_proxy:'x',All_Proxy:'y',
    no_proxy:'remote',HOME:'/home/test',NODE_ENV:'production'}),{
    HOME:'/home/test',NODE_ENV:'production',NO_PROXY:'*',PYTHONUTF8:'1',PYTHONIOENCODING:'utf-8',
  });
});
test('sessions have exactly one loopback egress without default route fallback',async()=>{
  const egress=new DirectEgress('unused');egress.info={port:54321,secret:'private'};
  let config,closed=false;
  await egress.configure({setProxy:async value=>{config=value;},closeAllConnections:async()=>{closed=true;}});
  assert.equal(config.mode,'fixed_servers');
  assert.equal(config.proxyRules,'http=127.0.0.1:54321;https=127.0.0.1:54321');
  assert.equal(closed,true);
  assert.ok(!config.proxyRules.includes('DIRECT'));
});
test('relay credentials are never provided to websites or another local proxy',()=>{
  const egress=new DirectEgress('unused');egress.info={port:54321,secret:'private'};
  let calls=0;
  const event={preventDefault:()=>{calls++;}};
  const callback=(name,secret)=>{assert.equal(name,'heyi');assert.equal(secret,'private');calls++;};
  assert.equal(egress.authenticate(event,{isProxy:false,host:'127.0.0.1',port:54321},callback),false);
  assert.equal(egress.authenticate(event,{isProxy:true,host:'127.0.0.1',port:1082},callback),false);
  assert.equal(calls,0);
  assert.equal(egress.authenticate(event,{isProxy:true,host:'127.0.0.1',port:54321},callback),true);
  assert.equal(calls,2);
});
