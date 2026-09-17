const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const tick=()=>new Promise(resolve=>setImmediate(resolve));
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return{promise,resolve,reject};};
const detection=title=>({data:{items:[{title,version:1,imageCount:3,skuCount:2,issues:[]}]}});

function harness({reply,route='#commerce-designs'}={}){
 const events=new Map(),documentEvents=new Map(),nodes=[],ids={},requests=[],emitted=[],observers=[],intervals=new Map();let nextInterval=0;
 const matches=(node,selector)=>{
  const data=selector.match(/^\[data-([\w-]+)(?:="([^"]*)")?\]$/);
  if(data){const key=data[1].replace(/-([a-z])/g,(_,c)=>c.toUpperCase());return key in node.dataset&&(data[2]===undefined||node.dataset[key]===data[2]);}
  if(selector.startsWith('.'))return (node.className||'').split(' ').includes(selector.slice(1))||node.classList.contains(selector.slice(1));
  return node.tag===selector;
 };
 function element(tag='div'){
  const classes=new Set(),handlers=new Map();
  const node={tag,children:[],parent:null,dataset:{},style:{},hidden:false,disabled:false,inert:false,textContent:'',className:'',replaceCount:0,
   classList:{contains:key=>classes.has(key),add:(...keys)=>keys.forEach(key=>classes.add(key)),remove:(...keys)=>keys.forEach(key=>classes.delete(key)),toggle:(key,on)=>on?classes.add(key):classes.delete(key)},
   append(...children){for(const child of children){if(child.parent)child.parent.children=child.parent.children.filter(item=>item!==child);child.parent=this;this.children.push(child);}},
   replaceChildren(...children){this.replaceCount++;this.children.forEach(child=>child.parent=null);this.children=[];this.textContent='';this.append(...children);},
   after(node){this.next=node;},setAttribute(key,value){this[key]=value;},removeAttribute(key){delete this[key];},
   addEventListener(type,handler){if(!handlers.has(type))handlers.set(type,[]);handlers.get(type).push(handler);},
   fire(type,fields={}){for(const handler of handlers.get(type)||[])handler({type,target:this,preventDefault(){},stopImmediatePropagation(){},...fields});},click(){this.fire('click');},
   querySelectorAll(selector){const result=[];const walk=parent=>{for(const child of parent.children){if(matches(child,selector))result.push(child);walk(child);}};walk(this);return result;},
   querySelector(selector){return this.querySelectorAll(selector)[0]||null;},closest(selector){for(let node=this;node;node=node.parent)if(matches(node,selector))return node;return null;}
  };nodes.push(node);return node;
 }
 const get=id=>ids[id]??=element();
 const journey=element('section'),recipe=element('form'),sample=element('details'),page=get('product-publish-page'),steps={};
 sample.className='sample-preview-box';get('commerce-design-panel').dataset.commercePanel='commerce-designs';
 for(const step of ['capture','check','extract','design','mockup','publish']){
  const button=element('button');button.dataset.studioStep=step;
  if(step==='capture')button.dataset.studioRoute='product-capture';if(step==='design')button.dataset.studioRoute='commerce-designs';if(step==='publish')button.dataset.studioRoute='commerce-batches';if(step==='check')button.dataset.studioCheck='';
  const title=element('b'),small=element('small');title.append(small);button.append(title);journey.append(button);steps[step]=button;
 }
 const add=(map,type,callback)=>{if(!map.has(type))map.set(type,[]);map.get(type).push(callback);};
 const fire=(type,detail)=>{for(const callback of events.get(type)||[])callback({type,detail});};
 const location={hash:route};
 const document={hidden:false,getElementById:get,createElement:element,createTextNode:value=>{const node=element('#text');node.textContent=value;return node;},
  querySelector:selector=>selector==='.studio-journey'?journey:selector==='#commerce-design-workspace .studio-recipe form'?recipe:selector==='.sample-preview-box'?sample:null,
  querySelectorAll:selector=>selector==='[data-commerce-panel]'?[get('commerce-design-panel')]:[],addEventListener:(type,callback)=>add(documentEvents,type,callback)};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../commerce-studio.js'),'utf8'),{
  document,location,window:{addEventListener:(type,callback)=>add(events,type,callback),dispatchEvent:event=>{emitted.push(event);fire(event.type,event.detail);}},
  CustomEvent:class{constructor(type,options={}){this.type=type;this.detail=options.detail;}},
  MutationObserver:class{constructor(callback){observers.push(callback);}observe(){}},
  setInterval:callback=>{const id=++nextInterval;intervals.set(id,callback);return id;},clearInterval:id=>intervals.delete(id),
  api:async url=>{requests.push(url);return reply?reply(url):detection(url);},encodeURIComponent
 });
 const stage=journey.next,guide=journey.children.find(node=>node.className==='studio-demo-guide');
 return{nodes,ids,get,journey,recipe,page,steps,stage,guide,sample,location,document,requests,emitted,intervals,fire,
  select(batchId,creativeOnly=false){page.classList.add('has-workbench-batch');fire('heyi:workbench-batch-selected',{batchId,creativeOnly});},
  run(run,extra={}){fire('heyi:execution-state',{run,...extra});},
  manual(step){journey.fire('click',{target:steps[step]});},
  sync(){observers.forEach(callback=>callback());},
  route(value){location.hash=value;fire('hashchange');},
  reset(){page.classList.remove('has-workbench-batch');fire('heyi:workbench-scope-reset');},
  visibility(hidden){document.hidden=hidden;for(const callback of documentEvents.get('visibilitychange')||[])callback();}
 };
}
const run=(stage='EXTRACT',status='RUNNING',batchId='a')=>({id:`run-${batchId}`,batchId,stage,status,message:'正在处理'});
const selected=h=>Object.entries(h.steps).find(([,button])=>button.classList.contains('is-current'))?.[0];
const content=node=>[node.textContent,...node.children.map(content)].join(' ');
const stageEvents=h=>h.emitted.filter(event=>event.type==='heyi:studio-step-selected');

test('same-stage execution polls and route observers preserve detection DOM, requests and stage events',async()=>{
 const h=harness();h.select('a');await tick();const count=h.stage.replaceCount,root=h.stage.children[2],events=stageEvents(h).length;
 h.run(run());await tick();assert.equal(h.requests.length,1,'first run reuses the already rendered extraction stage');assert.equal(h.recipe.inert,true);
 for(let i=0;i<10;i++){h.run({...run(),message:`进度 ${i}`});h.sync();}
 await tick();assert.equal(h.requests.length,1);assert.equal(h.stage.replaceCount,count);assert.equal(h.stage.children[2],root);assert.equal(stageEvents(h).length,events);
 for(const status of ['QUEUED','WAITING_INPUT','WAITING_CAPABILITY','UNKNOWN','RUNNING'])h.run(run('EXTRACT',status));
 assert.equal(h.stage.replaceCount,count);assert.equal(h.requests.length,1);
});

test('actual stage changes update once, including a reused mutable run object, without resetting repeated polls',async()=>{
 const h=harness();h.select('a');const mutable=run('CAPTURE');h.run(mutable);await tick();const start=h.stage.replaceCount;
 mutable.stage='DETECT';h.run(mutable);await tick();assert.equal(h.requests.length,2);assert.equal(h.stage.replaceCount,start+1);
 h.run({...mutable});assert.equal(h.stage.replaceCount,start+1);
 h.run(run('VARIATE'));assert.equal(selected(h),'design');const design=h.stage.replaceCount;h.run(run('VARIATE'));h.sync();assert.equal(h.stage.replaceCount,design);
 h.run(run('MOCKUP'));assert.equal(selected(h),'mockup');assert.equal(h.stage.replaceCount,design+1);assert.equal(h.requests.length,2);
});

test('manual navigation is not overridden by polling or reselection, with one detection refresh on terminal arrival',async()=>{
 const h=harness();h.select('a');h.run(run());await tick();h.manual('mockup');assert.equal(selected(h),'mockup');const stable=h.stage.replaceCount;
 h.run(run('VARIATE'));h.select('a');h.run(run('MOCKUP'));assert.equal(selected(h),'mockup');assert.equal(h.stage.replaceCount,stable);
 h.manual('check');await tick();const requests=h.requests.length,checks=h.stage.replaceCount;
 h.run(run('VARIATE','UNKNOWN'));h.run(run('VARIATE','WAITING_INPUT'));assert.equal(h.requests.length,requests);assert.equal(h.stage.replaceCount,checks);
 h.run(run('VARIATE','FAILED'));await tick();assert.equal(selected(h),'check');assert.equal(h.requests.length,requests+1);assert.equal(h.stage.replaceCount,checks+1);
 h.run(run('VARIATE','FAILED'));h.sync();assert.equal(h.requests.length,requests+1);assert.equal(h.stage.replaceCount,checks+1);
});

test('new batch resets manual navigation and stale execution events cannot affect it or an empty scope',async()=>{
 const h=harness(),demo=h.stage.replaceCount;h.run(run());assert.equal(h.stage.replaceCount,demo);assert.equal(h.recipe.inert,false);
 h.select('a');h.run(run());h.manual('publish');h.select('b');await tick();assert.equal(selected(h),'extract');assert.equal(h.recipe.inert,false);
 const before=h.stage.replaceCount,requests=h.requests.length;h.run(run('MOCKUP','RUNNING','a'));assert.equal(h.stage.replaceCount,before);assert.equal(h.recipe.inert,false);
 h.run(run('VARIATE','RUNNING','b'));assert.equal(selected(h),'design');assert.equal(h.recipe.inert,true);assert.equal(h.requests.length,requests);
 h.run(run('MOCKUP','RUNNING','b'),{batchId:'a'});assert.equal(selected(h),'design');
 h.reset();assert.equal(h.recipe.inert,false);assert.equal(selected(h),'extract');assert.match(content(h.stage),/流程示例/);
 const reset=h.stage.replaceCount;h.run(run('MOCKUP','RUNNING','b'));assert.equal(h.stage.replaceCount,reset);assert.equal(h.recipe.inert,false);
});

test('legacy unscoped run still follows the selected batch while explicit foreign batch IDs are rejected',()=>{
 const h=harness();h.select('a');h.run({id:'legacy',stage:'VARIATE',status:'RUNNING'});assert.equal(selected(h),'design');
 const before=h.stage.replaceCount;h.run({id:'invalid',batchId:'',stage:'MOCKUP',status:'RUNNING'});assert.equal(h.stage.replaceCount,before);assert.equal(selected(h),'design');
});

test('hidden routes keep the stage hidden and defer phase/terminal detection until returning',async()=>{
 const h=harness();h.select('a');h.run(run('CAPTURE'));await tick();const initial=h.stage.replaceCount;
 h.route('#commerce-templates');assert.equal(h.stage.hidden,true);assert.equal(h.journey.hidden,true);
 h.run(run('DETECT'));h.run(run('DETECT','FAILED'));h.sync();assert.equal(h.stage.hidden,true);assert.equal(h.requests.length,1);assert.equal(h.stage.replaceCount,initial);
 h.route('#commerce-designs');await tick();assert.equal(h.stage.hidden,false);assert.equal(h.journey.hidden,false);assert.equal(h.requests.length,2);assert.equal(h.stage.replaceCount,initial+1);
 h.sync();h.run(run('DETECT','FAILED'));assert.equal(h.requests.length,2);assert.equal(h.stage.replaceCount,initial+1);
 h.route('#product-capture');h.route('#commerce-designs');assert.equal(h.stage.replaceCount,initial+1);assert.equal(selected(h),'extract');
});

test('late detection responses and errors do not replace a new batch or a manually selected different stage',async()=>{
 const pending=deferred(),h=harness({reply:url=>url.includes('/a/')?pending.promise:detection('当前批次 B')});
 h.select('a');h.select('b');await tick();assert.match(content(h.stage),/当前批次 B/);pending.resolve(detection('旧批次 A'));await tick();assert.doesNotMatch(content(h.stage),/旧批次 A/);
 const error=deferred(),other=harness({reply:()=>error.promise});other.select('a');other.manual('mockup');const before=content(other.stage);error.reject(new Error('旧检测失败'));await tick();assert.equal(content(other.stage),before);
});

test('new isolated animation demo supports stage navigation and stops on real batch selection',()=>{
 const h=harness();assert.match(content(h.stage),/流程示例/);
 assert.equal(h.stage.querySelector('iframe').src,'/commerce-motion-demo.html?step=extract');
 assert.equal(h.stage.querySelectorAll('img').length,0);
 h.guide.click();assert.equal(h.intervals.size,1);
 [...h.intervals.values()][0]();assert.equal(selected(h),'design');
 assert.equal(h.stage.querySelector('iframe').src,'/commerce-motion-demo.html?step=design');
 h.visibility(true);assert.equal(h.intervals.size,0);assert.match(h.guide.textContent,/播放完整流程/);
 h.select('creative',true);h.run(run('REVIEW','RUNNING','creative'));assert.equal(selected(h),'publish');assert.match(content(h.steps.publish),/结果检查/);
 assert.equal(h.stage.querySelector('iframe'),null);assert.equal(h.guide.hidden,true);
 h.reset();assert.match(content(h.steps.publish),/上架/);assert.doesNotMatch(content(h.steps.publish),/结果检查/);
 assert.equal(h.stage.querySelector('iframe').src,'/commerce-motion-demo.html?step=extract');
});
