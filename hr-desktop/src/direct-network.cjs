const {spawn}=require('node:child_process');
const {EventEmitter}=require('node:events');
const {ReadableStream}=require('node:stream/web');

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
      let output='',settled=false,timeoutCheck;
      const finish=(error,value)=>{
        if(settled)return;settled=true;clearTimeout(timer);clearImmediate(timeoutCheck);
        if(error){child.kill();reject(error);}else resolve(value);
      };
      // OS encryption can block Electron's main thread while this child has
      // already written its handshake. Let the pending pipe read run before
      // declaring a timeout; an actually silent child still fails this turn.
      const timer=setTimeout(()=>{
        timeoutCheck=setImmediate(()=>finish(Error('未找到可用的直联网卡，请检查网络后重新启动助手')));
      },20000);
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
      let bodyController,finished=false;
      const outgoing=net.request({
        url:request.url,method:request.method,session:target,
        credentials:'include',redirect:request.redirect,bypassCustomProtocolHandlers:true,
      });
      const cleanup=()=>{
        request.signal.removeEventListener('abort',abort);
      };
      const fail=error=>{
        if(finished)return;
        finished=true;cleanup();
        bodyController?.error(error);
        reject(error);
      };
      const abort=()=>{
        fail(Error('请求已停止'));
        outgoing.abort();
      };
      request.signal.addEventListener('abort',abort,{once:true});
      // Electron 22's Writable can emit "close" as soon as the upload finishes,
      // before Chromium even starts the request. Only response completion or
      // an explicit failure can release cancellation.
      outgoing.once('abort',()=>fail(Error('网卡直连请求已中断')));
      outgoing.on('login',(info,callback)=>{
        if(!this.authenticate({preventDefault(){}},info,callback))callback();
      });
      outgoing.once('error',()=>fail(Error('网卡直连请求未完成，未自动重试')));
      outgoing.once('response',reply=>{
        reply.once('error',()=>fail(Error('网卡直连响应未完成，未自动重试')));
        reply.once('aborted',()=>fail(Error('网卡直连响应已中断')));
        reply.once('close',()=>{
          if(!finished)fail(Error('网卡直连响应未完整接收'));
        });
        reply.once('end',()=>{
          if(finished)return;
          finished=true;cleanup();bodyController?.close();
        });
        try{
          const responseHeaders=new Headers();
          for(const [name,values] of Object.entries(reply.headers)){
            if(['content-encoding','content-length','transfer-encoding'].includes(name.toLowerCase()))continue;
            for(const value of Array.isArray(values)?values:[values])responseHeaders.append(name,value);
          }
          const empty=request.method==='HEAD'||[204,205,304].includes(reply.statusCode);
          // Electron 22 uses Node 16, which has stream/web but no
          // Readable.toWeb. Adapt the actual response without assuming its
          // IncomingMessage is a Node Readable or supports async iteration.
          const body=empty?null:new ReadableStream({
            start(controller){
              bodyController=controller;
              reply.on('data',chunk=>{
                if(finished)return;
                controller.enqueue(new Uint8Array(chunk));
                if(controller.desiredSize<=0)reply.pause();
              });
            },
            pull(){if(!finished)reply.resume();},
            cancel(){
              if(finished)return;
              finished=true;cleanup();outgoing.abort();
            },
          });
          resolve(new Response(body,{status:reply.statusCode,headers:responseHeaders}));
          if(empty)reply.resume();
        }catch(error){fail(error);outgoing.abort();}
      });
      try{
        // net.request in the original runtime ignores an options.headers
        // object. Set headers through its actual ClientRequest API.
        for(const [name,value] of Object.entries(headers))outgoing.setHeader(name,value);
        if(request.signal.aborted)abort();
        else outgoing.end(body||undefined);
      }catch(error){fail(error);outgoing.abort();}
    });
  }
  close(){
    if(!this.child)return Promise.resolve();
    const child=this.child;
    // A process terminated by a signal has a null exitCode even after its
    // close event. Waiting for that event again would hang startup cleanup.
    if(child.exitCode!==null||child.signalCode!==null)return Promise.resolve();
    return new Promise(resolve=>{
      const timer=setTimeout(()=>child.kill(),2000);
      child.once('close',()=>{clearTimeout(timer);resolve();});
      child.stdin.end();
    });
  }
}
module.exports={DirectEgress,directEnvironment};
