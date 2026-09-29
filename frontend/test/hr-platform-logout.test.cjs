const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
const app=fs.readFileSync(path.join(__dirname,'../app.js'),'utf8');
const source=app.slice(app.indexOf('document.querySelector("#logout-button").addEventListener'),app.indexOf('document.querySelector("#refresh-button").addEventListener'));
function setup(disconnect){
  let handler,message;const events=[];
  const button={disabled:false,textContent:'退出',addEventListener(_event,callback){handler=callback;}};
  const env={document:{querySelector:()=>button},
    HrWorkspace:{allowed:()=>true,beginLogout(){events.push('pause-ui');}},
    HeyiHrTransport:{disconnect:async options=>{events.push('cleanup');return disconnect(options);}},
    api:async()=>{events.push('platform-logout');},
    loginForm:{reset(){}},showLogin(value){message=value.message;events.push('login-screen');},
    sessionExpiryActive:false,pendingWorkspaceAfterLogin:'',pendingProductCaptureAfterLogin:''};
  vm.runInNewContext(source,env);
  return {button,events,click:()=>handler({currentTarget:button}),message:()=>message};
}
test('platform logout waits for helper cleanup before ending platform authorization',async()=>{
  let finish;const app=setup(()=>new Promise(resolve=>{finish=resolve;}));
  const pending=app.click();await app.click();
  assert.deepEqual(app.events,['pause-ui','cleanup']);assert.equal(app.button.disabled,true);
  finish({cleared:true});await pending;
  assert.deepEqual(app.events,['pause-ui','cleanup','platform-logout','login-screen']);
  assert.equal(app.message(),'');assert.equal(app.button.disabled,false);
});
test('offline helper never produces a false cleanup success at platform logout',async()=>{
  const app=setup(async()=>{throw Error('offline');});await app.click();
  assert.match(app.message(),/未能确认助手中的 BOSS 登录信息已清除/);
  assert.ok(app.events.includes('platform-logout'));
});
