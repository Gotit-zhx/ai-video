import {readFileSync} from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
const source=readFileSync(new URL('../src/server.js',import.meta.url),'utf8');
const section=(a,b)=>source.slice(source.indexOf(a),source.indexOf(b,source.indexOf(a)+a.length));
test('runtime exposes actual retry stages without counting a wait as another POST',async()=>{
  const stages=[];let posts=0;
  const c=vm.createContext({randomUUID:()=> 'id',IMAGE_FETCH_DISPATCHER:{},IMAGE_RESPONSE_TIMEOUT_MS:1000,
    IMAGE_CONCURRENCY_RETRY_DELAYS_MS:[0,0],IMAGE_POLICY_RETRY_DELAYS_MS:[0],IMAGE_NETWORK_RETRY_DELAYS_MS:[0],
    AbortSignal:{timeout:()=>({})},setTimeout:fn=>fn(),
    fetch:async()=>{posts++;return {ok:posts===3,status:posts===3?200:503,text:async()=>'{"message":"overload"}'};}
  });
  vm.runInContext(section('function imageFailureCode(', 'async function callImageModel('),c);
  await c.requestImageModel('https://test.invalid',{},()=>'',(stage,n)=>stages.push([stage,n]));
  assert.equal(posts,3);
  assert.deepEqual(stages,[['GENERATING',1],['RETRY_WAIT',1],['GENERATING',2],['RETRY_WAIT',2],['GENERATING',3]]);
});
test('progress requires internal auth and never contains prompt or credentials',()=>{
  const out=[];const c=vm.createContext({INTERNAL_TOKEN:'test-token',json:(_,status,body)=>out.push({status,body})});
  vm.runInContext(section('const imageExecutionProgress =', 'async function handlePlatformImageGeneration('),c);
  const id='11111111-1111-1111-1111-111111111111';const update=c.beginImageProgress(id);
  update('GENERATING',2);const url=new URL('http://test/?requestId='+id);
  c.handleImageExecutionProgress({headers:{}},{},url);
  assert.equal(out[0].status,401);
  c.handleImageExecutionProgress({headers:{'x-internal-token':'test-token'}},{},url);
  assert.equal(out[1].status,200);assert.equal(out[1].body.attempts,2);
  assert.deepEqual(Object.keys(out[1].body).sort(),['attempts','stage','updatedAt']);
});
