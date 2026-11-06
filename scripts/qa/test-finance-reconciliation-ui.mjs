// Run with JSDOM_MODULE pointing at an installed jsdom module.
import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<!doctype html><div id="finance-page"></div>',{url:'https://example.test/#finance-reconciliation',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,requests=[];
w.currentUser={platformAdmin:true};w.matchMedia=()=>({matches:false});
w.ResizeObserver=class{observe(){}disconnect(){}};
let gate=null,fail=false;
w.api=async path=>{
  const url=new URL(path,'https://example.test');requests.push(url);
  assert.equal(url.pathname,'/api/v1/finance/reconciliation','reconciliation must not load dashboard or charts');
  if(gate)await gate;
  if(fail)throw Error('temporary outage');
  const page=Number(url.searchParams.get('page')||1),cny=url.searchParams.get('displayCurrency')==='CNY';
  const fields=[{id:'external_id',label:'账单',type:'text'},{id:'result',label:'结果',type:'text'}];
  return {data:{items:Array.from({length:page===1?50:7},(_,i)=>({id:`${page}-${i}`,external_id:`BILL-${page}-${i}`,result:'MATCHED'})),
    total:57,page,pageSize:50,filters:{...Object.fromEntries(url.searchParams),currency:'PHP',displayCurrency:cny?'CNY':'PHP'},
    currencies:['PHP'],shops:[{id:'shop',name:'Test shop',platform:'SHOPEE'}],sync:[],
    ...(cny?{fx:{id:'fixed',time:'2026-10-21',provider:'test'}}:{}),
    fieldSettings:{fields,visible:fields.map(f=>f.id)}}};
};
for(const file of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])
  w.eval(await fs.readFile(new URL('../../frontend/'+file,import.meta.url),'utf8'));
async function ready(){for(let i=0;i<100;i++){await new Promise(r=>setTimeout(r,5));if(d.querySelector('#finance-page').getAttribute('aria-busy')==='false')return;}throw Error('UI timed out');}
const button=text=>[...d.querySelectorAll('button')].find(n=>n.textContent.trim()===text);
w.FinanceWorkspace.enter('reconciliation');await ready();
assert.equal(d.querySelectorAll('tbody tr').length,50);assert(button('上一页').disabled);assert(!button('下一页').disabled);
assert.equal(d.querySelector('script[src*="echarts"]'),null);
button('下一页').click();await ready();
assert.equal(requests.at(-1).searchParams.get('page'),'2');assert.equal(d.querySelectorAll('tbody tr').length,7);
assert(button('下一页').disabled);assert(!button('上一页').disabled);
const firstRow=d.querySelector('tbody tr');
button('刷新').click();await ready();assert.equal(d.querySelector('tbody tr'),firstRow);
let release;gate=new Promise(resolve=>release=resolve);
const start=d.querySelector('[aria-label="开始日期"]');start.value='2026-10-01';start.dispatchEvent(new w.Event('change'));
assert.equal(d.querySelector('#finance-page').getAttribute('aria-busy'),'true');assert.equal(d.querySelector('tbody tr'),firstRow);
release();gate=null;await ready();
assert.equal(requests.at(-1).searchParams.get('start'),'2026-10-01');assert.equal(requests.at(-1).searchParams.get('page'),'1');
button('人民币 ¥').click();await ready();assert(d.querySelector('.fi-meta').textContent.includes('CNY'));
const retained=d.querySelector('tbody');fail=true;button('刷新').click();await ready();assert.equal(d.querySelector('tbody'),retained);
assert(d.querySelector('.fi-state').textContent.includes('已保留当前数据'));
w.FinanceWorkspace.leave();dom.window.close();
console.log('Reconciliation: one endpoint, 50-row pagination, calendar reset, CNY, stable refresh and failure retention passed.');
