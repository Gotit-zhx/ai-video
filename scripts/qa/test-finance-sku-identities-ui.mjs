import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-costs',runScripts:'outside-only',pretendToBeVisual:true}),w=dom.window,d=w.document;
w.currentUser={permissions:['finance:view']};w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
const identity={name:'历史订单商品（测试数据）',spec:'50片（1包）M-L',sellerSku:'',productId:'40026185876',skuId:'400076697058',lineId:'line-1',quantity:2,image:'https://example.test/product.png'};
const preview={live:true,shopId:'shop',groupId:'group',sourceName:'测试成本.csv',costs:[{sku:'PACK',warehouse:'海外仓-泰国',prices:{'2026-09':10}}],settings:{warehouse:'海外仓-泰国'},mappings:{},sampleOrders:2000,historicalPendingSkuCount:1,
 products:[{shop_id:'shop',product_id:identity.productId,sku_id:identity.skuId,seller_sku:'',name:identity.name,spec:identity.spec,image:identity.image,pending_cost_orders:55,pending_cost_reason:'平台未返回商家 SKU'}]};
const row={id:'record',shop_id:'shop',kind:'ORDER',platform:'SHOPEE',order_id:'TEST-ORDER',record_date:'2026-08-10',first_settlement_date:'2026-09-01',product_skus:'',product_sku_values:[],product_sku_details:[identity],product_cost_pending:1,product_cost_state:'待补齐',product_cost_issue:'平台未返回商家 SKU'};
const reads=[];
w.api=async path=>{
 const u=new URL(path,'https://example.test');reads.push(u);
 if(u.pathname.endsWith('/shops'))return {data:{items:[{id:'shop',name:'测试店铺',group_id:'group',platform:'SHOPEE',region:'TH'}],groups:[],sync:[]}};
 if(u.pathname.endsWith('/costs'))return {data:structuredClone(preview)};
 if(u.pathname.endsWith('/costs/orders'))return {data:{items:[structuredClone(row)],total:55,page:Number(u.searchParams.get('page')),pageSize:50,productCosts:{total:{pending_count:55,ready_count:0,known_amount_cny:0}}}};
 if(u.pathname.endsWith('/records/record'))return {data:{...structuredClone(row),productCost:{warehouse:'海外仓-泰国',note:'待补齐',lines:[{lineId:'different-source-line-id',productId:identity.productId,skuId:identity.skuId,sellerSku:'',quantity:2,issue:'平台未返回商家 SKU'}]}}};
 if(u.pathname.endsWith('/fields/ORDER'))return {data:{fields:[{id:'product_skus',label:'商品 SKU',type:'text'}]}};
 throw Error('Unexpected request '+path);
};
for(const f of ['incremental-dom.js','incremental-charts.js','finance-costs.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../../frontend/'+f,import.meta.url),'utf8'));
const settle=()=>new Promise(r=>setTimeout(r,25));
async function click(text,root=d){const b=[...root.querySelectorAll('button')].find(n=>n.textContent.trim()===text);assert(b,text);b.click();await settle();}
w.FinanceCosts.openOrders({shopId:'shop',start:'2026-08-01',end:'2026-09-30',costStatus:'PENDING'});w.FinanceWorkspace.enter('costs');await settle();
let cell=d.querySelector('tbody .fi-sku-details');assert(cell);assert(cell.textContent.includes(identity.name));assert(cell.textContent.includes('订单规格：'+identity.spec));assert(cell.textContent.includes('平台 SKU：'+identity.skuId));assert(cell.textContent.includes('购买数量：2'));assert(cell.textContent.includes('商家 SKU 未提供'));
assert.equal(cell.querySelector('img').getAttribute('loading'),'lazy');assert.equal(cell.querySelector('img').referrerPolicy,'no-referrer');
cell.querySelector('img').dispatchEvent(new w.Event('error'));assert(cell.textContent.includes('商品图未加载'));
await click('查看计算明细');let dialog=d.querySelector('dialog');assert(dialog.textContent.includes(identity.spec));assert(dialog.textContent.includes(identity.skuId));assert(dialog.querySelector('.fi-sku-details .fi-sku-photo img'));dialog.close();
await click('下一页');assert.equal(reads.at(-1).searchParams.get('page'),'2');
await click('核对关联',d.querySelector('tbody'));assert.equal(d.querySelector('[aria-label="搜索成本或商品"]').value,identity.skuId);assert.equal(d.querySelector('.fc-spu').dataset.productId,identity.productId);assert(d.body.textContent.includes('未提供商家 SKU'));
d.querySelector('.fc-shop-parent').click();await settle();assert(d.querySelector('.fc-shop-children').hidden);
await click('返回待补齐订单');assert.equal(reads.at(-1).searchParams.get('page'),'2');assert.equal(reads.at(-1).searchParams.get('costStatus'),'PENDING');assert.equal(reads.at(-1).searchParams.get('start'),'2026-08-01');
await click('核对关联',d.querySelector('tbody'));assert.equal(d.querySelector('.fc-shop-children').hidden,false,'targeted review expands a previously collapsed shop');
await click('返回待补齐订单');
if(process.env.FINANCE_SKU_PREVIEW_PATH){
 const css=(await Promise.all(['finance-workspace.css','finance-costs.css'].map(f=>fs.readFile(new URL('../../frontend/'+f,import.meta.url),'utf8')))).join('\n');
 await fs.writeFile(process.env.FINANCE_SKU_PREVIEW_PATH,`<!doctype html><meta charset="utf-8"><title>SKU 核对布局 · 测试数据</title><style>${css}body{margin:0;padding:24px;background:#f5f7fc;color:#25334d;font:14px/1.5 -apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif}</style>${d.body.innerHTML}`);
}
w.FinanceWorkspace.leave();dom.window.close();console.log('SKU identity UI: original image/spec/quantity/IDs, detail fallback, targeted mapping and preserved return filters passed.');
