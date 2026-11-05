import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-shops',runScripts:'outside-only'});
const w=dom.window,d=w.document;
w.currentUser={permissions:['finance:view','finance:manage']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
const shops=['a','b','c'].map((id,i)=>({id,name:'原名'+id,display_name:'别名'+id,platform:'TIKTOK_SHOP',currency:'PHP',timezone:'Asia/Manila',enabled:true,authorization_status:'ACTIVE'}));
shops[2].enabled=false;
shops[1].external_shop_id='9876543210987654321';
const time='2026-11-03T02:00:00Z';
const states=[
 {shop_id:'a',channel:'ORDER',status:'RUNNING',execution_state:'RUNNING',initial_sync_complete:false,window_start:'1788192000',window_end:'1788796800',last_progress_at:time},
 {shop_id:'a',channel:'SETTLEMENT',status:'SYNCING',execution_state:'WAITING_SHOP',blocking_channel:'ORDER',initial_sync_complete:false},
 {shop_id:'b',channel:'ORDER',status:'SYNCING',execution_state:'QUEUED',queue_position:1,initial_sync_complete:true,coverage_start:'2026-09-01',last_success_at:'2026-10-31T16:00:00Z',priority_requested_at:time,shop_priority_requested_at:time,priority_queue_position:1,priority_pending_channels:2},
 {shop_id:'b',channel:'SETTLEMENT',status:'SUCCESS',execution_state:'SUCCESS',initial_sync_complete:true,coverage_start:'2026-09-01',last_success_at:'2026-10-31T16:00:00Z'},
 {shop_id:'b',channel:'REFUND',status:'RATE_LIMITED',execution_state:'RATE_LIMITED',next_at:time,initial_sync_complete:true,error_message:'平台请求过快',priority_requested_at:time,shop_priority_requested_at:time,priority_queue_position:1,priority_pending_channels:2},
 {shop_id:'c',channel:'ORDER',status:'SUCCESS',execution_state:'SUCCESS',initial_sync_complete:true,last_success_at:time}
];
for(const state of states){state.previous_month='2026-10';state.previous_month_checked=true;state.previous_month_complete=state.shop_id==='b'&&['ORDER','SETTLEMENT'].includes(state.channel);}
const calls=[];
w.api=async(path,options)=>{calls.push({path,options});return {data:path.endsWith('/ads')?[]:path.endsWith('/sync')?'已加入店铺优先队列':{items:shops,groups:[],sync:states}};};
for(const file of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../frontend/'+file,import.meta.url),'utf8'));
const idle=async()=>{for(let i=0;i<200&&d.querySelector('main').getAttribute('aria-busy')!=='false';i++)await new Promise(r=>setTimeout(r,5));};
const filter=value=>{const s=d.querySelector('select[aria-label="上月抓取情况"]');s.value=value;s.dispatchEvent(new w.Event('change',{bubbles:true}));};
const search=value=>{const s=d.querySelector('input[aria-label="查询店铺"]');s.value=value;s.dispatchEvent(new w.Event('input',{bubbles:true}));};
const visible=id=>!d.querySelector('tr[data-update-key="'+id+'"]').hidden;
const refresh=async()=>{[...d.querySelectorAll('button')].find(b=>b.textContent==='刷新').click();await idle();};
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
 assert.equal(count('上月抓取完成'),'1');
 assert(!d.querySelector('select[aria-label="同步进度"]'),'execution-state filter was removed');
 assert(d.querySelector('tr[data-update-key="b"] .fi-shop-month').textContent.includes('上月抓取完成 · 2026-10'));
 assert([...d.querySelectorAll('.fi-sync-details')].every(n=>!n.open),'channel detail must be collapsed by default');
 const detail=d.querySelector('tr[data-update-key="a"] .fi-sync-details');detail.open=true;
 await refresh();
 assert(d.querySelector('tr[data-update-key="a"] .fi-sync-details').open,'refresh must preserve the user-expanded detail');
 const beforeFilters=calls.length;
 filter('INCOMPLETE');assert(visible('a'));assert(visible('c'));assert(!visible('b'));
 filter('COMPLETE');assert(visible('b'));assert(!visible('a'));assert(!visible('c'));
 assert.equal(count('独立授权店铺'),'3','month filters must not change total shop count');
 search('原名b');assert(visible('b'));
 search('别名B');assert(visible('b'),'alias search must ignore case');
 search('9876543210987654321');assert(visible('b'),'shop identifiers must stay exact strings');
 search('没有这个店铺');assert(!visible('b'));assert(!d.querySelector('.fi-shop-no-match').hidden);
 assert.equal(calls.length,beforeFilters,'month filtering and search must not request the server');
 [...d.querySelectorAll('button')].find(b=>b.textContent==='清空').click();assert(visible('b'));
 search('原名b');await refresh();assert.equal(d.querySelector('input[aria-label="查询店铺"]').value,'原名b');assert(visible('b'));assert(!visible('a'));
 const sync=[...d.querySelector('tr[data-update-key="b"]').querySelectorAll('button')].find(b=>b.textContent==='立即同步');
 assert(sync);sync.click();await new Promise(r=>setTimeout(r,20));
 assert(calls.some(c=>c.path.endsWith('/shops/b/sync')&&c.options.method==='POST'));
 search('');filter('ALL');
 const monthComplete=()=>d.querySelector('tr[data-update-key="b"]').dataset.monthComplete==='true';
 const settlement=states.find(s=>s.shop_id==='b'&&s.channel==='SETTLEMENT');
 settlement.previous_month_complete=false;settlement.previous_month_reason='上月结算明细仍在抓取或等待重试';await refresh();
 assert(!monthComplete(),'an earlier success must not override unfinished monthly details');
 assert(d.querySelector('tr[data-update-key="b"] .fi-shop-month').title.includes('结算明细仍在抓取'));
 settlement.previous_month_complete=true;settlement.previous_month_checked=false;await refresh();
 assert(!monthComplete(),'old or unchecked server metadata must never be green');
 settlement.previous_month_checked=true;settlement.previous_month_complete=false;settlement.previous_month_open_bills=1;
 settlement.previous_month_reason='上月还有 1 张账单待补齐明细或确认结算日期';await refresh();assert(!monthComplete());
 for(const state of states.filter(s=>s.shop_id==='b'&&['ORDER','SETTLEMENT'].includes(s.channel))){state.previous_month='2026-12';state.previous_month_complete=true;}
 await refresh();assert(monthComplete());assert(d.querySelector('tr[data-update-key="b"] .fi-shop-month').textContent.includes('2026-12'));
 console.log('PASS priority/month UI: execution, counts, local month boundary, coverage, search, filtering, refresh and manual request.');
}finally{w.FinanceWorkspace.reset();dom.window.close();}
