const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');
const {launchInspecting, evaluateRunning} = require('./inspector-driver.cjs');
const {discoverNativeRequestBuilder} = require('./native-request-discovery.cjs');

async function fixture(t, {ambiguous = false, failVerification = false} = {}) {
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'heyi-request-discovery-'));
  const file = path.join(root, 'fixture.cjs');
  const httpModule = path.join(root, 'node_modules/@bzl/electron-node-fetch/dist/index.js');
  await fs.mkdir(path.dirname(httpModule), {recursive: true});
  await fs.writeFile(httpModule, 'exports.enfetch=class Enfetch{send(){fixtureHTTPCalls++;throw Error("No network")}};');
  await fs.writeFile(file, `
    globalThis.fixtureHTTPCalls=0;globalThis.fixtureSDKCalls=0;globalThis.fixtureBuilderCalls=0;
    class Identity{get userInfo(){throw Error('private identity')}
      get userId(){throw Error('private identity')}get avatar(){throw Error('private identity')}}
    class Authentication{get(){}getAuthCookieHeader(){}setLoginCookies(){}clearLoginCookies(){}}
    class Network{getProxyConfig(){}getHttpProxy(){}getDNSProxy(){}setProxy(){}stopProxy(){}}
    class Receiver{receiveMessage(){}addMessageById(){}getMessageById(){}}
    class Sender{send(){}connect(){}onMessage(){}sendPresence(){}}
    class Factory{getProtoMsg(){}generateMsgProto(){}}
    globalThis.keepAlive=[new Identity,new Authentication,new Network,new Receiver,new Sender,Factory];
    globalThis.fixtureHTTP=new (require(${JSON.stringify(httpModule)}).enfetch);
    function makeBuilder(){return async function actualFixtureBuilder(input){
      fixtureBuilderCalls++;
      if(${failVerification}&&input.url.includes('job/data'))throw Error('private verification failure');
      await Promise.resolve();
      const prepared={...input,url:'https://www.zhipin.com'+input.url,
        headers:{Cookie:'fixture-private-cookie','User-Agent':'fixture-private-agent',Traceid:'fixture-private-trace'}};
      return new Promise(resolve=>{fixtureHTTP.send(prepared).then(response=>resolve(response.body));});
    }}
    const builder=makeBuilder();
    ${ambiguous ? 'globalThis.duplicateBuilder=makeBuilder();' : ''}
    globalThis.fixtureSDK={
      _sdk_getLoginStatus:async()=>false,_sdk_getNetworkStatus:async()=>false,
      _sdk_getUserInfo:async function(refresh=false){fixtureSDKCalls++;
        if(refresh)return builder({url:'/wapi/zpuser/wap/getUserInfo.json'});},
      _sdk_chatSocket_sendTextMessage(){throw Error('must not send')}
    };
    globalThis.$socket={clientID:new Map,handleEvent(_name,socket){
      this.clientID.set(socket.handshake.query.cid,{sid:socket.id});
      socket.on('message',request=>Promise.resolve(fixtureSDK[request.act]()).then(data=>
        socket.emit('message',{...request,data:{code:0,data}})));
    }};
    globalThis.fixtureReady=true;setInterval(()=>{},1000);
  `);
  const connection = await launchInspecting(process.execPath, [file]);
  t.after(async () => {
    connection.client.close();connection.child.kill();
    await fs.rm(root, {recursive: true, force: true});
  });
  const deadline = Date.now() + 5000;
  while (!await evaluateRunning(connection, 'globalThis.fixtureReady===true')) {
    if (Date.now() >= deadline) throw Error('Fixture did not start');
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  return connection;
}
test('discovery requires a verified anonymous process before inspecting it', async () => {
  let calls = 0;
  await assert.rejects(discoverNativeRequestBuilder({client: {command() {calls++;}}}),
    /Verified anonymous isolated/);
  assert.equal(calls, 0);
});
test('captures the actual caller by name and location, uses its native headers, and restores', async t => {
  const connection = await fixture(t);
  const report = await discoverNativeRequestBuilder(connection,
    {anonymousIsolated: true, pinnedOriginalVerified: true});
  assert.equal(report.ok, true);
  assert.equal(report.originalSDKCalls, 1);assert.equal(report.originalBuilderCalls, 1);
  assert.equal(report.originalHTTPCalls, 0);assert.equal(report.interceptedRequests, 2);
  assert.equal(await evaluateRunning(connection, 'fixtureSDKCalls'), 1);
  assert.equal(await evaluateRunning(connection, 'fixtureBuilderCalls'), 2);
  assert.equal(await evaluateRunning(connection, 'fixtureHTTPCalls'), 0);
  assert.equal(await evaluateRunning(connection, 'Object.hasOwn(fixtureHTTP,"send")'), false);
  for (const text of ['fixture-private-cookie', 'fixture-private-agent', 'fixture-private-trace', 'objectId']) {
    assert.equal(JSON.stringify(report).includes(text), false);
  }
});
test('same-name and same-location closures remain ambiguous and are not guessed', async t => {
  const connection = await fixture(t, {ambiguous: true});
  await assert.rejects(discoverNativeRequestBuilder(connection,
    {anonymousIsolated: true, pinnedOriginalVerified: true}),
  error => error.requestDiscoveryDiagnostic.stage === 'match-actual-caller-location');
  assert.equal(await evaluateRunning(connection, 'fixtureSDKCalls'), 1);
  assert.equal(await evaluateRunning(connection, 'fixtureBuilderCalls'), 1);
  assert.equal(await evaluateRunning(connection, 'fixtureHTTPCalls'), 0);
  assert.equal(await evaluateRunning(connection, 'Object.hasOwn(fixtureHTTP,"send")'), false);
});
test('a failed builder verification is not retried or changed into success', async t => {
  const connection = await fixture(t, {failVerification: true});
  const report = await discoverNativeRequestBuilder(connection,
    {anonymousIsolated: true, pinnedOriginalVerified: true});
  assert.equal(report.ok, false);
  assert.equal(report.interceptedRequests, 1);
  assert.equal(await evaluateRunning(connection, 'fixtureBuilderCalls'), 2);
  assert.equal(await evaluateRunning(connection, 'fixtureHTTPCalls'), 0);
  assert.equal(await evaluateRunning(connection, 'Object.hasOwn(fixtureHTTP,"send")'), false);
});
