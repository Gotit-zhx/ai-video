const {app,BrowserWindow,WebContentsView,session,ipcMain,Menu,Tray,nativeImage,powerMonitor,dialog,screen}=require('electron');
const fs=require('node:fs/promises');
const path=require('node:path');
const crypto=require('node:crypto');
const {ORIGIN,Runtime,Recruiter}=require('./core.cjs');
const {LocalBridge}=require('./bridge.cjs');
const {DirectEgress}=require('./direct-network.cjs');
const {ORB_SIZE,orbBounds,bossBounds}=require('./window-layout.cjs');
const {pathToFileURL}=require('node:url');

const selfTest=process.argv.includes('--self-test');
if(process.env.HEYI_TEST_USER_DATA)app.setPath('userData',process.env.HEYI_TEST_USER_DATA);
app.commandLine.appendSwitch('remote-debugging-address','127.0.0.1');
app.commandLine.appendSwitch('remote-debugging-port','0');
// TCP/TLS takes the app's physical-interface route; QUIC/UDP must not escape it.
app.commandLine.appendSwitch('disable-quic');
let main,orb,platformSession,bossView,worker,engine,bridge,tray,quitting=false;
let egress;
let pageError='',bossLoad=null,drag=null,positionSave=Promise.resolve();
let device='',lease=null,leaseChecked=0,leaseCheck=null;
const viewOptions={nodeIntegration:false,contextIsolation:true,sandbox:true,webSecurity:true};
const shellPath=path.join(__dirname,'index.html');
const orbPath=path.join(__dirname,'orb.html');

function snapshot(){
  return {...engine?.snapshot(),expanded:main?.isVisible()||false,loading:bossView?.webContents.isLoading()||false,
    pageError,version:app.getVersion(),platform:process.platform};
}
function publish(){
  for(const window of [main,orb])if(window&&!window.isDestroyed())window.webContents.send('state',snapshot());
}
function resize(){
  bossView?.setBounds(bossBounds(main.getContentBounds()));
}
function hideBoss(){
  main.hide();orb.showInactive();publish();
}
async function openBoss(){
  const view=createBoss();
  if(main.isMinimized())main.restore();
  main.show();main.focus();publish();
  if(bossLoad)return bossLoad;
  const current=view.webContents.getURL();
  if(!current||current==='about:blank'){
    const loading=view.webContents.loadURL('https://www.zhipin.com/web/user/');
    bossLoad=loading;
    try {await loading;}
    catch(error){
      if(bossView===view){pageError='BOSS 页面未加载，请检查网络后右键重试';publish();}
      throw error;
    }finally{if(bossLoad===loading)bossLoad=null;}
  }
}
function keepOrbVisible(){
  if(!orb||orb.isDestroyed())return;
  const bounds=orb.getBounds();
  const display=screen.getDisplayMatching(bounds);
  orb.setBounds(orbBounds(bounds,display.workArea));
}
function startDrag(){
  if(drag)return;
  drag={point:screen.getCursorScreenPoint(),bounds:orb.getBounds(),moved:false};
  drag.timer=setInterval(()=>{
    if(!drag)return;
    const point=screen.getCursorScreenPoint(),dx=point.x-drag.point.x,dy=point.y-drag.point.y;
    if(Math.hypot(dx,dy)>4)drag.moved=true;
    if(drag.moved)orb.setPosition(drag.bounds.x+dx,drag.bounds.y+dy,false);
  },16);
}
function endDrag(){
  if(!drag)return {moved:true};
  const point=screen.getCursorScreenPoint(),dx=point.x-drag.point.x,dy=point.y-drag.point.y;
  if(Math.hypot(dx,dy)>4){
    drag.moved=true;orb.setPosition(drag.bounds.x+dx,drag.bounds.y+dy,false);
  }
  const moved=drag.moved;clearInterval(drag.timer);drag=null;keepOrbVisible();
  if(moved){
    const {x,y}=orb.getBounds();
    positionSave=positionSave.catch(()=>{}).then(()=>fs.writeFile(
      path.join(app.getPath('userData'),'orb-position.json'),JSON.stringify({x,y}),{mode:0o600}));
  }
  return {moved};
}
async function releaseBrowser(){
  engine.pause();
  if(engine.busy)throw Error('当前任务正在执行，请先暂停并等待结束');
  hideBoss();
  if(bossView){main.contentView.removeChildView(bossView);bossView.webContents.close();bossView=null;}
  pageError='';bossLoad=null;
  engine.state={...engine.state,state:'verification_pending',failure_code:'MANUAL_REVIEW_REQUIRED',
    error:'浏览器资源已释放，登录资料已保留。需要时请在网页连接。'};
  engine.event('BROWSER_RELEASED');
}
function menuItems(){
  return [
    {label:main.isVisible()?'收起 BOSS 页面':'打开 BOSS 页面',click:()=>perform(main.isVisible()?'hide':'open')},
    {label:'重新加载 BOSS 页面',enabled:!!bossView&&!engine.busy,click:()=>perform('reload')},
    {type:'separator'},
    {label:'暂停本机任务',click:()=>perform('pause')},
    {label:'释放浏览器资源',enabled:!engine.busy,click:()=>perform('release')},
    {type:'separator'},{label:'退出助手',click:()=>perform('quit')},
  ];
}
async function perform(action){
  try{return await actionFor(action);}
  catch(error){dialog.showErrorBox('操作未完成',error.message);}
}
async function actionFor(action){
  if(action==='state')return snapshot();
  if(action==='open')await openBoss();
  else if(action==='toggle'){if(main.isVisible())hideBoss();else await openBoss();}
  else if(action==='hide')hideBoss();
  else if(action==='maximize'){if(main.isMaximized())main.unmaximize();else main.maximize();}
  else if(action==='reload'){
    if(engine.busy)throw Error('请等待当前任务结束后再重新加载');
    pageError='';bossView?.webContents.reload();
  }
  else if(action==='pause')engine.pause('USER_PAUSED');
  else if(action==='release')await releaseBrowser();
  else if(action==='quit'){quitting=true;app.quit();}
  else throw Error('不支持的应用操作');
  return snapshot();
}
function browserBoundary(contents,host){
  contents.setWebRTCIPHandlingPolicy('disable_non_proxied_udp');
  contents.setWindowOpenHandler(({url})=>{
    try {const u=new URL(url);if(u.protocol==='https:'&&(u.hostname===host||u.hostname.endsWith('.'+host)))contents.loadURL(url);}
    catch{}
    return {action:'deny'};
  });
  contents.on('will-navigate',(event,url)=>{
    try{const u=new URL(url);if(u.protocol!=='https:'||!(u.hostname===host||u.hostname.endsWith('.'+host)))event.preventDefault();}
    catch{event.preventDefault();}
  });
  contents.session.setPermissionRequestHandler((_contents,_permission,callback)=>callback(false));
  contents.session.setPermissionCheckHandler(()=>false);
}
async function localEndpoint(){
  const file=path.join(app.getPath('userData'),'DevToolsActivePort');
  const end=Date.now()+10000;
  while(Date.now()<end){
    try{
      const port=Number((await fs.readFile(file,'utf8')).split('\n')[0]);
      if(port>0&&port<65536)return `http://127.0.0.1:${port}`;
    }catch{}
    await new Promise(resolve=>setTimeout(resolve,100));
  }
  throw Error('本机浏览器连接未就绪');
}
function executable(){
  const root=app.isPackaged?path.join(process.resourcesPath,'runtime')
    :path.join(__dirname,'../runtime/dist/heyi-boss');
  return path.join(root,process.platform==='win32'?'heyi-boss.exe':'heyi-boss');
}
function createBoss(){
  if(bossView && !bossView.webContents.isDestroyed())return bossView;
  bossView=new WebContentsView({webPreferences:{...viewOptions,session:session.defaultSession}});
  browserBoundary(bossView.webContents,'zhipin.com');
  main.contentView.addChildView(bossView);resize();
  bossView.webContents.on('did-start-loading',()=>{pageError='';publish();});
  bossView.webContents.on('did-stop-loading',publish);
  bossView.webContents.on('did-fail-load',(_event,code,_description,_url,isMainFrame)=>{
    if(isMainFrame&&code!==-3){pageError='BOSS 页面未加载，请检查网络后右键重试';publish();}
  });
  bossView.webContents.on('render-process-gone',()=>{
    pageError='BOSS 页面已关闭，请右键重新加载';engine.pause('BROWSER_UNAVAILABLE');publish();
  });
  return bossView;
}
async function inspectBoss(){
  if(!bossView||bossView.webContents.isDestroyed())return {text:'',token:null};
  const url=new URL(bossView.webContents.getURL()||'about:blank');
  if(url.hostname!=='www.zhipin.com')return {text:'',token:null};
  const text=await bossView.webContents.executeJavaScript("(document.body?.innerText || '').slice(0,16000)",false);
  const jar=await session.defaultSession.cookies.get({});
  const cookies={};
  for(const cookie of jar) {
    const domain=cookie.domain.replace(/^\./,'');
    if(domain==='zhipin.com'||domain.endsWith('.zhipin.com'))cookies[cookie.name]=cookie.value;
  }
  return {text,token:{cookies,stoken:cookies.__zp_stoken__||'',user_agent:bossView.webContents.getUserAgent()}};
}
async function qr(){
  if(!bossView)throw Error('请在应用的 BOSS 页面扫码');
  const rect=await bossView.webContents.executeJavaScript(`(()=>{
    const el=document.querySelector('.qr-img-box img');if(!el)return null;
    const r=el.getBoundingClientRect();return {x:Math.round(r.x),y:Math.round(r.y),width:Math.round(r.width),height:Math.round(r.height)};
  })()`,false);
  if(!rect||rect.width<30||rect.height<30)throw Error('请在应用的 BOSS 页面完成确认');
  return (await bossView.webContents.capturePage(rect)).toPNG();
}
async function authorized(){
  if(selfTest)return {username:'13800000000',id:'offline-test',organizationCode:'heyi',roles:['ADMIN'],platformAdmin:true};
  if(lease && lease.expiresAt*1000>Date.now()){
    if(Date.now()-leaseChecked<15000)return lease.user;
    if(!leaseCheck)leaseCheck=cloud('lease',{token:lease.token,device})
      .then(result=>{leaseChecked=Date.now();return result.user;})
      .catch(error=>{lease=null;engine.pause('DEVICE_DISCONNECTED');throw error;})
      .finally(()=>{leaseCheck=null;});
    return leaseCheck;
  }
  throw Error('请在工作台点击“连接本机助手”');
}
async function cloud(route,body){
  if(selfTest)throw Error('Cloud access disabled in offline tests');
  const reply=await egress.fetch(platformSession,ORIGIN+'/api/v1/hr/desktop/'+route,{
    method:'POST',body:body?JSON.stringify(body):undefined,
    headers:{'Content-Type':'application/json',Origin:ORIGIN},
    bypassCustomProtocolHandlers:true,redirect:'error',signal:AbortSignal.timeout(8000),
  });
  const result=await reply.json();
  if(!reply.ok||!result.success)throw Error(result.message||'请先登录赫逸平台，再连接本机助手');
  return result.data;
}
async function pair(ticket){
  const connected=await cloud('redeem',{ticket,device});
  lease=connected;leaseChecked=0;
  engine.event('WORKSPACE_CONNECTED');return connected;
}
async function launch(){
  if(!app.requestSingleInstanceLock()){app.quit();return;}
  app.on('second-instance',()=>{orb?.showInactive();});
  await app.whenReady();
  await fs.mkdir(app.getPath('userData'),{recursive:true,mode:0o700});
  const deviceFile=path.join(app.getPath('userData'),'device.json');
  try{device=JSON.parse(await fs.readFile(deviceFile,'utf8')).id;}catch{}
  if(!/^[a-f0-9-]{36}$/.test(device)){
    device=crypto.randomUUID();await fs.writeFile(deviceFile,JSON.stringify({id:device}),{mode:0o600});
  }
  const shellPreferences={...viewOptions,partition:'desktop-shell',preload:path.join(__dirname,'preload.cjs')};
  main=new BrowserWindow({width:1280,height:840,minWidth:900,minHeight:600,title:'BOSS · 赫逸招聘助手',
    backgroundColor:'#1e2024',show:false,frame:false,autoHideMenuBar:true,webPreferences:shellPreferences});
  main.on('resize',resize);
  main.on('close',event=>{if(!quitting){event.preventDefault();hideBoss();}});
  main.on('minimize',()=>{if(!quitting)hideBoss();});
  let savedPosition;
  try{savedPosition=JSON.parse(await fs.readFile(path.join(app.getPath('userData'),'orb-position.json'),'utf8'));}catch{}
  const display=savedPosition&&Number.isFinite(savedPosition.x)&&Number.isFinite(savedPosition.y)
    ?screen.getDisplayNearestPoint(savedPosition):screen.getPrimaryDisplay();
  orb=new BrowserWindow({...orbBounds(savedPosition,display.workArea),minWidth:ORB_SIZE,minHeight:ORB_SIZE,
    maxWidth:ORB_SIZE,maxHeight:ORB_SIZE,frame:false,transparent:true,backgroundColor:'#00000000',
    alwaysOnTop:true,skipTaskbar:true,resizable:false,maximizable:false,minimizable:false,
    fullscreenable:false,hasShadow:false,thickFrame:false,autoHideMenuBar:true,show:false,title:'赫逸招聘助手',webPreferences:shellPreferences});
  orb.setVisibleOnAllWorkspaces(true,{visibleOnFullScreen:true,skipTransformProcessType:true});
  orb.on('close',event=>{if(!quitting)event.preventDefault();});
  orb.on('blur',()=>{if(drag)endDrag();});
  screen.on('display-removed',keepOrbVisible);
  screen.on('display-metrics-changed',keepOrbVisible);
  platformSession=session.fromPartition('persist:hoyee-platform');
  egress=new DirectEgress(executable());
  await egress.start(selfTest&&!process.argv.includes('--check-direct-network'));
  app.on('login',(event,_contents,_details,authInfo,callback)=>egress.authenticate(event,authInfo,callback));
  await egress.configure(session.defaultSession);
  await egress.configure(platformSession);
  if(selfTest){
    // No external traffic, even if a smoke fixture accidentally navigates.
    for(const target of [platformSession,session.defaultSession]){
      target.webRequest.onBeforeRequest((details,done)=>{
        const url=new URL(details.url);
        const diagnostic=process.argv.includes('--check-direct-network')&&target===platformSession
          &&url.origin===ORIGIN&&url.pathname==='/'&&details.method==='HEAD';
        done({cancel:['http:','https:','ws:','wss:'].includes(url.protocol)&&url.hostname!=='127.0.0.1'&&!diagnostic});
      });
    }
  }
  worker=new Runtime(executable(),egress.info.adapter);
  engine=new Recruiter({
    directory:path.join(app.getPath('userData'),'recruiter'),
    runtime:worker,authorize:authorized,
    browser:{
      open:openBoss,
      inspect:inspectBoss,qr,
      refresh:async()=>{createBoss().webContents.reload();},
      connected:publish,
      clear:async()=>{
        hideBoss();
        if(bossView){main.contentView.removeChildView(bossView);bossView.webContents.close();bossView=null;}
        pageError='';bossLoad=null;
        // Electron profile is exclusive to this app. Never call remote logout.
        // The default session is dedicated to BOSS; the Hoyee session is separate.
        await session.defaultSession.clearStorageData();
        await session.defaultSession.clearCache();
      },
    }
  });
  await engine.init(await localEndpoint());
  egress.on('stopped',()=>{if(!quitting)engine.pause('DIRECT_NETWORK_UNAVAILABLE');});
  bridge=new LocalBridge({engine,pair,authorize:authorized});
  await bridge.start(selfTest?0:17861);
  engine.on('change',publish);
  ipcMain.handle('desktop',async(event,action)=>{
    const fromOrb=event.sender===orb.webContents;
    const expected=pathToFileURL(fromOrb?orbPath:shellPath).href;
    if((!fromOrb&&event.sender!==main.webContents)||event.senderFrame!==event.sender.mainFrame
      ||event.senderFrame.url!==expected)throw Error('拒绝访问');
    if(action==='menu'){Menu.buildFromTemplate(menuItems()).popup({window:fromOrb?orb:main});return snapshot();}
    if(action==='drag-start'||action==='drag-end'){
      if(!fromOrb)throw Error('拒绝访问');
      if(action==='drag-start'){startDrag();return {moved:false};}
      return endDrag();
    }
    return actionFor(action);
  });
  for(const window of [main,orb]){
    window.webContents.setWindowOpenHandler(()=>({action:'deny'}));
    window.webContents.on('will-navigate',event=>event.preventDefault());
  }
  await Promise.all([main.loadFile(shellPath),orb.loadFile(orbPath)]);
  orb.showInactive();publish();
  Menu.setApplicationMenu(Menu.buildFromTemplate([
    {label:'赫逸招聘助手',submenu:[{label:'关于',click:()=>dialog.showMessageBox(main,{message:'赫逸招聘助手 '+app.getVersion(),detail:'独立本机浏览器 · Mac / Windows'})},
      {type:'separator'},{label:'退出',accelerator:process.platform==='darwin'?'Cmd+Q':'Alt+F4',click:()=>{quitting=true;app.quit();}}]},
    {label:'编辑',submenu:[{role:'undo'},{role:'redo'},{type:'separator'},{role:'cut'},{role:'copy'},{role:'paste'},{role:'selectAll'}]},
    {label:'窗口',submenu:[{label:'收起到悬浮球',accelerator:'CmdOrCtrl+W',click:hideBoss},
      {label:'打开 BOSS 页面',click:()=>perform('open')}]},
  ]));
  for(const window of [main,orb])window.setMenuBarVisibility(false);
  const icon=nativeImage.createFromPath(path.join(__dirname,'../assets/icon.png')).resize({width:20,height:20});
  if(!selfTest){
    tray=new Tray(icon);tray.setToolTip('赫逸招聘助手');
    tray.on('right-click',()=>tray.popUpContextMenu(Menu.buildFromTemplate(menuItems())));
    tray.on('click',()=>Menu.buildFromTemplate(menuItems()).popup());
  }
  powerMonitor.on('suspend',()=>{engine.pause('COMPUTER_SLEEP');});
  powerMonitor.on('resume',()=>{engine.pause('RECONNECT_REQUIRED');});
  app.on('activate',()=>orb.showInactive());
  if(selfTest)await require('./self-test.cjs').run({app,main,orb,platformSession,createBoss,engine,worker,bridge,session,egress,snapshot});
}
let closing=false;
app.on('before-quit',event=>{
  quitting=true;
  if(!closing&&engine){
    event.preventDefault();closing=true;
    if(drag)endDrag();
    Promise.allSettled([engine.close(),bridge?.close(),egress?.close(),positionSave]).finally(()=>app.quit());
  }
});
app.on('window-all-closed',()=>{if(quitting||selfTest)app.quit();});
launch().catch(async error=>{
  if(selfTest){
    console.error('DESKTOP_SELF_TEST_FAILED',error.stack);
    await Promise.allSettled([engine?.close(),bridge?.close(),egress?.close()]);
    app.exit(1);return;
  }
  await egress?.close();
  await app.whenReady();dialog.showErrorBox('启动未完成',
    error.message.startsWith('网卡')||error.message.startsWith('未找到可用的直连')?error.message:
    '本机运行环境未就绪，请保留数据目录并重新安装应用。');app.quit();
});
