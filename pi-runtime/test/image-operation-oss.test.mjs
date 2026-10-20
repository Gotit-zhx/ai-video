import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,readdir,rm,utimes,writeFile,symlink} from 'node:fs/promises';
import {createHash,createHmac,randomBytes} from 'node:crypto';
import os from 'node:os';
import path from 'node:path';
import {ImageOperationStore} from '../src/image-operation-store.js';
import {ImageOperationOss,createImageOperationOss} from '../src/image-operation-oss.js';

const hash = data => createHash('sha256').update(data).digest('hex');
const config = {baseUrl:'https://model.invalid/v1',modelId:'banana'};
const requestId = 'oss-migration-request-001';
const bytes = Buffer.from('original generated image'.repeat(100));
const result = {artifact:{dataBase64:bytes.toString('base64'),mimeType:'image/png'},usage:{images:1}};

async function fixture(t) {
  const dir = await mkdtemp(path.join(os.tmpdir(),'operation-oss-'));
  t.after(()=>rm(dir,{recursive:true,force:true}));
  const objects = new Map();
  const client = {
    async put(key,data,options) {
      assert.equal(options.headers['x-oss-object-acl'],'private');
      objects.set(key,Buffer.from(data));
    },
    async get(key) {
      if (!objects.has(key)) throw new Error('Missing remote object');
      return {content:objects.get(key)};
    }
  };
  const archive = new ImageOperationOss(client,'test-hoyee');
  const store = new ImageOperationStore(dir,{archive,minimumArchiveBytes:128});
  const file = path.join(dir,hash(requestId)+'.json');
  await store.execute(requestId,{sourceHash:'original'},async op=>{
    const route = await op.forRoute(config);
    await route.beforeAttempt();
    await route.recordResponse({payload:{data:[{b64_json:result.artifact.dataBase64}]}});
    return result;
  });
  await utimes(file,1,1);
  return {dir,file,store,archive,objects,client};
}

test('verified OSS migration preserves complete recovery and paid counters across restart',async t=>{
  const {dir,file,store,archive} = await fixture(t);
  const before = JSON.parse(await readFile(file,'utf8'));
  assert.equal(await store.migrateCompleted(),1);
  const index = JSON.parse(await readFile(file,'utf8'));
  assert.equal(index.status,'COMPLETED');
  assert.equal(index.fingerprint,before.fingerprint);
  assert.equal(index.result,undefined);
  assert.equal(Object.values(index.routes)[0].response,undefined);
  assert.equal(Object.values(index.routes)[0].attempts,1);
  assert.ok(index.externalRecord.key.startsWith('heyi/image-operations/'+hash(requestId)));
  const reader = new ImageOperationStore(dir,{archive});
  assert.deepEqual((await reader.lookup(requestId)).response,result);
  assert.deepEqual(await reader.execute(requestId,{sourceHash:'original'},()=>assert.fail('paid resend')),result);
  assert.equal(await store.migrateCompleted(),0);
});

test('failed readback leaves original local result unchanged',async t=>{
  const {file,store,client} = await fixture(t);
  const before = await readFile(file);
  client.get = async()=>({content:Buffer.from('damaged')});
  await assert.rejects(store.migrateCompleted(),/readback mismatch/);
  assert.deepEqual(await readFile(file),before);
  assert.equal(store.archiving,false);
});

test('OSS outage never turns an existing task into not-found or another paid request',async t=>{
  const {dir,file,store,archive,client} = await fixture(t);
  await store.migrateCompleted();
  const before = await readFile(file);
  client.get = async()=>{throw Object.assign(Error('outage'),{code:'ENOENT'});};
  const reader = new ImageOperationStore(dir,{archive});
  await assert.rejects(reader.lookup(requestId),{failureCode:'IMAGE_RESULT_STORAGE_UNAVAILABLE'});
  await assert.rejects(reader.execute(requestId,{sourceHash:'original'},()=>assert.fail('paid resend')),
    {failureCode:'IMAGE_RESULT_STORAGE_UNAVAILABLE'});
  await assert.rejects(new ImageOperationStore(dir).lookup(requestId),
    {failureCode:'IMAGE_RESULT_STORAGE_UNAVAILABLE'});
  assert.deepEqual(await readFile(file),before);
});

test('content changes during upload are not overwritten by an archive index',async t=>{
  const {file,store,archive} = await fixture(t);
  const original = archive.store.bind(archive);
  archive.store = async(...args)=>{
    const pointer = await original(...args);
    const newer = JSON.parse(await readFile(file,'utf8'));
    newer.status = 'ARCHIVED'; delete newer.result;
    await store.write(file,newer);
    return pointer;
  };
  assert.equal(await store.migrateCompleted(),0);
  assert.equal(JSON.parse(await readFile(file,'utf8')).status,'ARCHIVED');
});

test('recoverable archived responses are hydrated without another model POST',async t=>{
  const {dir,file,store,archive} = await fixture(t);
  const record = JSON.parse(await readFile(file,'utf8'));
  record.status = 'RECOVERABLE'; delete record.result;
  record.error = {message:'download failed',requestState:'COMPLETED_RESPONSE_RECEIVED'};
  await writeFile(file,JSON.stringify(record)); await utimes(file,1,1);
  await store.migrateCompleted();
  const reader = new ImageOperationStore(dir,{archive});
  assert.equal((await reader.lookup(requestId)).error.savedResponseAvailable,true);
  const recovered = await reader.execute(requestId,{sourceHash:'original'},async op=>{
    const route = await op.forRoute(config);
    assert.equal(route.beforeAttempt,undefined);
    return route.cachedResponse;
  });
  assert.equal(recovered.payload.data[0].b64_json,result.artifact.dataBase64);
});

test('business acknowledgement still validates image content after offload',async t=>{
  const {file,store} = await fixture(t);
  await store.migrateCompleted();
  const key = 'a'.repeat(64)+'/'+hash(bytes);
  await assert.rejects(store.archive(requestId,'a'.repeat(64)+'/'+'b'.repeat(64)),/不一致/);
  assert.deepEqual(await store.archive(requestId,key),{status:'ARCHIVED'});
  const saved = JSON.parse(await readFile(file,'utf8'));
  assert.equal(saved.externalRecord,undefined);
  assert.equal(saved.status,'ARCHIVED');
  assert.equal(Object.values(saved.routes)[0].attempts,1);
});

test('active requests and filesystem symlinks are never migrated',async t=>{
  const {dir,file,store,objects} = await fixture(t);
  store.active.set(requestId,{});
  assert.equal(await store.migrateCompleted(),0);
  store.active.clear();
  await symlink(file,path.join(dir,'b'.repeat(64)+'.json'));
  assert.equal(await store.migrateCompleted(),1);
  assert.equal(objects.size,1);
});

test('remote object references are scoped to the operation and bucket',async t=>{
  const {file,store,archive} = await fixture(t);
  await store.migrateCompleted();
  const pointer = JSON.parse(await readFile(file,'utf8')).externalRecord;
  await assert.rejects(archive.read('a'.repeat(64),pointer),/Invalid/);
  await assert.rejects(archive.read(hash(requestId),{...pointer,bucket:'other'}),/Invalid/);
  await assert.rejects(archive.read(hash(requestId),{...pointer,size:200*1024*1024}),/Invalid/);
});

test('remote corruption fails closed and leaves the paid ledger intact',async t=>{
  const {file,store,objects} = await fixture(t);
  await store.migrateCompleted();
  const before = await readFile(file);
  const pointer = JSON.parse(before).externalRecord;
  objects.set(pointer.key,Buffer.from('corrupt'));
  await assert.rejects(store.lookup(requestId),{failureCode:'IMAGE_RESULT_STORAGE_UNAVAILABLE'});
  assert.deepEqual(await readFile(file),before);
});

test('small inline records keep existing behavior without OSS writes',async t=>{
  const {dir,archive,objects} = await fixture(t);
  const store = new ImageOperationStore(dir,{archive,minimumArchiveBytes:1024*1024});
  assert.equal(await store.migrateCompleted(),0);
  assert.equal(objects.size,0);
  assert.equal((await readdir(dir)).length,1);
});

test('large compressed results use bounded verified parts and remain byte-exact',async()=>{
  const content = randomBytes(9*1024*1024), parts = [], objects = new Map();
  const client = {
    async initMultipartUpload(_key,options) {
      assert.equal(options.headers['x-oss-object-acl'],'private'); return {uploadId:'upload'};
    },
    async uploadPart(_key,_id,number,buffer,start,end,options) {
      const part = buffer.subarray(start,end), md5 = createHash('md5').update(part).digest();
      assert.ok(part.length<=8*1024*1024);
      if (number===1) assert.equal(part.length,8*1024*1024);
      assert.equal(options.headers['Content-MD5'],md5.toString('base64'));
      parts[number-1] = part; return {etag:'"'+md5.toString('hex')+'"'};
    },
    async completeMultipartUpload(key,_id,records,options) {
      assert.equal(records.length,2);
      assert.ok(records.every(part=>/^[a-f0-9]{32}$/.test(part.etag)));
      assert.equal(options.headers['x-oss-object-acl'],'private');
      objects.set(key,Buffer.concat(parts));
    },
    async get(key) {return {content:objects.get(key)};},
    async abortMultipartUpload() {assert.fail('must not abort successful upload');}
  };
  const archive = new ImageOperationOss(client,'test-hoyee');
  const pointer = await archive.store('a'.repeat(64),content);
  assert.deepEqual(await archive.read('a'.repeat(64),pointer),content);
});

test('failed multipart upload is aborted before an index can be committed',async()=>{
  let aborted = false;
  const archive = new ImageOperationOss({
    async initMultipartUpload() {return {uploadId:'upload'};},
    async uploadPart() {throw Error('upload interrupted');},
    async abortMultipartUpload(_key,id) {assert.equal(id,'upload');aborted=true;}
  },'test-hoyee');
  await assert.rejects(archive.store('a'.repeat(64),randomBytes(9*1024*1024)),/interrupted/);
  assert.equal(aborted,true);
});

test('background maintenance keeps offloading newly completed tasks after the historical pass',async t=>{
  const {store,file} = await fixture(t);
  t.mock.timers.enable({apis:['setTimeout']});
  t.after(()=>store.close());
  let passes = 0, completed;
  let next = new Promise(resolve=>{completed=resolve;});
  const migrate = store.migrateCompleted.bind(store);
  store.migrateCompleted = async()=>{
    const count = await migrate();
    passes++;
    completed();
    return count;
  };
  await store.initialize();
  t.mock.timers.tick(120_000);
  await next;
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(passes,1);
  assert.ok(JSON.parse(await readFile(file,'utf8')).externalRecord);
  const id = 'oss-newly-completed-task';
  await store.execute(id,{new:true},async()=>result);
  const newFile = path.join(store.directory,hash(id)+'.json');
  await utimes(newFile,1,1);
  next = new Promise(resolve=>{completed=resolve;});
  t.mock.timers.tick(60_000);
  await next;
  await new Promise(resolve=>setImmediate(resolve));
  assert.equal(passes,2);
  assert.ok(JSON.parse(await readFile(newFile,'utf8')).externalRecord);
});

test('Dayan V1 requests sign Date and canonical multipart subresources with the official SDK',async t=>{
  const dir = await mkdtemp(path.join(os.tmpdir(),'dayan-signing-'));
  t.after(()=>rm(dir,{recursive:true,force:true}));
  const file = path.join(dir,'credentials');
  await writeFile(file,'accessKeyId=test-id\naccessKeySecret=test-secret\n');
  const archive = await createImageOperationOss({
    IMAGE_OPERATION_OSS_BUCKET:'test-hoyee',IMAGE_OPERATION_OSS_CREDENTIALS_FILE:file,
    IMAGE_OPERATION_OSS_SDK_MODULE:process.env.IMAGE_OPERATION_OSS_SDK_MODULE || 'ali-oss'
  });
  const date = 'Tue, 20 Oct 2026 00:00:00 GMT';
  for (const [subres,query] of [['uploads','uploads'],[{partNumber:1,uploadId:'test'},'partNumber=1&uploadId=test']]) {
    const headers = {'x-oss-date':date,'x-oss-object-acl':'private','Content-Type':'application/gzip'};
    const signature = archive.client.authorization('POST','/test-hoyee/test',subres,headers);
    const canonical = `POST\n\napplication/gzip\n${date}\nx-oss-object-acl:private\n/test-hoyee/test?${query}`;
    assert.equal(signature,'OSS test-id:'+createHmac('sha1','test-secret').update(canonical).digest('base64'));
    assert.equal(headers.Date,date);
    assert.equal(headers['x-oss-date'],undefined);
  }
  assert.equal(archive.client.options.sldEnable,true);
});
