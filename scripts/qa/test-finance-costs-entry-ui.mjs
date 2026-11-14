import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-costs',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,requests=[];
w.currentUser={organizationId:'company',userId:'reader',permissions:['finance:view']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
const shops=[
  {id:'shop-a',name:'A 店',group_id:'group-a',platform:'SHOPEE',region:'PH',currency:'PHP',timezone:'Asia/Manila'},
  {id:'shop-b',name:'B 店',group_id:'group-a',platform:'TIKTOK_SHOP',region:'TH',currency:'THB',timezone:'Asia/Bangkok'}
];
const groups=[{id:'group-a',name:'A 分组'},{id:'empty',name:'空分组'}];
const labelCosts={
  files:[{id:'invoice',source_name:'10月贴单.xlsx',status:'READY',period_start:'2026-10-01',period_end:'2026-10-31',row_count:2,matched_count:1,unmatched_count:1,review_count:0,conflict_count:0,amount_cny:12.5}],
  rows:[{import_id:'invoice',source_row:1,order_id:'ORDER-1',shop_name:'A 店',customer:'客户 A',warehouse:'菲律宾仓',document_no:'OUT-1',transaction_date:'2026-10-03',amount_cny:12.5,match_status:'MATCHED',match_note:'已绑定',import_status:'READY'}],
  total:1,page:1,pageSize:50,period:{orders:1,amount_cny:12.5,conflicts:0,shipments:1},state:{},processing:false,basis:'参考号绑定订单，人民币'
};
const catalogue={live:true,catalogLoaded:true,settingsLoaded:true,shopId:'shop-a',groupId:'group-a',groupName:'A 分组',sourceName:'成本.xlsx',
  costs:[{sku:'PACK',warehouse:'菲律宾仓',prices:{'2026-10':2}}],manualCosts:[],products:[{shop_id:'shop-a',product_id:'product',sku_id:'sku',seller_sku:'PACK',name:'测试商品',spec:'单包'}],mappings:{},settings:{},settingsVersion:0,imports:[],shopCatalogs:{'shop-a':{sourceName:'成本.xlsx',costRows:1}},groupCatalogs:{'group-a':{sourceName:'成本.xlsx',costRows:1}}};
const orderCosts={items:[],total:0,page:1,pageSize:50,productCosts:{total:{pending_count:0,ready_count:0,known_amount_cny:0}}};
let releaseShops;const shopsGate=new Promise(resolve=>{releaseShops=()=>resolve({data:{items:shops,groups,sync:[]}});});
w.api=async path=>{
  const u=new URL(path,'https://example.test');requests.push(u);
  if(u.pathname.endsWith('/shops'))return shopsGate;
  if(u.pathname.endsWith('/costs/context'))return {data:{live:true,catalogLoaded:false,settingsLoaded:false,shopId:'shop-a',groupId:'group-a',groupName:'A 分组'}};
  if(u.pathname.endsWith('/label-costs'))return {data:structuredClone(labelCosts)};
  if(u.pathname.endsWith('/costs/orders'))return {data:structuredClone(orderCosts)};
  if(u.pathname.endsWith('/costs'))return {data:structuredClone(catalogue)};
  throw Error('Unexpected cost entry query '+u.pathname);
};
for(const file of ['incremental-dom.js','incremental-charts.js','finance-label-costs.js','finance-costs.js','finance-workspace.js'])
  w.eval(await fs.readFile(new URL('../../frontend/'+file,import.meta.url),'utf8'));
const waitFor=async predicate=>{for(let i=0;i<100;i++){if(predicate())return;await new Promise(resolve=>setTimeout(resolve,10));}assert.fail('Timed out waiting for cost entry state');};
const button=text=>[...d.querySelectorAll('button')].find(node=>node.textContent.trim()===text||node.textContent.trim().endsWith(text));

w.FinanceWorkspace.enter('costs');
await waitFor(()=>requests.some(url=>url.pathname==='/api/v1/finance/shops'));
await new Promise(resolve=>setTimeout(resolve,20));
assert(requests.some(url=>url.pathname==='/api/v1/finance/workspace/label-costs'),'the default labels request starts while fresh shops are still pending');
releaseShops();
await waitFor(()=>d.querySelector('#finance-page').getAttribute('aria-busy')==='false'&&d.body.textContent.includes('10月贴单.xlsx'));
const paths=requests.map(url=>url.pathname);
assert.deepEqual(paths.slice(0,2),['/api/v1/finance/shops','/api/v1/finance/workspace/label-costs'],'labels entry starts its real data request immediately after fresh authorized shops');
assert(!paths.includes('/api/v1/finance/workspace/costs/context'),'labels entry must not request unused cost context');
assert.equal(paths.filter(path=>path==='/api/v1/finance/workspace/costs').length,0,'labels entry must not request the full cost catalogue');
assert.equal(d.querySelectorAll('.fl-content .fc-metric').length,4,'the real labels module renders all four period metrics');
assert.deepEqual([...d.querySelectorAll('.fq-section h4')].map(node=>node.textContent),['导入文件','订单绑定明细']);
assert.equal(d.querySelectorAll('.fq-section table').length,2,'the file and source-record tables are complete');
assert(d.body.textContent.includes('ORDER-1'));assert(d.body.textContent.includes('12.50'));

button('订单成本').click();
await waitFor(()=>requests.some(url=>url.pathname==='/api/v1/finance/workspace/costs/orders'));
assert(!requests.some(url=>url.pathname==='/api/v1/finance/workspace/costs/context'),'orders use their own request without restoring unused context');
button('SKU 关联').click();
await waitFor(()=>requests.some(url=>url.pathname==='/api/v1/finance/workspace/costs')&&d.body.textContent.includes('测试商品'));
assert(d.body.textContent.includes('测试商品'),'a catalogue tab restores the authorized full catalogue request');

w.FinanceWorkspace.reset();dom.window.close();
console.log('Cost entry: labels start after shops without cost context/catalogue; complete labels UI and lazy orders/catalogue transitions passed.');
