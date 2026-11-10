import fs from 'node:fs/promises';
import assert from 'node:assert/strict';

const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-costs',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document;
w.currentUser={organizationId:'org-a',userId:'user-a',permissions:['finance:view']};
w.matchMedia=()=>({matches:false});
w.ResizeObserver=class{observe(){}disconnect(){}};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
w.requestAnimationFrame=fn=>fn();
w.crypto.randomUUID=()=>`uuid-${posts.length+1}`;

const nativeSetTimeout=w.setTimeout.bind(w),nativeClearTimeout=w.clearTimeout.bind(w);
let pollId=1000,polls=new Map(),cleared=[];
w.setTimeout=(fn,delay,...args)=>{
  if(delay===3000){const id=++pollId;polls.set(id,()=>fn(...args));return id;}
  return nativeSetTimeout(fn,delay,...args);
};
w.clearTimeout=id=>{if(polls.delete(id))cleared.push(id);else nativeClearTimeout(id);};

const shops=[{id:'shop-a',name:'测试店',display_name:'测试店',platform:'SHOPEE',group_id:'group-a',region:'MY',currency:'MYR',timezone:'Asia/Kuala_Lumpur'}];
const groups=[{id:'group-a',name:'测试组'}];
const costs={live:true,shopId:'shop-a',groupId:'group-a',groupName:'测试组',sourceName:'成本.xlsx',costs:[],manualCosts:[],products:[],sampleOrders:1,orderLimit:2000,settings:{warehouse:'',rules:{}},settingsVersion:1,mappings:{},shopCatalogs:{'shop-a':{costRows:1,sourceName:'成本.xlsx'}}};
const orderResult={items:[{id:'row-a',shop_id:'shop-a',order_id:'ORDER-A',record_date:'2026-10-01',order_created_time:'2026-10-01T00:00:00Z',first_settlement_date:'2026-10-05',settlement_currency:'MYR',product_cost_cny:8,product_cost_state:'已计算'}],total:1,page:1,pageSize:50,productCosts:{total:{ready_count:1,pending_count:0,estimated_count:0,amount_cny:8,known_amount_cny:8}},costAmountSummary:{order_count:1,pending_count:0,amount_cny:8}};

const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};};
let serverJobs=[],postFailure=null,listFailure=null,listGate=null,postGate=null;
const requests=[],posts=[],automaticDownloads=[];
w.HTMLAnchorElement.prototype.click=function(){let prevented=false;this.onclick?.({preventDefault:()=>{prevented=true;},currentTarget:this,target:this});if(!prevented)automaticDownloads.push({href:this.href,download:this.download,text:this.textContent});};
w.api=async(path,options={})=>{
  const url=new URL(path,'https://example.test'),body=options.body?JSON.parse(options.body):null;
  requests.push({url,options,body});
  if(url.pathname==='/api/v1/finance/shops')return {data:{items:shops,groups,sync:[]}};
  if(url.pathname==='/api/v1/finance/workspace/costs')return {data:structuredClone(costs)};
  if(url.pathname==='/api/v1/finance/workspace/costs/orders')return {data:structuredClone(orderResult)};
  if(url.pathname==='/api/v1/finance/cost-exports'&&options.method==='POST'){
    posts.push({options,body});
    if(postGate){const gate=postGate;postGate=null;await gate.promise;}
    if(postFailure)throw postFailure;
    const job={id:'job-'+posts.length,status:'QUEUED',processed:0,total:250000,orders:0,lines:0,parts:0,start:body.start,end:body.end};
    serverJobs=[job,...serverJobs.filter(item=>item.id!==job.id)];
    return {data:structuredClone(job)};
  }
  if(url.pathname==='/api/v1/finance/cost-exports'){
    if(listFailure)throw listFailure;
    const snapshot=structuredClone(serverJobs);
    if(listGate){const gate=listGate;listGate=null;await gate.promise;}
    return {data:snapshot};
  }
  throw Error('Unexpected request '+url.pathname);
};
w.fetch=async()=>{throw Error('Cost downloads must use the visible authenticated same-origin link');};

for(const name of ['incremental-dom.js','finance-costs.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../../frontend/'+name,import.meta.url),'utf8'));
const settle=()=>new Promise(resolve=>nativeSetTimeout(resolve,20));
const button=(text,root=d)=>[...root.querySelectorAll('button')].find(node=>node.textContent.trim()===text||node.textContent.trim().endsWith(text));
const runPoll=async()=>{const entry=[...polls.entries()][0];assert(entry,'expected a 3 second pending-job poll');polls.delete(entry[0]);entry[1]();await settle();};

w.FinanceCosts.openOrders({start:'2026-10-01',end:'2026-10-31',shopId:'shop-a',groupId:'group-a',platform:'SHOPEE',currency:'MYR',kind:'SETTLED_ORDER',numbers:'ORDER-A'});
w.FinanceWorkspace.enter('costs');await settle();
const staleList=deferred();listGate=staleList;
let details=d.querySelector('[data-finance-cost-exports]');details.open=true;details.dispatchEvent(new w.Event('toggle'));await settle();
const exportButton=button('导出 Excel');assert(exportButton);exportButton.click();await settle();

assert.equal(posts.length,1,'one click creates one task');
assert.deepEqual(posts[0].body,{start:'2026-10-01',end:'2026-10-31',shopId:'shop-a',groupId:'group-a',platform:'SHOPEE',currency:'MYR',kind:'SETTLED_ORDER',recordId:'',costStatus:'ALL',numbers:'ORDER-A'});
assert.equal(posts[0].options.headers['Idempotency-Key'],'uuid-1');
assert.match(d.body.textContent,/后台生成|已提交/);assert.doesNotMatch(d.body.textContent,/Excel 已生成/);
assert(details?.open,'submitting opens the task panel');
assert.match(details.textContent,/2026-10-01 至 2026-10-31/);assert.match(details.textContent,/测试店/);assert.match(details.textContent,/已结算订单/);assert.match(details.textContent,/排队中/);
assert.equal(polls.size,1,'pending tasks poll every three seconds');
staleList.resolve();await settle();
assert.match(details.textContent,/排队中/,'a list snapshot started before POST must not remove the accepted task');
assert.equal(polls.size,1,'discarding a stale list snapshot keeps the accepted task polling');

serverJobs[0]={...serverJobs[0],status:'RUNNING',processed:120000,total:250000,orders:30000,lines:120000};
await runPoll();assert.match(details.textContent,/120,000 \/ 250,000/);assert.match(details.textContent,/30,000 笔订单/);assert.equal(polls.size,1);

serverJobs[0]={...serverJobs[0],status:'READY',processed:250000,total:250000,orders:60000,lines:250000,parts:4,filename:'订单成本-2026-10.zip',contentType:'application/zip'};
await runPoll();
const zipLink=details.querySelector('a[href$="/api/v1/finance/cost-exports/job-1/download"]');assert(zipLink);
assert.equal(zipLink.download,'订单成本-2026-10.zip');assert.match(zipLink.textContent,/下载 ZIP/);assert.match(details.textContent,/4 份/);
assert.equal(automaticDownloads.length,1,'ready transition requests browser saving once');assert.equal(polls.size,0,'ready-only lists stop polling');
assert.match(details.textContent,/已请求浏览器保存/);assert.doesNotMatch(details.textContent,/已保存|已落盘/);

serverJobs.unshift({id:'single',status:'READY',processed:100,total:100,orders:20,lines:100,parts:1,filename:'小范围成本.xlsx',contentType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',start:'2026-10-01',end:'2026-10-31'});
button('刷新任务状态',details).click();await settle();
const xlsxLink=details.querySelector('a[href$="/api/v1/finance/cost-exports/single/download"]');assert(xlsxLink);assert.equal(xlsxLink.download,'小范围成本.xlsx');assert.match(xlsxLink.textContent,/下载 Excel/);

serverJobs[0]={...serverJobs[0],status:'FAILED',error:'单笔订单明细超过上限'};
button('刷新任务状态',details).click();await settle();assert.match(details.textContent,/单笔订单明细超过上限/);

const slowPost=deferred();postGate=slowPost;button('导出 Excel').click();await settle();
const slowPosts=posts.length;
d.querySelector('[data-cost-status="PENDING"]').click();await settle();details=d.querySelector('[data-finance-cost-exports]');
button('导出 Excel').click();await settle();
assert.equal(posts.length,slowPosts,'a second scope is not silently submitted while the first POST is unresolved');
assert.match(d.querySelector('.fc-feedback').textContent,/上一个导出请求仍在确认中/);
assert.doesNotMatch(d.querySelector('.fc-feedback').textContent,/任务已提交/,'the new scope must not receive a false submitted toast');
slowPost.resolve();await settle();

button('导出 Excel').click();await settle();
assert.deepEqual(posts.at(-1).body,{start:'2026-10-01',end:'2026-10-31',shopId:'',groupId:'',platform:'',currency:'',kind:'SETTLED_ORDER',recordId:'',costStatus:'PENDING',numbers:'ORDER-A'},'all-shop pending export preserves the complete date/number scope and every page');

details.open=false;details.dispatchEvent(new w.Event('toggle'));assert.equal(polls.size,0,'closing the panel clears frontend polling');
serverJobs=[{id:'remembered',status:'READY',processed:1,total:1,orders:1,lines:1,parts:1,filename:'重新打开.xlsx',contentType:'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',start:'2026-10-01',end:'2026-10-31'}];
details.open=true;details.dispatchEvent(new w.Event('toggle'));await settle();assert(details.querySelector('a[download="重新打开.xlsx"]'),'reopening reloads the current user task list');

postFailure=Error('connection closed');button('导出 Excel').click();await settle();
const uncertainPost=posts.at(-1);assert(button('确认上次任务',details));
postFailure=null;button('确认上次任务',details).click();await settle();
assert.equal(posts.at(-1).options.headers['Idempotency-Key'],uncertainPost.options.headers['Idempotency-Key']);
assert.deepEqual(posts.at(-1).body,uncertainPost.body);assert.equal(posts.length,5,'uncertain delivery is retried only by an explicit confirmation');

const oldLink=details.querySelector('a[download]');const beforeCrossUser=automaticDownloads.length;
w.currentUser={organizationId:'org-b',userId:'user-b',permissions:['finance:view']};
oldLink.click();
assert.equal(automaticDownloads.length,beforeCrossUser,'an old company/user link is invalidated before browser navigation');
assert.match(details.textContent,/登录信息已变化/);

w.FinanceWorkspace.leave();assert.equal(polls.size,0,'leaving clears the polling timer');
w.currentUser={organizationId:'org-a',userId:'user-a',permissions:['finance:view']};
w.FinanceWorkspace.enter('costs');await settle();
const reopened=d.querySelector('[data-finance-cost-exports]');reopened.open=true;reopened.dispatchEvent(new w.Event('toggle'));await settle();
serverJobs=[{id:'pending-reset',status:'RUNNING',processed:0,total:1,start:'2026-10-01',end:'2026-10-31'}];button('刷新任务状态',reopened).click();await settle();assert.equal(polls.size,1);
w.FinanceWorkspace.enter('overview');assert.equal(polls.size,0,'navigating away from costs clears the polling timer');
w.FinanceWorkspace.enter('costs');await settle();
const resetPanel=d.querySelector('[data-finance-cost-exports]');resetPanel.open=true;resetPanel.dispatchEvent(new w.Event('toggle'));await settle();assert.equal(polls.size,1);
w.FinanceWorkspace.reset();assert.equal(polls.size,0,'reset clears the polling timer and owner state');

dom.window.close();
console.log('Cost export jobs: exact snapshot, idempotency, progress, XLSX/ZIP links, failure, recovery, owner isolation and timer cleanup passed.');
