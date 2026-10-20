// A visual companion to persisted execution state. It never advances or submits a job.
(() => {
  const page = document.getElementById('product-publish-page');
  const journey = document.querySelector('.studio-journey');
  if (!page || !journey) return;
  const create = (tag, className, parent, text) => {
    const node = document.createElement(tag);
    node.className = className;
    if (text) node.textContent = text;
    if (parent) parent.append(node);
    return node;
  };
  const hero = create('section', 'studio-live-stage');
  hero.hidden = true;
  hero.setAttribute('aria-label', '当前批次实时创作');
  const art = create('div', 'studio-live-art', hero);
  art.setAttribute('aria-hidden', 'true');
  create('div', 'studio-live-orbit orbit-one', art);
  create('div', 'studio-live-orbit orbit-two', art);
  const particles = create('div', 'studio-live-particles', art);
  for (let i = 0; i < 8; i++) create('i', '', particles);
  const imageFrame = create('div', 'studio-live-image', art);
  const echoes = [0,1,2].map(i => { const frame=create('div', `studio-echo echo-${i}`, art); const img=create('img','',frame);img.alt='';return img; });
  const colorLight=create('div','studio-color-light',art);
  const colorBridge=create('div','studio-color-bridge',art);
  for(let i=0;i<3;i++)create('i','',colorBridge);
  const colorIntro=create('div','studio-color-intro',hero);
  create('span','studio-color-eyebrow',colorIntro,'COLOR STUDY / 商品配色');
  const colorTitle=create('h3','studio-color-title',colorIntro,'让配色，逐一就位');
  const colorDeck=create('section','studio-color-deck',hero);colorDeck.setAttribute('aria-label','商品配色准备进度');
  const colorDeckHead=create('div','studio-color-deck-head',colorDeck);
  create('span','',colorDeckHead,'原商品配色');
  const colorCount=create('strong','studio-color-count',colorDeckHead);
  const colorGrid=create('div','studio-color-grid',colorDeck);colorGrid.tabIndex=0;
  const colorNote=create('p','studio-color-note',colorDeck);colorNote.setAttribute('role','status');
  const colorFlights=create('div','studio-color-flights',hero);colorFlights.setAttribute('aria-hidden','true');
  const colorCards=new Map();let colorSources=[];
  const motionCaption=create('span','studio-motion-caption',hero);
  let image = null, imageUrl = '', transformed = null, transformedUrl = '', transformedLabel = '', transformedView = 'source', sourceId = null, previews = [];
  imageFrame.dataset.empty = 'true';
  create('div', 'studio-live-scan', art);
  const origin = create('span', 'studio-live-origin', art, '来源商品');
  const copy = create('div', 'studio-live-copy', hero);
  const eyebrow = create('div', 'studio-live-eyebrow', copy);
  create('i', 'studio-live-dot', eyebrow);
  create('span', '', eyebrow, 'CREATIVE STUDIO / 实时创作');
  const title = create('h2', 'studio-live-title', copy);
  const description = create('p', 'studio-live-description', copy);
  const meta = create('div', 'studio-live-meta', copy);
  const stageLabel = create('span', 'studio-live-stage-label', meta);
  const count = create('span', 'studio-live-count', meta);
  const track = create('div', 'studio-live-track', hero);
  track.setAttribute('aria-label', '后台当前环节');
  const flow = ['extract', 'design', 'mockup', 'publish'];
  const flowLabels = ['提取印花', '印花裂变', '商品套版', '上传草稿'];
  const segments = flow.map((step, i) => {
    const segment = create('span', '', track, `0${i + 1} ${flowLabels[i]}`);
    segment.dataset.flowStep = step;
    return segment;
  });
  const toggle = create('button', 'studio-motion-toggle', hero, '暂停动效');
  toggle.type = 'button';
  toggle.hidden = true;
  toggle.setAttribute('aria-label', '暂停装饰动效，任务继续执行');
  toggle.setAttribute('aria-pressed', 'false');
  journey.append(hero);
  const drapeStatus=create('div','studio-drape-status',hero);drapeStatus.hidden=true;drapeStatus.setAttribute('role','status');
  const drapeTitle=create('strong','studio-drape-title',drapeStatus),drapeDescription=create('p','studio-drape-description',drapeStatus),drapeProgress=create('span','studio-drape-progress',drapeStatus);
  const drapeSteps=create('div','studio-drape-steps',hero);drapeSteps.hidden=true;drapeSteps.setAttribute('aria-label','自然搭放真实处理阶段');
  const drapeSegments=[['precise','精确套版'],['master','一次搭放母图'],['register','印花定位'],['reuse','本地换色换花']].map(([phase,label],index)=>{const node=create('span','studio-drape-step',drapeSteps,`0${index+1} ${label}`);node.dataset.phase=phase;return node;});
  const drapeFlow=create('div','studio-drape-flow',art);drapeFlow.hidden=true;drapeFlow.setAttribute('aria-hidden','true');
  for(const side of ['left','right']){const channel=create('div',`studio-drape-channel channel-${side}`,drapeFlow);for(let i=0;i<3;i++)create('i','',channel);}
  const drapeProjection=create('div','studio-drape-projection',art);drapeProjection.hidden=true;drapeProjection.setAttribute('aria-hidden','true');for(let i=0;i<4;i++)create('i','',drapeProjection);
  const designFx=create('div','studio-design-fx',hero);designFx.hidden=true;designFx.setAttribute('aria-hidden','true');
  const designHalo=create('div','studio-design-halo',designFx);
  for(let i=0;i<3;i++)create('i','',designHalo);
  const designSheets=create('div','studio-design-sheets',designFx);
  for(let i=0;i<3;i++)create('i','',designSheets);
  const designStream=create('div','studio-design-stream',designFx);
  for(let i=0;i<7;i++){const line=create('i','',designStream);line.style?.setProperty('--line',String(i));}
  const designStatus=create('div','studio-design-status',hero);designStatus.hidden=true;
  create('span','studio-design-kicker',designStatus,'PRINT LAB / 印花创作');
  const designMessage=create('strong','',designStatus);
  create('span','studio-design-hint',designStatus,'提取画风 · 重组元素 · 保留透明画布');
  const publishFx=create('div','studio-publish-fx',hero);publishFx.hidden=true;
  const publishRail=create('div','studio-publish-rail',publishFx);publishRail.setAttribute('aria-hidden','true');
  for(let i=0;i<3;i++)create('i','',publishRail);
  const storefront=create('div','studio-storefront',publishFx);
  create('div','studio-store-canopy',storefront);
  create('span','studio-store-label',storefront,'店铺草稿');
  const storeSlots=create('div','studio-store-slots',storefront);
  const storeImages=[0,1,2].map(()=>{const slot=create('div','studio-store-slot',storeSlots),img=create('img','',slot);img.alt='';return img;});
  const storeReceipt=create('span','studio-store-receipt',storefront);
  const publishCopy=create('div','studio-publish-copy',publishFx);
  create('span','studio-design-kicker',publishCopy,'COLLECTION → SHOP');
  const publishTitle=create('strong','',publishCopy);
  const publishHint=create('span','',publishCopy);
  const handoff=create('div','studio-print-handoff',hero);handoff.hidden=true;handoff.setAttribute('aria-hidden','true');
  const handoffImage=create('img','',handoff);handoffImage.alt='';
  const handoffCaption=create('span','studio-handoff-caption',hero,'原印花就位，展开新的创作');handoffCaption.hidden=true;
  function clearHandoff(){handoff.hidden=handoffCaption.hidden=true;hero.dataset.printHandoff='false';}
  handoff.addEventListener('animationend',clearHandoff);handoffImage.addEventListener('error',clearHandoff);
  function transferPrint(){
    const extracted=previews.find(row=>row.captureId===sourceId&&row.kind==='EXTRACT'&&row.status==='READY'&&row.imageUrl);
    if(!extracted)return;
    handoffImage.src=extracted.imageUrl;handoff.hidden=handoffCaption.hidden=false;hero.dataset.printHandoff='true';
  }
  let photonFrame=0,photonCanvas=null;
  function stopPhotons(){
    if(photonFrame)window.cancelAnimationFrame?.(photonFrame);
    photonFrame=0;photonCanvas?.remove();photonCanvas=null;imageFrame.dataset.photons='false';
  }
  function formPrintWithPhotons(img){
    stopPhotons();
    if(!window.requestAnimationFrame||document.hidden||window.matchMedia?.('(prefers-reduced-motion: reduce)').matches)return;
    const width=imageFrame.clientWidth-14,height=imageFrame.clientHeight-14;
    if(width<1||height<1||!img.naturalWidth)return;
    const sample=document.createElement('canvas');sample.width=width;sample.height=height;
    const ctx=sample.getContext?.('2d',{willReadFrequently:true});if(!ctx)return;
    const scale=Math.min(width/img.naturalWidth,height/img.naturalHeight),iw=img.naturalWidth*scale,ih=img.naturalHeight*scale;
    let pixels;try{ctx.drawImage(img,(width-iw)/2,(height-ih)/2,iw,ih);pixels=ctx.getImageData(0,0,width,height).data;}catch(_){return;}
    const dots=[],spacing=Math.max(3,Math.ceil(Math.sqrt(width*height/1300)));
    for(let y=0;y<height;y+=spacing)for(let x=0;x<width;x+=spacing){
      const i=(y*width+x)*4;if(pixels[i+3]<36)continue;
      const n=dots.length,angle=n*2.39996,radius=Math.max(width,height)*(.5+(n%11)/16);
      dots.push({x,y,sx:width/2+Math.cos(angle)*radius,sy:height/2+Math.sin(angle)*radius,delay:(n%17)/90,color:`rgb(${pixels[i]},${pixels[i+1]},${pixels[i+2]})`,alpha:pixels[i+3]/255});
    }
    if(!dots.length)return;
    const canvas=create('canvas','studio-photon-canvas',imageFrame);canvas.width=width;canvas.height=height;canvas.setAttribute('aria-hidden','true');
    const paint=canvas.getContext('2d');photonCanvas=canvas;imageFrame.dataset.photons='true';let start;
    function draw(time){
      if(photonCanvas!==canvas||transformed!==img)return;
      start??=time;const progress=Math.min(1,(time-start)/2400);paint.clearRect(0,0,width,height);
      for(const dot of dots){
        const t=Math.min(1,Math.max(0,(progress-dot.delay)/(1-dot.delay))),ease=1-Math.pow(1-t,3);
        const x=dot.sx+(dot.x-dot.sx)*ease,y=dot.sy+(dot.y-dot.sy)*ease;
        const alpha=dot.alpha*Math.min(1,t*5+.15),radius=Math.max(.7,spacing*(.2+.4*ease));
        paint.fillStyle=dot.color;paint.globalAlpha=alpha*.14;
        paint.beginPath();paint.arc(x,y,radius*2.5,0,Math.PI*2);paint.fill();
        paint.globalAlpha=alpha;paint.beginPath();paint.arc(x,y,radius,0,Math.PI*2);paint.fill();
        if(t<.8){paint.globalAlpha=alpha*(1-t)*.8;paint.fillStyle='#fff';paint.beginPath();paint.arc(x,y,radius*.4,0,Math.PI*2);paint.fill();}
      }
      if(progress>.84){paint.globalAlpha=(progress-.84)/.16;paint.fillStyle='#f4f4f1';paint.fillRect(0,0,width,height);paint.drawImage(img,(width-iw)/2,(height-ih)/2,iw,ih);}
      if(progress<1)photonFrame=window.requestAnimationFrame(draw);else stopPhotons();
    }
    photonFrame=window.requestAnimationFrame(draw);
  }

  let batchId = null, run = null, creativeOnly = false, connection = true, userPaused = false, renderPolicy = null;
  const localPhoto=()=>['LOCAL_PHOTO_V1','GROK_SCENE_V1','GROK_DRAPE_REUSE_V1'].includes(renderPolicy);
  const sceneBackground=()=>renderPolicy==='GROK_SCENE_V1';
  const drapeReuse=()=>renderPolicy==='GROK_DRAPE_REUSE_V1';
  const productOutput=row=>drapeReuse()?row.kind==='DRAPED_FRONT':row.kind.startsWith('MOCKUP');
  const drapeMaster=()=>[...previews].reverse().find(row=>row.kind==='DRAPE_MASTER'&&row.captureId===sourceId)||(!sourceId?[...previews].reverse().find(row=>row.kind==='DRAPE_MASTER'):null);
  const sceneJob=()=>[...previews].reverse().find(row=>row.kind==='SCENE_BACKGROUND'&&row.captureId===sourceId)||(!sourceId?[...previews].reverse().find(row=>row.kind==='SCENE_BACKGROUND'):null);
  const scenePending=()=>sceneBackground()&&['DETECT','EXTRACT','MOCKUP'].includes(run?.stage)&&!!sceneJob()&&sceneJob().status!=='READY';
  const isMatte=row=>row.kind==='MATTE'||/^MATTE_G[1-9][0-9]*$/.test(row.kind);
  const readyImage=row=>row?.status==='READY'&&typeof row.imageUrl==='string'&&row.imageUrl;
  function drapeState(){
    const rows=sourceId?previews.filter(row=>row.captureId===sourceId):[],preciseJobs=rows.filter(row=>row.kind==='MOCKUP_FRONT'),outputs=rows.filter(row=>row.kind==='DRAPED_FRONT'&&row.status==='READY');
    const firstColor=colorSources.find(source=>source.captureId===sourceId)?.colors?.[0]?.key;
    const precise=preciseJobs.find(row=>readyImage(row)&&row.variant===1&&(!firstColor||row.colorKey===firstColor))||preciseJobs.find(readyImage);
    const master=sourceId?drapeMaster():null,blank=[...rows].reverse().find(row=>row.kind==='DRAPE_BLANK');
    const phase=run?.stage!=='MOCKUP'?'result':master?.status==='READY'?(blank?.status==='READY'||outputs.length?'reuse':'register'):master||preciseJobs.some(row=>row.status==='READY')?'master':'precise';
    const stopped=row=>row?.status==='UNKNOWN'||/REJECTED|FAILED/.test(row?.status||'');
    return {rows,preciseJobs,precise,master,blank,outputs,phase,blocked:stopped(master)||stopped(blank)};
  }
  const stages = {CAPTURE:'来源校验', DETECT:'提取前检查', EXTRACT:'提取印花', VARIATE:'印花裂变', MOCKUP:'商品套版', REVIEW:'自动检查', PUBLISH:'上传草稿'};
  const steps = {CAPTURE:'extract', DETECT:'extract', EXTRACT:'extract', VARIATE:'design', MOCKUP:'mockup', REVIEW:'mockup', PUBLISH:'publish'};
  const activeCopy = {
    CAPTURE:['正在读取商品灵感', '核对来源图片和保存的商品资料。'],
    DETECT:['为创作做好准备', '检查图片与商品信息，准备提取原始印花。'],
    EXTRACT:['让原始印花独立成形', '从商品原图中提取图案，保留后续裂变的风格依据。'],
    VARIATE:['让灵感长出新模样', '沿用原始印花的画风，创作这一批新的图案。'],
    MOCKUP:['让图案落在恰好的位置', '按模板中的印花位置，把新图案呈现在商品上。'],
    REVIEW:['正在自动检查这一批作品', '检查印花位置与图集结果，保留每一步生成的图片。'],
    PUBLISH:['正在上传店铺草稿', '按当前批次选择的店铺提交草稿，同步平台返回的上传结果。']
  };
  function colorState(){
    const selected=sourceId||colorSources[0]?.captureId||previews.find(row=>row.kind==='BASE_COLOR')?.captureId;
    const rows=previews.filter(row=>row.captureId===selected),jobs=rows.filter(row=>row.kind==='BASE_COLOR');
    const colors=new Map();
    const expected=colorSources.find(source=>source.captureId===selected)?.colors||[];
    for(const color of expected){
      if(typeof color.key==='string'&&color.key&&typeof color.label==='string')colors.set(color.key,{key:color.key,label:color.label});
    }
    for(const job of jobs){
      const key=job.colorKey||job.colorLabel||job.id;
      const existing=colors.get(key)||[...colors.values()].find(color=>color.label===job.colorLabel);
      const actual=existing?.key||key;
      colors.set(actual,{...existing,key:actual,label:existing?.label||job.colorLabel||'原色',job});
    }
    const entries=[...colors.values()],ready=entries.filter(color=>color.job?.status==='READY').length;
    const printStarted=rows.some(row=>row.kind==='EXTRACT'||row.kind.startsWith('VARIATE')||row.kind.startsWith('MOCKUP')||isMatte(row)||row.kind.startsWith('DRAPE'));
    const eligible=localPhoto()&&!scenePending()&&['DETECT','EXTRACT','MOCKUP'].includes(run?.stage)&&entries.length>0;
    const complete=expected.length>0&&ready===entries.length;
    const visible=eligible&&(!printStarted||(expected.length>0? !complete:jobs.some(job=>job.status!=='READY')));
    return {selected,entries,ready,visible,complete,known:expected.length>0};
  }
  function renderColors(state){
    colorIntro.hidden=colorDeck.hidden=colorFlights.hidden=!state.visible;
    colorLight.hidden=colorBridge.hidden=!state.visible;
    if(!state.visible)return;
    hero.dataset.scene='colors';hero.dataset.colorsReady=String(state.complete);
    colorTitle.textContent=state.complete?'配色已就位，接续创作':['FAILED','UNKNOWN','WAITING_INPUT','WAITING_CAPABILITY','STALE'].includes(hero.dataset.status)?'配色保留，等待继续':'让配色，逐一就位';
    colorCount.textContent=state.known?`${state.ready} / ${state.entries.length}`:`已就绪 ${state.ready} 色`;
    colorNote.textContent=state.complete?'所有颜色底图已准备完成':hero.dataset.status==='RUNNING'?`已准备 ${state.ready} 色 · ${state.known?'按原商品颜色逐一展开':'正在同步完整颜色列表'}`:hero.dataset.status==='FAILED'?'颜色准备已停止 · 已完成颜色保留':hero.dataset.status==='STALE'?'正在同步后台状态 · 已完成颜色保留':`已准备 ${state.ready} 色 · 等待任务继续`;
    motionCaption.textContent='固定模板 → 已配置配色 → 每色一张底图';
    const present=new Set();
    state.entries.forEach((color,index)=>{
      const key=JSON.stringify([state.selected,color.key]);present.add(key);
      let entry=colorCards.get(key);
      if(!entry){
        const card=create('article','studio-color-card',colorGrid),swatch=create('div','studio-color-swatch',card);
        const thumbnail=create('img','studio-color-thumbnail',swatch);thumbnail.alt='';thumbnail.decoding='async';
        const token=create('i','studio-color-token',colorFlights),check=create('i','studio-color-check',swatch);check.setAttribute('aria-hidden','true');
        const label=create('strong','studio-color-label',card),status=create('span','studio-color-status',card);
        entry={card,swatch,thumbnail,label,status,token,url:''};colorCards.set(key,entry);
        thumbnail.addEventListener('load',()=>{if(entry.url===thumbnail.getAttribute?.('src'))card.dataset.imageReady='true';});
        thumbnail.addEventListener('error',()=>{card.dataset.imageReady='false';});
      }
      const {card,swatch,thumbnail,label,status,token}=entry;const job=color.job;
      const current=job?.status==='READY'?'ready':['PREPARED','SUBMITTING','RESULT_RECEIVED','LOCAL_PROCESSING'].includes(job?.status)?'working':job?.status?'stopped':'waiting';
      const was=card.dataset.state;card.dataset.state=current;
      card.dataset.colorKey=color.key;label.textContent=color.label;
      status.textContent=current==='working'&&hero.dataset.status!=='RUNNING'?'等待继续':({ready:'已就绪',working:'正在准备',waiting:'等待准备',stopped:'待处理'})[current];
      card.setAttribute('aria-label',`${color.label} · ${status.textContent}`);
      const hex=/^#[0-9a-f]{6}$/i.test(job?.colorHex||'')?job.colorHex:null;
      card.dataset.sampled=String(!!hex);card.style?.setProperty('--sku-color',hex||'#9b9b98');
      token.dataset.sampled=String(!!hex);token.style?.setProperty('--sku-color',hex||'#9b9b98');
      card.style?.setProperty('--color-order',String(index%9));
      const url=current==='ready'&&typeof job.imageUrl==='string'?job.imageUrl:'';
      if(entry.url!==url){entry.url=url;card.dataset.imageReady='false';if(url)thumbnail.src=url;else thumbnail.removeAttribute('src');}
      if(token.style)token.style.backgroundImage=!hex&&url?`url(${JSON.stringify(url)})`:'';
      if(was!==current&&current==='ready'){
        const from=imageFrame.getBoundingClientRect?.(),to=swatch.getBoundingClientRect?.(),root=hero.getBoundingClientRect?.(),viewport=colorGrid.getBoundingClientRect?.();
        const visible=!!(from?.width&&to?.width&&root&&viewport&&to.top>=viewport.top&&to.bottom<=viewport.bottom&&to.left>=viewport.left&&to.right<=viewport.right);
        token.dataset.flight=String(visible&&!!(hex||url));
        if(visible){token.style.left=`${to.x+to.width/2-root.x}px`;token.style.top=`${to.y+to.height/2-root.y}px`;token.style.setProperty('--flight-x',`${from.x+from.width/2-to.x-to.width/2}px`);token.style.setProperty('--flight-y',`${from.y+from.height/2-to.y-to.height/2}px`);}
      }
      token.dataset.state=current;
    });
    colorCards.forEach((entry,key)=>{if(!present.has(key)){entry.card.remove();entry.token.remove();colorCards.delete(key);}});
  }
  function showTransformation() {
    if (!run || !sourceId) return;
    const rows = previews.filter(row => row.captureId === sourceId);
    const drape=drapeReuse()?drapeState():null;
    let chosen = null, view = 'source', label = '来源商品';
    if(scenePending()){chosen=null;view='source';label='商品照片 · 场景准备';}
    else if (['MOCKUP','REVIEW','PUBLISH'].includes(run.stage)) {
      const jobs = rows.filter(productOutput);
      {
        chosen = [...jobs].reverse().find(row => row.status === 'READY' && row.imageUrl);
        if (chosen) { view = 'result'; label = `${drapeReuse()?'自然搭放主图':localPhoto()?'本地套版主图':'已生成效果图'}${chosen.colorLabel ? ' · ' + chosen.colorLabel : ''}`; }
        else if(drapeReuse()&&drapeMaster()?.status==='READY'&&drapeMaster().imageUrl){chosen=drapeMaster();view='placement';label='搭放母图 · 多款多色共用';}
        else if(drape?.precise){chosen=drape.precise;view='placement';label='精确套版 · 搭放前参考';}
        else if(drape&&rows.some(row=>row.kind==='BASE_COLOR'&&readyImage(row))){chosen=rows.find(row=>row.kind==='BASE_COLOR'&&readyImage(row));view='placement';label='颜色底图 · 等待精确套版';}
        else if(localPhoto()) {
          chosen=[...rows].reverse().find(row=>row.status==='READY'&&row.imageUrl&&isMatte(row));
          if(chosen){view='artwork';label='透明印花母版 · 非商品成品';}
          else {chosen=[...rows].reverse().find(row=>row.status==='READY'&&row.imageUrl&&row.kind==='BASE_COLOR');if(chosen){view='placement';label='复用底图 · 尚未套版';}
            else if(sceneBackground()&&sceneJob()?.status==='READY'&&sceneJob().imageUrl){chosen=sceneJob();view='placement';label='本商品共用背景 · 非商品成品';}}
        }
      }
    } else if (run.stage === 'VARIATE' || run.stage === 'EXTRACT') {
      chosen = [...rows].reverse().find(row => row.status === 'READY' && row.imageUrl && (run.stage === 'VARIATE' ? row.kind.startsWith('VARIATE') : row.kind === 'EXTRACT'));
      if (chosen) { view = 'artwork'; label = run.stage === 'VARIATE' ? (localPhoto()?'印花原稿 · 非商品成品':'已生成独立印花') : '已提取独立印花'; }
    }
    if(hero.dataset.scene==='colors'){chosen=null;view='source';label='来源商品';}
    const url = chosen?.imageUrl || '';
    const extracted=rows.find(row=>row.kind==='EXTRACT'&&row.status==='READY'&&row.imageUrl);
    const anchor=drape&&chosen?.kind!=='DRAPED_FRONT'?drape.precise:chosen;
    const base=rows.find(row=>row.kind==='BASE_COLOR'&&readyImage(row)&&(anchor?.colorKey?row.colorKey===anchor.colorKey:row.colorLabel===anchor?.colorLabel))||(drape?rows.find(row=>row.kind==='BASE_COLOR'&&readyImage(row)):null);
    const matte=rows.find(row=>isMatte(row)&&readyImage(row)&&row.variant===anchor?.variant)||(drape?rows.find(row=>isMatte(row)&&readyImage(row)):null);
    const other=rows.find(row=>row.kind.startsWith('VARIATE')&&row.status==='READY'&&row.id!==chosen?.id&&row.imageUrl);
    const scene=hero.dataset.scene;
    const drapeBase=readyImage(drape?.blank)?drape.blank:readyImage(drape?.master)?drape.master:base||drape?.precise;
    const input=scene==='design'?extracted?.imageUrl||imageUrl:scene==='mockup'||scene==='result'?(drape?drapeBase?.imageUrl:base?.imageUrl)||imageUrl:imageUrl;
    if(image && image.getAttribute?.('src')!==input)image.src=input;
    const drapeInput=drape?.phase==='register'?drape.precise||base:drape?.phase==='reuse'?drapeBase:base;
    const sources=scene==='extract'?[url?imageUrl:'','','']:scene==='design'?[extracted?.imageUrl||'',other?.imageUrl||'','']:
      scene==='mockup'?[drape?drapeInput?.imageUrl||'':base?.imageUrl||'',matte?.imageUrl||'','']:['','',''];
    if(drape){echoes[0].parentElement.dataset.caption=drape.phase==='register'&&drape.precise?'精确套版参考':drape.phase==='reuse'?'共用搭放底图':'对应颜色底图';echoes[1].parentElement.dataset.caption='本款透明印花';}
    sources.forEach((src,i)=>{echoes[i].parentElement.hidden=!src;if(src&&echoes[i].getAttribute?.('src')!==src)echoes[i].src=src;});
    if(hero.dataset.scene==='colors')echoes.forEach(img=>img.parentElement.hidden=true);
    transformedView=view;transformedLabel=label;
    if (url === transformedUrl){if(imageFrame.dataset.previewReady==='true'){hero.dataset.view=view;origin.textContent=label;}else if(!url)origin.textContent=label;return;}
    stopPhotons();transformed?.remove(); transformed = null; transformedUrl = url;
    imageFrame.dataset.previewReady = 'false';
    hero.dataset.view = 'source';
    origin.textContent = '来源商品';
    if (!url){if(scenePending())origin.textContent='商品照片 · 场景准备';return;}
    const next = create('img', 'studio-live-transformation', imageFrame);
    transformed = next; next.alt = ''; next.decoding = 'async';
    next.addEventListener('load', () => {
      if (transformed !== next) return;
      imageFrame.dataset.empty = 'false';
      imageFrame.dataset.previewReady = 'true';
      hero.dataset.view = transformedView; origin.textContent = transformedLabel;
      if(run?.stage==='VARIATE'&&chosen?.kind?.startsWith('VARIATE'))formPrintWithPhotons(next);
    });
    next.addEventListener('error', () => {
      if (transformed !== next) return;
      next.remove(); transformed = null; transformedUrl = '';transformedLabel='';
      imageFrame.dataset.previewReady = 'false'; origin.textContent = '来源商品';
    });
    next.src = url;
  }
  function visibility() {
    const visible = !!batchId && !!run && !page.hidden && location.hash === '#commerce-designs';
    hero.hidden = !visible;
    if(document.hidden||!visible||!connection)stopPhotons();
    page.dataset.motionPaused = String(userPaused || document.hidden || !visible || !connection);
  }
  function render() {
    const state = run ? (connection ? run.status : 'STALE') : '';
    hero.dataset.status = state;
    hero.dataset.stage = run?.stage || '';
    hero.dataset.replay = 'false';hero.dataset.sceneBackground=String(!!scenePending());
    hero.dataset.scene = ({EXTRACT:'extract',VARIATE:'design',MOCKUP:'mockup',REVIEW:'result',PUBLISH:'result'})[run?.stage]||'extract';
    drapeStatus.hidden=drapeSteps.hidden=drapeFlow.hidden=drapeProjection.hidden=true;hero.dataset.drapeWorking='false';delete hero.dataset.drapePhase;
    motionCaption.textContent=run?.stage==='REVIEW'?'合成完成':({EXTRACT:'商品 → 独立印花',VARIATE:'原印花 → 新款印花',MOCKUP:'颜色底图 + 新印花 → 商品成品',PUBLISH:'商品成品 → 店铺草稿'})[run?.stage]||'准备素材';
    page.dataset.executionStatus = state;
    if (!run) { visibility(); return; }
    const stateCopy = {
      QUEUED:['创作即将开始', '任务已进入队列，等待后台推进。'],
      PAUSING:['正在等待安全暂停', '当前请求结束后暂停，已生成的结果会保留。'],
      PAUSED:['灵感暂歇，作品保留', '任务已暂停，可以从当前进度继续。'],
      WAITING_INPUT:['还差一步，等你处理', '请根据下方执行说明补充信息后继续。'],
      WAITING_CAPABILITY:['等待所需能力就绪', '请查看下方执行说明，完成配置后再继续。'],
      UNKNOWN:['这一步的结果需要核查', '尚未确认生成结果，请核对已存结果后恢复。'],
      FAILED:['本次创作已停止', '停止原因已列在下方，已归档的结果会保留。'],
      COMPLETED:['这一批灵感，已成形', '创作流程已完成，请在下方图集中检查实际效果。'],
      STALE:['正在重新同步状态', '暂时未读取到最新进度，后台任务状态待确认。']
    };
    const localCopy={
      VARIATE:['正在创作本款印花', '沿用来源画风生成独立印花原稿，同组位置和不同颜色共用后续透明母版。'],
      MOCKUP:['正在完成本地套版', '准备透明母版与各色固定底图，按已保存的位置合成每色每款一张主图。'],
      REVIEW:['正在自动检查本地套版结果', '检查透明母版、印花位置与各色主图；自动检查通过后继续上传草稿。']
    };
    const queuedCopy={
      DETECT:['准备检查来源商品', '任务已进入资料检查环节，等待后台继续。'],
      EXTRACT:['印花提取等待继续', '已保留当前素材，等待后台继续提取。'],
      VARIATE:['印花裂变等待继续', '已保留当前印花，等待后台继续生成未完成的款式。'],
      MOCKUP:['商品套版等待继续', '已保留当前图片，等待后台继续合成未完成的商品图。'],
      REVIEW:['准备自动检查结果', '套版结果已保存，等待后台继续自动检查。'],
      PUBLISH:['正在同步草稿上传进度', '商品图已完成，继续处理未完成款式并同步店铺回执。']
    };
    let text = state === 'RUNNING' ? (localPhoto()?localCopy[run.stage]:null)||activeCopy[run.stage] || ['正在处理当前环节', '处理进度以后台返回的结果为准。']
      : state === 'QUEUED' ? queuedCopy[run.stage]||stateCopy.QUEUED : stateCopy[state] || ['等待确认任务状态', '请查看下方的最新执行说明。'];
    if(run.stage==='PUBLISH'){
      if(state==='COMPLETED')text=['本批草稿已上传',run.message||'全部款式已取得平台回执，可在目标店铺草稿中查看。'];
      else if(['QUEUED','RUNNING'].includes(state)&&run.message)text=[text[0],run.message];
    }
    title.textContent = text[0];
    description.textContent = text[1];
    stageLabel.textContent = `${state === 'COMPLETED' ? '已结束于' : '当前环节'} · ${localPhoto()&&run.stage==='MOCKUP'?'本地套版':stages[run.stage] || run.stage}`;
    segments[2].textContent=drapeReuse()?'03 自然搭放':localPhoto()?'03 本地套版':'03 商品套版';
    const active = flow.indexOf(run.stage === 'REVIEW' && creativeOnly ? 'publish' : steps[run.stage]);
    segments.forEach((node, i) => {
      node.classList.toggle('is-active', i === active);
      node.classList.toggle('is-passed', i < active);
      if (i === active) node.setAttribute('aria-current', 'step');
      else node.removeAttribute('aria-current');
    });
    const colors=colorState();renderColors(colors);
    const publishing=run?.stage==='PUBLISH',published=publishing&&state==='COMPLETED';
    publishFx.hidden=!publishing;hero.dataset.publishWorking=String(publishing&&state==='RUNNING');hero.dataset.publishComplete=String(published);
    if(publishing){
      publishTitle.textContent=published?'作品已存入店铺草稿':state==='RUNNING'?'把这一批作品，送入店铺':'商品图集已保留';
      publishHint.textContent=published?'已收到平台商品编号':state==='RUNNING'?'整理商品图集 · 上传商品资料 · 等待平台回执':'等待后台继续处理';
      storeReceipt.textContent=published?'✓ 草稿已保存':'等待平台确认';
      const outputs=previews.filter(row=>productOutput(row)&&row.status==='READY'&&row.imageUrl).slice(-3);
      storeImages.forEach((img,i)=>{const url=published?outputs[i]?.imageUrl:null;img.hidden=!url;if(url&&img.getAttribute?.('src')!==url)img.src=url;});
    }
    const designing=run?.stage==='VARIATE';
    designFx.hidden=designStatus.hidden=!designing;
    hero.dataset.designWorking=String(designing&&state==='RUNNING');
    if(designing){
      const completed=previews.filter(row=>row.captureId===sourceId&&row.kind.startsWith('VARIATE')&&row.status==='READY').length;
      designMessage.textContent=state==='RUNNING'?(completed?`已生成 ${completed} 款，继续创作中`:'正在酝酿新的图案'):state==='COMPLETED'?'本轮图案已生成':'当前素材已保留';
    }
    if(colors.visible){title.textContent=colorTitle.textContent;description.textContent=colorNote.textContent;}
    if(scenePending()){
      hero.dataset.scene='mockup';hero.dataset.phase='scene-background';motionCaption.textContent='商品图背景风格 → 全新场景背景 → 同商品多款多色共用';stageLabel.textContent='当前环节 · 商品底图 / 场景准备';
      if(sceneJob().status==='UNKNOWN'){title.textContent='场景背景结果待核查';description.textContent='Grok 背景请求结果尚未确认，正在保留原任务等待核对，不重复生成。';}
      else if(['FAILED','REJECTED','INVALID_IMAGE_REJECTED'].includes(sceneJob().status)){title.textContent='场景背景准备已停止';description.textContent='请查看后台停止原因。已保存素材保留，未继续合成商品图。';}
      else if(state==='RUNNING'&&['SUBMITTING','RESULT_RECEIVED'].includes(sceneJob().status)){title.textContent='Grok 正在设计本商品背景';description.textContent='提取商品背景风格 · 同商品多款多色共用。Grok 根据所选商品图的场景氛围、配色、材质与光线重新设计背景。每个来源商品分别准备一次，已有相同来源背景可复用。随后将各款印花与各色商品本地合成。';}
      else if(state==='RUNNING'||state==='QUEUED'){title.textContent='场景背景等待继续';description.textContent='已选定本次商品图作为背景风格参考；本商品的新背景准备一次，供其多款多色共用，等待后台确认后继续。';}
    }else{delete hero.dataset.phase;if(sceneBackground()&&run.stage==='MOCKUP'&&state==='RUNNING')description.textContent='提取商品背景风格 · 同商品多款多色共用。Grok 根据所选商品图的场景氛围、配色、材质与光线重新设计背景。每个来源商品分别准备一次，已有相同来源背景可复用。随后将透明母版按已保存位置本地贴合，每色每款一张商品图。';}
    hero.dataset.drapeReuse=String(drapeReuse());
    if(drapeReuse()&&!colors.visible&&['MOCKUP','REVIEW','PUBLISH'].includes(run.stage)){
      const {master,blank,preciseJobs,outputs,phase,blocked}=drapeState();
      hero.dataset.drapePhase=phase;
      const working=state==='RUNNING'&&!blocked&&run.stage==='MOCKUP'&&(phase!=='master'||['SUBMITTING','RESULT_RECEIVED'].includes(master?.status));
      hero.dataset.drapeWorking=String(working);drapeStatus.hidden=drapeSteps.hidden=false;drapeFlow.hidden=run.stage!=='MOCKUP';drapeProjection.hidden=phase!=='register';
      motionCaption.textContent='精确套版 → 一次搭放母图 → 印花定位 → 本地换色换花';
      if(run.stage==='MOCKUP'){
        stageLabel.textContent='当前环节 · '+({precise:'精确套版',master:'搭放母图',register:'印花定位',reuse:'本地换色换花'})[phase];
        if(['RUNNING','QUEUED'].includes(state)){
          title.textContent=phase==='precise'?(state==='RUNNING'?'按保存位置精确套版':'精确套版等待继续'):phase==='register'?(state==='RUNNING'?'正在定位母图上的印花':'母图已保存，等待印花定位'):phase==='reuse'?(state==='RUNNING'?'复用母图，生成各款各色':'本地换色换花等待继续'):working?'正在生成自然搭放母图':'搭放母图等待准备';
          description.textContent=phase==='precise'?'把对应颜色底图与透明印花按保存位置合成；中央显示已归档的套版参考。':phase==='register'?'比对精确套版与搭放母图，定位印花区域并准备共用底图；母图已归档，定位通过后才开始换色换花。':phase==='reuse'?'沿用已经定位的同一张搭放母图，在本地更换颜色与印花；每色每款一张主图，不再按款色调用场景模型。':'以已归档的精确套版为依据，准备一次 Grok 搭放母图；当前参考图会一直保留。';
        }
        if(master?.status==='UNKNOWN'){title.textContent='搭放母图结果待核查';description.textContent='请求结果尚未确认，已保留原任务，不重复生成母图。';}
        else if(['WAITING_INPUT','WAITING_CAPABILITY','FAILED','UNKNOWN'].includes(state)||blocked){
          title.textContent=phase==='register'?'搭放印花定位已停止':phase==='reuse'?'本地换色换花已停止':phase==='master'?'搭放母图准备已停止':'精确套版已停止';
          description.textContent=(run.message||'当前步骤未通过检查，请查看执行详情。')+' 已归档的套版与母图保留，未完成的商品图不会计为成品。';
        }
      }
      const complete=[preciseJobs.length>0&&preciseJobs.every(row=>row.status==='READY'),master?.status==='READY',blank?.status==='READY',phase==='result'&&outputs.length>0];
      const phaseIndex=['precise','master','register','reuse'].indexOf(phase);
      drapeSegments.forEach((node,index)=>{node.dataset.state=complete[index]?'done':index===phaseIndex?(working?'working':['RUNNING','QUEUED'].includes(state)&&!blocked?'waiting':'stopped'):'waiting';if(index===phaseIndex)node.setAttribute('aria-current','step');else node.removeAttribute('aria-current');});
      const currentProgress=`已归档：精确套版 ${preciseJobs.filter(row=>row.status==='READY').length} 张 · 搭放母图 ${master?.status==='READY'?1:0} 张 · 商品成品 ${outputs.length} 张`;
      if(drapeTitle.textContent!==title.textContent)drapeTitle.textContent=title.textContent;
      if(drapeDescription.textContent!==description.textContent)drapeDescription.textContent=description.textContent;
      if(drapeProgress.textContent!==currentProgress)drapeProgress.textContent=currentProgress;
    }
    showTransformation();
    visibility();
  }
  function clearSource() {
    stopPhotons();
    imageUrl = '';
    image = null;
    transformed = null; transformedUrl = '';transformedLabel='';transformedView='source';
    imageFrame.replaceChildren();
    imageFrame.dataset.empty = 'true';
    imageFrame.dataset.previewReady = 'false'; hero.dataset.view = 'source';
    origin.textContent = '来源商品';
  }
  function reset() {
    stopPhotons();
    clearHandoff();
    batchId = null; run = null; connection = true; sourceId = null; previews = [];renderPolicy=null;colorSources=[];
    colorCards.forEach(entry=>{entry.card.remove();entry.token.remove();});colorCards.clear();colorIntro.hidden=colorDeck.hidden=colorFlights.hidden=colorLight.hidden=colorBridge.hidden=true;
    count.textContent = '';
    echoes.forEach(img=>img.removeAttribute('src'));
    clearSource(); render();
  }
  toggle.addEventListener('click', () => {
    userPaused = !userPaused;
    toggle.textContent = userPaused ? '开启动效' : '暂停动效';
    toggle.setAttribute('aria-pressed', String(userPaused));
    toggle.setAttribute('aria-label', userPaused ? '开启装饰动效' : '暂停装饰动效，任务继续执行');
    visibility();
  });
  window.addEventListener('heyi:workbench-scope-reset', reset);
  window.addEventListener('heyi:company-reset', reset);
  window.addEventListener('heyi:workbench-batch-selected', event => {
    const detail = event.detail;
    if (batchId !== detail.batchId) { reset(); batchId = detail.batchId; }
    creativeOnly = !!detail.creativeOnly;
    segments[3].textContent = creativeOnly ? '04 结果检查' : '04 上传草稿';
    if (detail.execution?.batchId === batchId) run = detail.execution;
    render();
  });
  window.addEventListener('heyi:design-source-selected', event => {
    if (!batchId || !event.detail?.captureId) return;
    if(sourceId!==event.detail.captureId)clearHandoff();
    sourceId = event.detail.captureId;
    const demoSource=page.dataset.motionDemo==='true'&&typeof event.detail.sourceImageUrl==='string'&&event.detail.sourceImageUrl.startsWith('/assets/motion-demo/')?event.detail.sourceImageUrl:null;
    const url = demoSource || `/api/v1/commerce-batches/${encodeURIComponent(batchId)}/execution/artifacts/sources/${encodeURIComponent(event.detail.captureId)}/image`;
    if (url === imageUrl) return;
    clearSource(); imageUrl = url;
    const next = create('img', '', imageFrame);
    image = next;
    next.alt = '';
    next.decoding = 'async';
    next.addEventListener('load', () => { if (image === next) imageFrame.dataset.empty = 'false'; });
    next.addEventListener('error', () => {
      if (image !== next) return;
      if(imageFrame.dataset.previewReady!=='true'){imageFrame.dataset.empty = 'true'; origin.textContent = '来源图片暂未读取';}
    });
    next.src = url;
    echoes.forEach(img=>img.src=url);
    render();
  });
  window.addEventListener('heyi:execution-state', event => {
    const next = event.detail?.run;
    if (!batchId || (next?.batchId && next.batchId !== batchId)) return;
    const crossing=run?.stage==='EXTRACT'&&next?.stage==='VARIATE'&&['RUNNING','QUEUED'].includes(next.status);
    if(next?.stage!=='VARIATE'||!['RUNNING','QUEUED'].includes(next?.status))clearHandoff();
    run = next || null;
    connection = true;
    render();
    if(crossing)transferPrint();
  });
  window.addEventListener('heyi:execution-connection', event => {
    if (event.detail?.batchId !== batchId) return;
    connection = event.detail.available !== false;
    render();
  });
  window.addEventListener('heyi:artifacts-updated', event => {
    if (event.detail?.batchId !== batchId) return;
    if(Object.hasOwn(event.detail,'renderPolicy'))renderPolicy=event.detail.renderPolicy||null;
    const ready = event.detail.ready;
    count.textContent = Number.isInteger(ready) && ready >= 0 ? `已归档 ${ready} 张${localPhoto()?'商品主图':''}` : '';
    if(Array.isArray(event.detail.previews))previews=event.detail.previews;
    if(Array.isArray(event.detail.colorSources))colorSources=event.detail.colorSources;
    render();
  });
  window.addEventListener('heyi:creative-plan-state',event=>{
    if(event.detail?.batchId!==batchId||!event.detail.version)return;
    renderPolicy=event.detail.renderPolicy||null;render();
  });
  window.addEventListener('hashchange', visibility);
  document.addEventListener('visibilitychange', visibility);
  new MutationObserver(visibility).observe(page, {attributes:true, attributeFilter:['hidden']});
  const panel = document.getElementById('commerce-design-panel');
  if (panel) new MutationObserver(visibility).observe(panel, {attributes:true, attributeFilter:['hidden']});
  render();
})();
