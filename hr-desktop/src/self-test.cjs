/* Executed only with --self-test and a disposable userData directory. */
const assert=require('node:assert/strict');
const fs=require('node:fs/promises');
const path=require('node:path');
const os=require('node:os');
const {ORIGIN}=require('./core.cjs');
const {nativeImage}=require('electron');

// Electron captures a WebContentsView separately from its parent. Assemble the
// two real renderer captures at the native view bounds for the QA artifact.
async function captureWindow(window,view){
  const shell=await window.webContents.capturePage();
  let page;
  try{page=await view.webContents.capturePage();}
  catch(error){
    if(!String(error.message).includes('display surface'))throw error;
    // Some macOS compositors cannot expose a reattached native child surface.
    // Capture that same real renderer through CDP; never substitute fixture art.
    const debug=view.webContents.debugger;
    debug.attach('1.3');
    try{
      const result=await debug.sendCommand('Page.captureScreenshot',{format:'png',fromSurface:false});
      page=nativeImage.createFromBuffer(Buffer.from(result.data,'base64'));
    }finally{debug.detach();}
  }
  const size=shell.getSize(),pageSize=page.getSize(),bounds=view.getBounds();
  assert.ok(size.width>0&&size.height>0&&pageSize.width>0&&pageSize.height>0,'Renderer captures must contain real pixels');
  const bitmap=shell.toBitmap({scaleFactor:1}),pixels=page.toBitmap({scaleFactor:1});
  for(let row=0;row<pageSize.height;row++){
    pixels.copy(bitmap,((bounds.y+row)*size.width+bounds.x)*4,row*pageSize.width*4,(row+1)*pageSize.width*4);
  }
  return nativeImage.createFromBitmap(bitmap,{...size,scaleFactor:1}).toPNG();
}

exports.run=async({app,main,orb,platformSession,createBoss,engine,worker,bridge,session,egress,snapshot})=>{
  assert.ok(process.env.HEYI_TEST_USER_DATA,'A disposable profile is required');
  assert.notEqual(app.getPath('userData'),path.join(os.homedir(),'Library/Application Support/heyi-recruiter'));
  const health=await worker.run({operation:'health'});
  assert.equal(health.ok,true,JSON.stringify(health));
  assert.equal(health.version,'2.0.0');
  assert.equal(main.isVisible(),false,'Startup must show only the floating orb');
  assert.equal(orb.isVisible(),true);
  assert.equal(orb.isAlwaysOnTop(),true);
  assert.equal(main.contentView.children.length,0,'No remote page before an explicit open');
  assert.equal(snapshot().busy,false);
  assert.equal(await main.webContents.executeJavaScript('document.querySelectorAll("nav,main,footer").length'),0);
  const output=path.resolve(process.env.HEYI_TEST_OUTPUT||'test-results');
  await fs.mkdir(output,{recursive:true});
  await orb.webContents.executeJavaScript("document.fonts.ready.then(()=>true)");
  await fs.writeFile(path.join(output,'desktop-orb.png'),(await orb.capturePage()).toPNG());
  const boss=createBoss();
  for(const target of [session.defaultSession,platformSession]){
    assert.equal(await target.resolveProxy('https://example.invalid'),`PROXY 127.0.0.1:${egress.info.port}`);
    assert.equal(await target.resolveProxy('http://127.0.0.1:17861'),'DIRECT');
  }
  assert.equal(boss.webContents.getWebRTCIPHandlingPolicy(),'disable_non_proxied_udp');
  if(process.argv.includes('--check-direct-network')){
    // Explicit local diagnostic against our public home page only. No BOSS
    // request, platform login or business API; disposable profile has no auth.
    const reply=await egress.fetch(platformSession,ORIGIN+'/',{
      method:'HEAD',bypassCustomProtocolHandlers:true,redirect:'error',
      signal:AbortSignal.timeout(15000),
    });
    assert.equal(reply.status,200);
    console.log('PHYSICAL_NETWORK_CHECK_PASSED',egress.info.adapter.name);
  }
  // Setting a synthetic cookie is local, and the entire session blocks external traffic.
  await session.defaultSession.cookies.set({url:'https://www.zhipin.com',name:'wt2',value:'offline-fixture-token',secure:true});
  await platformSession.cookies.set({url:ORIGIN,name:'platform_fixture',value:'separate-session',secure:true});
  assert.equal((await session.defaultSession.cookies.get({name:'platform_fixture'})).length,0);
  assert.equal((await platformSession.cookies.get({name:'wt2'})).length,0);
  const root=path.join(app.getPath('userData'),'recruiter/accounts/test/session');
  const diagnosis=await worker.run({operation:'diagnose-local',root,cdp:engine.cdp});
  assert.equal(diagnosis.ok,true,JSON.stringify(diagnosis));
  assert.equal(diagnosis.sessionCount,1);
  assert.equal(diagnosis.userAgent,boss.webContents.getUserAgent(),'CDP and page user agents must match without spoofing');
  const now=Date.now()/1000;
  const statuses={'offline-职位经理🚀':'开放中','offline-closed':'已关闭'};
  await fs.writeFile(path.join(root,'managed-job-statuses.json'),JSON.stringify({
    statuses,checkedAt:now,validUntil:now+300,retryAt:now+300,
  }),'utf8');
  const jobStatuses=await worker.run({operation:'job-statuses',root,cdp:engine.cdp});
  assert.equal(jobStatuses.ok,true,JSON.stringify(jobStatuses));
  assert.deepEqual(jobStatuses.data.statuses,statuses,'Frozen Python must preserve Chinese and emoji through the real pipe');
  const hrUser=await engine.authorize();
  assert.equal(hrUser.platformAdmin,false);
  assert.ok(hrUser.permissions.includes('hr:access'));
  const originalOwner=engine.owner,originalAuthorize=engine.authorize;
  try{
    assert.ok(await engine.checkOwner(),'Ordinary HR permission must authorize packaged assistants');
    engine.authorize=async()=>({...hrUser,permissions:[]});
    await assert.rejects(()=>engine.checkOwner(),/人力资源权限/);
  }finally{engine.owner=originalOwner;engine.authorize=originalAuthorize;}
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
  // The removed embedded workspace must not be needed to pair an external web page.
  const originalPair=bridge.pair;
  bridge.pair=async()=>({expiresAt:Math.floor(Date.now()/1000)+60});
  answer=await fetch(`http://127.0.0.1:${bridge.port}/pair`,{
    method:'POST',headers:{Origin:ORIGIN,'Content-Type':'application/json'},body:JSON.stringify({ticket:'x'.repeat(43)}),
  });
  const token=(await answer.json()).data.token;
  answer=await fetch(`http://127.0.0.1:${bridge.port}/api/v1/hr/login/status`,{headers:{Origin:ORIGIN,Authorization:'Bearer '+token}});
  assert.equal(answer.status,200);
  bridge.pair=originalPair;
  // Offline page fixture: no BOSS navigation, account operation or message.
  const fixture=`<!doctype html><meta charset="utf-8"><style>
    body{font:14px -apple-system,"Segoe UI","Microsoft YaHei",sans-serif;margin:0;color:#34383e;background:#fafafa}
    header{padding:26px 42px;border-bottom:1px solid #eee;background:#fff;font-size:16px;font-weight:600}
    main{max-width:800px;margin:75px auto;padding:30px}small{color:#93989e;letter-spacing:2px}
    h1{font-size:30px;margin:20px 0}p{color:#8b9096;line-height:1.9}
    button{margin-top:20px;background:#24272d;color:white;border:0;border-radius:8px;padding:14px 24px}
    section{border:1px solid #e4e6e9;background:white;border-radius:12px;padding:25px 30px;margin-top:40px}
    section p{margin:0}</style><header>BOSS 页面区域 · 离线交互测试</header><main><small>LOCAL BROWSER</small>
    <h1>页面独立运行，窗口轻盈随行。</h1><p>这是测试页面，不连接 BOSS 服务器。<br>执行光波位于窗口边缘，页面可以正常点击和滚动。</p>
    <button id="fixture-action" onclick="this.textContent='交互正常';window.clicked=true">测试页面点击</button>
    <section><p>收起、展开将保留当前页面。登录资料保留在本机。</p></section></main>`;
  await boss.webContents.loadURL('data:text/html;charset=utf-8,'+encodeURIComponent(fixture));
  assert.equal(await boss.webContents.executeJavaScript('typeof desktop'), 'undefined','Remote page has no privileged preload');
  const action=name=>orb.webContents.executeJavaScript(`desktop.action(${JSON.stringify(name)})`);
  await action('toggle');
  assert.equal(main.isVisible(),true);
  assert.equal(main.contentView.children.length,1,'Only the BOSS view is embedded');
  await boss.webContents.executeJavaScript("document.querySelector('button').click()");
  assert.equal(await boss.webContents.executeJavaScript('window.clicked'),true);
  await action('toggle');assert.equal(main.isVisible(),false);
  await orb.webContents.executeJavaScript("document.querySelector('#orb').click()");
  await new Promise(resolve=>setTimeout(resolve,80));
  assert.equal(main.isVisible(),true,'Keyboard-accessible orb click expands the page');
  assert.equal(await boss.webContents.executeJavaScript('window.clicked'),true,'Hiding must preserve page state');
  main.close();assert.equal(main.isVisible(),false);assert.equal(orb.isVisible(),true);
  await action('drag-start');assert.equal((await action('drag-end')).moved,false);
  assert.equal(main.isVisible(),false,'Drag handlers must not themselves navigate');
  await assert.rejects(()=>main.webContents.executeJavaScript("desktop.action('drag-start')"));
  await assert.rejects(()=>action('platform'),'Removed dashboard cannot be opened through old IPC');
  await action('open');
  const originalRuntime=engine.runtime;
  let finish;
  engine.runtime={run:()=>new Promise(resolve=>{finish=resolve;}),stop(){}};
  const pending=engine.call('tracked');
  await new Promise(resolve=>setTimeout(resolve,100));
  for(const window of [main,orb]){
    assert.equal(await window.webContents.executeJavaScript("document.body.classList.contains('working')"),true);
  }
  assert.equal(await main.webContents.executeJavaScript("getComputedStyle(document.querySelector('.flow-edge')).pointerEvents"),'none');
  assert.equal(boss.getVisible(),true);
  const point=await boss.webContents.executeJavaScript("(()=>{window.clicked=false;const r=document.querySelector('button').getBoundingClientRect();return {x:Math.round(r.x+10),y:Math.round(r.y+10)}})()");
  main.focus();boss.webContents.focus();
  boss.webContents.sendInputEvent({type:'mouseDown',...point,button:'left',clickCount:1});
  boss.webContents.sendInputEvent({type:'mouseUp',...point,button:'left',clickCount:1});
  await new Promise(resolve=>setTimeout(resolve,80));
  assert.equal(await boss.webContents.executeJavaScript('window.clicked'),true,'Animation must not steal page interaction');
  await main.webContents.executeJavaScript("document.fonts.ready.then(()=>true)");
  await new Promise(resolve=>setTimeout(resolve,350));
  await fs.writeFile(path.join(output,'desktop-boss-working.png'),await captureWindow(main,boss));
  await fs.writeFile(path.join(output,'desktop-orb-working.png'),(await orb.capturePage()).toPNG());
  finish({ok:true});await pending;engine.runtime=originalRuntime;
  await new Promise(resolve=>setTimeout(resolve,80));
  for(const window of [main,orb]){
    assert.equal(await window.webContents.executeJavaScript("document.body.classList.contains('working')"),false);
  }
  await fs.writeFile(path.join(output,'desktop-boss-idle.png'),await captureWindow(main,boss));
  await action('release');
  assert.equal(main.isVisible(),false);
  assert.equal(main.contentView.children.length,0);
  assert.equal((await session.defaultSession.cookies.get({name:'wt2'}))[0].value,'offline-fixture-token','Release must retain local login data');
  const savedAuth=path.join(root,'data','auth','session.enc');
  await fs.mkdir(path.dirname(savedAuth),{recursive:true});
  await fs.writeFile(savedAuth,'synthetic-saved-token');
  // Expired authority can clear its own paired session, never read or execute.
  bridge.expiry=Date.now()-1;
  answer=await fetch(`http://127.0.0.1:${bridge.port}/api/v1/hr/login/logout`,{
    method:'POST',headers:{Origin:ORIGIN,Authorization:'Bearer '+token},
  });
  assert.equal((await answer.json()).data.cleared,true);
  assert.equal((await session.defaultSession.cookies.get({})).length,0);
  assert.equal((await platformSession.cookies.get({name:'platform_fixture'})).length,1,'BOSS logout cannot clear the platform browser session');
  await assert.rejects(()=>fs.access(savedAuth));
  assert.equal(bridge.token,'');
  await fs.writeFile(path.join(output,'smoke.json'),JSON.stringify({
    ok:true,platform:process.platform,arch:process.arch,packaged:app.isPackaged,
    version:app.getVersion(),sdk:health.version,checks:['runtime-policy','cdp-cookie-isolation','user-agent-consistency',
      'frozen-unicode-job-status-pipe','hr-permission-without-admin',
      'local-routing','external-browser-pairing','no-cloud-fallback','physical-network-routing',
      'orb-only-startup','orb-toggle-and-drag','boss-only-window','no-page-reload-on-hide',
      'busy-animation-start-stop','page-interaction-during-animation','release-retains-cookies',
      'expired-pairing-cleanup','boss-cookies-and-saved-auth-deleted','platform-session-preserved','desktop-screenshot'],
  },null,2));
  console.log('DESKTOP_SELF_TEST_PASSED');
  app.quit();
};
