/* HR only: fail closed when the local assistant is unavailable. */
(() => {
  const base='http://127.0.0.1:17861', storage='heyi.hr.desktop.connection.v1';
  let mode='', connecting=null;
  let saved=null;
  try{saved=JSON.parse(sessionStorage.getItem(storage)||'null');}catch{}
  const timeout=(options,ms)=>({...options,signal:options.signal||AbortSignal.timeout(ms)});
  async function data(response){
    const result=await response.json();
    if(!response.ok||result.success===false)throw Error(result.message||'本机连接未完成');
    return result.data;
  }
  async function transportMode(){
    if(!mode){
      const result=await data(await fetch('/api/v1/hr/desktop/transport',
        timeout({credentials:'same-origin',cache:'no-store'},8000)));
      mode=result.mode;
    }
    return mode;
  }
  async function connect(){
    if(connecting)return connecting;
    connecting=(async()=>{
      if(await transportMode()==='embedded'){
        await data(await fetch('/api/v1/hr/desktop/connect',timeout({method:'POST',credentials:'same-origin'},20000)));
        return;
      }
      const health=await data(await fetch(base+'/health',timeout({cache:'no-store',credentials:'omit'},4000)));
      if(health.app!=='heyi-recruiter'||health.protocol!==1)throw Error('请安装新版赫逸招聘助手');
      const issued=await data(await fetch('/api/v1/hr/desktop/pair',
        timeout({method:'POST',credentials:'same-origin'},8000)));
      saved=await data(await fetch(base+'/pair',timeout({method:'POST',credentials:'omit',
        headers:{'Content-Type':'application/json'},body:JSON.stringify({ticket:issued.ticket})},15000)));
      sessionStorage.setItem(storage,JSON.stringify(saved));
    })().catch(error=>{
      saved=null;sessionStorage.removeItem(storage);
      throw Error(error instanceof TypeError
        ?'请先打开赫逸招聘助手，并允许此网站连接本地网络，再点击连接。':error.message);
    }).finally(()=>{connecting=null;});
    return connecting;
  }
  async function request(path,options={}){
    if(!path.startsWith('/api/v1/hr/')||path.includes('..'))throw Error('不支持的招聘接口');
    if(await transportMode()==='embedded')return fetch(path,timeout({...options,credentials:'same-origin'},145000));
    if(!saved||saved.expiresAt<=Date.now()){
      throw Error('请点击“连接本机助手”；BOSS 请求将在你的电脑上执行。');
    }
    try{
      const response=await fetch(base+path,timeout({...options,credentials:'omit',cache:'no-store',
        headers:{...options.headers,Authorization:'Bearer '+saved.token}},145000));
      if(response.status===428){saved=null;sessionStorage.removeItem(storage);throw Error('连接已到期，请重新连接本机助手');}
      return response;
    }catch(error){
      if(error instanceof TypeError)throw Error('本机助手已断开，请打开应用后重新连接。服务器未代为执行。');
      throw error;
    }
  }
  async function api(path,options={}){
    const response=await request(path,options),result=await response.json();
    if(!response.ok||result.success===false)throw Error(result.message||'本机操作未完成');
    return result;
  }
  window.HeyiHrTransport={connect,request,api,reset(){
    saved=null;mode='';sessionStorage.removeItem(storage);
  }};
})();
