const {spawn}=require('node:child_process');
const {EventEmitter}=require('node:events');
const {Readable}=require('node:stream');

function directEnvironment(source=process.env){
  const result=Object.fromEntries(Object.entries(source).filter(([key])=>
    !/^(https?|all|ftp|no)_proxy$/i.test(key)));
  return {...result,NO_PROXY:'*',PYTHONUTF8:'1',PYTHONIOENCODING:'utf-8'};
}

class DirectEgress extends EventEmitter{
  constructor(executable){super();this.executable=executable;}
  async start(offline=false){
    const child=spawn(this.executable,[offline?'--direct-network-test':'--direct-network'],{
      stdio:['pipe','pipe','ignore'],windowsHide:true,env:directEnvironment(),
    });
    this.child=child;
    return new Promise((resolve,reject)=>{
      let output='',settled=false;
      const finish=(error,value)=>{
        if(settled)return;settled=true;clearTimeout(timer);
        if(error){child.kill();reject(error);}else resolve(value);
      };
      const timer=setTimeout(()=>finish(Error('未找到可用的直联网卡，请检查网络后重新启动助手')),20000);
      child.stdout.setEncoding('utf8');
      child.stdout.on('data',chunk=>{
        output+=chunk;
        if(output.length>16000)return finish(Error('网卡直连服务启动失败'));
        if(!output.includes('\n')||settled)return;
        try{
          const info=JSON.parse(output.split('\n')[0]);
          if(!Number.isInteger(info.port)||info.port<1||info.port>65535
            ||!/^[\w-]{32,100}$/.test(info.secret)||!info.adapter?.address
            ||!Number.isInteger(info.adapter.index))throw Error();
          this.info=info;finish(null,info);
        }catch{finish(Error('网卡直连服务启动失败'));}
      });
      child.on('error',()=>finish(Error('网卡直连服务启动失败')));
      child.on('close',()=>{
        finish(Error('网卡直连不可用，请检查有线或 Wi-Fi 网络'));
        this.emit('stopped');
      });
      child.stdin.on('error',()=>{});
    });
  }
  async configure(target){
    // Loopback relay only transports unchanged TLS bytes onto the selected NIC.
    // A single fixed endpoint, deliberately no DIRECT/system-proxy fallback.
    await target.setProxy({mode:'fixed_servers',proxyRules:`http=127.0.0.1:${this.info.port};https=127.0.0.1:${this.info.port}`,
      proxyBypassRules:'127.0.0.1;[::1];localhost'});
    await target.closeAllConnections();
  }
  authenticate(event,info,callback){
    if(info.isProxy&&info.host==='127.0.0.1'&&info.port===this.info?.port){
      event.preventDefault();callback('heyi',this.info.secret);return true;
    }
    return false;
  }
  async fetch(target,input,init={}){
    // Electron net.fetch cancels proxy-auth challenges for main-process
    // requests. ClientRequest exposes the login callback without sending our
    // loopback credentials to an origin server.
    const {net}=require('electron');
    const request=new Request(input,init);
    if(request.signal.aborted)throw request.signal.reason;
    const body=request.body?Buffer.from(await request.arrayBuffer()):null;
    const headers=Object.fromEntries(request.headers);
    delete headers['proxy-authorization'];
    delete headers['content-length'];
    return new Promise((resolve,reject)=>{
      const outgoing=net.request({
        url:request.url,method:request.method,headers,session:target,
        credentials:'include',redirect:request.redirect,bypassCustomProtocolHandlers:true,
      });
      const abort=()=>{outgoing.abort();reject(Error('请求已停止'));};
      request.signal.addEventListener('abort',abort,{once:true});
      outgoing.once('close',()=>request.signal.removeEventListener('abort',abort));
      outgoing.on('login',(info,callback)=>{
        if(!this.authenticate({preventDefault(){}},info,callback))callback();
      });
      outgoing.once('error',()=>reject(Error('网卡直连请求未完成，未自动重试')));
      outgoing.once('response',reply=>{
        const responseHeaders=new Headers();
        for(const [name,values] of Object.entries(reply.headers)){
          if(['content-encoding','content-length','transfer-encoding'].includes(name.toLowerCase()))continue;
          for(const value of Array.isArray(values)?values:[values])responseHeaders.append(name,value);
        }
        const empty=request.method==='HEAD'||[204,205,304].includes(reply.statusCode);
        resolve(new Response(empty?null:Readable.toWeb(reply),{status:reply.statusCode,headers:responseHeaders}));
        if(empty)reply.resume();
      });
      outgoing.end(body||undefined);
    });
  }
  close(){
    if(!this.child)return Promise.resolve();
    const child=this.child;
    if(child.exitCode!==null)return Promise.resolve();
    return new Promise(resolve=>{
      const timer=setTimeout(()=>child.kill(),2000);
      child.once('close',()=>{clearTimeout(timer);resolve();});
      child.stdin.end();
    });
  }
}
module.exports={DirectEgress,directEnvironment};
