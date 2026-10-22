import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-costs',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,mutations=[];
w.currentUser={permissions:['finance:manage']};w.matchMedia=()=>({matches:false});
w.ResizeObserver=class{observe(){}disconnect(){}};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
const shops=[{id:'shop',name:'测试店',platform:'TIKTOK_SHOP',group_id:'group',region:'PH'}],groups=[{id:'group',name:'母婴组'}];
let costs={live:true,shopId:'shop',groupId:'group',groupName:'母婴组',sourceName:'成本.csv',costs:[{sku:'PACK',warehouse:'海外仓',prices:{'2026-09':2}}],
  products:[{shop_id:'shop',product_id:'product',sku_id:'variant',seller_sku:'PACK',name:'商品',spec:'一包'}],sampleOrders:1,orderLimit:2000,
  settings:{warehouse:'海外仓',rules:{currency:'CNY',unit:'包',basis:'shipment',missing:'pending',freight:'',inbound:''}},settingsVersion:1,mappings:{}};
let expenses={rows:[],rules:[],changes:[],totals:{records:0,amount:0,posted:0,included:0,pending:0},page:1,pageSize:50};
w.api=async(path,options={})=>{
  const url=new URL(path,'https://example.test'),body=options.body?JSON.parse(options.body):null;
  if(options.method&&options.method!=='GET'){
    mutations.push({path:url.pathname,method:options.method,body});
    if(path.endsWith('/mapping')){costs.mappings['shop:product:variant:海外仓']={...body,version:1,groupId:'group'};return {data:'已保存'};}
    if(path.endsWith('/settings')){costs.settings={rules:body.rules,warehouse:body.warehouse};costs.settingsVersion++;return {data:'已保存'};}
    if(path.endsWith('/expenses')){const row={...body,id:'expense-id',version:1,status:'已计入'};expenses={...expenses,rows:[row],totals:{records:1,amount:12.5,posted:12.5,included:0,pending:0}};return {data:row};}
    throw Error('Unexpected mutation '+path);
  }
  if(url.pathname==='/api/v1/finance/shops')return {data:{items:shops,groups,sync:[]}};
  if(url.pathname==='/api/v1/finance/workspace/costs')return {data:structuredClone(costs)};
  if(url.pathname==='/api/v1/finance/workspace/expenses')return {data:structuredClone(expenses)};
  throw Error('Unexpected GET '+path);
};
for(const name of ['incremental-dom.js','incremental-charts.js','finance-costs.js','finance-expenses-design.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../frontend/'+name,import.meta.url),'utf8'));
const button=(text,root=d)=>[...root.querySelectorAll('button')].find(n=>n.textContent.trim()===text||n.textContent.trim().endsWith(text));
const settle=()=>new Promise(r=>setTimeout(r,20));
async function click(text,root=d){const n=button(text,root);assert(n,text);n.click();await settle();}
function field(name,value,root=d){const n=root.querySelector(`[aria-label="${name}"]`);assert(n,name);n.value=value;n.dispatchEvent(new w.Event('change'));}
w.FinanceWorkspace.enter('costs');await settle();
assert(d.body.textContent.includes('成本准备'));assert(!d.body.textContent.includes('设计预览'));
await click('SKU 关联');await click('关联',d.querySelector('tbody'));
let dialog=d.querySelector('dialog');await click('保存关联',dialog);
assert.equal(mutations.length,0,'confirmation is required before persisting a mapping');
dialog.querySelector('input[type=checkbox]').checked=true;await click('保存关联',dialog);
assert.equal(mutations[0].path,'/api/v1/finance/workspace/costs/shop/mapping');
assert.equal(mutations[0].body.sku_id,'variant');assert(d.querySelector('tbody').textContent.includes('已关联'));
await click('仓库与规则');field('成本归属月份','order');await click('保存成本规则');
assert.equal(mutations[1].body.rules.basis,'order');assert.equal(mutations[1].body.version,1);
w.FinanceWorkspace.enter('fees');await settle();assert(d.body.textContent.includes('费用台账总额'));assert(d.body.textContent.includes('平台费用 / 原有明细'));assert(!d.body.textContent.includes('设计预览'));
await click('新增费用');dialog=d.querySelector('dialog');
for(const [name,value]of [['归属店铺','shop'],['订单编号','known-order'],['费用日期','2026-10-22'],['计入财务日期','2026-10-22'],['金额','12.50'],['费用来源编号','waybill-1'],['计入方式','extra']])field(name,value,dialog);
await click('保存费用',dialog);
assert.equal(mutations[2].method,'POST');assert.equal(mutations[2].body.amount,'12.50');
assert(d.querySelector('tbody').textContent.includes('waybill-1'));
await click('刷新');assert(d.querySelector('tbody').textContent.includes('waybill-1'),'saved record survives reload');
w.FinanceWorkspace.reset();w.currentUser={permissions:['finance:view']};w.FinanceWorkspace.enter('fees');await settle();
assert(!button('新增费用'));assert(!button('调整',d.querySelector('tbody')));
w.FinanceWorkspace.leave();w.close();
console.log('Live cost/expense UI: authorized endpoints, confirmed SKU mapping, versioned settings, exact decimal input, persisted refresh and read-only roles passed.');
