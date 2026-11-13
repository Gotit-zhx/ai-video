import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
import {createAdvertisingFixture} from './fixtures/finance-advertising.mjs';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-overview',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,root=d.querySelector('main'),fixture=createAdvertisingFixture(),reads=[];
w.currentUser={organizationId:'qa',userId:'viewer',permissions:['finance:view']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
w.echarts={init:()=>({setOption(){},resize(){},dispose(){}})};
let slowSpend,releaseSpend,holdMarket=false,releaseMarket,failMarket=false;
let market={provider:'QA daily reference',rateDate:'2026-11-10',updatedAt:'2026-11-10',fetchedAt:'2026-11-11T02:00:00Z',status:'READY',rates:{PHP:0.12,THB:0.21}};
const funding={platform:'TIKTOK_SHOP',shop_id:'a',shop_name:'演示菲律宾店',group_id:'baby',group_name:'母婴组',currency:'PHP',amount:101,cash_amount:0,credit_amount:null,grant_amount:2,net_amount:99,records:1,pending_records:0};
const spend=q=>({totals:[funding,{...funding,currency:'THB',amount:202}],groups:[funding],shops:[funding],items:[],total:0,page:1,pageSize:50,filters:{currency:q.currency||'',displayCurrency:'ORIGINAL'}});
w.api=async(path,options={})=>{
 const url=new URL(path,'https://example.test'),q=Object.fromEntries(url.searchParams),endpoint=url.pathname.replace('/api/v1/finance','');reads.push({endpoint,q});
 if(endpoint==='/overview'){const data=fixture.overview(q);data.fx={id:'history-fixed',mode:'HISTORICAL',basis:'按业务日期历史汇率',provider:'Historical QA'};return {data};}
 if(endpoint==='/records')return {data:fixture.records(q)};
 if(endpoint==='/ads')return {data:fixture.accounts};
 if(endpoint==='/advertising/spend'){const result=spend(q);if(slowSpend){slowSpend=false;await new Promise(r=>releaseSpend=r);}return {data:result};}
 if(endpoint==='/fx/market'){const result=structuredClone(market);if(holdMarket){holdMarket=false;await new Promise(r=>releaseMarket=r);}if(failMarket)throw Error('rate provider unavailable');return {data:result};}
 throw Error('Unexpected API '+endpoint);
};
for(const name of ['incremental-dom.js','incremental-charts.js','finance-ad-spend.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../../frontend/'+name,import.meta.url),'utf8'));
const wait=async predicate=>{for(let i=0;i<300;i++){if(predicate())return;await new Promise(r=>setTimeout(r,5));}throw Error('UI timeout '+root.textContent.slice(-200));};
const ready=()=>wait(()=>root.getAttribute('aria-busy')==='false');
const enter=async route=>{w.FinanceWorkspace.enter(route);await ready();};
const namedButton=(host,text)=>[...host.querySelectorAll('button')].find(n=>n.textContent.trim()===text);
try{
 await enter('overview');
 assert(root.querySelector('[data-metric="AD"]').textContent.includes('广告费用（已入账）'),'posted costs must have their own stable label');
 await wait(()=>root.querySelector('[data-market-fx]')?.textContent.includes('1 PHP = 0.12 CNY'));
 const quote=root.querySelector('[data-market-fx]');assert(quote.textContent.includes('QA daily reference'));assert(quote.textContent.includes('报价更新时间：2026-11-10'),'a date-only publication must not become an invented exact timestamp');assert(quote.textContent.includes('获取时间'));assert(quote.textContent.includes('业务日期历史汇率'));assert(!root.textContent.includes('按同一参考汇率折算'));
 const adAmount=root.querySelector('[data-metric="AD"] strong').textContent,overviewReads=reads.filter(r=>r.endpoint==='/overview').length;
 market={...market,rates:{PHP:0.15,THB:0.22}};namedButton(quote,'刷新行情').click();await wait(()=>root.querySelector('[data-market-fx]').textContent.includes('1 PHP = 0.15 CNY'));
 assert.equal(root.querySelector('[data-metric="AD"] strong').textContent,adAmount);assert.equal(reads.filter(r=>r.endpoint==='/overview').length,overviewReads,'market refresh must not recalculate historical totals');
 const start=d.querySelector('[aria-label="开始日期"]');start.value='2026-09-30';start.dispatchEvent(new w.Event('change'));await ready();assert.equal(reads.filter(r=>r.endpoint==='/overview').at(-1).q.fxSnapshot,'','a new business-date range must resolve its own historical snapshot');
 const month=d.querySelector('[aria-label="统计月份（店铺当地）"]');month.value='2026-10';month.dispatchEvent(new w.Event('change'));await ready();assert.equal(reads.filter(r=>r.endpoint==='/overview').at(-1).q.fxSnapshot,'','month shortcuts must also resolve a fresh historical scope');
 root.querySelector('[data-metric="AD"]').click();assert.equal(w.location.hash,'#finance-ads');await enter('ads');
 const checkSpending=()=>{assert.equal(root.querySelectorAll('[data-ad-spend-workspace]').length,1);assert.equal(root.querySelectorAll('[data-ad-currency]').length,2);assert.equal(root.querySelectorAll('[data-ad-currency="PHP"]').length,1);assert.equal(root.querySelectorAll('[data-ad-currency="THB"]').length,1);assert.equal(root.querySelectorAll('[data-ad-spend-support] .fi-metric').length,0,'ledger checks must not repeat a second row of large cards');assert(root.querySelector('[data-ad-spend-support]').textContent.includes('广告费用（已入账）'));const headers=[...root.querySelectorAll('[data-ad-spend-workspace]>.fi-card table thead')][0].textContent;assert(headers.includes('广告消耗（投放 / 账单参考）'));assert(headers.includes('净广告消耗'));};
 checkSpending();for(let i=0;i<3;i++)await enter('ads');checkSpending();
 const groups=[...root.querySelectorAll('[data-ad-currency]')];assert(groups.find(n=>n.dataset.adCurrency==='PHP').textContent.includes('101.00'));assert(groups.find(n=>n.dataset.adCurrency==='THB').textContent.includes('202.00'));assert(!root.querySelector('[data-ad-spend-workspace]').textContent.includes('303.00'),'different currencies must not be summed');
 slowSpend=true;w.FinanceWorkspace.enter('ads');await wait(()=>releaseSpend);await enter('overview');releaseSpend();await new Promise(r=>setTimeout(r,15));assert.equal(root.querySelectorAll('[data-ad-spend-workspace]').length,0,'a late spending response cannot restore the advertising page');
 await enter('ads');checkSpending();await enter('overview');
 market={...market,status:'STALE',error:'upstream unavailable',rateDate:'2026-11-08'};namedButton(root.querySelector('[data-market-fx]'),'刷新行情').click();await wait(()=>root.querySelector('[data-market-fx]').textContent.includes('过期'));assert(root.querySelector('[data-market-fx]').textContent.includes('2026-11-08'));
 market={...market,status:'UNAVAILABLE',rates:{PHP:0,THB:null}};namedButton(root.querySelector('[data-market-fx]'),'刷新行情').click();await wait(()=>root.querySelector('[data-market-fx]').textContent.includes('PHP：报价暂不可用'));assert(!root.querySelector('[data-market-fx]').textContent.includes('1 PHP = 0'),'unavailable exchange rates must not become zero quotes');
 failMarket=true;namedButton(root.querySelector('[data-market-fx]'),'刷新行情').click();await wait(()=>root.querySelector('[data-market-fx]').textContent.includes('获取失败'));assert.equal(root.querySelector('[data-metric="AD"] strong').textContent,adAmount);
 failMarket=false;holdMarket=true;namedButton(root.querySelector('[data-market-fx]'),'刷新行情').click();await wait(()=>releaseMarket);w.FinanceWorkspace.leave();w.currentUser={...w.currentUser,organizationId:'other'};market={...market,provider:'Other company view',status:'CURRENT',rates:{PHP:0.18}};await enter('overview');await wait(()=>root.querySelector('[data-market-fx]').textContent.includes('Other company view'));releaseMarket();await new Promise(r=>setTimeout(r,15));assert(root.querySelector('[data-market-fx]').textContent.includes('Other company view'),'late quotes cannot restore a previous identity');
 console.log('Finance ad navigation, currency grouping and independent market FX UI passed');
}finally{w.FinanceWorkspace.reset();dom.window.close();}
