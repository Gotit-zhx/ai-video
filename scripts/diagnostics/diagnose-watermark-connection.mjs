// Local QA only: explicit archived fixtures, persisted attempts, no image edits or publication.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {runWatermarkAnalysis} from '../../services/pi-runtime/src/product-watermark-analysis.js';
const docker='/Applications/Docker.app/Contents/Resources/bin/docker';
const env=Object.fromEntries(JSON.parse(execFileSync(docker,['inspect','ai-commerce-app'],{encoding:'utf8'}))[0].Config.Env.map(s=>{const p=s.indexOf('=');return [s.slice(0,p),s.slice(p+1)];}));
const sql="SELECT JSON_OBJECT('baseUrl',base_url,'modelId',model_id,'cipher',api_key_ciphertext) FROM agent_model_configs WHERE organization_id='4d6279a0-92b2-4a26-9edf-3bfe5b58f817' AND model_type='DRIVER' AND status='ACTIVE' ORDER BY default_model DESC,updated_at DESC LIMIT 1";
const raw=execFileSync(docker,['exec','ai-commerce-mysql','sh','-c','MYSQL_PWD="$MYSQL_PASSWORD" mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB --raw -e "$1"','qa',sql],{encoding:'utf8'}).trim();
const config=JSON.parse(raw),encrypted=Buffer.from(config.cipher.slice(3),'base64');
const decipher=crypto.createDecipheriv('aes-256-gcm',crypto.createHash('sha256').update(env.APP_MODEL_SECRET_KEY).digest(),encrypted.subarray(0,12));
decipher.setAuthTag(encrypted.subarray(-16));
const model={baseUrl:config.baseUrl,modelId:config.modelId,apiKey:Buffer.concat([decipher.update(encrypted.subarray(12,-16)),decipher.final()]).toString()};
if(process.env.WATERMARK_TEST_MODEL)model.modelId=process.env.WATERMARK_TEST_MODEL;

const worker=`let data='';process.stdin.on('data',s=>data+=s);process.stdin.on('end',async()=>{const p=JSON.parse(data);try{const r=await fetch(p.url,{...p.options,signal:AbortSignal.timeout(60000)});process.stdout.write(JSON.stringify({status:r.status,body:await r.text()}));}catch(e){process.stdout.write(JSON.stringify({error:e.message}));}});`;
for(const endpoint of (process.argv.includes('--models-only')?['/models']:['/models','/chat/completions'])){
const options={headers:{Authorization:'Bearer '+model.apiKey,'Content-Type':'application/json'}};
if(endpoint.includes('chat')){options.method='POST';options.body=JSON.stringify({model:model.modelId,messages:[{role:'user',content:'Reply OK.'}],max_completion_tokens:20});}
const r=JSON.parse(execFileSync(docker,['exec','-i','heyi-pi-runtime','node','-e',worker],{input:JSON.stringify({url:model.baseUrl.replace(/\/+$/,'')+endpoint,options}),encoding:'utf8',timeout:70000}));
let parsed;try{parsed=JSON.parse(r.body)}catch{parsed={message:r.body?.slice(0,500)}};
console.log(JSON.stringify({endpoint,status:r.status,error:r.error,detail:endpoint==='/models'&&r.status===200?{models:parsed.data?.filter(x=>/gpt-5|grok/i.test(x.id)).slice(0,50)}:parsed}).replaceAll(model.apiKey,'[REDACTED]'));
}
