import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<!doctype html><main class="finance-workspace"><div id="charges"></div></main>',{url:'https://example.test',runScripts:'outside-only'});
const w=dom.window,d=w.document,root=d.querySelector('#charges'),reads=[],writes=[];
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
const records={rules:[{id:'ph',country:'PH',effective_date:'2026-10-01',unit_price_cny:0.5,version:1}],imports:[{id:'file1',source_name:'母婴费用.xlsx',row_count:2}],
  totals:{records:2,unmatched:1},marked:{orders:1,service_fee_cny:12},rows:[
    {order_id:'ORDER-1',shop_hint:'母婴菲律宾店',principal_native:199,service_fee_cny:12,match:{matchStatus:'MATCHED',currency:'PHP',matchNote:'精确匹配'}},
    {order_id:'ORDER-2',shop_hint:'母婴菲律宾店',principal_native:299,service_fee_cny:12,match:{matchStatus:'UNMATCHED',matchNote:'等待订单同步'}}]};
let fail=false;
const request=async(path,options={})=>{
  assert(!path.includes('expenses'),'removed legacy ledger must not be requested');
  if(options.method){writes.push({path,...options});return {};}
  reads.push(path);if(fail)throw Error('分组读取失败');
  return new URL(path,'https://example.test').searchParams.get('groupId')==='baby'?structuredClone(records):{rules:[],imports:[],rows:[],totals:{records:0,unmatched:0},marked:{}};
};
for(const file of ['incremental-dom.js','finance-charges.js'])w.eval(await fs.readFile(new URL('../frontend/'+file,import.meta.url),'utf8'));
const wait=()=>new Promise(r=>setTimeout(r,15));
const btn=(text,scope=d)=>[...scope.querySelectorAll('button')].find(b=>b.textContent===text);
const mount=canManage=>w.FinanceCharges.mount(root,{groups:[{id:'baby',name:'母婴组'},{id:'other',name:'其他组'}],request,canManage});
mount(true);await wait();
assert.equal(root.querySelectorAll('.fq-tabs button').length,2);
assert(!root.textContent.includes('历史其他费用'));assert(root.textContent.includes('母婴费用.xlsx'));
assert(root.querySelector('.fq-toolbar').contains(btn('导入 Excel')));
assert(root.querySelector('.fq-toolbar').contains(d.querySelector('[aria-label="费用分组"]')));
if(process.env.FINANCE_CHARGES_PREVIEW_PATH){
  const css=(await Promise.all(['finance-workspace.css','finance-costs.css','finance-expenses-design.css'].map(file=>fs.readFile(new URL('../frontend/'+file,import.meta.url),'utf8')))).join('\n');
  await fs.writeFile(process.env.FINANCE_CHARGES_PREVIEW_PATH,`<!doctype html><meta charset="utf-8"><title>费用与调整 · 布局验收示例</title><style>${css}body{margin:0;padding:28px;background:#f7f8fb;font:14px/1.5 -apple-system,sans-serif}.finance-workspace{max-width:1250px;margin:auto}</style><p>界面验收示例 · 非正式财务数据</p>${d.querySelector('main').outerHTML}`);
}
btn('贴单成本').click();await wait();
assert.equal(btn('贴单成本').getAttribute('aria-pressed'),'true');assert(btn('新增贴单规则'));assert(!btn('导入 Excel'));
const retained=root.querySelector('tbody tr');
btn('调整').click();await wait();
assert(d.querySelector('dialog [aria-label="国家"]').disabled);
btn('保存并重算').click();await wait();
assert.equal(writes.length,1);assert.equal(JSON.parse(writes[0].body).groupId,'baby');assert.equal(JSON.parse(writes[0].body).unitPriceCny,'0.5');
assert.equal(root.querySelector('tbody tr'),retained,'unchanged refresh patches content and preserves rows');
btn('刷单成本').click();await wait();btn('导入 Excel').click();await wait();
assert(d.querySelector('input[type=file]').accept.includes('.xlsx'));assert(btn('确认导入').disabled);d.querySelector('dialog').close();
const select=d.querySelector('[aria-label="费用分组"]');fail=true;select.value='other';select.dispatchEvent(new w.Event('change'));await wait();
assert(root.textContent.includes('分组读取失败'));assert(!root.textContent.includes('母婴费用.xlsx'),'failed group change cannot display another group’s records');
fail=false;select.value='baby';select.dispatchEvent(new w.Event('change'));await wait();assert(root.textContent.includes('母婴费用.xlsx'));
w.FinanceCharges.reset();mount(false);await wait();assert(!btn('导入 Excel'));btn('贴单成本').click();await wait();assert(!btn('新增贴单规则'));assert(!btn('调整'));assert(!btn('移除'));
w.FinanceCharges.reset();w.close();
console.log('Finance charges passed: compact toolbar, scoped tabs, no legacy requests, RMB rule payloads, preserved rows and read-only access.');
