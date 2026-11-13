import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-costs',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,paths=[];
w.currentUser={organizationId:'company',userId:'reader',permissions:['finance:view']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
const shops=[{id:'shop',name:'财务店',group_id:'group',platform:'SHOPEE',region:'PH',currency:'PHP',timezone:'Asia/Manila'}];
w.api=async path=>{
  const u=new URL(path,'https://example.test');paths.push(u.pathname);
  if(u.pathname.endsWith('/shops'))return {data:{items:shops,groups:[{id:'group',name:'分组'}],sync:[]}};
  if(u.pathname.endsWith('/costs/context'))return {data:{live:true,catalogLoaded:false,settingsLoaded:false,shopId:'shop',groupId:'group',groupName:'分组'}};
  if(u.pathname.endsWith('/label-costs'))return {data:{rows:[],total:0,files:[],period:{orders:0,amount_cny:0,conflicts:0,shipments:0},state:{},processing:false,basis:'fixture'}};
  throw Error('Unexpected eager query '+u.pathname);
};
for(const file of ['incremental-dom.js','incremental-charts.js','finance-label-costs.js','finance-costs.js','finance-workspace.js'])
  w.eval(await fs.readFile(new URL('../../frontend/'+file,import.meta.url),'utf8'));
w.FinanceWorkspace.enter('costs');
for(let i=0;i<100;i++){
  await new Promise(resolve=>setTimeout(resolve,10));
  if(d.querySelector('#finance-page').getAttribute('aria-busy')==='false'&&d.body.textContent.includes('结算期贴单金额'))break;
}
assert(paths.includes('/api/v1/finance/workspace/costs/context'),'Cost entry must use the lightweight context before the invoice module');
assert(!paths.includes('/api/v1/finance/workspace/costs'),'Opening monthly invoices must not wait for the complete cost catalogue');
assert(d.body.textContent.includes('月度贴单账单'));
assert(d.body.textContent.includes('结算期贴单金额'));
w.FinanceWorkspace.reset();dom.window.close();
console.log('Cost entry: real parent and invoice module use context without eager catalogue passed.');
