const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const root=path.join(__dirname,'..'),catalog=JSON.parse(fs.readFileSync(path.join(root,'i18n/catalog.json'),'utf8')),html=fs.readFileSync(path.join(root,'index.html'),'utf8');
const source=fs.readFileSync(path.join(root,'i18n.js'),'utf8');
function runtime({lang='ms',stored=null,browser=['en-US'],data={},slots={}}={}){
 const html={dataset:{}};let store=stored,mutation;const native=[],scripts=[];
 const context={fetch:()=>Promise.reject(new Error("offline")),URL,Map,Set,WeakMap,Node:{TEXT_NODE:3,ELEMENT_NODE:1},navigator:{languages:browser},location:{href:'https://hoyee.org.cn/?lang='+lang+'#shops'},history:{replaceState(){}},localStorage:{getItem:()=>store,setItem:(_,v)=>store=v},CustomEvent:class{},MutationObserver:class{constructor(callback){mutation=callback;}observe(){}disconnect(){}},queueMicrotask:callback=>callback(),document:{body:{nodeType:1,matches:()=>false,getAttribute:()=>null,childNodes:[]},documentElement:html,querySelector:()=>null,querySelectorAll:()=>[],addEventListener(){},dispatchEvent(){}},window:{addEventListener(){},HoyeeCatalog:data,HoyeeCatalogSlots:slots,alert:m=>native.push(m),confirm:m=>(native.push(m),true),prompt:(m,d)=>(native.push(m),d)}};
 context.document.head={append:script=>scripts.push(script)};
 context.document.createElement=tag=>({tagName:tag,remove(){}});
 vm.runInNewContext(source,context);return {api:context.window.HoyeeLanguage,context,native,scripts,mutate:records=>mutation(records)};
}
test('each catalog entry covers every market without losing dynamic placeholders',()=>{for(const [key,variants]of Object.entries(catalog)){assert.equal(variants.length,6,key);for(const variant of variants){assert.equal(typeof variant,'string',key);assert.ok(variant.trim(),key);assert.deepEqual((variant.match(/\{\d+\}/g)||[]).sort(),(key.match(/\{\d+\}/g)||[]).sort(),key);}}});
test('safe language links preserve supported language and strip credentials',()=>{const bootstrap=html.match(/<script>([\s\S]*?)<\/script>/)[1];for(const [query,expected]of [['?lang=ms&password=secret','/app?lang=ms#login'],['?token=secret&lang=fil','/app?lang=fil#login'],['?lang=th','/app?lang=th#login'],['?lang=unknown&password=secret','/app#login']]){let url;vm.runInNewContext(bootstrap,{URLSearchParams,Date,Set,localStorage:{getItem:()=>null},window:{location:{search:query,pathname:'/app',hash:'#login'},history:{replaceState:(_,__,u)=>url=u}},document:{title:'',documentElement:{dataset:{},style:{}}}});assert.equal(url,expected);}});
test('locale preference, aliases, and English fallback',()=>{assert.equal(runtime({lang:'',browser:['ms-MY']}).api.current,'ms');assert.equal(runtime({lang:'',stored:'fil',browser:['zh-CN']}).api.current,'fil');assert.equal(runtime({lang:'',browser:['fr-FR']}).api.current,'en');assert.equal(runtime({lang:'tl-PH'}).api.current,'fil');assert.equal(runtime({lang:'zh',stored:'ms'}).api.current,'zh');});
test('dynamic placeholders retain literal dollar signs, and Chinese switching restores source',()=>{const data={'共 {0} 件商品':['{0} products','x {0}','x {0}','x {0}','{0} produk','x {0}'],'保存':['Save','Simpan','บันทึก','Lưu','Simpan','I-save']};const {api,context,native}=runtime({data});assert.equal(api.t('共 $& 件商品'),'$& produk');context.window.confirm('保存');assert.equal(native[0],'Simpan');api.set('zh');assert.equal(api.t('保存'),'保存');});
test('UI changes never mutate implicit option values, user input, or protected content',()=>{
 const {api,context}=runtime({data:{'保存':['Save','x','x','x','Simpan','x']}});
 function element(tag,attributes={}){return {nodeType:1,tagName:tag,childNodes:[],attributes,matches:()=>attributes.translate==='no',closest(){return attributes.translate==='no'||['INPUT','TEXTAREA'].includes(tag);},getAttribute:n=>attributes[n]??null,hasAttribute:n=>n in attributes,setAttribute:(n,v)=>attributes[n]=v,get textContent(){return this.childNodes.map(n=>n.nodeValue).join('');}};}
 function child(p,s){const n={nodeType:3,nodeValue:s,parentElement:p};p.childNodes.push(n);return n;}
 const option=element('OPTION'),n=child(option,'保存'),protectedNode=element('SPAN',{translate:'no'}),pn=child(protectedNode,'保存'),input=element('INPUT');input.value='保存';
 context.document.body.childNodes=[option,protectedNode,input];api.render();assert.equal(n.nodeValue,'Simpan');assert.equal(option.attributes.value,'保存');assert.equal(pn.nodeValue,'保存');assert.equal(input.value,'保存');api.set('zh');assert.equal(n.nodeValue,'保存');
 n.nodeValue='保存';api.set('ms');assert.equal(n.nodeValue,'Simpan');
});

test('nested interface labels translate but arbitrary task names in slots stay unchanged',()=>{const data={'商品运营 / {0}':['Products / {0}','x {0}','x {0}','x {0}','Produk / {0}','x {0}'],'采集链接':['Import products','x','x','x','Import produk','x'],'任务：{0}':['Task: {0}','x {0}','x {0}','x {0}','Tugas: {0}','x {0}'],'草稿':['Draft','x','x','x','Draf','x']};const {api}=runtime({data});assert.equal(api.t('商品运营 / 采集链接'),'Produk / Import produk');assert.equal(api.t('任务：草稿'),'Tugas: 草稿');});

test('background tab mutations translate immediately without waiting for animation frames',()=>{const {context,mutate}=runtime({data:{'保存':['Save','x','x','x','Simpan','x']}});const parent={nodeType:1,tagName:'SPAN',isConnected:true,childNodes:[],matches:()=>false,closest:()=>false,getAttribute:()=>null};const node={nodeType:3,nodeValue:'保存',parentElement:parent};parent.childNodes.push(node);context.document.body.childNodes=[parent];mutate([{target:node}]);assert.equal(node.nodeValue,'Simpan');});

test('typed conditional UI slots translate while untyped content slots remain literal',()=>{const data={'{0} 字符{1}':['{0} chars{1}','x {0}{1}','x {0}{1}','x {0}{1}','{0} aksara{1}','x {0}{1}'],'· 显示最近片段':['· Latest excerpt','x','x','x','· Petikan terkini','x']};const {api}=runtime({data,slots:{'{0} 字符{1}':[1]}});assert.equal(api.t('12 字符 · 显示最近片段'),'12 aksara · Petikan terkini');});

test('Chinese entry avoids catalog downloads; rapid language changes share a request and retain the latest choice',()=>{
 const {api,context,scripts}=runtime({lang:'zh'});
 assert.equal(scripts.length,0);
 api.set('en');api.set('ms');assert.equal(scripts.length,1);
 context.window.HoyeeCatalog={'保存':['Save','x','x','x','Simpan','x']};
 scripts[0].onload();assert.equal(api.current,'ms');assert.equal(api.t('保存'),'Simpan');
 api.set('zh');api.set('en');assert.equal(scripts.length,1);assert.equal(api.t('保存'),'Save');
});
test('a failed language download can retry and does not overwrite a switch back to Chinese',()=>{
 const {api,context,scripts}=runtime({lang:'en'});
 scripts[0].onerror();assert.equal(context.document.documentElement.dataset.languageLoadError,'true');
 api.set('ms');assert.equal(scripts.length,2);api.set('zh');
 context.window.HoyeeCatalog={'保存':['Save','x','x','x','Simpan','x']};
 scripts[1].onload();assert.equal(api.current,'zh');assert.equal(api.t('保存'),'保存');
 assert.equal(context.document.documentElement.dataset.languageLoadError,undefined);
});
