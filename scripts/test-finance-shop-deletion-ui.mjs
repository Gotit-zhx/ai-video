import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<!doctype html><div id="finance-page"></div>',{url:'https://example.test/#finance-shops',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,removed=[];
w.currentUser={permissions:['finance:manage']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
let failure=true,deletions=[],shops=[
  {id:'target',name:'母婴店',platform:'SHOPEE',enabled:true,currency:'PHP',timezone:'Asia/Manila'},
  {id:'sibling',name:'另一家店',platform:'SHOPEE',enabled:true,currency:'PHP',timezone:'Asia/Manila'}
];
w.api=async(path,options={})=>{
  const endpoint=new URL(path,'https://example.test').pathname;
  if(options.method==='DELETE'){
    assert.equal(endpoint,'/api/v1/finance/shops/target');removed.push(endpoint);
    if(failure)throw Error('提交暂未完成，请重试');
    shops=shops.filter(s=>s.id!=='target');
    deletions=[{shop_id:'target',shop_label:'母婴店',status:'RUNNING',deleted_rows:500,requested_at:'2026-10-24T07:00:00Z'}];
    return {data:'已停止同步，数据正在后台分批删除'};
  }
  if(endpoint.endsWith('/shops'))return {data:{items:shops,sync:[],groups:[],deletions}};
  if(endpoint.endsWith('/ads'))return {data:[]};
  if(endpoint.endsWith('/ads/application'))return {data:{configured:false}};
  throw Error('Unexpected request '+endpoint);
};
for(const name of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])
  w.eval(await fs.readFile(new URL('../frontend/'+name,import.meta.url),'utf8'));
const button=(text,parent=d)=>[...parent.querySelectorAll('button')].find(n=>n.textContent.trim()===text);
async function ready(){for(let i=0;i<200;i++){await new Promise(r=>setTimeout(r,5));if(d.querySelector('#finance-page').getAttribute('aria-busy')==='false')return;}throw Error('timeout');}
const pause=()=>new Promise(r=>setTimeout(r,10));
w.FinanceWorkspace.enter('shops');await ready();
button('移除').click();let dialog=d.querySelector('dialog');
assert(dialog.textContent.includes('此操作不可恢复'));
assert(button('永久删除',dialog).disabled);
dialog.querySelector('input').value='其他店';dialog.querySelector('input').dispatchEvent(new w.Event('input'));
assert(button('永久删除',dialog).disabled);assert.equal(removed.length,0);
button('取消',dialog).click();assert.equal(removed.length,0);
button('移除').click();dialog=d.querySelector('dialog');
dialog.querySelector('input').value='母婴店';dialog.querySelector('input').dispatchEvent(new w.Event('input'));
assert(!button('永久删除',dialog).disabled);
button('永久删除',dialog).click();await pause();
assert(dialog.textContent.includes('提交暂未完成'));
assert.equal(d.querySelectorAll('.fi-shop-controls').length,2,'failed submission leaves both shops visible');
failure=false;button('永久删除',dialog).click();await pause();await ready();
assert(!d.querySelector('dialog'));
assert.equal(d.querySelectorAll('.fi-shop-controls').length,1);
assert(d.querySelector('#finance-page').textContent.includes('另一家店'));
assert(d.querySelector('#finance-page').textContent.includes('正在删除'));
assert(d.querySelector('#finance-page').textContent.includes('500'));
deletions[0]={...deletions[0],shop_label:'',status:'COMPLETE',deleted_rows:1201};
button('刷新').click();await ready();
assert(d.querySelector('#finance-page').textContent.includes('已彻底删除'));
assert(!d.querySelector('#finance-page').textContent.includes('母婴店'));
w.FinanceWorkspace.leave();dom.window.close();
console.log('Shop deletion UI: explicit name confirmation, cancellation, failure recovery, sibling preservation and completion progress passed.');
