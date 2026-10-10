const test=require('node:test'), assert=require('node:assert/strict'), fs=require('node:fs'), vm=require('node:vm');
function harness(){
 const events={}, documentEvents={}, nodes=[];
 function element(tag='div'){
  const classes=new Set(), listeners={};
  const node={tag,children:[],dataset:{},hidden:false,textContent:'',
   style:{setProperty(key,value){this[key]=value;}},getAttribute(key){return this[key];},
   classList:{toggle:(key,on)=>on?classes.add(key):classes.delete(key),contains:key=>classes.has(key)},
   append(...children){children.forEach(child=>child.parentElement=this);this.children.push(...children);},replaceChildren(...children){this.children=children;},remove(){this.parentElement?.children.splice(this.parentElement.children.indexOf(this),1);},
   setAttribute(key,value){this[key]=value;},removeAttribute(key){delete this[key];},
   addEventListener:(name,fn)=>{listeners[name]=fn;}, fire:name=>listeners[name]?.()};
  nodes.push(node);return node;
 }
 const page=element(), journey=element(), panel=element(), document={hidden:false,
  getElementById:id=>id==='product-publish-page'?page:panel,
  querySelector:()=>journey,createElement:element,
  addEventListener:(name,fn)=>{documentEvents[name]=fn;}};
 const location={hash:'#commerce-designs'};
 vm.runInNewContext(fs.readFileSync(require.resolve('../commerce-motion.js'),'utf8'),{document,window:{addEventListener:(name,fn)=>(events[name]??=[]).push(fn)},location,MutationObserver:class{observe(){}},encodeURIComponent});
 const fire=(name,detail)=>events[name]?.forEach(fn=>fn({detail}));
 const get=className=>nodes.find(node=>node.className===className);
 const select=()=>fire('heyi:workbench-batch-selected',{batchId:'one',creativeOnly:true});
 const run=(status='RUNNING',stage='VARIATE',batchId='one')=>fire('heyi:execution-state',{run:{batchId,status,stage}});
 return {fire,get,page,journey,select,run,nodes,document,documentEvents,location};
}
test('real running, terminal, and stale states control the stage without creating requests',()=>{
 const h=harness(),hero=h.get('studio-live-stage');assert.equal(hero.hidden,true);
 h.select();h.run();assert.equal(hero.hidden,false);assert.equal(hero.dataset.status,'RUNNING');
 assert.equal(h.page.dataset.motionPaused,'false');assert.match(h.get('studio-live-title').textContent,/新模样/);
 h.fire('heyi:execution-connection',{batchId:'one',available:false});assert.equal(hero.dataset.status,'STALE');assert.equal(h.page.dataset.motionPaused,'true');
 h.run('FAILED');assert.equal(hero.dataset.status,'FAILED');assert.match(h.get('studio-live-title').textContent,/已停止/);
 h.run('UNKNOWN');assert.equal(hero.dataset.status,'UNKNOWN');
 h.run('COMPLETED','REVIEW');assert.equal(hero.dataset.status,'COMPLETED');
 assert.ok(h.get('studio-live-track').children[3].classList.contains('is-active'));
});
test('unchanged polling preserves animated DOM and uses archived counts without simulated percentages',()=>{
 const h=harness();h.select();h.run();const art=h.get('studio-live-art'),children=[...art.children];
 h.run();assert.deepEqual(art.children,children);
 h.fire('heyi:artifacts-updated',{batchId:'one',ready:3,total:5});assert.equal(h.get('studio-live-count').textContent,'已归档 3 张');
 h.fire('heyi:artifacts-updated',{batchId:'other',ready:99});assert.equal(h.get('studio-live-count').textContent,'已归档 3 张');
});
test('manual motion toggle and hidden page pause decorations without affecting execution',()=>{
 const h=harness();h.select();h.run();const toggle=h.get('studio-motion-toggle');toggle.fire('click');
 assert.equal(h.page.dataset.motionPaused,'true');assert.equal(h.get('studio-live-stage').dataset.status,'RUNNING');
 h.run();assert.equal(h.page.dataset.motionPaused,'true');toggle.fire('click');assert.equal(h.page.dataset.motionPaused,'false');
 h.document.hidden=true;h.documentEvents.visibilitychange();assert.equal(h.page.dataset.motionPaused,'true');
 h.document.hidden=false;h.documentEvents.visibilitychange();assert.equal(h.page.dataset.motionPaused,'false');
 h.location.hash='#shops';h.fire('hashchange');assert.equal(h.get('studio-live-stage').hidden,true);assert.equal(h.page.dataset.motionPaused,'true');
});
test('batch switch clears source and ignores late events from previous scope',()=>{
 const h=harness();h.select();h.run();h.fire('heyi:design-source-selected',{captureId:'source'});
 const image=h.get('studio-live-image').children[0];assert.match(image.src,/one.*source\/image$/);image.fire('load');assert.equal(h.get('studio-live-image').dataset.empty,'false');
 h.fire('heyi:workbench-batch-selected',{batchId:'two'});assert.equal(h.get('studio-live-stage').hidden,true);assert.equal(h.get('studio-live-image').children.length,0);
 image.fire('error');assert.equal(h.get('studio-live-origin').textContent,'来源商品');
 h.run('RUNNING','EXTRACT','one');assert.equal(h.get('studio-live-stage').hidden,true);
 h.run('RUNNING','EXTRACT','two');assert.equal(h.get('studio-live-stage').hidden,false);
 h.fire('heyi:company-reset');assert.equal(h.get('studio-live-stage').hidden,true);
});

test('technical positioning guides stay out of the animation and real results appear only after archival',()=>{
 const h=harness();h.select();h.run('RUNNING','MOCKUP');h.fire('heyi:design-source-selected',{captureId:'source'});
 const source=h.get('studio-live-image').children[0];source.fire('load');
 const job={id:'mockup',captureId:'source',kind:'MOCKUP_FRONT',status:'SUBMITTING'};
 const guide={...job,id:'mockup-guide',kind:'PLACEMENT_GUIDE_FRONT',status:'READY',imageUrl:'/guide'};
 h.fire('heyi:artifacts-updated',{batchId:'one',ready:0,previews:[job,guide]});
 assert.equal(h.get('studio-live-image').children.length,1);
 assert.equal(h.get('studio-live-stage').dataset.view,'source');assert.equal(h.get('studio-live-origin').textContent,'来源商品');
 h.run('FAILED','MOCKUP');assert.equal(h.get('studio-live-stage').dataset.status,'FAILED');assert.equal(h.get('studio-live-image').children[0],source);
 h.fire('heyi:artifacts-updated',{batchId:'one',ready:1,previews:[{...job,status:'READY',imageUrl:'/actual',colorLabel:'粉色'},guide]});
 const result=h.get('studio-live-image').children[1];assert.equal(result.src,'/actual');result.fire('load');
 assert.equal(h.get('studio-live-stage').dataset.view,'result');assert.match(h.get('studio-live-origin').textContent,/已生成效果图 · 粉色/);
 h.fire('heyi:workbench-scope-reset');result.fire('load');assert.equal(h.get('studio-live-stage').dataset.view,'source');
});

const skuColors=[{key:'white',label:'PURE WHITE'},{key:'black',label:'Black / 1'},{key:'pink',label:'Pink / 1'}];
function colorEvent(h,previews,extra={}){h.fire('heyi:artifacts-updated',{batchId:'one',ready:0,renderPolicy:'LOCAL_PHOTO_V1',colorSources:[{captureId:'source',colors:skuColors}],previews,...extra});}
const colorJob=(key,status='READY')=>({id:'base-'+key,captureId:'source',kind:'BASE_COLOR',colorKey:key,colorLabel:skuColors.find(color=>color.key===key)?.label,status,imageUrl:status==='READY'?'/base-'+key:undefined,colorHex:key==='white'?'#eeeeec':key==='black'?'#202225':'#dea6af'});
test('color study starts from real SKU targets and only archived BASE_COLOR rows fill the count',()=>{
 const h=harness();h.select();h.run('RUNNING','EXTRACT');h.fire('heyi:design-source-selected',{captureId:'source'});
 colorEvent(h,[colorJob('white'),colorJob('black','SUBMITTING')]);
 assert.equal(h.get('studio-live-stage').dataset.scene,'colors');assert.equal(h.get('studio-color-count').textContent,'1 / 3');
 const cards=h.get('studio-color-grid').children;assert.equal(cards.length,3);
 assert.deepEqual(cards.map(card=>card.dataset.state),['ready','working','waiting']);
 assert.equal(cards[0].children[1].textContent,'PURE WHITE');
 assert.equal(cards[0].style['--sku-color'],'#eeeeec');assert.equal(cards[2].dataset.sampled,'false');
 assert.equal(h.get('studio-live-image').children.length,1);assert.equal(h.get('studio-color-note').textContent,'已准备 1 色 · 按原商品颜色逐一展开');
 const before=[...cards];colorEvent(h,[colorJob('white'),colorJob('black','SUBMITTING')]);
 assert.deepEqual(h.get('studio-color-grid').children,before);
 colorEvent(h,[...skuColors.map(color=>colorJob(color.key))]);
 assert.equal(h.get('studio-color-count').textContent,'3 / 3');assert.equal(h.get('studio-live-stage').dataset.colorsReady,'true');
 assert.equal(h.get('studio-color-title').textContent,'原色已就位，接续创作');
 colorEvent(h,[...skuColors.map(color=>colorJob(color.key)),{id:'extract',captureId:'source',kind:'EXTRACT',status:'SUBMITTING'}]);
 assert.equal(h.get('studio-live-stage').dataset.scene,'extract');assert.equal(h.get('studio-color-deck').hidden,true);
});
test('color preparation ignores other sources, deduplicates retries, and never fabricates a complete palette',()=>{
 const h=harness();h.select();h.run('RUNNING','EXTRACT');h.fire('heyi:design-source-selected',{captureId:'source'});
 const white=colorJob('white');
 colorEvent(h,[{...white,status:'FAILED'},white,{...colorJob('black'),captureId:'other'}]);
 assert.equal(h.get('studio-color-grid').children.length,3);assert.equal(h.get('studio-color-count').textContent,'1 / 3');
 h.fire('heyi:artifacts-updated',{batchId:'one',previews:[white],colorSources:[]});
 assert.equal(h.get('studio-color-count').textContent,'已就绪 1 色');assert.equal(h.get('studio-live-stage').dataset.colorsReady,'false');
 assert.doesNotMatch(h.get('studio-color-note').textContent,/所有.*完成/);
 colorEvent(h,[{...white,colorHex:'url(https://invalid.example)'}]);
 assert.equal(h.get('studio-color-grid').children[0].dataset.sampled,'false');assert.equal(h.get('studio-color-grid').children[0].style['--sku-color'],'#9b9b98');
});
test('failed and disconnected color preparation holds its actual state and scope reset clears the palette',()=>{
 const h=harness();h.select();h.run('RUNNING','EXTRACT');h.fire('heyi:design-source-selected',{captureId:'source'});
 colorEvent(h,[colorJob('white'),colorJob('black','SUBMITTING')]);
 h.run('FAILED','EXTRACT');assert.match(h.get('studio-color-note').textContent,/已停止/);assert.equal(h.get('studio-color-count').textContent,'1 / 3');
 h.fire('heyi:execution-connection',{batchId:'one',available:false});assert.match(h.get('studio-color-note').textContent,/同步后台状态/);
 assert.equal(h.page.dataset.motionPaused,'true');
 h.fire('heyi:company-reset');assert.equal(h.get('studio-color-grid').children.length,0);assert.equal(h.get('studio-color-deck').hidden,true);
 colorEvent(h,[...skuColors.map(color=>colorJob(color.key))]);assert.equal(h.get('studio-color-grid').children.length,0);
});
test('color animation has reduced-motion and stopped-state rules with no timed progress code',()=>{
 const css=fs.readFileSync(require.resolve('../commerce-motion.css'),'utf8');
 assert.match(css,/@media\(prefers-reduced-motion:reduce\)[\s\S]*studio-color-token/);
 assert.match(css,/data-motion-paused="true"[^}]*studio-color-token/);
 assert.match(css,/data-colors-ready="false"[^}]*cm-color-scan/);
 const code=fs.readFileSync(require.resolve('../commerce-motion.js'),'utf8');
 assert.doesNotMatch(code,/setInterval|setTimeout|Math\.random|\.fetch\(/);
});

test('local mockup motion uses the frozen workflow and actual product count without calling masters products',()=>{
 const h=harness();h.select();h.fire('heyi:creative-plan-state',{batchId:'one',version:1,renderPolicy:'LOCAL_PHOTO_V1'});
 h.run('RUNNING','MOCKUP');h.fire('heyi:design-source-selected',{captureId:'source'});
 assert.match(h.get('studio-live-title').textContent,/本地套版/);assert.match(h.get('studio-live-description').textContent,/透明母版/);
 assert.equal(h.get('studio-live-track').children[2].textContent,'03 本地套版');
 h.fire('heyi:artifacts-updated',{batchId:'one',renderPolicy:'LOCAL_PHOTO_V1',ready:0,total:6,previews:[
  {id:'matte',captureId:'source',kind:'MATTE_G1',status:'READY',imageUrl:'/master'}]});
 let shown=h.get('studio-live-image').children[1];assert.equal(shown.src,'/master');shown.fire('load');
 assert.equal(h.get('studio-live-origin').textContent,'透明印花母版 · 非商品成品');assert.equal(h.get('studio-live-count').textContent,'已归档 0 张商品主图');
 h.fire('heyi:artifacts-updated',{batchId:'one',renderPolicy:'LOCAL_PHOTO_V1',ready:1,total:6,previews:[
  {id:'product',captureId:'source',kind:'MOCKUP_FRONT',status:'READY',imageUrl:'/product',colorLabel:'黑色'}]});
 shown=h.get('studio-live-image').children[1];assert.equal(shown.src,'/product');shown.fire('load');
 assert.equal(h.get('studio-live-origin').textContent,'本地套版主图 · 黑色');
 h.run('WAITING_INPUT','MOCKUP');assert.equal(h.get('studio-live-stage').dataset.status,'WAITING_INPUT');
 assert.doesNotMatch(h.get('studio-live-title').textContent,/正在完成/);
});
test('read-in-progress and previous-batch plan events cannot relabel a frozen workflow',()=>{
 const h=harness();h.select();h.run('RUNNING','MOCKUP');
 h.fire('heyi:creative-plan-state',{batchId:'other',version:1,renderPolicy:'LOCAL_PHOTO_V1'});
 h.fire('heyi:creative-plan-state',{batchId:'one',version:0,renderPolicy:'LOCAL_PHOTO_V1'});
 assert.equal(h.get('studio-live-track').children[2].textContent,'03 商品套版');
 h.fire('heyi:creative-plan-state',{batchId:'one',version:1,renderPolicy:'LOCAL_PHOTO_V1'});
 assert.equal(h.get('studio-live-track').children[2].textContent,'03 本地套版');
 h.fire('heyi:workbench-batch-selected',{batchId:'two'});h.run('RUNNING','MOCKUP','two');
 assert.equal(h.get('studio-live-track').children[2].textContent,'03 商品套版');
 h.fire('heyi:artifacts-updated',{batchId:'two',renderPolicy:null,ready:3,previews:[]});
 assert.equal(h.get('studio-live-count').textContent,'已归档 3 张');
});
test('scene follows actual stages, completion does not replay, and decoration has no pause control',()=>{
 const h=harness();h.select();
 for(const [stage,scene] of [['EXTRACT','extract'],['VARIATE','design'],['MOCKUP','mockup'],['REVIEW','result']]){
  h.run(stage==='REVIEW'?'WAITING_INPUT':'RUNNING',stage);
  assert.equal(h.get('studio-live-stage').dataset.scene,scene);
  assert.equal(h.get('studio-live-stage').dataset.replay,'false');
 }
 assert.equal(h.get('studio-motion-toggle').hidden,true);
 assert.match(h.get('studio-motion-caption').textContent,/合成完成/);
});

test('queued work retains its actual stage instead of restarting the creation headline',()=>{
 const h=harness();h.fire('heyi:workbench-batch-selected',{batchId:'one',creativeOnly:false});
 for(const [stage,label]of [['EXTRACT','印花提取'],['VARIATE','印花裂变'],['MOCKUP','商品套版'],['REVIEW','自动检查'],['PUBLISH','草稿上传']]){
  h.run('QUEUED',stage);assert.ok(h.get('studio-live-title').textContent.includes(label));
  assert.doesNotMatch(h.get('studio-live-title').textContent,/创作即将开始/);
 }
 assert.equal(h.get('studio-live-track').children[3].textContent,'04 上传草稿');
 assert.match(h.get('studio-live-stage-label').textContent,/当前环节 · 上传草稿/);
});

test('draft upload displays persisted partial progress and only completed execution receives a completion headline',()=>{
 const h=harness();h.fire('heyi:workbench-batch-selected',{batchId:'one',creativeOnly:false});
 const partial='草稿上传：1 / 2 款已确认，1 款提交中';
 h.fire('heyi:execution-state',{run:{batchId:'one',stage:'PUBLISH',status:'QUEUED',message:partial}});
 assert.equal(h.get('studio-live-description').textContent,partial);assert.equal(h.get('studio-live-title').textContent,'正在同步草稿上传进度');
 assert.match(h.get('studio-motion-caption').textContent,/商品成品 → 店铺草稿/);
 h.fire('heyi:execution-state',{run:{batchId:'one',stage:'PUBLISH',status:'RUNNING',message:partial}});
 assert.equal(h.get('studio-live-title').textContent,'正在上传店铺草稿');assert.equal(h.get('studio-live-description').textContent,partial);
 h.fire('heyi:execution-state',{run:{batchId:'one',stage:'PUBLISH',status:'COMPLETED',message:'草稿上传完成：2 款已取得平台商品编号'}});
 assert.equal(h.get('studio-live-title').textContent,'本批草稿已上传');assert.match(h.get('studio-live-description').textContent,/2 款已取得平台商品编号/);
 assert.doesNotMatch(h.get('studio-live-title').textContent,/上架完成|创作即将开始/);
});

test('print loading effects follow live status and count only completed variants for the selected source',()=>{
 const h=harness();h.select();h.fire('heyi:design-source-selected',{captureId:'shirt'});h.run();
 assert.equal(h.get('studio-design-fx').hidden,false);assert.equal(h.get('studio-live-stage').dataset.designWorking,'true');
 h.fire('heyi:artifacts-updated',{batchId:'one',previews:[{captureId:'shirt',kind:'VARIATE',status:'READY'},{captureId:'shirt',kind:'VARIATE',status:'SUBMITTING'},{captureId:'other',kind:'VARIATE',status:'READY'}]});
 assert.match(h.get('studio-design-status').children[1].textContent,/已生成 1 款/);
 h.run('UNKNOWN');assert.equal(h.get('studio-live-stage').dataset.designWorking,'false');
 h.run('RUNNING','MOCKUP');assert.equal(h.get('studio-design-fx').hidden,true);assert.equal(h.get('studio-design-status').hidden,true);
});

test('shop shelf only displays uploaded thumbnails after platform completion',()=>{
 const h=harness();h.select();h.run('RUNNING','PUBLISH');
 h.fire('heyi:artifacts-updated',{batchId:'one',previews:[{kind:'MOCKUP',status:'READY',imageUrl:'/ready.png'}]});
 const store=h.get('studio-store-slots').children[0].children[0];assert.equal(store.hidden,true);assert.equal(h.get('studio-live-stage').dataset.publishWorking,'true');
 h.run('UNKNOWN','PUBLISH');assert.equal(store.hidden,true);assert.equal(h.get('studio-live-stage').dataset.publishComplete,'false');
 h.run('COMPLETED','PUBLISH');assert.equal(store.hidden,false);assert.equal(store.src,'/ready.png');assert.equal(h.get('studio-store-receipt').textContent,'✓ 草稿已保存');
 h.run('RUNNING','VARIATE');assert.equal(h.get('studio-publish-fx').hidden,true);
});

test('extraction-to-variation handoff reuses the extracted print once and clears on failure or scope change',()=>{
 const h=harness();h.select();h.fire('heyi:design-source-selected',{captureId:'shirt'});h.run('RUNNING','EXTRACT');
 h.fire('heyi:artifacts-updated',{batchId:'one',previews:[{captureId:'shirt',kind:'EXTRACT',status:'READY',imageUrl:'/original.png'}]});
 h.run('RUNNING','VARIATE');const handoff=h.get('studio-print-handoff');assert.equal(handoff.hidden,false);assert.equal(handoff.children[0].src,'/original.png');
 handoff.fire('animationend');assert.equal(handoff.hidden,true);h.run('RUNNING','VARIATE');assert.equal(handoff.hidden,true);
 h.run('RUNNING','EXTRACT');h.run('RUNNING','VARIATE');assert.equal(handoff.hidden,false);h.run('FAILED','VARIATE');assert.equal(handoff.hidden,true);
 h.run('RUNNING','EXTRACT');h.run('RUNNING','VARIATE');h.fire('heyi:company-reset');assert.equal(handoff.hidden,true);
});

test('Grok background preparation is a shared base phase instead of print formation or color extraction',()=>{
 const h=harness();h.select();h.run('RUNNING','MOCKUP');h.fire('heyi:design-source-selected',{captureId:'second-source'});
 h.fire('heyi:artifacts-updated',{batchId:'one',renderPolicy:'GROK_SCENE_V1',ready:0,colorSources:[{captureId:'second-source',colors:skuColors}],previews:[{id:'background',captureId:'second-source',kind:'SCENE_BACKGROUND',status:'SUBMITTING',variant:0}]});
 assert.equal(h.get('studio-live-stage').dataset.phase,'scene-background');assert.match(h.get('studio-live-title').textContent,/Grok 正在设计本商品背景/);assert.match(h.get('studio-live-description').textContent,/提取商品背景风格 · 同商品多款多色共用/);assert.equal(h.get('studio-color-deck').hidden,true);assert.equal(h.get('studio-design-fx').hidden,true);assert.equal(h.get('studio-live-count').textContent,'已归档 0 张商品主图');
 assert.match(h.get('studio-live-description').textContent,/场景氛围、配色、材质与光线重新设计背景/);assert.doesNotMatch(h.get('studio-live-description').textContent,/保留商品照片的环境/);
});

test('an archived scene background previews as a reusable base, never as a finished product or print',()=>{
 const h=harness();h.select();h.run('RUNNING','MOCKUP');h.fire('heyi:design-source-selected',{captureId:'source'});
 h.fire('heyi:artifacts-updated',{batchId:'one',renderPolicy:'GROK_SCENE_V1',ready:0,previews:[{id:'background',captureId:'source',kind:'SCENE_BACKGROUND',status:'READY',variant:0,imageUrl:'/shared-background'}]});
 const image=h.get('studio-live-image').children.find(node=>node.src==='/shared-background');assert.ok(image);image.fire('load');assert.equal(h.get('studio-live-stage').dataset.view,'placement');assert.equal(h.get('studio-live-origin').textContent,'本商品共用背景 · 非商品成品');assert.equal(h.get('studio-live-stage').dataset.phase,undefined);
 h.run('RUNNING','VARIATE');assert.equal(h.get('studio-design-fx').hidden,false);assert.ok(!h.get('studio-live-image').children.some(node=>node.src==='/shared-background'),'background cannot become a print in variation stage');
});

test('unknown scene background remains awaiting verification instead of claiming continued generation',()=>{
 const h=harness();h.select();h.run('UNKNOWN','MOCKUP');h.fire('heyi:design-source-selected',{captureId:'source'});
 h.fire('heyi:artifacts-updated',{batchId:'one',renderPolicy:'GROK_SCENE_V1',ready:0,previews:[{id:'background',captureId:'source',kind:'SCENE_BACKGROUND',status:'UNKNOWN',variant:0}]});
 assert.equal(h.get('studio-live-title').textContent,'场景背景结果待核查');assert.match(h.get('studio-live-description').textContent,/不重复生成/);assert.equal(h.get('studio-live-stage').dataset.status,'UNKNOWN');
});

test('scene phase and preview never fall back to another source product background',()=>{
 const h=harness();h.select();h.run('RUNNING','MOCKUP');h.fire('heyi:design-source-selected',{captureId:'product-b'});
 const a={id:'background-a',captureId:'product-a',kind:'SCENE_BACKGROUND',status:'SUBMITTING',variant:0};h.fire('heyi:artifacts-updated',{batchId:'one',renderPolicy:'GROK_SCENE_V1',ready:0,previews:[a]});
 assert.equal(h.get('studio-live-stage').dataset.phase,undefined);assert.doesNotMatch(h.get('studio-live-title').textContent,/正在设计本商品背景/);
 h.fire('heyi:artifacts-updated',{batchId:'one',renderPolicy:'GROK_SCENE_V1',ready:0,previews:[{...a,status:'READY',imageUrl:'/background-a'}]});assert.ok(!h.get('studio-live-image').children.some(node=>node.src==='/background-a'));
 h.fire('heyi:design-source-selected',{captureId:'product-a'});assert.ok(h.get('studio-live-image').children.some(node=>node.src==='/background-a'));
});

test('drape motion follows precise placement, one shared master, local variants and final outputs in order',()=>{
 const h=harness();h.select();h.fire('heyi:design-source-selected',{captureId:'shirt'});h.run('RUNNING','MOCKUP');
 const emit=previews=>h.fire('heyi:artifacts-updated',{batchId:'one',renderPolicy:'GROK_DRAPE_REUSE_V1',ready:0,previews});
 const precise={id:'precise',captureId:'shirt',kind:'MOCKUP_FRONT',status:'READY',imageUrl:'/precise'};
 emit([]);assert.equal(h.get('studio-live-stage').dataset.drapePhase,'precise');assert.match(h.get('studio-live-title').textContent,/精确套版/);
 emit([precise]);assert.equal(h.get('studio-live-stage').dataset.drapePhase,'master');assert.match(h.get('studio-live-title').textContent,/等待准备/);
 assert.ok(h.get('studio-live-image').children.some(n=>n.src==='/precise'));
 const master={id:'master',captureId:'shirt',kind:'DRAPE_MASTER',status:'SUBMITTING'};
 emit([precise,master]);assert.match(h.get('studio-live-title').textContent,/正在生成自然搭放母图/);
 emit([precise,{...master,status:'READY',imageUrl:'/drape-master'}]);
 assert.equal(h.get('studio-live-stage').dataset.drapePhase,'register');assert.match(h.get('studio-live-title').textContent,/定位母图/);
 let image=h.get('studio-live-image').children[1];assert.equal(image.src,'/drape-master');image.fire('load');assert.match(h.get('studio-live-origin').textContent,/母图/);
 emit([precise,{...master,status:'READY',imageUrl:'/drape-master'},{captureId:'shirt',kind:'DRAPE_BLANK',status:'READY',imageUrl:'/drape-blank'}]);
 assert.equal(h.get('studio-live-stage').dataset.drapePhase,'reuse');assert.match(h.get('studio-live-title').textContent,/复用母图/);
 const result={id:'draped',captureId:'shirt',kind:'DRAPED_FRONT',status:'READY',imageUrl:'/draped',colorLabel:'白色'};
 emit([precise,{...master,status:'READY',imageUrl:'/drape-master'},result]);image=h.get('studio-live-image').children[1];assert.equal(image.src,'/draped');image.fire('load');
 assert.equal(h.get('studio-live-origin').textContent,'自然搭放主图 · 白色');
 h.run('RUNNING','REVIEW');assert.equal(h.get('studio-live-stage').dataset.drapePhase,'result');assert.equal(h.get('studio-motion-caption').textContent,'精确套版 → 一次搭放母图 → 印花定位 → 本地换色换花');
 h.run('COMPLETED','PUBLISH');const store=h.get('studio-store-slots').children[0].children[0];assert.equal(store.src,'/draped');
});
test('drape master cannot be borrowed from a different source and UNKNOWN never claims another image call',()=>{
 const h=harness();h.select();h.fire('heyi:design-source-selected',{captureId:'shirt'});h.run('RUNNING','MOCKUP');
 const emit=previews=>h.fire('heyi:artifacts-updated',{batchId:'one',renderPolicy:'GROK_DRAPE_REUSE_V1',ready:0,previews});
 emit([{captureId:'other',kind:'DRAPE_MASTER',status:'READY',imageUrl:'/other-master'}]);
 assert.equal(h.get('studio-live-stage').dataset.drapePhase,'precise');assert.ok(!h.get('studio-live-image').children.some(n=>n.src==='/other-master'));
 emit([{captureId:'shirt',kind:'DRAPE_MASTER',status:'UNKNOWN'}]);assert.match(h.get('studio-live-title').textContent,/待核查/);assert.match(h.get('studio-live-description').textContent,/不重复生成/);
 h.run('FAILED','MOCKUP');assert.doesNotMatch(h.get('studio-live-title').textContent,/正在生成/);
});

test('drape displays base and matte before master and keeps precise output visible as process material',()=>{
 const h=harness();h.select();h.fire('heyi:design-source-selected',{captureId:'shirt'});h.run('RUNNING','MOCKUP');
 const base={captureId:'shirt',kind:'BASE_COLOR',status:'READY',colorKey:'white',colorLabel:'白色',variant:0,imageUrl:'/base'},matte={captureId:'shirt',kind:'MATTE_G1',status:'READY',variant:1,imageUrl:'/matte'};
 const emit=previews=>h.fire('heyi:artifacts-updated',{batchId:'one',renderPolicy:'GROK_DRAPE_REUSE_V1',ready:0,previews});
 emit([base,matte]);let preview=h.get('studio-live-image').children[1];assert.equal(preview.src,'/base');preview.fire('load');assert.equal(h.get('studio-live-stage').dataset.view,'placement');
 assert.equal(h.get('studio-echo echo-0').hidden,false);assert.equal(h.get('studio-echo echo-0').children[0].src,'/base');assert.equal(h.get('studio-echo echo-1').children[0].src,'/matte');
 const precise={captureId:'shirt',kind:'MOCKUP_FRONT',status:'READY',variant:1,colorKey:'white',colorLabel:'白色',imageUrl:'/precise'};
 emit([base,matte,precise]);preview=h.get('studio-live-image').children[1];assert.equal(preview.src,'/precise');preview.fire('load');assert.equal(h.get('studio-live-origin').textContent,'精确套版 · 搭放前参考');assert.equal(h.get('studio-live-stage').dataset.view,'placement');
 emit([base,matte,precise,{captureId:'shirt',kind:'DRAPE_MASTER',status:'SUBMITTING'}]);assert.equal(h.get('studio-live-image').children[1],preview);assert.equal(h.get('studio-live-stage').dataset.drapeWorking,'true');assert.match(h.get('studio-drape-title').textContent,/正在生成自然搭放母图/);
 assert.equal(h.get('studio-live-count').textContent,'已归档 0 张商品主图');assert.match(h.get('studio-drape-progress').textContent,/精确套版 1 张.*商品成品 0 张/);
});

test('failed drape registration retains archived master and exact placement while exposing the actual stop reason',()=>{
 const h=harness();h.select();h.fire('heyi:design-source-selected',{captureId:'shirt'});h.run('RUNNING','MOCKUP');
 const precise=Array.from({length:20},(_,i)=>({id:'p'+i,captureId:'shirt',kind:'MOCKUP_FRONT',status:'READY',variant:i+1,colorKey:'white',imageUrl:'/precise-'+i}));
 const master={captureId:'shirt',kind:'DRAPE_MASTER',status:'READY',imageUrl:'/master'},guide={captureId:'shirt',kind:'DRAPE_GUIDE',status:'READY',imageUrl:'/guide'},blank={captureId:'shirt',kind:'DRAPE_BLANK',status:'FAILED'};
 const emit=()=>h.fire('heyi:artifacts-updated',{batchId:'one',renderPolicy:'GROK_DRAPE_REUSE_V1',ready:0,previews:[...precise,master,guide,blank]});
 emit();const preview=h.get('studio-live-image').children[1];preview.fire('load');
 h.fire('heyi:execution-state',{run:{batchId:'one',stage:'MOCKUP',status:'WAITING_INPUT',message:'FEATURE_TRACKING_FAILED：搭放母图中的印花位置无法可靠对齐'}});
 assert.equal(h.get('studio-live-stage').dataset.drapePhase,'register');assert.equal(h.get('studio-live-stage').dataset.drapeWorking,'false');assert.equal(h.get('studio-drape-title').textContent,'搭放印花定位已停止');assert.match(h.get('studio-drape-description').textContent,/FEATURE_TRACKING_FAILED/);
 assert.equal(h.get('studio-drape-status').hidden,false);assert.equal(h.get('studio-live-image').children[1],preview);assert.equal(preview.src,'/master');assert.equal(h.get('studio-echo echo-0').children[0].src,'/precise-0');assert.equal(h.get('studio-echo echo-0').dataset.caption,'精确套版参考');
 assert.ok(!h.get('studio-live-image').children.some(n=>n.src==='/guide'));assert.match(h.get('studio-drape-progress').textContent,/精确套版 20 张 · 搭放母图 1 张 · 商品成品 0 张/);
 assert.deepEqual(h.get('studio-drape-steps').children.map(n=>n.dataset.state),['done','done','stopped','waiting']);
 emit();assert.equal(h.get('studio-live-image').children[1],preview,'unchanged polling must not recreate the archived master');
 h.fire('heyi:execution-connection',{batchId:'one',available:false});assert.equal(h.get('studio-live-stage').dataset.drapeWorking,'false');assert.equal(preview.src,'/master');
});

test('local reuse starts after archived blank and uses matched variant layers without borrowing another source',()=>{
 const h=harness();h.select();h.fire('heyi:design-source-selected',{captureId:'shirt'});h.run('RUNNING','MOCKUP');
 h.fire('heyi:artifacts-updated',{batchId:'one',renderPolicy:'GROK_DRAPE_REUSE_V1',ready:1,previews:[
  {captureId:'shirt',kind:'MOCKUP_FRONT',status:'READY',variant:1,colorKey:'white',imageUrl:'/precise'},
  {captureId:'shirt',kind:'DRAPE_MASTER',status:'READY',imageUrl:'/master'},
  {captureId:'shirt',kind:'DRAPE_BLANK',status:'READY',imageUrl:'/blank'},
  {captureId:'shirt',kind:'MATTE_G1',status:'READY',variant:1,imageUrl:'/matte-one'},
  {captureId:'shirt',kind:'MATTE_G1',status:'READY',variant:2,imageUrl:'/matte-two'},
  {captureId:'shirt',kind:'DRAPED_FRONT',status:'READY',variant:2,colorKey:'pink',imageUrl:'/draped-two'},
  {captureId:'other',kind:'DRAPE_BLANK',status:'READY',imageUrl:'/foreign-blank'}
 ]});
 assert.equal(h.get('studio-live-stage').dataset.drapePhase,'reuse');assert.equal(h.get('studio-echo echo-0').children[0].src,'/blank');assert.equal(h.get('studio-echo echo-1').children[0].src,'/matte-two');assert.equal(h.get('studio-live-image').children[1].src,'/draped-two');
 h.fire('heyi:design-source-selected',{captureId:'missing'});assert.equal(h.get('studio-echo echo-0').hidden,true);assert.equal(h.get('studio-echo echo-1').hidden,true);assert.equal(h.get('studio-live-image').children.length,1);
 h.fire('heyi:workbench-scope-reset');assert.equal(h.get('studio-drape-status').hidden,true);assert.equal(h.get('studio-drape-steps').hidden,true);assert.equal(h.get('studio-drape-flow').hidden,true);
});
