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
  filters:{start:'2026-09-24',end:'2026-10-23',currency:'',displayCurrency:'CNY'},
  groups:[],shops:[{id:'shop',name:'示例母婴店',display_name:'示例母婴店',platform:'TIKTOK_SHOP',region:'PH',currency:'PHP',timezone:'Asia/Manila'}],sync:[],currencies:['PHP'],
  totals:[{kind:'SETTLEMENT',amount:82646.26,revenue:116622.69,records:3010,known_revenues:3010},{kind:'ORDER',amount:116762.59,records:3500},{kind:'REFUND',amount:969.52},{kind:'FEE',amount:33976.43}],
  ranking:[{shop_id:'shop',currency:'CNY',kind:'SETTLEMENT',amount:82646.26,settled_gross_amount:116622.69,records:3010}],
  fees:[],statuses:[],trend:[],updatedAt:'2026-10-23T09:00:00Z',basis:'示例数据，用于界面验收',
  productCosts:{scope:'SETTLED_ORDER',basis:'仅统计平台已结算订单，按首次结算日期归属；未结算不计入。',total:{amount_cny:78473.52,known_amount_cny:78473.52,pending_count:0,ready_count:2771,estimated_count:1731,excluded_count:239},shops:[]},
  profit:{currency:'CNY',settledGrossAmount:116622.69,settledLedgerAmount:82646.26,platformFees:33976.43,advertising:0,logisticsLabelCost:0,productCost:78473.52,productCostCny:78473.52,operatingCost:78473.52,knownOperatingCost:78473.52,amount:4172.74,status:'ESTIMATED',estimated:true,
    formula:'利润额 = 销售收入 − 已结算平台费用 − 广告消耗 − 营业成本',
    settlementBasis:'销售收入已包含优惠和退款；平台费用逐项列明。',refundBasis:'退款已体现在平台结算中，不再重复扣减。',basis:'按当前筛选期间已同步及已录入的数据计算。',
    notes:['广告消耗尚无记录，按 0 暂计，补录后利润将变化','商品成本包含历史月份取价估算']}
};
const categoryReads=[];let failCategory=false;
w.api=async path=>{if(path.startsWith('/api/v1/finance/qianyi/returns?')){if(failCategory)throw Error('test failed read');return {data:{summary:{refundTotals:[{currency:'PHP',amount:240,known_count:2,records:2}]}}};}if(path.startsWith('/api/v1/finance/workspace/costs/summary?')){const query=new URL(path,'https://example.test').searchParams;categoryReads.push(Object.fromEntries(query));assert.equal(query.get('kind'),'SAMPLE_SETTLED');return {data:{total:{amount_cny:120,known_amount_cny:120,order_count:2,pending_count:0}}};}assert(path.startsWith('/api/v1/finance/overview?'));const query=new URL(path,'https://example.test').searchParams;assert.equal(query.get('displayCurrency'),'CNY');assert.equal(query.get('currency'),'','default and refresh aggregate every source currency');return {data:structuredClone(response)};};
for(const file of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../frontend/'+file,import.meta.url),'utf8'));
async function ready(){for(let i=0;i<200;i++){await new Promise(r=>setTimeout(r,5));if(d.querySelector('#finance-page').getAttribute('aria-busy')==='false')return;}throw Error('UI timeout');}
const metric=key=>d.querySelector(`[data-metric="${key}"]`);
const refresh=()=>[...d.querySelectorAll('button')].find(n=>n.textContent.trim()==='刷新').click();
w.FinanceWorkspace.enter('overview');await ready();
assert.equal(d.querySelectorAll('.fi-metric').length,6);assert(d.querySelector('.fi-date-range').textContent.includes('结算日期'));
assert.equal(d.querySelector('.fi-pills [aria-pressed="true"]').textContent,'人民币 ¥');
assert.equal(d.querySelector('select[aria-label="原币"]').value,'');
assert([...d.querySelectorAll('.fi-metric-value small')].every(n=>n.textContent==='CNY'));
assert.deepEqual([...d.querySelectorAll('.fi-metric-label>span:first-child')].map(n=>n.textContent),['销售收入','已结算订单金额','已结算平台费用','广告消耗','营业成本','利润额']);
assert(metric('ORDER').querySelector('.fi-metric-note').textContent.includes('原订单金额合计，按首次结算日期归属；非平台净结算，不参与利润计算。'));
assert(metric('SETTLEMENT').textContent.includes('116,622.69'));assert(!metric('SETTLEMENT').textContent.includes('82,646.26'));
assert(metric('OPERATING_COST').textContent.includes('78,473.52'));assert(metric('OPERATING_COST').textContent.includes('CNY'));
assert(metric('PROFIT').textContent.includes('4,172.74'));assert(metric('PROFIT').textContent.includes('暂估'));
assert(!d.querySelector('.fi-cost-quality').textContent.includes('78,473.52'),'product cost amount moves into the top metrics instead of being duplicated');
assert.equal(d.querySelectorAll('.fi-cost-quality .fi-cost-stats>div').length,3);
const old=metric('PROFIT');refresh();await ready();assert.equal(metric('PROFIT'),old,'unchanged refresh retains the existing metric');
const firstAmounts=[...d.querySelectorAll('.fi-metric-value')].map(n=>n.textContent);
[...d.querySelectorAll('.fi-pills button')].find(n=>n.textContent==='人民币 ¥').click();await ready();
assert.deepEqual([...d.querySelectorAll('.fi-metric-value')].map(n=>n.textContent),firstAmounts,'first entry and manual CNY selection show the same amounts');
metric('PROFIT').click();let dialog=d.querySelector('dialog');assert(dialog);assert(dialog.textContent.includes('不再重复扣减'));assert(dialog.textContent.includes('按 0 暂计'));assert.equal(dialog.querySelectorAll('tbody tr').length,5);dialog.close();
assert.equal(categoryReads.length,0,'overview does not request cost classification');
metric('OPERATING_COST').click();await new Promise(r=>setTimeout(r,20));dialog=d.querySelector('dialog');
assert.equal(categoryReads.length,1);assert(dialog.textContent.includes('其中：商品成本—样品'));assert(dialog.textContent.includes('退款核对 · 数据来源：千易 ERP'));
assert(dialog.textContent.includes('120'));assert(dialog.textContent.includes('240 PHP'));assert(dialog.textContent.includes('不改变利润'));
assert.equal(dialog.querySelector('.fi-operating-total strong').textContent,'78,473.52 CNY','memo categories never add to the operating total');
if(process.env.FINANCE_BREAKDOWN_PREVIEW_PATH){const css=await fs.readFile(new URL('../frontend/finance-workspace.css',import.meta.url),'utf8');await fs.writeFile(process.env.FINANCE_BREAKDOWN_PREVIEW_PATH,`<!doctype html><meta charset="utf-8"><title>营业成本明细 · 验收示例</title><style>${css}body{background:#f7f8fb;font:14px/1.5 -apple-system,sans-serif}.fi-dialog{position:relative;margin:24px auto;max-height:none}</style>${dialog.outerHTML}`);}
[...dialog.querySelectorAll('button')].find(n=>n.textContent==='核对订单').click();assert.equal(drilldowns.at(-1).kind,'SAMPLE_SETTLED');
assert.equal(drilldowns.at(-1).start,categoryReads[0].start);assert.equal(drilldowns.at(-1).end,categoryReads[0].end);
failCategory=true;metric('OPERATING_COST').click();await new Promise(r=>setTimeout(r,20));dialog=d.querySelector('dialog');assert(dialog.textContent.includes('读取失败'));assert(dialog.textContent.includes('120'));dialog.close();failCategory=false;
response.profit.labelConfiguration={configuredShops:0,missingShops:[{shopId:'shop',groupId:'baby',name:'示例母婴店',region:'PH'}]};
refresh();await ready();assert(metric('OPERATING_COST').textContent.includes('贴单账单待补齐'));
metric('OPERATING_COST').click();await new Promise(r=>setTimeout(r,20));dialog=d.querySelector('dialog');
assert(dialog.textContent.includes('尚未匹配贴单账单'));assert(dialog.textContent.includes('仅包含已匹配部分'));dialog.close();
delete response.profit.labelConfiguration;refresh();await ready();
if(process.env.FINANCE_PREVIEW_PATH){
  const css=await fs.readFile(new URL('../frontend/finance-workspace.css',import.meta.url),'utf8');
  await fs.writeFile(process.env.FINANCE_PREVIEW_PATH,`<!doctype html><meta charset="utf-8"><title>财务总览 · 界面验收示例</title><style>${css}body{margin:0;padding:26px;background:#f7f8fb;font:14px/1.5 -apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif}#finance-page{max-width:1250px}body>p{max-width:1250px;margin:0 auto 12px;color:#8791a4;font-size:12px}</style><p>界面验收示例 · 非正式财务报表</p>${d.querySelector('#finance-page').outerHTML}`);
}
response.profit.amount=-35;response.profit.estimated=false;response.profit.status='READY';refresh();await ready();assert(metric('PROFIT').classList.contains('is-negative'));assert(metric('PROFIT').textContent.includes('-35'));
response.profit.amount=null;response.profit.status='INCOMPLETE_COST';response.profit.operatingCost=null;response.productCosts.total.amount_cny=null;response.productCosts.total.pending_count=1;refresh();await ready();
assert(metric('PROFIT').textContent.includes('数据待补齐'));assert(!metric('PROFIT').classList.contains('is-negative'));
assert(metric('OPERATING_COST').textContent.includes('仅已算部分'));assert(metric('OPERATING_COST').textContent.includes('78,473.52'));
assert(!d.querySelector('.fi-calculation-rail'),'missing cost data is not a running calculation');
assert(metric('PROFIT').textContent.includes('1 笔订单成本待补齐'));
metric('PROFIT').click();dialog=d.querySelector('dialog');
[...dialog.querySelectorAll('button')].find(n=>n.textContent==='核对 1 笔待补齐订单').click();
assert.equal(drilldowns.at(-1).costStatus,'PENDING');assert.equal(drilldowns.at(-1).kind,'SETTLED_ORDER');
response.profit.settledGrossAmount=null;
const settled=response.totals.find(r=>r.kind==='SETTLEMENT');
settled.known_revenues=2500;
response.settlementCalculation={status:'RUNNING',processed:60,total:100,scope:'所选店铺的历史结算与账单'};
refresh();await ready();
assert(metric('SETTLEMENT').textContent.includes('结算金额计算中'));
assert(metric('SETTLEMENT').querySelector('[role="progressbar"]'));
assert(metric('SETTLEMENT').textContent.includes('116,622.69'),'known provider amounts remain visible while the rest is computing');
assert(metric('SETTLEMENT').querySelector('.fi-partial-badge').textContent==='部分金额');
assert(metric('SETTLEMENT').textContent.includes('待补 510 笔'));
assert(d.querySelector('.fi-calculation-detail').textContent.includes('60 / 100'));
assert(!metric('AD').querySelector('[role="progressbar"]'),'no advertising data does not mean computing');
if(process.env.FINANCE_PROGRESS_PREVIEW_PATH){
  const css=await fs.readFile(new URL('../frontend/finance-workspace.css',import.meta.url),'utf8');
  await fs.writeFile(process.env.FINANCE_PROGRESS_PREVIEW_PATH,`<!doctype html><meta charset="utf-8"><title>计算进度 · 界面验收示例</title><style>${css}body{margin:0;padding:26px;background:#f7f8fb;font:14px/1.5 -apple-system,BlinkMacSystemFont,"PingFang SC",sans-serif}#finance-page{max-width:1250px}body>p{color:#8791a4;font-size:12px}</style><p>界面验收示例 · 非正式财务数据</p>${d.querySelector('#finance-page').outerHTML}`);
}
const stable=metric('SETTLEMENT'),rail=stable.querySelector('.fi-calculation-rail');refresh();await ready();
assert.equal(metric('SETTLEMENT'),stable);assert.equal(stable.querySelector('.fi-calculation-rail'),rail,'polling does not restart the animation');
response.settlementCalculation.status='INTERRUPTED';refresh();await ready();
assert(!metric('SETTLEMENT').querySelector('[role="progressbar"]'));assert(metric('SETTLEMENT').textContent.includes('计算暂时中断'));
assert(metric('SETTLEMENT').textContent.includes('116,622.69'),'interruption cannot hide amounts already calculated');
response.settlementCalculation.status='FAILED';refresh();await ready();
assert(metric('SETTLEMENT').textContent.includes('计算暂时中断'));assert(metric('SETTLEMENT').querySelector('.fi-partial-badge'));
settled.known_revenues=0;settled.revenue=null;refresh();await ready();
assert(!metric('SETTLEMENT').textContent.includes('116,622.69'),'an empty filter/result cannot reuse another result amount');
assert(metric('SETTLEMENT').textContent.includes('待补 3,010 笔'));
settled.known_revenues=3010;settled.revenue=116622.69;
response.settlementCalculation.status='COMPLETE';response.profit.settledGrossAmount=116622.69;
response.productCosts.processing=true;refresh();await ready();
assert(!metric('SETTLEMENT').querySelector('[role="progressbar"]'));assert(metric('SETTLEMENT').textContent.includes('116,622.69'));
assert(!metric('SETTLEMENT').querySelector('.fi-partial-badge'),'completion removes the partial badge');
assert(metric('OPERATING_COST').querySelector('[role="progressbar"]'));assert(metric('OPERATING_COST').textContent.includes('78,473.52'),'known costs remain visible while queued costs calculate');
response.productCosts.processing=false;refresh();await ready();assert(!d.querySelector('.fi-calculation-rail'));
response.productCosts.processing=true;response.productCosts.productProcessing=false;response.productCosts.labelProcessing=true;refresh();await ready();
assert(metric('OPERATING_COST').textContent.includes('贴单费用更新中'));
assert(d.querySelector('.fi-cost-quality').textContent.includes('商品成本已保留'));
response.profit.returnedProductCost=340.684917;
response.productCosts.shops=[{shop_id:'shop',amount_cny:74331.810998,known_amount_cny:74331.810998,label_cost_cny:4112.55,return_cost_cny:340.684917}];
const previousApi=w.api,receiptQueries=[];
w.api=async path=>{
  if(path.startsWith('/api/v1/finance/workspace/costs/returns?')){
    receiptQueries.push(Object.fromEntries(new URL(path,'https://example.test').searchParams));
    return {data:[{shop_name:'示例母婴店',order_id:'586120853671806580',receipt_date:'2026-09-28',amount_cny:36.297938,source_name:'已确认入库.xlsx',evidence:{sku:'ACTUAL-XL',quantity:2}}]};
  }
  return previousApi(path);
};
refresh();await ready();
assert(!d.querySelector('#finance-page').textContent.includes('退货入库冲回（人民币）'));
metric('OPERATING_COST').click();await ready();dialog=[...d.querySelectorAll('dialog')].at(-1);
const returnedButton=[...dialog.querySelectorAll('button')].find(n=>n.textContent==='退货成本冲回');
assert(!returnedButton,'cancelled return-cost reversal stays disabled');
assert.equal(receiptQueries.length,0);
assert(dialog.textContent.includes('退款核对 · 数据来源：千易 ERP'));
w.FinanceWorkspace.leave();dom.window.close();
console.log('Finance profit UI passed: six metrics with operating-cost grouping and lazy subset drilldowns, settled gross, product-cost drilldown, profit explanation, incomplete/negative states and stable refresh.');
