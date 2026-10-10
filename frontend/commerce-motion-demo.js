// Isolated iframe: only static assets and visual events, never API/model/publication requests.
(() => {
  location.hash='commerce-designs';
  const batchId='static-motion-demo',captureId='demo-shirt',asset=name=>'/assets/motion-demo/'+name+'.png';
  const emit=(name,detail)=>window.dispatchEvent(new CustomEvent(name,{detail}));
  const palette=[['white','白色','#eeeeed'],['black','黑色','#252525'],['pink','粉色','#d8a8b5'],['apricot','杏色','#d7c5ac'],['grey','花灰','#aaa9a7'],['white2','米白','#e4e4e1']];
  const timers=[];
  const later=(ms,fn)=>timers.push(setTimeout(fn,ms));
  const stop=()=>{timers.forEach(clearTimeout);timers.length=0;};
  let rows=[];
  const run=(stage,status='RUNNING')=>emit('heyi:execution-state',{run:{batchId,stage,status}});
  const update=()=>emit('heyi:artifacts-updated',{batchId,renderPolicy:'LOCAL_PHOTO_V1',ready:0,colorSources:[{captureId,colors:palette.map(([key,label])=>({key,label}))}],previews:rows});
  const print=(kind,name,variant=0)=>({id:name,captureId,kind,variant,status:'READY',imageUrl:asset(name)});
  function colors(count){rows=palette.slice(0,Math.min(count+1,6)).map(([key,label,hex],i)=>({id:'base-'+key,captureId,kind:'BASE_COLOR',colorKey:key,colorLabel:label,status:i<count?'READY':'LOCAL_PROCESSING',colorHex:i<count?hex:null,imageUrl:i<count?asset(key):null}));update();}
  function phase(text){document.getElementById('demo-phase').textContent=text;}
  emit('heyi:workbench-batch-selected',{batchId});
  emit('heyi:design-source-selected',{captureId,sourceImageUrl:asset('source')});
  const step=new URLSearchParams(location.search).get('step')||'extract';
  if(step==='extract'||step==='check'){
    run('EXTRACT');colors(0);phase('01 原色提取 · 每个 SKU 颜色各准备一张底图');
    for(let n=1;n<=6;n++)later(n*450,()=>colors(n));
    later(3600,()=>{rows.push(print('EXTRACT','print-original'));update();phase('02 提取印花 · 从衣服中分离透明图案');});
  }else{
    colors(6);rows.push(print('EXTRACT','print-original'));
    if(step==='design'){
      run('EXTRACT');update();phase('03 印花裂变 · 保留画风，变化图案');
      later(120,()=>run('VARIATE'));
      later(2800,()=>{rows.push(print('VARIATE','print-variant',1));update();});
      later(5000,()=>{rows.push(print('VARIATE','print-variant-2',2));update();});
    }else{
      rows.push(print('VARIATE','print-variant',1),print('MATTE','print-variant',1));
      run(step==='publish'?'PUBLISH':'MOCKUP');update();
      phase(step==='publish'?'05 图集就绪 · 正式任务按配置上传店铺草稿':'04 商品套版 · 同款图案应用到原商品各颜色');
      const result=(color,name)=>{rows.push({...print('MOCKUP',name,1),colorKey:color,colorLabel:palette.find(p=>p[0]===color)[1]});update();};
      later(500,()=>result('black','result-black'));
      later(2300,()=>result('pink','result-pink'));
      if(step==='publish')later(3800,()=>{run('PUBLISH','COMPLETED');phase('演示完成 · 未创建商品或提交店铺');});
    }
  }
  window.addEventListener('message',event=>{if(event.source===parent&&event.origin===location.origin&&event.data?.type==='heyi:demo-stop'){stop();run('EXTRACT','PAUSED');}});
  document.addEventListener('visibilitychange',()=>{if(document.hidden)stop();});
  window.addEventListener('pagehide',stop);
  const reportHeight=()=>parent.postMessage({type:'heyi:demo-height',height:document.body.getBoundingClientRect().height},location.origin);
  new ResizeObserver(reportHeight).observe(document.body);reportHeight();
})();
