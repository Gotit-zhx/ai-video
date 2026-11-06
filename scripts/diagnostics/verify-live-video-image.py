"""One idempotent real image check; credentials stay in memory and out of output."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import subprocess


def run(*args, data=None):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError("Live image check command failed; no credentials were logged")
    return result.stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--case", required=True)
    parser.add_argument("--size", default="2160x3840")
    parser.add_argument("--stream", action=argparse.BooleanOptionalAction, default=True)
    parser.add_argument("--resume-unsubmitted", action="store_true")
    parser.add_argument("--image-array", action="store_true")
    args = parser.parse_args()
    assert args.case.replace("-", "").isalnum()
    info = json.loads(run("docker", "inspect", "ai-commerce-app"))[0]
    env = dict(value.split("=", 1) for value in info["Config"]["Env"])
    sql = ("SELECT JSON_OBJECT('modelId',model_id,'baseUrl',base_url,'cipher',api_key_ciphertext,"
           "'apiFormat',api_format) FROM agent_model_configs "
           "WHERE id='818e7eb1-d7ed-4933-8d78-c72d5c02f189';")
    model = json.loads(run("docker", "exec", "ai-commerce-mysql", "sh", "-c",
                          'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB --raw -e "$1"',
                          "query", sql))
    mount = next(m["Source"] for m in info["Mounts"] if m["Destination"] == "/data/ai-video-tasks")
    source = Path(mount) / "6a61f788-6685-4b64-82b1-eec97ed8cf90/28366b61-fc7c-4e7e-a3bc-7e2ee0100843/233523e1-a4ff-41e0-b788-0a2eddf491dd/source.jpg"
    payload = {"secret": env["APP_MODEL_SECRET_KEY"], "model": model,
               "size": args.size, "stream": args.stream,
               "case": args.case, "imageArray": args.image_array,
               "source": base64.b64encode(source.read_bytes()).decode()}
    root = Path("/opt/heyi/releases/video-pipeline-20261015/live-proof") / args.case
    root.mkdir(mode=0o700, parents=True, exist_ok=True)
    if (root / "started.json").exists():
        assert args.resume_unsubmitted and not (root / "wire.json").exists(), "Existing submission must not be repeated"
        assert not any((root / "operations").glob("*.json")), "Existing ledger must not be repeated"
    (root / "started.json").write_text(json.dumps(
        {"size": args.size, "stream": args.stream, "input_fidelity": "high",
         "imageArray": args.image_array, "paidSubmissionLimit": 1}))
    program = r"""
import {createHash,createDecipheriv,randomUUID} from 'node:crypto';
import fs from 'node:fs';
import vm from 'node:vm';
import {Agent} from '/app/node_modules/undici/index.js';
import {ImageOperationStore} from '/app/src/image-operation-store.js';
import {callImageModelWithCopyrightFallback} from '/app/src/image-model-fallback.js';
import {supportsNativeTransparentPng} from '/app/src/image-output-capabilities.js';
import {readImageResponse} from '/app/src/image-stream-response.js';
(async()=>{
  let input=''; for await(const part of process.stdin)input+=part;
  const p=JSON.parse(input), encrypted=Buffer.from(p.model.cipher.slice(3),'base64');
  const decipher=createDecipheriv('aes-256-gcm',createHash('sha256').update(p.secret).digest(),encrypted.subarray(0,12));
  decipher.setAuthTag(encrypted.subarray(-16));
  const apiKey=Buffer.concat([decipher.update(encrypted.subarray(12,-16)),decipher.final()]).toString();
  const requestId='codex-video-live-20261015-'+p.case;
  const body={model:{modelId:p.model.modelId,baseUrl:p.model.baseUrl,apiKey,apiFormat:p.model.apiFormat},
    prompt:'Create one realistic front-view product model photograph. An adult woman wears exactly the uploaded pink short-sleeve top. Preserve its actual neckline, sleeves, color and shape. Full upper body, plain light gray studio background. One photograph, no text or collage.',
    size:p.size,references:[{name:'source.jpg',mimeType:'image/jpeg',dataBase64:p.source}],
    inputFidelity:'high',singleProviderAttempt:true,streamImages:p.stream,asyncResult:true,progressRequestId:requestId};
  let source=fs.readFileSync('/app/src/server.js','utf8');
  if(p.imageArray){
    const original='const field = inputImages.length === 1 ? "image" : "image[]";';
    if(source.split(original).length!==2)throw Error('Image field candidate source drift');
    source=source.replace(original,'const field = "image[]";');
  }
  const helperStart=source.indexOf('function imageApiUsage(');
  const helperEnd=source.indexOf('function publicErrorMessage(',helperStart);
  if(helperStart<0||helperEnd<=helperStart)throw Error('Source extraction failed');
  const dispatcher=new Agent({headersTimeout:960000,bodyTimeout:960000});
  const store=new ImageOperationStore('/proof/operations');
  const wire=[], hash=value=>createHash('sha256').update(value).digest('hex');
  let posts=0;
  const context=vm.createContext({
    Buffer,Blob,FormData,URL,randomUUID,AbortController,AbortSignal,DOMException,
    setTimeout,clearTimeout,readImageResponse,callImageModelWithCopyrightFallback,supportsNativeTransparentPng,
    IMAGE_FETCH_DISPATCHER:dispatcher,IMAGE_RESPONSE_TIMEOUT_MS:900000,
    IMAGE_CONCURRENCY_RETRY_DELAYS_MS:[],IMAGE_NETWORK_RETRY_DELAYS_MS:[],IMAGE_POLICY_RETRY_DELAYS_MS:[],
    fetch:async(url,options={})=>{
      const entry={method:options.method||'GET',origin:new URL(url).origin,path:new URL(url).pathname};
      if(entry.method==='POST'){
        if(++posts>1)throw Error('Proof single submission limit exceeded');
        const fields={},references=[];
        for(const [key,value] of options.body.entries()){
          if(typeof value==='string')fields[key]=key==='prompt'?{characters:value.length,sha256:hash(value)}:value;
          else{const bytes=Buffer.from(await value.arrayBuffer());references.push({field:key,name:value.name,type:value.type,bytes:bytes.length,sha256:hash(bytes)});}
        }
        Object.assign(entry,{fields,references});
      }
      wire.push(entry);fs.writeFileSync('/proof/wire.json',JSON.stringify(wire,null,2),{mode:0o600});
      const result=await fetch(url,options);
      entry.status=result.status;
      entry.contentType=result.headers.get('content-type');
      entry.providerRequestId=result.headers.get('x-request-id')||result.headers.get('request-id');
      if(!result.ok){
        const text=await result.clone().text();
        try{
          const value=JSON.parse(text),error=value.error||value;
          entry.error=Object.fromEntries(['message','code','type','param'].filter(key=>typeof error[key]==='string')
            .map(key=>[key,error[key].replaceAll(apiKey,'[REDACTED]').slice(0,1500)]));
        }catch{entry.error={bodyBytes:Buffer.byteLength(text)};}
      }
      fs.writeFileSync('/proof/wire.json',JSON.stringify(wire,null,2),{mode:0o600});
      return result;
    }
  });
  vm.runInContext(source.slice(helperStart,helperEnd),context);
  let summary;
  try{
    const result=await store.execute(requestId,body,operation=>context.callImageModel(body.model,{
      mode:'edit',prompt:body.prompt,size:body.size,inputFidelity:body.inputFidelity,streamImages:body.streamImages,
      operation,maxNetworkRetries:0,maxProviderRetries:0,deferDownload:false
    },body.references));
    const artifact=result.artifact,bytes=Buffer.from(artifact.dataBase64,'base64');
    const file='/proof/result.'+(artifact.mimeType==='image/png'?'png':'jpg');
    fs.writeFileSync(file,bytes,{mode:0o600});
    summary={status:'COMPLETED',requestId,bytes:bytes.length,sha256:hash(bytes),mimeType:artifact.mimeType,file,posts,wire};
  }catch(error){
    summary={status:'FAILED',requestId,posts,wire,error:{
      requestState:error.requestState,failureKind:error.failureKind,providerStatus:error.providerStatus,
      code:error.code,safeRetryCount:error.safeRetryCount,networkRetryCount:error.networkRetryCount}};
  }
  fs.writeFileSync('/proof/result.json',JSON.stringify(summary,null,2),{mode:0o600});
  console.log(JSON.stringify(summary));
  await dispatcher.close();
})().catch(error=>{console.error(JSON.stringify({setupError:error.name,code:error.code||'SETUP_FAILED'}));process.exitCode=1});
"""
    image = json.loads(run("docker", "inspect", "heyi-pi-runtime"))[0]["Image"]
    result = json.loads(run(
        "docker", "run", "--rm", "-i", "--name", "video-proof-" + args.case,
        "--read-only", "--user", "0", "--tmpfs", "/tmp", "--cap-drop", "ALL",
        "--security-opt", "no-new-privileges", "--memory", "768m", "--cpus", "1",
        "-v", str(root) + ":/proof", "--entrypoint", "node", image, "--input-type=module", "-e", program,
        data=json.dumps(payload).encode()))
    result.update(size=args.size, stream=args.stream, imageArray=args.image_array, paidSubmissionLimit=1)
    target = root / "summary.json"
    target.write_text(json.dumps(result, ensure_ascii=False, indent=2))
    target.chmod(0o600)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
