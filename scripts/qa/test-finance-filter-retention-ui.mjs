import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const scripts=await Promise.all(['incremental-dom.js','incremental-charts.js','finance-workspace.js'].map(f=>fs.readFile(new URL('../../frontend/'+f,import.meta.url),'utf8')));
const key=(org='org',user='u')=>'heyi:finance-filters:v1:'+JSON.stringify([org,user]);
async function boot(saved={},org='org',user='u',blocked=false){
 const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/',runScripts:'outside-only',pretendToBeVisual:true}),w=dom.window,reads=[];
 w.currentUser={organizationId:org,userId:user,permissions:['finance:view']};w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
 w.echarts={init:()=>({setOption(){},resize(){},dispose(){}})};
 for(const [k,v]of Object.entries(saved))w.sessionStorage.setItem(k,v);
 if(blocked)Object.defineProperty(w,'sessionStorage',{get(){throw Error('blocked');}});
 w.api=async path=>{const u=new URL(path,'https://example.test');reads.push(u);return {data:{filters:Object.fromEntries(u.searchParams),currencies:['PHP'],shops:[],groups:[],sync:[],totals:[],trend:[],fees:[],statuses:[],ranking:[],items:[],page:1,pageSize:50,total:0}};};
 scripts.forEach(s=>w.eval(s));
 const settle=()=>new Promise(r=>setTimeout(r,20));w.FinanceWorkspace.enter('overview');await settle();return {dom,w,reads,settle};
}
const saved={pinnedDates:true,start:'2026-08-01',end:'2026-09-30',shopId:'shop',groupId:'group',platform:'SHOPEE',displayCurrency:'CNY',currency:'',fxSnapshot:'old'};
const a=await boot({[key()]:JSON.stringify(saved)});assert.equal(a.reads[0].searchParams.get('start'),saved.start);assert.equal(a.reads[0].searchParams.get('shopId'),'shop');assert.equal(a.reads[0].searchParams.get('fxSnapshot'),'');
a.w.FinanceWorkspace.enter('live');await a.settle();a.w.FinanceWorkspace.enter('live');await a.settle();a.w.FinanceWorkspace.enter('overview');await a.settle();assert.equal(a.reads.at(-1).searchParams.get('start'),saved.start);assert.equal(a.reads.at(-1).searchParams.get('end'),saved.end);
assert.equal(JSON.parse(a.w.sessionStorage.getItem(key())).start,saved.start);
a.w.FinanceWorkspace.reset();assert.equal(a.w.sessionStorage.getItem(key()),null);a.dom.window.close();
for(const [storage,org,user,blocked]of [
 [{[key()]:JSON.stringify(saved)},'other','u',false], [{[key()]:JSON.stringify(saved)},'org','other',false],
 [{[key()]:'invalid'},'org','u',false],[{[key()]:JSON.stringify({...saved,pinnedDates:false})},'org','u',false],
 [{[key()]:JSON.stringify({...saved,start:'2026-02-30'})},'org','u',false],[{},'org','u',true]
]){const t=await boot(storage,org,user,blocked);assert.notEqual(t.reads[0].searchParams.get('start'),'2026-08-01');t.dom.window.close();}
console.log('Finance filters: reload, live round-trip, account isolation, reset, invalid storage and rolling defaults passed.');
