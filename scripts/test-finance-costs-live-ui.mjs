import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-costs',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,mutations=[];
w.currentUser={permissions:['finance:manage']};w.matchMedia=()=>({matches:false});
w.ResizeObserver=class{observe(){}disconnect(){}};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
const shops=[{id:'other',name:'无成本店',platform:'SHOPEE',group_id:'other-group',region:'TH'},{id:'shop',name:'母婴测试店',platform:'TIKTOK_SHOP',group_id:'group',region:'PH',timezone:'Asia/Manila'}],groups=[{id:'group',name:'母婴组'},{id:'other-group',name:'其他组'}];
let costs={live:true,shopId:'shop',groupId:'group',groupName:'母婴组',sourceName:'成本.csv',costs:[{sku:'PACK',warehouse:'海外仓',prices:{'2026-09':2}},{sku:'DUP*2',warehouse:'海外仓',prices:{'2026-09':3}},{sku:'(DUP)*2',warehouse:'海外仓',prices:{'2026-09':4}}],
  products:[{shop_id:'shop',product_id:'product',sku_id:'variant',seller_sku:'PACK',name:'商品',spec:'一包'},{shop_id:'shop',product_id:'product',sku_id:'unmatched',seller_sku:'OTHER-CODE',name:'商品',spec:'其他规格'},{shop_id:'shop',product_id:'product',sku_id:'review',seller_sku:'（DUP） ×2',name:'商品',spec:'Pants-XXXL'}],sampleOrders:1,orderLimit:2000,
  settings:{warehouse:'海外仓',rules:{currency:'CNY',unit:'包',basis:'shipment',missing:'pending',freight:'',inbound:''}},settingsVersion:1,mappings:{},shopCatalogs:{shop:{costRows:1,sourceName:'成本.csv'},other:{costRows:0,sourceName:''}}};
const reads=[];let failOther=false;
const exports=[];let exportFails=false,downloads=0;
w.AbortSignal.timeout=()=>undefined;
w.URL.createObjectURL=()=> 'blob:test-cost-export';w.URL.revokeObjectURL=()=>{};
w.HTMLAnchorElement.prototype.click=function(){assert(this.download.endsWith('.xlsx'));downloads++;};
w.fetch=async(path,options)=>{
  exports.push({url:new URL(path,'https://example.test'),options});
  return exportFails?{ok:false,json:async()=>({message:'导出范围过大，请缩小日期'})}:{ok:true,blob:async()=>new w.Blob(['xlsx'])};
};
let expenses={rows:[],rules:[],changes:[],totals:{records:0,amount:0,posted:0,included:0,pending:0},page:1,pageSize:50};
w.api=async(path,options={})=>{
  const url=new URL(path,'https://example.test'),body=options.body?JSON.parse(options.body):null;
  if(options.method&&options.method!=='GET'){
    mutations.push({path:url.pathname,method:options.method,body});
    if(path.endsWith('/auto-match')){for(const product of costs.products)for(const cost of costs.costs)if(product.seller_sku===cost.sku)costs.mappings['shop:product:'+product.sku_id+':'+cost.warehouse]??={code:cost.sku,warehouse:cost.warehouse,factor:null,method:'EXACT_CODE',version:1,groupId:'group'};return {data:structuredClone(costs)};}
    if(path.endsWith('/mapping')){costs.mappings['shop:product:'+body.sku_id+':海外仓']={...body,version:1,groupId:'group'};return {data:'已保存'};}
    if(path.endsWith('/settings')){costs.settings={rules:body.rules,warehouse:body.warehouse};costs.settingsVersion++;return {data:'已保存'};}
    if(path.endsWith('/expenses')){const row={...body,id:'expense-id',version:1,status:'已计入'};expenses={...expenses,rows:[row],totals:{records:1,amount:12.5,posted:12.5,included:0,pending:0}};return {data:row};}
    throw Error('Unexpected mutation '+path);
  }
  if(url.pathname==='/api/v1/finance/shops')return {data:{items:shops,groups,sync:[]}};
  if(url.pathname==='/api/v1/finance/workspace/costs/orders'){reads.push(Object.fromEntries(url.searchParams));return {data:{items:[{id:'order-1',shop_id:'shop',order_id:'ORDER-1',order_type_label:'商家承担的免费样品单',product_skus:'PACK\n（PACK）*2',record_date:'2026-08-28',order_created_time:'2026-08-28T05:27:54Z',first_settlement_date:'2026-10-23',amount:99,settlement_goods_amount:129,settlement_net_amount:80,settlement_currency:'PHP',product_cost_cny:null,product_cost_state:'待补齐'}],total:1,page:1,pageSize:50,productCosts:{total:{pending_count:1,ready_count:0,estimated_count:0,known_amount_cny:5.5,amount_cny:null}}}};}
  if(url.pathname==='/api/v1/finance/workspace/costs'){
    reads.push(url.searchParams.get('shopId'));
    if(url.searchParams.get('shopId')==='other'||url.searchParams.get('groupId')==='other-group'){
      if(failOther)throw Error('店铺读取暂时失败');
      return {data:{...structuredClone(costs),shopId:'other',groupId:'other-group',sourceName:'',costs:[],mappings:{},settings:{},products:[{shop_id:'other',product_id:'product',sku_id:'variant',seller_sku:'OTHER',name:'其他店商品',spec:'其他规格'}]}};
    }
    return {data:structuredClone(costs)};
  }
  if(url.pathname==='/api/v1/finance/workspace/charges')return {data:{rules:[],imports:[],rows:[],totals:{records:0,unmatched:0},marked:{orders:0,service_fee_cny:0}}};
  throw Error('Unexpected GET '+path);
};
for(const name of ['incremental-dom.js','incremental-charts.js','finance-costs.js','finance-charges.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../frontend/'+name,import.meta.url),'utf8'));
const button=(text,root=d)=>[...root.querySelectorAll('button')].find(n=>n.textContent.trim()===text||n.textContent.trim().endsWith(text));
const settle=()=>new Promise(r=>setTimeout(r,20));
async function click(text,root=d){const n=button(text,root);assert(n,text);n.click();await settle();}
function field(name,value,root=d){const n=root.querySelector(`[aria-label="${name}"]`);assert(n,name);n.value=value;n.dispatchEvent(new w.Event('change'));}
w.FinanceWorkspace.enter('costs');await settle();
assert(d.body.textContent.includes('分组成本管理'));assert(!d.body.textContent.includes('设计预览'));
assert.equal(mutations.filter(r=>r.path.endsWith('/auto-match')).length,1,'same codes are linked automatically without per-SKU clicks');
assert(!d.body.textContent.includes('待启用'));assert(button('订单成本'));assert(!button('仓库与规则'));
assert.equal(reads[0],'','initial selection is chosen by persisted cost availability, not the first shop');
assert.equal(d.querySelector('[aria-label="财务店铺"]').value,'shop');
await click('订单成本');assert(d.body.textContent.includes('已结算订单已算部分'));assert(d.body.textContent.includes('¥ 5.5'));assert(d.querySelector('tbody').textContent.includes('待补齐'));assert(!d.querySelector('tbody').textContent.includes('¥ 0'));
assert(d.querySelector('thead').textContent.includes('首次平台结算日期'));assert.equal(reads.at(-1).kind,'SETTLED_ORDER');assert(d.querySelector('thead').textContent.includes('商品 SKU'));assert(d.querySelector('tbody').textContent.includes('（PACK）*2'));
assert.equal(d.querySelector('tbody time').textContent,'2026-08-28 13:27:54');assert(d.querySelector('tbody').textContent.includes('2026-10-23'));
assert.equal(d.querySelector('tbody .fi-sample-tag')?.textContent,'免费样品单');
assert(d.querySelector('thead').textContent.includes('优惠前商品金额'));assert(d.querySelector('tbody').textContent.includes('129 PHP'));assert(d.querySelector('tbody').textContent.includes('80 PHP'));assert(!d.querySelector('tbody').textContent.includes('99 PHP'),'the buyer amount is not shown as provider gross');
const retainedCostRow=d.querySelector('tbody tr'),retainedCostStats=d.querySelector('.fi-cost-stats');
let costMutations=0;const observer=new w.MutationObserver(entries=>costMutations+=entries.length);
observer.observe(d.querySelector('.fc-order-results'),{subtree:true,childList:true,characterData:true,attributes:true});
await click('刷新成本');observer.disconnect();
assert.equal(d.querySelector('tbody tr'),retainedCostRow);assert.equal(d.querySelector('.fi-cost-stats'),retainedCostStats);
assert.equal(costMutations,0,'unchanged costs keep all result nodes and cause no DOM animation refresh');
let numberInput=d.querySelector('.fi-number-search textarea');numberInput.value='585766520348574952\n585766481305634280';numberInput.closest('form').dispatchEvent(new w.Event('submit',{cancelable:true}));await settle();assert.equal(reads.at(-1).numbers,'585766520348574952,585766481305634280');
field('结算开始日期','2026-10-01');await settle();
await click('导出 Excel');
assert.equal(downloads,1);assert.equal(exports.at(-1).url.pathname,'/api/v1/finance/workspace/costs/orders/export');
assert.equal(exports.at(-1).url.searchParams.get('shopId'),'shop');assert.equal(exports.at(-1).url.searchParams.get('kind'),'SETTLED_ORDER');
assert.equal(exports.at(-1).url.searchParams.get('start'),'2026-10-01');assert.equal(exports.at(-1).url.searchParams.get('numbers'),'585766520348574952,585766481305634280');
assert(!exports.at(-1).url.searchParams.has('page'),'export includes every page');
exportFails=true;await click('导出 Excel');
assert(d.body.textContent.includes('导出范围过大'));assert(!button('导出 Excel').disabled);
exportFails=false;
if(process.env.FINANCE_COST_PREVIEW_PATH){
  const css=(await Promise.all(['finance-workspace.css','finance-costs.css'].map(name=>fs.readFile(new URL('../frontend/'+name,import.meta.url),'utf8')))).join('\n');
  await fs.writeFile(process.env.FINANCE_COST_PREVIEW_PATH,`<!doctype html><meta charset="utf-8"><title>订单成本验收 · 测试数据</title><style>${css}body{margin:0;padding:32px;background:#f5f7fc;color:#25334d;font:14px/1.5 -apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif}</style><h2>订单成本验收 · 测试数据</h2>${d.querySelector('#finance-page').outerHTML}`);
}
w.FinanceCosts.openOrders({start:'2026-10-01',end:'2026-10-23',shopId:'shop',kind:'BILL',recordId:'selected-bill'});
w.FinanceWorkspace.enter('costs');await settle();
assert.equal(reads.at(-1).recordId,'selected-bill');assert.equal(reads.at(-1).kind,'BILL');
assert(d.querySelector('[aria-label="开始日期"]').disabled,'a single-record drill-down must not pretend the calendar filters it');
assert(d.body.textContent.includes('刚才点击记录关联的已结算订单'));
await click('导出 Excel');
assert.equal(exports.at(-1).url.searchParams.get('recordId'),'selected-bill');
assert.equal(exports.at(-1).url.searchParams.get('kind'),'BILL','drilldown export preserves source scope');
await click('查看该店已结算订单');assert(!reads.at(-1).recordId);assert(!d.querySelector('[aria-label="结算开始日期"]').disabled);
await click('SKU 关联');
assert.equal(d.querySelectorAll('.fc-shop-parent').length,1);
assert.equal(d.querySelector('.fc-shop-parent[aria-expanded=true]').getAttribute('aria-label'),'查看店铺 母婴测试店');
assert(d.querySelector('.fc-shop-children:not([hidden])').textContent.includes('成本.csv'));
assert.equal(d.querySelectorAll('.fc-spu').length,1,'SKUs share the parent platform product ID');
assert(d.querySelector('.fc-spu-parent').textContent.includes('已关联 1'));
assert(d.querySelector('.fc-spu-parent').textContent.includes('未关联 1'));
assert(d.querySelector('.fc-spu-parent').textContent.includes('需核对 1'));
assert(d.querySelector('.fc-spu-parent .fc-tag.warn'),'review state uses both an orange style and a readable label');
assert(d.querySelector('.fc-spu.danger'));
assert.equal(d.querySelector('.fc-spu-detail:not([hidden])').parentElement,d.querySelector('.fc-spu-list'),'expanded SKU table spans the grid, not a narrow card');
costs.costs.push({sku:'PACK*2',warehouse:'海外仓',prices:{'2026-09':5}});
costs.products.push({shop_id:'shop',product_id:'product',sku_id:'bundle',seller_sku:'(PACK) *2',name:'商品',spec:'两包'});
const originalApi=w.api;
w.api=async(path,options={})=>{
  if(path.endsWith('/auto-match'))costs.mappings['shop:product:bundle:海外仓']={code:'PACK*2',warehouse:'海外仓',factor:null,method:'NORMALIZED_CODE',version:1,groupId:'group'};
  return originalApi(path,options);
};
await click('刷新订单 SKU');
assert.equal(mutations.filter(r=>r.path.endsWith('/auto-match')).length,2,'new bracketed SKU triggers one automatic match');
assert(d.querySelector('.fc-spu-parent').textContent.includes('已关联 2'));
assert([...d.querySelectorAll('tbody tr')].find(r=>r.textContent.includes('(PACK) *2')).textContent.includes('已自动关联'));
costs.products.push({shop_id:'shop',product_id:'product',sku_id:'base-bundle',seller_sku:'(PACK) *3',name:'商品',spec:'三包'});
const normalizedApi=w.api;
w.api=async(path,options={})=>{
  if(path.endsWith('/auto-match'))costs.mappings['shop:product:base-bundle:海外仓']={code:'PACK',warehouse:'海外仓',factor:null,bundleQuantity:3,method:'BUNDLE_CODE',version:1,groupId:'group'};
  return normalizedApi(path,options);
};
await click('刷新订单 SKU');
assert.equal(mutations.filter(r=>r.path.endsWith('/auto-match')).length,3);
assert([...d.querySelectorAll('tbody tr')].find(r=>r.textContent.includes('(PACK) *3')).textContent.includes('组合数量 ×3'),'base association retains the encoded pack quantity');
await click('刷新订单 SKU');
assert.equal(mutations.filter(r=>r.path.endsWith('/auto-match')).length,3,'completed normalized and bundle links do not loop automatic requests');
await click('核对关联',d.querySelector('tbody'));
let dialog=d.querySelector('dialog');await click('保存关联',dialog);
assert.equal(mutations.filter(r=>r.path.endsWith('/mapping')).length,0,'unmatched codes require a reviewed override');
field('成本型号','PACK',dialog);dialog.querySelector('input[type=checkbox]').checked=true;await click('保存关联',dialog);
const mapping=mutations.find(r=>r.path.endsWith('/mapping'));
assert.equal(mapping.path,'/api/v1/finance/workspace/costs/shop/mapping');
assert.equal(mapping.body.sku_id,'unmatched');assert(d.querySelector('tbody').textContent.includes('已关联'));
await click('成本表设置');field('默认计价单位','箱');await click('保存成本设置');
const settings=mutations.find(r=>r.path.endsWith('/settings'));
assert.equal(settings.body.rules.unit,'箱');assert.equal(settings.body.version,1);
await click('SKU 关联');
field('成本分组','other-group');await settle();
assert(d.querySelector('tbody').textContent.includes('OTHER'));assert(!d.querySelector('tbody').textContent.includes('已关联'),'another shop with the same SKU identity has no inherited mapping');
assert(!d.querySelector('.fc-shop-source').textContent.includes('成本.csv'),'cost tables do not bleed into another group');
field('成本分组','group');await settle();
assert(d.querySelector('tbody').textContent.includes('已关联'),'returning to the shop restores its saved mapping');
failOther=true;field('成本分组','other-group');await settle();
assert(d.body.textContent.includes('店铺读取暂时失败'));assert.equal(d.querySelector('[aria-label="财务店铺"]').value,'shop');assert(d.querySelector('tbody').textContent.includes('已关联'),'failed reads preserve the last correctly labelled shop');
w.FinanceWorkspace.enter('expense-ledger');await settle();assert(d.body.textContent.includes('范围内已结算刷单'));assert(d.querySelector('[aria-label="费用数据分类"]').textContent.includes('平台费用'));assert(!d.body.textContent.includes('历史其他费用'));
assert(button('导入 Excel'));await click('贴单成本');assert(button('新增贴单规则'));assert(!button('导入 Excel'));
w.FinanceWorkspace.reset();w.currentUser={permissions:['finance:view']};w.FinanceWorkspace.enter('expense-ledger');await settle();
assert(!button('新增贴单规则'));assert(!button('导入 Excel'));assert(!button('新增费用'));
w.FinanceWorkspace.leave();w.close();
console.log('Live cost/expense UI: authorized endpoints, confirmed SKU mapping, versioned settings, exact decimal input, persisted refresh and read-only roles passed.');
