// Actual installed Pi SDK against a loopback-only model fixture; no paid calls.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {Agent} from '@earendil-works/pi-agent-core';
import {streamSimple} from '@earendil-works/pi-ai/api/openai-completions';
import {createPosterAgentRequest, runPosterAnalysis, POSTER_STYLE} from '../src/product-main-image-style.js';

test('installed Pi Agent SDK streams one image, emits counts, and returns validated poster JSON', async () => {
  const style = {complete:true, productDescription:'shirt', productRegions:[{x:.1,y:.1,width:.8,height:.8}],
    layout:'center', palette:'white', typography:'sans', background:'white', lighting:'soft', decorations:'none',
    prompt:'Poster of {{product}} with {{title}}'};
  const calls = [], progress = [];
  const server = http.createServer(async (request, response) => {
    let body = '';
    for await (const chunk of request) body += chunk;
    calls.push({url:request.url, body:JSON.parse(body)});
    response.writeHead(200, {'content-type':'text/event-stream'});
    const chunk = (delta, finish_reason = null) => response.write(`data: ${JSON.stringify({
      id:'fixture', object:'chat.completion.chunk', created:0, model:'gpt-fixture',
      choices:[{index:0,delta,finish_reason}]
    })}\n\n`);
    chunk({role:'assistant'});
    chunk({content:JSON.stringify(style)});
    chunk({}, 'stop');
    response.end('data: [DONE]\n\n');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    const config = {baseUrl:`http://127.0.0.1:${server.address().port}/v1`, modelId:'gpt-fixture', apiKey:'local-only'};
    const modelFrom = config => ({id:config.modelId,name:config.modelId,api:'openai-completions',provider:'fixture',
      baseUrl:config.baseUrl,reasoning:true,input:['text','image'],cost:{input:0,output:0,cacheRead:0,cacheWrite:0},
      contextWindow:128000,maxTokens:8192,compat:{supportsStore:false,supportsDeveloperRole:false}});
    const requestAgent = createPosterAgentRequest({Agent,streamSimple,modelFrom,timeoutMs:5000,idleTimeoutMs:3000});
    const result = await runPosterAnalysis({model:config,image:{mimeType:'image/png',data:'AA=='},
      context:{analysisType:POSTER_STYLE}},requestAgent,JSON.parse,{onProgress:event => progress.push(event)});
    assert.deepEqual(result.result,style);
    assert.equal(calls.length,1);
    assert.equal(calls[0].url,'/v1/chat/completions');
    assert.equal(calls[0].body.stream,true);
    assert.equal(calls[0].body.messages[1].content.filter(part => part.type==='image_url').length,1);
    assert.ok(progress.some(event => event.stage==='MODEL_STREAMING' && event.outputCharacters > 0));
    assert.equal(progress.at(-1).stage,'VALIDATING');
  } finally { await new Promise(resolve => server.close(resolve)); }
});
