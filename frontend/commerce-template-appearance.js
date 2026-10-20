/* Template-local appearance controls. No global theme or company data is persisted here. */
(() => {
  const normalizeHex = value => {
    let hex = String(value || '').trim().replace(/^#/, '');
    if (/^[0-9a-f]{3}$/i.test(hex)) hex = [...hex].map(c => c + c).join('');
    return /^[0-9a-f]{6}$/i.test(hex) ? '#' + hex.toUpperCase() : null;
  };
  const toHsv = hex => {
    const [r,g,b] = hex.slice(1).match(/../g).map(c => parseInt(c,16)/255);
    const max=Math.max(r,g,b), min=Math.min(r,g,b), d=max-min;
    let h=d ? max===r ? ((g-b)/d)%6 : max===g ? (b-r)/d+2 : (r-g)/d+4 : 0;
    h*=60;if(h<0)h+=360;
    return {h,s:max?d/max:0,v:max};
  };
  const fromHsv = ({h,s,v}) => {
    const c=v*s,x=c*(1-Math.abs(h/60%2-1)),m=v-c;
    const rgb=h<60?[c,x,0]:h<120?[x,c,0]:h<180?[0,c,x]:h<240?[0,x,c]:h<300?[x,0,c]:[c,0,x];
    return '#'+rgb.map(n=>Math.round((n+m)*255).toString(16).padStart(2,'0')).join('').toUpperCase();
  };
  function mount({layout,changed}) {
    const card=document.createElement('section');
    card.className='print-template-appearance';card.setAttribute('aria-label','模板风格与配色');
    card.innerHTML=`
      <div class="template-appearance-heading"><h3>模板风格与配色</h3><small>随当前模板保存</small></div>
      <div class="template-appearance-row"><label for="print-main-image-style">模板风格 <small>可选</small></label>
        <div><div class="template-appearance-style"><select id="print-main-image-style" aria-describedby="print-style-note"><option value="">不使用模板风格</option></select><button type="button" data-action="refresh">刷新列表</button></div>
        <p id="print-style-note">不使用模板风格，主图按原流程生成。</p></div></div>
      <div class="template-appearance-row"><span>裂变配色</span><div><div class="template-color-list" aria-label="模板配色"></div><p class="template-color-note"></p></div></div>`;
    layout.append(card);
    const picker=document.createElement('section');picker.className='template-color-picker';picker.hidden=true;
    picker.setAttribute('role','dialog');picker.setAttribute('aria-label','编辑模板配色');
    picker.innerHTML=`
      <div class="template-picker-heading"><strong>编辑配色</strong><button type="button" data-action="close" aria-label="关闭颜色选择器">×</button></div>
      <div class="template-picker-plane" tabindex="0" role="group" aria-label="色盘，左右调整饱和度，上下调整明度"><i></i></div>
      <div class="template-picker-hue"><span></span><input type="range" min="0" max="360" aria-label="色相"></div>
      <div class="template-picker-fields"><label>颜色名称<input data-field="name" maxlength="40" placeholder="例如：雾蓝"></label><label>HEX 色号<input data-field="hex" maxlength="7" spellcheck="false" autocomplete="off" aria-describedby="print-color-error"></label></div>
      <div class="template-picker-presets" aria-label="常用颜色"></div><p id="print-color-error" role="alert"></p>
      <div class="template-picker-footer"><button type="button" data-action="delete">删除此配色</button><span></span><button type="button" data-action="cancel">取消</button><button type="button" data-action="apply">保存配色</button></div>`;
    document.body.append(picker);
    const select=card.querySelector('select'),refresh=card.querySelector('[data-action=refresh]');
    const list=card.querySelector('.template-color-list'),note=card.querySelector('.template-color-note');
    const hex=picker.querySelector('[data-field=hex]'),name=picker.querySelector('[data-field=name]');
    const plane=picker.querySelector('.template-picker-plane'),hue=picker.querySelector('input[type=range]');
    const error=picker.querySelector('[role=alert]');
    let colors=[],styles=[],styleId=null,editing=-1,anchor=null,hsv=toHsv('#AABBCC'),locked=false,saveLocked=false,loading=false,loaded=false,generation=0;
    const isLocked=()=>locked||saveLocked;
    function styleNote() {
      card.querySelector('#print-style-note').textContent=styleId
        ? '全部主图使用所选风格模板，不混用原来的主图。'
        : '不使用模板风格，主图按原流程生成。';
    }
    function drawStyles() {
      select.replaceChildren(new Option('不使用模板风格',''));
      for(const style of styles){
        const option=new Option(`${style.name} · ${style.imageCount} 张${style.status==='READY'?'':' · 尚未就绪'}`,style.id);
        option.disabled=style.status!=='READY';select.append(option);
      }
      if(styleId&&!styles.some(s=>s.id===styleId))select.append(new Option('已选风格 · 待读取或已不可用',styleId));
      select.value=styleId||'';styleNote();
    }
    async function loadStyles() {
      if(loading)return;
      const ticket=++generation;loading=true;refresh.disabled=true;
      try{
        const response=await api('/api/v1/product-main-image-templates');
        if(ticket!==generation)return;
        styles=Array.isArray(response.data)?response.data:[];loaded=true;drawStyles();
      }catch(failure){
        if(ticket===generation)card.querySelector('#print-style-note').textContent='风格列表读取失败，可刷新重试；当前选择已保留。';
      }finally{if(ticket===generation){loading=false;refresh.disabled=isLocked();}}
    }
    function close(focus=true) {
      picker.hidden=true;
      list.querySelectorAll('[aria-expanded]').forEach(b=>b.setAttribute('aria-expanded','false'));
      if(focus&&anchor?.isConnected)anchor.focus({preventScroll:true});
      anchor=null;editing=-1;
    }
    function position() {
      if(picker.hidden||!anchor)return;
      const box=anchor.getBoundingClientRect(),rect=picker.getBoundingClientRect(),gap=12;
      picker.style.left=Math.max(gap,Math.min(box.left,innerWidth-rect.width-gap))+'px';
      picker.style.top=Math.max(gap,Math.min(box.top-rect.height-8<gap?box.bottom+8:box.top-rect.height-8,innerHeight-rect.height-gap))+'px';
    }
    function drawPicker(update=true) {
      const value=fromHsv(hsv);plane.style.setProperty('--picker-hue',hsv.h);
      plane.querySelector('i').style.left=(hsv.s*100)+'%';plane.querySelector('i').style.top=((1-hsv.v)*100)+'%';
      picker.querySelector('.template-picker-hue span').style.background=value;
      hue.value=hsv.h;if(update)hex.value=value;error.textContent='';hex.removeAttribute('aria-invalid');
    }
    function open(index,button) {
      if(isLocked())return;
      editing=index;anchor=button;
      const color=colors[index]||{name:'',hex:'#AABBCC'};
      name.value=color.name;hsv=toHsv(color.hex);drawPicker();
      picker.querySelector('strong').textContent=index<0?'添加配色':'编辑配色';
      picker.querySelector('[data-action=delete]').hidden=index<0;
      picker.querySelector('[data-action=apply]').textContent=index<0?'添加配色':'保存配色';
      picker.hidden=false;button.setAttribute('aria-expanded','true');position();hex.focus({preventScroll:true});hex.select();
    }
    function drawColors() {
      list.replaceChildren();
      colors.forEach((color,index)=>{
        const button=document.createElement('button');button.type='button';button.className='template-color-chip';
        button.setAttribute('aria-label',`编辑${color.name} ${color.hex}`);button.setAttribute('aria-haspopup','dialog');button.setAttribute('aria-expanded','false');
        const dot=document.createElement('i');dot.style.background=color.hex;
        const text=document.createElement('span'),label=document.createElement('strong'),code=document.createElement('code');
        label.textContent=color.name;code.textContent=color.hex;text.append(label,code);button.append(dot,text);
        button.disabled=isLocked();button.addEventListener('click',()=>open(index,button));list.append(button);
      });
      const add=document.createElement('button');add.type='button';add.className='template-color-add';add.textContent='＋ 添加颜色';
      add.disabled=isLocked()||colors.length>=20;add.addEventListener('click',()=>open(-1,add));list.append(add);
      note.textContent=colors.length?`${colors.length} 个配色 · 每款生成 ${colors.length} 个配色版本，点击色块可修改。`:'请添加裂变配色，按模板色号生成，不从竞品图取色。';
    }
    function apply() {
      if(isLocked())return;
      const value=normalizeHex(hex.value),label=name.value.trim();
      if(!value){error.textContent='请输入有效 HEX 色号，例如 #AABBCC。';hex.setAttribute('aria-invalid','true');return;}
      if(!label){error.textContent='请填写颜色名称。';name.focus();return;}
      if(colors.some((c,i)=>i!==editing&&(c.hex===value||c.name.toLowerCase()===label.toLowerCase()))){error.textContent='颜色名称或色号已存在。';return;}
      const index=editing;
      if(index<0){if(colors.length>=20)return;colors.push({name:label,hex:value});}else colors[index]={name:label,hex:value};
      close(false);drawColors();changed();list.querySelectorAll('.template-color-chip')[index<0?colors.length-1:index]?.focus({preventScroll:true});
    }
    picker.querySelector('[data-action=apply]').addEventListener('click',apply);
    for(const action of ['close','cancel'])picker.querySelector(`[data-action=${action}]`).addEventListener('click',()=>close());
    picker.querySelector('[data-action=delete]').addEventListener('click',()=>{
      if(isLocked()||editing<0)return;colors.splice(editing,1);close(false);drawColors();changed();list.querySelector('.template-color-add').focus({preventScroll:true});
    });
    hex.addEventListener('input',()=>{const value=normalizeHex(hex.value);if(value){hsv=toHsv(value);drawPicker(false);}});
    hex.addEventListener('blur',()=>{const value=normalizeHex(hex.value);if(value)hex.value=value;});
    hue.addEventListener('input',()=>{hsv.h=Number(hue.value)%360;drawPicker();});
    function pick(event) {const r=plane.getBoundingClientRect();hsv.s=Math.max(0,Math.min(1,(event.clientX-r.left)/r.width));hsv.v=1-Math.max(0,Math.min(1,(event.clientY-r.top)/r.height));drawPicker();}
    plane.addEventListener('pointerdown',e=>{e.preventDefault();plane.setPointerCapture(e.pointerId);pick(e);});
    plane.addEventListener('pointermove',e=>{if(plane.hasPointerCapture(e.pointerId))pick(e);});
    plane.addEventListener('pointerup',e=>{if(plane.hasPointerCapture(e.pointerId))plane.releasePointerCapture(e.pointerId);});
    plane.addEventListener('keydown',e=>{
      if(!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(e.key))return;e.preventDefault();
      hsv.s=Math.max(0,Math.min(1,hsv.s+(e.key==='ArrowLeft'?-.01:e.key==='ArrowRight'?.01:0)));
      hsv.v=Math.max(0,Math.min(1,hsv.v+(e.key==='ArrowDown'?-.01:e.key==='ArrowUp'?.01:0)));drawPicker();
    });
    for(const [label,value] of [['墨黑','#252A26'],['奶油白','#F2EDE3'],['雾蓝','#B4C5D7'],['浅陶粉','#D5A89A'],['燕麦色','#C5B598']]){
      const button=document.createElement('button');button.type='button';button.style.background=value;button.setAttribute('aria-label',`${label} ${value}`);
      button.addEventListener('click',()=>{hsv=toHsv(value);if(!name.value.trim())name.value=label;drawPicker();});picker.querySelector('.template-picker-presets').append(button);
    }
    document.addEventListener('pointerdown',e=>{if(!picker.hidden&&!picker.contains(e.target)&&!list.contains(e.target))close(false);});
    document.addEventListener('keydown',e=>{if(picker.hidden)return;if(e.key==='Escape'){e.preventDefault();close();}else if(e.key==='Enter'&&(e.target===hex||e.target===name)){e.preventDefault();apply();}});
    window.addEventListener('resize',position);window.addEventListener('scroll',position,true);
    select.addEventListener('change',()=>{styleId=select.value||null;styleNote();changed();});
    refresh.addEventListener('click',loadStyles);
    function setLocked(value) {locked=value;select.disabled=isLocked();refresh.disabled=isLocked()||loading;list.querySelectorAll('button').forEach(b=>b.disabled=isLocked()||b.className==='template-color-add'&&colors.length>=20);picker.querySelectorAll('button,input').forEach(control=>control.disabled=isLocked());}
    window.addEventListener('heyi:template-save-lock',event=>{saveLocked=!!event.detail;setLocked(locked);});
    window.addEventListener('hashchange',()=>{close(false);if(location.hash==='#commerce-templates'&&!loaded)loadStyles();});
    if(location.hash==='#commerce-templates')loadStyles();
    drawColors();
    return {
      fields:()=>({colors:colors.map(c=>({...c})),mainImageStyleTemplateId:styleId}),
      load(config){close(false);colors=(config.colors||[]).map(c=>({name:c.name,hex:normalizeHex(c.hex)||'#000000'}));styleId=config.mainImageStyleTemplateId||null;drawColors();drawStyles();},
      validate(){if(!picker.hidden)throw new Error('请先保存或取消正在编辑的配色。');if(!colors.length)throw new Error('请先添加至少一种裂变配色。');},
      setLocked,
      reset(){generation++;styles=[];loaded=false;loading=false;saveLocked=false;locked=false;this.load({});setLocked(false);}
    };
  }
  window.CommerceTemplateAppearance={mount,normalizeHex};
})();
