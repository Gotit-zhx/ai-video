"""Host-local diagnostic of one existing poster; credentials stay in process pipes."""
import base64
import json
import subprocess
import sys


def run(*args, data=None):
    return subprocess.check_output(args, input=data, stderr=subprocess.PIPE)


template = sys.argv[1]
assert len(template) == 36 and all(char in "0123456789abcdef-" for char in template)
model = sys.argv[2] if len(sys.argv) > 2 else ""
assert all(char.isalnum() or char in "._-" for char in model)
sql = """
SELECT JSON_OBJECT('organizationId',t.organization_id,'storageKey',i.storage_key,
 'modelId',m.model_id,'baseUrl',m.base_url,'ciphertext',m.api_key_ciphertext)
FROM product_main_image_templates t JOIN product_main_image_template_images i ON i.template_id=t.id
JOIN agent_model_configs m ON m.organization_id=t.organization_id AND m.model_type='DRIVER' AND m.status='ACTIVE'
WHERE t.id='%s' AND t.archived=0 AND i.sort_order=0
%s
ORDER BY m.default_model DESC,m.updated_at DESC LIMIT 1;
""" % (template, "AND m.model_id='" + model + "'" if model else "")
payload = json.loads(run("docker", "exec", "-i", "ai-commerce-mysql", "sh", "-c",
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot --default-character-set=utf8mb4 -NB "$MYSQL_DATABASE"',
    data=sql.encode()).decode())
info = json.loads(run("docker", "inspect", "ai-commerce-app"))[0]
env = dict(value.split("=", 1) for value in info["Config"]["Env"])
payload["secret"] = env["APP_MODEL_SECRET_KEY"]
storage = env.get("APP_COMMERCE_IMAGE_STORAGE", env.get("APP_AI_VIDEO_TASK_STORAGE", "/data/ai-video-tasks") + "/commerce-images")
payload["image"] = base64.b64encode(run("docker", "exec", "ai-commerce-app", "cat", storage + "/" + payload["storageKey"])).decode()
program = r"""
import {createHash,createDecipheriv} from 'node:crypto';
import {Agent} from '@earendil-works/pi-agent-core';
import {streamSimple} from '@earendil-works/pi-ai/api/openai-completions';
import {createPosterAgentRequest,runPosterAnalysis,POSTER_STYLE} from './src/product-main-image-style.js';
let raw='';for await(const chunk of process.stdin)raw+=chunk;
const input=JSON.parse(raw);
const encrypted=Buffer.from(input.ciphertext.slice(3),'base64');
const cipher=createDecipheriv('aes-256-gcm',createHash('sha256').update(input.secret).digest(),encrypted.subarray(0,12));
cipher.setAuthTag(encrypted.subarray(-16));
const apiKey=Buffer.concat([cipher.update(encrypted.subarray(12,-16)),cipher.final()]).toString();
const report={modelId:input.modelId,endpointOrigin:new URL(input.baseUrl).origin,events:[]};
const redact=value=>String(value||'').replaceAll(apiKey,'[REDACTED]').replaceAll(input.secret,'[REDACTED]')
 .replace(/data:image\/[^;]+;base64,[A-Za-z0-9+/=]+/g,'[IMAGE]').slice(0,1600);
class DiagnosticAgent extends Agent {
 constructor(options) {
  const original=options.streamFn;
  super({...options,streamFn:(model,context,opts)=>original(model,context,{...opts,
   onPayload:payload=>{report.payload={keys:Object.keys(payload),max_tokens:payload.max_tokens,max_completion_tokens:payload.max_completion_tokens,
    reasoning_effort:payload.reasoning_effort,stream_options:payload.stream_options};},
   onResponse:response=>{report.response={status:response.status,contentType:response.headers['content-type']};}
  })});
  this.subscribe(event=>{
   if(event.type==='message_end'&&event.message?.role==='assistant')
    report.assistant={stopReason:event.message.stopReason,error:redact(event.message.errorMessage),
     outputCharacters:event.message.content.filter(c=>c.type==='text').reduce((n,c)=>n+c.text.length,0)};
  });
 }
}
const modelFrom=config=>({id:config.modelId,name:config.modelId,api:'openai-completions',provider:'heyi-custom',
 baseUrl:config.baseUrl,reasoning:false,input:['text','image'],cost:{input:0,output:0,cacheRead:0,cacheWrite:0},
 contextWindow:128000,maxTokens:8192,compat:{supportsStore:false,supportsDeveloperRole:false,supportsReasoningEffort:false,supportsUsageInStreaming:true}});
const request=createPosterAgentRequest({Agent:DiagnosticAgent,modelFrom,streamSimple});
const data=Buffer.from(input.image,'base64'),mimeType=data[0]===0x89?'image/png':data[0]===0xff?'image/jpeg':'image/webp';
const start=Date.now();
try {
 const result=await runPosterAnalysis({model:{baseUrl:input.baseUrl,modelId:input.modelId,apiKey},image:{mimeType,data:input.image},
  context:{analysisType:POSTER_STYLE}},request,JSON.parse,{onProgress:p=>{if(!report.events.includes(p.stage))report.events.push(p.stage);}});
 report.complete=result.result.complete;
} catch(error){report.failureCode=error.failureCode;report.error=redact(error.message);}
report.elapsedMs=Date.now()-start;
console.log(JSON.stringify(report));
"""
result = run("docker", "exec", "-i", "-w", "/app", "heyi-pi-runtime", "node", "--input-type=module", "-e", program,
             data=json.dumps(payload).encode())
print(result.decode())
