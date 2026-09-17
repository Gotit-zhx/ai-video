import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,readdir,rm} from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import {ImageOperationStore} from '../src/image-operation-store.js';
const config={baseUrl:'https://model.invalid/v1',modelId:'image'};
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
