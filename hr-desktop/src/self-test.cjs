/* Executed only with --self-test and a disposable userData directory. */
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const os=require('node:os');
const {ORIGIN}=require('./core.cjs');

exports.run=async({app,main,platformView,createBoss,engine,worker,bridge,show,session,egress})=>{
  assert.ok(process.env.HEYI_TEST_USER_DATA,'A disposable profile is required');
  assert.notEqual(app.getPath('userData'),path.join(os.homedir(),'Library/Application Support/heyi-recruiter'));
  const health=await worker.run({operation:'health'});
  assert.equal(health.ok,true,JSON.stringify(health));
  assert.equal(health.version,'2.0.0');
  const boss=createBoss();
  for(const target of [session.defaultSession,platformView.webContents.session]){
    assert.equal(await target.resolveProxy('https://example.invalid'),`PROXY 127.0.0.1:${egress.info.port}`);
    assert.equal(await target.resolveProxy('http://127.0.0.1:17861'),'DIRECT');
  }
  assert.equal(boss.webContents.getWebRTCIPHandlingPolicy(),'disable_non_proxied_udp');
  if(process.argv.includes('--check-direct-network')){
    // Explicit local diagnostic against our public home page only. No BOSS
    // request, platform login or business API; disposable profile has no auth.
    const reply=await egress.fetch(platformView.webContents.session,ORIGIN+'/',{
      method:'HEAD',bypassCustomProtocolHandlers:true,redirect:'error',
      signal:AbortSignal.timeout(15000),
    });
    assert.equal(reply.status,200);
    console.log('PHYSICAL_NETWORK_CHECK_PASSED',egress.info.adapter.name);
  }
  // Setting a synthetic cookie is local, and the entire session blocks external traffic.
  await session.defaultSession.cookies.set({url:'https://www.zhipin.com',name:'wt2',value:'offline-fixture-token',secure:true});
  await platformView.webContents.session.cookies.set({url:ORIGIN,name:'platform_fixture',value:'separate-session',secure:true});
  assert.equal((await session.defaultSession.cookies.get({name:'platform_fixture'})).length,0);
  const root=path.join(app.getPath('userData'),'recruiter/accounts/test/session');
  const diagnosis=await worker.run({operation:'diagnose-local',root,cdp:engine.cdp});
  assert.equal(diagnosis.ok,true,JSON.stringify(diagnosis));
  assert.equal(diagnosis.sessionCount,1);
  assert.equal(diagnosis.userAgent,boss.webContents.getUserAgent(),'CDP and page user agents must match without spoofing');
  let answer=await engine.route(new Request(ORIGIN+'/api/v1/hr/login/status'));
  assert.equal(answer.status,200);
  assert.notEqual((await answer.json()).data.state,'complete');
  answer=await engine.route(new Request(ORIGIN+'/api/v1/hr/jobs'));
  assert.equal(answer.status,409,'Never query BOSS before explicit login');
  assert.equal(await engine.route(new Request(ORIGIN+'/api/v1/auth/me')),null);
  answer=await fetch(`http://127.0.0.1:${bridge.port}/health`,{headers:{Origin:ORIGIN}});
  assert.equal((await answer.json()).data.app,'heyi-recruiter');
  answer=await fetch(`http://127.0.0.1:${bridge.port}/api/v1/hr/jobs`,{headers:{Origin:ORIGIN}});
  assert.equal(answer.status,428,'Unpaired callers cannot run recruitment operations');
  // This request is handled by the local protocol adapter, without a cloud call.
  const transport=await platformView.webContents.session.fetch(ORIGIN+'/api/v1/hr/desktop/transport');
  assert.equal((await transport.json()).data.mode,'embedded');
  show('home');
  main.show();
  await main.webContents.executeJavaScript("document.fonts.ready.then(()=>true)");
  await new Promise(resolve=>setTimeout(resolve,350));
  const output=path.resolve(process.env.HEYI_TEST_OUTPUT||'test-results');
  await fs.mkdir(output,{recursive:true});
  await fs.writeFile(path.join(output,'desktop-home.png'),(await main.webContents.capturePage()).toPNG());
  await fs.writeFile(path.join(output,'smoke.json'),JSON.stringify({
    ok:true,platform:process.platform,arch:process.arch,packaged:app.isPackaged,
    sdk:health.version,checks:['runtime-policy','cdp-cookie-isolation','user-agent-consistency',
      'local-routing','pairing-required','no-cloud-fallback','physical-network-routing','desktop-screenshot'],
  },null,2));
  console.log('DESKTOP_SELF_TEST_PASSED');
  app.quit();
};
