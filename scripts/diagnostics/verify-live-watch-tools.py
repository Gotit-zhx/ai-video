"""Capture one isolated read-tool response without replaying a video analysis."""
import json
from pathlib import Path
import subprocess


def command(*args, data=None):
    result = subprocess.run(args, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if result.returncode:
        raise RuntimeError("Isolated tool diagnostic failed; no credentials logged")
    return result.stdout


def main():
    root = Path("/opt/heyi/releases/video-pipeline-20261015/watch-tool-proof")
    root.mkdir(mode=0o700, exist_ok=True)
    assert not (root / "started.json").exists(), "Never repeat this diagnostic automatically"
    info = json.loads(command("docker", "inspect", "ai-commerce-app"))[0]
    env = dict(value.split("=", 1) for value in info["Config"]["Env"])
    sql = ("SELECT JSON_OBJECT('modelId',c.model_id,'baseUrl',c.base_url,'cipher',c.api_key_ciphertext,"
           "'apiFormat',c.api_format) FROM video_scripts s JOIN agent_model_configs c ON c.id=s.model_config_id "
           "WHERE s.id='5fb04535-6439-473d-8550-bf656c7f5b7f'")
    model = json.loads(command("docker", "exec", "ai-commerce-mysql", "sh", "-c",
                              'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u "$MYSQL_USER" "$MYSQL_DATABASE" -NB --raw -e "$1"',
                              "query", sql))
    payload = json.dumps({"secret": env["APP_MODEL_SECRET_KEY"], "model": model}).encode()
    (root / "started.json").write_text('{"postLimit":1}')
    program = r"""
import {createHash,createDecipheriv} from 'node:crypto';
import fs from 'node:fs';
import {requestChatCompletion} from '/app/src/chat-completion-transport.js';
(async()=>{
  let input='';for await(const part of process.stdin)input+=part;const p=JSON.parse(input);
  const encrypted=Buffer.from(p.model.cipher.slice(3),'base64');
  const decipher=createDecipheriv('aes-256-gcm',createHash('sha256').update(p.secret).digest(),encrypted.subarray(0,12));
  decipher.setAuthTag(encrypted.subarray(-16));
  const apiKey=Buffer.concat([decipher.update(encrypted.subarray(12,-16)),decipher.final()]).toString();
  const config={modelId:p.model.modelId,baseUrl:p.model.baseUrl,apiFormat:p.model.apiFormat};
  let posts=0;const events=[];
  const fetchImpl=async(url,options)=>{
    if(++posts>1)throw Error('One diagnostic POST only');
    fs.writeFileSync('/proof/submitted.json',JSON.stringify({posts,model:config.modelId}),{mode:0o600});
    const response=await fetch(url,options);
    const text=await response.clone().text();
    for(const line of text.split(/\r?\n/)){
      if(!line.startsWith('data:'))continue;
      try{
        const event=JSON.parse(line.slice(5)),choice=event.choices?.[0];
        if(choice?.delta?.tool_calls||choice?.finish_reason)events.push({
          calls:choice.delta?.tool_calls,finish:choice.finish_reason});
      }catch{}
    }
    fs.writeFileSync('/proof/events.json',JSON.stringify(events).replaceAll(apiKey,'[REDACTED]'),{mode:0o600});
    return response;
  };
  let summary;
  try{
    const result=await requestChatCompletion({config,apiKey,maxTokens:6500,timeoutMs:120000,
      reasoningEffort:'low',requireStreaming:true,allowMissingDone:true,allowToolCalls:true,fetchImpl,
      messages:[{role:'system',content:'You analyze video frames. Read all listed frames with the read tool before making visual claims.'},
      {role:'user',content:'Read all four frames: /tmp/watch-proof/frame-1.jpg, /tmp/watch-proof/frame-2.jpg, /tmp/watch-proof/frame-3.jpg, /tmp/watch-proof/frame-4.jpg. Use the read tool now; do not describe unseen frames.'}],
      tools:[{type:'function',function:{name:'read',description:'Read one JPEG keyframe at the supplied absolute path.',
        parameters:{type:'object',properties:{path:{type:'string'}},required:['path']}}}]});
    summary={status:'RECEIVED',model:config.modelId,posts,events,toolCalls:result.toolCalls.map(call=>{
      let valid=false;try{const parsed=JSON.parse(call.function.arguments);valid=parsed&&typeof parsed==='object'&&!Array.isArray(parsed);}catch{}
      return {...call,validObjectJson:!!valid};
    })};
  }catch(error){summary={status:'FAILED',model:config.modelId,posts,events,failureCode:error.failureCode||error.code};}
  fs.writeFileSync('/proof/result.json',JSON.stringify(summary,null,2).replaceAll(apiKey,'[REDACTED]'),{mode:0o600});
  console.log(JSON.stringify(summary));
})().catch(()=>{console.error('Tool proof interrupted; inspect existing evidence, do not repeat');process.exitCode=1});
"""
    image = json.loads(command("docker", "inspect", "heyi-pi-runtime"))[0]["Image"]
    output = command("docker", "run", "--rm", "-i", "--name", "video-watch-tool-proof",
                     "--read-only", "--user", "0", "--tmpfs", "/tmp", "--cap-drop", "ALL",
                     "--security-opt", "no-new-privileges", "--memory", "384m", "--cpus", "1",
                     "-v", str(root) + ":/proof", "--entrypoint", "node", image,
                     "--input-type=module", "-e", program, data=payload)
    print(output.decode())


if __name__ == "__main__":
    main()
