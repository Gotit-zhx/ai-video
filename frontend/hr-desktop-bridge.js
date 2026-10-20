/* Local connection recovery never retries BOSS operations or starts login. */
(() => {
  const base='http://127.0.0.1:17861', storage='heyi.hr.desktop.connection.v1';
  let mode='',connecting=null,saved=null,epoch=0,active=false,timer=null,monitoring=false;
  let failures=0,repair=false,reachable=false,phase='idle',message='',lastState=null;
  let recoverySince=0,wasReady=false,versionError=null;
  const listeners=new Set();
  try{saved=JSON.parse(sessionStorage.getItem(storage)||'null');}catch{}
  const channel=typeof BroadcastChannel==='function'?new BroadcastChannel(storage):null;
  const timeout=(options,ms)=>({...options,signal:options.signal||AbortSignal.timeout(ms)});
  const valid=()=>saved&&saved.expiresAt>Date.now();
  function persist(){if(saved)sessionStorage.setItem(storage,JSON.stringify(saved));else sessionStorage.removeItem(storage);}
  function snapshot(){return {paired:mode==='embedded'||!!valid()&&reachable,phase,message,state:lastState};}
  function notify(next,text=''){
    if(next==='reconnecting'&&wasReady){
      if(!recoverySince)recoverySince=Date.now();
      if(Date.now()-recoverySince<15000)next='recovering';
    }else if(next==='connected'&&!lastState?.reconnecting)recoverySince=0;
    phase=next;message=text;for(const listener of listeners)listener(snapshot());
  }
  async function data(response){
    const result=await response.json();
    if(!response.ok||result.success===false){const error=Error(result.message||'本机连接未完成');error.status=response.status;error.code=result.code;throw error;}
    return result.data;
  }
  async function connectionFetch(path,options){
    try{return await fetch(path,options);}
    catch(error){
      if(!(error instanceof TypeError)&&!['TimeoutError','AbortError'].includes(error.name))throw error;
      const local=path.startsWith(base+'/');
      let code=local?'LOCAL_CONNECTION_UNAVAILABLE':'PLATFORM_NETWORK_UNAVAILABLE';
      let text=local
        ?'网页无法连接本机助手。请确认网页与助手在同一台电脑，并在浏览器的网站设置中允许本地网络访问；仍失败时检查安全软件是否拦截助手。'
        :'平台请求未完成，请检查本机能否打开本网站后重试。';
      if(local){
        for(const name of ['loopback-network','local-network-access']){
          try{
            const permission=await globalThis.navigator?.permissions?.query({name});
            if(permission?.state==='denied'){
              code='LOCAL_NETWORK_PERMISSION_DENIED';
              text='浏览器已禁止此网站访问本机助手。请打开地址栏左侧的网站设置，将本地网络访问设为允许，再点击“检查连接”。';
              break;
            }
          }catch{/* Unsupported permission names provide no evidence of denial. */}
        }
        if(code!=='LOCAL_NETWORK_PERMISSION_DENIED'&&['TimeoutError','AbortError'].includes(error.name)){
          code='LOCAL_CONNECTION_TIMEOUT';
          text='连接本机助手超时。若浏览器弹出本地网络访问提示，请先选择允许，再点击“检查连接”；无需退出 BOSS 登录。';
        }
      }
      throw Object.assign(Error(text+' ['+code+']'),{code});
    }
  }
  async function transportMode(){
    if(!mode){
      const ticket=epoch;
      const result=await data(await connectionFetch('/api/v1/hr/desktop/transport',timeout({credentials:'same-origin',cache:'no-store'},8000)));
      if(ticket!==epoch)throw Error('连接已取消');
      mode=result.mode;
    }
    return mode;
  }
  async function health({interactive=false}={}){
    const value=await data(await connectionFetch(base+'/health',timeout({cache:'no-store',credentials:'omit'},interactive?30000:4000)));
    if(value.app!=='heyi-recruiter'||value.protocol!==1)throw Error('请安装新版赫逸招聘助手');
    const parts=typeof value.version==='string'&&/^\d+\.\d+\.\d+$/.test(value.version)?value.version.split('.').map(Number):[];
    const isWindows=/Win/i.test(navigator.userAgentData?.platform||navigator.platform||navigator.userAgent||'');
    const minimum=isWindows?[0,1,27]:[0,1,25],different=parts.findIndex((part,index)=>part!==minimum[index]);
    const recent=parts.length===3&&(different===-1||parts[different]>minimum[different]);
    if(!recent){
      versionError=Object.assign(Error('当前运行的是旧版助手'+(parts.length?' '+value.version:'')
        +'，请完全退出旧助手，再从“下载助手”覆盖安装 '+minimum.join('.')+' 或更新版本，以使用启动及跨设备同步修复。无需退出 BOSS 登录。'),{code:'ASSISTANT_UPDATE_REQUIRED'});
      throw versionError;
    }
    versionError=null;
    return value;
  }
  async function diagnose(){
    const ticket=epoch;let stage='platform';
    try{
      if(await transportMode()==='embedded')return {ok:true,stage,message:'已连接助手内的平台工作台。'};
      stage='local';
      const value=await health({interactive:true});
      if(ticket!==epoch)return {ok:false,stage,message:'连接检查已取消。'};
      const version=typeof value.version==='string'&&/^\d+\.\d+\.\d+$/.test(value.version)?value.version:'';
      return {ok:true,stage,version,message:'网页可以连接本机助手'+(version?'（'+version+'）':'')+'。如尚未绑定，请点击“连接本机助手”。'};
    }catch(error){
      return {ok:false,stage,code:error.code,message:error.message};
    }
  }
  async function connect({automatic=false}={}){
    if(connecting)return connecting;
    const ticket=epoch;
    const pending=(async()=>{
      if(await transportMode()==='embedded'){
        await data(await fetch('/api/v1/hr/desktop/connect',timeout({method:'POST',credentials:'same-origin'},20000)));return;
      }
      await health({interactive:!automatic});
      if(ticket!==epoch)throw Error('连接已取消');
      const issued=await data(await connectionFetch('/api/v1/hr/desktop/pair',timeout({method:'POST',credentials:'same-origin',
        headers:automatic?{'X-Heyi-Auto-Connect':'1'}:{}},8000)));
      if(ticket!==epoch)throw Error('连接已取消');
      const paired=await data(await connectionFetch(base+'/pair',timeout({method:'POST',credentials:'omit',
        headers:{'Content-Type':'application/json'},body:JSON.stringify({ticket:issued.ticket})},25000)));
      if(ticket!==epoch)throw Error('连接已取消');
      saved=paired;persist();reachable=true;repair=false;failures=0;
      channel?.postMessage({type:'paired',value:paired});notify('connected');
    })().catch(error=>{
      if(ticket===epoch){
        reachable=false;
        if(error.code==='ASSISTANT_UPDATE_REQUIRED'){
          active=false;clearTimeout(timer);timer=null;notify('update-required',error.message);
        }else notify('reconnecting',error instanceof TypeError
          ?'正在等待本机助手，请打开应用并允许此网站连接本地网络。':error.message);
      }
      throw error;
    }).finally(()=>{if(connecting===pending)connecting=null;});
    connecting=pending;return pending;
  }
  function schedule(ms){
    clearTimeout(timer);timer=null;
    if(active)timer=setTimeout(tick,ms);
  }
  async function tick(){
    if(!active)return;
    if(monitoring){schedule(1000);return;}
    monitoring=true;const ticket=epoch;
    try{
      if(await transportMode()==='embedded')return;
      // Do not initiate first-time local permission from a hidden tab. Once
      // paired, keep its lease alive even while the HR page is in background.
      if(globalThis.document?.visibilityState==='hidden'&&!saved)return;
      const inspect=async()=>{
        if(!active||ticket!==epoch)return;
        await health();
        if(!active||ticket!==epoch)return;
        if(!valid()||saved.expiresAt-Date.now()<120000||repair)await connect({automatic:true});
        if(!active||ticket!==epoch)return;
        // This endpoint returns cached local state. It does not inspect or call BOSS.
        const response=await connectionFetch(base+'/api/v1/hr/login/status',timeout({credentials:'omit',cache:'no-store',
          headers:{Authorization:'Bearer '+saved.token}},12000));
        if(response.status===428||response.status===409)repair=true;
        const state=await data(response);
        if(ticket!==epoch||!active)return;
        lastState=state;reachable=true;failures=0;
        if(state.state==='complete'&&!state.paused)wasReady=true;
        if(state.state==='disconnected'||state.paused&&!state.reconnecting)wasReady=false;
        notify(state.reconnecting?'reconnecting':'connected');
      };
      if(globalThis.navigator?.locks)await navigator.locks.request('heyi-hr-pairing',{ifAvailable:true},lock=>lock?inspect():undefined);
      else await inspect();
    }catch(error){
      if(ticket!==epoch||!active)return;
      failures++;reachable=false;
      if(error.code==='ASSISTANT_UPDATE_REQUIRED'){active=false;notify('update-required',error.message);}
      else if(error.code==='DEVICE_IN_USE'||error.message?.includes('另一台电脑正在使用助手')){active=false;notify('device-in-use',error.message);}
      else if(error.status===401||error.status===403){active=false;notify('unauthorized','平台登录已失效，请重新登录');}
      else notify('reconnecting',error instanceof TypeError?'助手暂时离线，正在自动重连':error.message);
    }finally{
      monitoring=false;
      if(ticket===epoch&&active)schedule(failures?Math.min(60000,2000*2**Math.min(failures-1,5))*(.85+Math.random()*.3)
        :lastState?.reconnecting?2500+Math.random()*1500
        :globalThis.document?.visibilityState==='hidden'?60000+Math.random()*30000:25000+Math.random()*10000);
    }
  }
  async function request(path,options={}){
    if(!path.startsWith('/api/v1/hr/')||path.includes('..'))throw Error('不支持的招聘接口');
    if(await transportMode()==='embedded')return fetch(path,timeout({...options,credentials:'same-origin'},145000));
    if(versionError)throw versionError;
    if(!valid()){
      if(!active)throw Error('请点击“连接本机助手”；BOSS 请求将在你的电脑上执行。');
      await connect({automatic:true});
    }
    const ticket=epoch;
    try{
      const response=await fetch(base+path,timeout({...options,credentials:'omit',cache:'no-store',
        headers:{...options.headers,Authorization:'Bearer '+saved.token}},145000));
      if(response.status===428){
        if(ticket===epoch){repair=true;reachable=false;schedule(0);}
        throw Error('助手连接正在恢复，本次操作未自动重复，请连接恢复后再操作');
      }
      return response;
    }catch(error){
      if(ticket===epoch&&(error instanceof TypeError||error.name==='TimeoutError')){
        reachable=false;notify('reconnecting','助手连接暂时中断，正在自动检查');schedule(2000);
        throw Error('本机助手连接中断，正在自动重连；本次操作未自动重放，服务器未代为执行。');
      }
      throw error;
    }
  }
  async function api(path,options={}){
    const response=await request(path,options),result=await response.json();
    if(!response.ok||result.success===false){
      const error=Error(result.message||'本机操作未完成');error.status=response.status;error.code=result.code;throw error;
    }
    return result;
  }
  function stopAutoConnect(){active=false;epoch++;clearTimeout(timer);timer=null;}
  function reset(){stopAutoConnect();saved=null;mode='';repair=false;reachable=false;lastState=null;versionError=null;
    recoverySince=0;wasReady=false;persist();notify('idle');}
  async function disconnect({ensureConnection=false}={}){
    stopAutoConnect();
    // Retain cleanup capability through failed cleanup and expired leases.
    if(connecting)await connecting.catch(()=>{});
    if(!saved&&mode!=='embedded'){
      if(!ensureConnection)return {cleared:false,reason:'not-paired'};
      await connect();
    }
    const response=mode==='embedded'
      ?await fetch('/api/v1/hr/login/logout',timeout({method:'POST',credentials:'same-origin'},25000))
      :await fetch(base+'/api/v1/hr/login/logout',timeout({method:'POST',credentials:'omit',headers:{Authorization:'Bearer '+saved.token}},25000));
    const result=await data(response);
    if(result?.state!=='disconnected'||result?.cleared!==true)throw Error('助手没有确认完整清理，请更新桌面助手后重试');
    channel?.postMessage({type:'disconnected'});reset();return {cleared:true};
  }
  function startAutoConnect(){if(active)return;active=true;failures=0;notify('reconnecting','正在检查本机助手');schedule(0);}
  const wake=()=>{if(active)schedule(0);};
  globalThis.addEventListener?.('online',wake);
  globalThis.addEventListener?.('focus',wake);
  globalThis.document?.addEventListener('visibilitychange',wake);
  if(channel)channel.onmessage=({data:value})=>{
    if(value?.type==='disconnected'){reset();return;}
    if(active&&value?.type==='paired'&&typeof value.value?.token==='string'&&value.value.expiresAt>Date.now()){
      saved=value.value;persist();reachable=true;repair=false;schedule(0);
    }
  };
  window.HeyiHrTransport={connect,diagnose,request,api,disconnect,startAutoConnect,stopAutoConnect,
    subscribe(listener){listeners.add(listener);return ()=>listeners.delete(listener);},connection:snapshot,reset};
})();
