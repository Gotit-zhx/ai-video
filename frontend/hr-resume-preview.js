(() => {
  let pdfModule;
  globalThis.createHrResumePreview=({root,element:el,button,row})=>{
    let stopped=false,pdf=null,loading=null,rendering=null,page=1,revision=0,resizeTimer,image=null;
    const controller=new AbortController();
    const top=el('div','',root,'hr-preview-toolbar');
    el('strong','简历原件',top);
    const controls=el('div','',top,'hr-preview-controls');
    const previous=button('上一页',controls,()=>render(page-1));
    const counter=el('span','',controls,'hr-preview-counter');
    const next=button('下一页',controls,()=>render(page+1));
    previous.disabled=next.disabled=true;
    const stage=el('div','',root,'hr-preview-stage');
    const status=el('p','正在加载简历…',stage,'hr-preview-message');
    const canvas=el('canvas','',stage,'hr-preview-canvas');canvas.hidden=true;
    const retry=button('重新加载',stage,()=>{if(!stopped)load();});retry.hidden=true;retry.className='hr-secondary';
    const download=el('a','下载原件',top,'hr-file-link');
    download.href=row.fileUrl;download.download=row.fileName||'简历'; // Explicit download only.
    async function render(number){
      if(!pdf||stopped||number<1||number>pdf.numPages)return;
      const current=++revision;const previousRender=rendering;previousRender?.cancel();
      try{
        await previousRender?.promise.catch(()=>{});if(stopped||current!==revision)return;
        const sheet=await pdf.getPage(number);if(stopped||current!==revision)return;
        const base=sheet.getViewport({scale:1});
        const width=Math.max(240,Math.min(stage.clientWidth-32,1100));
        const viewport=sheet.getViewport({scale:width/base.width});
        const ratio=Math.min(window.devicePixelRatio||1,2,Math.sqrt(5000000/(viewport.width*viewport.height)));
        canvas.width=Math.round(viewport.width*ratio);canvas.height=Math.round(viewport.height*ratio);
        canvas.style.width=Math.round(viewport.width)+'px';canvas.style.height=Math.round(viewport.height)+'px';
        const task=sheet.render({canvasContext:canvas.getContext('2d'),canvas,viewport,
          transform:ratio===1?null:[ratio,0,0,ratio,0,0]});rendering=task;
        await task.promise;if(stopped||current!==revision)return;
        if(number!==page)stage.scrollTop=0;
        page=number;counter.textContent=`${page} / ${pdf.numPages}`;
        previous.disabled=page===1;next.disabled=page===pdf.numPages;
        canvas.hidden=false;status.hidden=true;retry.hidden=true;
      }catch(error){
        if(stopped||current!==revision||error?.name==='RenderingCancelledException')return;
        status.textContent='这一页未能显示，请重新加载。';status.hidden=false;retry.hidden=false;
      }
    }
    async function load(){
      retry.hidden=true;status.hidden=false;
      try{
        ++revision;rendering?.cancel();
        await loading?.destroy().catch(()=>{});await pdf?.destroy().catch(()=>{});
        loading=null;pdf=null;if(stopped)return;
        if(['png','jpg','jpeg'].includes(row.extension)){
          controls.hidden=true;canvas.hidden=true;
          image?.remove();image=el('img','',stage,'hr-preview-image');image.alt='简历原件';
          image.onload=()=>{if(!stopped)status.hidden=true;};
          image.onerror=()=>{if(!stopped){status.textContent='图片未能显示，请重新加载。';retry.hidden=false;}};
          image.src=row.fileUrl;return;
        }
        if(row.extension!=='pdf'){
          controls.hidden=true;canvas.hidden=true;status.textContent='此 Word 文件可下载查看；右侧可查看已提取的正文。';return;
        }
        const response=await fetch(row.fileUrl,{credentials:'same-origin',cache:'no-store',signal:controller.signal});
        if(!response.ok||!/^application\/pdf(?:;|$)/i.test(response.headers.get('Content-Type')||''))throw Error('pdf');
        const bytes=new Uint8Array(await response.arrayBuffer());
        if(stopped)return;
        if(bytes.length>25*1024*1024||String.fromCharCode(...bytes.slice(0,5))!=='%PDF-')throw Error('pdf');
        if(!pdfModule)pdfModule=import('/vendor/pdfjs/pdf.js').catch(error=>{pdfModule=null;throw error;});
        const library=await pdfModule;if(stopped)return;
        library.GlobalWorkerOptions.workerSrc='/vendor/pdfjs/pdf.worker.js';
        loading=library.getDocument({data:bytes,isEvalSupported:false});
        const document=await loading.promise;
        if(stopped){document.destroy().catch(()=>{});return;}
        pdf=document;loading=null;await render(page);
      }catch(error){
        if(stopped||controller.signal.aborted)return;
        status.textContent='简历预览未能加载，请重试。';retry.hidden=false;
      }
    }
    const resize=typeof ResizeObserver==='function'?new ResizeObserver(()=>{
      clearTimeout(resizeTimer);resizeTimer=setTimeout(()=>render(page),150);
    }):null;
    resize?.observe(stage);load();
    return ()=>{
      stopped=true;++revision;clearTimeout(resizeTimer);controller.abort();resize?.disconnect();rendering?.cancel();
      loading?.destroy().catch(()=>{});pdf?.destroy().catch(()=>{});
    };
  };
})();
