import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,readdir,rm,writeFile} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {ImageOperationStore} from '../src/image-operation-store.js';
const config={baseUrl:'https://model.invalid/v1',modelId:'image'};
test('OSS acknowledgement removes image payloads but keeps paid identity across restart',async t=>{
 const {dir,store}=await fixture(t),bytes=Buffer.from('generated-image'),encoded=bytes.toString('base64');
 const key='a'.repeat(64)+'/'+createHash('sha256').update(bytes).digest('hex');
 await store.execute('archived-image-001',{prompt:'fixed'},async op=>{
  const route=await op.forRoute(config);await route.beforeAttempt();await route.recordResponse({payload:{data:[{b64_json:encoded}]}});
  return {artifact:{dataBase64:encoded}};
 });
 assert.deepEqual(await store.archive('archived-image-001',key),{status:'ARCHIVED'});
 const file=path.join(dir,(await readdir(dir))[0]),raw=await readFile(file,'utf8'),record=JSON.parse(raw);
 assert.ok(!raw.includes(encoded));assert.ok(record.fingerprint);assert.equal(Object.values(record.routes)[0].attempts,1);
 assert.equal((await store.lookup('archived-image-001')).status,'ARCHIVED');
 const restarted=new ImageOperationStore(dir);
 assert.deepEqual(await restarted.archive('archived-image-001',key),{status:'ARCHIVED'});
 await assert.rejects(restarted.execute('archived-image-001',{prompt:'fixed'},()=>{assert.fail('must not regenerate');}),
  {failureCode:'IMAGE_RESULT_ARCHIVED'});
 await assert.rejects(restarted.archive('archived-image-001','a'.repeat(64)+'/'+'b'.repeat(64)),/编号已改变/);
});
test('archive refuses live, failed, invalid and mismatched operations without losing images',async t=>{
 const {store}=await fixture(t),key='a'.repeat(64)+'/'+'b'.repeat(64);
 let release;const gate=new Promise(r=>release=r);
 const live=store.execute('active-image-001',{},async()=>{await gate;return {artifact:{dataBase64:'aW1hZ2U='}};});
 await assert.rejects(store.archive('active-image-001',key),/仍在运行/);release();await live;
 await assert.rejects(store.archive('active-image-001',key),/不一致/);
 assert.equal((await store.lookup('active-image-001')).status,'COMPLETED');
 await assert.rejects(store.execute('failed-image-001',{},async()=>{throw Error('failed');}));
 await assert.rejects(store.archive('failed-image-001',key),/只能清理已完成/);
 await assert.rejects(store.archive('../secret',key));
 assert.deepEqual(await store.archive('missing-image-001',key),{status:'NOT_FOUND'});
});
async function fixture(t){const dir=await mkdtemp(path.join(os.tmpdir(),'image-store-'));t.after(()=>rm(dir,{recursive:true,force:true}));return {dir,store:new ImageOperationStore(dir)};}
test('duplicates join one operation and completed results survive restart',async t=>{
 const {dir,store}=await fixture(t);let calls=0,release;const gate=new Promise(r=>release=r);
 const run=async operation=>{calls++;const route=await operation.forRoute(config);await route.beforeAttempt();await gate;await route.recordResponse({payload:{data:[{url:'https://result.invalid/image'}]}});return {result:{url:'https://result.invalid/image'}};};
 const a=store.execute('request-001',{sourceHash:'a'},run),b=store.execute('request-001',{sourceHash:'a'},run);release();
 assert.deepEqual(await a,await b);assert.equal(calls,1);
 const result=await new ImageOperationStore(dir).execute('request-001',{sourceHash:'a'},()=>{throw Error('must not resend');});assert.equal(result.result.url,'https://result.invalid/image');
 await assert.rejects(store.execute('request-001',{sourceHash:'b'},run),/输入已改变/);
});
test('disk record precedes model POST and stable key/attempt metadata exclude source and credentials',async t=>{
 const {dir,store}=await fixture(t);let key;
 await store.execute('request-002',{apiKey:'private-key',source:'image-bytes'},async op=>{
  const route=await op.forRoute(config);key=route.idempotencyKey;await route.beforeAttempt();
  const text=await readFile(path.join(dir,(await readdir(dir))[0]),'utf8');const record=JSON.parse(text);
  assert.equal(Object.values(record.routes)[0].attempts,1);assert.equal(Object.values(record.routes)[0].pending,true);
  assert.ok(!text.includes('private-key'));assert.ok(!text.includes('image-bytes'));return {result:{url:'result'}};
 });assert.match(key,/^[a-f0-9]{64}$/);
});
test('failed transport operation stays terminal across restarts',async t=>{
 const {dir,store}=await fixture(t);let calls=0;
 const run=async op=>{calls++;const route=await op.forRoute(config);await route.beforeAttempt();await route.recordNetworkRetry(2);throw Object.assign(Error('socket lost'),{requestState:'ACCEPTED_OR_UNKNOWN',retrySafe:false,networkRetryCount:2});};
 await assert.rejects(store.execute('request-003',{},run),/socket lost/);
 await assert.rejects(new ImageOperationStore(dir).execute('request-003',{},run),/socket lost/);assert.equal(calls,1);
});
test('received response is reused after downstream decode/download failure',async t=>{
 const {dir,store}=await fixture(t);let posts=0;
 await assert.rejects(store.execute('request-004',{},async op=>{
  const route=await op.forRoute(config);await route.beforeAttempt();posts++;await route.recordResponse({payload:{data:[{url:'saved'}]}});
  throw Object.assign(Error('download failed'),{requestState:'COMPLETED_RESPONSE_RECEIVED'});
 }),/download failed/);
 const value=await new ImageOperationStore(dir).execute('request-004',{},async op=>{const route=await op.forRoute(config);assert.equal(route.beforeAttempt,undefined);return route.cachedResponse;});
 assert.equal(posts,1);assert.equal(value.payload.data[0].url,'saved');
});
test('restart after an interrupted POST consumes a finite recovery budget',async t=>{
 const {dir,store}=await fixture(t);let recordPath;
 await store.execute('request-005',{},async op=>{const route=await op.forRoute(config);await route.beforeAttempt();return {};});
 recordPath=path.join(dir,(await readdir(dir))[0]);let record=JSON.parse(await readFile(recordPath,'utf8'));
 record.status='RUNNING';delete record.result;const route=Object.values(record.routes)[0];route.pending=true;route.networkRetryCount=1;await store.write(recordPath,record);
 await new ImageOperationStore(dir).execute('request-005',{},async op=>{const options=await op.forRoute(config);assert.equal(options.networkRetryCount,2);await options.beforeAttempt();return {};});
 record=JSON.parse(await readFile(recordPath,'utf8'));record.status='RUNNING';Object.values(record.routes)[0].pending=true;await store.write(recordPath,record);
 await assert.rejects(new ImageOperationStore(dir).execute('request-005',{},op=>op.forRoute(config)),/次数已用完/);
});

test('startup verifies ledger writes and fails before accepting work on invalid storage',async t=>{
 const {dir}=await fixture(t);
 const store=new ImageOperationStore(path.join(dir,'nested'));
 await store.initialize();
 await writeFile(path.join(dir,'not-a-directory'),'occupied');
 await assert.rejects(new ImageOperationStore(path.join(dir,'not-a-directory','ledger')).initialize());
});

test('lookup reports missing IDs without writing or starting an operation',async t=>{
 const {dir,store}=await fixture(t);
 assert.deepEqual(await store.lookup('missing-operation'),{status:'NOT_FOUND',active:false,updatedAt:0,response:null,error:null});
 for(const id of ['',null,'../private-file','short'])await assert.rejects(store.lookup(id),error=>error.status===400);
 assert.deepEqual(await readdir(dir),[]);
});

test('lookup hides an active provider response then returns the durable completed result after restart',async t=>{
 const {dir,store}=await fixture(t);let entered,release;
 const ready=new Promise(resolve=>entered=resolve),gate=new Promise(resolve=>release=resolve);
 const expected={result:{url:'https://result.invalid/grid.png',mimeType:'image/png'}};
 const pending=store.execute('detail-grid-lookup',{apiKey:'private-model-key',source:'original-image'},async op=>{
  const route=await op.forRoute(config);await route.beforeAttempt();
  await route.recordResponse({payload:{rawProviderValue:'provider-only',data:[{url:'raw-provider-url'}]}});
  entered();await gate;return expected;
 });
 await ready;
 try{
  const live=await store.lookup('detail-grid-lookup');
  assert.equal(live.status,'RUNNING');assert.equal(live.active,true);assert.equal(live.response,null);assert.equal(live.error,null);assert.ok(live.updatedAt>0);
  assert.deepEqual(Object.keys(live).sort(),['active','error','response','status','updatedAt']);
 }finally{release();}
 await pending;
 const restarted=new ImageOperationStore(dir),result=await restarted.lookup('detail-grid-lookup');
 assert.equal(result.status,'COMPLETED');assert.equal(result.active,false);assert.deepEqual(result.response,expected);assert.equal(result.error,null);
 const exposed=JSON.stringify(result);
 for(const secret of ['routes','fingerprint','original-image','private-model-key','provider-only','raw-provider-url'])assert.ok(!exposed.includes(secret),secret);
});

test('lookup returns only allowlisted scalar failure metadata and never retries terminal failures',async t=>{
 const {dir,store}=await fixture(t);let calls=0;
 await assert.rejects(store.execute('failed-grid-lookup',{},async op=>{
  calls++;const route=await op.forRoute(config);await route.beforeAttempt();
  throw Object.assign(Error('provider message with private-key'),{requestState:'ACCEPTED_OR_UNKNOWN',failureKind:'NETWORK',failureCode:'SOCKET_LOST',
   retrySafe:false,retryAuthorized:true,networkRetryCount:2,safeRetryCount:2,requestId:'stable-provider-id',apiKey:'private-key',
   imageModelFallback:{apiKey:'nested-private-key'},providerStatus:503});
 }),/private-key/);
 const reader=new ImageOperationStore(dir);
 const result=await reader.lookup('failed-grid-lookup');
 assert.equal(result.status,'FAILED');assert.equal(result.active,false);assert.equal(result.response,null);
 assert.deepEqual(result.error,{requestState:'ACCEPTED_OR_UNKNOWN',retrySafe:false,retryAuthorized:true,safeRetryCount:2,networkRetryCount:2,
  failureCode:'SOCKET_LOST',failureKind:'NETWORK',providerStatus:503,requestId:'stable-provider-id'});
 await reader.lookup('failed-grid-lookup');assert.equal(calls,1);
 assert.ok(!JSON.stringify(result).includes('private-key'));assert.ok(!JSON.stringify(result).includes('message'));
});

test('lookup marks interrupted and recoverable operations without exposing raw routes or resubmitting',async t=>{
 const {dir,store}=await fixture(t);
 await assert.rejects(store.execute('recoverable-lookup',{},async op=>{
  const route=await op.forRoute(config);await route.beforeAttempt();await route.recordResponse({payload:{data:[{url:'raw-only'}]}});
  throw Object.assign(Error('decode failed'),{requestState:'COMPLETED_RESPONSE_RECEIVED'});
 }),/decode failed/);
 const recoverable=await new ImageOperationStore(dir).lookup('recoverable-lookup');
 assert.equal(recoverable.status,'RECOVERABLE');assert.equal(recoverable.active,false);assert.equal(recoverable.response,null);
 assert.equal(recoverable.error.requestState,'COMPLETED_RESPONSE_RECEIVED');assert.equal(recoverable.error.savedResponseAvailable,true);
 assert.ok(!JSON.stringify(recoverable).includes('raw-only'));
 const file=path.join(dir,(await readdir(dir))[0]),record=JSON.parse(await readFile(file,'utf8'));
 record.status='RUNNING';await store.write(file,record);
 const interrupted=await new ImageOperationStore(dir).lookup('recoverable-lookup');
 assert.equal(interrupted.status,'RUNNING');assert.equal(interrupted.active,false);assert.equal(interrupted.response,null);assert.equal(interrupted.error,null);
});

test('recoverable lookup derives saved-response evidence only from durable route responses',async t=>{
 const {dir,store}=await fixture(t);
 await assert.rejects(store.execute('no-saved-response',{},async()=>{
  throw Object.assign(Error('processing failed'),{requestState:'COMPLETED_RESPONSE_RECEIVED',savedResponseAvailable:true});
 }));
 const file=path.join(dir,(await readdir(dir))[0]),record=JSON.parse(await readFile(file,'utf8'));
 record.error.savedResponseAvailable=true;record.result={artifact:{dataBase64:'not-route-evidence'}};
 record.routes={missing:{},empty:{response:null}};await store.write(file,record);
 const before=await readFile(file,'utf8');
 const result=await new ImageOperationStore(dir).lookup('no-saved-response');
 assert.equal(result.status,'RECOVERABLE');assert.equal(result.response,null);
 assert.equal(result.error.savedResponseAvailable,false);
 assert.equal(await readFile(file,'utf8'),before,'lookup must not mutate the durable operation');
 assert.ok(!JSON.stringify(result).includes('not-route-evidence'));
});
