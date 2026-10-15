const MAX_BYTES=20*1024*1024;
const ORIGIN='https://www.zhipin.com';
const failure=(code,message)=>Object.assign(Error(message),{code});

function encodedForm(data) {
  if(data==null)return '';
  if(typeof data!=='object'||Array.isArray(data))throw failure('INVALID_ACTION','接口参数不正确');
  const form=new URLSearchParams();
  for(const [key,value] of Object.entries(data)){
    for(const entry of Array.isArray(value)?value:[value]){
      form.append(key,entry==null?'':typeof entry==='object'?JSON.stringify(entry):String(entry));
    }
  }
  const body=form.toString();
  if(Buffer.byteLength(body)>65536)throw failure('INVALID_ACTION','接口参数过长');
  return body;
}

function nativeHeaders(auth) {
  const cookie=auth?.cookieHeader || (auth?.token?'wt2='+auth.token:'');
  if(typeof cookie!=='string'||cookie.length>16384||!/^[\x20-\x7e]+$/.test(cookie)){
    throw failure('AUTH_REQUIRED','原客户端登录态尚未建立');
  }
  const tokens=cookie.split(';').map(part=>part.trim()).filter(part=>part.startsWith('wt2='));
  if(tokens.length!==1||!/^wt2=[^;\s]+$/.test(tokens[0])){
    throw failure('AUTH_REQUIRED','原客户端登录态尚未核对');
  }
  if(auth.token&&tokens[0]!=='wt2='+auth.token){
    throw failure('SESSION_MISMATCH','原客户端登录态不一致');
  }
  if(typeof auth.userAgent!=='string'||auth.userAgent.length>4096||!/^[\x20-\x7e]+$/.test(auth.userAgent)){
    throw failure('AUTH_REQUIRED','原客户端请求标识尚未建立');
  }
  const headers={Cookie:cookie,'User-Agent':auth.userAgent,
    'X-Requested-With':'XMLHttpRequest',Accept:'application/json'};
  for(const [field,key] of [['zpToken','zp_token'],['traceId','Traceid']]){
    if(auth[field]==null||auth[field]==='')continue;
    if(typeof auth[field]!=='string'||auth[field].length>8192||!/^[\x20-\x7e]+$/.test(auth[field])){
      throw failure('AUTH_REQUIRED','原客户端请求标识尚未核对');
    }
    headers[key]=auth[field];
  }
  return headers;
}

// One guarded Chromium request. Account/session checks and the existing SDK
// budget surround this transport; it never retries or follows redirects.
function nativeJSONRequest({net,session,url:input,method='GET',data,endpoints,
  credentials,checkAccount,authenticate,signal,timeout=65000,maxBytes=MAX_BYTES}) {
  let url,form,verb;
  try {
    url=new URL(input,ORIGIN);
    verb=String(method).toUpperCase();
    if(url.origin!==ORIGIN||url.username||url.password||url.hash||
      !endpoints?.has(url.pathname)||!url.pathname.startsWith('/wapi/')||
      !['GET','POST'].includes(verb))throw failure('UNSUPPORTED_OPERATION','不支持的客户端接口');
    if(!Number.isInteger(timeout)||timeout<1||timeout>75000||
      !Number.isInteger(maxBytes)||maxBytes<1||maxBytes>MAX_BYTES){
      throw failure('INVALID_ACTION','接口读取限制不正确');
    }
    form=encodedForm(data);
    if(verb==='GET'&&form)url.search+=(url.search?'&':'')+form;
    checkAccount();
  } catch(error){return Promise.reject(error);}
  return new Promise((resolve,reject)=>{
    let request,settled=false;
    const abort=()=>{try {request?.abort();}catch {}};
    const finish=(error,value)=>{
      if(settled)return;
      settled=true;clearTimeout(timer);signal?.removeEventListener('abort',cancel);
      if(error){abort();reject(error);}else resolve(value);
    };
    const cancel=()=>finish(failure('REQUESTS_PAUSED','接口读取已取消'));
    const timer=setTimeout(()=>finish(failure('NATIVE_HTTP_TIMEOUT','原客户端接口读取超时')),timeout);
    const check=()=>{
      if(settled)return false;
      try {checkAccount();return true;}catch(error){finish(error);return false;}
    };
    signal?.addEventListener('abort',cancel,{once:true});
    if(signal?.aborted){cancel();return;}
    Promise.resolve().then(()=>credentials()).then(auth=>{
      if(!check())return;
      const headers=nativeHeaders(auth);
      request=net.request({url:url.href,method:verb,session,redirect:'manual',useSessionCookies:false});
      request.on('error',()=>finish(failure('NATIVE_HTTP_FAILED','原客户端接口读取未完成')));
      request.on('abort',()=>finish(failure('NATIVE_HTTP_FAILED','原客户端接口读取已中断')));
      request.on('login',(info,callback)=>{
        let called=false;
        const reply=(...args)=>{if(!called){called=true;callback(...args);}};
        try {
          if(!check()){reply();return;}
          if(authenticate?.(info,reply)!==true)reply();
        }catch {
          finish(failure('NATIVE_HTTP_FAILED','原客户端网络认证未完成'));
        }
      });
      request.on('redirect',status=>{
        if(!check())return;
        if(!Number.isInteger(status)||status<300||status>399){
          finish(failure('INVALID_NATIVE_RESPONSE','原客户端返回状态不正确'));return;
        }
        finish(null,{heyiNativeHttp:1,statusCode:status,body:null});
        abort();
      });
      request.on('response',response=>{
        if(!check())return;
        const status=response.statusCode;
        if(!Number.isInteger(status)||status<200||status>599){
          finish(failure('INVALID_NATIVE_RESPONSE','原客户端返回状态不正确'));return;
        }
        const header=name=>{
          const key=Object.keys(response.headers||{}).find(key=>key.toLowerCase()===name);
          const value=key?response.headers[key]:undefined;
          return Array.isArray(value)?value[0]:value;
        };
        const length=header('content-length');
        if(length!==undefined&&(typeof length!=='string'||!/^\d+$/.test(length))){
          finish(failure('INVALID_NATIVE_RESPONSE','原客户端返回长度不正确'));return;
        }
        const declared=length===undefined?NaN:Number(length);
        if(declared>maxBytes){finish(failure('NATIVE_RESPONSE_TOO_LARGE','接口返回内容过大'));return;}
        const chunks=[];let size=0;
        response.on('data',chunk=>{
          if(!check())return;
          size+=chunk.length;
          if(size>maxBytes){finish(failure('NATIVE_RESPONSE_TOO_LARGE','接口返回内容过大'));return;}
          chunks.push(Buffer.from(chunk));
        });
        response.on('error',()=>finish(failure('NATIVE_HTTP_FAILED','原客户端接口响应中断')));
        response.on('aborted',()=>finish(failure('NATIVE_HTTP_FAILED','原客户端接口响应中断')));
        response.on('close',()=>{if(!settled)finish(failure('NATIVE_HTTP_FAILED','原客户端接口响应不完整'));});
        response.on('end',()=>{
          if(!check())return;
          if(Number.isFinite(declared)&&declared>=0&&!header('content-encoding')&&declared!==size){
            finish(failure('NATIVE_HTTP_FAILED','原客户端接口响应不完整'));return;
          }
          const text=Buffer.concat(chunks).toString('utf8');
          let body=text||null;
          try {if(text)body=JSON.parse(text);}catch {}
          finish(null,{heyiNativeHttp:1,statusCode:status,body});
        });
      });
      for(const [name,value] of Object.entries(headers))request.setHeader(name,value);
      if(verb==='POST')request.setHeader('Content-Type','application/x-www-form-urlencoded;charset=UTF-8');
      if(!check())return;
      if(verb==='POST')request.end(form);else request.end();
    }).catch(error=>{
      if(['AUTH_REQUIRED','SESSION_MISMATCH'].includes(error.code))finish(error);
      else finish(failure('NATIVE_HTTP_FAILED','原客户端接口准备未完成'));
    });
  });
}

module.exports={nativeJSONRequest,nativeHeaders,encodedForm};
