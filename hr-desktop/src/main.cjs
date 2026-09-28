const {app,BrowserWindow,WebContentsView,session,ipcMain,Menu,Tray,nativeImage,powerMonitor,dialog}=require('electron');
const fs=require('node:fs/promises');
const path=require('node:path');
const crypto=require('node:crypto');
const {ORIGIN,Runtime,Recruiter}=require('./core.cjs');
const {LocalBridge}=require('./bridge.cjs');

const selfTest=process.argv.includes('--self-test');
if(process.env.HEYI_TEST_USER_DATA)app.setPath('userData',process.env.HEYI_TEST_USER_DATA);
app.commandLine.appendSwitch('remote-debugging-address','127.0.0.1');
app.commandLine.appendSwitch('remote-debugging-port','0');
let main,platformView,bossView,worker,engine,bridge,active='home',tray,quitting=false;
let device='',lease=null,leaseChecked=0,leaseCheck=null;
const viewOptions={nodeIntegration:false,contextIsolation:true,sandbox:true,webSecurity:true};
const shellPath=path.join(__dirname,'index.html');

function show(view){
  active=view;
  for(const candidate of [platformView,bossView]){
    if(candidate && main.contentView.children.includes(candidate))main.contentView.removeChildView(candidate);
  }
  const target=view==='platform'?platformView:view==='boss'?bossView:null;
  if(target){main.contentView.addChildView(target);resize();}
  main.webContents.send('state',{...engine?.snapshot(),active});
}
function resize(){
  const {width,height}=main.getContentBounds();
  for(const view of [platformView,bossView])view?.setBounds({x:0,y:76,width,height:Math.max(1,height-106)});
}
function browserBoundary(contents,host){
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
  const reply=await platformView.webContents.session.fetch(ORIGIN+'/api/v1/hr/desktop/'+route,{
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
async function pairEmbedded(){
  const issued=await cloud('pair');
  await pair(issued.ticket);
  return {success:true,data:{connected:true}};
}
async function launch(){
  if(!app.requestSingleInstanceLock()){app.quit();return;}
  app.on('second-instance',()=>{main?.show();main?.focus();});
  await app.whenReady();
  await fs.mkdir(app.getPath('userData'),{recursive:true,mode:0o700});
  const deviceFile=path.join(app.getPath('userData'),'device.json');
  try{device=JSON.parse(await fs.readFile(deviceFile,'utf8')).id;}catch{}
  if(!/^[a-f0-9-]{36}$/.test(device)){
    device=crypto.randomUUID();await fs.writeFile(deviceFile,JSON.stringify({id:device}),{mode:0o600});
  }
  main=new BrowserWindow({width:1360,height:900,minWidth:1020,minHeight:700,title:'赫逸招聘助手',
    backgroundColor:'#f7f7f5',show:!selfTest,
    webPreferences:{...viewOptions,preload:path.join(__dirname,'preload.cjs')}});
  main.on('resize',resize);
  main.on('close',event=>{if(!quitting&&!selfTest){event.preventDefault();main.hide();}});
  const platformSession=session.fromPartition('persist:hoyee-platform');
  platformView=new WebContentsView({webPreferences:{...viewOptions,session:platformSession}});
  browserBoundary(platformView.webContents,'hoyee.org.cn');
  if(selfTest){
    // No external traffic, even if a smoke fixture accidentally navigates.
    for(const target of [platformView.webContents.session,session.defaultSession]){
      target.webRequest.onBeforeRequest((details,done)=>{
        const url=new URL(details.url);
        const intercepted=target===platformView.webContents.session&&url.origin===ORIGIN;
        done({cancel:['http:','https:','ws:','wss:'].includes(url.protocol)&&url.hostname!=='127.0.0.1'&&!intercepted});
      });
    }
  }
  worker=new Runtime(executable());
  engine=new Recruiter({
    directory:path.join(app.getPath('userData'),'recruiter'),
    runtime:worker,authorize:authorized,
    browser:{
      open:async()=>{
        const view=createBoss();main.show();show('boss');
        const current=view.webContents.getURL();
        if(!current||current==='about:blank')await view.webContents.loadURL('https://www.zhipin.com/web/user/');
      },
      inspect:inspectBoss,qr,
      refresh:async()=>{createBoss().webContents.reload();},
      connected:()=>{show('platform');},
      clear:async()=>{
        if(bossView){if(active==='boss')show('home');bossView.webContents.close();bossView=null;}
        // Electron profile is exclusive to this app. Never call remote logout.
        // The default session is dedicated to BOSS; the Hoyee session is separate.
        await session.defaultSession.clearStorageData();
        await session.defaultSession.clearCache();
      },
    }
  });
  await engine.init(await localEndpoint());
  bridge=new LocalBridge({engine,pair,authorize:authorized});
  await bridge.start(selfTest?0:17861);
  engine.on('change',state=>{
    if(!main.isDestroyed())main.webContents.send('state',{...state,active});
  });
  // Only this embedded workspace routes HR operations to the local runtime.
  // Ordinary browser visits and the production server are not modified.
  platformSession.protocol.handle('https',async request=>{
    try{
      const url=new URL(request.url);
      if(url.origin===ORIGIN&&url.pathname==='/api/v1/hr/desktop/transport'){
        return Response.json({success:true,data:{mode:'embedded'}});
      }
      if(url.origin===ORIGIN&&url.pathname==='/api/v1/hr/desktop/connect'&&request.method==='POST'){
        return Response.json(await pairEmbedded());
      }
      const local=await engine.route(request);
      if(local)return local;
      if(selfTest)return new Response('offline test',{headers:{'Content-Type':'text/plain'}});
      return platformSession.fetch(request,{bypassCustomProtocolHandlers:true});
    }catch{return new Response('请求未完成',{status:503});}
  });
  ipcMain.handle('desktop',async(event,action)=>{
    if(event.sender!==main.webContents||!event.senderFrame?.url.startsWith('file://'))throw Error('拒绝访问');
    if(action==='state')return {...engine.snapshot(),active,version:app.getVersion(),platform:process.platform};
    if(action==='platform'){
      show('platform');
      if(!platformView.webContents.getURL())await platformView.webContents.loadURL(ORIGIN+'/?lang=zh#hr-overview');
    }else if(action==='boss'){
      if(bossView)show('boss');else await engine.start();
    }else if(action==='home')show('home');
    else if(action==='pause'){engine.pause('USER_PAUSED');}
    else if(action==='connect'){await engine.start();}
    else if(action==='quit'){quitting=true;app.quit();}
    else if(action==='release'){
      engine.pause();
      if(engine.busy)throw Error('当前任务正在执行，请先暂停并等待结束');
      if(bossView){show('home');bossView.webContents.close();bossView=null;}
      engine.state={...engine.state,state:'verification_pending',failure_code:'MANUAL_REVIEW_REQUIRED',
        error:'本机浏览器资源已释放，登录资料已保留。需要时请手动连接。'};
      engine.event('BROWSER_RELEASED');
    }else if(action!=='state')throw Error('不支持的应用操作');
    return {...engine.snapshot(),active};
  });
  main.webContents.setWindowOpenHandler(()=>({action:'deny'}));
  main.webContents.on('will-navigate',event=>event.preventDefault());
  await main.loadFile(shellPath);
  Menu.setApplicationMenu(Menu.buildFromTemplate([
    {label:'赫逸招聘助手',submenu:[{label:'关于',click:()=>dialog.showMessageBox(main,{message:'赫逸招聘助手 '+app.getVersion(),detail:'独立本机浏览器 · Mac / Windows'})},
      {type:'separator'},{label:'退出',accelerator:process.platform==='darwin'?'Cmd+Q':'Alt+F4',click:()=>{quitting=true;app.quit();}}]},
    {label:'编辑',submenu:[{role:'undo'},{role:'redo'},{type:'separator'},{role:'cut'},{role:'copy'},{role:'paste'},{role:'selectAll'}]},
    {label:'窗口',submenu:[{role:'minimize'},{label:'显示主窗口',click:()=>main.show()}]},
  ]));
  const icon=nativeImage.createFromDataURL('data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAGUlEQVR42mNkYGD4z0ABYBw1YNSAUQNGDQAAHCAEHXifvoEAAAAASUVORK5CYII=');
  if(!selfTest){
    tray=new Tray(icon);tray.setToolTip('赫逸招聘助手');
    tray.setContextMenu(Menu.buildFromTemplate([
      {label:'打开工作台',click:()=>{main.show();show('home');}},
      {label:'暂停本机任务',click:()=>{engine.pause('USER_PAUSED');}},
      {type:'separator'},{label:'退出',click:()=>{quitting=true;app.quit();}}
    ]));
    tray.on('click',()=>main.show());
  }
  powerMonitor.on('suspend',()=>{engine.pause('COMPUTER_SLEEP');});
  powerMonitor.on('resume',()=>{engine.pause('RECONNECT_REQUIRED');});
  app.on('activate',()=>main.show());
  if(selfTest)await require('./self-test.cjs').run({app,main,platformView,createBoss,engine,worker,bridge,show,session});
}
let closing=false;
app.on('before-quit',event=>{
  quitting=true;
  if(!closing&&engine){
    event.preventDefault();closing=true;
    Promise.all([engine.close(),bridge?.close()]).finally(()=>app.quit());
  }
});
app.on('window-all-closed',()=>{if(quitting||selfTest)app.quit();});
launch().catch(async error=>{
  if(selfTest){console.error('DESKTOP_SELF_TEST_FAILED',error.stack);app.exit(1);return;}
  await app.whenReady();dialog.showErrorBox('启动未完成','本机运行环境未就绪，请保留数据目录并重新安装应用。');app.quit();
});
