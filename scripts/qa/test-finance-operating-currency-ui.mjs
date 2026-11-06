import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<div id="finance-page" class="finance-workspace"></div>',{url:'https://example.test/#finance-overview',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document;
w.currentUser={roles:['STAFF'],permissions:['finance:view']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
w.echarts={init:()=>({setOption(){},resize(){},dispose(){},getWidth(){return 1;},getHeight(){return 1;}})};
w.FinanceCosts={openOrders(){},reset(){}};
const returns=[];w.FinanceQianyi={openReturns(context){returns.push(context);}};
let rate=.1,partial=false,sample=12,refund=18,hold=false,pending=[];
const response=(cny,start,end)=>({
  filters:{start,end,currency:cny?'':'PHP',displayCurrency:cny?'CNY':'PHP'},groups:[],shops:[],sync:[],currencies:['PHP'],
  totals:[{kind:'SETTLEMENT',records:1,known_revenues:1,revenue:100,amount:80}],ranking:[],fees:[],statuses:[],trend:[],
  productCosts:{scope:'SETTLED_ORDER',total:{amount_cny:50,known_amount_cny:50,pending_count:0,ready_count:1},shops:[]},
  profit:{currency:cny?'CNY':'PHP',costCnyRate:cny?1:rate,productCost:cny?50:500,productCostCny:50,
    logisticsLabelCost:cny?2:20,returnedProductCost:cny?3:30,returnedProductCostCny:3,
    operatingCost:cny?52:520,amount:cny?31:310,status:'READY',fx:{id:'fixed',time:'2026-10-29T00:00:00Z',provider:'test'},notes:[]}
});
w.api=async path=>{
  const url=new URL(path,'https://example.test'),q=url.searchParams;
  if(url.pathname.endsWith('/qianyi/returns')){assert.equal(q.get('refundStatus'),'FINISH');assert.equal(q.get('dateField'),'CREATED');return {data:{summary:{refundTotals:[{currency:'PHP',amount:44,records:1,known_count:1}]}}};}
  if(url.pathname.endsWith('/overview'))return {data:response(q.get('displayCurrency')==='CNY',q.get('start'),q.get('end'))};
  if(url.pathname.endsWith('/costs/summary')){
    if(hold)await new Promise(resolve=>pending.push(resolve));
    const amount=q.get('kind')==='SAMPLE_SETTLED'?sample:refund;
    return {data:{total:{amount_cny:partial?null:amount,known_amount_cny:amount,pending_count:partial?1:0,order_count:2}}};
  }
  if(url.pathname.endsWith('/costs/returns'))return {data:[{shop_name:'测试店',order_id:'return-1',receipt_date:'2026-09-28',amount_cny:3,evidence:{sku:'ACTUAL-M',quantity:1}}]};
  throw Error(path);
};
for(const file of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../../frontend/'+file,import.meta.url),'utf8'));
async function wait(test){for(let i=0;i<200;i++){if(test())return;await new Promise(r=>setTimeout(r,5));}throw Error('UI timeout');}
const ready=()=>wait(()=>d.querySelector('#finance-page').getAttribute('aria-busy')==='false');
const button=(root,text)=>[...root.querySelectorAll('button')].find(n=>n.textContent.trim()===text);
const row=(dialog,text)=>[...dialog.querySelectorAll('tr')].find(r=>r.firstChild?.textContent===text);
const last=()=>[...d.querySelectorAll('dialog')].at(-1);
async function open(){d.querySelector('[data-metric="OPERATING_COST"]').click();const dialog=last();if(!hold)await wait(()=>row(dialog,'其中：商品成本—样品'));return dialog;}
async function closeAll(){for(const dialog of [...d.querySelectorAll('dialog')])dialog.close();}
w.FinanceWorkspace.enter('overview');await ready();
let dialog=await open();
assert.equal(row(dialog,'其中：商品成本—样品').children[1].textContent,'¥ 12');
await wait(()=>row(dialog,'已完成退款（千易）'));assert.equal(row(dialog,'已完成退款（千易）').children[1].textContent,'44 PHP');assert(!dialog.textContent.includes('冲回'));
assert(dialog.textContent.includes('商品成本（CNY）'));await closeAll();
button(d.querySelector('.fi-pills'),'原币').click();await ready();
dialog=await open();
assert(dialog.querySelector('.fi-operating-total').textContent.includes('520 PHP'));
assert.equal(row(dialog,'其中：商品成本—样品').children[1].textContent,'120');
await wait(()=>row(dialog,'已完成退款（千易）'));assert.equal(row(dialog,'已完成退款（千易）').children[1].textContent,'44 PHP');
assert(dialog.textContent.includes('商品成本（PHP）'));
button(dialog,'已完成退款（千易）').click();assert.equal(returns.at(-1).selection.dateField,'CREATED');assert.equal(returns.at(-1).selection.currency,'');await closeAll();
// Keep the open dialog's rate when background refresh obtains a different quote.
hold=true;dialog=await open();await wait(()=>pending.length===1);
rate=.2;button(d,'刷新').click();await ready();hold=false;pending.splice(0).forEach(resolve=>resolve());
await wait(()=>row(dialog,'其中：商品成本—样品'));
assert.equal(row(dialog,'其中：商品成本—样品').children[1].textContent,'120');await closeAll();
rate=null;partial=true;button(d,'刷新').click();await ready();dialog=await open();
assert.equal(row(dialog,'其中：商品成本—样品').children[1].textContent,'汇率待补齐 · 已算部分');await closeAll();
sample=0;refund=null;dialog=await open();
assert.equal(row(dialog,'其中：商品成本—样品').children[1].textContent,'0 · 已算部分');
assert(!row(dialog,'其中：退货退款关联商品成本'));await closeAll();
sample=12;refund=18;partial=false;
button(d.querySelector('.fi-pills'),'人民币 ¥').click();await ready();dialog=await open();
assert.equal(row(dialog,'其中：商品成本—样品').children[1].textContent,'¥ 12');
await wait(()=>row(dialog,'已完成退款（千易）'));button(dialog,'已完成退款（千易）').click();assert.equal(returns.at(-1).selection.displayCurrency,'CNY');assert.equal(returns.at(-1).selection.refundStatus,'FINISH');
w.FinanceWorkspace.leave();dom.window.close();
console.log('Operating cost currency passed: CNY/PHP toggles, sample conversion and Qianyi refund source, frozen quote, missing FX, partial and zero amounts.');
