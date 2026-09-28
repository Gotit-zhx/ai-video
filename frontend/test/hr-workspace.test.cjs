const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const fixtures = require('./fixtures/hr-workspace.json');
const source = fs.readFileSync(path.join(__dirname,'../hr-workspace.js'),'utf8');
const flush = async () => { await new Promise(r=>setImmediate(r));await new Promise(r=>setImmediate(r)); };

class Node {
  constructor(tag='div') {
    this.tagName=tag;this.children=[];this.events={};this.dataset={};this.attributes={};
    this.value='';this._text='';this.hidden=false;this.disabled=false;this.className='';
    this.classList={toggle:()=>{}};
  }
  set textContent(value) {this._text=String(value);this.children=[];}
  get textContent() {return this._text+this.children.map(n=>n.textContent).join(' ');}
  append(...nodes) {this.children.push(...nodes);}
  replaceChildren(...nodes) {this._text='';this.children=nodes;}
  setAttribute(key,value) {this.attributes[key]=value;}
  addEventListener(key,callback) {this.events[key]=callback;}
  querySelectorAll(selector) {return all(this).filter(n=>selector==='.hr-result'&&n.className.split(' ').includes('hr-result'));}
  click() {if(!this.disabled)return this.events.click?.({currentTarget:this,preventDefault(){}});}
}
const all = node => [node,...node.children.flatMap(all)];
function setup(overrides={}) {
  const root=new Node(),calls=[],timers=new Map();
  let timerSequence = 0;
  const links=['overview','jobs','talent','applications','chats','resume'].map(route=>{
    const node=new Node('a');node.dataset.hrRoute=route;return node;
  });
  const env={document:{getElementById:()=>root,createElement:tag=>new Node(tag),
    querySelectorAll:()=>links},currentUser:{platformAdmin:true,username:'13800000000'},
    activeWorkspace:'hr',location:{pathname:'/',search:'',hash:''},
    history:{pushState(_a,_b,url){env.location.hash=url.slice(url.indexOf('#'));},
      replaceState(_a,_b,url){env.location.hash=url.slice(url.indexOf('#'));}},
    switchWorkspace(){},URLSearchParams,encodeURIComponent,Date,URL,
    setInterval(fn,ms){const id=++timerSequence;timers.set(id,{fn,ms});return id;},
    clearInterval(id){timers.delete(id);},setTimeout(){},addEventListener(){},confirm:()=>false,
    fetch:overrides.fetch || (async()=>({ok:false,status:503})),
    api:async(url,options)=>{
      calls.push({url,options});
      if(overrides.api)return overrides.api(url,options);
      if(url.endsWith('/login/status'))return {data:{state:'complete'}};
      if(url.endsWith('/resumes'))return {data:[]};
      const key=url.split('/hr/')[1].split(/[/?]/)[0];
      return {data:structuredClone(fixtures[key==='candidates'?'recommendations':key])};
    }};
  env.window=env;vm.createContext(env);vm.runInContext(source,env);
  const visible=()=>all(root).filter(n=>!n.hidden);
  const click=async(text)=>{const node=visible().find(n=>n.tagName==='button'&&n.textContent===text);
    assert.ok(node,'button exists: '+text);await node.click();await flush();};
  const text=()=>root.children.filter(n=>!n.hidden).map(n=>n.textContent).join('\n');
  const enter=async route=>{env.HrWorkspace.enter(route);await flush();};
  const tick=async(ms)=>{for(const timer of [...timers.values()])if(timer.ms===ms)await timer.fn();await flush();};
  return {root,env,calls,click,text,enter,visible,timers,tick};
}

test('reloading during phone confirmation resumes polling and opens jobs after login',async()=>{
  let state='pending',phase='scanned',failOnce=false;
  const app=setup({api:async url=>{
    if(url.endsWith('/login/status')){
      if(failOnce){failOnce=false;throw Error('network unavailable');}
      return {data:{state,phase}};
    }
    if(url.endsWith('/status'))return {data:{ok:false,error:{code:'LOGIN_PENDING'}}};
    return {data:fixtures.jobs};
  }});
  await app.enter('overview');
  const progress=all(app.root).find(n=>n.className==='hr-login-progress');
  const success=all(app.root).find(n=>n.className.includes('hr-login-success'));
  assert.equal(progress.hidden,false);
  assert.equal(progress.dataset.phase,'scanned');
  assert.equal(success.hidden,true);
  assert.match(app.text(),/已扫码，请在手机上确认/);
  assert.doesNotMatch(app.text(),/错误代码：LOGIN_PENDING/);
  assert.equal([...app.timers.values()].filter(t=>t.ms===3000).length,2);
  failOnce=true;
  await app.tick(3000);
  assert.match(app.text(),/正在重新读取登录状态/);
  assert.equal(progress.dataset.phase,'reconnecting');
  phase='verifying';
  await app.tick(3000);
  assert.equal(progress.dataset.phase,'verifying');
  assert.equal(success.hidden,true,'phone confirmation is not a verified login yet');
  assert.equal([...app.timers.values()].filter(t=>t.ms===3000).length,1);
  state='complete';
  await app.tick(3000);
  assert.equal(app.env.location.hash,'#hr-jobs');
  assert.match(app.text(),/15–25K/);
  assert.equal(progress.hidden,true);
  assert.equal(success.hidden,false);
  assert.match(success.textContent,/登录成功/);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
});

test('phone confirmation updates loading state before the CLI finishes synchronizing',async()=>{
  let phase='scanned';
  const app=setup({api:async url=>({data:url.endsWith('/login/status')
    ? {state:'pending',phase} : {ok:false,error:{code:'LOGIN_PENDING'}}}),
    fetch:async()=>{
      phase='verifying';
      return {status:202,json:async()=>({data:{phase}})};
    }});
  await app.enter('overview');
  await app.tick(3000);
  const progress=all(app.root).find(n=>n.className==='hr-login-progress');
  assert.equal(progress.hidden,false);
  assert.equal(progress.dataset.phase,'verifying');
  assert.match(progress.textContent,/正在确认登录/);
  assert.equal(all(app.root).find(n=>n.className==='hr-qr').hidden,true);
  assert.equal(all(app.root).find(n=>n.className.includes('hr-login-success')).hidden,true);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
});

test('jobs render business fields and job action passes encrypted ID automatically',async()=>{
  const app=setup();await app.enter('jobs');
  assert.match(app.text(),/15–25K/);assert.match(app.text(),/厦门 · 思明区/);
  assert.doesNotMatch(app.text(),/job-test-1|encryptJobId|平台返回字段/);
  assert.doesNotMatch(app.text(),/TALENT STUDIO|刷新当前数据|从职位到人才/);
  await app.click('推荐人才');
  assert.ok(app.calls.some(c=>c.url.includes('/recommendations?')&&c.url.includes('jobId=job-test-1')));
  assert.match(app.text(),/测试候选人甲/);assert.match(app.text(),/软件工程/);
  assert.equal(all(app.root).filter(n=>n.tagName==='pre').length,0);
});
test('nested geekCard renders and resume follows exact IDs including required security ID',async()=>{
  const app=setup();await app.enter('jobs');await app.click('推荐人才');await app.click('查看简历');
  const request=app.calls.find(c=>c.url.includes('/resume?'));
  assert.ok(request.url.includes('geekId=candidate-test-1'));
  assert.ok(request.url.includes('jobId=job-test-1'));
  assert.ok(request.url.includes('securityId=test-security-1'));
  assert.match(app.text(),/工作经历/);assert.match(app.text(),/教育经历/);assert.match(app.text(),/证书资质/);
  assert.doesNotMatch(app.text(),/test-security/);
});
test('application opens a conversation and messages render in time order without sending',async()=>{
  const app=setup();await app.enter('applications');await app.click('查看沟通');
  assert.ok(app.calls.some(c=>c.url==='/api/v1/hr/messages/12345'));
  assert.match(app.text(),/候选人甲/);assert.match(app.text(),/主要使用 Java/);
  const bubbles=all(app.root).filter(n=>n.className.startsWith('hr-message '));
  assert.equal(bubbles.length,3);
  assert.match(bubbles[0].textContent,/订单和库存系统/);
  assert.ok(bubbles[1].className.includes('is-own'));
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
});
test('chat names, previews, unread state and selecting a conversation are visual',async()=>{
  const app=setup();await app.enter('chats');
  assert.match(app.text(),/2 条未读/);
  const conversation=all(app.root).find(n=>n.className==='hr-conversation');
  await conversation.click();await flush();
  assert.match(app.text(),/索取附件简历/);
  await app.click('索取附件简历');
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0,'cancelled confirmation never sends');
});
test('explicit hasMore=false disables next; empty and failed pages are not paginated',async()=>{
  const app=setup();await app.enter('jobs');await app.click('推荐人才');
  assert.equal(all(app.root).find(n=>n.tagName==='button'&&n.textContent==='下一页').disabled,true);
  for(const data of [{ok:true,data:{result:[]}},{ok:false,error:{code:'RATE_LIMITED',message:'请稍后再试'}}]){
    const emptyApp=setup({api:async url=>({data:url.endsWith('/jobs')?fixtures.jobs:data})});
    await emptyApp.enter('applications');
    const next=all(emptyApp.root).find(n=>n.tagName==='button'&&n.textContent==='下一页');
    assert.ok(!next||next.disabled);
  }
});
test('malicious names and messages stay plain text; no HTML injection or credential display',async()=>{
  const payload=structuredClone(fixtures.recommendations);
  payload.data.geekList[0].geekCard.geekName='<img src=x onerror=alert(1)>';
  const app=setup({api:async url=>({data:url.includes('/jobs')?fixtures.jobs:payload})});
  await app.enter('jobs');await app.click('推荐人才');
  assert.match(app.text(),/<img src=x onerror=alert\(1\)>/);
  assert.equal(all(app.root).filter(n=>n.tagName==='img'&&n.src==='x').length,0);
  assert.doesNotMatch(app.text(),/test-security-1/);
});
test('verified recruiter status renders connection card despite degraded secondary credential',async()=>{
  const app=setup();await app.enter('overview');
  assert.match(app.text(),/招聘方身份验证通过/);assert.doesNotMatch(app.text(),/auth_health|degraded/);
});
test('route change discards late sensitive response and reset clears candidate selections',async()=>{
  let resolve;
  const app=setup({api:url=>url.includes('/applications')?new Promise(r=>resolve=r):
    Promise.resolve({data:fixtures.jobs})});
  app.env.HrWorkspace.enter('applications');await flush();
  app.env.HrWorkspace.enter('jobs');await flush();
  resolve({data:fixtures.applications});await flush();
  assert.doesNotMatch(app.text(),/测试候选人甲/);
  app.env.HrWorkspace.reset();
  await app.enter('resume');
  assert.match(app.text(),/先选择一位候选人/);
});
test('candidate without required security ID never offers a broken resume action',async()=>{
  const payload=structuredClone(fixtures.recommendations);
  payload.data.geekList.forEach(row=>delete row.geekCard.securityId);
  const app=setup({api:async url=>({data:url.includes('/jobs')?fixtures.jobs:payload})});
  await app.enter('jobs');await app.click('推荐人才');
  assert.equal(all(app.root).filter(n=>n.tagName==='button'&&n.textContent==='查看简历').length,0);
  assert.match(app.text(),/平台暂未开放详细资料入口/);
});
test('position options finish before a candidate query uses the serialized connector',async()=>{
  let resolve;
  const app=setup({api:url=>url.endsWith('/jobs')?new Promise(r=>resolve=r):
    Promise.resolve({data:fixtures.applications})});
  app.env.HrWorkspace.enter('applications');await flush();
  assert.equal(app.calls.filter(c=>c.url.includes('/applications')).length,0);
  resolve({data:fixtures.jobs});await flush();
  assert.equal(app.calls.filter(c=>c.url.includes('/applications')).length,1);
});

test('explicit clear-and-scan starts exactly one QR flow after local cleanup',async()=>{
  let state='verification_pending';
  const app=setup({api:async(url,options)=>{
    if(url.endsWith('/login/logout')) {state='disconnected';return {data:{state}};}
    if(url.endsWith('/login/start')) {state='pending';return {data:{state}};}
    if(url.endsWith('/login/status'))return {data:{state,phase:'verifying',error:'当前会话已过期'}};
    return {data:{ok:false,error:{code:'RECRUITER_VERIFICATION_PENDING'}}};
  }});
  await app.enter('overview');
  await app.click('清除会话并重新扫码');
  assert.deepEqual(app.calls.filter(c=>c.options?.method==='POST').map(c=>c.url),
    ['/api/v1/hr/login/logout','/api/v1/hr/login/start']);
  assert.match(app.text(),/验证招聘方账号/);
  assert.equal(all(app.root).find(n=>n.tagName==='button'&&n.textContent==='清除会话并重新扫码').hidden,true);
});

test('ordinary disconnect never restarts QR and stays disconnected on refresh',async()=>{
  let disconnected=false;
  const app=setup({api:async(url,options)=>{
    if(url.endsWith('/login/logout')) {disconnected=true;return {data:{state:'disconnected'}};}
    if(url.endsWith('/login/status'))return {data:{state:disconnected?'disconnected':'complete'}};
    return {data:disconnected?{ok:false,error:{code:'SESSION_DISCONNECTED'}}:fixtures.status};
  }});
  await app.enter('overview');
  await app.click('断开服务器连接');
  assert.match(app.text(),/服务器连接已断开/);
  await app.enter('overview');
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,1);
  assert.equal(all(app.root).find(n=>n.className==='hr-qr').hidden,true);
});

test('disconnected page reload stays idle until an explicit connect click',async()=>{
  let started=false;
  const app=setup({api:async url=>{
    if(url.endsWith('/login/start')) {started=true;return {data:{state:'pending'}};}
    if(url.endsWith('/login/status'))return {data:{state:started?'pending':'disconnected',phase:'verifying'}};
    return {data:{ok:false,error:{code:'SESSION_DISCONNECTED'}}};
  }});
  await app.enter('overview');
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
  await app.click('扫码连接招聘账号');
  assert.deepEqual(app.calls.filter(c=>c.options?.method==='POST').map(c=>c.url),
    ['/api/v1/hr/login/start']);
});

test('reset rejects double clicks and ignores an old login status response',async()=>{
  let readCount=0,lateStatus,finishLogout;
  const app=setup({api:url=>{
    if(url.endsWith('/login/status')){
      if(++readCount===1)return Promise.resolve({data:{state:'verification_pending'}});
      return new Promise(resolve=>lateStatus=resolve);
    }
    if(url.endsWith('/login/logout'))return new Promise(resolve=>finishLogout=resolve);
    if(url.endsWith('/login/start'))return Promise.resolve({data:{state:'pending'}});
    return Promise.resolve({data:{ok:false,error:{code:'RECRUITER_VERIFICATION_PENDING'}}});
  }});
  await app.enter('overview');
  await app.enter('overview');
  const disconnect=app.visible().find(n=>n.tagName==='button'&&n.textContent==='清除会话并重新扫码');
  const first=disconnect.click();
  disconnect.click();
  finishLogout({data:{state:'disconnected'}});
  await first;
  lateStatus({data:{state:'verification_pending',error:'old response'}});
  await flush();
  assert.match(app.text(),/正在生成登录二维码/);
  assert.doesNotMatch(app.text(),/old response/);
  assert.deepEqual(app.calls.filter(c=>c.options?.method==='POST').map(c=>c.url),
    ['/api/v1/hr/login/logout','/api/v1/hr/login/start']);
  assert.equal(all(app.root).find(n=>n.className==='hr-qr').hidden,true);
});
