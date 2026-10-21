/* Interface localization only: never rewrite request payloads or editable values. */
(() => {
  'use strict';
  const languages={zh:'简体中文',en:'English',ms:'Bahasa Melayu',id:'Bahasa Indonesia',th:'ไทย',vi:'Tiếng Việt',fil:'Filipino'};
  const indexes={en:0,id:1,th:2,vi:3,ms:4,fil:5};
  const key='hoyee:language';
  function normalize(value){
    const v=String(value||'').toLowerCase().replace('_','-');
    if(v==='tl'||v.startsWith('tl-')||v.startsWith('fil'))return 'fil';
    const base=v.split('-')[0];return Object.hasOwn(languages,base)?base:null;
  }
  let language;
  try{language=normalize(new URL(location.href).searchParams.get('lang'))||normalize(localStorage.getItem(key));}catch(_){}
  language ||= (navigator.languages||[navigator.language]).map(normalize).find(Boolean)||'en';
  let dictionary=window.HoyeeCatalog||{};
  let slotKinds=window.HoyeeCatalogSlots||{};
  let catalogLoading=null;
  const originals=new WeakMap(),attrs=new WeakMap(),pending=new Set(),controls=new Set();
  const cache=new Map(),missing=new Set();
  let scheduled=false,patterns=[];
  const excluded='script,style,textarea,input,code,pre,[contenteditable],[translate="no"],[data-language-control]';
  const escapeRegex=s=>s.replace(/[.*+?^${}()|[\]\\]/g,'\\$&');
  function compile(){
    patterns=Object.entries(dictionary).filter(([source])=>/\{\d+\}/.test(source)).map(([source,variants])=>{
      const ids=[];let last=0,pattern='';
      for(const m of source.matchAll(/\{(\d+)\}/g)){pattern+=escapeRegex(source.slice(last,m.index))+'([\\s\\S]*?)';ids.push(m[1]);last=m.index+m[0].length;}
      pattern+=escapeRegex(source.slice(last));
      const anchor=source.split(/\{\d+\}/).sort((a,b)=>b.length-a.length)[0];
      return {source,anchor,uiSlots:new Set((slotKinds[source]||[]).map(String)),regex:new RegExp('^'+pattern+'$'),ids,variants,weight:source.replace(/\{\d+\}/g,'').length};
    }).sort((a,b)=>b.weight-a.weight);cache.clear();
  }
  function text(value,depth=0){
    if(typeof value!=='string'||language==='zh'||!/[\u3400-\u9fff]/u.test(value))return value;
    const trimmed=value.trim(),index=indexes[language];
    if(!trimmed)return value;
    if(cache.has(trimmed))return value.replace(trimmed,()=>cache.get(trimmed));
    let translated=dictionary[trimmed]?.[index]||dictionary[trimmed]?.[0];
    if(translated===undefined&&depth<3){
      for(const {source,anchor,uiSlots,regex,ids,variants}of patterns){if(!trimmed.includes(anchor))continue;const match=regex.exec(trimmed);if(!match)continue;
        const slots={};ids.forEach((id,i)=>slots[id]=match[i+1]);
        translated=(variants[index]||variants[0]).replace(/\{(\d+)\}/g,(_,id)=>uiSlots.has(id)||source==='商品运营 / {0}'||/失败[:：]|错误[:：]/.test(source)?text(slots[id],depth+1):slots[id]);break;
      }
      if(translated===undefined){
        const numbered=trimmed.match(/^(\d+[.、]?\s+)(.+)$/);
        if(numbered&&dictionary[numbered[2]])translated=numbered[1]+(dictionary[numbered[2]][index]||dictionary[numbered[2]][0]);
      }
      if(translated===undefined){
        const parts=trimmed.split(/(\s+\/\s+|\s*[·｜|]\s*|\n)/);
        if(parts.length>1){const mapped=parts.map((p,i)=>i%2?p:text(p,depth+1));if(mapped.some((p,i)=>p!==parts[i]))translated=mapped.join('');}
      }
    }
    if(translated===undefined){if(missing.size<2000)missing.add(trimmed);return value;}
    if(cache.size>10000)cache.clear();cache.set(trimmed,translated);
    return value.replace(trimmed,()=>translated);
  }
  function translateNode(node){
    if(node.nodeType===Node.TEXT_NODE){
      if(!node.parentElement||node.parentElement.closest(excluded))return;
      let saved=originals.get(node);
      if(!saved||node.nodeValue!==saved.rendered)saved={original:node.nodeValue};
      // An option without a value inherits its text; freeze that value before translating.
      const parent=node.parentElement;
      if(parent.tagName==='OPTION'&&!parent.hasAttribute('value'))parent.setAttribute('value',parent.textContent);
      saved.rendered=text(saved.original);originals.set(node,saved);
      if(node.nodeValue!==saved.rendered)node.nodeValue=saved.rendered;
      return;
    }
    if(node.nodeType!==Node.ELEMENT_NODE||node.matches('script,style,code,pre,[contenteditable],[translate="no"],[data-language-control]'))return;
    const saved=attrs.get(node)||{};
    for(const name of ['placeholder','aria-label','title','alt','data-nav-label','data-caption']){
      const value=node.getAttribute(name);if(value===null)continue;
      if(!saved[name]||value!==saved[name].rendered)saved[name]={original:value};
      saved[name].rendered=text(saved[name].original);
      if(value!==saved[name].rendered)node.setAttribute(name,saved[name].rendered);
    }
    attrs.set(node,saved);
    for(const child of node.childNodes)translateNode(child);
  }
  function installFileControls(){
    for(const input of document.querySelectorAll('input[type="file"]')){
      if(input.hidden||input.closest('.localized-file-control')||getComputedStyle(input).display==='none')continue;
      const wrapper=document.createElement('span');wrapper.className='localized-file-control';
      const action=document.createElement('span');action.className='localized-file-action';action.textContent='选择文件';
      const name=document.createElement('span');name.className='localized-file-name';
      const update=()=>{const files=Array.from(input.files||[]);name.setAttribute('translate',files.length?'no':'yes');name.textContent=files.length?files.map(f=>f.name).join(', '):'未选择文件';};
      input.before(wrapper);wrapper.append(action,name,input);input.classList.add('localized-file-input');
      input.addEventListener('change',update);input.form?.addEventListener('reset',()=>queueMicrotask(update));update();
    }
  }
  function installControls(){
    installFileControls();
    for(const selector of ['.portal-nav nav','.login-header-tools','.topbar .user-area']){
      const container=document.querySelector(selector);if(!container||container.querySelector('[data-language-control]'))continue;
      const label=document.createElement('label');label.className='language-picker';label.dataset.languageControl='';
      const icon=document.createElement('span');icon.textContent='◎';icon.setAttribute('aria-hidden','true');
      const control=document.createElement('select');control.setAttribute('aria-label','Language');
      for(const [value,name]of Object.entries(languages)){const option=document.createElement('option');option.value=value;option.textContent=name;control.append(option);}
      control.value=language;control.addEventListener('change',()=>select(control.value));controls.add(control);label.append(icon,control);container.prepend(label);
    }
  }
  const observer=new MutationObserver(records=>{
    for(const record of records)pending.add(record.target.nodeType===Node.TEXT_NODE?record.target.parentElement:record.target);
    if(!scheduled){scheduled=true;queueMicrotask(()=>render(false));}
  });
  function render(full=true){
    scheduled=false;observer.disconnect();installControls();
    if(full)translateNode(document.body);else for(const node of pending)if(node?.isConnected)translateNode(node);
    pending.clear();document.documentElement.lang=language==='zh'?'zh-CN':language;document.documentElement.dataset.language=language;
    document.title=text('赫逸智能化 AI 平台');
    for(const control of controls){if(!control.isConnected)controls.delete(control);else control.value=language;}
    observer.observe(document.body,{subtree:true,childList:true,characterData:true,attributes:true,attributeFilter:['placeholder','aria-label','title','alt','data-nav-label','data-caption','translate','hidden']});
  }
  function select(value){
    const next=normalize(value);if(!next)return;language=next;cache.clear();missing.clear();
    try{localStorage.setItem(key,next);}catch(_){}
    const url=new URL(location.href);url.searchParams.set('lang',next);history.replaceState(null,'',url);
    render();document.dispatchEvent(new CustomEvent('hoyee:languagechange',{detail:{language}}));
    ensureCatalog();
  }
  // Chinese is the source language; loading all translations must not block the app.
  // One shared request also handles rapid language changes without applying an old choice.
  function ensureCatalog(){
    if(language==='zh'||Object.keys(dictionary).length)return;
    if(catalogLoading)return catalogLoading;
    catalogLoading=new Promise(resolve=>{
      const script=document.createElement('script');
      script.src='/i18n/catalog.js?v=shopee-logistics-20260921';script.async=true;
      const finish=ok=>{
        script.remove();catalogLoading=null;
        if(ok&&Object.keys(window.HoyeeCatalog||{}).length){
          dictionary=window.HoyeeCatalog;slotKinds=window.HoyeeCatalogSlots||{};
          delete document.documentElement.dataset.languageLoadError;
          compile();render();
          document.dispatchEvent(new CustomEvent('hoyee:languagechange',{detail:{language}}));
        }else document.documentElement.dataset.languageLoadError='true';
        resolve();
      };
      script.onload=()=>finish(true);script.onerror=()=>finish(false);
      document.head.append(script);
    });
    return catalogLoading;
  }
  for(const name of ['alert','confirm','prompt']){const native=window[name]?.bind(window);if(native)window[name]=(message,...args)=>native(text(String(message)),...args);}
  const validationMessages=new WeakMap();
  document.addEventListener('invalid',event=>{
    const input=event.target,v=input.validity;if(!v||v.valid)return;
    if(v.customError&&!validationMessages.has(input))return;
    const message=v.valueMissing?'请填写此字段':v.typeMismatch?(input.type==='email'?'请输入有效的电子邮箱地址':'请输入有效的网址'):v.rangeOverflow||v.rangeUnderflow?'输入值超出允许范围':'输入格式不正确';
    validationMessages.set(input,message);input.setCustomValidity(text(message));
  },true);
  document.addEventListener('input',event=>{if(validationMessages.has(event.target)){event.target.setCustomValidity('');validationMessages.delete(event.target);}},true);
  document.addEventListener('hoyee:languagechange',()=>{for(const input of document.querySelectorAll('input,select,textarea'))if(validationMessages.has(input))input.setCustomValidity(text(validationMessages.get(input)));});
  window.HoyeeLanguage={get current(){return language;},get locale(){return language==='zh'?'zh-CN':language;},set:select,t:text,normalize,render,
    originalText(node){return originals.get(node)?.original??node?.textContent??'';},
    // For local QA only. Nothing is sent off-device.
    missing(){return [...missing];}};
  // Some workspaces mount detached template fragments and then reveal them.
  // Revisit the complete UI after navigation as well as translating observed mutations.
  document.addEventListener('click',event=>{if(event.target.closest?.('.nav-item,[data-commerce-route]'))queueMicrotask(()=>render());});
  window.addEventListener('hashchange',()=>queueMicrotask(()=>render()));
  window.addEventListener('heyi:commerce-section-entered',()=>queueMicrotask(()=>render()));
  document.addEventListener('DOMContentLoaded',()=>render());
  compile();render();
  ensureCatalog();
})();
