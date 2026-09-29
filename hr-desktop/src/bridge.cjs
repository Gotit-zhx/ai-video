const http=require('node:http');
const crypto=require('node:crypto');
const {ORIGIN}=require('./core.cjs');
const PORT=17861;

class LocalBridge {
  constructor({engine,pair,authorize,release=async()=>{}}) {
    Object.assign(this,{engine,pair,authorize,release});
    this.token='';this.expiry=0;this.pairing=false;
  }
  async start(port=PORT){
    this.server=http.createServer((req,res)=>this.handle(req,res));
    this.server.requestTimeout=150000;this.server.headersTimeout=10000;
    await new Promise((resolve,reject)=>{this.server.once('error',reject);this.server.listen(port,'127.0.0.1',resolve);});
    this.port=this.server.address().port;return this.port;
  }
  async handle(req,res){
    const origin=req.headers.origin;
    const send=(status,body)=>{res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'});res.end(JSON.stringify(body));};
    if(req.headers.host!==`127.0.0.1:${this.port}`||origin!==ORIGIN){send(403,{success:false,message:'不允许的来源'});return;}
    res.setHeader('Access-Control-Allow-Origin',ORIGIN);
    res.setHeader('Vary','Origin');
    res.setHeader('Access-Control-Allow-Methods','GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers','Content-Type, Authorization');
    res.setHeader('Access-Control-Allow-Private-Network','true');
    res.setHeader('Access-Control-Expose-Headers','Content-Disposition');
    if(req.method==='OPTIONS'){res.writeHead(204);res.end();return;}
    try{
      if(req.url==='/health'&&req.method==='GET'){
        send(200,{success:true,data:{app:'heyi-recruiter',version:require('../package.json').version,protocol:1}});return;
      }
      let raw='';
      for await(const chunk of req){raw+=chunk;if(Buffer.byteLength(raw)>8192)throw Error('请求过长');}
      if(req.url==='/pair'&&req.method==='POST'){
        if(this.pairing||this.engine.busy||this.engine.disconnecting)throw Error('助手正在处理另一项操作，请稍后再连接');
        this.pairing=true;
        try{
          const value=JSON.parse(raw);
          if(typeof value.ticket!=='string'||!value.ticket.match(/^[\w-]{43}$/))throw Error('连接凭证格式不正确');
          const paired=await this.pair(value.ticket);
          await this.engine.checkOwner();
          this.token=crypto.randomBytes(32).toString('base64url');
          this.expiry=paired.expiresAt*1000;
          send(200,{success:true,data:{token:this.token,expiresAt:this.expiry}});return;
        }finally{this.pairing=false;}
      }
      const expected=Buffer.from('Bearer '+this.token),actual=Buffer.from(req.headers.authorization||'');
      const logout=req.method==='POST'&&req.url==='/api/v1/hr/login/logout';
      if(!this.token||(!logout&&Date.now()>=this.expiry)||actual.length!==expected.length||!crypto.timingSafeEqual(actual,expected)){
        send(428,{success:false,message:'请在网页连接本机助手'});return;
      }
      if(logout){
        await this.engine.disconnect();
        this.token='';this.expiry=0;
        await this.release();
        send(200,{success:true,data:{state:'disconnected',cleared:true}});return;
      }
      if(!/^\/api\/v1\/hr\/(?!desktop\/)[a-z0-9/?=&_%~-]+$/i.test(req.url))throw Error('不支持的本机接口');
      await this.authorize();
      const reply=await this.engine.route(new Request(ORIGIN+req.url,{
        method:req.method,headers:{'Content-Type':'application/json'},
        ...(req.method==='POST'?{body:raw||undefined}:{}),
      }));
      if(!reply)throw Error('不支持的本机接口');
      res.writeHead(reply.status,Object.fromEntries(reply.headers));
      res.end(Buffer.from(await reply.arrayBuffer()));
    }catch(error){if(!res.headersSent)send(409,{success:false,message:error.message||'本机操作未完成'});}
  }
  close(){this.token='';this.server?.closeAllConnections();return new Promise(resolve=>this.server?this.server.close(resolve):resolve());}
}
module.exports={LocalBridge,PORT};
