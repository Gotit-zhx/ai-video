import test from 'node:test';
import assert from 'node:assert/strict';
import {readWatermarkChatStream} from '../src/watermark-chat-stream.js';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const event = (content, finish_reason=null) => 'data: '+JSON.stringify({choices:[{delta:{content},finish_reason}]})+'\r\n\r\n';
const response = text => ({body:(async function*(){const bytes=new TextEncoder().encode(text);for(let i=0;i<bytes.length;i++)yield bytes.slice(i,i+1);})()});
test('waits for complete stream and decodes Chinese split across byte chunks',async()=>{
  const value=await readWatermarkChatStream(response(': heartbeat\n\n'+event('{"reason":"水印"}')+event('', 'stop')+'data: [DONE]\n\n'));
  assert.equal(value,'{"reason":"水印"}');
});
test('valid-looking JSON before a disconnected stream must fail',async()=>{
  await assert.rejects(readWatermarkChatStream(response(event('{"decision":"ABSENT"}'))),/完成标记之前/);
});
test('token limit and provider error cannot become successful verdicts',async()=>{
  await assert.rejects(readWatermarkChatStream(response(event('{}','length')+'data: [DONE]\n')),/未正常完成/);
  await assert.rejects(readWatermarkChatStream(response('data: {"error":{"message":"failed"}}\n')),/返回错误/);
});
test('completion marker ends reading without waiting for provider socket close',async()=>{
  let closed=false;
  const body=(async function*(){try{yield new TextEncoder().encode(event('{}','stop')+'data: [DONE]\n');throw Error('must not request another chunk');}finally{closed=true;}})();
  assert.equal(await readWatermarkChatStream({body}),'{}');
  assert.equal(closed,true);
});
test('deployed Grok watermark request matches verified streaming payload',async()=>{
  const source=readFileSync(new URL('../src/server.js',import.meta.url),'utf8');
  const code=source.slice(source.indexOf('async function requestNonStreamingChat('),source.indexOf('async function requestVideoScriptNonStreaming('));
  let payload;
  const context=vm.createContext({AbortSignal,PRODUCT_ANALYSIS_DISPATCHER:{},readWatermarkChatStream,
    fetch:async(url,options)=>{payload=JSON.parse(options.body);return {ok:true,...response(event('{}','stop')+'data: [DONE]\n')};}});
  vm.runInContext(code,context);
  assert.equal(await context.requestNonStreamingChat({modelId:'grok-4.6',baseUrl:'https://unit.invalid/v1'},'test-key','system','prompt',[{mimeType:'image/png',data:'AA=='}],2000,600000,true),'{}');
  assert.equal(payload.stream,true);
  assert.equal(payload.max_completion_tokens,undefined);
  assert.equal(payload.max_tokens,undefined);
  assert.equal(payload.reasoning_effort,undefined);
});
