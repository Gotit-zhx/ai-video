import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');

const files=['incremental-dom.js','incremental-charts.js','finance-label-costs.js','finance-costs.js','finance-workspace.js'];
const sources=await Promise.all(files.map(file=>fs.readFile(new URL('../../frontend/'+file,import.meta.url),'utf8')));
const shops=[{id:'shop-a',name:'A 店',group_id:'group-a',platform:'SHOPEE',region:'PH',currency:'PHP',timezone:'Asia/Manila'}];
const groups=[{id:'group-a',name:'A 分组'},{id:'empty',name:'空分组'}];
const shopsResponse={data:{items:shops,groups,sync:[]}};
const orderResponse={data:{items:[],total:0,page:1,pageSize:50,productCosts:{total:{pending_count:0,ready_count:0,known_amount_cny:0}}}};
const fixture=marker=>({data:{
  files:[{id:'invoice',source_name:marker+'.xlsx',status:'READY',period_start:'2026-10-01',period_end:'2026-10-31',row_count:3,matched_count:2,unmatched_count:1,review_count:0,conflict_count:0,amount_cny:12.5}],
  rows:[{import_id:'invoice',source_row:1,order_id:marker,shop_name:'A 店',customer:'客户 A',warehouse:'菲律宾仓',document_no:'OUT-1',transaction_date:'2026-10-03',amount_cny:12.5,match_status:'MATCHED',match_note:'已绑定',import_status:'READY'}],
  total:1,page:1,pageSize:50,period:{orders:2,amount_cny:12.5,conflicts:0,shipments:3},state:{},processing:false,basis:'参考号绑定订单，人民币'
}});
const deferred=()=>{let resolve,reject,signal;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {
  wait(nextSignal){signal=nextSignal;if(signal?.aborted)reject(signal.reason);else signal?.addEventListener('abort',()=>reject(signal.reason),{once:true});return promise;},
  resolve,reject,get signal(){return signal;}
}};
const pause=()=>new Promise(resolve=>setTimeout(resolve,20));
const waitFor=async predicate=>{for(let index=0;index<150;index++){if(predicate())return;await new Promise(resolve=>setTimeout(resolve,10));}assert.fail('Timed out waiting for label prefetch state');};

function boot(api){
  const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-costs',runScripts:'outside-only',pretendToBeVisual:true});
  const w=dom.window,d=w.document;
  w.currentUser={organizationId:'company',userId:'reader',permissions:['finance:view']};
  w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};w.api=api;
  for(const source of sources)w.eval(source);
  const button=(text,scope=d)=>[...scope.querySelectorAll('button')].find(node=>node.textContent.trim()===text||node.textContent.trim().endsWith(text));
  const complete=marker=>d.querySelector('#finance-page').getAttribute('aria-busy')==='false'&&d.querySelector('.fl-content')?.getAttribute('aria-busy')==='false'&&d.body.textContent.includes(marker);
  return {dom,w,d,button,complete,close(){w.FinanceWorkspace.reset();dom.window.close();}};
}

{
  const shopGate=deferred(),labelGate=deferred(),calls=[];
  const app=boot(async(path,options={})=>{const url=new URL(path,'https://example.test');calls.push(url);
    if(url.pathname.endsWith('/shops'))return shopGate.wait(options.signal);
    if(url.pathname.endsWith('/label-costs'))return labelGate.wait(options.signal);
    throw Error('Unexpected data-first request '+path);
  });
  app.w.FinanceWorkspace.enter('costs');await waitFor(()=>calls.some(url=>url.pathname.endsWith('/label-costs')));
  const initialRead=calls.find(url=>url.pathname.endsWith('/label-costs'));assert.equal(initialRead.searchParams.get('page'),'1');assert(initialRead.searchParams.get('start'));assert(initialRead.searchParams.get('end'));assert(!initialRead.searchParams.has('groupId'));assert(!initialRead.searchParams.has('shopId'));assert(!initialRead.searchParams.has('platform'));
  labelGate.resolve(fixture('DATA-FIRST'));await pause();assert(!app.d.body.textContent.includes('DATA-FIRST'),'prefetched financial data waits for fresh navigation context before rendering');
  shopGate.resolve(shopsResponse);await waitFor(()=>app.complete('DATA-FIRST'));
  assert.equal(calls.filter(url=>url.pathname.endsWith('/label-costs')).length,1,calls.map(url=>url.pathname+url.search).join('\n'));assert.equal(app.d.querySelectorAll('.fl-content .fc-metric').length,4);assert.equal(app.d.querySelectorAll('.fq-section table').length,2);
  app.close();
}

{
  const labelGate=deferred(),calls=[];let labelReads=0;
  const app=boot(async(path,options={})=>{const url=new URL(path,'https://example.test');calls.push(url);
    if(url.pathname.endsWith('/shops'))return shopsResponse;
    if(url.pathname.endsWith('/label-costs')){labelReads++;return labelReads===1?labelGate.wait(options.signal):fixture('INACTIVE-RETRY');}
    throw Error('Unexpected mounted-leave request '+path);
  });
  const nativeTimeout=app.w.setTimeout.bind(app.w),nativeClear=app.w.clearTimeout.bind(app.w),retryTimers=new Map();let timerId=100000,retrySchedules=0;
  app.w.setTimeout=(fn,delay,...args)=>{if(delay!==10000)return nativeTimeout(fn,delay,...args);retrySchedules++;const id=++timerId;retryTimers.set(id,()=>fn(...args));return id;};
  app.w.clearTimeout=id=>{if(retryTimers.delete(id))return;nativeClear(id);};
  app.w.FinanceWorkspace.enter('costs');await waitFor(()=>app.button('订单成本')&&labelGate.signal);app.w.FinanceWorkspace.leave();
  await waitFor(()=>labelGate.signal.aborted);await pause();for(const [id,run]of [...retryTimers]){retryTimers.delete(id);run();}await pause();
  assert.equal(retrySchedules,0,'parent cancellation is terminal and must not schedule a detached ten-second retry');assert.equal(labelReads,1,'leaving after the child consumes a pending lease must not start a new labels GET');app.close();
}

{
  const labelGate=deferred(),calls=[];
  const app=boot(async(path,options={})=>{const url=new URL(path,'https://example.test');calls.push(url);
    if(url.pathname.endsWith('/shops'))return shopsResponse;
    if(url.pathname.endsWith('/label-costs'))return labelGate.wait(options.signal);
    throw Error('Unexpected shops-first request '+path);
  });
  app.w.FinanceWorkspace.enter('costs');await waitFor(()=>app.button('订单成本')&&labelGate.signal);
  assert.equal(app.d.querySelector('#finance-page').getAttribute('aria-busy'),'false');assert.equal(app.d.querySelector('.fl-content').getAttribute('aria-busy'),'true');
  labelGate.resolve(fixture('SHOPS-FIRST'));await waitFor(()=>app.complete('SHOPS-FIRST'));
  assert.equal(calls.filter(url=>url.pathname.endsWith('/label-costs')).length,1);app.close();
}

{
  const calls=[],unhandled=[];let labelAttempts=0;const onUnhandled=error=>unhandled.push(error);process.on('unhandledRejection',onUnhandled);
  const app=boot(async path=>{const url=new URL(path,'https://example.test');calls.push(url);
    if(url.pathname.endsWith('/shops'))return shopsResponse;
    if(url.pathname.endsWith('/label-costs')){labelAttempts++;if(labelAttempts===1)throw Error('预取读取失败');return fixture('ACTIVE-RETRY');}
    throw Error('Unexpected rejection request '+path);
  });
  const nativeTimeout=app.w.setTimeout.bind(app.w),nativeClear=app.w.clearTimeout.bind(app.w),retryTimers=new Map();let timerId=200000;
  app.w.setTimeout=(fn,delay,...args)=>{if(delay!==10000)return nativeTimeout(fn,delay,...args);const id=++timerId;retryTimers.set(id,()=>fn(...args));return id;};
  app.w.clearTimeout=id=>{if(retryTimers.delete(id))return;nativeClear(id);};
  try{
    app.w.FinanceWorkspace.enter('costs');await waitFor(()=>app.d.querySelector('.fc-tab-content .fc-feedback')?.textContent.includes('预取读取失败')&&retryTimers.size===1);assert.equal(labelAttempts,1,'a rejected lease surfaces once without a duplicate initial GET');
    for(const [id,run]of [...retryTimers]){retryTimers.delete(id);run();}await waitFor(()=>app.complete('ACTIVE-RETRY'));
    assert.equal(labelAttempts,2,'an active mounted labels view retains its ordinary timed retry');assert.deepEqual(unhandled,[]);
  }finally{process.removeListener('unhandledRejection',onUnhandled);app.close();}
}

{
  const shopGate=deferred(),labelGate=deferred(),calls=[];
  const app=boot(async(path,options={})=>{const url=new URL(path,'https://example.test');calls.push(url);
    if(url.pathname.endsWith('/shops'))return shopGate.wait(options.signal);
    if(url.pathname.endsWith('/label-costs'))return labelGate.wait(options.signal);
    throw Error('Unexpected leave request '+path);
  });
  app.w.FinanceWorkspace.enter('costs');await waitFor(()=>labelGate.signal);labelGate.resolve(fixture('UNMOUNTED'));await pause();
  app.w.FinanceWorkspace.leave();await pause();assert(labelGate.signal.aborted,'leaving the route cancels an unconsumed resolved lease');assert(shopGate.signal.aborted);
  app.close();
}

{
  const labelGate=deferred(),calls=[];
  const app=boot(async(path,options={})=>{const url=new URL(path,'https://example.test');calls.push(url);
    if(url.pathname.endsWith('/shops'))return shopsResponse;
    if(url.pathname.endsWith('/label-costs'))return labelGate.wait(options.signal);
    if(url.pathname.endsWith('/costs/orders'))return orderResponse;
    throw Error('Unexpected tab-dispose request '+path);
  });
  app.w.FinanceWorkspace.enter('costs');await waitFor(()=>app.button('订单成本')&&labelGate.signal);app.button('订单成本').click();
  await waitFor(()=>labelGate.signal.aborted&&calls.some(url=>url.pathname.endsWith('/costs/orders')));assert.equal(calls.filter(url=>url.pathname.endsWith('/label-costs')).length,1);app.close();
}

{
  const calls=[];let labelReads=0;
  const app=boot(async path=>{const url=new URL(path,'https://example.test');calls.push(url);
    if(url.pathname.endsWith('/shops'))return shopsResponse;
    if(url.pathname.endsWith('/label-costs'))return fixture('LABEL-'+(++labelReads));
    if(url.pathname.endsWith('/costs/orders'))return orderResponse;
    throw Error('Unexpected remount request '+path);
  });
  app.w.FinanceWorkspace.enter('costs');await waitFor(()=>app.complete('LABEL-1'));app.button('订单成本').click();await waitFor(()=>calls.some(url=>url.pathname.endsWith('/costs/orders')));
  app.button('月度贴单账单').click();await waitFor(()=>app.d.body.textContent.includes('LABEL-2'));
  assert.equal(labelReads,2,'returning to labels performs a fresh request instead of reusing the consumed lease');app.close();
}

{
  const calls=[];let shopGate=null,labelGate=null,labelReads=0;
  const app=boot(async(path,options={})=>{const url=new URL(path,'https://example.test');calls.push(url);
    if(url.pathname.endsWith('/shops'))return shopGate?shopGate.wait(options.signal):shopsResponse;
    if(url.pathname.endsWith('/label-costs')){
      labelReads++;if(labelGate&&!url.searchParams.has('groupId'))return labelGate.wait(options.signal);
      return fixture(url.searchParams.has('groupId')?'SCOPED':'INITIAL');
    }
    throw Error('Unexpected scoped request '+path);
  });
  app.w.FinanceWorkspace.enter('costs');await waitFor(()=>app.complete('INITIAL'));
  const group=app.d.querySelector('[aria-label="账单所属分组"]');group.value='group-a';group.dispatchEvent(new app.w.Event('change'));await waitFor(()=>app.d.body.textContent.includes('SCOPED'));
  const before=labelReads;shopGate=deferred();labelGate=deferred();app.button('刷新',app.d.querySelector('.fi-head')).click();await waitFor(()=>shopGate.signal);await pause();
  assert.equal(labelReads,before,'remembered scoped filters skip speculative default prefetch');shopGate.resolve(shopsResponse);
  await waitFor(()=>labelReads===before+1&&app.d.body.textContent.includes('SCOPED'));assert.equal(calls.filter(url=>url.pathname.endsWith('/label-costs')).at(-1).searchParams.get('groupId'),'group-a');app.close();
}

{
  const calls=[];let shopGate=null,staleGate=null,labelReads=0;
  const app=boot(async(path,options={})=>{const url=new URL(path,'https://example.test');calls.push(url);
    if(url.pathname.endsWith('/shops'))return shopGate?shopGate.wait(options.signal):shopsResponse;
    if(url.pathname.endsWith('/label-costs')){
      labelReads++;if(staleGate&&url.searchParams.get('start')===initialStart&&!url.searchParams.has('groupId'))return staleGate.wait(options.signal);
      return fixture(labelReads===1?'DATE-INITIAL':'DATE-CURRENT');
    }
    throw Error('Unexpected date mismatch request '+path);
  });
  let initialStart='';app.w.FinanceWorkspace.enter('costs');await waitFor(()=>app.complete('DATE-INITIAL'));initialStart=app.d.querySelector('[aria-label="结算开始日期"]').value;
  shopGate=deferred();staleGate=deferred();app.button('刷新',app.d.querySelector('.fi-head')).click();await waitFor(()=>staleGate.signal);
  const group=app.d.querySelector('[aria-label="账单所属分组"]');group.value='group-a';group.dispatchEvent(new app.w.Event('change'));
  const start=app.d.querySelector('[aria-label="结算开始日期"]'),nextDate=new Date(initialStart+'T00:00:00Z');nextDate.setUTCDate(nextDate.getUTCDate()+1);const changedStart=nextDate.toISOString().slice(0,10);
  start.value=changedStart;start.dispatchEvent(new app.w.Event('change'));await waitFor(()=>calls.some(url=>url.pathname.endsWith('/label-costs')&&url.searchParams.get('start')===changedStart));
  staleGate.resolve(fixture('STALE-PREFETCH'));shopGate.resolve(shopsResponse);await waitFor(()=>app.complete('DATE-CURRENT')&&calls.filter(url=>url.pathname.endsWith('/label-costs')&&url.searchParams.get('start')===changedStart).length===2);
  assert(staleGate.signal.aborted,'an exact-query mismatch cancels the stale lease even after its data resolved');assert(!app.d.body.textContent.includes('STALE-PREFETCH'));const lastRead=calls.filter(url=>url.pathname.endsWith('/label-costs')).at(-1);assert.equal(lastRead.searchParams.get('start'),changedStart);assert.equal(lastRead.searchParams.get('groupId'),'group-a');app.close();
}

console.log('Finance label prefetch: parallel completion orders, one-use freshness, rejection, cancellation, scoped skip and exact date matching passed.');
