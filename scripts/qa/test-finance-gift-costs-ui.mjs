import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="root"></main>',{runScripts:'outside-only',url:'https://example.test'});
const w=dom.window,d=w.document;
w.eval(await fs.readFile(new URL('../../frontend/finance-costs.js',import.meta.url),'utf8'));
const product={shop_id:'shop',product_id:'product',sku_id:'gift',seller_sku:' Gift ',pending_cost_orders:4,pending_cost_reason:'旧的待匹配记录'};
w.FinanceCosts.openMapping({shopId:'shop',productId:'product',skuId:'gift'});
w.FinanceCosts.mount(d.querySelector('#root'),{canManage:true,shops:[{id:'shop',group_id:'group',name:'测试店',platform:'SHOPEE'}],groups:[{id:'group',name:'组'}],
  preview:{live:true,shopId:'shop',groupId:'group',costs:[],products:[product],settings:{},mappings:{},sampleOrders:1},
  request:()=>{throw Error('Fixed gift must not create mappings');}});
assert(d.body.textContent.includes('赠品 · 固定 0 成本'));
assert(d.body.textContent.includes('无需关联'));
assert(!d.body.textContent.includes('历史订单待补齐 · 4'));
const pending=[...d.querySelectorAll('.fc-metric')].find(n=>n.textContent.includes('待关联 SKU'));
assert.equal(pending.querySelector('strong').textContent,'0');
assert(![...d.querySelectorAll('button')].some(n=>n.textContent==='核对关联'));
assert.equal(w.FinanceCosts.candidate({seller_sku:'GIFT-BOX'},[],'').status,'missing');
console.log('PASS GIFT cost UI: fixed zero, no pending count or mapping action');
