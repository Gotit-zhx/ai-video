// Explicit local QA: one pre-generation vision call per named attempt; never edit or publish.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {runWatermarkAnalysis} from '../pi-runtime/src/product-watermark-analysis.js';
import {readWatermarkChatStream} from '../pi-runtime/src/watermark-chat-stream.js';

const docker='/Applications/Docker.app/Contents/Resources/bin/docker';
const sourcePath=process.env.WATERMARK_TEST_SOURCE;
const viewPath=process.env.WATERMARK_TEST_DETECTION_VIEW;
const attempt=process.env.WATERMARK_TEST_ATTEMPT;
if(!sourcePath||!viewPath||!attempt||!path.isAbsolute(sourcePath)||!path.isAbsolute(viewPath))
  throw new Error('Set absolute WATERMARK_TEST_SOURCE and WATERMARK_TEST_DETECTION_VIEW plus a unique WATERMARK_TEST_ATTEMPT');
const output=path.resolve('output/watermark-missed-gray-20260909');
fs.mkdirSync(output,{recursive:true});
const reportPath=path.join(output,attempt.replace(/[^a-zA-Z0-9_.-]/g,'_')+'.json');
if(fs.existsSync(reportPath)) {
  console.log('Existing named attempt retained; no model request sent.');
  process.exit(0);
}
const image=file=>{
  const data=fs.readFileSync(file);
  let mimeType;
  if(data.subarray(0,8).equals(Buffer.from([137,80,78,71,13,10,26,10])))mimeType='image/png';
  else if(data[0]===255&&data[1]===216&&data[2]===255)mimeType='image/jpeg';
  else if(data.toString('ascii',0,4)==='RIFF'&&data.toString('ascii',8,12)==='WEBP')mimeType='image/webp';
  else throw new Error('The explicit fixture is not PNG, JPEG or WebP');
  return {frame:{mimeType,data:data.toString('base64')},evidence:{file,bytes:data.length,sha256:crypto.createHash('sha256').update(data).digest('hex')}};
};
const original=image(sourcePath),enhanced=image(viewPath);
const modelId=process.env.WATERMARK_TEST_MODEL||'grok-4.6';
const timeout=Number(process.env.WATERMARK_TEST_TIMEOUT_MS||180000);
if(!Number.isFinite(timeout)||timeout<1000||timeout>180000)throw new Error('Vision test timeout must be between 1000 and 180000 ms');
const report={attempt,status:'PREPARED',analysisType:'WATERMARK_DETECTION_V1',detectionPass:'ENHANCED_CONFIRMATION',modelId,
  original:original.evidence,enhanced:enhanced.evidence,startedAt:new Date().toISOString(),modelRequests:0};
fs.writeFileSync(reportPath,JSON.stringify(report,null,2),{flag:'wx'});
let secret='';
const safe=value=>String(value).replaceAll(secret||'\u0000','[REDACTED]').replace(/https?:\/\/[^\s"'<>]+/gi,'[URL REDACTED]');
try {
  const environment=Object.fromEntries(JSON.parse(execFileSync(docker,['inspect','ai-commerce-app'],{encoding:'utf8'}))[0].Config.Env.map(value=>{const split=value.indexOf('=');return [value.slice(0,split),value.slice(split+1)];}));
  const sql="SELECT JSON_OBJECT('baseUrl',base_url,'cipher',api_key_ciphertext) FROM agent_model_configs WHERE organization_id='4d6279a0-92b2-4a26-9edf-3bfe5b58f817' AND model_type='DRIVER' AND status='ACTIVE' ORDER BY default_model DESC,updated_at DESC LIMIT 1";
  const raw=execFileSync(docker,['exec','ai-commerce-mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB --raw -e "$1"','qa',sql],{encoding:'utf8'}).trim();
  const config=JSON.parse(raw),encrypted=Buffer.from(config.cipher.slice(3),'base64');
  const decipher=crypto.createDecipheriv('aes-256-gcm',crypto.createHash('sha256').update(environment.APP_MODEL_SECRET_KEY).digest(),encrypted.subarray(0,12));
  decipher.setAuthTag(encrypted.subarray(-16));
  secret=Buffer.concat([decipher.update(encrypted.subarray(12,-16)),decipher.final()]).toString();
  const model={baseUrl:config.baseUrl,modelId,apiKey:secret};
  const request=async(connection,key,prompt,context,frames)=>{
    if(report.modelRequests!==0)throw new Error('This named pre-detection attempt allows one request only');
    report.modelRequests=1;report.status='SUBMITTING';fs.writeFileSync(reportPath,JSON.stringify(report,null,2));
    const options={method:'POST',headers:{Authorization:'Bearer '+key,'Content-Type':'application/json'},body:JSON.stringify({model:connection.modelId,stream:true,messages:[{role:'system',content:prompt},{role:'user',content:[{type:'text',text:context},...frames.map(frame=>({type:'image_url',image_url:{url:'data:'+frame.mimeType+';base64,'+frame.data}}))]}]})};
    const worker=`const readStream=${readWatermarkChatStream.toString()};let input='';process.stdin.on('data',chunk=>input+=chunk);process.stdin.on('end',async()=>{const started=Date.now();let status=0;try{const p=JSON.parse(input);const response=await fetch(p.url,{...p.options,signal:AbortSignal.timeout(p.timeout)});status=response.status;const headersMs=Date.now()-started;if(!response.ok){await response.text();throw Error('Vision HTTP '+status);}const reply=await readStream(response);process.stdout.write(JSON.stringify({ok:true,status,headersMs,elapsedMs:Date.now()-started,reply}));}catch(error){process.stdout.write(JSON.stringify({ok:false,status,elapsedMs:Date.now()-started,errorType:error.name,message:String(error.message).replace(/https?:\\/\\/[^\\s]+/gi,'[URL REDACTED]')}));}});`;
    const data=JSON.parse(execFileSync(docker,['exec','-i','heyi-pi-runtime','node','-e',worker],{input:JSON.stringify({url:connection.baseUrl.replace(/\/+$/,'')+'/chat/completions',options,timeout}),encoding:'utf8',timeout:timeout+10000,maxBuffer:4*1024*1024}));
    report.transport={streaming:true,minimalParameters:true,timeoutMs:timeout,status:data.status,headersMs:data.headersMs,elapsedMs:data.elapsedMs,completed:data.ok};
    if(!data.ok)throw new Error(safe(data.errorType+': '+data.message));
    return data.reply;
  };
  report.result=await runWatermarkAnalysis({model,context:{analysisType:'WATERMARK_DETECTION_V1',detectionPass:'ENHANCED_CONFIRMATION'},image:original.frame,detectionViews:[enhanced.frame]},request,text=>JSON.parse(String(text).replace(/^```(?:json)?\s*/,'').replace(/\s*```$/,'')));
  report.status='COMPLETED';
  console.log(JSON.stringify({attempt,status:report.status,decision:report.result.result.decision,confidence:report.result.result.confidence,modelRequests:report.modelRequests,transport:report.transport}));
} catch(error) {
  report.status=report.modelRequests?'UNKNOWN_OR_FAILED':'FAILED_BEFORE_REQUEST';report.error=safe(error.message);
  console.log(JSON.stringify({attempt,status:report.status,modelRequests:report.modelRequests,error:report.error}));
  process.exitCode=1;
} finally {
  report.finishedAt=new Date().toISOString();
  fs.writeFileSync(reportPath,safe(JSON.stringify(report,null,2)));
}
