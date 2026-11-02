import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-shops',runScripts:'outside-only'});
const w=dom.window,d=w.document;
w.currentUser={permissions:['finance:view']};w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
const shops=[{id:'my',name:'Wasup Clothing.MY',platform:'TIKTOK_SHOP',currency:'MYR',timezone:'Asia/Kuala_Lumpur',enabled:true,authorization_status:'ACTIVE'}];
w.api=async path=>({data:path.endsWith('/ads')?[]:{items:shops,groups:[],sync:[
 {shop_id:'my',channel:'ORDER_REPAIR',status:'SYNCING',window_start:'0',window_end:'0'},
 {shop_id:'my',channel:'ORDER',status:'SYNCING',window_start:'1788192000',window_end:'1788796800'},
 {shop_id:'my',channel:'REFUND',status:'SYNCING',window_start:'0',window_end:'0'}]}});
for(const file of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../frontend/'+file,import.meta.url),'utf8'));
try{
 w.FinanceWorkspace.enter('shops');
 for(let i=0;i<100&&d.querySelector('main').getAttribute('aria-busy')!=='false';i++)await new Promise(r=>setTimeout(r,5));
 const text=d.querySelector('main').textContent;
 assert(!text.includes('1970'));assert(!text.includes('1969'));
 assert(text.includes('补齐中'));assert(text.includes('按结算账单补齐缺失订单'));
 const history=d.querySelector('.fi-progress[data-update-key="ORDER"]');assert(history.textContent.includes('2026/9/1'));assert(history.textContent.includes('2026/9/7'));
 console.log('PASS sync dates: repair sentinel zero is not a date; real historical ranges remain visible.');
}finally{w.FinanceWorkspace.reset();dom.window.close();}
