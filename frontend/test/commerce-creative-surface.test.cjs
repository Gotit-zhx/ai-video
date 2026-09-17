const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const hash=char=>char.repeat(64),tick=()=>new Promise(resolve=>setImmediate(resolve));
function harness({multi=true,oldSnapshot=false,locked=false,artworkSource='SOURCE_PRODUCT',empty=false,selected=null,renderPolicy='LOCAL_PHOTO_V1',savedRenderPolicy='LOCAL_PHOTO_V1',photo=false,photoPrints,reusedArtworks,canonicalLegacy=false,noPlan=false}={}){
 const nodes=[],handlers={},calls=[],emitted=[];
 function element(tag='div'){
  const e={tag,children:[],handlers:{},dataset:{},value:'',hidden:false,disabled:false,checked:false,textContent:'',
   append(...children){for(const child of children){child.parentElement=this;this.children.push(child);}},
   before(child){child.parentElement=this.parentElement;},after(){},replaceChildren(...children){this.children=[];this.append(...children);},
   add(option){this.append(option);},setAttribute(k,v){this[k]=v;},removeAttribute(k){delete this[k];},
   addEventListener(type,fn){(this.handlers[type]||=[]).push(fn);},
   querySelectorAll(selector){const all=[];const visit=p=>p.children.forEach(c=>{all.push(c);visit(c);});visit(this);
    if(selector==='input:checked')return all.filter(n=>n.tag==='input'&&n.checked);
    if(selector==='input')return all.filter(n=>n.tag==='input');
    if(selector==='[data-cleanup-action]')return all.filter(n=>'cleanupAction'in n.dataset);
    if(selector==='[data-source-hint]')return all.filter(n=>'sourceHint'in n.dataset);
    return [];
   },reportValidity(){const required=this.querySelectorAll('input').filter(n=>n.required&&n.type==='radio');return required.every(n=>required.some(r=>r.name===n.name&&r.checked));}
  };nodes.push(e);return e;
 }
 const execution=element();
 function Option(label,value){const e=element('option');e.textContent=label;e.value=value;return e;}
 const config={sourceImageId:hash('f'),referenceSetId:'refs',surfacePrints:empty?[]:[{id:'first',artworkId:hash('a')},...(multi?[{id:'second',artworkId:hash('b')},{id:'first-again',artworkId:hash('a')}]:[])]};
 if(photo){config.photoPrints=photoPrints||config.surfacePrints.map((p,index)=>({...p,side:['FRONT','BACK','RIGHT'][index],x:.4,y:.25,width:.1,height:.1}));config.surfacePrints=[];}else if(canonicalLegacy)config.photoPrints=[];
 const frozen=Array.from({length:2},(_,index)=>({groupArtifacts:Object.fromEntries((config.photoPrints?.length?config.photoPrints:config.surfacePrints).map(p=>[p.artworkId,{id:'artifact-'+index+'-'+p.artworkId[0],storageKey:'tenant/private-key',contentSha256:hash(String(index+1))}]))}));
 const data={version:1,locked,sources:[{captureId:'capture',version:1,currentVersion:1,title:'Metadata source',images:[{id:'image',url:'https://example.invalid/source.png'}]}],
  plan:{printTemplateSnapshotId:oldSnapshot?'older':'snapshot',sources:[{captureId:'capture',version:1,imageId:'image',colorTargets:[{key:'black',label:'黑色'},{key:'white',label:'白色'}]}],variantsPerPrint:2,variationArtworkId:selected,artworkSource,renderPolicy,outputPolicy:'SOURCE_SKU_COLORS',...(artworkSource==='SAVED_ARTWORK'?{reusedArtworks:reusedArtworks??frozen}:{})},printTemplateConfiguration:config};
 const savedSources=data.plan.sources;if(noPlan)data.plan=null;
 const api=async(url,request)=>{calls.push({url,request});if(request)return {data:{...data,version:2,plan:{...JSON.parse(request.body),renderPolicy:savedRenderPolicy,outputPolicy:'SOURCE_SKU_COLORS',sources:savedSources}}};
  return url.endsWith('/creative-plan')?{data}:{data:[{snapshotId:'snapshot',name:'Template',version:1,configuration:oldSnapshot?{surfacePrints:[]}:config}]};};
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../commerce-creative-plan.js'),'utf8'),{
  document:{querySelector:selector=>selector==='.workbench-execution'?execution:null,createElement:element,getElementById:()=>null},
  window:{addEventListener:(name,fn)=>(handlers[name]||=[]).push(fn),dispatchEvent:event=>emitted.push(event)},
  Option,URL,location:{origin:'http://localhost'},api,FormData:class{},CustomEvent:class{constructor(type,{detail}){this.type=type;this.detail=detail;}},
  encodeURIComponent,console,Map,Set
 });
 const load=async()=>{handlers['heyi:workbench-batch-selected'][0]({detail:{batchId:'batch'}});await tick();await tick();};
 const form=nodes.find(n=>n.tag==='form');
 const submit=async()=>{await form.handlers.submit[0]({preventDefault(){}});await tick();};
 return {nodes,calls,emitted,load,submit,ready:()=>emitted.filter(e=>e.type==='heyi:creative-plan-state').at(-1)?.detail.ready};
}

test('one position marker uses source-product artwork without selecting the marker as style input',async()=>{
 const s=harness({multi:false});await s.load();await s.submit();
 const body=JSON.parse(s.calls.find(c=>c.request).request.body);
 assert.equal(body.variationArtworkId,null);assert.equal(body.artworkSource,'SOURCE_PRODUCT');
 assert.equal(body.printTemplateSnapshotId,'snapshot');
 assert.equal(s.nodes.filter(n=>n.name==='variationArtworkId').length,0);
 assert.equal(s.ready(),true);
});
test('different markers become separate read-only groups and repeated marker positions remain together',async()=>{
 const s=harness();await s.load();await s.submit();
 assert.equal(s.calls.filter(c=>c.request).length,1);
 assert.equal(s.nodes.filter(n=>n.name==='variationArtworkId').length,0);
 const group=s.nodes.find(n=>n.tag==='legend'&&n.textContent==='图片印花位置分组').parentElement;
 assert.equal(group.hidden,false);
 const options=group.children.find(n=>n.className==='creative-plan-images');assert.equal(options.children.length,2);
 assert.equal(options.children[0].children.find(n=>n.tag==='span').textContent,'图案组 1 · 2 个位置');
 assert.equal(options.children[1].children.find(n=>n.tag==='span').textContent,'图案组 2 · 1 个位置');
 assert.ok(options.children[1].children.some(n=>n.tag==='img'&&n.src.includes(hash('b'))));
 const body=JSON.parse(s.calls.find(c=>c.request).request.body);assert.equal(body.artworkSource,'SOURCE_PRODUCT');assert.equal(body.variationArtworkId,null);
});
test('saved older snapshot keeps its frozen marker grouping when the latest template differs',async()=>{
 const s=harness({oldSnapshot:true});await s.load();
 const groups=s.nodes.filter(n=>n.tag==='span'&&n.textContent.startsWith('图案组 '));assert.equal(groups.length,2);await s.submit();
 const body=JSON.parse(s.calls.find(c=>c.request).request.body);
 assert.equal(body.printTemplateSnapshotId,'older');assert.equal(body.artworkSource,'SOURCE_PRODUCT');
});
test('an unstarted legacy plan must be saved before it becomes ready for source-product grouping',async()=>{
 const s=harness({artworkSource:null,selected:hash('a')});await s.load();assert.equal(s.ready(),false);
 assert.ok(s.nodes.find(n=>n.tag==='details'&&n.className==='creative-plan').open);
 await s.submit();assert.equal(s.ready(),true);
 const body=JSON.parse(s.calls.find(c=>c.request).request.body);assert.equal(body.artworkSource,'SOURCE_PRODUCT');assert.equal(body.variationArtworkId,null);
});
test('a locked legacy run preserves its template artwork source and cannot be upgraded by submission',async()=>{
 const s=harness({locked:true,artworkSource:null,selected:hash('b'),renderPolicy:null});await s.load();assert.equal(s.ready(),false);
 const legend=s.nodes.find(n=>n.tag==='legend'&&n.textContent==='已固定的模板印花');assert.ok(legend);
 const options=legend.parentElement.children.find(n=>n.className==='creative-plan-images');
 assert.ok(options.children[0].children.some(n=>n.tag==='span'&&n.textContent.endsWith(' · 保留')));
 assert.ok(options.children[1].children.some(n=>n.tag==='span'&&n.textContent.endsWith(' · 本次裂变')));
 await s.submit();assert.equal(s.calls.filter(c=>c.request).length,0);
});
test('a surface template with no marker positions cannot be marked ready or submitted',async()=>{
 const s=harness({empty:true});await s.load();assert.equal(s.ready(),false);await s.submit();
 assert.equal(s.calls.filter(c=>c.request).length,0);
});
test('saved local-photo plans explain one product per color and variant without extra viewpoints',async()=>{
 const s=harness();await s.load();
 const hint=s.nodes.find(node=>node.className==='creative-output-rule');assert.equal(hint.hidden,false);
 assert.match(hint.textContent,/固定底图 · 本地套版 · 每色每款1张/);assert.match(hint.textContent,/共 4 张商品主图/);assert.match(hint.textContent,/尺码不增加出图/);
 assert.ok(s.nodes.some(node=>node.textContent.includes('左右、背面不额外生图')));
 const state=s.emitted.filter(event=>event.type==='heyi:creative-plan-state').at(-1).detail;
 assert.equal(state.renderPolicy,'LOCAL_PHOTO_V1');assert.equal(state.expectedOutputs,4);assert.equal(state.ready,true);
});
test('unstarted source-product plans with old rendering must be saved before launch',async()=>{
 const s=harness({renderPolicy:null});await s.load();assert.equal(s.ready(),false);
 assert.equal(s.nodes.find(node=>node.className==='creative-plan').open,true);
 assert.ok(s.nodes.some(node=>node.textContent.includes('保存一次升级为本地套版')));
 await s.submit();assert.equal(s.ready(),true);assert.equal(s.nodes.find(node=>node.className==='creative-plan').open,false);
 const body=JSON.parse(s.calls.find(call=>call.request).request.body);assert.equal(body.renderPolicy,undefined,'server chooses and freezes the rendering policy');
});
test('locked old source-product execution keeps its original output wording',async()=>{
 const s=harness({locked:true,renderPolicy:null});await s.load();await s.submit();
 assert.equal(s.calls.filter(call=>call.request).length,0);assert.equal(s.ready(),false);
 const hint=s.nodes.find(node=>node.className==='creative-output-rule');assert.match(hint.textContent,/沿用已固定的生成流程/);assert.doesNotMatch(hint.textContent,/本地套版/);
 assert.ok(s.nodes.some(node=>node.textContent.includes('任务沿用启动时的生成流程')));
});
test('a save response that did not freeze the new policy cannot mark an old draft ready',async()=>{
 const s=harness({renderPolicy:null,savedRenderPolicy:null});await s.load();await s.submit();
 assert.equal(s.ready(),false);assert.equal(s.nodes.find(node=>node.className==='creative-plan').open,true);
 assert.ok(s.nodes.some(node=>node.textContent.includes('保存结果尚未启用本地套版')));
});

test('photo prints use their actual artwork groups and keep shared positions together',async()=>{
 const s=harness({photo:true});await s.load();assert.equal(s.ready(),true);
 const legend=s.nodes.find(node=>node.tag==='legend'&&node.textContent==='图片印花位置分组');assert.ok(legend);const choices=legend.parentElement.children.find(node=>node.className==='creative-plan-images');assert.equal(choices.children.length,2);
 assert.equal(choices.children[0].children.find(node=>node.tag==='span').textContent,'图案组 1 · 2 个位置');await s.submit();assert.equal(JSON.parse(s.calls.find(call=>call.request).request.body).artworkSource,'SOURCE_PRODUCT');
 assert.ok(!s.nodes.some(node=>/模型印花|模板模型/.test(node.textContent)));
});

test('canonical old layouts with an empty photo array keep their surface artwork groups',async()=>{
 const s=harness({canonicalLegacy:true});await s.load();assert.equal(s.ready(),true);await s.submit();assert.equal(s.calls.filter(call=>call.request).length,1);
 const legend=s.nodes.find(node=>node.tag==='legend'&&node.textContent==='图片印花位置分组'),groups=legend.parentElement.children.find(node=>node.className==='creative-plan-images');assert.equal(groups.children.length,2);
});

test('saved transparent artwork plans are read-only and never rewritten as SOURCE_PRODUCT',async()=>{
 for(const locked of [false,true]){
  const s=harness({photo:true,artworkSource:'SAVED_ARTWORK',locked});await s.load();await s.submit();assert.equal(s.calls.filter(call=>call.request).length,0);
  assert.ok(s.nodes.some(node=>node.textContent.includes('使用已有透明印花，直接套版')));assert.ok(s.nodes.some(node=>node.tag==='summary'&&node.textContent.includes('2 款（只读）')));
  assert.ok(!s.nodes.some(node=>/请保存更新后的规则|升级为本地套版/.test(node.textContent)));
  const form=s.nodes.find(node=>node.tag==='form');assert.equal(form.children[0].disabled,true);assert.equal(s.ready(),!locked);
  assert.equal(s.nodes.find(node=>node.className==='creative-source-uploads').hidden,true);
 }
});

test('reused groups preview each frozen artwork rather than the placeholder marker',async()=>{
 const s=harness({photo:true,artworkSource:'SAVED_ARTWORK',locked:true});await s.load();
 const legend=s.nodes.find(node=>node.tag==='legend'&&node.textContent==='已固定的透明印花与位置');const choices=legend.parentElement.children.find(node=>node.className==='creative-plan-images');assert.equal(choices.children.length,2);
 const images=choices.children.flatMap(node=>node.children.filter(child=>child.tag==='img'));assert.equal(images.length,4);assert.ok(images.every(image=>image.src.startsWith('/api/v1/commerce-artwork-library/artifact-')));assert.ok(images.every(image=>!image.src.includes('tenant/')));
 assert.equal(choices.children[0].children.find(node=>node.tag==='span').textContent,'位置组 1 · 2 个位置 · 2 款素材');
});

test('an incomplete saved-artwork record stays read-only and cannot silently start new generation',async()=>{
 const s=harness({photo:true,artworkSource:'SAVED_ARTWORK',reusedArtworks:[{groupArtifacts:{[hash('a')]:{id:'one',contentSha256:hash('1')}}}]});await s.load();assert.equal(s.ready(),false);await s.submit();assert.equal(s.calls.filter(call=>call.request).length,0);
 assert.ok(s.nodes.some(node=>node.textContent.includes('已有印花记录不完整')));assert.ok(!s.nodes.some(node=>node.textContent.includes('保存一次升级')));
});

test('Grok scene policy is ready without upgrading and explains a shared background call',async()=>{
 const s=harness({photo:true,renderPolicy:'GROK_SCENE_V1',savedRenderPolicy:'GROK_SCENE_V1'});await s.load();assert.equal(s.ready(),true);
 const hint=s.nodes.find(node=>node.className==='creative-output-rule');assert.match(hint.textContent,/提取商品背景风格 · 同商品多款多色共用/);assert.match(hint.textContent,/场景氛围、配色、材质与光线重新设计背景/);assert.match(hint.textContent,/共 4 张商品主图/);
 assert.ok(!s.nodes.some(node=>node.textContent.includes('请保存更新后的规则')));await s.submit();assert.equal(s.ready(),true);assert.equal(s.emitted.filter(event=>event.type==='heyi:creative-plan-state').at(-1).detail.renderPolicy,'GROK_SCENE_V1');
});

test('reusing artwork under scene policy still discloses the Grok background preparation',async()=>{
 const s=harness({photo:true,artworkSource:'SAVED_ARTWORK',renderPolicy:'GROK_SCENE_V1',locked:true});await s.load();await s.submit();assert.equal(s.calls.filter(call=>call.request).length,0);
 assert.ok(s.nodes.some(node=>node.textContent.includes('不再生成新印花。提取商品背景风格')));assert.ok(s.nodes.some(node=>node.textContent.includes('多款多色共用')));assert.ok(!s.nodes.some(node=>/总.*0.*调用|无需.*生图|零.*生图/.test(node.textContent)));
});

test('photo creative configuration saves natural drape explicitly without changing artwork selection',async()=>{
 const s=harness({photo:true,renderPolicy:'GROK_SCENE_V1',savedRenderPolicy:'GROK_DRAPE_REUSE_V1'});await s.load();
 const mode=s.nodes.find(n=>n.tag==='select'&&n['aria-label']==='商品呈现方式');assert.equal(mode.value,'GROK_SCENE_V1');assert.equal(mode.disabled,false);
 mode.value='GROK_DRAPE_REUSE_V1';mode.handlers.change[0]();assert.equal(s.ready(),false);
 assert.ok(s.nodes.some(n=>n.textContent.includes('本批最多 1 次')));
 await s.submit();const request=JSON.parse(s.calls.find(c=>c.request).request.body);assert.equal(request.renderPolicy,'GROK_DRAPE_REUSE_V1');assert.equal(request.artworkSource,'SOURCE_PRODUCT');assert.equal(s.ready(),true);
 assert.ok(s.nodes.some(n=>n.textContent.includes('先精确套版，再生成一次自然搭放母图')));
 const state=s.emitted.filter(e=>e.type==='heyi:creative-plan-state').at(-1).detail;assert.equal(state.sourceCount,1);assert.equal(state.expectedOutputs,4);
});
test('frozen scene and drape plans preserve their saved policy and cannot be switched by the form',async()=>{
 for(const policy of ['GROK_SCENE_V1','GROK_DRAPE_REUSE_V1']){
  const s=harness({photo:true,renderPolicy:policy,locked:true});await s.load();
  const mode=s.nodes.find(n=>n.tag==='select'&&n['aria-label']==='商品呈现方式');assert.equal(mode.value,policy);assert.equal(mode.disabled,true);
  mode.value=policy==='GROK_SCENE_V1'?'GROK_DRAPE_REUSE_V1':'GROK_SCENE_V1';mode.handlers.change[0]();await s.submit();
  assert.equal(mode.value,policy);assert.equal(s.calls.filter(c=>c.request).length,0);
 }
});
test('historical non-photo creative plans cannot select natural drape',async()=>{
 const s=harness();await s.load();const mode=s.nodes.find(n=>n.tag==='select'&&n['aria-label']==='商品呈现方式');
 assert.equal(mode.parentElement.hidden,true);assert.equal(mode.disabled,true);mode.value='GROK_DRAPE_REUSE_V1';await s.submit();
 assert.equal(s.calls.filter(c=>c.request).length,0);assert.ok(s.nodes.some(n=>n.textContent==='自然搭放需要已保存的图片印花模板。'));
});

test('creative mode explains white-template requirement and keeps back and side positions eligible',async()=>{
 const s=harness({photo:true,renderPolicy:'GROK_SCENE_V1'});await s.load();const mode=s.nodes.find(n=>n.tag==='select'&&n['aria-label']==='商品呈现方式');
 assert.equal(mode.children[1].disabled,false);mode.value='GROK_DRAPE_REUSE_V1';mode.handlers.change[0]();
 const note=mode.parentElement.children.find(n=>n.tag==='small');assert.match(note.textContent,/纯白服装模板/);assert.match(note.textContent,/背面、侧面补图沿用原有方式/);assert.match(note.textContent,/自动检查模板布色/);
});

test('creative unsupported drape cannot be submitted and default scene remains available',async()=>{
 const front={id:'front',artworkId:hash('a'),side:'FRONT',x:.4,y:.25,width:.1,height:.1};
 for(const photoPrints of [[{...front,side:'BACK'}],[front,{...front,id:'front-two'}],[{...front,width:.4,height:.3}],[{...front,y:.55}]]){
  const s=harness({photo:true,photoPrints,renderPolicy:'GROK_SCENE_V1'});await s.load();const mode=s.nodes.find(n=>n.tag==='select'&&n['aria-label']==='商品呈现方式');
  assert.equal(mode.children[1].disabled,true);assert.equal(mode.disabled,false);assert.match(mode.parentElement.children.find(n=>n.tag==='small').textContent,/正面胸花/);
  mode.value='GROK_DRAPE_REUSE_V1';await s.submit();assert.equal(s.calls.filter(c=>c.request).length,0);
  mode.handlers.change[0]();assert.equal(mode.value,'GROK_DRAPE_REUSE_V1');assert.match(mode.parentElement.children.find(n=>n.tag==='small').textContent,/已保留自然搭放选择/);
 }
});

test('new creative plan defaults eligible templates to drape and preserves an explicit scene selection',async()=>{
 const s=harness({photo:true,noPlan:true,savedRenderPolicy:'GROK_DRAPE_REUSE_V1'});await s.load();const template=s.nodes.find(n=>n.tag==='select'&&n.required),mode=s.nodes.find(n=>n.tag==='select'&&n['aria-label']==='商品呈现方式');
 template.value='snapshot';template.handlers.change[0]();assert.equal(mode.value,'GROK_DRAPE_REUSE_V1');
 s.nodes.find(n=>n.type==='radio').checked=true;await s.submit();assert.equal(JSON.parse(s.calls.find(c=>c.request).request.body).renderPolicy,'GROK_DRAPE_REUSE_V1');
 const manual=harness({photo:true,noPlan:true,savedRenderPolicy:'GROK_SCENE_V1'});await manual.load();const t=manual.nodes.find(n=>n.tag==='select'&&n.required),m=manual.nodes.find(n=>n.tag==='select'&&n['aria-label']==='商品呈现方式');
 t.value='snapshot';t.handlers.change[0]();m.value='GROK_SCENE_V1';m.handlers.change[0]();t.handlers.change[0]();assert.equal(m.value,'GROK_SCENE_V1');
 manual.nodes.find(n=>n.type==='radio').checked=true;await manual.submit();assert.equal(JSON.parse(manual.calls.find(c=>c.request).request.body).renderPolicy,'GROK_SCENE_V1');
});

test('new unsupported creative templates default scene while saved scene plans stay unchanged',async()=>{
 const s=harness({photo:true,noPlan:true,photoPrints:[{id:'back',artworkId:hash('a'),side:'BACK',x:.4,y:.25,width:.1,height:.1}]});await s.load();const template=s.nodes.find(n=>n.tag==='select'&&n.required),mode=s.nodes.find(n=>n.tag==='select'&&n['aria-label']==='商品呈现方式');
 template.value='snapshot';template.handlers.change[0]();assert.equal(mode.value,'GROK_SCENE_V1');assert.match(mode.parentElement.children.find(n=>n.tag==='small').textContent,/当前模板使用原有模式/);
 const saved=harness({photo:true,renderPolicy:'GROK_SCENE_V1',savedRenderPolicy:'GROK_SCENE_V1'});await saved.load();const savedMode=saved.nodes.find(n=>n.tag==='select'&&n['aria-label']==='商品呈现方式');assert.equal(savedMode.value,'GROK_SCENE_V1');await saved.submit();assert.equal(JSON.parse(saved.calls.find(c=>c.request).request.body).renderPolicy,'GROK_SCENE_V1');
});
