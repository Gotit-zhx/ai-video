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
    this.tagName=tag;this.children=[];this.events={};this.dataset={};this.attributes={};this.style={};
    this.value='';this._text='';this.hidden=false;this.disabled=false;this.className='';
    this.classList={toggle:()=>{}};
  }
  set textContent(value) {this._text=String(value);this.children=[];}
  get textContent() {return this._text+this.children.map(n=>n.textContent).join(' ');}
  get isConnected() {return this._connected===true||!!this.parent?.isConnected;}
  get parentElement() {return this.parent||null;}
  append(...nodes) {nodes.forEach(n=>{n.parent=this;});this.children.push(...nodes);}
  replaceChildren(...nodes) {this._text='';this.children.forEach(n=>{n.parent=null;});this.children=[];this.append(...nodes);}
  remove(){if(this.parent){this.parent.children=this.parent.children.filter(n=>n!==this);this.parent=null;}}
  setAttribute(key,value) {this.attributes[key]=value;}
  removeAttribute(key) {delete this.attributes[key];}
  addEventListener(key,callback) {this.events[key]=callback;}
  querySelectorAll(selector) {return all(this).filter(n=>selector==='.hr-result'&&n.className.split(' ').includes('hr-result'));}
  click() {if(!this.disabled)return this.events.click?.({currentTarget:this,preventDefault(){}});}
  showModal(){this.open=true;this.hidden=false;}
  close(){const wasOpen=this.open;this.open=false;this.hidden=true;if(wasOpen)this.events.close?.();}
  reportValidity(){return true;}
}
const all = node => [node,...node.children.flatMap(all)];
test('HR access comes from the granted permission rather than a role label or administrator-only phone',()=>{
  const app=setup();
  app.env.currentUser={userId:'hr-a',username:'15800000001',platformAdmin:false,roles:['HR_DEPARTMENT'],permissions:['hr:access']};
  assert.equal(app.env.HrWorkspace.allowed(),true);
  app.env.currentUser.permissions=[];
  assert.equal(app.env.HrWorkspace.allowed(),false);
});
test('an unpaired HR user can inspect the local connection without restarting BOSS login or clearing it',async()=>{
  let reads=0;
  const app=setup({transport:{
    connection:()=>({paired:false}),
    diagnose:async()=>{reads++;return {ok:false,message:'浏览器已禁止本地网络访问，请在网站设置中允许。'};},
  }});
  await app.enter('overview');
  await app.click('检查连接');
  assert.equal(reads,1);
  assert.match(app.text(),/浏览器已禁止本地网络访问/);
  assert.equal(app.calls.length,0);
});
function setup(overrides={}) {
  const root=new Node(),calls=[],timers=new Map();
  root._connected=true;
  let timerSequence = 0;
  const links=['overview','demands','jobs','resume-pool','pool','onboarding','speeches','departments','talent','applications','chats','resume'].map(route=>{
    const node=new Node('a');node.dataset.hrRoute=route;return node;
  });
  const env={echarts:overrides.echarts,document:{documentElement:{dataset:{theme:'light'}},
    getElementById:()=>root,createElement:tag=>new Node(tag),createElementNS:(_ns,tag)=>new Node(tag),
    querySelectorAll:()=>links},currentUser:{platformAdmin:true,username:'13800000000'},
    activeWorkspace:'hr',location:{pathname:'/',search:'',hash:''},
    DOMParser:class{parseFromString(text){return {body:{textContent:text},querySelectorAll:()=>[]};}},
    history:{pushState(_a,_b,url){env.location.hash=url.slice(url.indexOf('#'));},
      replaceState(_a,_b,url){env.location.hash=url.slice(url.indexOf('#'));}},
    switchWorkspace(){},URLSearchParams,encodeURIComponent,Date,URL,crypto:require('node:crypto'),
    FileReader:class{
      readAsDataURL(file){this.result='data:application/pdf;base64,'+Buffer.from(file.name).toString('base64');this.onload();}
    },
    setInterval(fn,ms){const id=++timerSequence;timers.set(id,{fn,ms});return id;},
    clearInterval(id){timers.delete(id);},setTimeout:overrides.setTimeout||(()=>{}),clearTimeout(){},addEventListener(){},confirm:overrides.confirm || (()=>false),
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
  if(overrides.onboarding)vm.runInContext(fs.readFileSync(path.join(__dirname,'../hr-onboarding.js'),'utf8'),env);
  if(overrides.bi)vm.runInContext(fs.readFileSync(path.join(__dirname,'../hr-bi.js'),'utf8'),env);
  if(overrides.passive)vm.runInContext(fs.readFileSync(path.join(__dirname,'../hr-passive.js'),'utf8'),env);
  if(overrides.sourcing)vm.runInContext(fs.readFileSync(path.join(__dirname,'../hr-sourcing.js'),'utf8'),env);
  vm.runInContext(source,env);
  const visible=()=>{const visit=node=>node.hidden?[]:[node,...node.children.flatMap(visit)];return visit(root);};
  const click=async(text)=>{const node=visible().find(n=>n.tagName==='button'&&n.textContent===text);
    assert.ok(node,'button exists: '+text);await node.click();await flush();};
const text=()=>visible().map(n=>n._text).join('\n');
  const enter=async route=>{env.HrWorkspace.enter(route);await flush();};
  const tick=async(ms)=>{for(const timer of [...timers.values()])if(timer.ms===ms)await timer.fn();await flush();};
  return {root,env,calls,click,text,enter,visible,timers,tick,links};
}

const poolResult=()=>({items:[{id:'talent-1',name:'测试人才',education:'本科',experience:'5年',stage:'first',
  departmentId:'department-1',version:1,updatedAt:'2026-09-29T00:00:00Z'}],
  departments:[{id:'department-1',name:'电商部',status:'ACTIVE'}],
  projects:[],counts:{all:1,first:1},total:1,page:1,pages:1});
test('HR navigation follows recruitment workflow before configuration pages',()=>{
  const html=fs.readFileSync(path.join(__dirname,'../index.html'),'utf8');
  assert.deepEqual([...html.matchAll(/data-hr-route="([^"]+)"/g)].map(match=>match[1]),
    ['overview','demands','jobs','resume-pool','pool','onboarding','speeches','departments']);
});
test('only open job cards enable actions and passive invitation; closed and unknown cards cannot navigate or save',async()=>{
  const statuses=['开放中','待开放','已失效','已关闭','审核中','免费曝光中',null];
  const rows=statuses.map((status,index)=>({...fixtures.jobs.data[0],encryptJobId:'job-'+index,statusDesc:status}));
  const app=setup({passive:true,api:async url=>({data:url.endsWith('/login/status')
    ?{state:'complete',accountKey:'a'.repeat(64),capabilities:['passive-resume']}
    :url.endsWith('/jobs')?{ok:true,data:rows}:{items:[]}})});
  await app.enter('jobs');
  const cards=all(app.root).filter(n=>n.tagName==='article'&&n.className.includes('hr-job-card'));
  assert.equal(cards.length,statuses.length);
  for(const [index,card] of cards.entries()){
    const actions=all(card).filter(n=>n.tagName==='button');
    assert.equal(actions.length,3);
    assert.ok(actions.every(n=>n.disabled===(index!==0)));
    const input=all(card).find(n=>n.tagName==='input'&&n.type==='checkbox');
    assert.equal(input.disabled,index!==0);
    if(index>0){
      assert.ok(card.className.includes('is-unavailable'));
      assert.match(card.textContent,/仅开放中的职位可操作/);
      for(const action of actions)await action.events.click({preventDefault(){}});
    }
  }
  assert.ok(app.calls.every(c=>c.url.endsWith('/login/status')||c.url.endsWith('/jobs')||c.url.endsWith('/passive')));
  assert.equal(app.calls.filter(c=>c.url.endsWith('/passive')).length,1,'Unavailable cards never load or change rules');
});
test('an enabled job never claims another position sent a request or made progress',async()=>{
  const key='a'.repeat(64);
  const app=setup({passive:true,api:async url=>({data:url.endsWith('/login/status')
    ?{state:'complete',accountKey:key,capabilities:['passive-resume'],
      passive:{phase:'receiving',counts:{closed:{requested:2},open:{seen:8}},
        lastProgressAt:1791423063,progressByRule:{closed:1791423063}}}
    :url.endsWith('/jobs')?{ok:true,data:[fixtures.jobs.data[0]]}
    :{items:[{id:'open',jobId:'job-test-1',accountKey:key,enabled:true}]}})});
  await app.enter('jobs');
  const note=all(app.root).find(n=>n.className==='hr-hint hr-passive-note');
  assert.match(note.textContent,/正在等待新消息/);
  assert.doesNotMatch(note.textContent,/已索取|待接收|上次进展/);
});
test('job queue phase uses its own pending work even when another job is receiving',async()=>{
  const key='a'.repeat(64);
  const app=setup({passive:true,api:async url=>({data:url.endsWith('/login/status')
    ?{state:'complete',accountKey:key,capabilities:['passive-resume'],
      passive:{phase:'receiving',counts:{closed:{requested:2},open:{ready:1}},
        activityPending:7,catchupPending:4,lastProgressAt:1791423063,progressByRule:{closed:1791423063}}}
    :url.endsWith('/jobs')?{ok:true,data:[fixtures.jobs.data[0]]}
    :{items:[{id:'open',jobId:'job-test-1',accountKey:key,enabled:true}]}})});
  await app.enter('jobs');
  const note=all(app.root).find(n=>n.className==='hr-hint hr-passive-note');
  assert.match(note.textContent,/已回复，等待索取简历/);
  assert.match(note.textContent,/待索取 1/);
  assert.doesNotMatch(note.textContent,/已索取|待接收|上次进展|新消息待检查 7|补收检查剩余 4/);
});
test('history backfill status and cursor belong only to the selected job',async()=>{
  const key='a'.repeat(64);
  const timeouts=[];
  let histories={closed:{complete:false,page:5},open:{complete:false,page:2}};
  const app=setup({passive:true,setTimeout(fn,ms){timeouts.push({fn,ms});return timeouts.length;},
    api:async url=>({data:url.endsWith('/login/status')
    ?{state:'complete',accountKey:key,capabilities:['passive-resume'],
      passive:{phase:'catching_up',counts:{open:{inspect:8}},historyByRule:histories}}
    :url.endsWith('/jobs')?{ok:true,data:[fixtures.jobs.data[0]]}
    :{items:[{id:'open',jobId:'job-test-1',accountKey:key,enabled:true}]}})});
  await app.enter('jobs');
  let note=all(app.root).find(n=>n.className==='hr-hint hr-passive-note');
  assert.match(note.textContent,/正在补处理本职位历史招呼和简历/);
  assert.match(note.textContent,/待处理 8/);
  histories={closed:{complete:false,page:5},open:{complete:true,page:3}};
  await timeouts.find(timer=>timer.ms===30000).fn();
  await flush();
  note=all(app.root).find(n=>n.className==='hr-hint hr-passive-note');
  assert.doesNotMatch(note.textContent,/正在补处理本职位/);
});
test('job card owns sourcing; bounded candidate reads and AI do not send until HR confirms the individual greeting',async()=>{
  const key='a'.repeat(64);
  let saved=null;
  const app=setup({sourcing:true,api:async(url,options)=>{
    const body=options?.body?JSON.parse(options.body):{};
    if(url.endsWith('/login/status'))return {data:{state:'complete',accountKey:key,capabilities:['job-jd','active-sourcing','sourcing-credentials-v2']}};
    if(url==='/api/v1/hr/jobs')return {data:fixtures.jobs};
    if(url.startsWith('/api/v1/hr/jobs?'))return {data:{ok:true,data:{job:{encryptId:'job-test-1',jobName:'Java 后端开发工程师',postDescription:'负责系统开发与测试'}}}};
    if(url.startsWith('/api/v1/hr/sourcing?'))return {data:saved};
    if(url.endsWith('/sourcing/save')){
      saved={...body,id:'source1',version:1,profileState:'complete',
        profile:{summary:'后端工程师',skills:['Java'],suggestedJd:'负责开发测试'},
        references:[{id:'ref1',filename:'参考.pdf',characters:300}],run:null};
      return {data:saved};
    }
    if(url.includes('/recommendations?'))return {data:fixtures.recommendations};
    if(url.endsWith('/sourcing/search')){
      assert.equal(body.id,'source1');assert.equal(body.accountKey,key);assert.equal(body.count,5);
      assert.equal(body.candidates.length,2,'Repeated pages are deduplicated');
      saved={...saved,run:{state:'complete',version:1,wanted:5,examined:2,candidates:[
        {id:'c1',name:'测试候选人甲',score:80,summary:'经验相关',evidence:['有 Java 经验'],gaps:[],canGreet:true}]}};
      return {data:saved};
    }
    if(url.endsWith('/speeches'))return {data:{items:[{id:'speech1',name:'主动联系',kind:'active_greet',active:true,content:'{姓名}，想和您沟通{职位}。'}]}};
    if(url.endsWith('/sourcing/greet')){
      assert.equal(body.candidateId,'c1');assert.equal(body.confirmed,true);
      assert.match(body.message,/测试候选人甲.*Java 后端开发工程师/);
      saved.run.candidates[0].contact={status:'queued'};
      return {data:{id:'contact1',status:'queued'}};
    }
    throw Error('unexpected '+url);
  }});
  await app.enter('jobs');await app.click('AI 寻源');
  assert.match(app.text(),/Java 后端开发工程师/);
  assert.match(app.text(),/招聘需求池/);
  assert.equal(app.visible().some(n=>n.type==='file'),false);
  assert.equal(app.calls.some(c=>c.url.includes('/recommendations?')),false);
  const count=app.visible().find(n=>n.tagName==='select'&&n.attributes['aria-label']==='获取人数');count.value='5';
  await app.click('一键 AI 获取');
  assert.equal(app.calls.filter(c=>c.url.includes('/recommendations?')).length,1,'Explicit end of results prevents another BOSS read');
  assert.equal(app.calls.filter(c=>c.url.endsWith('/sourcing/search')).length,1);
  assert.equal(app.calls.filter(c=>c.url.endsWith('/sourcing/greet')).length,0);
  await app.click('打招呼');
  assert.equal(app.calls.filter(c=>c.url.endsWith('/sourcing/greet')).length,0);
  await app.click('确认向该候选人发送');
  assert.equal(app.calls.filter(c=>c.url.endsWith('/sourcing/greet')).length,1);
  assert.match(app.text(),/等待助手发送/);
  saved.run.candidates[0].contact={status:'queued',waitInfo:{reasonCode:'SEND_INTERVAL',retryAt:Date.now()/1000+60}};
  await app.tick(5000);
  assert.match(app.text(),/等待发送间隔 · 预计/);
  saved.run.candidates[0].contact={status:'queued',waitInfo:{reasonCode:'DAILY_GREETING_LIMIT',retryAt:Date.now()/1000+600}};
  await app.tick(5000);
  assert.match(app.text(),/已达每日 150 次上限/);
  saved.run.candidates[0].contact={status:'sent',friend_id:'123'};
  await app.tick(5000);
  assert.match(app.text(),/已打招呼 · 等待对方回复/);
  assert.equal(app.calls.filter(c=>c.url.endsWith('/sourcing/greet')).length,1,'Status polling never sends again');
  const html=fs.readFileSync(path.join(__dirname,'../index.html'),'utf8');
  assert.doesNotMatch(html,/data-hr-route="talent"/);
  assert.match(html,/hr-sourcing\.js/);
  app.env.HrWorkspace.reset();
  assert.doesNotMatch(all(app.root).map(n=>n._text).join(' '),/参考.pdf|向 测试候选人甲 打招呼/);
});
test('sourcing route without selected job never reads BOSS or creates a new scope',async()=>{
  const app=setup({sourcing:true,api:async()=>{throw Error('No request expected');}});
  await app.enter('talent');
  assert.match(app.text(),/请返回职位管理/);
  assert.equal(app.calls.length,0);
});
test('HR match score includes missing information instead of showing a sparse known score as 100',()=>{
  const app=setup({sourcing:true});
  const score=app.env.HeyiHrSourcing.matchingScore;
  assert.equal(score({score:100,knownWeight:50,totalWeight:100}),50);
  assert.equal(score({score:94.3,knownWeight:70,totalWeight:100}),66);
  assert.equal(score({score:100,knownWeight:100,totalWeight:100,verifiedMatch:100}),100);
  assert.equal(score({score:100,verifiedMatch:66}),66);
  assert.equal(score({score:0,knownWeight:0,totalWeight:100}),0);
  assert.equal(app.calls.length,0,'Displaying match scores never calls a model or BOSS');
});
test('an uncertain greeting refreshes from cloud and displays success without another send',async()=>{
  const key='a'.repeat(64),contact={id:'contact1',status:'uncertain',friend_id:''};
  const record={id:'scope1',accountKey:key,jobId:'job-test-1',jobName:'工程师',version:1,
    profileState:'complete',profile:{},criteria:{confirmed:true,conditions:[]},
    run:{id:'run1',version:1,state:'complete',wanted:5,candidates:[
      {id:'candidate1',name:'已发送人才',score:100,summary:'符合',canGreet:true,contact}]}};
  const app=setup({sourcing:true,api:async url=>{
    if(url.endsWith('/login/status'))return {data:{state:'complete',accountKey:key,capabilities:['job-jd','active-sourcing']}};
    if(url==='/api/v1/hr/jobs')return {data:fixtures.jobs};
    if(url.startsWith('/api/v1/hr/jobs?'))return {data:{ok:true,data:{job:{encryptId:record.jobId,
      jobName:record.jobName,postDescription:'岗位职责'}}}};
    if(url.startsWith('/api/v1/hr/sourcing?')||url.endsWith('/sourcing/save'))return {data:structuredClone(record)};
    if(url.startsWith('/api/v1/hr/demands?'))return {data:{items:[],pages:1}};
    throw Error('No extra model call or sending expected: '+url);
  }});
  await app.enter('jobs');await app.click('AI 寻源');
  assert.match(app.text(),/发送结果待核对/);
  assert.ok([...app.timers.values()].some(timer=>timer.ms===5000));
  contact.status='sent';contact.friend_id='123';
  await app.tick(5000);
  assert.match(app.text(),/已打招呼 · 等待对方回复/);
  assert.doesNotMatch(app.text(),/发送结果待核对/);
  assert.equal([...app.timers.values()].some(timer=>timer.ms===5000),false);
  assert.equal(app.calls.some(c=>c.url.endsWith('/sourcing/greet')||c.url.endsWith('/sourcing/search')),false);
});
test('job minimums and candidate checks are visible; unverified historical results cannot greet',async()=>{
  const key='a'.repeat(64),record={id:'scope1',accountKey:key,jobId:'job-test-1',jobName:'总经理秘书',version:1,
    profileState:'complete',profile:{},criteria:{confirmed:true,conditions:[]},
    basicRequirements:[{id:'education',text:'最低学历：本科'},{id:'years',text:'最低工作年限：3 年'}],
    run:{id:'run1',version:1,state:'complete',wanted:5,candidates:[
      {id:'checked',name:'已核验人才',score:100,summary:'符合核心能力',canGreet:true,basicChecks:[
        {label:'学历',status:'matched',evidence:'卡片学历：硕士'},
        {label:'工作年限',status:'matched',evidence:'卡片工作年限：5年'}]},
      {id:'legacy',name:'历史人才',score:100,summary:'旧分析',canGreet:false,basicRequirementsPending:true}]}};
  const app=setup({sourcing:true,api:async(url,options)=>{
    if(url.endsWith('/login/status'))return {data:{state:'complete',accountKey:key,capabilities:['job-jd','active-sourcing']}};
    if(url==='/api/v1/hr/jobs')return {data:{ok:true,data:[{...fixtures.jobs.data[0],
      degreeName:'本科',experienceName:'3-5年'}]}};
    if(url.startsWith('/api/v1/hr/jobs?'))return {data:{ok:true,data:{job:{encryptId:record.jobId,
      jobName:record.jobName,postDescription:'办公事务',experience:104}}}};
    if(url.endsWith('/sourcing/save')){
      const body=JSON.parse(options.body);
      assert.equal(body.education,'本科','Missing JD degree falls back to the exact selected job list label');
      assert.equal(body.experience,'3-5年','Opaque JD experience code never replaces the readable official label');
      return {data:record};
    }
    if(url.startsWith('/api/v1/hr/sourcing?'))return {data:record};
    if(url.startsWith('/api/v1/hr/demands?'))return {data:{items:[],pages:1}};
    throw Error('No extra model or BOSS request expected: '+url);
  }});
  await app.enter('jobs');await app.click('AI 寻源');
  assert.match(app.text(),/最低学历：本科.*最低工作年限：3 年/);
  assert.match(app.text(),/学历：符合 · 卡片学历：硕士/);
  assert.match(app.text(),/工作年限：符合 · 卡片工作年限：5年/);
  const cards=app.visible().filter(n=>n.tagName==='article'&&n.className==='hr-person-card');
  assert.equal(all(cards[0]).find(n=>n.tagName==='button'&&n._text==='打招呼').disabled,false);
  assert.equal(all(cards[1]).find(n=>n.tagName==='button'&&n._text==='打招呼').disabled,true);
  assert.match(cards[1].textContent,/历史结果尚未核验学历、年限/);
  assert.doesNotMatch(cards[1].textContent,/未提供首次打招呼参数/);
  assert.equal(app.calls.some(c=>c.url.endsWith('/sourcing/search')||c.url.endsWith('/sourcing/greet')),false);
});
test('sourcing requires confirmed conditions and never uses unsaved edits',async()=>{
  const key='a'.repeat(64);
  let saved={id:'scope1',accountKey:key,jobId:'job-test-1',jobName:'总经理秘书',version:1,
    profileState:'complete',references:[{id:'r1',filename:'参考.pdf',characters:500}],profile:{summary:'参考画像'},
    criteria:{confirmed:false,conditions:[
      {id:'k1',text:'相关或可迁移工作经验',weight:50,required:false},
      {id:'k2',text:'办公事务、会议或文件整理，其中一项即可',weight:30,required:false},
      {id:'k3',text:'接待或沟通协调，其中一项即可',weight:20,required:false}],
      suggestedConditions:[
        {id:'k1',text:'行政或秘书实际工作经历',weight:50,required:true,kind:'direction'},
        {id:'k2',text:'有文件或会议管理经验',weight:30,required:false,kind:'skills'},
        {id:'k3',text:'实际完成日常办公事务',weight:20,required:false,kind:'experience'}]},run:null};
  const app=setup({sourcing:true,api:async(url,options)=>{
    if(url.endsWith('/login/status'))return {data:{state:'complete',accountKey:key,capabilities:['job-jd','active-sourcing']}};
    if(url==='/api/v1/hr/jobs')return {data:fixtures.jobs};
    if(url.startsWith('/api/v1/hr/jobs?'))return {data:{ok:true,data:{job:{encryptId:saved.jobId,
      jobName:saved.jobName,postDescription:'支持日常办公及接待事务'}}}};
    if(url.startsWith('/api/v1/hr/sourcing?')||url.endsWith('/sourcing/save'))return {data:saved};
    if(url.endsWith('/sourcing/criteria')){
      const body=JSON.parse(options.body);
      assert.equal(body.version,saved.version);
      assert.equal(body.conditions.length,3);
      saved={...saved,version:saved.version+1,criteria:{confirmed:true,conditions:body.conditions}};
      return {data:saved};
    }
    throw Error('No BOSS reads or model calls expected: '+url);
  }});
  await app.enter('jobs');await app.click('AI 寻源');
  const find=()=>app.visible().find(n=>n.tagName==='button'&&n._text==='一键 AI 获取');
  assert.equal(find().disabled,true);
  assert.match(app.text(),/匹配条件/);
  assert.equal(app.visible().some(n=>n.tagName==='button'&&n._text==='使用 JD 建议'),false);
  assert.equal(find().disabled,true);
  assert.equal(saved.criteria.confirmed,false,'Suggestions do not confirm conditions for HR');
  assert.equal(app.visible().find(n=>n.attributes['aria-label']==='匹配条件 1').value,'行政或秘书实际工作经历');
  assert.match(app.text(),/核心方向/);
  assert.match(app.text(),/关键技能/);
  assert.match(app.text(),/实战经历/);
  assert.equal(app.visible().find(n=>n.tagName==='details'&&n.className==='hr-sourcing-advanced').open,undefined);
  await app.click('确认并保存条件');
  assert.equal(find().disabled,false);
  await app.click('刷新结果');
  assert.equal(app.visible().find(n=>n.attributes['aria-label']==='匹配条件 1').value,'行政或秘书实际工作经历');
  const field=app.visible().find(n=>n.attributes['aria-label']==='匹配条件 1');
  field.value='有行政或接待经验';field.events.input();
  assert.equal(find().disabled,true);
  assert.match(app.text(),/条件已修改/);
  await app.click('确认并保存条件');
  assert.equal(saved.criteria.conditions[0].text,'有行政或接待经验');
  await app.click('刷新结果');
  assert.equal(app.visible().find(n=>n.attributes['aria-label']==='匹配条件 1').value,'有行政或接待经验',
    'Refreshing preserves HR-approved conditions instead of replacing them with JD suggestions');
  assert.equal(find().disabled,false);
  assert.equal(app.calls.some(c=>c.url.includes('/recommendations?')||c.url.endsWith('/sourcing/search')),false);
});
for(const temporaryCode of ['REQUEST_BUDGET_EXHAUSTED','SESSION_UNAVAILABLE'])
test(`continuous sourcing reaches page 22, waits on ${temporaryCode} and never sends automatically`,async()=>{
  const key='a'.repeat(64);
  let saved={id:'scope1',accountKey:key,jobId:'job-test-1',jobName:'工程师',version:1,
    profileState:'complete',references:[],profile:{},run:null};
  const pages=[],batches=[];
  let budget=false,recovered=false,statusReads=0,finishPage;
  const app=setup({sourcing:true,setTimeout:fn=>{queueMicrotask(fn);return 1;},api:async(url,options)=>{
    if(url.endsWith('/login/status')){
      statusReads++;
      if(budget&&!recovered){recovered=true;return {data:{paused:true,reconnecting:true,
        hold:{code:temporaryCode}}};}
      return {data:{state:'complete',accountKey:key,capabilities:['job-jd','active-sourcing','continuous-sourcing']}};
    }
    if(url==='/api/v1/hr/jobs')return {data:fixtures.jobs};
    if(url.startsWith('/api/v1/hr/jobs?'))return {data:{ok:true,data:{job:{
      encryptId:saved.jobId,jobName:saved.jobName,postDescription:'系统开发'}}}};
    if(url.startsWith('/api/v1/hr/sourcing?')||url.endsWith('/sourcing/save'))return {data:saved};
    if(url.includes('/recommendations?')){
      const page=Number(new URL('https://local'+url).searchParams.get('page'));pages.push(page);
      if(page===3)await new Promise(resolve=>{finishPage=resolve;});
      if(page===7&&!budget){budget=true;throw Error('额度不足');}
      return {data:{ok:true,data:{hasMore:true,geekList:Array.from({length:10},(_,i)=>({
        encryptGeekId:`geek${page}_${i}`,encryptJobId:saved.jobId,geekCard:{geekName:'人才'}}))}}};
    }
    if(url.endsWith('/sourcing/search')){
      const body=JSON.parse(options.body);batches.push(body);
      saved={...saved,run:{id:'run1',state:'complete',version:1,wanted:5,pagesRead:body.page,
        examined:batches.reduce((sum,b)=>sum+b.candidates.length,0),
        candidates:Array.from({length:body.page===22?5:1},(_,i)=>({
          id:'c'+i,name:'人才',score:80,canGreet:true}))}};
      return {data:saved};
    }
    throw Error('unexpected '+url);
  }});
  await app.enter('jobs');await app.click('AI 寻源');
  app.visible().find(n=>n.attributes['aria-label']==='获取人数').value='5';
  const action=app.visible().find(n=>n.tagName==='button'&&n._text==='一键 AI 获取').click();
  await flush();await app.click('暂停寻找');finishPage();await action;await flush();
  assert.match(app.text(),/已暂停寻找/);
  await app.click('继续寻找');
  assert.ok(batches.length>22);
  assert.ok(batches.every(batch=>batch.candidates.length<=5),'AI batches stay small while pages continue');
  const submitted=batches.flatMap(batch=>batch.candidates.map(row=>row.encryptGeekId));
  assert.equal(new Set(submitted).size,submitted.length,'Queued cards are analyzed only once');
  for(let page=1;page<=21;page++)for(let i=0;i<10;i++)
    assert.ok(submitted.includes(`geek${page}_${i}`),'Every queued card on prior pages was analyzed');
  assert.equal(new Set(batches.map(b=>b.requestId)).size,1);
  assert.equal(pages.filter(p=>p===7).length,2,'Budget failure resumes the same unconsumed page');
  assert.equal(pages.at(-1),22);
  assert.equal(pages.filter(p=>p===3).length,1,'Pause retains queued cards and the cursor');
  assert.match(app.text(),/已获取 5 位初步匹配人选/);
  assert.equal(app.calls.some(c=>c.url.endsWith('/sourcing/greet')),false);
  assert.ok(statusReads>22);
});
test('zero matches stops at 100 analyzed people without requesting another page or paid batch',async()=>{
  const key='a'.repeat(64),pages=[],batches=[];
  let examined=0;
  let saved={id:'scope1',accountKey:key,jobId:'job-test-1',jobName:'工程师',version:1,
    profileState:'complete',references:[],profile:{},run:null};
  const app=setup({sourcing:true,api:async(url,options)=>{
    if(url.endsWith('/login/status'))return {data:{state:'complete',accountKey:key,
      capabilities:['job-jd','active-sourcing','continuous-sourcing']}};
    if(url==='/api/v1/hr/jobs')return {data:fixtures.jobs};
    if(url.startsWith('/api/v1/hr/jobs?'))return {data:{ok:true,data:{job:{
      encryptId:saved.jobId,jobName:saved.jobName,postDescription:'开发'}}}};
    if(url.startsWith('/api/v1/hr/sourcing?')||url.endsWith('/sourcing/save'))return {data:saved};
    if(url.includes('/recommendations?')){
      const page=Number(new URL('https://local'+url).searchParams.get('page'));pages.push(page);
      return {data:{ok:true,data:{hasMore:true,geekList:Array.from({length:7},(_,i)=>({
        encryptGeekId:`geek${page}_${i}`,encryptJobId:saved.jobId,geekCard:{geekName:'人才'}}))}}};
    }
    if(url.endsWith('/sourcing/search')){
      const body=JSON.parse(options.body);batches.push(body);examined+=body.candidates.length;
      saved={...saved,run:{id:'run1',state:'complete',version:1,wanted:5,examined,candidates:[],
        ...(examined===100?{stopReason:'no_matches_100',suggestions:['条件“办公经验”有 100 人未说明，建议核对工作经历。']}: {})}};
      return {data:saved};
    }
    throw Error('unexpected '+url);
  }});
  await app.enter('jobs');await app.click('AI 寻源');
  app.visible().find(n=>n.attributes['aria-label']==='获取人数').value='5';
  await app.click('一键 AI 获取');
  assert.equal(examined,100);assert.equal(batches.length,20);assert.equal(pages.at(-1),15);
  assert.equal(new Set(pages).size,pages.length);
  assert.match(app.text(),/已分析 100 人，仍无匹配，已停止寻找/);
  assert.match(app.text(),/办公经验.*100 人未说明/);
  assert.equal(app.calls.some(c=>c.url.endsWith('/sourcing/greet')),false);
  const before=app.calls.length;await flush();assert.equal(app.calls.length,before);
});
test('sourcing visibly reports progress and rejected cards next to the action without repeating reads or model submission',async()=>{
  const key='a'.repeat(64),security='a'.repeat(700)+'~'+'B'.repeat(45)+'==';
  const saved={id:'scope1',accountKey:key,jobId:'job-test-1',jobName:'工程师',version:2,profileState:'complete',
    references:[{id:'ref1',filename:'参考.pdf',characters:300}],profile:{},run:null};
  let finish;
  const app=setup({sourcing:true,api:async(url,options)=>{
    if(url.endsWith('/login/status'))return {data:{state:'complete',accountKey:key,capabilities:['job-jd','active-sourcing']}};
    if(url==='/api/v1/hr/jobs')return {data:fixtures.jobs};
    if(url.startsWith('/api/v1/hr/jobs?'))return {data:{ok:true,data:{job:{encryptId:saved.jobId,jobName:saved.jobName,postDescription:'系统开发与测试'}}}};
    if(url.startsWith('/api/v1/hr/sourcing?')||url.endsWith('/sourcing/save'))return {data:saved};
    if(url.includes('/recommendations?'))return {data:{ok:true,data:{hasMore:false,geekList:[
      {encryptGeekId:'geek1',encryptJobId:saved.jobId,securityId:security,geekCard:{geekName:'测试候选人'}}]}}};
    if(url.endsWith('/sourcing/search')){
      assert.equal(JSON.parse(options.body).candidates[0].securityId,security);
      await new Promise(resolve=>{finish=resolve;});
      throw Error('候选人卡片校验未通过，本次未调用 AI');
    }
    throw Error('unexpected '+url);
  }});
  await app.enter('jobs');await app.click('AI 寻源');
  app.visible().find(n=>n.attributes['aria-label']==='获取人数').value='5';
  const button=app.visible().find(n=>n.tagName==='button'&&n._text==='一键 AI 获取');
  const action=button.click();await flush();
  assert.equal(button._text,'正在获取…');assert.equal(button.disabled,true);
  assert.equal(button.attributes['aria-busy'],'true');
  await button.click();
  const card=app.visible().find(n=>n.className==='hr-sourcing-card'&&n.textContent.includes('02 / 人才寻源'));
  const status=all(card).find(n=>n.attributes.role==='status');
  assert.match(status.textContent,/已读取 1 位候选人/);
  finish();await action;await flush();
  assert.equal(button._text,'继续寻找');assert.equal(button.disabled,false);
  assert.equal(button.attributes['aria-busy'],'false');
  assert.match(status.textContent,/校验未通过/);assert.equal(status.className,'hr-error');
  assert.equal(app.calls.filter(c=>c.url.includes('/recommendations?')).length,1);
  assert.equal(app.calls.filter(c=>c.url.endsWith('/sourcing/search')).length,1);
  assert.equal(app.calls.filter(c=>c.url.endsWith('/sourcing/greet')).length,0);
});
test('sourcing reuses exact job demand analysis without uploading or regenerating; obsolete runs do not lock controls',async()=>{
  const key='a'.repeat(64);let online=true;
  let saved={id:'source1',accountKey:key,jobId:'job-test-1',jobName:'Java 后端开发工程师',version:3,
    references:[],profileState:'stale',profile:{summary:'旧画像'},run:{version:1,state:'running',candidates:[]}};
  const app=setup({sourcing:true,api:async(url,options)=>{
    const body=options?.body&&JSON.parse(options.body);
    if(url.endsWith('/login/status'))return {data:online?{state:'complete',accountKey:key,capabilities:['job-jd','active-sourcing']}:{state:'verification_pending',paused:true}};
    if(url==='/api/v1/hr/jobs')return {data:fixtures.jobs};
    if(url.startsWith('/api/v1/hr/jobs?'))return {data:{ok:true,data:{job:{encryptId:'job-test-1',jobName:saved.jobName,postDescription:'负责系统开发与测试'}}}};
    if(url.startsWith('/api/v1/hr/sourcing?')||url.endsWith('/sourcing/save'))return {data:structuredClone(saved)};
    if(url.startsWith('/api/v1/hr/demands?')){
      assert.equal(new URLSearchParams(url.split('?')[1]).get('jobId'),'job-test-1');
      assert.equal(new URLSearchParams(url.split('?')[1]).get('jobAccountKey'),key);
      return {data:{items:[{id:'d1',position:saved.jobName,date:'2026-10-08',aiStatus:'complete'}],pages:1}};
    }
    if(url.endsWith('/sourcing/demand')){
      assert.equal(body.demandId,'d1');
      saved={...saved,version:saved.version+1,profileState:'complete',profile:{summary:'需求池画像',suggestedJd:'系统开发',skills:['Java']},
        sourceDemand:{id:'d1',position:saved.jobName,version:2,referenceCount:0}};
      return {data:structuredClone(saved)};
    }
    throw Error('unexpected '+url);
  }});
  await app.enter('jobs');await app.click('AI 寻源');
  assert.match(app.text(),/招聘需求池/);
  const find=()=>app.visible().find(n=>n.tagName==='button'&&n._text==='一键 AI 获取');
  const select=app.visible().find(n=>n.attributes['aria-label']==='选择本职位招聘需求');
  assert.equal(app.visible().some(n=>n.type==='file'),false,'References belong in demand analysis');
  assert.equal(app.visible().some(n=>['使用岗位分析','刷新需求','前往招聘需求池'].includes(n._text)),false);
  assert.equal(select.disabled,false);
  online=false;
  const before=app.calls.filter(c=>c.url.endsWith('/login/status')).length;
  select.value='d1';await select.events.change();await flush();
  assert.equal(app.calls.filter(c=>c.url.endsWith('/login/status')).length,before,'Reusing cloud analysis needs no BOSS call');
  assert.equal(app.calls.filter(c=>c.url.endsWith('/sourcing/profile')).length,0);
  assert.equal(find().disabled,false);
  const details=app.visible().find(n=>n.tagName==='details'&&n.textContent.includes('查看完整画像与建议'));
  details.open=true;
  await app.click('刷新结果');
  assert.ok(app.visible().includes(details));assert.equal(details.open,true,'Polling retains expanded profile');
  await app.click('一键 AI 获取');
  assert.match(app.text(),/请先连接并登录/);
  assert.equal(app.calls.filter(c=>c.url.endsWith('/sourcing/search')||c.url.includes('/recommendations?')).length,0,'Candidate reads still require the actual active recruiter');
});
test('job refresh keeps cards during recovery, removes repeated errors, and reset renders fresh cards',async()=>{
  let fail=false,release;
  const app=setup({api:async url=>{
    if(url.endsWith('/login/status'))return {data:{state:'complete'}};
    if(url.endsWith('/jobs')){
      if(fail)await new Promise(resolve=>{release=resolve;});
      if(fail)throw Error('网络暂时中断');
      return {data:structuredClone(fixtures.jobs)};
    }
    throw Error('unexpected '+url);
  }});
  await app.enter('jobs');
  const card=app.visible().find(n=>n.className==='hr-job-card');
  assert.ok(card);
  fail=true;await app.enter('jobs');
  assert.ok(app.visible().includes(card),'Existing cards stay visible while reloading');
  release();await flush();assert.ok(app.visible().includes(card));
  await app.enter('jobs');release();await flush();
  assert.equal(app.visible().filter(n=>n._text==='网络暂时中断').length,1);
  fail=false;await app.enter('jobs');assert.ok(app.visible().includes(card));
  assert.equal(app.visible().filter(n=>n._text==='网络暂时中断').length,0);
  app.env.HrWorkspace.reset();await app.enter('jobs');
  const fresh=app.visible().find(n=>n.className==='hr-job-card');
  assert.ok(fresh);assert.notEqual(fresh,card);
});
test('origin filter composes with selected job and review status',async()=>{
  const app=setup({passive:true,api:async url=>({data:url.endsWith('/login/status')?{state:'complete'}:
    {items:[],jobs:[{id:'job1',name:'工程师',count:2}],counts:{pending:1,approved:1,rejected:0,all:2},total:0}})});
  await app.enter('resume-pool');await app.click('工程师 2');await app.click('主动邀约');await app.click('通过 1');
  assert.ok(app.calls.some(c=>c.url.includes('status=approved&jobId=job1&origin=active')));
});
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
test('resume rejection uses the managed default directly without a speech selector or an extra confirmation',async()=>{
  const row={id:'resume1',version:2,name:'测试人才',jobName:'算法工程师',canReview:true,status:'pending',aiState:'idle',resumeText:'原简历文字'};
  const speech={id:'reject1',version:3,name:'婉拒话术',content:'{姓名}，感谢关注{职位}。',kind:'reject',active:true,isDefault:true};
  const app=setup({passive:true,api:async(url,options)=>({data:
    url.endsWith('/login/status')?{state:'complete'}:
    url.endsWith('/resume-pool/review')?{id:row.id,status:'rejected',rejectionStatus:'queued'}:
    url.endsWith('/speeches')?{items:[speech,{...speech,id:'inactive',active:false},{...speech,id:'invite',kind:'invite'}]}:
    url.endsWith('/pool')?{projects:[]}:{items:[row],total:1}})});
  let previews=0;app.env.createHrResumePreview=()=>{previews++;return ()=>{};};
  await app.enter('resume-pool');await app.click('查看与审批');
  const reject=all(app.root).find(n=>n.tagName==='button'&&n._text==='不通过，自动婉拒');
  assert.equal(reject.disabled,false);assert.equal(previews,1);
  assert.equal(all(app.root).some(n=>n.attributes['aria-label']==='默认婉拒话术'),false);
  assert.doesNotMatch(app.text(),/不通过时自动发送|管理话术|感谢关注算法工程师/);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
  await app.click('不通过，自动婉拒');
  const writes=app.calls.filter(c=>c.options?.method==='POST');
  assert.equal(writes.length,1);assert.match(writes[0].url,/resume-pool\/review$/);
  assert.deepEqual(JSON.parse(writes[0].options.body),{id:row.id,version:row.version,action:'reject'});
  assert.ok(app.calls.every(c=>!c.url.includes('/speeches/save')&&!c.url.includes('/analyze')));
});
test('resume approval automatically attributes the job department without a manual selector',async()=>{
  const row={id:'resume1',version:1,name:'测试人才',jobName:'算法',canReview:true,status:'pending',aiState:'idle'};
  const app=setup({passive:true,api:async url=>({data:
    url.endsWith('/login/status')?{state:'complete'}:
    url.endsWith('/pool')?{departments:[{id:'dept-one',name:'研发部',status:'ACTIVE'},
      {id:'dept-disabled',name:'停用部',status:'DISABLED'}]}:
    url.endsWith('/speeches')?{items:[]}:
    url.startsWith('/api/v1/hr/pool?')?{...poolResult(),items:[{...poolResult().items[0],stage:'invited'}]}:
    {items:[row],total:1}})});
  await app.enter('pool');await app.click('二面 0');
  await app.enter('resume-pool');await app.click('查看与审批');
  assert.doesNotMatch(app.text(),/通过后归属部门|归属项目组|停用部/);
  assert.equal(app.visible().some(n=>n.tagName==='select'&&n.children.some(o=>o.value==='dept-one')),false);
  await app.click('通过，加入邀约');
  const writes=app.calls.filter(c=>c.options?.method==='POST');
  assert.equal(writes.length,1);
  assert.deepEqual(JSON.parse(writes[0].options.body),{id:row.id,version:1,action:'approve'});
  assert.equal(app.env.location.hash,'#hr-pool');
  assert.equal(app.links.find(n=>n.dataset.hrRoute==='pool').attributes['aria-current'],'page');
  const destination=new URL(app.calls.at(-1).url,'https://platform.test').searchParams;
  assert.equal(destination.get('stage'),'invited');
  assert.equal(destination.get('q'),'');assert.equal(destination.get('page'),'1');
});
test('missing managed default keeps rejection disabled and guides HR to speech management',async()=>{
  const row={id:'resume1',version:1,name:'测试人才',jobName:'算法工程师',canReview:true,status:'pending',aiState:'idle'};
  const speech={id:'reject1',version:1,name:'婉拒',kind:'reject',active:true,content:'感谢关注',isDefault:false};
  const app=setup({passive:true,api:async url=>({data:url.endsWith('/login/status')?{state:'complete'}:
    url.endsWith('/speeches')?{items:[speech,{...speech,id:'inactive',active:false,isDefault:true},
      {...speech,id:'invite',kind:'invite',isDefault:true}]}:
    url.endsWith('/pool')?{projects:[]}:{items:[row],total:1}})});
  await app.enter('resume-pool');await app.click('查看与审批');
  assert.equal(all(app.root).find(n=>n._text==='不通过，自动婉拒').disabled,true);
  assert.equal(all(app.root).find(n=>n._text==='通过，加入邀约').disabled,false);
  assert.match(app.text(),/请在话术管理中启用并设置默认婉拒话术/);
  assert.ok(app.calls.every(c=>!c.options?.method||c.options.method!=='POST'));
});
test('closed or unconfirmed job keeps its resume viewable but disables both decisions and new AI analysis',async()=>{
  for(const jobStatus of ['已关闭','待开放','审核中','状态待确认']){
    const row={id:'r1',version:1,name:'测试人才',jobName:'算法',status:'pending',aiState:'idle',jobStatus,canReview:false};
    const app=setup({passive:true,api:async url=>({data:url.endsWith('/login/status')?{state:'complete'}:
      url.endsWith('/speeches')?{items:[{id:'s1',kind:'reject',active:true,isDefault:true}]}:{items:[row],total:1}})});
    await app.enter('resume-pool');await app.click('查看与审批');
    for(const label of ['通过，加入邀约','不通过，自动婉拒','AI 辅助分析']){
      const control=app.visible().find(n=>n.tagName==='button'&&n._text===label);
      assert.equal(control.disabled,true);await control.events.click();
    }
    assert.match(app.text(),/仅可查看/);
    assert.equal(app.calls.some(c=>c.options?.method==='POST'),false);
    assert.ok(app.visible().some(n=>n.className==='hr-review-preview'));
  }
});
test('a late resume approval never returns HR from a different menu',async()=>{
  let finish;
  const row={id:'resume1',version:1,name:'测试人才',jobName:'算法',canReview:true,status:'pending',aiState:'idle'};
  const app=setup({passive:true,api:async url=>{
    if(url.endsWith('/resume-pool/review'))return new Promise(resolve=>{finish=resolve;});
    return {data:url.endsWith('/login/status')?{state:'complete'}:url.endsWith('/speeches')?{items:[]}:{items:[row],total:1}};
  }});
  await app.enter('resume-pool');await app.click('查看与审批');
  const pending=app.visible().find(n=>n._text==='通过，加入邀约').click();
  await flush();await app.enter('speeches');
  finish({data:{status:'approved'}});await pending;await flush();
  assert.equal(app.links.find(n=>n.dataset.hrRoute==='speeches').attributes['aria-current'],'page');
  assert.equal(app.links.find(n=>n.dataset.hrRoute==='pool').attributes['aria-current'],undefined);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,1);
  assert.ok(app.calls.every(c=>!c.url.startsWith('/api/v1/hr/pool?')));
});
test('a failed review response remains visible and never claims approval or retries sending',async()=>{
  const row={id:'resume1',version:1,name:'测试人才',jobName:'算法工程师',canReview:true,status:'pending',aiState:'idle'};
  const app=setup({passive:true,api:async url=>url.endsWith('/resume-pool/review')
    ?{success:false,message:'默认话术已停用，请重新配置',data:null}:{data:url.endsWith('/login/status')?{state:'complete'}:
      url.endsWith('/speeches')?{items:[{id:'speech1',kind:'reject',active:true,isDefault:true}]}:
      url.endsWith('/pool')?{projects:[]}:{items:[row],total:1}}});
  await app.enter('resume-pool');await app.click('查看与审批');await app.click('不通过，自动婉拒');
  assert.match(app.text(),/默认话术已停用，请重新配置/);
  assert.ok(all(app.root).find(n=>n.className.includes('hr-resume-review')).open);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,1);
});
test('managing rejection speech closes the resume dialog and leaves the speech editor accessible',async()=>{
  const row={id:'resume1',name:'测试人才',jobName:'算法工程师',status:'pending',aiState:'idle'};
  const app=setup({passive:true,api:async url=>({data:url.endsWith('/login/status')?{state:'complete'}:
    url.endsWith('/speeches')?{items:[]}:url.endsWith('/pool')?{projects:[]}:{items:[row],total:1}})});
  await app.enter('resume-pool');await app.click('查看与审批');
  const detail=all(app.root).find(n=>n.className.includes('hr-resume-review'));
  assert.equal(detail.open,true);await app.click('前往话术管理');
  assert.equal(detail.open,false);assert.equal(app.env.location.hash,'#hr-speeches');
  await app.click('新建话术');
  assert.ok(all(app.root).some(n=>n.tagName==='dialog'&&n.open));
  assert.ok(app.calls.every(c=>!c.url.includes('/review')));
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
const interviewPreview=(talent)=>({talentId:talent.id,name:talent.name,version:1,stage:'invited',
  packageReady:true,config:{verified:true,version:1},resumes:[{id:'r1',filename:'测试人才.pdf',jobName:'算法'}],
  demands:[{id:'d1',position:'算法',department:'研发',version:1,resumeIds:['r1'],questionsReady:true,
    questionCount:6,recipientReady:true,recipient:'面试负责人',recipientId:'manager1'}],handoff:null});
test('invitation uses an active template and sends edited text once to the exact talent',async()=>{
  const talent={...poolResult().items[0],source:'passive',stage:'invited',position:'算法工程师'};
  let messages=[];
  const app=setup({api:async(url,options)=>{
    if(url.endsWith('/speeches'))return {data:{items:[
      {id:'invite1',kind:'invite',active:true,name:'面试邀约',content:'您好，{姓名}，想约您交流{职位}。'},
      {id:'disabled',kind:'invite',active:false,content:'不可使用'},
      {id:'reject',kind:'reject',active:true,content:'不可使用'}]}};
    if(url.includes('/pool/messages')){
      if(options?.method==='POST'){messages=[{id:'m1',status:'queued',message:JSON.parse(options.body).message}];return {data:messages[0]};}
      return {data:{items:messages}};
    }
    return {data:{...poolResult(),items:[talent]}};
  }});
  await app.enter('pool');await app.click('发送邀约');
  const dialog=all(app.root).find(n=>n.className.includes('hr-invite-dialog'));
  const content=all(dialog).find(n=>n.tagName==='textarea');
  assert.equal(content.value,'您好，测试人才，想约您交流算法工程师。');
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
  content.value='请问周三下午方便参加算法工程师岗位面试吗？';content.events.input();
  await app.click('发送邀约到 BOSS');await app.click('发送邀约到 BOSS');
  const writes=app.calls.filter(c=>c.options?.method==='POST');assert.equal(writes.length,1);
  const payload=JSON.parse(writes[0].options.body);assert.equal(payload.talentId,talent.id);
  assert.equal(payload.message,content.value);assert.match(payload.requestId,/^[a-f0-9-]{36}$/);
  assert.match(dialog.textContent,/等待助手发送/);
  assert.equal(all(dialog).find(n=>n._text==='发送邀约到 BOSS').disabled,true);
  assert.ok(app.calls.every(c=>!c.url.endsWith('/interview/start')));
});
test('uncertain invitation blocks another send and lost write responses are never replayed',async()=>{
  const talent={...poolResult().items[0],source:'passive',stage:'invited'};
  for(const uncertain of [true,false]){
    const app=setup({api:async(url,options)=>{
      if(url.endsWith('/speeches'))return {data:{items:[{id:'i1',kind:'invite',active:true,name:'邀约',content:'邀请{姓名}面试'}]}};
      if(url.includes('/pool/messages')){
        if(options?.method==='POST')throw Error('响应丢失');
        return {data:{items:uncertain?[{status:'uncertain',message:'待核对'}]:[]}};
      }
      return {data:{...poolResult(),items:[talent]}};
    }});
    await app.enter('pool');await app.click('发送邀约');await app.click('发送邀约到 BOSS');
    const dialog=all(app.root).find(n=>n.className.includes('hr-invite-dialog'));
    assert.equal(all(dialog).find(n=>n._text==='发送邀约到 BOSS').disabled,true);
    assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,uncertain?0:1);
    if(!uncertain)assert.match(dialog.textContent,/不会自动重发/);
  }
});
test('speech management exposes four used purposes and supplies an editable invitation draft',async()=>{
  const app=setup({passive:true,api:async url=>({data:url.endsWith('/speeches')?{items:[
    {id:'i1',kind:'invite',active:true,name:'邀请交流',content:'邀请{姓名}面试'},
    {id:'unused',kind:'general',active:true,name:'未接入话术',content:'旧内容'}]}:{items:[]}})});
  await app.enter('speeches');assert.doesNotMatch(app.text(),/未接入话术|其他沟通/);
  await app.click('邀约对话 1');assert.match(app.text(),/邀请交流/);
  await app.click('新建话术');
  const dialog=all(app.root).find(n=>n.className.includes('hr-speech-dialog'));
  assert.equal(all(dialog).find(n=>n.tagName==='select').value,'invite');
  assert.match(all(dialog).find(n=>n.tagName==='textarea').value,/参加面试/);
  assert.equal(all(dialog).find(n=>n.tagName==='select').children.length,4);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
});
test('confirmed first handoff opens first pending and closes its dialog without a duplicate send',async()=>{
  let talent={...poolResult().items[0],stage:'invited'},handoff=null;
  const app=setup({api:async(url,options)=>{
    if(url.endsWith('/interview/start')){
      handoff={id:'handoff1',status:'sent'};talent={...talent,stage:'first',version:2};
      return {data:handoff};
    }
    if(url.includes('/interview?'))return {data:{...interviewPreview(talent),stage:talent.stage,handoff}};
    return {data:{...poolResult(),items:[talent]}};
  }});
  await app.enter('pool');await app.click('开始一面');
  const dialog=all(app.root).find(n=>n.className==='hr-pool-dialog hr-interview-dialog');
  assert.equal(dialog.open,false);
  const destination=new URL(app.calls.at(-1).url,'https://platform.test').searchParams;
  assert.equal(destination.get('stage'),'first');assert.equal(destination.get('outcome'),'pending');
  assert.equal(app.env.location.hash,'#hr-pool');
  assert.equal(app.links.find(n=>n.dataset.hrRoute==='pool').attributes['aria-current'],'page');
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,1);
});
test('optional second interview selects a verified person and sends only after explicit click',async()=>{
  const talent={...poolResult().items[0],stage:'second'};
  const preview={...interviewPreview(talent),round:'second',stage:'second',
    recipients:[{userId:'manager2',name:'二面老师',department:'研发',bindingKey:'department:dept2:1'}]};
  let handoff=null;
  const app=setup({api:async(url,options)=>{
    if(url.endsWith('/interview/start')){handoff={id:'second-send',status:'preparing',round:'second'};return {data:handoff};}
    if(url.includes('/pool/interview?'))return {data:{...preview,handoff}};
    return {data:{...poolResult(),items:[talent]}};
  }});
  await app.enter('pool');await app.click('发送二面资料');
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
  assert.ok(app.calls.some(c=>c.url.includes('round=second')));
  const dialog=all(app.root).find(n=>n.className==='hr-pool-dialog hr-interview-dialog');
  const send=all(dialog).find(n=>n.tagName==='button'&&n._text==='发送二面资料');
  assert.equal(send.disabled,true);
  const select=all(dialog).find(n=>n.tagName==='select'&&n.children.some(o=>o.value==='department:dept2:1'));
  select.value='department:dept2:1';select.events.change();
  assert.equal(send.disabled,false);
  await send.click();await flush();
  const writes=app.calls.filter(c=>c.options?.method==='POST');
  assert.equal(writes.length,1);
  const body=JSON.parse(writes[0].options.body);
  assert.equal(body.round,'second');assert.equal(body.recipientId,'manager2');
  assert.match(dialog.textContent,/正在准备二面资料包/);
});
test('second interview opinions are available directly without choosing or sending DingTalk materials',async()=>{
  const talent={...poolResult().items[0],stage:'second'};
  const app=setup({api:async()=>({data:{...poolResult(),items:[talent]}})});
  await app.enter('pool');await app.click('审核');
  assert.match(app.text(),/填写入职安排/);
  assert.equal(app.calls.some(c=>c.url.includes('/interview?')||c.url.endsWith('/interview/start')),false);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
});
const directoryResult=()=>({state:'complete',configVersion:1,employeeCount:45,departmentCount:2,
  departments:[{id:'2',name:'研发'}],items:[{userId:'employee-2',name:'测试老师',department:'研发'}],
  total:45,page:1,pages:3});
test('second interview queries company directory and verifies selected identity without sending',async()=>{
  const talent={...poolResult().items[0],stage:'second'};
  const app=setup({api:async(url,options)=>{
    if(url.includes('/dingtalk/directory?')){
      const params=new URL(url,'https://platform.test').searchParams;
      return {data:{...directoryResult(),page:Number(params.get('page'))}};
    }
    if(url.endsWith('/interview/identity'))return {data:{userId:'employee-2',name:'测试老师',
      bindingKey:'identity',identityProof:'verified-proof'}};
    if(url.includes('/interview?'))return {data:{...interviewPreview(talent),stage:'second'}};
    return {data:{...poolResult(),items:[talent]}};
  }});
  await app.enter('pool');await app.click('发送二面资料');await flush();
  assert.match(app.text(),/可查询 45 人/);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
  const directory=app.visible().find(n=>n.className==='hr-directory');
  const q=all(directory).find(n=>n.tagName==='input');
  q.value='研发';await q.events.keydown({key:'Enter',preventDefault(){}});await flush();
  const dept=all(directory).find(n=>n.tagName==='select');dept.value='2';await dept.events.change();await flush();
  const next=all(directory).find(n=>n._text==='下一页');await next.click();await flush();
  const params=new URL(app.calls.at(-1).url,'https://platform.test').searchParams;
  assert.equal(params.get('q'),'研发');assert.equal(params.get('departmentId'),'2');assert.equal(params.get('page'),'2');
  const person=all(directory).find(n=>n.dataset.userId==='employee-2');
  await person.click();await flush();
  const writes=app.calls.filter(c=>c.options?.method==='POST');
  assert.equal(writes.length,1);assert.ok(writes[0].url.endsWith('/interview/identity'));
  assert.equal(JSON.parse(writes[0].options.body).userId,'employee-2');
  assert.match(app.text(),/已识别：测试老师/);
  const dialog=app.visible().find(n=>n.className.includes('hr-interview-dialog'));
  assert.equal(all(dialog).find(n=>n._text==='发送二面资料').disabled,false);
  assert.equal(app.calls.some(c=>c.url.endsWith('/interview/start')),false);
  app.env.HrWorkspace.reset();
  assert.doesNotMatch(app.text(),/测试老师/);assert.equal(all(directory).some(n=>n.dataset.userId),false);
});
test('late directory response is discarded after closing and reopening for another candidate',async()=>{
  let resolve,count=0;
  const talent={...poolResult().items[0],stage:'second'};
  const app=setup({api:async url=>{
    if(url.includes('/dingtalk/directory?')){
      if(++count===1)return new Promise(r=>resolve=r);
      return {data:{...directoryResult(),items:[],total:0}};
    }
    return {data:url.includes('/interview?')?{...interviewPreview(talent),stage:'second'}:
      {...poolResult(),items:[talent]}};
  }});
  await app.enter('pool');await app.click('发送二面资料');
  await app.click('关闭');await app.click('发送二面资料');
  resolve({data:directoryResult()});await flush();
  assert.doesNotMatch(app.text(),/测试老师/);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
});
test('directory permission failure keeps optional interview opinion and phone fallback available',async()=>{
  const talent={...poolResult().items[0],stage:'second'};
  const app=setup({api:async url=>({data:url.includes('/dingtalk/directory?')
    ?{...directoryResult(),state:'failed',error:'通讯录权限不足，请授权',items:[]}
    :url.includes('/interview?')?{...interviewPreview(talent),stage:'second'}:
    {...poolResult(),items:[talent]}})});
  await app.enter('pool');await app.click('发送二面资料');await flush();
  assert.match(app.text(),/通讯录权限不足/);
  const select=app.visible().find(n=>n.tagName==='select'&&n.children.some(c=>c.value==='mobile'));
  select.value='mobile';select.events.change();
  assert.ok(app.visible().some(n=>n._text==='识别面试人员'));
  select.value='';select.events.change();
  const dialog=app.visible().find(n=>n.className.includes('hr-interview-dialog'));
  assert.equal(all(dialog).find(n=>n._text==='发送二面资料').disabled,true);
  await app.click('关闭');await app.click('审核');
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
});
const onboardingValues=()=>({name:'测试人才',startDate:'2026-10-20',position:'算法工程师',supervisor:'主管',
  department:'研发',company:'测试公司',fixedSalary:'22000',performanceSalary:'0',commission:'无',mentorName:'导师',joined:false});
test('second approval opens arrangements first and submits opinion and onboarding atomically',async()=>{
  const talent={...poolResult().items[0],stage:'second'};
  const app=setup({onboarding:true,api:async(url,options)=>({data:
    url.includes('/onboarding?talentId=')?{defaults:onboardingValues()}:
    url.includes('/onboarding?')?{items:[],total:0,page:1,pages:1}:
    url.endsWith('/interview/review')?{stage:'onboarding'}:{...poolResult(),items:[talent]}})});
  await app.enter('pool');await app.click('审核');
  const review=all(app.root).find(n=>n.className.includes('hr-interview-review-dialog'));
  const opinion=all(review).find(n=>n.tagName==='textarea');opinion.value='建议通过，进入入职安排';opinion.events.input();
  await app.click('通过');
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
  const dialog=all(app.root).find(n=>n.className==='hr-pool-dialog hr-onboarding-dialog');
  assert.equal(dialog.open,true);assert.match(dialog.textContent,/归属公司|固定薪资|导师名称/);
  const form=all(dialog).find(n=>n.tagName==='form');
  await form.events.submit({preventDefault(){}});await flush();
  const writes=app.calls.filter(c=>c.options?.method==='POST');
  assert.equal(writes.length,1);assert.ok(writes[0].url.endsWith('/interview/review'));
  const data=JSON.parse(writes[0].options.body);
  assert.equal(data.round,'second');assert.equal(data.onboarding.fixedSalary,'22000');
  assert.equal(data.onboarding.joined,false);
  assert.match(app.env.location.hash,/hr-onboarding/);
  assert.equal(app.links.find(n=>n.dataset.hrRoute==='onboarding').attributes['aria-current'],'page');
  assert.equal(app.links.find(n=>n.dataset.hrRoute==='pool').attributes['aria-current'],undefined);
  assert.equal(app.calls.some(c=>c.url.endsWith('/onboarding/send')),false);
});
test('cancelling arrangements preserves second opinion and does not mark approval',async()=>{
  const talent={...poolResult().items[0],stage:'second'};
  const app=setup({onboarding:true,api:async url=>({data:url.includes('/onboarding?talentId=')
    ?{defaults:onboardingValues()}:{...poolResult(),items:[talent]}})});
  await app.enter('pool');await app.click('审核');
  const review=all(app.root).find(n=>n.className.includes('hr-interview-review-dialog'));
  const opinion=all(review).find(n=>n.tagName==='textarea');opinion.value='保留这条意见';opinion.events.input();
  await app.click('通过');await app.click('取消');
  assert.equal(opinion.value,'保留这条意见');assert.equal(review.open,true);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
});
test('onboarding can update joining independently and mentor notification stays explicit',async()=>{
  const row={...onboardingValues(),talentId:'t1',version:1};
  const app=setup({onboarding:true,api:async url=>({data:url.endsWith('/onboarding/save')?{saved:true}:
    {items:[row],total:1,page:1,pages:1}})});
  await app.enter('onboarding');
  assert.equal(app.visible().find(n=>n._text==='通知导师').disabled,true);
  await app.click('编辑');
  const dialog=all(app.root).find(n=>n.className==='hr-pool-dialog hr-onboarding-dialog');
  const joined=all(dialog).find(n=>n.tagName==='select');joined.value='true';
  await all(dialog).find(n=>n.tagName==='form').events.submit({preventDefault(){}});await flush();
  const writes=app.calls.filter(c=>c.options?.method==='POST');
  assert.equal(writes.length,1);assert.ok(writes[0].url.endsWith('/onboarding/save'));
  assert.equal(JSON.parse(writes[0].options.body).joined,true);
  assert.equal(app.calls.some(c=>c.url.endsWith('/onboarding/send')),false);
});
test('onboarding defaults to all and explicit departure selects departed without sending',async()=>{
  let row={...onboardingValues(),talentId:'t1',version:1,joined:true};
  const app=setup({onboarding:true,confirm:()=>true,api:async(url,options)=>{
    if(url.endsWith('/onboarding/depart')){
      const body=JSON.parse(options.body);assert.equal(body.talentId,'t1');assert.equal(body.version,1);
      row={...row,departed:true,version:2};return {data:{saved:true,departed:true}};
    }
    return {data:{items:[row],total:1,page:1,pages:1}};
  }});
  await app.enter('onboarding');
  assert.equal(new URL(app.calls.at(-1).url,'https://platform.test').searchParams.get('status'),'all');
  const tabs=app.visible().find(n=>n.className==='hr-pool-tabs');
  assert.deepEqual(tabs.children.map(n=>n._text),['全部','已入职','已离职']);
  assert.ok(!app.visible().some(n=>n._text==='入职安排'));
  await app.click('员工离职');
  assert.equal(new URL(app.calls.at(-1).url,'https://platform.test').searchParams.get('status'),'departed');
  assert.equal(app.visible().find(n=>n._text==='通知导师').disabled,true);
  assert.equal(app.visible().find(n=>n._text==='员工离职').disabled,true);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,1);
  await app.click('编辑');
  const dialog=all(app.root).find(n=>n.className.includes('hr-onboarding-dialog'));
  assert.equal(all(dialog).find(n=>n.tagName==='select').disabled,true);
});
test('mentor defaults to department leader and company directory allows another verified person',async()=>{
  const row={...onboardingValues(),talentId:'t1',version:1,joined:true};
  const leader={userId:'leader',name:'部门组长',bindingKey:'department:dept:1'};
  const app=setup({onboarding:true,api:async(url,options)=>{
    if(url.includes('/onboarding/recipients?')){
      assert.match(url,/talentId=t1/);
      return {data:{config:{verified:true,version:1},items:[leader],defaultRecipient:leader}};
    }
    if(url.includes('/dingtalk/directory?'))return {data:directoryResult()};
    if(url.endsWith('/onboarding/identity'))return {data:{userId:'employee-2',name:'测试老师',bindingKey:'identity',identityProof:'verified-proof'}};
    if(url.endsWith('/onboarding/send'))return {data:{status:'preparing'}};
    return {data:{items:[row],total:1,page:1,pages:1}};
  }});
  await app.enter('onboarding');await app.click('通知导师');
  const dialog=all(app.root).find(n=>n.className.includes('hr-mentor-dialog'));
  const recipient=all(dialog).find(n=>n.tagName==='select');
  assert.equal(recipient.value,leader.bindingKey);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
  recipient.value='directory';await recipient.events.change();await flush();
  const directory=all(dialog).find(n=>n.className==='hr-directory');
  const person=all(directory).find(n=>n.dataset.userId==='employee-2');
  await person.click();await flush();
  assert.equal(app.calls.some(c=>c.url.endsWith('/onboarding/send')),false);
  assert.match(app.text(),/已选择导师：测试老师/);
  await app.click('发送导师提醒');
  const body=JSON.parse(app.calls.find(c=>c.url.endsWith('/onboarding/send')).options.body);
  assert.equal(body.recipientId,'employee-2');assert.equal(body.updateMentor,true);
  assert.equal(body.identityProof,'verified-proof');
});
test('closing mentor directory discards a late personnel response and sends nothing',async()=>{
  const row={...onboardingValues(),talentId:'t1',version:1,joined:true};
  let resolve;
  const app=setup({onboarding:true,api:async url=>{
    if(url.includes('/onboarding/recipients?'))return {data:{config:{verified:true,version:1},items:[],defaultRecipient:null}};
    if(url.includes('/dingtalk/directory?'))return new Promise(r=>{resolve=r;});
    return {data:{items:[row],total:1,page:1,pages:1}};
  }});
  await app.enter('onboarding');await app.click('通知导师');
  const dialog=all(app.root).find(n=>n.className.includes('hr-mentor-dialog'));
  assert.equal(all(dialog).find(n=>n.tagName==='button'&&n._text==='发送导师提醒').disabled,true);
  await all(dialog).find(n=>n._text==='关闭').click();
  resolve({data:directoryResult()});await flush();
  assert.equal(all(dialog).some(n=>n.dataset.userId),false);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
});
test('second approval chooses a mentor from DingTalk and saves their unique identity with arrangements',async()=>{
  const talent={...poolResult().items[0],stage:'second'};
  const app=setup({onboarding:true,api:async url=>({data:
    url.endsWith('/pool/dingtalk')?{config:{verified:true,version:1}}:
    url.includes('/pool/dingtalk/directory?')?directoryResult():
    url.includes('/onboarding?talentId=')?{defaults:{...onboardingValues(),mentorName:'部门组长'}}:
    url.includes('/onboarding?')?{items:[],total:0,page:1,pages:1}:
    url.endsWith('/interview/review')?{stage:'onboarding'}:{...poolResult(),items:[talent]}})});
  await app.enter('pool');await app.click('审核');
  const opinion=all(app.root).find(n=>n.className.includes('hr-interview-review-dialog'));
  const text=all(opinion).find(n=>n.tagName==='textarea');text.value='符合要求';text.events.input();
  await app.click('通过');
  await app.click('选择钉钉导师');
  const picker=all(app.root).find(n=>n.className.includes('hr-mentor-picker'));
  await all(picker).find(n=>n.dataset.userId==='employee-2').click();
  assert.equal(app.calls.some(c=>c.options?.method==='POST'),false);
  const dialog=all(app.root).find(n=>n.className.includes('hr-onboarding-dialog'));
  await all(dialog).find(n=>n.tagName==='form').events.submit({preventDefault(){}});await flush();
  const body=JSON.parse(app.calls.find(c=>c.url.endsWith('/interview/review')).options.body);
  assert.equal(body.onboarding.mentorName,'测试老师');assert.equal(body.onboarding.mentorUserId,'employee-2');
  assert.equal(body.onboarding.mentorConfigVersion,1);
  assert.equal(app.calls.some(c=>c.url.endsWith('/onboarding/send')),false);
});
test('BI renders six K periods and export follows the applied scope without BOSS actions',async()=>{
  const app=setup({bi:true,api:async url=>{
    if(url.endsWith('/login/status'))return {data:{state:'complete'}};
    const params=new URL(url,'https://platform.test').searchParams;
    return {data:{filters:Object.fromEntries(params),metrics:{resumes:5,pending:2,averageScore:88},
      options:{departments:[],jobs:[]},departments:[],jobs:[],sources:[],stages:[],
      trend:[{date:'2026-10',resumes:5,firstReviews:2,secondReviews:1}],
      generatedAt:'2026-10-09T12:00:00+08:00',definitions:['平台记录口径']}};
  }});
  await app.enter('overview');assert.match(app.text(),/招聘经营看板/);
  const select=all(app.root).find(n=>n.tagName==='select'&&n.children.some(o=>o.value==='halfyear'));
  assert.equal(select.children.length,6);select.value='quarter';await select.events.change();await flush();
  assert.ok(app.calls.some(c=>c.url.includes('granularity=quarter')));
  assert.match(app.text(),/季度 K · 招聘进展/);
  const link=all(app.root).find(n=>n.tagName==='a'&&n.textContent.includes('导出 Excel 汇总与明细'));
  assert.equal(new URL(link.href,'https://platform.test').searchParams.get('granularity'),'quarter');
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
  assert.ok(app.calls.every(c=>c.url.includes('/bi?')||c.url.endsWith('/login/status')));
});
const biFixture=params=>({filters:Object.fromEntries(params),metrics:{resumes:10,pending:5,approved:4,rejected:1,
  talents:3,joined:1,examined:20,qualified:6,greetings:2,aiComplete:2,averageScore:88,
  resumeApprovalRate:80,firstPassRate:50,secondPassRate:null},
  options:{departments:[],jobs:[]},
  departments:[{name:'研发部',resumes:10,approved:4}],jobs:[{name:'工程师',resumes:10,pending:5,approved:4,firstReviews:2,secondReviews:0,joined:1}],
  sources:[{name:'主动邀约',count:3},{name:'被动邀约',count:7}],
  stages:[{name:'一面',count:2},{name:'已入职',count:1}],aging:[{name:'0–3 天',count:5}],
  trend:[{date:'2026-10-08',resumes:4,firstReviews:1,secondReviews:0},{date:'2026-10-09',resumes:6,firstReviews:1,secondReviews:0}],
  generatedAt:'2026-10-09T12:00:00+08:00',definitions:['平台记录口径']});
function chartMock(){
  const records=[];
  return {records,library:{init(node,_theme,settings){
    const record={node,settings,disposed:false,options:null,updates:0};records.push(record);
    return {setOption(options){record.options=options;record.updates++;},on(){},resize(){},dispose(){record.disposed=true;}};
  }}};
}
test('BI creates nine real-data charts and preserves chart mode and series across K changes',async()=>{
  const mock=chartMock();
  const app=setup({bi:true,echarts:mock.library,api:async url=>({data:url.endsWith('/login/status')?{state:'complete'}:
    biFixture(new URL(url,'https://platform.test').searchParams)})});
  await app.enter('overview');assert.equal(mock.records.length,9);
  assert.ok(mock.records.every(r=>r.settings.renderer==='svg'&&r.options.tooltip.renderMode==='richText'));
  const trend=mock.records.find(r=>r.node.dataset.chart==='招聘周期趋势');
  assert.deepEqual(Array.from(trend.options.series[0].data),[4,6]);
  const ring=mock.records.find(r=>r.node.dataset.chart==='邀约来源环形图');
  assert.deepEqual(Array.from(ring.options.series[0].data,r=>r.value),[3,7]);
  const heat=mock.records.find(r=>r.node.dataset.chart==='职位进展热力图');
  assert.deepEqual(Array.from(heat.options.series[0].data,r=>r[2]),[10,5,4,2,0,1]);
  await app.click('柱状');assert.equal(trend.options.series[0].type,'bar');
  const series=all(app.root).find(n=>n.attributes['aria-label']==='趋势指标');
  series.value='AI 寻源与主动联系';await series.events.change();await flush();
  assert.equal(trend.options.series[0].name,'评估人次');
  await app.click('半年 K');assert.ok(mock.records.slice(0,9).every(r=>r.disposed));
  const updated=mock.records.slice(9).find(r=>r.node.dataset.chart==='招聘周期趋势');
  assert.equal(updated.options.series[0].type,'bar');assert.equal(updated.options.series[0].name,'评估人次');
  assert.match(app.text(),/半年 K · 招聘进展/);
  assert.equal(all(app.root).filter(n=>n.className.includes('hr-bi-metric')&&n.tagName==='button').length,24);
  await app.enter('onboarding');assert.ok(mock.records.every(r=>r.disposed));
});
test('BI clears stale data and export on failed scope changes and ignores older overlapping responses',async()=>{
  const mock=chartMock(),pending=[];
  const app=setup({bi:true,echarts:mock.library,api:async url=>{
    if(url.endsWith('/login/status'))return {data:{state:'complete'}};
    const params=new URL(url,'https://platform.test').searchParams;
    if(params.get('granularity')==='month')throw Error('读取失败');
    if(['week','year'].includes(params.get('granularity')))return new Promise(resolve=>pending.push({resolve,params}));
    return {data:biFixture(params)};
  }});
  await app.enter('overview');await app.click('月 K');assert.match(app.text(),/读取失败/);
  assert.equal(mock.records.every(r=>r.disposed),true);
  assert.equal(all(app.root).filter(n=>n.className==='hr-bi-plot').length,0);
  assert.equal(all(app.root).find(n=>n.tagName==='a'&&n.textContent.includes('导出 Excel')).hidden,true);
  // Recover with the persistent refresh action, then overlap two user-selected scopes.
  const cycle=all(app.root).find(n=>n.tagName==='select'&&n.children.some(o=>o.value==='halfyear'));
  cycle.value='day';await app.visible().find(n=>n.className==='hr-bi-refresh').click();await flush();
  cycle.value='week';const older=cycle.events.change();
  cycle.value='year';const newer=cycle.events.change();await flush();
  pending[1].resolve({data:biFixture(pending[1].params)});await newer;await flush();
  pending[0].resolve({data:biFixture(pending[0].params)});await older;await flush();
  assert.match(app.text(),/年 K · 招聘进展/);assert.doesNotMatch(app.text(),/周 K · 招聘进展/);
  const link=all(app.root).find(n=>n.tagName==='a'&&n.textContent.includes('导出 Excel'));
  assert.equal(new URL(link.href,'https://platform.test').searchParams.get('granularity'),'year');
});
test('empty BI shows zero counts and missing rates without inventing a successful cohort',async()=>{
  const mock=chartMock();
  const app=setup({bi:true,echarts:mock.library,api:async url=>{
    if(url.endsWith('/login/status'))return {data:{state:'complete'}};
    const data=biFixture(new URL(url,'https://platform.test').searchParams);
    data.metrics={resumes:0,pending:0,talents:0,joined:0,resumeApprovalRate:null,averageScore:null};
    data.sources=[{name:'主动邀约',count:0},{name:'被动邀约',count:0}];
    data.departments=[];data.jobs=[];data.stages=[];data.aging=[];data.trend=[];
    return {data};
  }});
  await app.enter('overview');assert.equal(mock.records.length,9);
  const ring=mock.records.find(r=>r.node.dataset.chart==='邀约来源环形图');
  assert.equal(ring.options.series[0].silent,true);
  assert.equal(ring.options.graphic[0].style.text,'0');
  assert.equal(ring.options.series[0].data[0].tooltip.show,false);
  const rates=mock.records.find(r=>r.node.dataset.chart==='审批和面试通过率图');
  assert.equal(rates.options.tooltip.formatter({dataIndex:0}),'简历：暂无已处理记录');
  assert.equal(rates.options.series[0].label.formatter({dataIndex:0,value:0}),'—');
  assert.ok(app.text().includes('所选范围暂无数据'));
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
});
test('resume analysis uses resolved job JD and reports preflight errors immediately',async()=>{
  const row={id:'resume1',version:1,name:'测试人才',jobName:'算法',canReview:true,status:'pending',aiState:'idle',
    resumeText:'提取的简历正文'.repeat(10),jobDescription:'对应岗位的职责和任职要求',jobSource:'招聘需求池'};
  const app=setup({passive:true,api:async(url,options)=>{
    if(url.endsWith('/analyze'))throw Error('驱动模型连接失败，请核对配置');
    return {data:url.endsWith('/login/status')?{state:'complete'}:
      url.endsWith('/speeches')?{items:[]}:
      url.endsWith('/pool')?{departments:[]}:{items:[row],total:1}};
  }});
  await app.enter('resume-pool');await app.click('查看与审批');
  assert.match(app.text(),/JD 来自招聘需求池/);
  await app.click('AI 辅助分析');
  const request=app.calls.find(c=>c.url.endsWith('/analyze'));
  assert.equal(JSON.parse(request.options.body).jobDescription,row.jobDescription);
  assert.match(app.text(),/驱动模型连接失败/);
  assert.equal(app.visible().find(n=>n._text==='AI 辅助分析').disabled,false);
});
test('a stalled resume download times out with a retry instead of remaining on loading',async()=>{
  const timeouts=[];
  const app=setup({setTimeout:(fn,ms)=>{timeouts.push({fn,ms});return timeouts.length;},
    fetch:(_url,options)=>new Promise((_resolve,reject)=>options.signal.addEventListener('abort',()=>reject(Error('aborted'))))});
  app.env.AbortController=AbortController;
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../hr-resume-preview.js'),'utf8'),app.env);
  const root=new Node(),el=(tag,text,parent,className)=>{const node=new Node(tag);node.textContent=text;node.className=className||'';parent.append(node);return node;};
  const button=(text,parent,action)=>{const node=el('button',text,parent);node.addEventListener('click',action);return node;};
  const dispose=app.env.createHrResumePreview({root,element:el,button,row:{extension:'pdf',fileUrl:'/private-resume',fileName:'简历.pdf'}});
  await flush();
  timeouts.find(t=>t.ms===45000).fn();await flush();
  assert.match(root.textContent,/简历加载超时/);
  assert.equal(all(root).find(n=>n._text==='重新加载').hidden,false);
  dispose();
});
test('start first interview sends exact linked packet once without BOSS message and shows progress',async()=>{
  const talent={...poolResult().items[0],stage:'invited',source:'active'};
  let handoff=null;
  const app=setup({api:async(url,options)=>{
    if(url.endsWith('/login/status'))return {data:{state:'complete'}};
    if(url.endsWith('/interview/start')){handoff={id:'i1',status:'preparing'};return {data:handoff};}
    if(url.includes('/pool/interview?'))return {data:{...interviewPreview(talent),handoff}};
    return {data:{...poolResult(),items:[talent]}};
  }});
  await app.enter('pool');
  await app.click('开始一面');
  const calls=app.calls.filter(c=>c.url.endsWith('/interview/start')&&c.options);
  assert.equal(calls.length,1);
  const body=JSON.parse(calls[0].options.body);
  assert.equal(body.talentId,talent.id);assert.equal(body.demandId,'d1');assert.equal(body.recipientId,'manager1');
  assert.match(app.text(),/正在准备一面资料包/);
  assert.ok(app.calls.every(c=>!c.url.includes('/pool/messages')));
  const send=all(app.root).find(n=>n._text==='发送一面资料');
  assert.equal(send.disabled,true);
});
test('missing DingTalk configuration explains setup and never queues an interview',async()=>{
  const talent={...poolResult().items[0],stage:'invited',source:'passive'};
  const app=setup({api:async url=>({data:url.includes('/pool/interview?')
    ?{...interviewPreview(talent),config:{verified:false},demands:[]}
    :{...poolResult(),items:[talent]}})});
  await app.enter('pool');await app.click('开始一面');
  assert.match(app.text(),/请先配置钉钉企业内部应用/);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
});
test('delivered first interview hides setup and send actions and retains the close button',async()=>{
  const talent={...poolResult().items[0],stage:'first',source:'passive'};
  const app=setup({api:async url=>({data:url.includes('/pool/interview?')
    ?{...interviewPreview(talent),stage:'first',handoff:{id:'i1',status:'sent'}}
    :{...poolResult(),items:[talent]}})});
  await app.enter('pool');
  assert.ok(!app.visible().some(n=>n.tagName==='button'&&n._text==='一面资料'));
  await app.click(talent.name);
  const dialog=all(app.root).find(n=>n.className==='hr-pool-dialog hr-interview-dialog');
  const buttons=app.visible().filter(n=>all(dialog).includes(n)&&n.tagName==='button');
  assert.deepEqual(buttons.map(n=>n.textContent),['关闭']);
  assert.match(dialog.textContent,/人才详情/);
  assert.match(dialog.textContent,/已送达负责人/);
  const download=all(dialog).find(n=>n.tagName==='a'&&n._text==='下载资料 ZIP');
  assert.equal(download.hidden,false);
  assert.equal(new URL(download.href,'https://platform.test').searchParams.get('talentId'),talent.id);
  assert.ok(download.href.startsWith('/api/v1/hr/pool/interview/package?'));
  await app.click('关闭');await app.click(talent.name.slice(0,1));
  assert.equal(dialog.open,true);
  assert.equal(app.visible().find(n=>n.className==='hr-person-card').events.click,undefined);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
});
test('interview stage badges show pending opinions while processed records remain accessible',async()=>{
  const app=setup({api:async url=>{
    const query=new URL(url,'https://platform.test').searchParams;
    const processed=query.get('outcome')==='passed';
    return {data:{...poolResult(),items:processed?poolResult().items:[],total:processed?7:0,
      counts:{invited:0,first:7,second:3,hired:0},pendingCounts:{invited:0,first:0,second:1,hired:0},
      outcomeCounts:{pending:0,passed:7,failed:0}}};
  }});
  await app.enter('pool');await app.click('一面 0');
  assert.equal(new URL(app.calls.at(-1).url,'https://platform.test').searchParams.get('outcome'),'pending');
  assert.equal(app.visible().find(n=>n.dataset.stage==='second')._text,'二面 1');
  await app.click('通过 7');
  assert.match(app.text(),/测试人才/);
  assert.equal(app.visible().find(n=>n.dataset.stage==='first')._text,'一面 0');
  assert.ok(app.calls.every(c=>!c.options?.method||c.options.method!=='POST'));
});
test('first opinion requires text and approval advances to second pending with one explicit save',async()=>{
  let talent={...poolResult().items[0],stage:'first',source:'passive',interviewReviews:{}};
  const app=setup({api:async(url,options)=>{
    if(url.endsWith('/interview/review')){
      const body=JSON.parse(options.body);talent={...talent,stage:'second',version:2,
        interviewReviews:{first:{outcome:body.outcome,opinion:body.opinion,createdAt:'2026-10-09T00:00:00Z'}}};
      return {data:talent};
    }
    return {data:{...poolResult(),items:[talent],counts:{all:1,first:1,second:1},
      outcomeCounts:{all:1,pending:1,passed:0,failed:0}}};
  }});
  await app.enter('pool');await app.click('一面意见');
  const dialog=all(app.root).find(n=>n.className.includes('hr-interview-review-dialog'));
  const opinion=all(dialog).find(n=>n.tagName==='textarea');
  assert.equal(all(dialog).find(n=>n._text==='通过').disabled,true);
  await app.click('通过');
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
  opinion.value='基础扎实，建议进入二面';opinion.events.input();await app.click('通过');
  const writes=app.calls.filter(c=>c.options?.method==='POST');
  assert.equal(writes.length,1);assert.equal(writes[0].url,'/api/v1/hr/pool/interview/review');
  const body=JSON.parse(writes[0].options.body);
  assert.equal(body.round,'first');assert.equal(body.outcome,'passed');
  const selected=new URL(app.calls.at(-1).url,'https://platform.test').searchParams;
  assert.equal(selected.get('stage'),'second');assert.equal(selected.get('outcome'),'pending');
  assert.equal(app.env.location.hash,'#hr-pool');
  assert.equal(app.links.find(n=>n.dataset.hrRoute==='pool').attributes['aria-current'],'page');
  assert.match(app.text(),/审核/);assert.equal(opinion.value,'');
  assert.ok(app.calls.every(c=>!c.url.endsWith('/interview/start')&&!c.url.includes('/pool/messages')));
});
test('interview result pills compose with department and completed opinions open without decision buttons',async()=>{
  const talent={...poolResult().items[0],stage:'second',interviewReviews:{
    first:{outcome:'passed',opinion:'面试表现良好',createdAt:'2026-10-09T00:00:00Z'}}};
  const app=setup({api:async()=>({data:{...poolResult(),items:[talent],counts:{all:1,first:1,second:1},
    outcomeCounts:{all:1,pending:0,passed:1,failed:0}}})});
  await app.enter('pool');await app.click('一面 1');
  assert.equal(new URL(app.calls.at(-1).url,'https://platform.test').searchParams.get('outcome'),'pending');
  assert.equal(app.visible().find(n=>n.dataset.outcome==='pending').attributes['aria-pressed'],'true');
  assert.ok(!app.visible().some(n=>n.dataset.outcome==='all'));
  await app.click('电商部');
  await app.click('通过 1');
  const pill=app.visible().find(n=>n.dataset.outcome==='passed');
  assert.equal(pill.attributes['aria-pressed'],'true');
  assert.ok(app.visible().find(n=>n.className.includes('hr-pool-outcomes')).className.includes('hr-pool-tabs'));
  const params=new URL(app.calls.at(-1).url,'https://platform.test').searchParams;
  assert.equal(params.get('stage'),'first');assert.equal(params.get('outcome'),'passed');
  assert.equal(params.get('department'),'department-1');
  await app.click('二面 1');
  assert.equal(new URL(app.calls.at(-1).url,'https://platform.test').searchParams.get('outcome'),'pending');
  assert.equal(app.visible().find(n=>n.dataset.outcome==='pending').attributes['aria-pressed'],'true');
  await app.click('一面 1');
  assert.equal(new URL(app.calls.at(-1).url,'https://platform.test').searchParams.get('outcome'),'pending');
  await app.click('通过 1');
  await app.click('一面意见');
  const dialog=all(app.root).find(n=>n.className.includes('hr-interview-review-dialog'));
  const opinion=all(dialog).find(n=>n.tagName==='textarea');
  assert.equal(opinion.value,'面试表现良好');assert.equal(opinion.readOnly,true);
  assert.ok(all(dialog).filter(n=>n._text==='通过'||n._text==='不通过').every(n=>n.hidden));
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
});
test('failed opinion save preserves visible error and logout drops a late success',async()=>{
  let resolve;
  const talent={...poolResult().items[0],stage:'first'};
  const app=setup({api:async url=>url.endsWith('/interview/review')?new Promise(r=>resolve=r)
    :{data:{...poolResult(),items:[talent]}}});
  await app.enter('pool');await app.click('一面意见');
  const dialog=all(app.root).find(n=>n.className.includes('hr-interview-review-dialog'));
  const opinion=all(dialog).find(n=>n.tagName==='textarea');
  opinion.value='内部面试意见';opinion.events.input();
  const pending=app.click('不通过');await flush();app.env.HrWorkspace.reset();
  resolve({data:{...talent,interviewReviews:{first:{opinion:'内部面试意见',outcome:'failed'}}}});
  await pending;
  assert.equal(opinion.value,'');assert.doesNotMatch(dialog.textContent,/内部面试意见|测试人才/);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,1);
  const failed=setup({api:async url=>url.endsWith('/interview/review')
    ?{success:false,message:'人才资料已更新，请刷新'}:{data:{...poolResult(),items:[talent]}}});
  await failed.enter('pool');await failed.click('一面意见');
  const failedDialog=all(failed.root).find(n=>n.className.includes('hr-interview-review-dialog'));
  const failedOpinion=all(failedDialog).find(n=>n.tagName==='textarea');
  failedOpinion.value='待复核';failedOpinion.events.input();await failed.click('不通过');
  assert.match(failed.text(),/人才资料已更新，请刷新/);assert.equal(failedDialog.open,true);
});
test('DingTalk setup guides personal employee mapping and does not echo saved secret',async()=>{
  const app=setup({api:async url=>({data:url.endsWith('/dingtalk')
    ?{config:{verified:true,appKey:'ding-test',agentId:'123',hasSecret:true,version:1},demands:[]}
    :poolResult()})});
  await app.enter('pool');await app.click('钉钉设置');
  assert.match(app.text(),/成员信息读权限/);assert.match(app.text(),/UserID/);
  const secret=all(app.root).find(n=>n.type==='password');
  assert.equal(secret.value,'');assert.match(secret.placeholder,/留空保留/);
  assert.ok(app.calls.every(c=>c.options?.method!=='POST'),'Opening setup never sends any notification');
});
test('unknown interview receipt blocks resending and does not falsely advance stage',async()=>{
  const talent={...poolResult().items[0],stage:'invited',source:'passive'};
  const app=setup({api:async url=>({data:url.includes('/interview?')
    ?{...interviewPreview(talent),handoff:{id:'i1',status:'uncertain',error:'结果待核对'}}
    :{...poolResult(),items:[talent]}})});
  await app.enter('pool');await app.click('开始一面');
  assert.match(app.text(),/不会自动重发/);
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
  assert.equal(all(app.root).find(n=>n._text==='发送一面资料').disabled,true);
});
test('explicit failed interview retry carries the existing record and preserves visible errors',async()=>{
  const talent={...poolResult().items[0],stage:'invited',source:'passive'};
  const app=setup({api:async url=>{
    if(url.endsWith('/interview/start'))return {success:false,message:'负责人配置已更新，请刷新'};
    return {data:url.includes('/interview?')
      ?{...interviewPreview(talent),handoff:{id:'i1',status:'failed',error:'上次未发送'}}
      :{...poolResult(),items:[talent]}};
  }});
  await app.enter('pool');await app.click('开始一面');
  assert.equal(app.calls.filter(c=>c.options?.method==='POST').length,0);
  await app.click('手动重试');
  assert.equal(JSON.parse(app.calls.find(c=>c.options?.method==='POST').options.body).retryId,'i1');
  assert.match(app.text(),/负责人配置已更新，请刷新/);
});
test('late DingTalk settings response cannot restore employee details after logout',async()=>{
  let resolve;
  const app=setup({api:async url=>url.endsWith('/dingtalk')?new Promise(r=>resolve=r):{data:poolResult()}});
  await app.enter('pool');
  const pending=app.click('钉钉设置');await flush();
  app.env.HrWorkspace.reset();
  resolve({data:{config:{appKey:'private-app',agentId:'123',verified:true,version:1},
    demands:[{id:'d1',position:'秘密职位',department:'研发',recipient:{userId:'private-id',name:'秘密姓名',valid:true}}]}});
  await pending;
  assert.doesNotMatch(app.text(),/秘密姓名|秘密职位/);
  assert.ok(all(app.root).filter(n=>n.tagName==='input').every(n=>!['private-app','private-id'].includes(n.value)));
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
  assert.match(app.text(),/测试人才/);assert.match(app.text(),/本科/);assert.match(app.text(),/电商部/);
  assert.ok(!app.visible().some(n=>['管理项目组','查看 / 编辑','新建人才'].includes(n._text)));
  assert.ok(!app.visible().some(n=>n.dataset.stage==='all'));
  assert.equal(new URL(app.calls.at(-1).url,'https://platform.test').searchParams.get('stage'),'invited');
  assert.ok(app.calls.every(c=>c.url.startsWith('/api/v1/hr/pool')));
  assert.ok(!app.visible().some(n=>n.tagName==='select'&&n.children.some(v=>v.textContent==='全部部门')));
  const departmentButton=app.visible().find(n=>n.dataset.department==='department-1');
  await app.click('电商部');
  assert.equal(departmentButton.attributes['aria-pressed'],'true');
  assert.equal(app.visible().find(n=>n.dataset.department==='department-1'),departmentButton);
  await app.click('一面 1');
  const selected=new URL(app.calls.at(-1).url,'https://platform.test').searchParams;
  assert.equal(selected.get('stage'),'first');assert.equal(selected.get('department'),'department-1');
  assert.equal(app.timers.size,0);
});
test('talent pool drops late responses after logout and clears personal data',async()=>{
  let resolve;
  const app=setup({api:()=>new Promise(r=>resolve=r)});
  await app.enter('pool');app.env.HrWorkspace.reset();
  resolve({data:poolResult()});await flush();
  assert.doesNotMatch(app.text(),/测试人才/);
});
test('talent pool page buttons preserve department and stage filters and reset when filters change',async()=>{
  const app=setup({api:async url=>{
    const params=new URL(url,'https://platform.test').searchParams;
    return {data:{...poolResult(),total:45,pages:3,page:Number(params.get('page')||1)}};
  }});
  await app.enter('pool');await app.click('电商部');await app.click('一面 1');
  await app.click('2');
  let params=new URL(app.calls.at(-1).url,'https://platform.test').searchParams;
  assert.equal(params.get('page'),'2');assert.equal(params.get('department'),'department-1');
  assert.equal(params.get('stage'),'first');
  assert.match(app.text(),/第 2 \/ 3 页 · 共 45 人 · 每页 20 人/);
  await app.click('下一页');
  assert.equal(new URL(app.calls.at(-1).url,'https://platform.test').searchParams.get('page'),'3');
  assert.equal(app.visible().find(n=>n._text==='下一页').disabled,true);
  await app.click('全部部门');
  params=new URL(app.calls.at(-1).url,'https://platform.test').searchParams;
  assert.equal(params.get('page'),'1');assert.equal(params.get('department'),'');
  assert.ok(app.calls.every(c=>!c.options?.method||c.options.method!=='POST'));
});
test('talent pool always shows single-page and empty pagination without allowing out-of-range navigation',async()=>{
  for(const empty of [false,true]){
    const app=setup({api:async()=>({data:{...poolResult(),...(empty?{items:[],total:0,counts:{all:0}}:{})}})});
    await app.enter('pool');
    const pager=app.visible().find(n=>n.className.includes('hr-pool-pagination'));
    assert.ok(pager);assert.equal(pager.hidden,false);
    assert.equal(app.visible().find(n=>n._text==='上一页').disabled,true);
    assert.equal(app.visible().find(n=>n._text==='下一页').disabled,true);
    assert.match(app.text(),new RegExp(`第 1 / 1 页 · 共 ${empty?0:1} 人`));
  }
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
  assert.equal(body.stage,'invited');
  assert.equal(body.securityId,undefined);
});
const demandDirectory=()=>({items:[{id:'dept-1',name:'电商组',manager:'测试经理',status:'ACTIVE',code:'ECOM',version:1}]});
const demandRow=()=>({id:'d1',version:1,date:'2026-09-29',position:'电商运营',department:'电商组',departmentId:'dept-1',manager:'测试经理',
  category:'运营',headcount:2,education:'本科',years:3,status:'recruiting',responsibilities:'店铺运营',
  requirements:'电商经验',aiStatus:'idle',aiResult:{},jobId:'job-test-2',jobAccountKey:'a'.repeat(64),
  jobDescription:'已保存旧 JD',jobFetchedAt:'2026-09-29T00:00:00Z'});
const demandStatus=()=>({state:'complete',accountKey:'a'.repeat(64),capabilities:['job-jd']});
const demandDetail=(id='job-test-2')=>({ok:true,data:{job:{encryptId:id,jobName:'电商运营',postDescription:'当前岗位 JD：负责直播策划与内容运营。'}}});
test('demands remove status controls and preserve query through pagination including a single page',async()=>{
  const app=setup({api:async url=>{
    const params=new URL(url,'https://platform.test').searchParams;
    return {data:{items:[demandRow()],total:41,page:Number(params.get('page')||1),pages:3}};
  }});
  await app.enter('demands');
  assert.ok(!app.visible().some(n=>n._text==='需求状态'));
  assert.ok(!app.visible().some(n=>n._text==='招聘中'));
  const query=app.visible().find(n=>n.name==='demand-search');query.value='电商';
  await app.click('搜索');await app.click('下一页');
  const params=new URL(app.calls.at(-1).url,'https://platform.test').searchParams;
  assert.equal(params.get('page'),'2');assert.equal(params.get('q'),'电商');assert.equal(params.has('status'),false);
  assert.match(app.text(),/第 2 \/ 3 页 · 共 41 项 · 每页 20 项/);
  await app.click('搜索');
  assert.equal(new URL(app.calls.at(-1).url,'https://platform.test').searchParams.get('page'),'1');
  const single=setup({api:async()=>({data:{items:[demandRow()],total:1,page:1,pages:1}})});
  await single.enter('demands');
  assert.ok(single.visible().find(n=>n.className.includes('hr-demand-pagination')));
  assert.equal(single.visible().find(n=>n._text==='下一页').disabled,true);
});
test('demand deletion is explicit once and refreshes the clamped last page',async()=>{
  let removed=false;
  const app=setup({confirm:()=>true,api:async(url,options)=>{
    if(url.endsWith('/delete')){
      const body=JSON.parse(options.body);assert.equal(body.id,'d1');assert.equal(body.version,1);
      assert.match(body.requestId,/^[a-f0-9-]{36}$/);removed=true;return {data:{deleted:true,id:'d1'}};
    }
    return {data:{items:removed?[]:[demandRow()],total:removed?0:1,page:1,pages:1}};
  }});
  await app.enter('demands');await app.click('删除');
  assert.equal(app.calls.filter(c=>c.url.endsWith('/delete')).length,1);
  assert.doesNotMatch(app.text(),/电商运营/);assert.match(app.text(),/共 0 项/);
  const running=setup({confirm:()=>true,api:async()=>({data:{items:[{...demandRow(),aiStatus:'running'}],total:1,page:1,pages:1}})});
  await running.enter('demands');await running.click('删除');
  assert.equal(running.calls.filter(c=>c.options?.method==='POST').length,0);
  const cancelled=setup({confirm:()=>false,api:async()=>({data:{items:[demandRow()],total:1,page:1,pages:1}})});
  await cancelled.enter('demands');await cancelled.click('删除');
  assert.equal(cancelled.calls.filter(c=>c.options?.method==='POST').length,0);
});
test('optional demand references upload offline in AI area and generation still uses one DRIVER request',async()=>{
  let row={...demandRow(),references:[]},generations=0;
  const app=setup({confirm:()=>true,api:async(url,options)=>{
    const body=options?.body&&JSON.parse(options.body);
    if(url.endsWith('/login/status'))return {data:demandStatus()};
    if(url==='/api/v1/hr/jobs')return {data:fixtures.jobs};
    if(url.startsWith('/api/v1/hr/jobs?'))return {data:demandDetail()};
    if(url==='/api/v1/hr/departments')return {data:demandDirectory()};
    if(url.endsWith('/save')){row={...row,...body,version:row.version+1};return {data:row};}
    if(url.endsWith('/upload')){
      assert.equal(body.id,row.id);assert.equal(body.version,row.version);assert.ok(body.fileBase64);
      row={...row,version:row.version+1,references:[{id:'ref1',filename:body.filename}],aiStatus:'stale'};
      return {data:row};
    }
    if(url.endsWith('/generate')){generations++;row={...row,aiStatus:'running'};return {data:row};}
    return {data:{items:[row],counts:{all:1},total:1,page:1,pages:1}};
  }});
  await app.enter('demands');await app.click('查看 / 编辑');
  const input=app.visible().find(n=>n.attributes['aria-label']==='上传岗位分析参考简历');
  assert.ok(input);assert.match(input.parent.parent.parent.className,/hr-demand-references/);
  input.files=[{name:'优秀员工.pdf',size:100}];input.events.change();await flush();
  assert.equal(row.references.length,1);assert.equal(generations,0);
  assert.match(app.text(),/优秀员工.pdf/);assert.match(app.text(),/未上传时，仍按岗位 JD/);
  await app.click('一键 AI 生成');
  assert.equal(generations,1);assert.equal(input.disabled,true);
});
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
  row={...row,aiProgress:{phase:'generating',receivedChars:640}};await app.tick(2500);
  assert.match(app.text(),/已接收 640 字/);
  let track=all(app.root).find(n=>n.className==='hr-demand-progress-track');
  assert.equal(track.attributes.role,'progressbar');assert.equal(track.attributes['aria-valuenow'],undefined);
  assert.equal(track.attributes['aria-valuetext'],'模型生成');
  await app.click('关闭');await app.click('查看 / 编辑');
  assert.match(app.text(),/已接收 640 字/);assert.equal(started,1);
  row={...row,aiProgress:{phase:'validating',receivedChars:1000}};await app.tick(2500);
  assert.match(app.text(),/正在校验/);
  track=all(app.root).find(n=>n.className==='hr-demand-progress-track');
  assert.equal(track.attributes['aria-valuetext'],'结构校验');
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
  const form=all(dialog).find(n=>n.tagName==='form');const pending=form.events.submit({preventDefault(){}});
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
const syncedDepartmentDirectory=()=>({...demandDirectory(),items:demandDirectory().items.map(d=>({...d,synced:true,memberCount:2,source:'dingtalk'})),
  dingtalkConfig:{verified:true,version:1}});
const syncedDepartments=()=>({state:'complete',departmentCount:1,employeeCount:2,items:syncedDepartmentDirectory().items});
const departmentMembers=()=>({...directoryResult(),items:[{userId:'employee1',name:'钉钉经理'}],total:1,page:1,pages:1});
test('department module imports DingTalk departments without manual creation or BOSS operations',async()=>{
  const app=setup({transport:{connection:()=>({paired:false})},api:async url=>({data:url.endsWith('/sync')?syncedDepartments():syncedDepartmentDirectory()})});
  app.env.HeyiHrTransport.api=async()=>{throw Error('Must not use BOSS');};
  await app.enter('departments');assert.match(app.text(),/电商组/);
  assert.ok(app.calls.every(c=>c.url.startsWith('/api/v1/hr/departments')));
  assert.equal(JSON.parse(app.calls.find(c=>c.url.endsWith('/sync')).options.body).refresh,false);
  assert.ok(!app.visible().some(n=>['新建部门','编辑部门'].includes(n._text)));
  const b=app.visible().find(n=>n._text==='选择负责人');assert.equal(b.disabled,false);
  await app.click('同步钉钉部门');
  assert.equal(JSON.parse(app.calls.filter(c=>c.url.endsWith('/sync')).at(-1).options.body).refresh,true);
});
test('department member selection verifies identity before binding and never sends notifications',async()=>{
  const app=setup({api:async(url,options)=>{
    if(url.endsWith('/identity'))return {data:{name:'钉钉经理',identityProof:'synthetic-proof'}};
    if(url.endsWith('/bind'))return {data:{name:'钉钉经理'}};
    return {data:url.endsWith('/sync')?syncedDepartments():url.includes('/members?')?departmentMembers():syncedDepartmentDirectory()};
  }});
  await app.enter('departments');await app.click('选择负责人');
  const dialog=all(app.root).find(n=>n.className.includes('hr-department-binding'));
  const select=all(dialog).find(n=>n.tagName==='select');select.value='employee1';
  await select.events.change();await flush();
  assert.match(app.text(),/已核对：钉钉经理/);
  assert.equal(app.calls.filter(c=>c.url.endsWith('/bind')).length,0);
  await app.click('确认负责人');
  const writes=app.calls.filter(c=>c.options?.method==='POST');
  assert.deepEqual(writes.map(c=>c.url),['/api/v1/hr/departments/sync','/api/v1/hr/departments/identity','/api/v1/hr/departments/bind']);
  assert.equal(JSON.parse(writes[1].options.body).userId,'employee1');
  assert.equal(JSON.parse(writes[2].options.body).identityProof,'synthetic-proof');
  assert.equal(JSON.parse(writes[1].options.body).mobile,undefined);
  assert.equal(select.value,'');assert.equal(dialog.open,false);
  assert.match(app.text(),/已设置负责人：钉钉经理/);
});
test('department search invalidates unconfirmed identity and missing app config cannot sync',async()=>{
  const app=setup({api:async url=>({data:url.endsWith('/identity')
    ?{name:'钉钉经理',identityProof:'synthetic-proof'}
    :url.endsWith('/sync')?syncedDepartments():url.includes('/members?')?departmentMembers():syncedDepartmentDirectory()})});
  await app.enter('departments');await app.click('选择负责人');
  const dialog=all(app.root).find(n=>n.className.includes('hr-department-binding'));
  const select=all(dialog).find(n=>n.tagName==='select');select.value='employee1';await select.events.change();await flush();
  const query=all(dialog).find(n=>n.tagName==='input');query.value='另一人';query.events.input();
  assert.ok(all(dialog).find(n=>n._text==='确认负责人').disabled);
  assert.equal(app.calls.filter(c=>c.url.endsWith('/bind')).length,0);
  const unconfigured=setup({api:async()=>({data:demandDirectory()})});
  await unconfigured.enter('departments');
  assert.match(unconfigured.text(),/请先在储备人才库/);
  assert.equal(all(unconfigured.root).find(n=>n._text==='同步钉钉部门').disabled,true);
  assert.ok(unconfigured.calls.every(c=>c.options?.method!=='POST'));
});
test('late department identity response cannot repopulate data after logout',async()=>{
  let resolve;
  const app=setup({api:async url=>url.endsWith('/identity')?new Promise(r=>resolve=r)
    :{data:url.endsWith('/sync')?syncedDepartments():url.includes('/members?')?departmentMembers():syncedDepartmentDirectory()}});
  await app.enter('departments');await app.click('选择负责人');
  const dialog=all(app.root).find(n=>n.className.includes('hr-department-binding'));
  const select=all(dialog).find(n=>n.tagName==='select');select.value='employee1';
  const pending=select.events.change();
  app.env.HrWorkspace.reset();
  resolve({data:{name:'私密姓名',identityProof:'private-proof'}});
  await pending;
  assert.equal(select.children.length,0);
  assert.doesNotMatch(app.text(),/私密姓名|private-proof/);
  assert.equal(app.calls.filter(c=>c.url.endsWith('/bind')).length,0);
});
test('department synchronization polls without repeating force refresh and stops after leaving',async()=>{
  const timeouts=[];let phase='running';
  const app=setup({setTimeout(fn,ms){timeouts.push({fn,ms});return timeouts.length;},api:async url=>({data:url.endsWith('/sync')
    ?{...syncedDepartments(),state:phase}:syncedDepartmentDirectory()})});
  await app.enter('departments');assert.match(app.text(),/正在同步/);
  assert.equal(app.visible().find(n=>n._text==='选择负责人').disabled,true);
  phase='complete';await timeouts.find(t=>t.ms===1500).fn();await flush();
  assert.equal(app.visible().find(n=>n._text==='选择负责人').disabled,false);
  assert.ok(app.calls.filter(c=>c.url.endsWith('/sync')).every(c=>JSON.parse(c.options.body).refresh===false));
  phase='running';await app.click('同步钉钉部门');
  const pending=timeouts.at(-1);await app.enter('pool');const count=app.calls.length;
  await pending.fn();assert.equal(app.calls.length,count);
});
test('department member pagination keeps department id and query and late responses cannot restore a closed picker',async()=>{
  let resolve,hold=false;
  const app=setup({api:async url=>{
    if(url.includes('/members?')){
      if(hold)return new Promise(r=>resolve=r);
      const query=new URL(url,'https://platform.test').searchParams;
      return {data:{...departmentMembers(),page:Number(query.get('page')),pages:2,total:21}};
    }
    return {data:url.endsWith('/sync')?syncedDepartments():syncedDepartmentDirectory()};
  }});
  await app.enter('departments');await app.click('选择负责人');
  const dialog=app.visible().find(n=>n.className.includes('hr-department-binding'));
  const q=all(dialog).find(n=>n.tagName==='input');q.value='经理';
  await q.events.keydown({key:'Enter',preventDefault(){}});await flush();
  await all(dialog).find(n=>n._text==='下一页').click();await flush();
  const params=new URL(app.calls.at(-1).url,'https://platform.test').searchParams;
  assert.equal(params.get('id'),'dept-1');assert.equal(params.get('q'),'经理');assert.equal(params.get('page'),'2');
  hold=true;const pending=all(dialog).find(n=>n._text==='重新读取成员').click();await flush();
  await app.click('关闭');resolve({data:departmentMembers()});await pending;await flush();
  assert.equal(all(dialog).find(n=>n.tagName==='select').children.length,0);
  assert.equal(app.calls.filter(c=>c.url.endsWith('/identity')||c.url.endsWith('/bind')).length,0);
});
test('failed department synchronization retains existing departments and no late sync can restore data after logout',async()=>{
  const failed=setup({api:async url=>({data:url.endsWith('/sync')?{state:'failed',error:'部门权限不足'}:syncedDepartmentDirectory()})});
  await failed.enter('departments');assert.match(failed.text(),/部门权限不足/);assert.match(failed.text(),/电商组/);
  assert.equal(failed.calls.some(c=>c.url.endsWith('/save')),false);
  let resolve;
  const app=setup({api:async url=>url.endsWith('/sync')?new Promise(r=>resolve=r):{data:syncedDepartmentDirectory()}});
  await app.enter('departments');app.env.HrWorkspace.reset();
  resolve({data:syncedDepartments()});await flush();
  assert.doesNotMatch(app.text(),/电商组|测试经理/);
});
test('department cards paginate twelve at a time and search resets and resync clamps the page',async()=>{
  let rows=Array.from({length:27},(_,i)=>({...syncedDepartmentDirectory().items[0],
    id:'dept-'+i,name:'部门 '+String(i+1).padStart(2,'0')}));
  const app=setup({api:async url=>({data:url.endsWith('/sync')
    ?{...syncedDepartments(),departmentCount:rows.length,items:rows}:{...syncedDepartmentDirectory(),items:rows}})});
  const cards=()=>app.visible().filter(n=>n.tagName==='article'&&n.className==='hr-person-card');
  await app.enter('departments');assert.equal(cards().length,12);
  const calls=app.calls.length;
  await app.click('2');assert.equal(cards().length,12);assert.match(cards()[0].textContent,/部门 13/);
  await app.click('3');assert.equal(cards().length,3);assert.match(app.text(),/第 3 \/ 3 页 · 共 27 个部门 · 每页 12 个/);
  assert.equal(app.calls.length,calls,'Paging the saved directory never reads BOSS or DingTalk again');
  const query=app.visible().find(n=>n.tagName==='input');query.value='部门 01';query.events.input();
  assert.equal(cards().length,1);assert.match(app.text(),/第 1 \/ 1 页/);
  query.value='';query.events.input();await app.click('3');
  rows=rows.slice(0,13);await app.click('同步钉钉部门');
  assert.equal(cards().length,1);assert.match(app.text(),/第 2 \/ 2 页 · 共 13 个部门/);
  app.env.HrWorkspace.reset();assert.ok(!app.visible().some(n=>n.attributes['aria-label']==='部门列表分页'));
});

test('overview has useful entry points and no webpage QR or QR requests',async()=>{
  const app=setup({api:async()=>({data:{state:'pending',phase:'qr'}})});
  await app.enter('overview');await app.tick(3000);
  assert.equal(all(app.root).filter(n=>n.tagName==='img').length,0);
  assert.match(app.text(),/职位管理/);assert.doesNotMatch(app.text(),/人才寻源/);
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
test('automatic recovery clears a stale pairing error without replaying operations',async()=>{
  let listener;
  const app=setup({transport:{connection:()=>({paired:false}),subscribe(fn){listener=fn;},
    connect:async()=>{throw Error('连接服务暂时不可用，未执行招聘操作');}}});
  await app.enter('overview');await app.click('连接本机助手');
  assert.match(app.text(),/连接服务暂时不可用/);
  listener({paired:true,phase:'connected',state:{state:'complete',paused:false,busy:false}});
  await flush();
  assert.match(app.text(),/招聘账号已就绪/);
  assert.doesNotMatch(app.text(),/连接服务暂时不可用/);
  assert.equal(app.calls.length,0);
});
test('healthy connection updates retain an unconfirmed logout error',async()=>{
  let listener;
  const app=setup({transport:{connection:()=>({paired:true}),subscribe(fn){listener=fn;},
    disconnect:async()=>{throw Error('登录信息未能清除，请重试');}}});
  await app.enter('overview');await app.click('退出 BOSS 登录');
  listener({paired:true,phase:'connected',state:{state:'complete',paused:false,busy:false}});
  await flush();
  assert.match(app.text(),/登录信息未能清除，请重试/);
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

test('refreshing managed jobs includes a new unchatted job and preserves the list on failure',async()=>{
  let reads=0,finish;
  const initial={ok:true,data:{jobs:[{encryptJobId:'old',jobName:'秘书',statusDesc:'开放中'}]}};
  const app=setup({api:async url=>{
    if(url.endsWith('/login/status'))return {data:{state:'complete'}};
    if(url.endsWith('/jobs')){
      reads++;
      if(reads===1)return {data:initial};
      if(reads===2){
        await new Promise(resolve=>{finish=resolve;});
        return {data:{ok:true,data:{jobs:[...initial.data.jobs,
          {encryptJobId:'new',jobName:'图像算法',statusDesc:'开放中'},
          {encryptJobId:'expired',jobName:'验证工程师',statusDesc:'已失效'}]}}};
      }
      throw Error('列表暂时不可用');
    }
    throw Error('unexpected '+url);
  }});
  await app.enter('jobs');assert.equal(reads,1);
  const refresh=app.visible().find(n=>n._text==='刷新职位');
  const pending=refresh.click();await flush();await refresh.click();assert.equal(reads,2);
  finish();await pending;await flush();
  assert.match(app.text(),/图像算法/);assert.match(app.text(),/已失效/);
  await app.click('刷新职位');
  assert.match(app.text(),/图像算法/);assert.match(app.text(),/列表暂时不可用/);
  assert.ok(app.calls.every(c=>!c.options?.method||c.options.method==='GET'));
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
