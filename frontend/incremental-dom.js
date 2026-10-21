/* Explicit, scoped DOM updates for polling views. No global DOM overrides. */
(() => {
  'use strict';
  const keyAttributes=['data-update-key','id','data-image-id','data-general-task-id',
    'data-product-capture-id','data-monitor-task-id','data-view-automation-job','data-style-id',
    'data-item-id','data-asset-id','data-ai-video-task-id','data-video-task-id'];
  function key(node){
    if(node.nodeType!==1)return '';
    for(const attr of keyAttributes)if(node.hasAttribute(attr))return attr+':'+node.getAttribute(attr);
    return '';
  }
  const compatible=(a,b)=>a.nodeType===b.nodeType&&a.nodeName===b.nodeName&&a.namespaceURI===b.namespaceURI;
  function patch(node,next){
    if(node.nodeType!==1){if(node.nodeValue!==next.nodeValue)node.nodeValue=next.nodeValue;return node;}
    const focused=node===document.activeElement;
    // details.open and edited form values belong to the user until their
    // authoritative rendered value changes. Do not reset them on every poll.
    const edited=/^(INPUT|TEXTAREA|SELECT)$/.test(node.tagName);
    const value=edited?next.value:undefined,checked=next.checked;
    const priorValue=node.getAttribute('value')??(node.tagName==='TEXTAREA'?node.textContent:null);
    const nextValue=next.getAttribute('value')??(next.tagName==='TEXTAREA'?next.textContent:null);
    for(const attr of [...node.attributes]){
      if(attr.name==='open'&&node.tagName==='DETAILS'||attr.name==='_echarts_instance_')continue;
      if(!next.hasAttribute(attr.name))node.removeAttribute(attr.name);
    }
    for(const attr of [...next.attributes]){
      if(attr.name==='open'&&node.tagName==='DETAILS')continue;
      if(node.getAttribute(attr.name)!==attr.value)node.setAttribute(attr.name,attr.value);
    }
    // Programmatic renderers use event properties so handlers always receive
    // the retained node and current row/version, without accumulating listeners.
    for(const name of ['onclick','onchange','oninput','ontoggle'])if(next[name])node[name]=next[name];
    if(next.financePlot){node.financePlot=next.financePlot;return node;}
    if(next.hrPlot){node.hrPlot=next.hrPlot;return node;}
    if(node.tagName==='TEXTAREA'){
      if(!focused&&priorValue!==nextValue&&node.value!==next.value)node.value=next.value;
      if(priorValue!==nextValue)node.defaultValue=next.defaultValue;
      return node;
    }
    children(node,next);
    if(edited&&!focused){
      if(node.tagName==='SELECT'&&node.value!==value)node.value=value;
      else if(node.tagName==='INPUT'&&priorValue!==nextValue&&node.type!=='file'&&node.value!==value)node.value=value;
      if(node.tagName==='INPUT'&&node.checked!==checked)node.checked=checked;
    }
    return node;
  }
  function children(parent,next){
    const old=[...parent.childNodes],keyed=new Map(old.map(n=>[key(n),n]).filter(([k])=>k));
    const used=new Set();let cursor=parent.firstChild;
    for(const wanted of [...next.childNodes]){
      const k=key(wanted);
      let node=k?keyed.get(k):cursor&&!key(cursor)&&compatible(cursor,wanted)?cursor:null;
      if(node&&(!compatible(node,wanted)||used.has(node)))node=null;
      if(!node&&!k)node=old.find(n=>!used.has(n)&&!key(n)&&compatible(n,wanted)
        &&n.nodeType===1&&n.getAttribute('class')===wanted.getAttribute('class'));
      if(node){
        used.add(node);
        if(node!==cursor)parent.insertBefore(node,cursor);
        patch(node,wanted);
      }else {node=wanted;parent.insertBefore(node,cursor);}
      cursor=node.nextSibling;
    }
    for(const node of old)if(!used.has(node)&&node.parentNode===parent)node.remove();
    return parent;
  }
  function html(parent,markup){
    const next=document.createElement(parent.localName||'div');
    next.innerHTML=markup;children(parent,next);return parent;
  }
  globalThis.IncrementalDOM={patch,children,html};
})();
