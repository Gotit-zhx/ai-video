import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const storageKey='heyi:finance-filters:v1:'+JSON.stringify(['org','user']);
const shops=[
  {id:'my',name:'马来店',currency:'MYR',platform:'TIKTOK_SHOP',amount:200,rate:1.6},
  {id:'ph',name:'菲律宾店',currency:'PHP',platform:'TIKTOK_SHOP',amount:100,rate:0.12}
];
const scripts=await Promise.all(['incremental-dom.js','incremental-charts.js','finance-workspace.js'].map(file=>
  fs.readFile(file==='finance-workspace.js'&&process.env.FINANCE_WORKSPACE_SOURCE||new URL('../../frontend/'+file,import.meta.url),'utf8')));
async function boot(saved,route='overview'){
  const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/',runScripts:'outside-only',pretendToBeVisual:true});
  const w=dom.window,d=w.document,reads=[],downloads=[];
  w.currentUser={organizationId:'org',userId:'user',permissions:['finance:view']};
  w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
  w.echarts={init:()=>({setOption(){},resize(){},dispose(){}})};
  w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
  w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
  w.HTMLAnchorElement.prototype.click=function(){};
  w.URL.createObjectURL=()=> 'blob:test';w.URL.revokeObjectURL=()=>{};
  w.fetch=async path=>{downloads.push(new URL(path,'https://example.test'));return {ok:true,blob:async()=>new w.Blob(['test'])};};
  if(saved)w.sessionStorage.setItem(storageKey,JSON.stringify(saved));
  w.api=async path=>{
    const url=new URL(path,'https://example.test'),q=Object.fromEntries(url.searchParams);
    reads.push(url);
    if(url.pathname.endsWith('/ads'))return {data:[]};
    const cny=q.displayCurrency==='CNY',available=shops.filter(s=>!q.shopId||s.id===q.shopId);
    const currency=q.currency||(cny?'':available[0]?.currency||'');
    const selected=available.filter(s=>!currency||s.currency===currency);
    const amount=selected.reduce((sum,s)=>sum+s.amount*(cny?s.rate:1),0);
    const fields=[{id:'order_id',label:'订单编号',type:'text'},{id:'amount',label:'金额',type:'money'}];
    return {data:{
      filters:{...q,currency,displayCurrency:cny?'CNY':currency},shops,groups:[],currencies:['MYR','PHP'],
      fx:cny?{id:'test-rate',time:'2026-10-29T00:00:00Z',provider:'test'}:null,
      sync:[],trend:[],hourly:[],fees:[],statuses:[],recent:[],ranking:[],
      totals:['ORDER','SETTLEMENT','BILL','PENDING','FEE','AD'].map(kind=>({kind,amount,revenue:amount,records:selected.length,known_amounts:selected.length,known_revenues:selected.length})),
      profit:{currency:cny?'CNY':currency,settledGrossAmount:amount,amount,platformFees:0,advertising:0,productCost:0,operatingCost:0,notes:[]},
      items:selected.map(s=>({id:s.id,order_id:'ORDER-'+s.id,shop_id:s.id,currency:s.currency,amount:s.amount,cny_amount:s.amount*s.rate,kind:q.kind||'BILL',platform:s.platform})),
      fieldSettings:{fields,visible:['order_id','amount'],exported:['order_id','amount']},total:selected.length,page:1,pageSize:50
    }};
  };
  scripts.forEach(s=>w.eval(s));
  const settle=async()=>{for(let n=0;n<100;n++){await new Promise(r=>setTimeout(r,5));if(d.querySelector('main').getAttribute('aria-busy')==='false')return;}throw Error('UI timeout');};
  const select=async(label,value)=>{const e=d.querySelector(`[aria-label="${label}"]`);assert(e,label);e.value=value;assert.equal(e.value,value);e.dispatchEvent(new w.Event('change'));await settle();};
  const click=async(text,host=d)=>{const b=[...host.querySelectorAll('button')].find(e=>e.textContent.trim()===text);assert(b,text);b.click();await settle();};
  const lastReport=()=>reads.filter(r=>/\/(overview|reconciliation|screen)$/.test(r.pathname)).at(-1);
  w.FinanceWorkspace.enter(route);await settle();
  return {dom,w,d,reads,downloads,settle,select,click,lastReport};
}
const calendar={pinnedDates:true,start:'2026-09-01',end:'2026-09-30',shopId:'',groupId:'',platform:''};
for(const mode of ['CNY','ORIGINAL'])for(const route of ['overview','settlements','orders','reconciliation','fee-records','ads','live']){
  const t=await boot({...calendar,displayCurrency:mode,currency:'MYR'},route);
  try{
    const first=t.lastReport().searchParams;
    assert.equal(first.get('currency'),'','legacy '+mode+' MYR filter must not narrow default '+route);
    assert.equal(first.get('displayCurrency'),'CNY','default entry always uses RMB');
    assert.equal(first.get('shopId'),'');
    if(route!=='live'){assert.equal(first.get('start'),calendar.start);assert.equal(first.get('end'),calendar.end);}
    if(route==='overview')assert.equal(t.d.querySelector('[data-metric="SETTLEMENT"] strong').textContent,'332');
    const saved=JSON.parse(t.w.sessionStorage.getItem(storageKey));
    assert(!saved.currency,'resolved/previous native currency must not survive reload');
    assert(!saved.displayCurrency||saved.displayCurrency==='CNY');
    if(route==='settlements'){
      await t.click('导出 Excel');await t.click('下载报表',t.d.querySelector('dialog'));
      assert.equal(t.downloads.at(-1).searchParams.get('currency'),'');
      assert.equal(t.downloads.at(-1).searchParams.get('displayCurrency'),'CNY');
    }
  }finally{t.w.FinanceWorkspace.reset();t.dom.window.close();}
}
// Native viewing remains available when explicitly chosen during this visit.
const t=await boot(calendar);
try{
  await t.click('原币',t.d.querySelector('.fi-pills'));await t.select('原币','MYR');
  assert.equal(t.lastReport().searchParams.get('currency'),'MYR');
  await t.select('原币','');
  assert.equal(t.lastReport().searchParams.get('displayCurrency'),'CNY','all currencies means an RMB aggregate');
  assert.equal(t.lastReport().searchParams.get('currency'),'');
  assert.equal(t.d.querySelector('[data-metric="SETTLEMENT"] strong').textContent,'332');
  await t.select('店铺','my');await t.select('店铺','');
  assert.equal(t.d.querySelector('[data-metric="SETTLEMENT"] strong').textContent,'332');
  await t.select('原币','MYR');await t.select('店铺','');
  assert.equal(t.lastReport().searchParams.get('currency'),'','reselecting all shops clears the previous currency even when shopId stays empty');
  assert.equal(t.d.querySelector('[data-metric="SETTLEMENT"] strong').textContent,'332');
}finally{t.w.FinanceWorkspace.reset();t.dom.window.close();}
console.log('Default finance currency passed: legacy MYR cache, all reporting routes, calendar retention, all-country totals, explicit native selection and matching exports.');
