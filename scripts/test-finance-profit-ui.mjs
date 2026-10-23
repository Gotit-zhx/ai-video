import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<!doctype html><div id="finance-page" class="finance-workspace"></div>',{url:'https://example.test/#finance-overview',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,drilldowns=[];
w.currentUser={roles:['STAFF'],permissions:['finance:view']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
w.echarts={init:()=>({setOption(){},resize(){},dispose(){},getWidth(){return 1;},getHeight(){return 1;}})};
w.FinanceCosts={openOrders:filters=>drilldowns.push(filters),reset(){}};
const response={
  filters:{start:'2026-09-24',end:'2026-10-23',currency:'PHP',displayCurrency:'CNY'},
  groups:[],shops:[{id:'shop',name:'示例母婴店',display_name:'示例母婴店',platform:'TIKTOK_SHOP',region:'PH',currency:'PHP',timezone:'Asia/Manila'}],sync:[],currencies:['PHP'],
  totals:[{kind:'SETTLEMENT',amount:82646.26,records:3010},{kind:'ORDER',amount:116762.59,records:3500},{kind:'REFUND',amount:969.52},{kind:'FEE',amount:33976.43}],
  ranking:[{shop_id:'shop',currency:'CNY',kind:'SETTLEMENT',amount:82646.26,settled_gross_amount:116622.69,records:3010}],
  fees:[],statuses:[],trend:[],updatedAt:'2026-10-23T09:00:00Z',basis:'示例数据，用于界面验收',
  productCosts:{scope:'SETTLED_ORDER',basis:'仅统计平台已结算订单，按首次结算日期归属；未结算不计入。',total:{amount_cny:78473.52,known_amount_cny:78473.52,pending_count:0,ready_count:2771,estimated_count:1731,excluded_count:239},shops:[]},
  profit:{currency:'CNY',settledGrossAmount:116622.69,settledLedgerAmount:82646.26,platformFees:33976.43,advertising:0,logisticsLabelCost:0,productCost:78473.52,productCostCny:78473.52,amount:4172.74,status:'ESTIMATED',estimated:true,
    formula:'利润额 = 已结算额度 − 平台费用 − 广告消耗 − 物流贴单成本 − 商品成本',
    settlementBasis:'已结算额度按平台结算金额加回已扣平台费用计算。',refundBasis:'退款已体现在平台结算中，不再重复扣减。',basis:'按当前筛选期间已同步及已录入的数据计算。',
    notes:['广告消耗尚无记录，按 0 暂计，补录后利润将变化','商品成本包含历史月份取价估算']}
};
w.api=async path=>{assert(path.startsWith('/api/v1/finance/overview?'));return {data:structuredClone(response)};};
for(const file of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../frontend/'+file,import.meta.url),'utf8'));
async function ready(){for(let i=0;i<200;i++){await new Promise(r=>setTimeout(r,5));if(d.querySelector('#finance-page').getAttribute('aria-busy')==='false')return;}throw Error('UI timeout');}
const metric=key=>d.querySelector(`[data-metric="${key}"]`);
const refresh=()=>[...d.querySelectorAll('button')].find(n=>n.textContent.trim()==='刷新').click();
w.FinanceWorkspace.enter('overview');await ready();
assert.equal(d.querySelectorAll('.fi-metric').length,8);
assert.deepEqual([...d.querySelectorAll('.fi-metric-label>span:first-child')].map(n=>n.textContent),['已结算额度','订单金额','确认退款','平台费用','广告消耗','物流贴单成本','商品成本','利润额']);
assert(metric('SETTLEMENT').textContent.includes('116,622.69'));assert(!metric('SETTLEMENT').textContent.includes('82,646.26'));
assert(metric('PRODUCT_COST').textContent.includes('78,473.52'));assert(metric('PRODUCT_COST').textContent.includes('CNY'));
assert(metric('PROFIT').textContent.includes('4,172.74'));assert(metric('PROFIT').textContent.includes('暂估'));
assert(!d.querySelector('.fi-cost-quality').textContent.includes('78,473.52'),'product cost amount moves into the top metrics instead of being duplicated');
assert.equal(d.querySelectorAll('.fi-cost-quality .fi-cost-stats>div').length,3);
const old=metric('PROFIT');refresh();await ready();assert.equal(metric('PROFIT'),old,'unchanged refresh retains the existing metric');
metric('PROFIT').click();let dialog=d.querySelector('dialog');assert(dialog);assert(dialog.textContent.includes('不再重复扣减'));assert(dialog.textContent.includes('按 0 暂计'));assert.equal(dialog.querySelectorAll('tbody tr').length,6);dialog.close();
metric('PRODUCT_COST').click();assert.equal(drilldowns.at(-1).kind,'SETTLED_ORDER');
if(process.env.FINANCE_PREVIEW_PATH){
  const css=await fs.readFile(new URL('../frontend/finance-workspace.css',import.meta.url),'utf8');
  await fs.writeFile(process.env.FINANCE_PREVIEW_PATH,`<!doctype html><meta charset="utf-8"><title>财务总览 · 界面验收示例</title><style>${css}body{margin:0;padding:26px;background:#f7f8fb;font:14px/1.5 -apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif}#finance-page{max-width:1250px}body>p{max-width:1250px;margin:0 auto 12px;color:#8791a4;font-size:12px}</style><p>界面验收示例 · 非正式财务报表</p>${d.querySelector('#finance-page').outerHTML}`);
}
response.profit.amount=-35;response.profit.estimated=false;response.profit.status='READY';refresh();await ready();assert(metric('PROFIT').classList.contains('is-negative'));assert(metric('PROFIT').textContent.includes('-35'));
response.profit.amount=null;response.profit.status='INCOMPLETE_COST';response.productCosts.total.amount_cny=null;response.productCosts.total.pending_count=1;refresh();await ready();
assert(metric('PROFIT').textContent.includes('数据待补齐'));assert(!metric('PROFIT').classList.contains('is-negative'));
assert(metric('PRODUCT_COST').textContent.includes('仅已算部分'));assert(metric('PRODUCT_COST').textContent.includes('78,473.52'));
w.FinanceWorkspace.leave();dom.window.close();
console.log('Finance profit UI passed: eight metrics, settled gross, product-cost drilldown, profit explanation, incomplete/negative states and stable refresh.');
