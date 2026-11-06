import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<!doctype html><main class="finance-workspace"><div id="charges"></div></main>',{url:'https://example.test',runScripts:'outside-only'});
const w=dom.window,d=w.document,root=d.querySelector('#charges'),reads=[],writes=[];
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
const records={rules:[{id:'ph',country:'PH',effective_date:'2026-10-01',unit_price_cny:0.5,version:1}],imports:[{id:'file1',source_name:'母婴费用.xlsx',row_count:2}],
  totals:{records:53,unmatched:1},recordTotal:53,sourceSummary:{principal_rows:51,service_fee_cny:357},
  marked:{orders:1,service_fee_cny:12},periodMarked:{orders:0,service_fee_cny:0},
  labelSummary:{processing:false,total:{order_count:7,label_cost_cny:3.5,queued_count:0,charges_pending:0}},rows:[
    {order_id:'ORDER-1',shop_hint:'母婴菲律宾店',principal_native:199,service_fee_cny:12,match:{matchStatus:'MATCHED',currency:'PHP',matchNote:'精确匹配'}},
    {order_id:'ORDER-2',shop_hint:'母婴菲律宾店',principal_native:299,service_fee_cny:12,match:{matchStatus:'UNMATCHED',matchNote:'等待订单同步'}}]};
let fail=false;
const request=async(path,options={})=>{
  assert(!path.includes('expenses'),'removed legacy ledger must not be requested');
  if(options.method){writes.push({path,...options});return {};}
  reads.push(path);if(fail)throw Error('分组读取失败');
  if(path.startsWith('/label-costs'))return {files:[],rows:[],period:{amount_cny:0,orders:0,shipments:0,conflicts:0},state:{},processing:false,total:0};
  return new URL(path,'https://example.test').searchParams.get('groupId')==='baby'?structuredClone(records):{rules:[],imports:[],rows:[],totals:{records:0,unmatched:0},sourceSummary:{principal_rows:0,service_fee_cny:0},marked:{}};
};
for(const file of ['incremental-dom.js','finance-label-costs.js','finance-charges.js'])w.eval(await fs.readFile(new URL('../../frontend/'+file,import.meta.url),'utf8'));
const wait=()=>new Promise(r=>setTimeout(r,15));
const btn=(text,scope=d)=>[...scope.querySelectorAll('button')].find(b=>b.textContent===text);
const mount=canManage=>w.FinanceCharges.mount(root,{groups:[{id:'baby',name:'母婴组'},{id:'other',name:'其他组'}],request,canManage,filters:{start:'2026-10-01',end:'2026-10-27'},
  numberSearch(host,value,change){const input=d.createElement('input');input.setAttribute('aria-label','核对订单编号');input.value=value;input.onchange=event=>change(event.currentTarget.value);host.append(input);}});
mount(true);await wait();
assert.equal(root.querySelectorAll('.fq-tabs button').length,2);
assert(root.textContent.includes('月度贴单账单'));assert(!btn('新增贴单规则'));
btn('刷单成本').click();await wait();
assert(!root.textContent.includes('历史其他费用'));assert(root.textContent.includes('母婴费用.xlsx'));
assert(root.querySelector('.fq-toolbar').contains(btn('导入 Excel')));
assert(root.querySelector('.fq-toolbar').contains(d.querySelector('[aria-label="费用分组"]')));
assert(reads[0].includes('start=2026-10-01')&&reads[0].includes('end=2026-10-27'));
const metric=name=>[...root.querySelectorAll('.fc-metric')].find(n=>n.querySelector('span').textContent===name)?.querySelector('strong').textContent;
assert.equal(metric('范围内刷单合计'),'51','nonzero principal count covers every query page, independent of the settled count');
assert.equal(metric('范围内刷单费合计（¥）'),'357.00','fee total comes from the whole source query, not the current page or deduplicated markers');
assert(root.textContent.includes('本金原币非零的记录笔数'));assert(root.textContent.includes('独立期间统计'));
assert(root.textContent.includes('累计已标记订单'));
btn('下一页').click();await wait();assert(reads.at(-1).includes('page=2'));
assert.equal(metric('范围内刷单合计'),'51');assert.equal(metric('范围内刷单费合计（¥）'),'357.00');
const originalSummary=records.sourceSummary;
records.sourceSummary={principal_rows:0,service_fee_cny:7.5};
let numberInput=d.querySelector('[aria-label="核对订单编号"]');numberInput.value='ZERO-PRINCIPAL';numberInput.dispatchEvent(new w.Event('change'));await wait();
assert(reads.at(-1).includes('numbers=ZERO-PRINCIPAL'));assert(reads.at(-1).includes('page=1'));
assert.equal(metric('范围内刷单合计'),'0');assert.equal(metric('范围内刷单费合计（¥）'),'7.50','fees remain independent of principal zero values');
records.sourceSummary={principal_rows:2,service_fee_cny:0};
numberInput=d.querySelector('[aria-label="核对订单编号"]');numberInput.value='ZERO-FEE';numberInput.dispatchEvent(new w.Event('change'));await wait();
assert.equal(metric('范围内刷单合计'),'2');assert.equal(metric('范围内刷单费合计（¥）'),'0.00');
delete records.sourceSummary;
numberInput=d.querySelector('[aria-label="核对订单编号"]');numberInput.value='OLDER-RESPONSE';numberInput.dispatchEvent(new w.Event('change'));await wait();
assert.equal(metric('范围内刷单合计'),'待更新');assert.equal(metric('范围内刷单费合计（¥）'),'待更新','missing aggregates must not fall back to a different scope');
records.sourceSummary=originalSummary;
numberInput=d.querySelector('[aria-label="核对订单编号"]');numberInput.value='';numberInput.dispatchEvent(new w.Event('change'));await wait();
assert(root.textContent.includes('等待订单同步'),'unmatched reason is directly visible');
if(process.env.FINANCE_CHARGES_PREVIEW_PATH){
  const css=(await Promise.all(['finance-workspace.css','finance-costs.css','finance-expenses-design.css'].map(file=>fs.readFile(new URL('../../frontend/'+file,import.meta.url),'utf8')))).join('\n');
  await fs.writeFile(process.env.FINANCE_CHARGES_PREVIEW_PATH,`<!doctype html><meta charset="utf-8"><title>费用与调整 · 布局验收示例</title><style>${css}body{margin:0;padding:28px;background:#f7f8fb;font:14px/1.5 -apple-system,sans-serif}.finance-workspace{max-width:1250px;margin:auto}</style><p>界面验收示例 · 非正式财务数据</p>${d.querySelector('main').outerHTML}`);
}
btn('贴单成本').click();await wait();
assert.equal(btn('贴单成本').getAttribute('aria-pressed'),'true');assert(btn('导入月度账单'));assert(!btn('新增贴单规则'));assert(!btn('导入 Excel'));
btn('刷单成本').click();await wait();
btn('重新匹配').click();await wait();assert.equal(writes[0].path,'/charges/rematch');assert.equal(JSON.parse(writes[0].body).groupId,'baby');
const dateInput=d.querySelector('[aria-label="开始日期"]');dateInput.value='2026-09-01';dateInput.dispatchEvent(new w.Event('change'));await wait();
assert(reads.at(-1).includes('start=2026-09-01'));
assert.equal(metric('范围内刷单合计'),'51');assert.equal(metric('范围内刷单费合计（¥）'),'357.00','source totals retain the audit-table scope when the settlement range changes');
dateInput.value='2026-11-01';dateInput.dispatchEvent(new w.Event('change'));await wait();assert(root.textContent.includes('开始日期不能晚于结束日期'));
btn('导入 Excel').click();await wait();
assert(d.querySelector('input[type=file]').accept.includes('.xlsx'));assert(btn('确认导入').disabled);d.querySelector('dialog').close();
const select=d.querySelector('[aria-label="费用分组"]');fail=true;select.value='other';select.dispatchEvent(new w.Event('change'));await wait();
assert(root.textContent.includes('分组读取失败'));assert(!root.textContent.includes('母婴费用.xlsx'),'failed group change cannot display another group’s records');
fail=false;select.dispatchEvent(new w.Event('change'));await wait();
assert.equal(metric('范围内刷单合计'),'0');assert.equal(metric('范围内刷单费合计（¥）'),'0.00','empty groups must not retain another group’s totals');
fail=false;select.value='baby';select.dispatchEvent(new w.Event('change'));await wait();assert(root.textContent.includes('母婴费用.xlsx'));
assert.equal(metric('范围内刷单合计'),'51');assert.equal(metric('范围内刷单费合计（¥）'),'357.00');
w.FinanceCharges.reset();mount(false);await wait();assert(!btn('导入 Excel'));btn('贴单成本').click();await wait();assert(!btn('新增贴单规则'));assert(!btn('调整'));assert(!btn('移除'));
w.FinanceCharges.reset();w.close();
console.log('Finance charges passed: nonzero principal counts, CNY fee sums, all-page totals, number searches, zero values, missing aggregates, group isolation, imports and read-only access.');
