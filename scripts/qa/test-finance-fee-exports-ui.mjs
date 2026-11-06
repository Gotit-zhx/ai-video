import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const source=await fs.readFile(new URL('../../frontend/finance-fee-exports.js',import.meta.url),'utf8');
const dom=new JSDOM('<main id="host"></main>',{url:'https://example.test',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window;w.eval(source);
let identity='org/user',posts=[],serverJobs=[],fail=false,pending=false;
const selection={start:'2026-10-01',end:'2026-10-31',shopId:'',groupId:'g',platform:'SHOPEE',currency:'',displayCurrency:'CNY',fxSnapshot:'fixed'};
const ctx={identity:()=>identity,isPending:()=>pending,selection,shops:[],groups:[{id:'g',name:'母婴'}],canCreate:true,
  request:async(path,options)=>{
    if(options?.method==='POST'){
      posts.push(options);if(fail)throw Error('connection closed');
      const job={id:'one',status:'QUEUED',...JSON.parse(options.body),filename:'report.xlsx'};serverJobs=[job];return job;
    }return serverJobs;
  }};
const settle=()=>new Promise(resolve=>setTimeout(resolve,10));
const button=label=>[...w.document.querySelectorAll('button')].find(n=>n.textContent===label);
const mount=()=>{w.document.querySelector('#host').replaceChildren();w.FinanceFeeExports.mount(w.document.querySelector('#host'),ctx);};
mount();w.FinanceFeeExports.open();await settle();
assert.match(w.document.body.textContent,/母婴全部店铺/);
pending=true;button('生成 Excel').click();await settle();assert.equal(posts.length,0);
pending=false;button('生成 Excel').click();await settle();
assert.equal(posts.length,1);assert.deepEqual(JSON.parse(posts[0].body),{start:selection.start,end:selection.end,shopId:'',groupId:'g',platform:'SHOPEE',currency:'',displayCurrency:'ORIGINAL'});
assert.match(w.document.body.textContent,/原币分币种/);assert.doesNotMatch(w.document.body.textContent,/人民币/);
assert.match(w.document.body.textContent,/排队中/);
serverJobs[0].status='READY';button('刷新任务状态').click();await settle();assert.ok(button('下载 Excel'));
ctx.selection={...selection,groupId:'',shopId:'shop'};
fail=true;mount();button('生成 Excel').click();await settle();
const failed=posts.at(-1);assert.ok(button('确认上次任务'));
ctx.selection={...selection,groupId:'another'};mount();
fail=false;button('确认上次任务').click();await settle();
assert.equal(posts.at(-1).headers['Idempotency-Key'],failed.headers['Idempotency-Key']);
assert.equal(posts.at(-1).body,failed.body);
assert.equal(posts.length,3,'Only explicit actions submit; status polling never creates a job');
identity='other/user';mount();assert.doesNotMatch(w.document.body.textContent,/排队中|可下载/);
w.FinanceFeeExports.reset();dom.window.close();
console.log('Fee export dropdown: filter snapshot, pending-selection guard, queued/ready states, explicit same-key recovery and owner isolation passed.');
