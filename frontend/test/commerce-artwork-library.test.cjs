const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const flush=()=>new Promise(resolve=>setImmediate(resolve));
function setup(request){
 const nodes=[],calls=[];
 function node(tag){const n={tag,children:[],events:{},textContent:'',value:'',dataset:{},hidden:false,disabled:false,
  append(...v){this.children.push(...v);},replaceChildren(...v){this.children=[...v];},setAttribute(k,v){this[k]=v;},remove(){},
  addEventListener(k,fn){this.events[k]=fn;},click(){if(!this.disabled)return this.events.click?.();},
  querySelectorAll(selector){const all=[];const walk=x=>x.children.forEach(c=>{all.push(c);walk(c);});walk(this);return all.filter(n=>n.tag===selector);}};nodes.push(n);return n;}
 const api=async url=>{calls.push(url);return request?request(url):{data:{page:0,hasMore:false,items:[{id:'asset-1',name:'狐狸',batchName:'历史批次',imageUrl:'/api/v1/commerce-artwork-library/asset-1/image'},{id:'asset-2',name:'小狗',imageUrl:'/api/v1/commerce-artwork-library/asset-2/image'}]}};};
 const env={document:{getElementById:()=>null,createElement:node},api,encodeURIComponent};vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../commerce-artwork-library.js'),'utf8'),env);
 const host=node('section'),picker=env.CommerceArtworkPicker.mount({host,api});
 const all=()=>{const result=[];const walk=x=>{result.push(x);x.children.forEach(walk);};walk(host);return result;};
 const buttons=text=>all().filter(x=>x.tag==='button'&&x.textContent===text);
 return {picker,calls,host,all,buttons};
}
const config={sourceImageId:'source',photoPrints:[{id:'p1',side:'FRONT',artworkId:'group-a'},{id:'p2',side:'BACK',artworkId:'group-a'},{id:'p3',side:'LEFT',artworkId:'group-b'}]};
test('defaults to generation and reuse requires a material for every distinct marker group',async()=>{
 const h=setup();assert.equal(h.picker.getSelection(),null);h.picker.setTemplate(config);h.buttons('使用已有印花')[0].click();
 assert.equal(h.buttons('选择印花').length,2,'front/back share one group');assert.throws(()=>h.picker.getSelection(),/图案组 1/);
 h.buttons('选择印花')[0].click();await flush();h.buttons('使用此印花')[0].click();assert.throws(()=>h.picker.getSelection(),/图案组 2/);
 h.buttons('选择印花')[0].click();await flush();h.buttons('使用此印花')[1].click();
 assert.deepEqual(JSON.parse(JSON.stringify(h.picker.getSelection())),[{groupArtifacts:{'group-a':'asset-1','group-b':'asset-2'}}]);
 assert.equal(h.calls.length,1,'cached catalogue, no generation API calls');
});
test('adding a variant requires complete choices and removing it preserves the first design',async()=>{
 const h=setup();h.picker.setTemplate({photoPrints:[config.photoPrints[0]]});h.buttons('使用已有印花')[0].click();h.buttons('选择印花')[0].click();await flush();h.buttons('使用此印花')[0].click();
 h.buttons('＋ 增加一款')[0].click();assert.equal(h.picker.getState().count,2);assert.throws(()=>h.picker.getSelection(),/第 2 款/);
 h.buttons('移除这款')[1].click();assert.equal(h.picker.getSelection()[0].groupArtifacts['group-a'],'asset-1');
 const result=h.picker.getSelection();result[0].groupArtifacts['group-a']='tampered';assert.equal(h.picker.getSelection()[0].groupArtifacts['group-a'],'asset-1');
});
test('same template retains choices; switching layout and organization reset do not leak them',async()=>{
 const h=setup();h.picker.setTemplate({photoPrints:[config.photoPrints[0]]});h.buttons('使用已有印花')[0].click();h.buttons('选择印花')[0].click();await flush();h.buttons('使用此印花')[0].click();
 h.picker.setTemplate({photoPrints:[config.photoPrints[0]]});assert.equal(h.picker.getSelection()[0].groupArtifacts['group-a'],'asset-1');
 h.picker.setTemplate({photoPrints:[config.photoPrints[2]]});assert.throws(()=>h.picker.getSelection(),/选择印花/);
 h.picker.reset();assert.equal(h.picker.getSelection(),null);assert.equal(h.picker.getState().count,0);
});
test('locked picker cannot change source, remove designs or change selected artwork',async()=>{
 const h=setup();h.picker.setTemplate(config);h.buttons('使用已有印花')[0].click();h.picker.setLocked(true);
 assert.ok(h.buttons('选择印花').every(b=>b.disabled));assert.equal(h.buttons('生成新印花')[0].disabled,true);
 h.buttons('生成新印花')[0].click();assert.equal(h.picker.getState().reuse,true);h.picker.setLocked(false);h.buttons('生成新印花')[0].click();assert.equal(h.picker.getSelection(),null);
});
test('a late catalogue response after company reset cannot restore old company artworks',async()=>{
 let resolve;const response=new Promise(r=>resolve=r),h=setup(()=>response);h.picker.setTemplate(config);h.buttons('使用已有印花')[0].click();h.buttons('选择印花')[0].click();
 h.picker.reset();resolve({data:{page:0,hasMore:false,items:[{id:'old',name:'old company'}]}});await flush();
 assert.equal(h.buttons('使用此印花').length,0);assert.equal(h.picker.getState().busy,false);assert.equal(h.picker.getSelection(),null);
});
test('library failure remains retryable and never switches reuse back to paid generation',async()=>{
 const h=setup(()=>Promise.reject(new Error('服务暂不可用')));h.picker.setTemplate(config);h.buttons('使用已有印花')[0].click();h.buttons('选择印花')[0].click();await flush();
 assert.equal(h.picker.getState().reuse,true);assert.equal(h.picker.getState().busy,false);assert.throws(()=>h.picker.getSelection());assert.ok(h.all().some(n=>n.textContent.includes('点击“读取印花”重试')));
});
test('identical print combinations cannot accidentally create duplicate variants',async()=>{
 const h=setup();h.picker.setTemplate({photoPrints:[config.photoPrints[0]]});h.buttons('使用已有印花')[0].click();h.buttons('选择印花')[0].click();await flush();h.buttons('使用此印花')[0].click();
 h.buttons('＋ 增加一款')[0].click();h.buttons('选择印花')[0].click();await flush();h.buttons('使用此印花')[0].click();assert.throws(()=>h.picker.getSelection(),/相同的印花组合/);
});
test('historical spatial templates require conversion before existing-artwork reuse',()=>{
 const h=setup();h.picker.setTemplate({photoPrints:[],surfacePrints:[{artworkId:'legacy',side:'FRONT'}]});h.buttons('使用已有印花')[0].click();assert.throws(()=>h.picker.getSelection(),/四面图片模板/);assert.equal(h.buttons('选择印花').length,0);
});
