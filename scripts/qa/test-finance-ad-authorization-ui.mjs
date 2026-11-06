import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-shops',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,root=d.querySelector('main');
w.currentUser={organizationId:'qa',userId:'reader',permissions:['finance:view']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
const groups=[{id:'clothes',name:'服饰'},{id:'shoes',name:'鞋靴'}];
const shops=[{id:'a',name:'服饰店',platform:'TIKTOK_SHOP',currency:'PHP',timezone:'Asia/Manila',group_id:'clothes'},
  {id:'b',name:'鞋店',platform:'TIKTOK_SHOP',currency:'PHP',timezone:'Asia/Manila',group_id:'shoes'}];
let accounts=[{advertiser_id:'100001',name:'已连接未分配账户',shop_id:'',currency:'PHP',status:'READY'},
  {advertiser_id:'100002',name:'鞋靴广告账户',shop_id:'b',currency:'PHP',status:'SUCCESS'}];
w.api=async(path,options={})=>{
  assert(!options.method||options.method==='GET','viewer only reads existing accounts');
  if(path==='/api/v1/finance/shops')return {data:{items:shops,groups,sync:[],deletions:[]}};
  if(path==='/api/v1/finance/ads')return {data:accounts};
  throw Error('Unexpected endpoint '+path);
};
for(const name of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../../frontend/'+name,import.meta.url),'utf8'));
const ready=async()=>{for(let i=0;i<200;i++){if(root.getAttribute('aria-busy')==='false')return;await new Promise(r=>setTimeout(r,5));}throw Error('Account UI timeout');};
const choose=async value=>{const select=d.querySelector('[aria-label="店铺分组"]');select.value=value;select.dispatchEvent(new w.Event('change'));await ready();};
const button=text=>[...root.querySelectorAll('button')].find(n=>n.textContent.trim()===text);
const card=()=>root.querySelector('[data-update-key="广告账户"]');
try{
  w.FinanceWorkspace.enter('shops');await ready();await choose('clothes');
  assert(card().textContent.includes('公司已连接 2 个广告账户，当前分组显示 0 个'));
  assert(card().textContent.includes('当前分组没有广告账户'));
  assert(!card().textContent.includes('尚未连接广告账户'));
  button('查看全部广告账户').click();await ready();
  assert(card().textContent.includes('已连接未分配账户'));assert(card().textContent.includes('鞋靴广告账户'));
  assert.deepEqual([...card().querySelectorAll('tbody tr')].map(row=>row.dataset.updateKey),['100001','100002']);
  await choose('shoes');assert.deepEqual([...card().querySelectorAll('tbody tr')].map(row=>row.dataset.updateKey),['100002']);
  await choose('UNGROUPED');assert.deepEqual([...card().querySelectorAll('tbody tr')].map(row=>row.dataset.updateKey),['100001']);
  accounts=[];button('刷新').click();await ready();assert(card().textContent.includes('尚未连接广告账户'));assert(!button('查看全部广告账户'));
  assert(!button('复制广告授权链接'));assert(!button('更新授权'));
  console.log('PASS advertising account UI: hidden scoped accounts, unassigned accounts, stable row identity, genuine empty state and viewer permissions.');
}finally{w.FinanceWorkspace.leave();w.close();}
