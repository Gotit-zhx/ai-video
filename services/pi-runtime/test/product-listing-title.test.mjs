import {test} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {generateListingTitle} from '../src/product-listing-title.js';
const input={originalTitle:'Misleading phone title that must not reach the model',category:'Women > T-shirts',
  description:'Cotton knit tee',attributes:{'Neckline':'Others'},skuOptions:[{Color:'Black'}]};
const images=[{role:'MAIN',mimeType:'image/png',data:'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jL1sAAAAASUVORK5CYII='}];
for(const id of ['gpt-5.5','grok-4.6','deepseek-chat']){
  test(`real pi SDK generates a structured ${id} title without tools or retries`,async()=>{
    let payload,calls=0;
    const server=http.createServer(async(req,res)=>{
      calls++; let text='';for await(const chunk of req)text+=chunk;payload=JSON.parse(text);
      res.writeHead(200,{'Content-Type':'text/event-stream'});
      res.end(`data: ${JSON.stringify({choices:[{index:0,delta:{content:'{"title":"Women Cotton Short Sleeve Casual T-shirt"}'},finish_reason:null}]})}\n\n`
        +`data: ${JSON.stringify({choices:[{index:0,delta:{},finish_reason:'stop'}]})}\n\n`+'data: [DONE]\n\n');
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    try{
      const result=await generateListingTitle({input,images,model:{modelId:id,apiKey:'fixture-secret',apiFormat:'OPENAI_COMPATIBLE',baseUrl:`http://127.0.0.1:${server.address().port}/v1`}});
      assert.equal(result.title,'Women Cotton Short Sleeve Casual T-shirt');assert.equal(calls,1);
      assert.equal(payload.model,id);assert.equal(payload.tools,undefined);
      assert.ok(payload.messages[0].content.includes('Do not invent stretch'));
      const user=payload.messages.find(message=>message.role==='user');
      assert.equal(user.content.filter(block=>block.type==='image_url').length,1);
      assert.equal(user.content.find(block=>block.type==='image_url').image_url.url,`data:image/png;base64,${images[0].data}`);
      const facts=JSON.parse(user.content.find(block=>block.type==='text').text);
      assert.equal(facts.originalTitle,undefined);
      assert.equal(facts.description,'Cotton knit tee');
      assert.deepEqual(facts.imageEvidence,[{image:1,role:'MAIN'}]);
      assert.ok(!JSON.stringify(payload).includes('Misleading phone title'));
      assert.equal(payload[id==='gpt-5.5'?'max_completion_tokens':'max_tokens'],1500);
    }finally{await new Promise(resolve=>server.close(resolve));}
  });
}
test('truncated, tool and invalid title results cannot be presented as successful',async()=>{
  for(const [delta,finish] of [
    [{content:'{"title":"Women Cotton T-shirt"}'},'length'],
    [{tool_calls:[{index:0,id:'forbidden',type:'function',function:{name:'send',arguments:'{}'}}]},'tool_calls'],
    [{content:'{"title":"<b>Women Cotton T-shirt</b>"}'},'stop'],
  ]){
    let calls=0;
    const server=http.createServer((req,res)=>{
      calls++;req.resume();res.writeHead(200,{'Content-Type':'text/event-stream'});
      res.end(`data: ${JSON.stringify({choices:[{index:0,delta,finish_reason:null}]})}\n\n`
        +`data: ${JSON.stringify({choices:[{index:0,delta:{},finish_reason:finish}]})}\n\n`+'data: [DONE]\n\n');
    });
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    try{
      await assert.rejects(generateListingTitle({input,images,model:{modelId:'deepseek-chat',baseUrl:`http://127.0.0.1:${server.address().port}/v1`,apiKey:'fixture',apiFormat:'OPENAI_COMPATIBLE'}}));
      assert.equal(calls,1);
    }finally{await new Promise(resolve=>server.close(resolve));}
  }
});
test('missing, invalid and oversized pictures are rejected before any model call',async()=>{
  for(const photos of [undefined,[],[{...images[0],data:'not base64'}],Array(5).fill(images[0]),
    [{...images[0],data:'a'.repeat(6*1024*1024)}]]){
    let calls=0;
    await assert.rejects(generateListingTitle({input,images:photos,model:{}},{stream:()=>{calls++;}}));
    assert.equal(calls,0);
  }
});
