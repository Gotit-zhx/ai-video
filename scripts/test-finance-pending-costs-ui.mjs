import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main></main>',{url:'https://example.test/',runScripts:'outside-only'}),w=dom.window,d=w.document;
const reads=[],exports=[],root=d.querySelector('main');
for(const file of ['incremental-dom.js','finance-costs.js'])w.eval(await fs.readFile(new URL('../frontend/'+file,import.meta.url),'utf8'));
const preview={live:true,shopId:'shop',groupId:'group',sourceName:'cost.csv',costs:[{sku:'PACK',warehouse:'泰国海外仓',prices:{'2026-09':10}}],settings:{warehouse:'泰国海外仓'},mappings:{},historicalPendingSkuCount:1,
 products:[{shop_id:'shop',product_id:'old',sku_id:'missing',seller_sku:'',name:'旧商品',spec:'50片1包',historical_cost_pending:true,pending_cost_orders:55,pending_cost_reason:'平台未返回商家 SKU'}]};
const context={preview,shops:[{id:'shop',name:'测试店',platform:'SHOPEE',group_id:'group'}],downloadOrderCosts:async f=>exports.push(f),request:async path=>{
 const q=new URL(path,'https://example.test').searchParams;reads.push(Object.fromEntries(q));const page=Number(q.get('page')),pending=q.get('costStatus')==='PENDING';
 return {items:[{id:pending?'missing-'+page:'ready',shop_id:'shop',order_id:'order-'+page,product_cost_pending:pending?1:0,product_cost_state:pending?'待补齐':'已计算',product_cost_issue:pending?'平台未返回商家 SKU':''}],total:pending?55:60,page,pageSize:50,productCosts:{total:{pending_count:55,ready_count:5,known_amount_cny:50}}};
}};
const settle=()=>new Promise(r=>setTimeout(r,10));
async function click(text){const b=[...d.querySelectorAll('button')].find(x=>x.textContent.trim().endsWith(text));assert(b,text);b.click();await settle();}
w.FinanceCosts.openOrders({shopId:'shop',start:'2026-08-01',end:'2026-09-30'});w.FinanceCosts.mount(root,context);await settle();
d.querySelector('[data-cost-status=PENDING]').click();await settle();assert.equal(reads.at(-1).costStatus,'PENDING');assert.equal(reads.at(-1).page,'1');assert(d.body.textContent.includes('平台未返回商家 SKU'));
await click('下一页');assert.equal(reads.at(-1).page,'2');assert.equal(reads.at(-1).costStatus,'PENDING');
await click('导出 Excel');assert.equal(exports.at(-1).costStatus,'PENDING');assert.equal(exports.at(-1).start,'2026-08-01');assert(!('page' in exports.at(-1)));
d.querySelector('[data-cost-status=ALL]').click();await settle();assert.equal(reads.at(-1).page,'1');assert.equal(reads.at(-1).costStatus,'ALL');
await click('SKU 关联');assert(d.body.textContent.includes('另补充历史待补齐规格 1 个'));assert(d.body.textContent.includes('历史订单待补齐 · 55 笔'));assert(d.body.textContent.includes('未关联'));
dom.window.close();console.log('Pending costs UI: server filtering, pagination, matching export and historical SKU reasons passed.');
