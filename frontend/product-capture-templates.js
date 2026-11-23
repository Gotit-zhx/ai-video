(() => {
  'use strict';
  const root = document.querySelector('#capture-template-workspace');
  if (!root) return;
  const $ = id => root.querySelector('#ct-' + id);
  const all = selector => root.querySelectorAll(selector);
  const icons = {plus:'M12 5v14M5 12h14',check:'m5 12 4 4L19 6',search:'m21 21-5-5M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',info:'M12 11v6M12 7h.01M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',layers:'m12 3 10 6-10 6L2 9l10-6M2 13l10 6 10-6M2 17l10 6 10-6',upload:'M12 16V3m-5 5 5-5 5 5M4 15v5h16v-5',pipette:'m14 5 5 5M3 21l3-1 12-12-4-4L2 16v4h4M14 4l2-2 6 6-2 2',close:'m6 6 12 12M6 18 18 6',lock:'M6 10V7a6 6 0 0 1 12 0v3M4 10h16v12H4zM12 15v3'};
  const icon = (name) => `<svg class="ct-icon" viewBox="0 0 24 24" aria-hidden="true"><path d="${icons[name] || icons.layers}"/></svg>`;
  all('[data-icon]').forEach((el) => el.outerHTML = icon(el.dataset.icon));
  const uid = () => crypto.randomUUID();
  const color = (name,hex) => ({id:uid(),name,hex,colorCode:''});
  const variant = (name='',image='',filename='',sample=false) => ({id:uid(),name,image,filename,sample,styleCode:'',colors:[color('','')],sizes:[]});
  const newTemplate = () => ({id:uid(),name:'',description:'',variants:[variant()]});
  let templates = [newTemplate()];
  let selected = templates[0].id, toastTimer, picking = null;
  const edits = new Map(), controllers = new Set();
  let generation=0, organization=null, entering=false;
  const endpoint='/api/v1/product-capture-templates';
  async function request(path,options={}){
    const ticket=generation, org=currentUser?.organizationId, controller=new AbortController();controllers.add(controller);
    try{
      const result=await api(path,{...options,signal:controller.signal});
      if(ticket!==generation||org!==currentUser?.organizationId)throw new DOMException('Company changed','AbortError');
      if(result?.success!==true)throw new Error(result?.message||'操作失败，请重试');
      return result.data;
    }finally{controllers.delete(controller);}
  }
  const transport={
    list:offset=>request(`${endpoint}?offset=${offset}&limit=50`),
    upload:file=>{const body=new FormData();body.append('file',file);return request(`${endpoint}/images`,{method:'POST',body});},
    save:(template,draft)=>request(`${endpoint}/${encodeURIComponent(template.id)}`,{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({
      version:template.version||0,draft,name:template.name,description:template.description,
      variants: template.variants.map(v => ({
        id: v.id, name: v.name, imageId: v.imageId, styleCode: v.styleCode,
        colors: v.colors.map(c => ({id: c.id, name: c.name, hex: c.hex, colorCode: c.colorCode})),
        sizes: v.sizes || []
      }))
    })})
  };
  let canEdit=false, ready=false, busy=false, loaded=0, total=0;
  function lockControls(){
    all('.ct-editor input, .ct-editor button').forEach(node => {
      if (!node.closest('.ct-settings-section')) node.disabled = busy || !canEdit;
    });
    $('save').disabled = busy || !canEdit || Boolean(edits.get(selected)?.pending);
    all('.ct-template-item').forEach(node=>node.disabled=busy||!ready);
    $('load-more-templates').disabled=busy;
  }
  function setBusy(value){busy=value;lockControls();}
  const current = () => templates.find(t => t.id === selected);
  const active = () => current().variants[0];

  function mountSettings() {
    const template = current();
    const scope = {id: template.id, exists: () => Boolean(template.version), ensureSaved: () => saveTemplate(template)};
    window.ProductMainImageStyles?.setCaptureTemplate(scope.id, scope);
    window.ProductListingTemplates?.mountInline($('listing-host'), scope);
  }
  function el(tag,className,text){const node=document.createElement(tag);if(className)node.className=className.split(' ').map(name=>'ct-'+name).join(' ');if(text!==undefined)node.textContent=text;return node;}
  function toast(message){if(!message||!currentUser?.organizationId)return;$('toast').textContent=message;$('toast').hidden=false;clearTimeout(toastTimer);toastTimer=setTimeout(()=>$('toast').hidden=true,4500);}
  function editState(template) {
    if (!edits.has(template.id)) edits.set(template.id, {revision: 0, saved: 0});
    return edits.get(template.id);
  }
  function saveStatus(template, message) {
    if (template.id === selected) $('save-state').textContent = message;
  }
  function settledStatus(template) {
    return !canEdit ? '仅查看' : !template.version ? '自动保存已开启'
      : template.draft ? '草稿已保存' : '已保存';
  }
  function saveSoon(template, state) {
    clearTimeout(state.timer);
    state.timer = setTimeout(() => void saveTemplate(template).catch(() => {}), 600);
  }
  function changed() {
    const template = current(), state = editState(template);
    state.revision++;
    saveStatus(template, '等待自动保存…');
    saveSoon(template, state);
  }

  function drawList(){
    $('template-count').textContent=templates.length;
    const list=$('template-list');list.replaceChildren();
    const query=$('search').value.trim().toLowerCase();
    templates.filter(t=>t.name.toLowerCase().includes(query)).forEach(t=>{
      const button=el('button','template-item');button.type='button';button.setAttribute('aria-pressed',String(t.id===selected));
      const image=t.variants.find(v=>v.image)?.image;
      if(image){const img=el('img');img.src=image;img.alt='';button.append(img);}else{const blank=el('span','template-thumb-empty');blank.innerHTML=icon('layers');button.append(blank);}
      const text=el('span');text.append(el('strong','',t.name||'填写模板'),el('small','',`${t.variants[0]?.styleCode || '未填写编码'} · ${t.variants.reduce((n,v)=>n+v.colors.length,0)} 个颜色`));button.append(text);
      button.onclick = () => {
        if (busy || selected === t.id) return;
        const previous = current(), state = edits.get(previous.id);
        if (state?.revision !== state?.saved) void saveTemplate(previous).catch(() => {});
        selected = t.id;
        drawEditor(); drawList(); mountSettings();
        const edit = edits.get(t.id);
        saveStatus(t, edit?.revision !== edit?.saved ? '等待保存…' : settledStatus(t));
      };list.append(button);
    });
    if(!list.children.length)list.append(el('p','empty-list','未找到模板'));lockControls();
  }
  function drawEditor(){const t=current();$('editor-title').textContent=t.name||'填写模板';$('template-name').value=t.name;$('template-description').value=t.description;drawVariant();drawSizes();}
  function drawVariant(){const v=active();picking=null;$('dropzone').classList.remove('picking');$('image-hint').textContent='';$('image-hint').hidden=true;$('variant-name').value=v.name;$('style-code').value=v.styleCode||'';
    $('product-image').hidden=!v.image;if(v.image)$('product-image').src=v.image;else $('product-image').removeAttribute('src');
    $('image-empty').hidden=!!v.image;$('replace-image').hidden=!v.image;$('sample-tag').hidden=!v.sample;$('image-filename').textContent=v.filename||'尚未上传图片';drawColors();
  }
  function normalize(value){const hex=value.trim().replace(/^#/,'');if(/^[a-f\d]{3}$/i.test(hex))return '#'+hex.split('').map(c=>c+c).join('').toUpperCase();return /^[a-f\d]{6}$/i.test(hex)?'#'+hex.toUpperCase():null;}
  function drawPalette(){const v=active();$('color-count').textContent=`(${v.colors.length})`;$('palette').replaceChildren();v.colors.filter(c=>normalize(c.hex)).forEach(c=>{const swatch=el('span','palette-chip');swatch.style.backgroundColor=c.hex;swatch.title=`${c.name} ${c.hex}`;$('palette').append(swatch);});drawList();lockControls();}
  function drawColors(){
    const container=$('colors');container.replaceChildren();const v=active();
    v.colors.forEach((c,i)=>{const row=el('div','color-row');const swatch=el('input');swatch.type='color';swatch.value=c.hex||'#FFFFFF';swatch.setAttribute('aria-label',`选择第 ${i+1} 个颜色`);
      const name=el('input');name.value=c.name;name.placeholder='颜色名称';name.maxLength=24;name.setAttribute('aria-label',`第 ${i+1} 个颜色名称`);
      const code=el('input');code.value=c.colorCode||'';code.placeholder='如：BK';code.maxLength=16;code.spellcheck=false;code.setAttribute('aria-label',`第 ${i+1} 个颜色编码`);
      code.oninput=()=>{c.colorCode=code.value;changed();};code.onblur=()=>{c.colorCode=code.value.trim().toUpperCase();code.value=c.colorCode;};
      const hex=el('input','hex');hex.value=c.hex;hex.placeholder='#RRGGBB';hex.maxLength=7;hex.spellcheck=false;hex.setAttribute('aria-label',`第 ${i+1} 个颜色 HEX 色值`);
      const error=el('span','color-error','请输入 3 位或 6 位十六进制色值');error.hidden=true;error.id=`error-${c.id}`;hex.setAttribute('aria-describedby',error.id);
      const update=(value)=>{const valid=normalize(value);error.hidden=!!valid;hex.setAttribute('aria-invalid',String(!valid));c.hex=valid||value;if(valid)swatch.value=valid;changed();drawPalette();return valid;};
      swatch.oninput=()=>{hex.value=swatch.value.toUpperCase();update(hex.value);};name.oninput=()=>{c.name=name.value;changed();drawPalette();};hex.oninput=()=>update(hex.value);hex.onblur=()=>{const valid=normalize(hex.value);if(valid)hex.value=valid;};
      const pick=el('button');pick.type='button';pick.innerHTML=icon('pipette');pick.title='从屏幕取色';pick.setAttribute('aria-label',`为第 ${i+1} 个颜色取色`);pick.onclick=async()=>{
        if(window.EyeDropper){try{const result=await new EyeDropper().open();hex.value=result.sRGBHex.toUpperCase();update(hex.value);}catch(e){if(e.name!=='AbortError')toast('取色失败，请重试或输入 HEX。');}return;}
        if(!v.image){toast('请先上传图片。');return;}picking=c.id;$('dropzone').classList.add('picking');$('image-hint').hidden=false;$('image-hint').textContent='点击图片取色，Esc 取消。';
      };
      const remove=el('button');remove.type='button';remove.innerHTML=icon('close');remove.title='删除颜色';remove.setAttribute('aria-label',`删除第 ${i+1} 个颜色`);remove.onclick=()=>{v.colors=v.colors.filter(item=>item.id!==c.id);changed();drawColors();};
      row.append(swatch,name,code,hex,pick,remove,error);container.append(row);
    });
    if(!v.colors.length)container.append(el('p','empty-list','暂无颜色'));drawPalette();
  }
  $('template-name').oninput=()=>{current().name=$('template-name').value;$('editor-title').textContent=current().name||'填写模板';changed();drawList();};
  $('template-description').oninput=()=>{current().description=$('template-description').value;changed();};
  $('style-code').oninput=()=>{active().styleCode=$('style-code').value;changed();drawList();};
  $('style-code').onblur=()=>{active().styleCode=$('style-code').value.trim().toUpperCase();$('style-code').value=active().styleCode;drawList();};
  $('variant-name').oninput=()=>{active().name=$('variant-name').value;changed();};$('search').oninput=drawList;
  const sizeFields = [
    ['name', '尺码名称', '如 M'],
    ['sizeCode', '尺码编码', '英数可沿用名称'],
    ['chestCm', '胸围 (cm)'],
    ['lengthCm', '衣长 (cm)'],
    ['shoulderCm', '肩宽 (cm)'],
    ['sleeveCm', '袖长 (cm)']
  ];

  function drawSizes() {
    const sizes = active().sizes ||= [];
    const container = $('sizes');
    container.replaceChildren();
    if (!sizes.length) container.append(el('p', 'empty-list', '暂无尺码'));
    sizes.forEach((size, index) => {
      const row = el('div', 'size-row');
      for (const [key, title, placeholder] of sizeFields) {
        const label = el('label', '', title);
        const input = el('input');
        input.value = size[key] ?? '';
        if (placeholder) {
          input.placeholder = placeholder;
          input.maxLength = key === 'name' ? 24 : 16;
          if (key === 'sizeCode') {
            input.spellcheck = false;
            input.onblur = () => { input.value = size[key] = input.value.trim().toUpperCase(); };
          }
        } else {
          input.type = 'number';
          input.inputMode = 'decimal';
          input.min = '0.01';
          input.max = '999';
          input.step = '0.01';
        }
        input.oninput = () => {
          size[key] = placeholder ? input.value : input.value === '' ? null : input.valueAsNumber;
          changed();
        };
        label.append(input);
        row.append(label);
      }
      const remove = el('button');
      remove.type = 'button';
      remove.innerHTML = icon('close');
      remove.title = '删除尺码';
      remove.setAttribute('aria-label', `删除第 ${index + 1} 个尺码`);
      remove.onclick = () => {
        sizes.splice(index, 1);
        changed();
        drawSizes();
        (container.children[index]?.querySelector('input') || $('add-size')).focus();
      };
      row.append(remove);
      container.append(row);
    });
    lockControls();
  }

  $('add-size').onclick = () => {
    if (!canEdit || busy) return;
    const sizes = active().sizes ||= [];
    if (sizes.length >= 30) { toast('每个款式最多配置 30 个尺码'); return; }
    sizes.push({id: uid(), name: '', sizeCode: ''});
    changed();
    drawSizes();
    $('sizes').lastElementChild.querySelector('input').focus();
  };

  $('add-color').onclick=()=>{if(!canEdit||busy)return;if(active().colors.length>=30){toast('每个款式最多 30 个颜色');return;}active().colors.push(color('',''));changed();drawColors();$('colors').lastElementChild.querySelectorAll('input')[1].focus();};
  $('choose-image').onclick=$('replace-image').onclick=()=>$('image-input').click();
  async function upload(files){
    if(!canEdit||busy)return;
    if(files.length!==1){toast('每个款式只能上传一张图片');return;}
    const file=files[0];
    if(!['image/png','image/jpeg','image/webp'].includes(file.type)||file.size>8*1024*1024){toast('仅支持 8 MiB 以内的 PNG、JPG、WebP');return;}
    const ticket=generation, original=active();
    setBusy(true);$('save-state').textContent='上传中…';
    try{
      const image=await transport.upload(file);
      original.image=image.imageUrl;original.imageId=image.id;original.filename=image.filename;original.sample=false;
      changed();toast('已上传');
    }catch(error){if(ticket===generation&&error.name!=='AbortError')toast(error.message);}
    finally{if(ticket!==generation)return;drawEditor();drawList();if(!edits.get(selected)?.timer&&!edits.get(selected)?.pending)saveStatus(current(),settledStatus(current()));setBusy(false);}
  }
  $('image-input').onchange=async(e)=>{await upload(e.target.files);e.target.value='';};
  ['dragenter','dragover'].forEach(type=>$('dropzone').addEventListener(type,e=>{e.preventDefault();$('dropzone').classList.add('dragging');}));
  $('dropzone').ondragleave=()=>$('dropzone').classList.remove('dragging');$('dropzone').ondrop=e=>{e.preventDefault();$('dropzone').classList.remove('dragging');upload(e.dataTransfer.files);};
  $('product-image').onclick=(e)=>{if(!picking)return;const img=e.currentTarget,rect=img.getBoundingClientRect(),scale=Math.min(rect.width/img.naturalWidth,rect.height/img.naturalHeight),w=img.naturalWidth*scale,h=img.naturalHeight*scale,x=(e.clientX-rect.left-(rect.width-w)/2)/scale,y=(e.clientY-rect.top-(rect.height-h)/2)/scale;
    if(x<0||y<0||x>=img.naturalWidth||y>=img.naturalHeight)return;
    try{const canvas=document.createElement('canvas');canvas.width=img.naturalWidth;canvas.height=img.naturalHeight;const ctx=canvas.getContext('2d');ctx.drawImage(img,0,0);const pixel=ctx.getImageData(Math.floor(x),Math.floor(y),1,1).data;const c=active().colors.find(c=>c.id===picking);if(c)c.hex='#'+Array.from(pixel).slice(0,3).map(n=>n.toString(16).padStart(2,'0')).join('').toUpperCase();changed();drawVariant();}catch{toast('无法读取此图片的颜色，请使用 HEX 输入。');}
  };
  document.addEventListener('keydown',e=>{if(e.key==='Escape'&&picking)drawVariant();});
  function complete(template) {
    const v = template.variants[0];
    if (!template.name.trim() || !v.name.trim() || !v.imageId || !v.colors.length
        || !/^[A-Z][0-9]{2}$/i.test((v.styleCode || '').trim())) return false;
    const codes = new Set();
    for (const c of v.colors) {
      const code = (c.colorCode || '').trim().toUpperCase();
      if (!c.name.trim() || !normalize(c.hex) || !/^[A-Z0-9]{1,16}$/.test(code) || codes.has(code)) return false;
      codes.add(code);
    }
    const sizeNames = new Set(), sizeCodes = new Set();
    for (const size of v.sizes || []) {
      const name = (size.name || '').trim().toUpperCase();
      const code = (size.sizeCode || '').trim().toUpperCase() || name;
      if (!name || sizeNames.has(name) || sizeCodes.has(code) || !/^[A-Z0-9]{1,16}$/.test(code)) return false;
      sizeNames.add(name);
      sizeCodes.add(code);
    }
    return true;
  }
  async function saveTemplate(template) {
    if (!canEdit || !ready || !templates.includes(template)) throw new Error('当前款式不可保存');
    const state = editState(template);
    clearTimeout(state.timer);
    state.timer = null;
    if (state.pending) {
      await state.pending;
      return saveTemplate(template);
    }
    if (template.version && state.revision === state.saved) return;
    const ticket = generation, revision = state.revision;
    saveStatus(template, '保存中…');
    const pending = transport.save(template, !complete(template));
    state.pending = pending;
    lockControls();
    try {
      const saved = await pending;
      template.version = saved.version;
      template.draft = saved.draft;
      state.saved = revision;
      if (!templates.some(item => !item.version)) templates.unshift(newTemplate());
      if (selected === template.id && revision === state.revision) saveStatus(template, settledStatus(template));
      drawList();
    } catch (error) {
      if (ticket === generation) {
        saveStatus(template, '保存失败，请重试');
        if (error.name !== 'AbortError') toast(error.message);
      }
      throw error;
    } finally {
      state.pending = null;
      if (ticket === generation) lockControls();
    }
  }
  $('save').onclick = () => void saveTemplate(current()).catch(() => {});
  async function loadMore(){
    const page=await transport.list(loaded);total=page.total;loaded+=page.items.length;
    for(const item of page.items){if(!templates.some(t=>t.id===item.id))templates.push(item);}
    $('load-more-templates').hidden=loaded>=total;drawList();
  }
  $('load-more-templates').onclick=async()=>{const ticket=generation;setBusy(true);try{await loadMore();}catch(error){if(ticket===generation&&error.name!=='AbortError')toast(error.message);}finally{if(ticket===generation)setBusy(false);}};
  window.addEventListener('beforeunload', e => {
    if ([...edits.values()].some(state => state.saved !== state.revision)) {
      e.preventDefault(); e.returnValue = '';
    }
  });
  function reset(){
    generation++;controllers.forEach(controller=>controller.abort());controllers.clear();
    organization=null;entering=false;edits.forEach(state=>clearTimeout(state.timer));edits.clear();busy=false;ready=false;canEdit=false;loaded=0;total=0;
    clearTimeout(toastTimer);$('toast').hidden=true;$('image-input').value='';$('search').value='';
    window.ProductListingTemplates?.unmountInline();templates=[newTemplate()];selected=templates[0].id;
    $('load-more-templates').hidden=true;drawEditor();drawList();lockControls();$('save-state').textContent='';
  }
  async function enter(){
    const org=currentUser?.organizationId;
    if(!org)return;
    if(organization&&organization!==org)reset();
    if (entering) return;
    if (ready && organization === org) {
      mountSettings();
      return;
    }
    organization=org;entering=true;const ticket=generation;
    setBusy(true);$('save-state').textContent='读取中…';
    try{
      canEdit=hasAnyPermission('product:edit');await loadMore();
      if(ticket!==generation)return;
      ready = true;
      selected = templates.find(template => template.version)?.id || selected;
      drawEditor(); drawList(); saveStatus(current(), settledStatus(current()));
      if (!root.hidden) mountSettings();
    }catch(error){if(ticket===generation&&error.name!=='AbortError'){$('save-state').textContent=error.message;toast(error.message);}}
    finally{if(ticket===generation){entering=false;setBusy(false);}}
  }
  window.addEventListener('heyi:commerce-section-entered', event => {
    if (event.detail.route === 'product-capture-templates') void enter();
    else window.ProductListingTemplates?.unmountInline();
  });
  window.addEventListener('heyi:company-reset',reset);
  drawEditor();drawList();lockControls();
  if(!root.hidden&&currentUser?.organizationId)void enter();
})();
