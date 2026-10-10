/* The same company-scoped transparent artwork catalogue powers the library and batch picker. */
(() => {
 const make=(tag,value,parent)=>{const node=document.createElement(tag);node.textContent=value;parent?.append(node);return node;};
 const button=(label,parent,action)=>{const node=make('button',label,parent);node.type='button';if(action)node.addEventListener('click',action);return node;};
 const imageUrl=item=>typeof item?.imageUrl==='string'&&/^\/api\/v1\/commerce-artwork-library\/[a-zA-Z0-9-]+\/image$/.test(item.imageUrl)?item.imageUrl:null;
 const thumbnail=(item,parent)=>{const box=make('div','',parent);box.className='artwork-transparent-preview';const url=imageUrl(item);if(url){const image=make('img','',box);image.alt=item.name||'透明印花';image.loading='lazy';image.src=url;image.addEventListener('error',()=>{image.remove();make('span','图片暂不可读取',box);});}else make('span','选择透明印花',box);return box;};
 function catalogue(host,{request=api,onPick=null,onBusy=()=>{}}={}){
  let page=0,revision=0,busy=false,locked=false,loaded=false;
  const toolbar=make('div','',host);toolbar.className='artwork-library-toolbar';
  const query=make('input','',toolbar);query.type='search';query.placeholder='搜索印花或来源批次';query.setAttribute('aria-label','搜索透明印花');
  const search=button('读取印花',toolbar,()=>load(0));
  const message=make('p','裂变成功的透明印花会自动出现在这里。',host);message.setAttribute('role','status');
  const grid=make('div','',host);grid.className='artwork-library-grid';
  const pagination=make('div','',host);pagination.className='artwork-library-pagination';
  const previous=button('上一页',pagination,()=>load(Math.max(0,page-1))),pageText=make('span','',pagination),next=button('下一页',pagination,()=>load(page+1));
  let hasMore=false;
  function controls(){search.disabled=query.disabled=busy||locked;previous.disabled=busy||locked||page===0;next.disabled=busy||locked||!hasMore;grid.querySelectorAll('button').forEach(node=>node.disabled=locked||busy);}
  async function load(target=0){
   if(locked||busy)return;const ticket=++revision;busy=true;controls();onBusy(true);message.textContent='正在读取已归档的透明印花…';
   try{const result=await request('/api/v1/commerce-artwork-library?page='+target+'&query='+encodeURIComponent(query.value.trim()));if(ticket!==revision)return;
    const data=result.data;if(!data||!Array.isArray(data.items))throw new Error('印花列表暂不可用');
    loaded=true;page=data.page;hasMore=!!data.hasMore;grid.replaceChildren();
    data.items.forEach(item=>{const card=make('article','',grid);card.className='artwork-library-card';thumbnail(item,card);make('strong',item.name||'透明印花',card);make('small',item.batchName||'历史裂变',card);
     if(onPick)button('使用此印花',card,()=>{if(!locked&&!busy)onPick(item);});
     else {const url=imageUrl(item);if(url){const link=make('a','查看透明原图 ↗',card);link.href=url;link.target='_blank';link.rel='noopener noreferrer';}}
    });
    pageText.textContent='第 '+(page+1)+' 页';message.textContent=data.items.length?'透明 PNG · 相同图片合并展示，历史记录保留':'没有找到可用印花。生成成功且通过透明检查的印花会自动收录。';
   }catch(error){if(ticket===revision){grid.replaceChildren();hasMore=false;message.textContent='读取印花失败：'+error.message+'。点击“读取印花”重试。';}}
   finally{if(ticket===revision){busy=false;controls();onBusy(false);}}
  }
  query.addEventListener('keydown',event=>{if(event.key==='Enter'){event.preventDefault();load(0);}});
  controls();
  return {load,ensureLoaded:()=>loaded?Promise.resolve():load(0),setLocked(value){locked=!!value;controls();},reset(){revision++;busy=false;locked=false;loaded=false;hasMore=false;page=0;query.value='';grid.replaceChildren();pageText.textContent='';message.textContent='裂变成功的透明印花会自动出现在这里。';controls();onBusy(false);}};
 }
 globalThis.CommerceArtworkPicker={mount({host,onChange=()=>{},api:request=api}){
  if(!host)return null;
  let reuse=false,groups=[],designs=[{}],configKey='',locked=false,browsing=null,loading=false;
  const title=make('h3','印花来源',host);title.className='artwork-picker-title';
  const modes=make('div','',host);modes.className='artwork-source-modes';modes.setAttribute('role','group');modes.setAttribute('aria-label','印花来源');
  const generate=button('生成新印花',modes,()=>setMode(false)),existing=button('使用已有印花',modes,()=>setMode(true));
  generate.dataset.artworkMode='generate';existing.dataset.artworkMode='reuse';
  const hint=make('p','从来源商品提取画风，生成新的透明印花。',host);hint.className='artwork-picker-hint';
  const chosen=make('div','',host);chosen.className='artwork-reuse-designs';
  const add=button('＋ 增加一款',host,()=>{if(locked||designs.length>=20)return;designs.push({});draw();onChange();});add.className='artwork-add-design';
  const browser=make('section','',host);browser.className='artwork-picker-browser';browser.hidden=true;
  const browserHeader=make('div','',browser);browserHeader.className='artwork-picker-browser-heading';const browseTitle=make('strong','选择透明印花',browserHeader);
  button('收起素材',browserHeader,()=>{browsing=null;browser.hidden=true;});
  const catalogueHost=make('div','',browser);
  const library=catalogue(catalogueHost,{request,onBusy:value=>{loading=value;onChange();},onPick:item=>{
   if(locked||!browsing||!designs[browsing.design]||!groups.some(g=>g.id===browsing.group))return;
   designs[browsing.design][browsing.group]=item;browsing=null;browser.hidden=true;draw();onChange();
  }});
  function error(){if(!reuse)return '';if(!groups.length)return '请先选择已设置印花位置的四面图片模板。';
   for(let i=0;i<designs.length;i++)for(const group of groups)if(!designs[i][group.id])return '请为第 '+(i+1)+' 款的图案组 '+group.index+' 选择印花素材。';
   const seen=new Set();for(const design of designs){const key=JSON.stringify(groups.map(group=>design[group.id].contentSha256||design[group.id].id));if(seen.has(key))return '有两款使用了完全相同的印花组合，请更换素材或移除重复款。';seen.add(key);}return ''; }
  function setMode(value){if(locked)return;reuse=value;browsing=null;browser.hidden=true;draw();onChange();}
  function draw(){
   generate.setAttribute('aria-pressed',String(!reuse));existing.setAttribute('aria-pressed',String(reuse));generate.disabled=existing.disabled=locked;
   hint.textContent=reuse?'每款为每个图案组选择一张透明印花。同组前后等位置共用这张图；按已选款数套图，不再调用印花生图。':'从来源商品提取画风，按模板数量生成新的透明印花。';
   chosen.hidden=add.hidden=!reuse;add.disabled=locked||designs.length>=20||!groups.length;chosen.replaceChildren();
   if(!reuse)return;
   if(!groups.length){make('p','请先选择已设置印花位置的四面图片模板。',chosen);return;}
   designs.forEach((design,index)=>{
    const card=make('section','',chosen);card.className='artwork-reuse-design';const header=make('div','',card);header.className='artwork-reuse-heading';make('strong','第 '+(index+1)+' 款',header);
    const remove=button('移除这款',header,()=>{if(locked)return;designs.splice(index,1);browsing=null;browser.hidden=true;draw();onChange();});remove.disabled=locked||designs.length===1;
    const slots=make('div','',card);slots.className='artwork-reuse-groups';
    groups.forEach(group=>{const row=make('div','',slots);row.className='artwork-reuse-group';thumbnail(design[group.id],row);const info=make('div','',row);make('strong','图案组 '+group.index,info);make('small',group.sides.join(' / ')+' · '+group.count+' 处位置',info);if(design[group.id])make('span',design[group.id].name||'已选印花',info);
     const choose=button(design[group.id]?'更换印花':'选择印花',info,()=>{if(locked)return;browsing={design:index,group:group.id};browseTitle.textContent='为第 '+(index+1)+' 款 · 图案组 '+group.index+' 选择印花';browser.hidden=false;library.ensureLoaded();});choose.disabled=locked;
    });
   });
  }
  draw();
  return {setTemplate(config){
   const prints=config?.photoPrints||[];
   const key=JSON.stringify([config?.sourceImageId,config?.referenceSetId,config?.renderBinding,prints]);if(key===configKey)return;configKey=key;groups=[];
   const names={FRONT:'正面',BACK:'背面',LEFT:'左侧',RIGHT:'右侧'};
   for(const print of prints){if(!print.artworkId)continue;let group=groups.find(g=>g.id===print.artworkId);if(!group){group={id:print.artworkId,index:groups.length+1,count:0,sides:[]};groups.push(group);}group.count++;const name=names[print.side]||'已保存位置';if(!group.sides.includes(name))group.sides.push(name);}
   designs=[{}];browsing=null;browser.hidden=true;draw();onChange();
  },getSelection(){if(!reuse)return null;const failure=error();if(failure)throw new Error(failure);return designs.map(design=>({groupArtifacts:Object.fromEntries(groups.map(g=>[g.id,design[g.id].id]))}));},
  getState(){return {reuse,count:reuse?designs.length:0,busy:loading,error:error()};},
  reset(){reuse=false;groups=[];designs=[{}];configKey='';browsing=null;browser.hidden=true;locked=false;library.reset();draw();onChange();},
  setLocked(value){locked=!!value;library.setLocked(locked);draw();}
  };
 }};
 const host=document.getElementById('commerce-artwork-library');
 if(host){make('h2','透明印花',host);const note=make('p','历次裂变的可用印花自动归档，可在“新建裂变上架”中重复使用。',host);note.className='artwork-library-description';const library=catalogue(host);
  const loadWhenVisible=()=>{if(globalThis.location?.hash==='#commerce-assets')library.load(0);};
  globalThis.addEventListener('heyi:commerce-section-entered',event=>{if(event.detail?.route==='commerce-assets')library.load(0);});
  globalThis.addEventListener('heyi:company-reset',()=>library.reset());
  if(typeof currentUser!=='undefined'&&currentUser)Promise.resolve().then(loadWhenVisible);
 }
})();
