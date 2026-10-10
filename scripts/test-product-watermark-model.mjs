// Local QA only: explicit archived fixtures, persisted attempts, no image edits or publication.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {runWatermarkAnalysis} from '../pi-runtime/src/product-watermark-analysis.js';
import {readWatermarkChatStream} from '../pi-runtime/src/watermark-chat-stream.js';
const docker='/Applications/Docker.app/Contents/Resources/bin/docker';
const env=Object.fromEntries(JSON.parse(execFileSync(docker,['inspect','ai-commerce-app'],{encoding:'utf8'}))[0].Config.Env.map(s=>{const p=s.indexOf('=');return [s.slice(0,p),s.slice(p+1)];}));
const sql="SELECT JSON_OBJECT('baseUrl',base_url,'modelId',model_id,'cipher',api_key_ciphertext) FROM agent_model_configs WHERE organization_id='4d6279a0-92b2-4a26-9edf-3bfe5b58f817' AND model_type='DRIVER' AND status='ACTIVE' ORDER BY default_model DESC,updated_at DESC LIMIT 1";
const raw=execFileSync(docker,['exec','ai-commerce-mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB --raw -e "$1"','qa',sql],{encoding:'utf8'}).trim();
const config=JSON.parse(raw),encrypted=Buffer.from(config.cipher.slice(3),'base64');
const decipher=crypto.createDecipheriv('aes-256-gcm',crypto.createHash('sha256').update(env.APP_MODEL_SECRET_KEY).digest(),encrypted.subarray(0,12));
decipher.setAuthTag(encrypted.subarray(-16));
const model={baseUrl:config.baseUrl,modelId:config.modelId,apiKey:Buffer.concat([decipher.update(encrypted.subarray(12,-16)),decipher.final()]).toString()};
if(process.env.WATERMARK_TEST_MODEL) model.modelId=process.env.WATERMARK_TEST_MODEL;
const containerTransport=process.argv.includes('--container');
const modelSuffix=process.env.WATERMARK_TEST_MODEL?'/'+model.modelId.replace(/[^a-zA-Z0-9_.-]/g,'_'):'';
const attemptSuffix=process.env.WATERMARK_TEST_ATTEMPT?'/'+process.env.WATERMARK_TEST_ATTEMPT.replace(/[^a-zA-Z0-9_.-]/g,'_'):'';
const out=path.resolve('output/watermark-detection-20260908'+modelSuffix+(containerTransport?'/container-transport':'')+attemptSuffix);fs.mkdirSync(out,{recursive:true});
const root='output/batch-runs/20260908-pink-cat/';
const load=file=>({mimeType:file.endsWith('.jpg')?'image/jpeg':'image/png',data:fs.readFileSync(path.isAbsolute(file)?file:root+file).toString('base64')});
const tests=[
  {id:'photo-with-overlay',type:'WATERMARK_DETECTION_V1',source:process.env.WATERMARK_TEST_SOURCE||(process.env.WATERMARK_TEST_PNG?'source-original.png':'source-original.jpg')},
  {id:'print-text-is-not-watermark',type:'WATERMARK_DETECTION_V1',source:'extract-0.png'},
  {id:'changed-print-must-fail',type:'WATERMARK_VALIDATION_V1',source:'source-original.jpg',candidate:process.env.WATERMARK_TEST_CANDIDATE||'mockup_watermark_removed-2.png'}
];
for(const test of tests){
  if(process.env.WATERMARK_TEST_CASE && process.env.WATERMARK_TEST_CASE!==test.id) continue;
  const file=path.join(out,test.id+'.json');
  if(fs.existsSync(file)){console.log(test.id+': existing attempt retained; skipped');continue;}
  const evidence={...test,modelId:model.modelId,status:'SUBMITTING',startedAt:new Date().toISOString()};
  fs.writeFileSync(file,JSON.stringify(evidence,null,2),{flag:'wx'});
  try{
    const request=async(config,key,system,prompt,frames,maxTokens,timeout)=>{
      timeout=Number(process.env.WATERMARK_TEST_TIMEOUT_MS||timeout);
      const streaming=process.env.WATERMARK_TEST_STREAM==='true';
      evidence.transport={streaming,timeoutMs:timeout,api:process.env.WATERMARK_TEST_API||'chat'};
      const useResponses=process.env.WATERMARK_TEST_API==='responses';
      const url=config.baseUrl.replace(/\/+$/,'')+(useResponses?'/responses':'/chat/completions');
      const options={method:'POST',headers:{'Authorization':'Bearer '+key,'Content-Type':'application/json'},body:JSON.stringify({model:config.modelId,stream:false,max_completion_tokens:maxTokens,reasoning_effort:'low',messages:[{role:'system',content:system},{role:'user',content:[{type:'text',text:prompt},...frames.map(i=>({type:'image_url',image_url:{url:'data:'+i.mimeType+';base64,'+i.data}}))]}]})};
      if(useResponses)options.body=JSON.stringify({model:config.modelId,stream:false,store:false,max_output_tokens:maxTokens,reasoning:{effort:'low'},instructions:system,input:[{role:'user',content:[{type:'input_text',text:prompt},...frames.map(i=>({type:'input_image',image_url:'data:'+i.mimeType+';base64,'+i.data}))]}]});
      if(streaming){const body=JSON.parse(options.body);body.stream=true;options.body=JSON.stringify(body);}
      if(process.env.WATERMARK_TEST_MINIMAL==='true'){const body=JSON.parse(options.body);delete body.max_completion_tokens;delete body.reasoning_effort;options.body=JSON.stringify(body);evidence.transport.minimalParameters=true;}
      let response;
      if(containerTransport){
        const worker=`const readStream=${readWatermarkChatStream.toString()};let input='';process.stdin.on('data',s=>input+=s);process.stdin.on('end',async()=>{const started=Date.now();let headersMs,firstChunkMs=null,chunks=0,status=0;try{const p=JSON.parse(input);const r=await fetch(p.url,{...p.options,signal:AbortSignal.timeout(p.timeout)});status=r.status;headersMs=Date.now()-started;let text='';const decoder=new TextDecoder();const observed=(async function*(){for await(const bytes of r.body){if(firstChunkMs===null)firstChunkMs=Date.now()-started;chunks++;text+=decoder.decode(bytes,{stream:true});yield bytes;}text+=decoder.decode();})();if(r.ok&&p.streamChat)await readStream({body:observed});else for await(const chunk of observed){}process.stdout.write(JSON.stringify({status,text,headersMs,firstChunkMs,chunks,elapsedMs:Date.now()-started,contentType:r.headers.get('content-type')}));}catch(e){process.stdout.write(JSON.stringify({status:0,upstreamStatus:status,text:'Transport '+e.name+': '+e.message,headersMs,firstChunkMs,chunks,elapsedMs:Date.now()-started}));}});`;
        const result=JSON.parse(execFileSync(docker,['exec','-i','heyi-pi-runtime','node','-e',worker],{input:JSON.stringify({url,options,timeout,streamChat:streaming&&!useResponses}),encoding:'utf8',timeout:timeout+10000,maxBuffer:4*1024*1024}));
        Object.assign(evidence.transport,{status:result.status,headersMs:result.headersMs,firstChunkMs:result.firstChunkMs,chunks:result.chunks,elapsedMs:result.elapsedMs,contentType:result.contentType});
        response={ok:result.status>=200&&result.status<300,status:result.status,json:async()=>JSON.parse(result.text),text:async()=>result.text};
      }else response=await fetch(url,{...options,signal:AbortSignal.timeout(timeout)});
      if(!response.ok){
        const raw=await response.text();let detail;
        try{const error=JSON.parse(raw)?.error;detail=typeof error==='object'?{code:error.code,message:error.message}:error;}catch{detail=raw.slice(0,400);}
        throw Error(('Model HTTP '+response.status+': '+JSON.stringify(detail)).replaceAll(key,'[REDACTED]').slice(0,1000));
      }
      if(streaming){
        const raw=await response.text();let reply='',finished=false;
        for(const line of raw.split(/\r?\n/)){if(!line.startsWith('data:'))continue;const data=line.slice(5).trim();if(data==='[DONE]'){finished=true;continue;}if(!data)continue;const event=JSON.parse(data);if(event.error)throw Error('Stream provider error: '+JSON.stringify(event.error).replaceAll(key,'[REDACTED]').slice(0,500));if(useResponses){if(event.type==='response.output_text.delta')reply+=event.delta||'';if(event.type==='response.completed')finished=true;}else{reply+=event.choices?.[0]?.delta?.content||'';}}
        evidence.transport.completedEvent=finished;evidence.transport.replyLength=reply.length;
        if(!finished)throw Error('Stream closed before completion marker');return reply;
      }
      const body=await response.json();return useResponses?body.output?.flatMap(x=>x.content||[]).filter(x=>x.type==='output_text').map(x=>x.text).join('\n'):body.choices?.[0]?.message?.content;
    };
    const parse=text=>JSON.parse(String(text).replace(/^```(?:json)?\s*/,'').replace(/\s*```$/,''));
    const input={model,context:{analysisType:test.type,...(process.env.WATERMARK_TEST_REGION_HINT?{localScreening:{status:'SUSPECTED',regions:JSON.parse(process.env.WATERMARK_TEST_REGION_HINT),note:'Review this possible cross-garment translucent overlay; independently verify the full image and keep physical embroidery.'}}:{})},image:load(test.source),...(test.candidate?{candidateImage:load(test.candidate)}:{})};
    let result;
    if(process.argv.includes('--runtime')) {
      const worker=`let input='';process.stdin.on('data',s=>input+=s);process.stdin.on('end',async()=>{try{const p=JSON.parse(input);const r=await fetch('http://127.0.0.1:8090/v1/product/analyze',{method:'POST',headers:{'Content-Type':'application/json','X-Internal-Token':p.token},body:JSON.stringify(p.body),signal:AbortSignal.timeout(1020000)});process.stdout.write(JSON.stringify({status:r.status,body:await r.json()}));}catch(e){process.stdout.write(JSON.stringify({status:0,body:{message:e.name}}));}});`;
      const r=JSON.parse(execFileSync(docker,['exec','-i','heyi-pi-runtime','node','-e',worker],{input:JSON.stringify({token:env.APP_PI_RUNTIME_TOKEN,body:input}),encoding:'utf8',timeout:1030000,maxBuffer:4*1024*1024}));
      if(r.status!==200)throw Error('Runtime HTTP '+r.status+': '+JSON.stringify(r.body).replaceAll(model.apiKey,'[REDACTED]'));
      result=r.body;evidence.runtime=true;
    } else result=await runWatermarkAnalysis(input,request,parse);
    Object.assign(evidence,{status:'COMPLETED',result,finishedAt:new Date().toISOString()});
    console.log(test.id+': '+JSON.stringify(result.result));
  }catch(error){Object.assign(evidence,{status:/^Model HTTP [45]\d\d(?:$|:)/.test(error.message)?'REJECTED':'UNKNOWN_OR_FAILED',message:error.message,finishedAt:new Date().toISOString()});console.log(test.id+': '+error.message);}
  if(evidence.transport)console.log('transport: '+JSON.stringify(evidence.transport));
  fs.writeFileSync(file,JSON.stringify(evidence,null,2));
}
