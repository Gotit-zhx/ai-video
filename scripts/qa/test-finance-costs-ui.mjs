import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<div id="finance-page"></div>',{url:'https://example.test/#finance-costs',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,requests=[];
w.currentUser={permissions:['finance:manage']};w.matchMedia=()=>({matches:false});
w.ResizeObserver=class{observe(){}disconnect(){}};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
const shops=[{id:'baby',name:'母婴测试店',group_id:'baby-group',platform:'TIKTOK_SHOP',region:'PH'},{id:'other',name:'其他测试店',group_id:'other-group',platform:'SHOPEE',region:'TH'}];
const groups=[{id:'baby-group',name:'母婴组'},{id:'other-group',name:'其他组'}];
w.api=async(path,options={})=>{assert(!options.method||options.method==='GET','design must never write financial data');requests.push(path);if(path.startsWith('/api/v1/finance/workspace/costs/context?')||path.startsWith('/api/v1/finance/workspace/costs?'))return {data:null};assert.equal(path,'/api/v1/finance/shops','cost design must not load dashboards or platform APIs');return {data:{items:shops,groups,sync:[]}};};
for(const name of ['incremental-dom.js','incremental-charts.js','finance-costs.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../../frontend/'+name,import.meta.url),'utf8'));
const preview={shopId:'baby',groupId:'baby-group',groupName:'母婴组',sourceName:'成本测试.xlsx',suggestedWarehouse:'菲律宾仓',sampleOrders:4,
  costs:[{sku:'BABY-BPants-XXL',warehouse:'菲律宾仓',prices:{'2026-09':10}},{sku:'BABY-BPants-XXL*2',warehouse:'菲律宾仓',prices:{'2026-09':21}},{sku:'BABY-BPants-XXL',warehouse:'泰国仓',prices:{'2026-09':12}}],
  products:[
    {shop_id:'baby',product_id:'product-1',sku_id:'sku-1',seller_sku:'BABY-BPants-XXL',name:'测试母婴商品',spec:'Pants-XXL 1 Pack'},
    {shop_id:'baby',product_id:'product-2',sku_id:'sku-2',seller_sku:'（BABY-BPants-XXL）*2',name:'测试组合装',spec:'Pants-XXL 2 Packs'},
    {shop_id:'baby',product_id:'product-3',sku_id:'sku-3',seller_sku:'（BABY-BPants-XXL）*2',name:'测试冲突规格',spec:'Pants-XXXL 2 Packs'}
  ]};
function button(text,scope=d){return [...scope.querySelectorAll('button')].find(n=>n.textContent===text||n.textContent.trim().endsWith(text));}
async function ready(){for(let i=0;i<100;i++){await new Promise(r=>setTimeout(r,5));if(d.querySelector('#finance-page').getAttribute('aria-busy')==='false')return;}throw Error('timeout');}
function select(label,value){const n=d.querySelector(`[aria-label="${label}"]`);assert(n,label);n.value=value;n.dispatchEvent(new w.Event('change'));}
w.FinanceWorkspace.enter('costs');await ready();
assert(d.body.textContent.includes('暂无成本资料'));assert(!d.body.textContent.includes('成本测试.xlsx'));assert(!d.querySelector('script[src*="echarts"]'));
assert.equal(requests.length,2);assert(!button('导入账单'),'SKU unit costs must not use the financial expense import');
w.FinanceCostPreviewData=preview;button('刷新').click();await ready();
button('商品成本表').click();
assert(d.body.textContent.includes('设计预览'));assert(d.body.textContent.includes('成本测试.xlsx'));assert(d.querySelector('thead').textContent.includes('人民币'));assert(!d.querySelector('tbody').textContent.includes('单位待确认'));
button('SKU 关联').click();assert.equal(d.querySelectorAll('.fc-spu').length,3);assert(d.body.textContent.includes('需核对'));
assert.equal(w.FinanceCosts.candidate({seller_sku:'BABY-BPants-XXL*2',spec:'Pants-XXL'},preview.costs,'菲律宾仓').factor,1,'explicit bundle SKU cost wins over multiplying single-item cost');
assert.equal(w.FinanceCosts.candidate(preview.products[1],preview.costs,'菲律宾仓').factor,1,'bracketed bundles use their own cost instead of multiplying the base price');
assert.equal(w.FinanceCosts.candidate(preview.products[1],preview.costs,'菲律宾仓').code,'BABY-BPants-XXL*2');
assert.equal(w.FinanceCosts.candidate({seller_sku:' ( BABY-BPants-XXL ) × 2 '},preview.costs,'菲律宾仓').code,'BABY-BPants-XXL*2');
const ambiguousCosts=[...preview.costs,{sku:'(BABY-BPants-XXL)*2',warehouse:'菲律宾仓',prices:{'2026-09':19}}];
assert.equal(w.FinanceCosts.candidate({seller_sku:'（BABY-BPants-XXL） × 2'},ambiguousCosts,'菲律宾仓').status,'conflict','normalized duplicate codes need review');
assert.equal(w.FinanceCosts.candidate({seller_sku:'BABY-BPants-XXL*2'},ambiguousCosts,'菲律宾仓').code,'BABY-BPants-XXL*2','exact code still has priority');
assert.equal(w.FinanceCosts.candidate({seller_sku:'(BABY-BPants-XXL)*3'},preview.costs,'菲律宾仓').status,'auto','an unambiguous encoded bundle is automatically matched');
assert.equal(w.FinanceCosts.candidate({seller_sku:'(BABY-BPants-XXL)*3'},preview.costs,'菲律宾仓').factor,3,'the encoded bundle quantity is retained');
assert.equal(w.FinanceCosts.candidate(preview.products[2],preview.costs,'菲律宾仓').code,'BABY-BPants-XXL*2','sales description size no longer blocks the merchant SKU');
assert.equal(w.FinanceCosts.candidate(preview.products[0],preview.costs,'').status,'missing','warehouse ambiguity must not silently choose a cost');
const firstProduct=d.querySelector('[data-product-id="product-1"] .fc-spu-parent');if(firstProduct.getAttribute('aria-expanded')==='false')firstProduct.click();
const row=[...d.querySelectorAll('tbody tr')].find(n=>n.textContent.includes('sku-1'));button('查看 / 调整',row).click();
let dialog=d.querySelector('dialog');assert(dialog.textContent.includes('商品 ID：product-1'));
button('应用到预览',dialog).click();assert(d.body.textContent.includes('预览已关联'));assert(d.body.textContent.includes('尚未写入正式系统'));
select('成本分组','other-group');assert(!d.body.textContent.includes('sku-1'),'shop samples must not bleed into another shop');
button('成本表').click();assert(!d.body.textContent.includes('成本测试.xlsx'),'baby cost catalog must not be attributed to another group');
assert(!d.querySelector('[aria-label="成本仓库"]').textContent.includes('菲律宾仓'),'warehouse choices belong to the selected group');
select('成本分组','baby-group');button('成本表设置').click();select('默认计价单位','包');button('应用到预览').click();assert(d.body.textContent.includes('正式数据未变更'));
select('成本分组','other-group');assert.equal(d.querySelector('[aria-label="默认计价单位"]').value,'','cost rules are isolated by shop');
select('成本分组','baby-group');assert.equal(d.querySelector('[aria-label="默认计价单位"]').value,'包','switching shops retains that shop’s draft');
assert(button('订单成本'));assert(!button('仓库与规则'));
assert.equal(requests.length,3,'all prototype interactions remain local');
w.FinanceWorkspace.reset();w.currentUser={permissions:['finance:view']};w.FinanceWorkspace.enter('costs');await ready();
assert(!button('导入成本表'));button('SKU 关联').click();assert(!d.querySelector('tbody button'),'viewers cannot edit mapping drafts');
button('成本表设置').click();assert(d.querySelector('[aria-label="默认计价单位"]').disabled);
w.FinanceWorkspace.leave();
const production=await fs.readFile(new URL('../../frontend/index.html',import.meta.url),'utf8');
assert(production.includes('finance-expenses-design'),'approved expense workspace is loaded by the production entry');
w.eval(await fs.readFile(new URL('../../frontend/finance-expenses-design.js',import.meta.url),'utf8'));
w.fetch=()=>{throw Error('prototype must never write or request ledger data');};
const host=d.querySelector('#finance-page');
w.FinanceExpensesDesign.mount(host,{shops,groups,canManage:true});
button('新增费用').click();
dialog=d.querySelector('dialog');
function input(name,value){const n=dialog.querySelector(`[aria-label="${name}"]`);assert(n,name);n.value=value;}
input('归属店铺','baby');input('订单编号','PREVIEW-ORDER');input('费用日期','2026-10-22');
input('金额','12.50');input('费用来源编号','PREVIEW-FEE');input('计入方式','extra');
button('添加到预览',dialog).click();
assert(d.body.textContent.includes('12.50 CNY'));assert.equal(d.querySelectorAll('tbody tr').length,1);
button('新增费用').click();dialog=d.querySelector('dialog');
input('归属店铺','baby');input('订单编号','PREVIEW-ORDER');input('费用日期','2026-10-22');input('金额','12.50');input('费用来源编号','PREVIEW-FEE');
button('添加到预览',dialog).click();assert(dialog.textContent.includes('相同来源编号'));button('取消',dialog).click();
button('调整',d.querySelector('tbody')).click();dialog=d.querySelector('dialog');input('金额','15.00');
button('应用调整到预览',dialog).click();assert(dialog.textContent.includes('请填写调整原因'));
await Promise.resolve();
input('调整原因','核对运单后修订');button('应用调整到预览',dialog).click();
button('调整记录').click();assert(d.querySelector('tbody').textContent.includes('12.50 CNY'));assert(d.querySelector('tbody').textContent.includes('15.00 CNY'));assert(d.querySelector('tbody').textContent.includes('核对运单后修订'));
button('费用台账').click();select('费用币种','PHP');assert.equal(d.querySelectorAll('tbody tr').length,0,'currencies are never silently mixed');
w.FinanceExpensesDesign.mount(host,{shops,groups,canManage:false});assert(!button('新增费用'));
assert.equal(requests.length,4,'expense prototype makes no API requests');
w.FinanceExpensesDesign.reset();dom.window.close();
console.log('Finance cost design: read-only data access, permissions, catalog scope, SKU identity, bundle priority, conflicts and isolated preview drafts passed.');
console.log('Expense preview: memory-only edits, duplicate check, reasons and before/after trace, currency isolation and viewer permissions passed.');
