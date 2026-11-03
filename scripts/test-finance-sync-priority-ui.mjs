import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-shops',runScripts:'outside-only'});
const w=dom.window,d=w.document;
w.currentUser={permissions:['finance:view','finance:manage']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
const shops=['a','b','c'].map((id,i)=>({id,name:'原名'+id,display_name:'别名'+id,platform:'TIKTOK_SHOP',currency:'PHP',timezone:'Asia/Manila',enabled:true,authorization_status:'ACTIVE'}));
shops[2].enabled=false;
const time='2026-11-03T02:00:00Z';
const states=[
 {shop_id:'a',channel:'ORDER',status:'RUNNING',execution_state:'RUNNING',initial_sync_complete:false,window_start:'1788192000',window_end:'1788796800',last_progress_at:time},
 {shop_id:'a',channel:'SETTLEMENT',status:'SYNCING',execution_state:'WAITING_SHOP',blocking_channel:'ORDER',initial_sync_complete:false},
 {shop_id:'b',channel:'ORDER',status:'SYNCING',execution_state:'QUEUED',queue_position:1,initial_sync_complete:false,priority_requested_at:time,shop_priority_requested_at:time,priority_queue_position:1,priority_pending_channels:2},
 {shop_id:'b',channel:'REFUND',status:'RATE_LIMITED',execution_state:'RATE_LIMITED',next_at:time,initial_sync_complete:true,error_message:'平台请求过快',priority_requested_at:time,shop_priority_requested_at:time,priority_queue_position:1,priority_pending_channels:2},
 {shop_id:'c',channel:'ORDER',status:'SUCCESS',execution_state:'SUCCESS',initial_sync_complete:true,last_success_at:time}
];
const calls=[];
w.api=async(path,options)=>{calls.push({path,options});return {data:path.endsWith('/ads')?[]:path.endsWith('/sync')?'已加入店铺优先队列':{items:shops,groups:[],sync:states}};};
for(const file of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../frontend/'+file,import.meta.url),'utf8'));
const idle=async()=>{for(let i=0;i<200&&d.querySelector('main').getAttribute('aria-busy')!=='false';i++)await new Promise(r=>setTimeout(r,5));};
const filter=async value=>{const s=d.querySelector('select[aria-label="同步进度"]');s.value=value;s.dispatchEvent(new w.Event('change',{bubbles:true}));await idle();};
try{
 w.FinanceWorkspace.enter('shops');await idle();
 const text=d.querySelector('main').textContent;
 assert(text.includes('最近单项成功'));assert(text.includes('最近推进'));assert(text.includes('等待本店其他项目'));
 assert(d.querySelector('.fi-sync-active').textContent.includes('别名a'));
 assert(d.querySelector('.fi-sync-priority').textContent.includes('别名b'));
 assert(d.querySelector('tr[data-update-key="b"]').textContent.includes('排队等待'));
 assert(!d.querySelector('tr[data-update-key="b"]').textContent.includes('正在抓取'));
 assert(text.includes('平台限流等待'));assert(text.includes('优先第 1 家'));
 const count=label=>[...d.querySelectorAll('.fi-shop-stats>div')].find(n=>n.firstChild.textContent===label).querySelector('strong').textContent;
 assert.equal(count('独立授权店铺'),'3');assert.equal(count('已开启财务同步'),'2');
 assert([...d.querySelectorAll('.fi-sync-details')].every(n=>!n.open),'channel detail must be collapsed by default');
 const detail=d.querySelector('tr[data-update-key="a"] .fi-sync-details');detail.open=true;
 [...d.querySelectorAll('button')].find(b=>b.textContent==='刷新').click();await idle();
 assert(d.querySelector('tr[data-update-key="a"] .fi-sync-details').open,'refresh must preserve the user-expanded detail');
 await filter('INCOMPLETE');assert(d.querySelector('tr[data-update-key="a"]'));assert(!d.querySelector('tr[data-update-key="c"]'));
 await filter('PRIORITY');assert(d.querySelector('tr[data-update-key="b"]'));assert(!d.querySelector('tr[data-update-key="a"]'));
 assert.equal(count('独立授权店铺'),'3','progress filters must not change total shop count');
 const sync=[...d.querySelector('tr[data-update-key="b"]').querySelectorAll('button')].find(b=>b.textContent==='立即同步');
 assert(sync);sync.click();await new Promise(r=>setTimeout(r,20));
 assert(calls.some(c=>c.path.endsWith('/shops/b/sync')&&c.options.method==='POST'));
 await filter('RUNNING');assert(d.querySelector('tr[data-update-key="a"]'));assert(!d.querySelector('tr[data-update-key="b"]'));
 await filter('WAIT');assert(d.querySelector('tr[data-update-key="b"]'));assert(!d.querySelector('tr[data-update-key="c"]'));
 console.log('PASS priority UI: real execution, queue, aliases, progress, filters and manual request.');
}finally{w.FinanceWorkspace.reset();dom.window.close();}
