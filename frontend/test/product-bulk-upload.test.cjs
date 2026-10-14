const {test}=require('node:test');
const assert=require('node:assert/strict');
const {plan,canChangeShop,changeShop,changeShops,submit,withDeadline,retryRead,isRetryableReadError}=require('../product-bulk-upload.js');
const fixtures=()=>plan([{id:'a',title:'A'},{id:'b',title:'B'},{id:'c',title:'C'}],'shop','batch-key');
const prepare=async item=>({confirmed:true,destinationPlatform:'SHOPEE',shopId:item.shopId,idempotencyKey:item.idempotencyKey,autoConvertPrice:true,publishStock:999});
const deps=(overrides={})=>({save:()=>{},prepare,isCurrent:()=>true,changed:()=>{},create:async id=>({id:'workflow-'+id,status:'PENDING'}),...overrides});
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};};
const turn=()=>new Promise(resolve=>setImmediate(resolve));
const manyFixtures=()=>plan(Array.from({length:8},(_,index)=>({id:String(index),title:'Product '+index})),'shop','batch-key');

test('shop-only plan needs no price, currency, weight, category or logistics input and deduplicates captures',()=>{
 const items=fixtures();assert.notEqual(items[0].idempotencyKey,items[1].idempotencyKey);
 assert.equal(items[0].shopId,'shop');assert.equal(items[0].payload,undefined);
 assert.equal(plan([{id:'a'},{id:'a'}],'shop','key').length,1);
 assert.throws(()=>plan([{id:'a'}],'','key'));
 assert.throws(()=>plan([],'shop','key'));
});
test('multi-shop plan creates each unique capture and shop pair with separate request keys',()=>{
 const items=plan([{id:'a',title:'A'},{id:'a'},{id:'b',title:'B'}],['one','two','one'],'batch');
 assert.deepEqual(items.map(item=>[item.captureId,item.shopId]),[['a','one'],['a','two'],['b','one'],['b','two']]);
 assert.equal(new Set(items.map(item=>item.idempotencyKey)).size,4);
 assert.throws(()=>plan([{id:'a'}],[],'batch'));
});
test('multi-shop selection retains sent requests and existing keys while adding only missing targets',()=>{
 const items=plan([{id:'a',title:'A'}],['one','two'],'batch');
 Object.assign(items[0],{status:'CREATE_FAILED',payload:{shopId:'one'},sizeChartImageKey:'chart'});
 const next=changeShops(items,['two','three'],'next');
 assert.equal(next[0],items[0]);assert.equal(next[1],items[1]);
 assert.equal(next[2].shopId,'three');assert.equal(next[2].sizeChartImageKey,'chart');
 assert.equal(changeShops(next,['two','three'],'unused'),next);
 const removed=changeShops(next,['three'],'last');
 assert.deepEqual(removed.map(item=>item.shopId),['one','three']);
 assert.equal(removed[0],items[0]);assert.throws(()=>changeShops(items,[],'key'));
});
test('same-capture shops wait for the predecessor while other products can submit independently',async()=>{
 const items=plan([{id:'a'},{id:'b'}],['one','two'],'batch'),gate=deferred(),created=[];
 const running=submit(items,deps({beforeNextShop:async(item,previous)=>{
   assert.equal(previous.workflow.id,'workflow-'+item.captureId+'-one');
   if(item.captureId==='a')await gate.promise;
 },create:async(id,payload)=>{created.push([id,payload.shopId]);return {id:'workflow-'+id+'-'+payload.shopId,status:'PENDING'};}}));
 await turn();assert.ok(created.some(([id])=>id==='b'));
 assert.ok(!created.some(([id,shop])=>id==='a'&&shop==='two'));
 gate.resolve();await running;assert.equal(created.length,4);
});
test('retry waits for already accepted same-capture workflow and never submits it again',async()=>{
 const items=plan([{id:'a'}],['one','two'],'batch'),calls=[];
 Object.assign(items[0],{workflow:{id:'accepted',status:'RUNNING'},status:'RUNNING'});
 await submit(items,deps({beforeNextShop:async(item,previous)=>calls.push(previous.workflow.id),
   create:async(id,payload)=>{calls.push(payload.shopId);return {id:'second',status:'PENDING'};}}));
 assert.deepEqual(calls,['accepted','two']);
});
test('shop changes are limited to unsent items without a saved request or accepted workflow',()=>{
 for(const status of ['WAITING','PREPARING','PREPARE_FAILED','PREPARED']) {
   assert.equal(canChangeShop({status}),true,status);
   assert.equal(canChangeShop({status,payload:{shopId:'original'}}),false,status+' with payload');
   assert.equal(canChangeShop({status,workflow:{id:'accepted'}}),false,status+' with workflow');
 }
 for(const status of ['SUBMITTING','CREATE_FAILED','PENDING','RUNNING','SUCCEEDED','FAILED','REVIEW_REQUIRED',undefined]) {
   assert.equal(canChangeShop({status}),false,String(status));
 }
});
test('changing a shop replaces only unsent items and keeps accepted and uncertain requests intact',()=>{
 const items=fixtures();
 Object.assign(items[0],{status:'PREPARE_FAILED',error:'category mismatch',retryCount:2,retryMessage:'old retry'});
 Object.assign(items[1],{status:'SUCCEEDED',payload:{shopId:'shop',idempotencyKey:items[1].idempotencyKey},workflow:{id:'accepted',status:'SUCCEEDED'}});
 Object.assign(items[2],{status:'CREATE_FAILED',payload:{shopId:'shop',idempotencyKey:items[2].idempotencyKey},error:'response lost'});
 const snapshot=JSON.stringify(items),next=changeShop(items,'new-shop','new-batch');
 assert.notEqual(next,items);assert.equal(JSON.stringify(items),snapshot);
 assert.deepEqual(next[0],{captureId:'a',title:'A',shopId:'new-shop',status:'WAITING',idempotencyKey:'new-batch-0'});
 assert.equal(next[1],items[1]);assert.equal(next[2],items[2]);
 assert.equal(changeShop(next,'new-shop','unused-key'),next,'Selecting the same shop must not rotate request keys');
 assert.equal(changeShop(items.slice(1),'new-shop','unused-key')[0],items[1]);
 assert.throws(()=>changeShop(items,'','key'));
 assert.throws(()=>changeShop(items,'new-shop',''));
});
test('lost response reuses exact prepared request and skips all accepted products',async()=>{
 const items=fixtures(),calls=[],prepares=[];let fail=true;
 const dependencies=deps({prepare:async item=>{prepares.push(item.captureId);return prepare(item);},create:async(id,payload,key)=>{calls.push([id,JSON.stringify(payload),key]);if(id==='b'&&fail)throw Error('lost response');return {id:'workflow-'+id,status:'PENDING'};}});
 await submit(items,dependencies);assert.equal(items[1].status,'CREATE_FAILED');assert.equal(items[2].status,'PENDING');
 fail=false;await submit(items,dependencies);
 assert.deepEqual(calls.map(x=>x[0]),['a','b','c','b']);assert.deepEqual(calls[1],calls[3]);
 assert.deepEqual(prepares,['a','b','c']);
});
test('preparation failure is isolated and retries only the unprepared product',async()=>{
 const items=fixtures(),calls=[];let fail=true;
 const dependencies=deps({prepare:async item=>{if(item.captureId==='b'&&fail)throw Error('category unavailable');return prepare(item);},create:async id=>{calls.push(id);return {id:'wf-'+id,status:'PENDING'};}});
 await submit(items,dependencies);assert.equal(items[1].status,'PREPARE_FAILED');assert.equal(items[1].payload,undefined);assert.deepEqual(calls,['a','c']);
 fail=false;await submit(items,dependencies);assert.deepEqual(calls,['a','c','b']);
});
test('persists immutable prepared request before external create and storage failure prevents create',async()=>{
 let calls=0;const snapshots=[];
 await assert.rejects(submit(fixtures(),deps({save:()=>{throw Error('storage full');},create:async()=>{calls++;}})));
 assert.equal(calls,0);
 await assert.rejects(submit(fixtures(),deps({save:items=>{if(items.some(item=>item.payload))throw Error('storage full');},create:async()=>{calls++;}})));
 assert.equal(calls,0);
 await submit(fixtures(),deps({save:items=>snapshots.push(JSON.parse(JSON.stringify(items))),create:async(id,payload)=>{assert.deepEqual(snapshots.at(-1).find(item=>item.captureId===id).payload,payload);return {id:'wf-'+id};}}));
});
test('organization switch during preparation or create prevents further submissions and writes',async()=>{
 let calls=0,current=true,saves=0;
 await submit(fixtures(),deps({concurrency:1,save:()=>{saves++;},isCurrent:()=>current,prepare:async item=>{current=false;return prepare(item);},create:async()=>{calls++;}}));
 assert.equal(calls,0);assert.equal(saves,2);
 current=true;saves=0;
 await submit(fixtures(),deps({concurrency:1,save:()=>{saves++;},isCurrent:()=>current,create:async()=>{calls++;current=false;return {id:'accepted'};}}));
 assert.equal(calls,1);assert.equal(saves,3);
});
test('existing persisted legacy payload is never rebuilt, even if defaults changed',async()=>{
 const items=fixtures().slice(0,1);items[0].payload={shopId:'shop',publishPrice:'19.90',publishStock:1,idempotencyKey:items[0].idempotencyKey};items[0].status='CREATE_FAILED';
 const expected=JSON.stringify(items[0].payload);
 await submit(items,deps({prepare:()=>{throw Error('must not prepare');},create:async(id,payload)=>{assert.equal(JSON.stringify(payload),expected);return {id:'recovered'};}}));
 assert.equal(items[0].workflow.id,'recovered');
});
test('authentication expiry stops subsequent items with a single worker',async()=>{
 const items=fixtures(),calls=[];
 await submit(items,deps({concurrency:1,create:async id=>{calls.push(id);throw Object.assign(Error('login expired'),{status:401});}}));
 assert.deepEqual(calls,['a']);
});

test('default three workers keep each preparation and create in one bounded slot',async()=>{
 const items=manyFixtures(),prepareGates=items.map(deferred),createGates=items.map(deferred),starts=[];
 let active=0,maxActive=0;
 const running=submit(items,deps({prepare:async item=>{
   active++;maxActive=Math.max(maxActive,active);starts.push(item.captureId);
   await prepareGates[Number(item.captureId)].promise;return prepare(item);
 },create:async id=>{
   await createGates[Number(id)].promise;active--;return {id:'wf-'+id,status:'PENDING'};
 }}));
 assert.deepEqual(starts,['0','1','2']);assert.equal(active,3);
 prepareGates[0].resolve();await turn();
 assert.deepEqual(starts,['0','1','2']);assert.equal(items[0].status,'SUBMITTING');
 createGates[0].resolve();await turn();assert.deepEqual(starts,['0','1','2','3']);
 for(const gate of prepareGates)gate.resolve();for(const gate of createGates)gate.resolve();
 await running;assert.equal(maxActive,3);assert.equal(active,0);assert.ok(items.every(item=>item.workflow?.id));
});

test('a hung prepare does not prevent other workers from submitting remaining products',async()=>{
 const items=manyFixtures(),gate=deferred(),created=[];
 const running=submit(items,deps({prepare:async item=>{if(item.captureId==='0')await gate.promise;return prepare(item);},create:async id=>{created.push(id);return {id:'wf-'+id,status:'PENDING'};}}));
 await turn();assert.deepEqual(created,['1','2','3','4','5','6','7']);assert.equal(items[0].status,'PREPARING');
 gate.resolve();await running;assert.equal(items[0].workflow.id,'wf-0');
});

test('a hung create does not prevent other workers from submitting remaining products',async()=>{
 const items=manyFixtures(),gate=deferred();
 const running=submit(items,deps({create:async id=>{if(id==='0')await gate.promise;return {id:'wf-'+id,status:'PENDING'};}}));
 await turn();assert.equal(items[0].status,'SUBMITTING');assert.ok(items.slice(1).every(item=>item.workflow?.id));
 gate.resolve();await running;assert.ok(items.every(item=>item.workflow?.id));
});

test('configurable concurrency and accepted workflows do not consume submission slots',async()=>{
 const items=manyFixtures(),gates=items.map(deferred),started=[];
 items[0].workflow={id:'accepted'};items[0].status='SUCCEEDED';
 const running=submit(items,deps({concurrency:2,prepare:async item=>{started.push(item.captureId);await gates[Number(item.captureId)].promise;return prepare(item);}}));
 assert.deepEqual(started,['1','2']);for(const gate of gates)gate.resolve();await running;
 assert.equal(items[0].workflow.id,'accepted');assert.equal(items[0].status,'SUCCEEDED');
});

test('401 stops new claims while already in-flight creates settle and persist receipts',async()=>{
 const items=manyFixtures(),gates=items.map(deferred),started=[];
 const running=submit(items,deps({create:async id=>{started.push(id);return gates[Number(id)].promise;}}));
 await turn();assert.deepEqual(started,['0','1','2']);
 gates[0].reject(Object.assign(Error('login expired'),{status:401}));await turn();
 gates[1].resolve({id:'wf-1',status:'PENDING'});gates[2].resolve({id:'wf-2',status:'PENDING'});
 await running;assert.deepEqual(started,['0','1','2']);assert.equal(items[0].status,'CREATE_FAILED');
 assert.equal(items[1].workflow.id,'wf-1');assert.equal(items[2].workflow.id,'wf-2');
 assert.ok(items.slice(3).every(item=>item.status==='WAITING'));
});

test('401 during preparation stops new claims and does not discard already accepted work',async()=>{
 const items=manyFixtures(),gate=deferred(),accepted=deferred(),started=[];
 const running=submit(items,deps({prepare:async item=>{
   started.push(item.captureId);if(item.captureId==='0')return gate.promise;return prepare(item);
 },create:async id=>{await accepted.promise;return {id:'wf-'+id,status:'PENDING'};}}));
 await turn();gate.reject(Object.assign(Error('login expired'),{status:401}));await turn();accepted.resolve();await running;
 assert.deepEqual(started,['0','1','2']);assert.equal(items[0].status,'PREPARE_FAILED');
 assert.ok(items.slice(1,3).every(item=>item.workflow?.id));assert.ok(items.slice(3).every(item=>item.status==='WAITING'));
});

test('storage failure stops new work and in-flight preparations cannot create unsaved requests',async()=>{
 const items=manyFixtures(),gates=items.map(deferred),created=[],started=[];let failStorage=false;
 const running=submit(items,deps({save:()=>{if(failStorage)throw Error('storage full');},prepare:async item=>{
   started.push(item.captureId);await gates[Number(item.captureId)].promise;return prepare(item);
 },create:async id=>{created.push(id);return {id:'wf-'+id};}}));
 const rejection=assert.rejects(running,/storage full/);
 failStorage=true;gates[0].resolve();await turn();gates[1].resolve();gates[2].resolve();await rejection;
 assert.deepEqual(started,['0','1','2']);assert.deepEqual(created,[]);assert.ok(items.slice(3).every(item=>item.status==='WAITING'));
});

test('storage failure still waits for an in-flight accepted create and preserves its receipt',async()=>{
 const items=manyFixtures(),gate=deferred(),started=[];let failed=false;
 const running=submit(items,deps({save:state=>{
   if(!failed&&state.some(item=>item.captureId==='1'&&item.status==='SUBMITTING')){failed=true;throw Error('storage full');}
 },create:async id=>{started.push(id);return gate.promise;}}));
 const rejection=assert.rejects(running,/storage full/);await turn();assert.deepEqual(started,['0']);
 gate.resolve({id:'accepted-0',status:'PENDING'});await rejection;assert.equal(items[0].workflow.id,'accepted-0');
 assert.ok(items.slice(3).every(item=>item.status==='WAITING'));
});

test('organization switch while concurrent requests settle prevents all stale mutations and saves',async()=>{
 const items=manyFixtures(),gates=items.map(deferred);let current=true,saves=0,changes=0;
 const running=submit(items,deps({isCurrent:()=>current,save:()=>{saves++;},changed:()=>{changes++;},create:async id=>gates[Number(id)].promise}));
 await turn();const snapshot=JSON.stringify(items),saved=saves,changed=changes;current=false;
 gates[0].resolve({id:'accepted-0'});gates[1].reject(Error('network error'));gates[2].resolve({id:'accepted-2'});
 await running;assert.equal(JSON.stringify(items),snapshot);assert.equal(saves,saved);assert.equal(changes,changed);
});

test('organization switch inside persistence prevents stale UI changes and create calls',async()=>{
 const items=fixtures();let current=true,created=0,changed=0;
 await submit(items,deps({isCurrent:()=>current,save:state=>{if(state.some(item=>item.payload))current=false;},changed:()=>{changed++;},create:async()=>{created++;}}));
 assert.equal(created,0);assert.equal(changed,3);
});

test('preparation cannot substitute a different shop or idempotency key',async()=>{
 for(const replace of [{shopId:'other-shop'},{idempotencyKey:'other-key'}]){
   const items=fixtures();let created=0;
   await submit(items,deps({prepare:async item=>({...await prepare(item),...replace}),create:async()=>{created++;}}));
   assert.equal(created,0);assert.ok(items.every(item=>item.status==='PREPARE_FAILED'&&!item.payload));
 }
});

test('deadline rejects a never-settling operation and aborts its signal',async()=>{
 let receivedSignal;
 await assert.rejects(withDeadline(signal=>{receivedSignal=signal;return new Promise(()=>{});},{timeoutMs:15}),error=>{
   assert.equal(error.name,'TimeoutError');assert.equal(error.status,0);assert.equal(error.message,'请求超时，请重试');return true;
 });
 assert.equal(receivedSignal.aborted,true);assert.equal(receivedSignal.reason.name,'TimeoutError');
});

test('deadline covers response body consumption after headers have already arrived',async()=>{
 let headersReceived=false,bodySignal;
 await assert.rejects(withDeadline(async signal=>{
   const response=await Promise.resolve({json:()=>new Promise(()=>{})});headersReceived=true;bodySignal=signal;
   return response.json();
 },{timeoutMs:15,timeoutMessage:'读取上传任务超时，请重试'}),{name:'TimeoutError',status:0,message:'读取上传任务超时，请重试'});
 assert.equal(headersReceived,true);assert.equal(bodySignal.aborted,true);
});

test('timed-out preparation and create release worker slots and retain the create retry body',async()=>{
 const items=manyFixtures(),sent=[];
 await submit(items,deps({concurrency:1,prepare:item=>withDeadline(()=>item.captureId==='0'?new Promise(()=>{}):prepare(item),{timeoutMs:15}),create:(id,payload,key)=>withDeadline(()=>{
   sent.push({id,payload,key});return id==='1'?new Promise(()=>{}):{id:'wf-'+id,status:'PENDING'};
 },{timeoutMs:15})}));
 assert.equal(items[0].status,'PREPARE_FAILED');assert.equal(items[1].status,'CREATE_FAILED');assert.ok(items.slice(2).every(item=>item.workflow?.id));
 const original=sent[0];assert.equal(items[1].payload,original.payload);assert.equal(items[1].idempotencyKey,original.key);
 await submit(items,deps({create:async(id,payload,key)=>{if(id==='1'){assert.equal(payload,original.payload);assert.equal(key,original.key);}return {id:'retried-'+id,status:'PENDING'};}}));
 assert.ok(items.every(item=>item.workflow?.id));
});

test('deadline respects cancellation before start and during a hung operation',async()=>{
 const prior=new AbortController(),reason=Object.assign(Error('已取消'),{name:'AbortError'});prior.abort(reason);let called=false;
 await assert.rejects(withDeadline(()=>{called=true;},{signal:prior.signal}),error=>error===reason);assert.equal(called,false);
 const controller=new AbortController();let receivedSignal;
 const pending=withDeadline(signal=>{receivedSignal=signal;return new Promise(()=>{});},{signal:controller.signal});controller.abort(reason);
 await assert.rejects(pending,error=>error===reason);assert.equal(receivedSignal.aborted,true);assert.equal(receivedSignal.reason,reason);
});

test('deadline cleans up listeners and timers after success, rejection, timeout and cancellation',async()=>{
 const {getEventListeners}=require('node:events');
 for(const outcome of ['success','rejection','timeout','cancellation']){
   const controller=new AbortController();let innerSignal;
   const pending=withDeadline(signal=>{
     innerSignal=signal;if(outcome==='success')return 42;if(outcome==='rejection')throw Error('failed');return new Promise(()=>{});
   },{timeoutMs:15,signal:controller.signal});
   if(outcome==='cancellation')controller.abort();
   if(outcome==='success')assert.equal(await pending,42);else await assert.rejects(pending);
   assert.equal(getEventListeners(controller.signal,'abort').length,0);
   if(outcome==='success'||outcome==='rejection'){
     await new Promise(resolve=>setTimeout(resolve,25));assert.equal(innerSignal.aborted,false);
   }
 }
});

test('read retry succeeds after temporary failures with three total attempts and 2s then 5s delays',async()=>{
 const attempts=[],delays=[],progress=[];
 const result=await retryRead(async attempt=>{
   attempts.push(attempt);if(attempt<3)throw Object.assign(Error('network unavailable'),{name:'ApiRequestError',status:0});return {id:'ready'};
 },{sleep:async delay=>delays.push(delay),onRetry:info=>progress.push(info)});
 assert.deepEqual(result,{id:'ready'});assert.deepEqual(attempts,[1,2,3]);assert.deepEqual(delays,[2000,5000]);
 assert.deepEqual(progress,[{attempt:2,maxAttempts:3,delayMs:2000},{attempt:3,maxAttempts:3,delayMs:5000}]);
});

test('read retry stops after the configured total attempts and preserves the last error',async()=>{
 const failure=Object.assign(Error('gateway unavailable'),{status:503});let count=0,delays=[];
 await assert.rejects(retryRead(async()=>{count++;throw failure;},{maxAttempts:2,sleep:async delay=>delays.push(delay)}),error=>error===failure);
 assert.equal(count,2);assert.deepEqual(delays,[2000]);
 count=0;await assert.rejects(retryRead(async()=>{count++;throw failure;},{sleep:async()=>{}}),error=>error===failure);assert.equal(count,3);
});

test('read retry classifies only transport timeouts and the approved transient status codes',()=>{
 for(const status of [0,408,429,502,503,504])assert.equal(isRetryableReadError(Object.assign(Error('temporary'),{status})),true,String(status));
 assert.equal(isRetryableReadError(Object.assign(Error('timed out'),{name:'TimeoutError'})),true);
 for(const status of [400,401,403,404,409,422,500,501,505])assert.equal(isRetryableReadError(Object.assign(Error('rejected'),{status})),false,String(status));
 assert.equal(isRetryableReadError(Object.assign(Error('cancelled'),{name:'AbortError',status:0})),false);
 assert.equal(isRetryableReadError(Object.assign(Error('auth failed'),{name:'TimeoutError',status:401})),false);
 assert.equal(isRetryableReadError(Error('invalid product')),false);assert.equal(isRetryableReadError(null),false);
});

test('read retry does not delay or retry authentication, business failures, aborts or HTTP 500',async()=>{
 for(const failure of [Object.assign(Error('cancelled'),{name:'AbortError',status:0}),...[401,403,409,422,500].map(status=>Object.assign(Error('not retryable'),{status}))]){
   let count=0,delays=0,progress=0;
   await assert.rejects(retryRead(async()=>{count++;throw failure;},{sleep:async()=>{delays++;},onRetry:()=>{progress++;}}),error=>error===failure);
   assert.equal(count,1);assert.equal(delays,0);assert.equal(progress,0);
 }
});

test('read retry checks organization before calls, after errors and after delays',async()=>{
 let count=0;
 await assert.rejects(retryRead(async()=>{count++;},{isCurrent:()=>false}),{name:'AbortError',status:0});assert.equal(count,0);
 let current=true,progress=0;
 await assert.rejects(retryRead(async()=>{count++;current=false;throw Object.assign(Error('timeout'),{status:0});},{isCurrent:()=>current,onRetry:()=>{progress++;}}),{name:'AbortError',status:0});
 assert.equal(count,1);assert.equal(progress,0);
 current=true;count=0;
 await assert.rejects(retryRead(async()=>{count++;throw Object.assign(Error('timeout'),{status:0});},{isCurrent:()=>current,sleep:async()=>{current=false;}}),{name:'AbortError',status:0});assert.equal(count,1);
});

test('read retry rejects a stale successful response and stops if retry UI changes organization',async()=>{
 let current=true;
 await assert.rejects(retryRead(async()=>{current=false;return {id:'stale'};},{isCurrent:()=>current}),{name:'AbortError',status:0});
 current=true;let sleeps=0;
 await assert.rejects(retryRead(async()=>{throw Object.assign(Error('timeout'),{status:0});},{isCurrent:()=>current,onRetry:()=>{current=false;},sleep:async()=>{sleeps++;}}),{name:'AbortError',status:0});assert.equal(sleeps,0);
});

test('read retry external cancellation interrupts the default wait and removes its listener',async()=>{
 const {getEventListeners}=require('node:events'),controller=new AbortController();let count=0;
 const running=retryRead(async()=>{count++;throw Object.assign(Error('network unavailable'),{status:0});},{signal:controller.signal});
 await turn();assert.equal(getEventListeners(controller.signal,'abort').length,1);controller.abort();
 await assert.rejects(running,{name:'AbortError',status:0});assert.equal(count,1);assert.equal(getEventListeners(controller.signal,'abort').length,0);
 let called=false;await assert.rejects(retryRead(async()=>{called=true;},{signal:controller.signal}),{name:'AbortError',status:0});assert.equal(called,false);
});

test('read retry uses a fresh deadline for each attempt after a stalled response body',async()=>{
 let count=0;const signals=[];
 const result=await retryRead(()=>withDeadline(async signal=>{
   signals.push(signal);count++;if(count===1)return new Promise(()=>{});return {id:'recovered'};
 },{timeoutMs:10}),{sleep:async()=>{}});
 assert.equal(result.id,'recovered');assert.equal(count,2);assert.equal(signals[0].aborted,true);assert.equal(signals[1].aborted,false);
});
