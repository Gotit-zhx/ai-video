const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm'),path=require('node:path');
const flush=()=>new Promise(r=>setImmediate(r));
for(const file of ['commerce-artwork-library.js','commerce-material-slots.js'])test(file+' loads on entry, deduplicates pending work, refreshes on reentry, discards reset results',async()=>{
 const events={},nodes=new Map(),calls=[],pending=[];
 const node=()=>({children:[],value:'',disabled:false,hidden:false,dataset:{},append(...xs){this.children.push(...xs);},replaceChildren(...xs){this.children=xs;},setAttribute(){},addEventListener(){},querySelectorAll(){return [];}});
 const get=id=>{if(!nodes.has(id))nodes.set(id,node());return nodes.get(id);};
 const env={document:{getElementById:get,createElement:node},location:{hash:'#commerce-assets'},encodeURIComponent,api:url=>{calls.push(url);return new Promise(r=>pending.push(r));},addEventListener:(name,fn)=>events[name]=fn};env.window=env;
 vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../'+file),'utf8'),env);await flush();assert.equal(calls.length,0,'no request before authenticated navigation');
 const enter=()=>events['heyi:commerce-section-entered']({detail:{route:'commerce-assets'}});
 enter();enter();assert.equal(calls.length,1);
 const data={page:0,hasMore:false,items:[],slots:[]};pending.shift()({data});await flush();enter();assert.equal(calls.length,2);
 events['heyi:company-reset']();const stale=pending.shift();enter();assert.equal(calls.length,3);stale({data});await flush();enter();assert.equal(calls.length,3,'stale completion must not unlock new company request');
 pending.shift()({data});await flush();enter();assert.equal(calls.length,4);
});
