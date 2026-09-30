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
  showModal(){this.open=true;this.hidden=false;}
  close(){this.open=false;this.hidden=true;}
  reportValidity(){return true;}
}
const all = node => [node,...node.children.flatMap(all)];
function setup(overrides={}) {
  const root=new Node(),calls=[],timers=new Map();
  let timerSequence = 0;
  const links=['overview','jobs','talent','applications','chats','resume'].map(route=>{
    const node=new Node('a');node.dataset.hrRoute=route;return node;
  });
  const env={document:{getElementById:()=>root,createElement:tag=>new Node(tag),createElementNS:(_ns,tag)=>new Node(tag),
    querySelectorAll:()=>links},currentUser:{platformAdmin:true,username:'13800000000'},
    activeWorkspace:'hr',location:{pathname:'/',search:'',hash:''},
    DOMParser:class{parseFromString(text){return {body:{textContent:text},querySelectorAll:()=>[]};}},
    history:{pushState(_a,_b,url){env.location.hash=url.slice(url.indexOf('#'));},
      replaceState(_a,_b,url){env.location.hash=url.slice(url.indexOf('#'));}},
    switchWorkspace(){},URLSearchParams,encodeURIComponent,Date,URL,crypto:require('node:crypto'),
    setInterval(fn,ms){const id=++timerSequence;timers.set(id,{fn,ms});return id;},
    clearInterval(id){timers.delete(id);},setTimeout(){},clearTimeout(){},addEventListener(){},confirm:overrides.confirm || (()=>false),
    fetch:overrides.fetch || (async()=>({ok:false,status:503})),
    api:async(url,options)=>{
      calls.push({url,options});
      if(overrides.api)return overrides.api(url,options);
      if(url.endsWith('/login/status'))return {data:{state:'complete'}};
      if(url.endsWith('/resumes'))return {data:[]};
      const key=url.split('/hr/')[1].split(/[/?]/)[0];
      return {data:structuredClone(fixtures[key==='candidates'?'recommendations':key])};
    }};
  if(overrides.transport)env.HeyiHrTransport={api:env.api,...overrides.transport};
  env.window=env;vm.createContext(env);
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../hr-pool.js'),'utf8'),env);
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../hr-demands.js'),'utf8'),env);
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../hr-departments.js'),'utf8'),env);
  if(overrides.passive)vm.runInContext(fs.readFileSync(path.join(__dirname,'../hr-passive.js'),'utf8'),env);
  vm.runInContext(source,env);
  const visible=()=>{const visit=node=>node.hidden?[]:[node,...node.children.flatMap(visit)];return visit(root);};
  const click=async(text)=>{const node=visible().find(n=>n.tagName==='button'&&n.textContent===text);
    assert.ok(node,'button exists: '+text);await node.click();await flush();};
const text=()=>visible().map(n=>n._text).join('\n');
  const enter=async route=>{env.HrWorkspace.enter(route);await flush();};
  const tick=async(ms)=>{for(const timer of [...timers.values()])if(timer.ms===ms)await timer.fn();await flush();};
  return {root,env,calls,click,text,enter,visible,timers,tick};
}

const poolResult=()=>({items:[{id:'talent-1',name:'测试人才',education:'本科',experience:'5年',stage:'first',
  projectId:'project-1',version:1,updatedAt:'2026-09-29T00:00:00Z'}],
  projects:[{id:'project-1',name:'电商项目组',version:1}],counts:{all:1,first:1},total:1,page:1,pages:1});
test('resume pool shows queue and retry time using cached status only, and refreshes without BOSS actions',async()=>{
  let reconnects=0;
  const app=setup({passive:true,transport:{connection:()=>({paired:true}),startAutoConnect(){reconnects++;},reset(){}},
    api:async url=>({data:url.endsWith('/login/status')
    ?{state:'complete',passive:{phase:'budget_wait',retryAt:Date.now()/1000+300,lastProgressAt:Date.now()/1000-600,
      counts:{r1:{inspect:2,requested:3}}}}
    :{items:[],total:0}})});
  await app.enter('resume-pool');
  assert.equal(reconnects,1);
  assert.match(app.text(),/待处理 2/);assert.match(app.text(),/待接收 3/);
  assert.match(app.text(),/预计 \d\d:\d\d 继续/);assert.match(app.text(),/上次进展/);
  await app.tick(10000);
  assert.ok(app.calls.every(c=>c.url.endsWith('/login/status')||c.url.startsWith('/api/v1/hr/resume-pool')));
  app.env.HrWorkspace.reset();assert.equal(app.timers.size,0);
});
test('resume categories are explicit, history is collapsed, and review embeds the document without new-tab links',async()=>{
  const row={id:'resume1',name:'张晓林',jobName:'秘书',status:'pending',extension:'pdf',fileUrl:'/api/v1/hr/resume-pool/file?id=resume1',aiState:'idle'};
  const app=setup({passive:true,api:async url=>({data:url.endsWith('/login/status')?{state:'complete'}:
    url.endsWith('/speeches')?{items:[]}:url.endsWith('/pool')?{projects:[]}:
    {items:[row],total:1,counts:{pending:1,approved:0,rejected:0,all:1},events:[{code:'RESUME_RECEIVED',name:row.name,createdAt:'2026-09-29T10:00:00Z'}]}})});
  let previews=0;
  app.env.createHrResumePreview=({row:opened})=>{assert.equal(opened.id,'resume1');previews++;return ()=>{};};
  await app.enter('resume-pool');
  assert.match(app.text(),/待审批 1/);assert.match(app.text(),/不通过 0/);
  const log=all(app.root).find(n=>n.className==='hr-activity-log');
  assert.equal(log.open,false);
  await app.click('查看与审批');assert.equal(previews,1);
  assert.equal(all(app.root).some(n=>n.tagName==='a'&&n.target==='_blank'&&n.href===row.fileUrl),false);
  assert.ok(all(app.root).some(n=>n.className==='hr-review-preview'));
});
test('resume job filter combines with review state and reports reconnect progress without a BOSS fetch',async()=>{
  const app=setup({passive:true,api:async url=>({data:url.endsWith('/login/status')
    ?{state:'complete',passive:{phase:'catching_up',catchupPending:3,counts:{r1:{requested:2},r2:{requested:4}}}}
    :{items:[],total:0,counts:{pending:0,approved:1,rejected:0,all:1},
      jobs:[{id:'job1',name:'运营',count:1,ruleIds:['r1']},{id:'job2',name:'秘书',count:2,ruleIds:['r2']}]}})});
  await app.enter('resume-pool');
  assert.match(app.text(),/补收断开期间/);
  const filter=all(app.root).find(n=>n.attributes['aria-label']==='按职位筛选简历');
  assert.ok(filter);await app.click('秘书 2');
  assert.ok(app.calls.some(c=>c.url.includes('status=pending&jobId=job2')));
  assert.equal(all(filter).find(n=>n._text==='秘书 2').attributes['aria-pressed'],'true');
  await app.click('通过 1');
  assert.ok(app.calls.some(c=>c.url.includes('status=approved&jobId=job2')));
  assert.ok(app.calls.every(c=>c.url.endsWith('/login/status')||c.url.startsWith('/api/v1/hr/resume-pool')));
});
test('invitation card opens an editable message and only an explicit send queues it',async()=>{
  const talent={...poolResult().items[0],stage:'invited',source:'passive'};
  const app=setup({api:async(url,options)=>{
    if(url.endsWith('/login/status'))return {data:{state:'complete'}};
    if(url.includes('/pool/messages'))return {data:options?{id:'message1',status:'queued'}:{items:[]}};
    return {data:{...poolResult(),items:[talent]}};
  }});
  await app.enter('pool');
  await app.click('发送邀约');
  assert.equal(app.calls.filter(c=>c.url.endsWith('/pool/messages')&&c.options).length,0);
  const input=all(app.root).find(n=>n.tagName==='textarea'&&n.placeholder?.startsWith('例如'));
  input.value='您好，明天下午方便参加面试吗？';
  const dialog=all(app.root).find(n=>n.className==='hr-pool-dialog hr-invite-dialog');
  await all(dialog).find(n=>n.tagName==='button'&&n._text==='发送邀约').click();await flush();
  const calls=app.calls.filter(c=>c.url.endsWith('/pool/messages')&&c.options);
  assert.equal(calls.length,1);assert.equal(JSON.parse(calls[0].options.body).message,input.value||'您好，明天下午方便参加面试吗？');
  assert.equal(JSON.parse(calls[0].options.body).talentId,talent.id);
});
test('workspace automatically reconnects helper and displays cached risk state without initiating BOSS work',async()=>{
  let listener,starts=0,stops=0;
  const app=setup({transport:{
    connection:()=>({paired:false}),
    subscribe(fn){listener=fn;},
    startAutoConnect(){starts++;},
    stopAutoConnect(){stops++;},
  }});
  await app.enter('overview');assert.equal(starts,1);
  listener({paired:false,phase:'reconnecting',message:'助手暂时离线，正在自动重连'});
  assert.match(app.text(),/助手自动重连中/);
  listener({paired:true,phase:'connected',state:{state:'verification_pending',error:'需要完成安全验证'}});
  assert.match(app.text(),/需要完成安全验证/);
  assert.equal(app.calls.length,0,'UI recovery must not call jobs, verify, or login/start');
  app.env.HrWorkspace.beginLogout();assert.equal(stops,1);
});
test('talent pool uses platform API without pairing and combines independent filters',async()=>{
  const app=setup({transport:{connection:()=>({paired:false})},api:async()=>({data:poolResult()})});
  app.env.HeyiHrTransport.api=async()=>{throw Error('Pool must not call BOSS');};
  await app.enter('pool');
  assert.match(app.text(),/测试人才/);assert.match(app.text(),/本科/);assert.match(app.text(),/电商项目组/);
  assert.ok(app.calls.every(c=>c.url.startsWith('/api/v1/hr/pool')));
  const select=app.visible().find(n=>n.tagName==='select'&&n.children.some(v=>v.textContent==='全部项目组'));
  select.value='project-1';await select.events.change();await flush();
  await app.click('一面 1');
  assert.match(app.calls.at(-1).url,/stage=first&project=project-1/);
  assert.equal(app.timers.size,0);
});
test('talent pool drops late responses after logout and clears personal data',async()=>{
  let resolve;
  const app=setup({api:()=>new Promise(r=>resolve=r)});
  await app.enter('pool');app.env.HrWorkspace.reset();
  resolve({data:poolResult()});await flush();
  assert.doesNotMatch(app.text(),/测试人才/);
});
test('candidate reserve dialog saves normalized fields through platform only',async()=>{
  const app=setup({api:async(url)=>({data:url.startsWith('/api/v1/hr/pool')?poolResult():fixtures.recommendations})});
  await app.enter('talent');await app.click('搜索人才');
  // A source detail fetch is not needed to save the information already rendered.
  const add=app.visible().find(n=>n.tagName==='button'&&n.textContent==='加入储备人才库');
  assert.ok(add);await add.click();await flush();
  const dialog=all(app.root).find(n=>n.className==='hr-pool-dialog');
  assert.equal(dialog.open,true);
  const form=dialog.children[0];
  await form.events.submit({preventDefault(){}});await flush();
  const save=app.calls.find(c=>c.url.endsWith('/pool/talents'));
  assert.ok(save);const body=JSON.parse(save.options.body);
  assert.equal(body.source,'boss');assert.ok(body.name);assert.ok(body.sourceId);
  assert.equal(body.securityId,undefined);
});
const demandDirectory=()=>({items:[{id:'dept-1',name:'电商组',manager:'测试经理',status:'ACTIVE',code:'ECOM',version:1}]});
const demandRow=()=>({id:'d1',version:1,date:'2026-09-29',position:'电商运营',department:'电商组',departmentId:'dept-1',manager:'测试经理',
  category:'运营',headcount:2,education:'本科',years:3,status:'recruiting',responsibilities:'店铺运营',
  requirements:'电商经验',aiStatus:'idle',aiResult:{},jobId:'job-test-2',jobAccountKey:'a'.repeat(64),
  jobDescription:'已保存旧 JD',jobFetchedAt:'2026-09-29T00:00:00Z'});
const demandStatus=()=>({state:'complete',accountKey:'a'.repeat(64),capabilities:['job-jd']});
const demandDetail=(id='job-test-2')=>({ok:true,data:{job:{encryptId:id,jobName:'电商运营',postDescription:'当前岗位 JD：负责直播策划与内容运营。'}}});
test('demand generation refreshes the exact local job JD then saves before a single DRIVER call',async()=>{
  let row=demandRow(),started=0;
  const app=setup({api:async(url,options)=>{
    if(url.endsWith('/login/status'))return {data:demandStatus()};
    if(url==='/api/v1/hr/jobs')return {data:fixtures.jobs};
    if(url.startsWith('/api/v1/hr/jobs?'))return {data:demandDetail()};
    if(url==='/api/v1/hr/departments')return {data:demandDirectory()};
    if(url.endsWith('/save')){row={...JSON.parse(options.body),version:2};return {data:row};}
    if(url.endsWith('/generate')){started++;row={...row,aiStatus:'running',aiJob:'j1',aiStartedAt:Date.now()/1000-12};return {data:row};}
    if(url.includes('?id='))return {data:{item:row}};
    return {data:{items:[row],counts:{all:1,recruiting:1},total:1,page:1,pages:1}};
  }});
  await app.enter('demands');assert.match(app.text(),/电商运营/);
  assert.ok(app.calls.every(c=>c.url.startsWith('/api/v1/hr/demands')),'Listing is cloud-only');
  await app.click('查看 / 编辑');assert.equal(started,0);
  await app.click('一键 AI 生成');
  assert.equal(started,1);assert.match(app.text(),/驱动模型生成中/);
  assert.match(app.text(),/已等待 12 秒/);assert.match(app.text(),/结果返回后将在这里显示/);
  assert.doesNotMatch(app.text(),/关联具体职位后即可生成/);
  assert.ok(app.calls.find(c=>c.url==='/api/v1/hr/jobs?jobId=job-test-2'));
  assert.match(row.jobDescription,/直播策划/);assert.equal(row.jobAccountKey,'a'.repeat(64));
  assert.ok(app.calls.findIndex(c=>c.url.includes('/jobs?'))<app.calls.findIndex(c=>c.url.endsWith('/save')));
  assert.ok(app.calls.findIndex(c=>c.url.endsWith('/save'))<app.calls.findIndex(c=>c.url.endsWith('/generate')));
  await app.tick(2500);assert.equal(started,1);
  row={...row,aiStatus:'failed',aiError:'模型不可用'};await app.tick(2500);
  assert.match(app.text(),/模型不可用/);assert.equal(app.timers.size,0);
  assert.match(app.text(),/需求和关联 JD 已保留/);
  assert.ok(!app.calls.some(c=>/resumes\/request|login\/start|messages/.test(c.url)));
});
test('missing JD, mismatched position, failed read or changed account cannot start AI',async()=>{
  for(const failure of ['empty','wrong-job','failed','account-switch']){
    let switched=false;
    const app=setup({api:async(url)=>{
      if(url.endsWith('/login/status'))return {data:{...demandStatus(),accountKey:switched?'b'.repeat(64):'a'.repeat(64)}};
      if(url==='/api/v1/hr/jobs')return {data:fixtures.jobs};
      if(url.startsWith('/api/v1/hr/jobs?')){
        switched=failure==='account-switch';
        return {data:failure==='empty'?{ok:true,data:{job:{encryptId:'job-test-2'}}}:failure==='failed'
          ?{ok:false,error:{message:'读取失败'}}:demandDetail(failure==='wrong-job'?'job-test-1':'job-test-2')};
      }
      if(url==='/api/v1/hr/departments')return {data:demandDirectory()};
      return {data:{items:[demandRow()],counts:{all:1},total:1,page:1,pages:1}};
    }});
    await app.enter('demands');await app.click('查看 / 编辑');await app.click('一键 AI 生成');
    assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0,failure);
  }
});
test('late demand save response cannot repopulate personal data after logout',async()=>{
  let resolve;
  const app=setup({api:async(url)=>{
    if(url.endsWith('/login/status'))return {data:demandStatus()};
    if(url==='/api/v1/hr/jobs')return {data:fixtures.jobs};
    if(url==='/api/v1/hr/departments')return {data:demandDirectory()};
    if(url.endsWith('/save'))return new Promise(r=>resolve=r);
    return {data:{items:[demandRow()],counts:{all:1},total:1,page:1,pages:1}};
  }});
  await app.enter('demands');await app.click('查看 / 编辑');
  const dialog=all(app.root).find(n=>n.className==='hr-pool-dialog hr-demand-dialog');
  all(dialog).find(n=>n.name==='requirements').value='修改后的补充条件';
  const form=dialog.children[0];const pending=form.events.submit({preventDefault(){}});
  await flush();app.env.HrWorkspace.reset();resolve({data:demandRow()});await pending;await flush();
  assert.doesNotMatch(app.text(),/测试经理|电商运营/);
});
test('helper restores an interrupted read once without replaying writes or starting login',async()=>{
  let listener,reads=0;
  const app=setup({transport:{connection:()=>({paired:true}),subscribe(fn){listener=fn;}},
    api:async(url)=>{
      if(url.endsWith('/jobs')){if(++reads===1)throw Error('助手暂时不可用');return {data:fixtures.jobs};}
      return {data:{}};
    }});
  await app.enter('jobs');assert.match(app.text(),/助手暂时不可用/);
  listener({paired:true,state:{state:'complete',paused:false,busy:false}});await flush();
  assert.equal(reads,2);assert.match(app.text(),/Java 后端开发/);
  listener({paired:true,state:{state:'complete',paused:false,busy:false}});await flush();
  assert.equal(reads,2);
  assert.ok(app.calls.every(c=>!c.options?.method||c.options.method==='GET'));
});
test('department module uses its independent HR directory and does not touch system departments or BOSS',async()=>{
  const app=setup({transport:{connection:()=>({paired:false})},api:async()=>({data:demandDirectory()})});
  app.env.HeyiHrTransport.api=async()=>{throw Error('Must not use BOSS');};
  await app.enter('departments');assert.match(app.text(),/电商组/);assert.match(app.text(),/测试经理/);
  assert.ok(app.calls.every(c=>c.url==='/api/v1/hr/departments'));
  await app.click('编辑部门');
  const dialog=all(app.root).find(n=>n.className==='hr-pool-dialog hr-department-dialog');
  const code=all(dialog).find(n=>n.name==='code');
  assert.equal(code.value,'ECOM');
});

test('overview has useful entry points and no webpage QR or QR requests',async()=>{
  const app=setup({api:async()=>({data:{state:'pending',phase:'qr'}})});
  await app.enter('overview');await app.tick(3000);
  assert.equal(all(app.root).filter(n=>n.tagName==='img').length,0);
  assert.match(app.text(),/职位管理/);assert.match(app.text(),/人才寻源/);
  assert.match(app.text(),/在桌面助手完成登录/);
  assert.doesNotMatch(app.text(),/刷新二维码|清除会话并重新扫码/);
  assert.ok(app.calls.every(c=>c.url.endsWith('/login/status')));
  assert.equal(app.timers.size,1);
});
test('unpaired overview is quiet; explicit pair does not automatically login',async()=>{
  let pairs=0;
  const app=setup({transport:{connection:()=>({paired:false}),connect:async()=>{pairs++;}},
    api:async()=>({data:{state:'idle'}})});
  await app.enter('overview');assert.equal(app.calls.length,0);
  await app.click('连接本机助手');assert.equal(pairs,1);
  assert.match(app.text(),/在桌面助手中登录 BOSS/);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
  await app.click('在助手中登录 BOSS');
  assert.equal(app.calls.filter(c=>c.url.endsWith('/login/start')).length,1);
});
test('phone confirmation uses one status poll and stays in the overview when ready',async()=>{
  let state='pending',phase='scanned';
  const app=setup({api:async url=>({data:url.endsWith('/login/status')?{state,phase}:fixtures.jobs})});
  await app.enter('overview');
  const progress=all(app.root).find(n=>n.className==='hr-login-progress');
  assert.equal(progress.dataset.phase,'scanned');assert.equal(app.timers.size,1);
  phase='verifying';await app.tick(3000);assert.equal(progress.dataset.phase,'verifying');
  phase='scanned';await app.tick(3000);assert.equal(progress.dataset.phase,'verifying');
  state='complete';await app.tick(3000);
  assert.equal(progress.hidden,true);assert.equal(app.timers.size,0);
  assert.match(app.text(),/招聘账号已就绪/);assert.equal(app.env.location.hash,'');
  assert.equal(app.calls.filter(c=>c.url.endsWith('/jobs')).length,0);
  await app.click('查看我的职位');assert.equal(app.env.location.hash,'#hr-jobs');
});
test('unreachable assistant stops its spinner after bounded local status failures',async()=>{
  let failed=false;
  const app=setup({api:async()=>{if(failed)throw Error('助手已断开');return {data:{state:'pending',phase:'scanned'}};}});
  await app.enter('overview');failed=true;
  await app.tick(3000);await app.tick(3000);await app.tick(3000);
  assert.equal(app.timers.size,0);
  assert.equal(all(app.root).find(n=>n.className==='hr-login-progress').hidden,true);
  assert.match(app.text(),/助手已断开/);assert.match(app.text(),/助手未连接/);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
});
test('risk-held sessions show the exact error and never trigger automatic login',async()=>{
  const app=setup({api:async()=>({data:{state:'verification_pending',failure_code:'PC_LOGIN_RESTRICTED',
    error:'电脑端受限，预计恢复时间：2026-09-29 13:44:43'}})});
  await app.enter('overview');await app.tick(3000);
  assert.match(app.text(),/2026-09-29 13:44:43/);assert.equal(app.timers.size,0);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
  await app.click('在助手中登录 BOSS');
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,1);
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
test('masked resume name retains the selected candidate identity without inventing hidden names',async()=>{
  for(const [detailName,listName,expected] of [
    ['***','测试候选人甲','测试候选人甲'],
    ['＊＊＊','张**','张**'],
    ['', '李**','李**'],
    ['***','***','姓名暂未公开'],
    ['简历公开昵称','测试候选人甲','简历公开昵称'],
  ]){
    const recommendations=structuredClone(fixtures.recommendations);
    recommendations.data.geekList[0].geekCard.geekName=listName;
    const resume=structuredClone(fixtures.resume);
    resume.data.basic.name=detailName;
    const app=setup({api:async url=>({data:url.includes('/resume?') ? resume
      : url.includes('/recommendations?') ? recommendations : fixtures.jobs})});
    await app.enter('jobs');await app.click('推荐人才');await app.click('查看简历');
    const sheet=all(app.root).find(n=>n.className==='hr-resume-sheet');
    assert.equal(all(sheet).find(n=>n.tagName==='h3').textContent,expected);
    assert.notEqual(all(sheet).find(n=>n.className==='hr-avatar').textContent,'*');
    if(expected.includes('*')||expected==='姓名暂未公开'){
      assert.match(sheet.textContent,/BOSS 暂未公开完整姓名/);
    }
    assert.doesNotMatch(sheet.textContent,/测试候选人乙/,'never borrow a different candidate name');
  }
});
test('candidate name can fall back from masked nested name to the same row nickname',async()=>{
  const recommendations=structuredClone(fixtures.recommendations);
  recommendations.data.geekList[0].geekCard.geekName='***';
  recommendations.data.geekList[0].nickname='已公开昵称';
  const resume=structuredClone(fixtures.resume);
  resume.data.basic.name='***';
  const app=setup({api:async url=>({data:url.includes('/resume?') ? resume
    : url.includes('/recommendations?') ? recommendations : fixtures.jobs})});
  await app.enter('jobs');await app.click('推荐人才');
  assert.match(app.text(),/已公开昵称/);
  await app.click('查看简历');
  const sheet=all(app.root).find(n=>n.className==='hr-resume-sheet');
  assert.equal(all(sheet).find(n=>n.tagName==='h3').textContent,'已公开昵称');
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
test('resume request shows progress, prevents double clicks and retains feedback after tracker refresh',async()=>{
  let finish,records=[];
  const app=setup({confirm:()=>true,api:async(url,options)=>{
    if(options?.method==='POST')return new Promise(resolve=>{finish=()=>{
      records=[{friend_id:'12345',state:'waiting',requested_at:1790568000,updated_at:1790568000}];
      resolve({success:true,data:{...records[0],friendId:'12345'}});
    };});
    if(url.endsWith('/resumes'))return {data:records};
    return {data:url.includes('/messages/')?fixtures.messages:url.endsWith('/jobs')?fixtures.jobs:fixtures.chats};
  }});
  await app.enter('chats');
  await all(app.root).find(n=>n.className==='hr-conversation').click();await flush();
  const action=all(app.root).find(n=>n.textContent==='索取附件简历'&&n.tagName==='button');
  const pending=action.click();await flush();
  assert.equal(action.disabled,true);
  assert.equal(action.textContent,'正在索取…');
  await action.click();
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,1);
  finish();await pending;await flush();
  assert.match(app.text(),/附件简历请求已发出/);
  assert.equal(action.textContent,'已索取，等待附件');
  assert.equal(action.disabled,true);
  const record=all(app.root).find(n=>n.className==='hr-record');
  assert.match(record.textContent,/已索取 · 等待候选人发送/);
  assert.match(record.textContent,/索取时间/);
  assert.match(all(app.root).find(n=>n.className==='hr-conversation').textContent,/已索取/);
});
test('confirmed resume request remains recorded after page reload without sending again',async()=>{
  const records=[{friend_id:'12345',state:'waiting',requested_at:1790568000,updated_at:1790568000}];
  const app=setup({api:async url=>({data:url.endsWith('/resumes')?records:
    url.includes('/messages/')?fixtures.messages:url.endsWith('/jobs')?fixtures.jobs:fixtures.chats})});
  await app.enter('chats');
  assert.match(app.text(),/已索取 · 等待候选人发送/);
  assert.match(app.text(),/索取时间/);
  await all(app.root).find(n=>n.className==='hr-conversation').click();await flush();
  const action=all(app.root).find(n=>n.tagName==='button'&&n.textContent==='已索取，等待附件');
  assert.equal(action.disabled,true);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
});
test('missing or mismatched resume response never claims success and preserves uncertainty if reads fail',async()=>{
  for(const response of [null,{success:true,data:{}},
    {success:true,data:{friendId:'99999',state:'waiting',requested_at:1790568000}}]){
    let dispatched=false;
    const app=setup({confirm:()=>true,api:async(url,options)=>{
      if(options?.method==='POST'){dispatched=true;return response;}
      if(url.endsWith('/resumes')){
        if(dispatched)throw Error('network unavailable');
        return {data:[]};
      }
      return {data:url.includes('/messages/')?fixtures.messages:url.endsWith('/jobs')?fixtures.jobs:fixtures.chats};
    }});
    await app.enter('chats');
    await all(app.root).find(n=>n.className==='hr-conversation').click();await flush();
    await app.click('索取附件简历');
    assert.doesNotMatch(app.text(),/附件简历请求已发出|已索取/);
    assert.match(app.text(),/索取结果待核对/);
    assert.match(app.text(),/避免重复发送/);
    assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,1);
  }
});
test('confirmed record and disabled button survive a failed tracker refresh',async()=>{
  let dispatched=false;
  const app=setup({confirm:()=>true,api:async(url,options)=>{
    if(options?.method==='POST'){
      dispatched=true;
      return {success:true,data:{friendId:'12345',state:'waiting',requested_at:1790568000,updated_at:1790568000}};
    }
    if(url.endsWith('/resumes')){
      if(dispatched)throw Error('network unavailable');
      return {data:[]};
    }
    return {data:url.includes('/messages/')?fixtures.messages:url.endsWith('/jobs')?fixtures.jobs:fixtures.chats};
  }});
  await app.enter('chats');
  await all(app.root).find(n=>n.className==='hr-conversation').click();await flush();
  await app.click('索取附件简历');
  assert.match(app.text(),/已索取 · 等待候选人发送/);
  assert.match(app.text(),/索取时间/);
  assert.match(app.text(),/已保留当前记录/);
  assert.equal(all(app.root).find(n=>n.tagName==='button'&&n.textContent==='已索取，等待附件').disabled,true);
});
test('received attachment retains confirmed request status and time after refreshing',async()=>{
  const app=setup({api:async url=>({data:url.endsWith('/resumes')?
    [{friend_id:'12345',state:'received',requested_at:1790568000,confirmed_at:1790568000,
      updated_at:1790569000,message_id:'12'}]:
    url.includes('/messages/')?fixtures.messages:url.endsWith('/jobs')?fixtures.jobs:fixtures.chats})});
  await app.enter('chats');
  const record=all(app.root).find(n=>n.className==='hr-record');
  assert.match(record.textContent,/已索取 · 已收到附件/);
  assert.match(record.textContent,/索取时间/);
  assert.match(record.textContent,/接收时间/);
  assert.match(record.textContent,/下载已收到的简历/);
});
test('chat direction uses participant IDs when BOSS marks both sources as zero',async()=>{
  const messages={ok:true,data:{messages:[
    {time:1790568000,from:{uid:98765,source:0},to:{uid:12345,source:0},
      body:{type:1,text:'方便发一份简历过来吗？'}},
    {time:1790568001,from:{uid:12345,source:0},to:{uid:98765,source:0},
      body:{type:1,text:'好的'}},
  ]}};
  const app=setup({api:async url=>({data:url.endsWith('/resumes')?[]:
    url.includes('/messages/')?messages:url.endsWith('/jobs')?fixtures.jobs:fixtures.chats})});
  await app.enter('chats');
  await all(app.root).find(n=>n.className==='hr-conversation').click();await flush();
  const bubbles=all(app.root).filter(n=>n.className.startsWith('hr-message '));
  assert.match(bubbles[0].className,/is-own/);
  assert.doesNotMatch(bubbles[1].className,/is-own/);
});
test('retrying an uncertain resume request requires a warning and preserves errors through refresh',async()=>{
  const confirmations=[];
  const app=setup({confirm:text=>{confirmations.push(text);return true;},api:async(url,options)=>{
    if(options?.method==='POST')throw Error('沟通页面准备失败，本次请求未发送');
    if(url.endsWith('/resumes'))return {data:[{friend_id:'12345',state:'uncertain',updated_at:1,error:'待核对'}]};
    return {data:url.includes('/messages/')?fixtures.messages:url.endsWith('/jobs')?fixtures.jobs:fixtures.chats};
  }});
  await app.enter('chats');
  await all(app.root).find(n=>n.className==='hr-conversation').click();await flush();
  await app.click('重新索取附件简历');
  assert.match(confirmations[0],/可能已经送达/);
  const posts=app.calls.filter(c=>c.options?.method==='POST');
  assert.equal(posts.length,1);
  assert.deepEqual(JSON.parse(posts[0].options.body),{friendId:'12345',retryUncertain:true});
  assert.match(app.text(),/沟通页面准备失败，本次请求未发送/);
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

test('explicit BOSS logout confirms cleanup without restarting login',async()=>{
  let disconnected=false;
  const app=setup({api:async(url,options)=>{
    if(url.endsWith('/login/logout')){disconnected=true;return {success:true,data:{state:'disconnected'}};}
    return {data:{state:disconnected?'disconnected':'complete'}};
  }});
  await app.enter('overview');await app.click('退出 BOSS 登录');
  assert.match(app.text(),/Cookie 和保存的登录凭据已清除/);
  await app.enter('overview');
  assert.deepEqual(app.calls.filter(c=>c.options?.method==='POST').map(c=>c.url),['/api/v1/hr/login/logout']);
});
test('unconfirmed cleanup never claims cookies were removed',async()=>{
  const app=setup({api:async url=>url.endsWith('/login/logout')?{success:true,data:{}}:{data:{state:'complete'}}});
  await app.enter('overview');await app.click('退出 BOSS 登录');
  assert.match(app.text(),/未确认 Cookie 已清除/);
  assert.doesNotMatch(app.text(),/保存的登录凭据已清除/);
});
test('logout locks repeated clicks and discards a late login response',async()=>{
  let reads=0,late,finish;
  const app=setup({api:url=>{
    if(url.endsWith('/login/status'))return ++reads===1?Promise.resolve({data:{state:'complete'}}):new Promise(r=>{late=r;});
    if(url.endsWith('/login/logout'))return new Promise(r=>{finish=r;});
  }});
  await app.enter('overview');app.env.HrWorkspace.enter('overview');await flush();
  const button=app.visible().find(n=>n.tagName==='button'&&n.textContent==='退出 BOSS 登录');
  const pending=button.click();button.click();
  finish({success:true,data:{state:'disconnected'}});await pending;
  late({data:{state:'complete'}});await flush();
  assert.match(app.text(),/保存的登录凭据已清除/);assert.doesNotMatch(app.text(),/招聘账号已就绪/);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,1);
});
