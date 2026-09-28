const {test}=require('node:test');
const assert=require('node:assert/strict');
const {DirectEgress,directEnvironment}=require('../src/direct-network.cjs');

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
