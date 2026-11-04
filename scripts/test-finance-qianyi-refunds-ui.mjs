import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-orders',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,calls=[],exports=[];
w.currentUser={organizationId:'org',userId:'user',permissions:['finance:view']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
w.echarts={init:()=>({setOption(){},resize(){},dispose(){}})};
w.URL.createObjectURL=()=> 'blob:qa';w.URL.revokeObjectURL=()=>{};w.HTMLAnchorElement.prototype.click=function(){};
w.fetch=async path=>{exports.push(new URL(path,'https://example.test'));return {ok:true,blob:async()=>new w.Blob(['qa'])};};
let gate=null;
const shops=[{id:'ph',name:'示例菲律宾店',platform:'TIKTOK_SHOP',group_id:'baby'}],groups=[{id:'baby',name:'母婴'}];
w.api=async(path,options={})=>{
  const url=new URL(path,'https://example.test'),q=Object.fromEntries(url.searchParams);calls.push({url,q,options});
  if(url.pathname.endsWith('/shops'))return {data:{items:shops,groups}};
  if(url.pathname.endsWith('/qianyi/returns')){
    if(gate){const held=gate;gate=null;await new Promise(resolve=>held.release=resolve);}
    const ready=q.refundStatus==='FINISH';
    return {data:{total:73,page:Number(q.page),pageSize:50,basis:'千易接口原始口径',
      summary:{records:73,awaitingLogistics:2,returnCost:{amountCny:ready?125.5:null,knownAmountCny:ready?125.5:80,pendingRecords:ready?0:5,estimatedRecords:ready?2:0},refundTotals:[{currency:'PHP',records:73,known_count:73,amount:ready?730:1460}]},
      items:[{shop_id:'ph',shop_name:'示例菲律宾店',return_number:ready?'Q-DONE':'Q-PENDING',order_id:'586208660231587825',aftersale_type:q.type||'RETURN',refund_state:q.refundStatus||'WAIT_PAYMENT',return_state:'FINISH',source_received_at:1788291000000,return_receipt_state:'SIGNED',cost_status:ready?'READY':'WAIT_COST_BASIS',amount_cny:ready?125.5:null,refund_amount:10,refund_currency:'PHP'}]}};
  }
  if(url.pathname.endsWith('/overview'))return {data:{filters:{...q,currency:'',displayCurrency:'CNY'},fx:{id:'fixed',provider:'QA',time:'2026-11-02'},shops,groups,currencies:['PHP'],sync:[],totals:[],trend:[],fees:[],statuses:[],ranking:[]}};
  if(url.pathname.endsWith('/records'))return {data:{items:[{order_id:'PLATFORM-ORDER'}],total:1,page:1,pageSize:50,fieldSettings:{fields:[{id:'order_id',label:'订单编号'}],visible:['order_id']}}};
  throw Error('Unexpected request '+path);
};
for(const file of ['incremental-dom.js','incremental-charts.js','finance-qianyi.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../frontend/'+file,import.meta.url),'utf8'));
const pause=()=>new Promise(r=>setTimeout(r,10));
const button=text=>[...d.querySelectorAll('button')].find(n=>n.textContent.trim()===text);
async function wait(fn){for(let i=0;i<200;i++){if(fn())return;await pause();}throw Error(d.body.textContent);}
async function change(label,value){const n=d.querySelector(`[aria-label="${label}"]`);assert(n,label);n.value=value;n.dispatchEvent(new w.Event('change'));await pause();}
w.FinanceWorkspace.enter('orders');await wait(()=>button('退款'));
const before=calls.length;button('退款').click();await wait(()=>d.querySelector('.fq-totals')?.textContent.includes('73'));
const refundCalls=calls.slice(before);assert(refundCalls.every(c=>!c.url.pathname.endsWith('/records')&&!c.url.pathname.endsWith('/overview')));
assert.equal(refundCalls.at(-1).q.type,'SALES_RETURN');assert.equal(d.querySelector('[aria-label="售后类型"]').value,'SALES_RETURN');
assert.equal(refundCalls.at(-1).q.returnStatus,'FINISH');assert.equal(refundCalls.at(-1).q.receiptStatus,'SIGNED');assert.equal(refundCalls.at(-1).q.dateField,'RECEIPT');
assert.equal(refundCalls.at(-1).q.receiptScope,'RECEIVED');
assert([...d.querySelectorAll('th')].some(n=>n.textContent==='收货时间（北京时间）'));
assert(d.querySelector('.fq-totals').textContent.includes('物流待确认（未计入）'));
assert(d.querySelector('.fq-totals').textContent.includes('退货成本合计（CNY）'));
assert(d.querySelector('.fq-totals').textContent.includes('已匹配 80'));
assert(d.querySelector('.fq-totals').textContent.includes('5 笔 · 合计仅含已匹配部分'));
assert(!d.querySelector('.fq-panel').textContent.includes('退货成本冲回'));assert(!d.querySelector('[aria-label="成本状态"]'));assert(!button('导出报表'));assert(!d.querySelector('.fi-date-range'));
await change('退款状态','FINISH');assert(d.querySelector('.fq-totals').textContent.includes('125.5'));assert(d.querySelector('.fq-totals').textContent.includes('730'));
assert(!d.querySelector('.fq-totals').textContent.includes('退货成本待补齐'));assert(d.querySelector('.fq-totals').textContent.includes('使用历史成本价'));
await change('售后类型','RETURN');assert.equal(calls.at(-1).q.type,'RETURN');assert.equal(calls.at(-1).q.refundStatus,'FINISH');
assert.equal(calls.at(-1).q.receiptScope,'ALL');
button('导出千易售后 Excel').click();await pause();assert(exports.at(-1).pathname.endsWith('/qianyi/returns/export'));assert.equal(exports.at(-1).searchParams.get('type'),'RETURN');
assert.equal(exports.at(-1).searchParams.get('dateField'),'RECEIPT');assert.equal(exports.at(-1).searchParams.get('receiptScope'),'ALL');
button('下一页').click();await pause();assert.equal(calls.at(-1).q.page,'2');assert(d.querySelector('.fq-totals').textContent.includes('73'));
const held={};gate=held;await change('退款状态','WAIT_PAYMENT');assert(d.querySelector('.fq-totals').hidden);
await change('退款状态','FINISH');assert(!d.querySelector('.fq-totals').hidden);held.release();await pause();
assert(d.querySelector('.fq-totals').textContent.includes('125.5'));assert(!d.querySelector('.fq-panel').textContent.includes('Q-PENDING'));
const leaving={};gate=leaving;button('查询').click();await pause();button('订单').click();await wait(()=>d.body.textContent.includes('PLATFORM-ORDER'));
leaving.release();await pause();assert(!d.querySelector('.fq-panel'));assert(d.body.textContent.includes('PLATFORM-ORDER'));
w.FinanceWorkspace.reset();dom.window.close();
console.log('PASS embedded Qianyi refund source, defaults, full-filter totals, export, pagination, loading and rapid-switch isolation.');
