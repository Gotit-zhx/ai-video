import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<!doctype html><div id="finance-page"></div>',{url:'https://example.test/#finance-shops',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,requests=[];
let adConfigured=true,copied='';
Object.defineProperty(w.navigator,'clipboard',{value:{writeText:async value=>{copied=value;}}});
w.currentUser={roles:['STAFF'],permissions:['finance:manage']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
w.echarts={init:()=>({setOption(){},resize(){},dispose(){},getWidth(){return 1;},getHeight(){return 1;}})};
let groups=[{id:'clothes',name:'服饰',shop_count:1},{id:'shoes',name:'鞋靴',shop_count:1}];
const shops=[{id:'shop1',name:'服饰店',platform:'SHOPEE',currency:'PHP',timezone:'Asia/Manila',group_id:'clothes',enabled:true},
  {id:'shop2',name:'鞋店',platform:'TIKTOK_SHOP',currency:'PHP',timezone:'Asia/Manila',group_id:'shoes',enabled:true},
  {id:'shop3',name:'未分组店',platform:'SHOPEE',currency:'PHP',timezone:'Asia/Manila',enabled:true}];
w.api=async(path,options={})=>{
  const url=new URL(path,'https://example.test'),method=options.method||'GET',body=options.body?JSON.parse(options.body):{};
  requests.push({url,method,body});const endpoint=url.pathname.replace('/api/v1/finance','');
  if(endpoint==='/shops')return {data:{items:shops,sync:[],groups}};
  if(endpoint==='/ads')return {data:[]};
  if(endpoint==='/ads/application')return {data:{configured:adConfigured,managed:true}};
  if(endpoint==='/ads/authorize')return {data:{authorizationUrl:'https://business-api.tiktok.com/portal/auth?state=opaque',expiresAt:'2026-10-22T20:00:00Z'}};
  if(endpoint==='/groups'&&method==='GET')return {data:groups};
  if(endpoint==='/groups'&&method==='POST'){groups=[...groups,{id:'new',name:body.name,shop_count:0}];return {data:{id:'new'}};}
  if(endpoint.startsWith('/groups/')){const id=endpoint.split('/').at(-1);groups=method==='DELETE'?groups.filter(g=>g.id!==id):groups.map(g=>g.id===id?{...g,name:body.name}:g);return {data:'已保存'};}
  if(endpoint==='/shops/authorize'){assert(body.groupId,'OAuth requires a group');return {data:{authorizationUrl:'https://official.test/authorize?state=opaque',expiresAt:'2026-10-22T20:00:00Z'}};}
  const fields=[{id:'external_id',label:'编号',type:'text'}],page=Number(url.searchParams.get('page')||1);
  const common={filters:{...Object.fromEntries(url.searchParams),currency:'PHP',displayCurrency:'PHP'},groups,shops,sync:[],currencies:['PHP'],
    totals:[{kind:'SETTLEMENT',amount:1200,records:3},{kind:'BILL',amount:1200,records:1},{kind:'PENDING',amount:300,records:2}],
    ranking:[],statuses:[],fees:[],trend:[],basis:'测试数据',updatedAt:'2026-10-22T00:00:00Z',hourly:[],recent:[]};
  if(['/overview','/screen'].includes(endpoint))return {data:common};
  if(['/records','/reconciliation'].includes(endpoint))return {data:{...common,items:[{id:'record1',external_id:'A1'}],total:51,page,pageSize:50,fieldSettings:{fields,visible:['external_id']}}};
  throw Error('Unexpected API '+endpoint);
};
for(const file of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../frontend/'+file,import.meta.url),'utf8'));
const button=(text,scope=d)=>[...scope.querySelectorAll('button')].find(n=>n.textContent.trim()===text);
async function ready(){for(let i=0;i<200;i++){await new Promise(r=>setTimeout(r,5));if(d.querySelector('#finance-page').getAttribute('aria-busy')==='false')return;}throw Error('UI timeout');}
async function tick(){await new Promise(r=>setTimeout(r,15));}
async function choose(label,value){const select=d.querySelector(`[aria-label="${label}"]`);assert(select,label);select.value=value;select.dispatchEvent(new w.Event('change'));await ready();}
w.FinanceWorkspace.enter('shops');await ready();
assert(button('分组管理'));assert.equal(d.querySelectorAll('.fi-shop-controls').length,3);
assert(!button('广告应用配置'));assert(!button('连接广告账户'));
button('复制广告授权链接').click();await tick();
let adDialog=d.querySelector('dialog');assert(adDialog);assert(!adDialog.querySelector('input[type=password]'));
assert(adDialog.querySelector('.fi-authorization-link'));assert.equal(copied,'https://business-api.tiktok.com/portal/auth?state=opaque');
assert.equal(requests.filter(r=>r.url.pathname.endsWith('/ads/authorize')).length,1);
assert.deepEqual(requests.at(-1).body,{advertiserId:'',shopId:'',gmvStoreId:''});
assert(!adDialog.querySelector('details').open);
const gmv=adDialog.querySelector('details label:last-of-type input');gmv.value='123456';gmv.dispatchEvent(new w.Event('input'));
assert(!adDialog.querySelector('.fi-authorization-link'));adDialog.close();
adConfigured=false;button('复制广告授权链接').click();await tick();adDialog=d.querySelector('dialog');
assert(adDialog.textContent.includes('服务端配置'));assert(!adDialog.querySelector('.fi-authorization-link'));
assert.equal(requests.filter(r=>r.url.pathname.endsWith('/ads/authorize')).length,1);adDialog.close();adConfigured=true;
requests.length=0;
button('连接店铺').click();await tick();
let dialog=d.querySelector('dialog');assert(dialog);button('生成授权链接',dialog).click();await tick();
assert(dialog.textContent.includes('请选择店铺分组'));assert(!requests.some(r=>r.url.pathname.endsWith('/authorize')));
await choose('店铺分组（必选）','clothes');button('生成授权链接',dialog).click();await tick();
assert(dialog.querySelector('.fi-authorization-link'));assert.equal(requests.at(-1).body.groupId,'clothes');
await choose('店铺分组（必选）','shoes');assert(!dialog.querySelector('.fi-authorization-link'));dialog.close();
button('分组管理').click();await tick();dialog=d.querySelector('dialog');
dialog.querySelector('.fi-group-create input').value='家居';button('添加分组',dialog).click();await tick();assert(dialog.textContent.includes('分组已添加'));
const created=[...dialog.querySelectorAll('.fi-group-row')].find(row=>row.querySelector('input').value==='家居');assert(created);
created.querySelector('input').value='家纺';button('保存',created).click();await tick();assert(groups.some(g=>g.name==='家纺'));
button('删除',[...dialog.querySelectorAll('.fi-group-row')].find(row=>row.querySelector('input').value==='家纺')).click();await tick();assert(!groups.some(g=>g.name==='家纺'));dialog.close();await ready();
await choose('店铺分组','clothes');assert.equal(d.querySelectorAll('.fi-shop-controls').length,1);
w.FinanceWorkspace.enter('orders');await ready();assert.equal(requests.at(-1).url.searchParams.get('groupId'),'clothes');
await choose('店铺','shop1');button('下一页').click();await ready();assert.equal(requests.at(-1).url.searchParams.get('page'),'2');
await choose('店铺分组','shoes');assert.equal(requests.at(-1).url.searchParams.get('page'),'1');assert.equal(requests.at(-1).url.searchParams.get('shopId'),'');
assert.equal(d.querySelector('[aria-label="店铺"]').options.length,2);
for(const route of ['overview','reconciliation','settlements','fees','ads','live']){
  w.FinanceWorkspace.enter(route);await ready();
  assert.equal(requests.at(-1).url.searchParams.get('groupId'),'shoes',route+' retains group');
  if(route==='settlements'){assert.equal(d.querySelectorAll('.fi-settlement-totals strong').length,3);assert(d.querySelector('.fi-settlement-chart'));assert(d.querySelector('.fi-settlement-totals').textContent.includes('1,200'));}
}
w.FinanceWorkspace.reset();w.currentUser={roles:['STAFF'],permissions:['finance:view']};
assert(w.FinanceWorkspace.allowed());assert(!w.FinanceWorkspace.canManage());
w.FinanceWorkspace.enter('shops');await ready();
for(const label of ['连接店铺','分组管理','配置','移除','重新授权','广告应用配置','复制广告授权链接'])assert(!button(label),'viewer must not see '+label);
w.FinanceWorkspace.enter('overview');await ready();assert(button('导出报表'));assert(!button('导入账单'));
w.currentUser={roles:['STAFF'],permissions:[]};assert(!w.FinanceWorkspace.allowed());
w.FinanceWorkspace.leave();dom.window.close();
console.log('Finance groups UI: required OAuth group, stale-link removal, CRUD, role gates, group propagation, page reset, shop filtering and settlement summaries passed.');
