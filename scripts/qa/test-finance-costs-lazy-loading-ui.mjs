import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');

const dom=new JSDOM('<main id="root"></main>',{url:'https://example.test/#finance-costs',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,requests=[];
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
w.FinanceLabelCosts={mount(root){root.textContent='月度账单已加载';},reset(){}};
w.eval(await fs.readFile(new URL('../../frontend/finance-costs.js',import.meta.url),'utf8'));

const shops=[{id:'shop',name:'测试店',group_id:'group',platform:'SHOPEE',region:'PH'}];
const groups=[{id:'group',name:'测试组'}];
const contextPreview={live:true,catalogLoaded:false,settingsLoaded:false,shopId:'shop',groupId:'group',groupName:'测试组'};
const catalogue={...contextPreview,catalogLoaded:true,settingsLoaded:true,sourceName:'成本.xlsx',costs:[{sku:'PACK',warehouse:'菲律宾仓',prices:{'2026-10':2}}],manualCosts:[],products:[],mappings:{},settings:{},settingsVersion:0,imports:[],shopCatalogs:{shop:{sourceName:'成本.xlsx',costRows:1}},groupCatalogs:{group:{sourceName:'成本.xlsx',costRows:1}}};
const request=async path=>{requests.push(path);assert(path.startsWith('/costs?'),'only catalogue tabs may request the full cost workspace');return structuredClone(catalogue);};
const settle=()=>new Promise(resolve=>setTimeout(resolve,20));
const button=text=>[...d.querySelectorAll('button')].find(node=>node.textContent.trim()===text||node.textContent.trim().endsWith(text));

assert.equal(typeof w.FinanceCosts.initialPath,'function','the parent loader needs an explicit initial cost path contract');
assert.equal(new URL(w.FinanceCosts.initialPath({shopId:'shop',groupId:'group'}),'https://example.test').pathname,'/costs/context');
w.FinanceCosts.mount(d.querySelector('#root'),{shops,groups,canManage:true,preview:contextPreview,request});
assert(d.body.textContent.includes('月度账单已加载'));
assert.deepEqual(requests,[],'default monthly invoice view must not load the cost catalogue');

button('商品成本表').click();
await settle();
assert.equal(requests.length,1);
assert.equal(new URL(requests[0],'https://example.test').pathname,'/costs');
assert(d.body.textContent.includes('成本.xlsx'));

button('月度贴单账单').click();
await settle();
button('SKU 关联').click();
await settle();
assert.equal(requests.length,1,'the loaded catalogue is reused when returning to a catalogue tab');

w.FinanceCosts.reset();
w.FinanceCosts.openOrders({start:'2026-10-01',end:'2026-10-31',shopId:'shop',groupId:'group',costStatus:'PENDING'});
assert.equal(new URL(w.FinanceCosts.initialPath({shopId:'shop',groupId:'group'}),'https://example.test').pathname,'/costs/context');
const orderRequests=[];
w.FinanceCosts.mount(d.querySelector('#root'),{shops,groups,canManage:true,preview:contextPreview,request:async path=>{
  orderRequests.push(path);assert(path.startsWith('/costs/orders?'));
  return {items:[],total:0,page:1,pageSize:50,productCosts:{total:{pending_count:0,ready_count:0,known_amount_cny:0}}};
}});
await settle();
assert.equal(orderRequests.length,1);
assert.equal(d.querySelector('.fc-cost-metrics').hidden,true,'orders context must not present unloaded catalogue counts as real zero values');
w.FinanceCosts.reset();
w.FinanceCosts.openMapping({shopId:'shop',productId:'product',skuId:'sku'});
assert.equal(new URL(w.FinanceCosts.initialPath({shopId:'shop',groupId:'group'}),'https://example.test').pathname,'/costs');

dom.window.close();
console.log('Finance costs lazy loading: context-first invoices, one-time catalogue load and initial caller modes passed.');
