// Read-only local QA observer: save actual images and state, never start/retry a generation.
const fs=require('node:fs');
const path=require('node:path');
(async()=>{
  const [batch,outArg]=process.argv.slice(2);
  if(!batch||!outArg||!process.env.HEYI_LOCAL_TEST_PASSWORD)throw Error('Usage: HEYI_LOCAL_TEST_PASSWORD=... node observe-commerce-creative-run.cjs BATCH_ID OUTPUT_DIR');
  const out=path.resolve(outArg),base='http://localhost:3000';fs.mkdirSync(out,{recursive:true});
  const login=await fetch(base+'/api/v1/auth/login',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({organizationCode:'heyi',phone:'13800000000',password:process.env.HEYI_LOCAL_TEST_PASSWORD}),signal:AbortSignal.timeout(15000)});
  if(!login.ok)throw Error('Local login failed');
  const cookie=login.headers.getSetCookie().map(x=>x.split(';')[0]).join('; ');
  const get=async endpoint=>{const r=await fetch(base+endpoint,{headers:{Cookie:cookie},signal:AbortSignal.timeout(15000)});if(!r.ok)throw Error('HTTP '+r.status);return(await r.json()).data;};
  const saved=new Set();let signature='';
  for(let attempt=0;attempt<240;attempt++){
    try {
      const root='/api/v1/commerce-batches/'+encodeURIComponent(batch)+'/execution';
      const [run,artifacts,limits]=await Promise.all([get(root),get(root+'/artifacts'),get(root+'/limits')]);
      fs.writeFileSync(path.join(out,'status.json'),JSON.stringify({run,limits,artifacts},null,2));
      for(const artifact of artifacts)if(artifact.imageUrl&&!saved.has(artifact.id)){
        const url=new URL(artifact.imageUrl,base);if(url.origin!==base)throw Error('Unexpected artifact origin');
        const image=await fetch(url,{headers:{Cookie:cookie},signal:AbortSignal.timeout(15000)});
        if(!image.ok)continue;
        const type=image.headers.get('content-type')||'',extension=type.includes('jpeg')?'jpg':type.includes('webp')?'webp':'png';
        const file=path.join(out,artifact.kind.toLowerCase()+'-'+artifact.variant+'.'+extension);
        fs.writeFileSync(file,Buffer.from(await image.arrayBuffer()));saved.add(artifact.id);console.log('SAVED '+file);
      }
      const current={stage:run.stage,status:run.status,message:run.message,calls:limits.reservedCalls,artifacts:artifacts.map(a=>({kind:a.kind,variant:a.variant,status:a.status}))};
      const next=JSON.stringify(current);if(next!==signature){signature=next;console.log(new Date().toISOString()+' '+next);}
      if(['COMPLETED','UNKNOWN','WAITING_INPUT','WAITING_CAPABILITY','PAUSED'].includes(run.status))return;
    }catch(error){console.log('Observation failed: '+error.message);}
    await new Promise(resolve=>setTimeout(resolve,10000));
  }
  throw Error('Observation deadline reached; this does not cancel the backend execution');
})().catch(error=>{console.error(error.message);process.exitCode=1;});
