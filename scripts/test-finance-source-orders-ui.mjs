import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-orders',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,calls=[],exports=[];
let linkedReturnSelection;
w.FinanceQianyi={openReturns:async options=>{linkedReturnSelection=options.selection;}};
w.currentUser={organizationId:'org',userId:'user',permissions:['finance:view']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
w.echarts={init:()=>({setOption(){},resize(){},dispose(){}})};
w.URL.createObjectURL=()=> 'blob:qa';w.URL.revokeObjectURL=()=>{};w.HTMLAnchorElement.prototype.click=function(){};
w.fetch=async path=>{exports.push(new URL(path,'https://example.test'));return {ok:true,blob:async()=>new w.Blob(['qa'])};};
const shops=[{id:'s',name:'平台原名',display_name:'共享别名',platform:'SHOPEE',group_id:'g',timezone:'Asia/Bangkok'},
  {id:'tk',name:'TK店铺',platform:'TIKTOK_SHOP',group_id:'g'}],groups=[{id:'g',name:'分组'}];
const fields=[{id:'record_date',label:'结算日期',type:'date'},{id:'shop_id',label:'店铺',type:'text'},
  {id:'order_id',label:'订单编号',type:'text'},{id:'status',label:'状态',type:'text'},
  {id:'first_settlement_date',label:'首次平台结算日期',type:'date'}];
const settings={view:'ORDER',fields,visible:['record_date','shop_id','order_id','status'],exported:['record_date','order_id'],defaults:{visible:['order_id'],exported:['order_id']}};
let delayed=null;
w.api=async path=>{
  const url=new URL(path,'https://example.test'),q=Object.fromEntries(url.searchParams);calls.push(url);
  const context={filters:{...q,currency:q.currency||'',displayCurrency:q.displayCurrency},shops,groups,currencies:['THB'],sync:[],
    fx:{id:'fixed',provider:'QA',time:'2026-11-05'},totals:[],trend:[],fees:[],statuses:[],ranking:[]};
  if(url.pathname.endsWith('/overview'))return {data:context};
  if(url.pathname.includes('/records/'))return {data:{id:'cancel',shop_id:'s',kind:'ORDER',order_id:'CANCELLED-1',status:'CANCELLED',record_date:'2026-10-09',first_settlement_date:null,order_date_basis:'CREATED'}};
  if(url.pathname.includes('/fields/'))return {data:settings};
  if(url.pathname.endsWith('/records')){
    if(delayed){const gate=delayed;delayed=null;await new Promise(resolve=>gate.release=resolve);}
    return {data:{...context,items:q.orderScope==='ALL'?[
      {id:'cancel',shop_id:'s',order_id:'CANCELLED-1',status:'CANCELLED',record_date:'2026-10-09',first_settlement_date:null,order_date_basis:'CREATED'},
      {id:'nov',shop_id:'s',order_id:'NOVEMBER-1',status:'COMPLETED',record_date:'2026-10-25',first_settlement_date:'2026-11-01',order_date_basis:'CREATED'}
    ]:[{id:'settled',shop_id:'s',order_id:'OCTOBER-1',status:'COMPLETED',record_date:'2026-10-29'}],
      total:101,page:Number(q.page),pageSize:50,fieldSettings:settings}};
  }
  throw Error('Unexpected request '+path);
};
for(const file of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../frontend/'+file,import.meta.url),'utf8'));
const tick=()=>new Promise(r=>setTimeout(r,5));
async function ready(){for(let i=0;i<200;i++){await tick();if(d.querySelector('#finance-page').getAttribute('aria-busy')==='false')return;}throw Error(d.body.textContent);}
const button=text=>[...d.querySelectorAll('button')].find(n=>n.textContent.trim()===text);
function change(label,value){const n=d.querySelector(`[aria-label="${label}"]`);assert(n,label);n.value=value;n.dispatchEvent(new w.Event('change'));}
w.FinanceWorkspace.enter('orders');await ready();
assert.equal(calls.length,1);assert.equal(calls[0].searchParams.get('orderScope'),'ALL');
assert(d.querySelector('tbody').textContent.includes('已取消'));
assert(d.querySelector('tbody').textContent.includes('暂无结算'));
assert(d.querySelector('tbody').textContent.includes('2026-11-01'));
assert(d.querySelector('thead').textContent.includes('下单日期（店铺时区）'));
assert(!d.querySelector('.fi-product-cost-summary'),'unsettled rows must not display settled-accounting totals as their total');
change('店铺分组','g');await ready();change('店铺','s');await ready();
const search=d.querySelector('.fi-number-search textarea');search.value='CANCELLED-1\nNOVEMBER-1';search.closest('form').dispatchEvent(new w.Event('submit',{cancelable:true}));await ready();
assert.equal(calls.at(-1).searchParams.get('numbers'),'CANCELLED-1,NOVEMBER-1');
button('下一页').click();await ready();assert.equal(calls.at(-1).searchParams.get('page'),'2');
button('CANCELLED-1').click();await tick();await tick();
assert.equal(calls.findLast(u=>u.pathname.includes('/records/')).searchParams.get('orderScope'),'ALL');
assert(d.querySelector('dialog').textContent.includes('下单日期（店铺时区）'));
button('查看该订单的千易售后').click();await tick();
assert.equal(linkedReturnSelection.shopId,'s');assert.equal(linkedReturnSelection.numbers,'CANCELLED-1');
assert.equal(linkedReturnSelection.start,'');assert.equal(linkedReturnSelection.end,'');
assert.equal(linkedReturnSelection.receiptScope,'ALL');assert.equal(linkedReturnSelection.receiptStatus,'');
button('取消').click();
button('导出报表').click();await tick();button('下载报表').click();await tick();await tick();
assert.equal(exports.at(-1).searchParams.get('view'),'ORDER');
assert.equal(exports.at(-1).searchParams.get('orderScope'),'ALL');
assert.equal(exports.at(-1).searchParams.get('numbers'),'CANCELLED-1,NOVEMBER-1');
assert.equal(exports.at(-1).searchParams.get('shopId'),'s');
const gate={};delayed=gate;button('刷新').click();await tick();
change('订单范围','SETTLED');await ready();
assert(d.querySelector('tbody').textContent.includes('OCTOBER-1'));
assert(d.querySelector('thead').textContent.includes('首次平台结算日期'));
gate.release();await tick();assert(!d.querySelector('tbody').textContent.includes('CANCELLED-1'),'late raw-order data cannot overwrite settlement selection');
change('订单范围','ALL');await ready();assert.equal(calls.at(-1).searchParams.get('page'),'1');
change('平台','TIKTOK_SHOP');await ready();assert.equal(calls.at(-1).searchParams.get('platform'),'TIKTOK_SHOP');
assert.equal(calls.at(-1).searchParams.get('orderScope'),'ALL');assert.equal(d.querySelector('[aria-label="店铺"]').value,'');
w.FinanceWorkspace.reset();dom.window.close();
console.log('PASS source orders: cancellations, cross-month settlement, aliases, lookup, export, pagination, detail, platform/group filters and stale-response isolation.');
