(() => {
  const journey = document.querySelector('.studio-journey');
  if (!journey) return;
  const status = document.getElementById('studio-journey-status');
  let execution=null,executionStageKey='',executionStatus='',manualStep=false,renderedStageKey=null,stageDirty=false;
  let currentBatch=null,checkRevision=0,creativeOnly=false;
  async function showDetection(){const ticket=++checkRevision,id=currentBatch;if(!id)return;const result=document.createElement('div');result.textContent='正在检查批次来源资料…';stageView.append(result);
    try{const response=await api(`/api/v1/commerce-batches/${encodeURIComponent(id)}/detection`);if(ticket!==checkRevision||!['check','extract'].includes(selectedStep)||currentBatch!==id)return;result.replaceChildren();
      response.data.items.forEach(item=>{const card=document.createElement('article');card.className='batch-detection-card';const title=document.createElement('h3'),summary=document.createElement('p');title.textContent=item.title;summary.textContent=`固定版本 v${item.version} · ${item.imageCount} 张图片 · ${item.skuCount} 个规格`;card.append(title,summary);const notes=item.issues.length?item.issues:['资料检查未发现缺失项；不代表图片质量或使用权审核通过'];notes.forEach(note=>{const p=document.createElement('p');p.textContent=note;card.append(p);});result.append(card);});
    }catch(error){if(ticket===checkRevision&&currentBatch===id&&['check','extract'].includes(selectedStep))result.textContent=error.message;}
  }
  let selectedStep='extract',tour=null,actualMode=false;
  const demo=()=>!actualMode&&!execution&&!document.getElementById('product-publish-page').classList.contains('has-workbench-batch');
  const guide=document.createElement('button');guide.type='button';guide.className='studio-demo-guide';guide.textContent='▶ 播放完整流程';journey.append(guide);
  function stopTour(){clearInterval(tour);tour=null;guide.textContent='▶ 播放完整流程';if(document.hidden||location.hash!=='#commerce-designs')stageView.querySelector('iframe')?.contentWindow?.postMessage({type:'heyi:demo-stop'},location.origin);}
  guide.addEventListener('click',()=>{if(tour){stopTour();return;}if(!demo())return;guide.textContent='Ⅱ 暂停演示';showStage('extract');tour=setInterval(()=>{if(!demo()||location.hash!=='#commerce-designs'||document.hidden){stopTour();return;}const sequence=['extract','design','mockup','publish'];showStage(sequence[(sequence.indexOf(selectedStep)+1)%4]);},6500);});
  const stageView=document.createElement('section');stageView.className='studio-stage-view';stageView.hidden=true;journey.after(stageView);
  const labels={check:['检测','下方为实时商品资料检查，资料齐全不代表图片质量或使用权审核通过。'],extract:['提取印花','按保存的原图提取独立印花，结果归档到下方批次图集。'],publish:['上架','仅创作批次到结果检查结束，不执行上架。发布批次需完成商品和店铺配置。']};
  labels.design=['印花裂变','以已归档的独立印花生成变体，每张结果保存在本批次图集中。'];
  labels.mockup=['商品套版','使用上传的底图与保存的印花位置；未上传底图时先去除原商品印花，再套用新图案。结果会进行位置变化检查，仍需业务复核。'];
  function showStage(step,{refresh=false}={}){
    selectedStep=step;
    if(refresh)stageDirty=true;
    const panel=document.getElementById('commerce-design-panel');
    panel.style.display=actualMode?'':'none';stageView.hidden=actualMode||location.hash!=='#commerce-designs';
    // Polling and hidden-attribute observers often report the same stage. Keep
    // its DOM (and running animations) alive until its content actually changes.
    if(location.hash!=='#commerce-designs')return;
    journey.querySelectorAll('[data-studio-step]').forEach(button=>{const active=button.dataset.studioStep===step;button.classList.toggle('is-current',active);if(active)button.setAttribute('aria-current','step');else button.removeAttribute('aria-current');});
    const key=JSON.stringify([currentBatch,step,actualMode,demo(),creativeOnly]);
    if(!stageDirty&&renderedStageKey===key)return;
    renderedStageKey=key;stageDirty=false;checkRevision++;
    window.dispatchEvent(new CustomEvent('heyi:studio-step-selected',{detail:{step,batchId:currentBatch}}));
    stageView.replaceChildren();if(labels[step]){const title=document.createElement('h2'),copy=document.createElement('p');title.textContent=labels[step][0];copy.textContent=labels[step][1];stageView.append(title,copy);}
    if(demo())renderDemo(step);
    else if(step==='check'||step==='extract')showDetection();
  }
  function renderDemo(step){
    stageView.replaceChildren();
    const badge=document.createElement('small');badge.className='demo-badge';badge.textContent='流程示例 · 预置素材演示，不消耗生图积分';
    const frame=document.createElement('iframe');frame.className='studio-motion-demo';
    frame.title='商品取色、透明印花裂变与套版动画示例';
    frame.src='/commerce-motion-demo.html?step='+encodeURIComponent(step);
    stageView.append(badge,frame);
  }
  window.addEventListener('message',event=>{
    const frame=stageView.querySelector('iframe');
    if(!frame||event.source!==frame.contentWindow||event.origin!==location.origin||event.data?.type!=='heyi:demo-height')return;
    const height=event.data.height;if(Number.isFinite(height)&&height>=300&&height<=1000)frame.style.height=`${Math.ceil(height)}px`;
  });
  journey.addEventListener('click',event=>{
    const button=event.target.closest('[data-studio-step]');if(!button||location.hash!=='#commerce-designs')return;
    event.preventDefault();event.stopImmediatePropagation();
    manualStep=true;
    status.textContent='正在查看所选环节；后台任务继续按原流程执行。';
    stopTour();
    actualMode=false;
    showStage(button.dataset.studioStep);
  },true);
  journey.querySelector('[data-studio-step="extract"]').disabled=false;
  function syncRecipeLock(){const form=document.querySelector('#commerce-design-workspace .studio-recipe form');if(form){form.inert=!!execution;form.setAttribute('aria-disabled',String(!!execution));}}
  window.addEventListener('heyi:execution-state',event=>{
    const next=event.detail?.run||null;
    if(next&&(!currentBatch||next.batchId!=null&&next.batchId!==currentBatch))return;
    if(event.detail?.batchId&&event.detail.batchId!==currentBatch)return;
    const nextKey=next?JSON.stringify([next.id||'',next.stage||'']):'';
    const firstRun=!!next&&!execution,stageChanged=!!next&&nextKey!==executionStageKey;
    const terminalArrived=!!next&&['COMPLETED','FAILED','CANCELLED'].includes(next.status)&&next.status!==executionStatus;
    execution=next;executionStageKey=nextKey;executionStatus=next?.status||'';
    if(execution)stopTour();guide.hidden=!demo();
    const mapping={CAPTURE:'extract',DETECT:'extract',EXTRACT:'extract',VARIATE:'design',MOCKUP:'mockup',REVIEW:creativeOnly?'publish':'mockup',PUBLISH:'publish'};
    journey.querySelectorAll('[data-studio-step]').forEach(button=>button.disabled=false);
    syncRecipeLock();
    if(execution&&!manualStep&&(firstRun||stageChanged))showStage(mapping[execution.stage]||'extract',{refresh:!firstRun||terminalArrived});
    else if(terminalArrived&&['check','extract'].includes(selectedStep))showStage(selectedStep,{refresh:true});
  });
  window.addEventListener('heyi:workbench-scope-reset',()=>{execution=null;executionStageKey='';executionStatus='';manualStep=false;currentBatch=null;creativeOnly=false;finalStepLabel();syncRecipeLock();checkRevision++;renderedStageKey=null;stageDirty=false;actualMode=false;stopTour();guide.hidden=false;showStage('extract');});
  guide.addEventListener('click',()=>{if(actualMode){actualMode=false;stopTour();showStage('extract');}});
  document.addEventListener('visibilitychange',()=>{if(document.hidden)stopTour();});
  function finalStepLabel(){const last=journey.querySelector('[data-studio-step="publish"]'),title=last.querySelector('b'),small=last.querySelector('small')||document.createElement('small');small.textContent=creativeOnly?'位置变化 · 图集归档':'规格价格 · 店铺发布';title.replaceChildren(document.createTextNode(creativeOnly?'结果检查':'上架'),small);labels.publish=creativeOnly?['结果检查','自动检查图片位置变化并保留图集，不执行上架。候选图片仍需业务复核。']:['上架','发布批次需完成商品和店铺配置，当前不代表已刊登。'];}
  window.addEventListener('heyi:workbench-batch-selected',event=>{
    const id=event.detail?.batchId;if(!id)return;
    const sameBatch=currentBatch===id,changedMode=creativeOnly!==!!event.detail.creativeOnly;
    creativeOnly=!!event.detail.creativeOnly;finalStepLabel();
    if(sameBatch){showStage(selectedStep,{refresh:changedMode});return;}
    currentBatch=id;execution=null;executionStageKey='';executionStatus='';manualStep=false;syncRecipeLock();checkRevision++;renderedStageKey=null;stageDirty=false;actualMode=false;stopTour();guide.hidden=true;showStage('extract');
  });
  const destinations = {'product-capture':'#nav-publish','commerce-designs':'[data-commerce-route="commerce-designs"]','commerce-batches':'[data-commerce-route="commerce-batches"]','commerce-templates':'[data-commerce-route="commerce-templates"]'};
  function sync() {
    const route = location.hash.slice(1);
    journey.hidden = !['product-capture','commerce-designs','commerce-batches'].includes(route);
    if(route!=='commerce-designs'){stageView.hidden=true;stopTour();}
    if(route==='commerce-designs'){showStage(selectedStep);return;}
    journey.querySelectorAll('[data-studio-step]').forEach(button => {
      const active = button.dataset.studioRoute === route;
      button.classList.toggle('is-current', active);
      if (active) button.setAttribute('aria-current','step'); else button.removeAttribute('aria-current');
    });
  }
  journey.querySelectorAll('[data-studio-route]').forEach(button => button.addEventListener('click', event => {
    event.preventDefault();
    if(button.dataset.studioRoute==='commerce-designs'&&document.getElementById('product-publish-page').classList.contains('has-workbench-batch')){window.dispatchEvent(new CustomEvent('heyi:workbench-design-request'));return;}
    status.textContent = '步骤导航不代表处理已完成，具体结果以商品与任务状态为准。';
    // Use the existing navigation permission and unsaved-source flow.
    if (button.dataset.studioRoute === 'commerce-designs' && location.hash === '#product-capture') {
      const editor = document.getElementById('product-capture-editor');
      const handoff = document.getElementById('design-current-product');
      if (!editor.hidden) {
        if (handoff.disabled) { status.textContent = '请先保存商品修改，或等待资料读取完成，再进入图片设计。'; return; }
        handoff.click(); sync(); return;
      }
    }
    document.querySelector(destinations[button.dataset.studioRoute])?.click(); sync();
  }));
  journey.querySelector('[data-studio-check]').addEventListener('click', () => {
    // Detection belongs to the active batch, never to the independent capture library.
    const selected=document.getElementById('product-publish-page').classList.contains('has-workbench-batch');
    status.textContent=selected?'本批次会自动检查来源图片与商品版本，通过后提取印花；缺少资料时会显示具体原因。进度以左侧执行状态为准。':'请先在左侧选择批次。检测会在当前工作台内进行，不需要返回采集链接。';
    journey.querySelectorAll('[data-studio-step]').forEach(button=>{
      const active=button.dataset.studioStep==='check';button.classList.toggle('is-current',active);
      if(active)button.setAttribute('aria-current','step');else button.removeAttribute('aria-current');
    });
  });
  window.addEventListener('hashchange',sync);
  // The application also navigates via replaceState, which does not emit hashchange.
  const observer = new MutationObserver(sync);
  [document.getElementById('product-publish-page'), ...document.querySelectorAll('[data-commerce-panel]')].forEach(panel => observer.observe(panel,{attributes:true,attributeFilter:['hidden']}));
  document.querySelectorAll('#primary-navigation a').forEach(link => link.addEventListener('click',sync));
  sync();
})();
