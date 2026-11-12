import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
import {createAdvertisingFixture} from './fixtures/finance-advertising.mjs';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-fees',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,fixture=createAdvertisingFixture(),reads=[];
w.currentUser={organizationId:'qa',userId:'viewer',permissions:['finance:view']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
w.echarts={init:()=>({setOption(){},resize(){},dispose(){}})};
let release,hold=false,failPrimary=false,primaryRevision='v1',statusRevision='v1',statusState='READY';
const polls=[],timeout=w.setTimeout.bind(w);w.setTimeout=(fn,ms,...args)=>ms===15000?(polls.push(fn),100000+polls.length):timeout(fn,ms,...args);
w.api=async path=>{
  const u=new URL(path,'https://example.test'),q=Object.fromEntries(u.searchParams);reads.push(u);
  if(u.pathname.endsWith('/shops'))return {data:{items:fixture.shops,groups:fixture.groups||[],sync:[]}};
  if(u.pathname.endsWith('/ads'))return {data:fixture.accounts};
  if(u.pathname.endsWith('/workspace/status'))return {data:{revision:statusRevision,status:statusState}};
  if(!u.pathname.endsWith('/workspace/query'))throw Error('Page must not wait for a separate overview or record request: '+u.pathname);
  if(hold){hold=false;await new Promise(r=>release=r);}
  const context=fixture.overview(q),records={...fixture.records(q),page:Number(q.page||1),pageSize:50,total:100};
  if(q.view==='ads'&&failPrimary)throw Error('Advertising sources exceed limit');
  if(q.view==='ads')return {data:{...context,readModel:{revision:primaryRevision,status:'READY'},adAccounts:fixture.accounts,adSpend:{items:[],shops:[],groups:[],totals:[],accountFunding:[],total:0,page:1,pageSize:50,filters:{currency:'',displayCurrency:'CNY'}}}};
  return {data:{...context,records,readModel:{revision:q.view==='ads-support'?'v2':'v1',generatedAt:'2026-11-12T00:00:00Z',status:'READY'}}};
};
for(const file of ['incremental-dom.js','incremental-charts.js','finance-ad-spend.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../../frontend/'+file,import.meta.url),'utf8'));
async function ready(){for(let i=0;i<200;i++){await new Promise(r=>setTimeout(r,5));if(d.querySelector('main').getAttribute('aria-busy')==='false')return;}throw Error('Page never completed');}
const btn=text=>[...d.querySelectorAll('button')].find(n=>n.textContent.trim()===text);
try{
  w.FinanceWorkspace.enter('fees');await ready();
  assert.equal(reads.length,1);assert.equal(reads[0].pathname,'/api/v1/finance/workspace/query');
  assert(d.querySelector('tbody tr'),'fee rows appear from the same response as the summary');
  const beforePoll=reads.length;await polls.shift()();
  assert.equal(reads.length,beforePoll+1);assert(reads.at(-1).pathname.endsWith('/workspace/status'));
  assert.equal(reads.filter(u=>u.pathname.endsWith('/workspace/query')).length,1,'unchanged status cannot reread the whole financial page');
  statusState='PENDING';await polls.shift()();
  assert.equal(reads.filter(u=>u.pathname.endsWith('/workspace/query')).length,1,'a dirty read model polls for completion instead of rerunning the slow fallback');
  statusState='READY';statusRevision='v2';await polls.shift()();await ready();
  assert.equal(reads.filter(u=>u.pathname.endsWith('/workspace/query')).length,2,'new published data triggers one reload');
  hold=true;btn('下一页').click();await new Promise(r=>setTimeout(r,10));
  assert.equal(reads.at(-1).searchParams.get('page'),'2');
  assert.equal(d.querySelector('main').getAttribute('aria-busy'),'true');
  release();await ready();
  w.FinanceWorkspace.enter('settlements');await ready();assert.equal(reads.at(-1).searchParams.get('view'),'settlements');
  w.FinanceWorkspace.enter('ads');await ready();
  assert.equal(reads.at(-1).searchParams.get('view'),'ads');
  const count=reads.length,support=d.querySelector('[data-ad-spend-support]');assert(support);
  assert(!support.textContent.includes('独立投放明细'),'closed auxiliary section must not fetch or render ledger rows');
  support.open=true;support.dispatchEvent(new w.Event('toggle'));await new Promise(r=>setTimeout(r,30));
  assert.equal(reads.length,count+1);assert.equal(reads.at(-1).searchParams.get('view'),'ads-support');
  const primaryReads=reads.filter(u=>u.searchParams.get('view')==='ads').length;
  [...support.querySelectorAll('button')].find(n=>n.textContent.trim()==='下一页').click();await new Promise(r=>setTimeout(r,30));
  assert.equal(reads.filter(u=>u.searchParams.get('view')==='ads').length,primaryReads,'auxiliary pagination cannot rebuild the primary spending report');
  assert.equal(reads.at(-1).searchParams.get('view'),'ads-support');assert.equal(reads.at(-1).searchParams.get('page'),'2');
  const beforeRevisionPoll=reads.filter(u=>u.searchParams.get('view')==='ads').length;
  primaryRevision='v2';statusRevision='v2';await polls.pop()();await ready();
  assert.equal(reads.filter(u=>u.searchParams.get('view')==='ads').length,beforeRevisionPoll+1,'auxiliary v2 cannot relabel the old v1 primary and suppress its refresh');
  w.FinanceWorkspace.reset();failPrimary=true;w.FinanceWorkspace.enter('ads');await ready();
  assert(d.querySelector('[data-ad-spend-support]'),'primary spending failures retain an auxiliary lookup entry');
  console.log('Finance dedicated page loads and lazy ad support passed');
}finally{w.FinanceWorkspace.reset();dom.window.close();}
