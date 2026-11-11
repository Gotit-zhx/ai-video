import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
import {createAdvertisingFixture} from './fixtures/finance-advertising.mjs';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-ads',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,root=d.querySelector('main'),fixture=createAdvertisingFixture(),writes=[],reads=[];
fixture.shops[1].external_shop_id='SHOP_123';
w.currentUser={organizationId:'qa',userId:'reader',permissions:['finance:view']};w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
w.echarts={init:()=>({setOption(){},resize(){},dispose(){}})};w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
const summary={platform:'TIKTOK_SHOP',group_id:'baby',group_name:'母婴组',currency:'CNY',amount:10,cash_amount:0,credit_amount:9,grant_amount:1,records:2,shop_count:1,pending_records:1,net_amount:9};
const spend={totals:[summary],groups:[summary,{...summary,platform:'SHOPEE',group_id:'clothes',group_name:'服装组',amount:20,cash_amount:null,credit_amount:null,grant_amount:null,records:1,pending_records:0}],shops:[{...summary,shop_id:'a',shop_name:'演示菲律宾店'},{...summary,platform:'SHOPEE',shop_id:'b',shop_name:'演示泰国店',amount:20,cash_amount:null,credit_amount:null,grant_amount:null}],items:[{...summary,source_key:'record-a',shop_id:'UNASSIGNED',shop_name:'待分配店铺',source:'IMPORT_FUNDING',record_date:'2026-10-01',campaign_id:'9012345678901234',campaign_name:'共享系列',account_id:'123',account_name:'共享广告账户',source_currency:'USD',original_cash_amount:0,original_credit_amount:9,original_grant_amount:1,original_amount:10,assignment_status:'PENDING'}],accountFunding:[{platform:'TIKTOK_SHOP',account_name:'账户日报核对',account_id:'123',currency:'CNY',cash_amount:6,credit_amount:null,reason_credit_amount:'接口未拆分',grant_amount:2,amount:10,net_amount:8}],total:51,page:1,pageSize:50,overlapExcluded:2};
let failSpend=false,resumeHeld;
w.api=async(path,options={})=>{const u=new URL(path,'https://example.test'),q=Object.fromEntries(u.searchParams),endpoint=u.pathname.replace('/api/v1/finance','');reads.push({endpoint,q});
 if(options.method&&options.method!=='GET')writes.push({endpoint,options});
 if(endpoint==='/overview')return {data:fixture.overview(q)};
 if(endpoint==='/records')return {data:fixture.records(q)};
 if(endpoint==='/ads')return {data:fixture.accounts};
 if(endpoint==='/advertising/spend'){if(failSpend)throw Error('offline');if(q.search==='slow')await new Promise(resolve=>{resumeHeld=resolve;});return {data:{...spend,page:Number(q.page)||1,items:spend.items.map(r=>({...r,campaign_name:q.search||r.campaign_name}))}};}
 if(endpoint==='/advertising/spend/record-a')return {data:{...spend.items[0],fingerprint:'verified',currency:'USD',amount:10,cash_amount:0,credit_amount:9,grant_amount:1,allocations:[]}};
 if(endpoint.endsWith('/allocations'))return {data:'已保存'};
 throw Error('Unexpected API '+endpoint);
};
for(const name of ['incremental-dom.js','incremental-charts.js','finance-ad-spend.js','finance-workspace.js']){let code;try{code=await fs.readFile(new URL('../../frontend/'+name,import.meta.url),'utf8');}catch(e){if(name==='finance-ad-spend.js'&&e.code==='ENOENT')continue;throw e;}w.eval(code);}
const wait=async fn=>{for(let i=0;i<300;i++){if(fn())return;await new Promise(r=>setTimeout(r,5));}throw Error('UI timeout '+root.textContent.slice(-200));};
const ready=()=>wait(()=>root.getAttribute('aria-busy')==='false');
try{
 w.FinanceWorkspace.enter('ads');await ready();
 const main=root.querySelector('[data-ad-spend-workspace]');assert(main,'three primary spending tables are mounted');
 assert.deepEqual([...main.querySelectorAll('h3')].map(n=>n.textContent),['分组广告消耗','店铺广告消耗','店铺消耗明细']);
 for(const t of main.querySelectorAll('table'))for(const label of ['现金消耗','信用额度消耗','广告费用赠款消耗'])assert([...t.querySelectorAll('th')].some(n=>n.textContent.includes(label)),label);
 assert.deepEqual([...main.querySelectorAll('table')][2].querySelector('thead tr')&&[...[...main.querySelectorAll('table')][2].querySelectorAll('thead th')].slice(0,8).map(n=>n.textContent),['日期','平台','店铺','展示币种','现金消耗','信用额度消耗','广告费用赠款消耗','消耗合计'],'the first visible detail columns prioritize ownership and spending');
 assert([...main.querySelectorAll(':scope > .fi-card table')].slice(0,3).every(t=>t.textContent.includes('净消耗（剔除赠款）')));assert(main.querySelector('details[data-ad-funding]'));assert(!main.querySelector('details[data-ad-funding]').open);assert(main.textContent.includes('账户日报核对'));assert(main.textContent.includes('接口未拆分'));
 assert(main.textContent.includes('Shopee'));assert(main.textContent.includes('TikTok'));assert(main.textContent.includes('未提供'));assert(main.textContent.includes('待分配店铺'));assert(main.textContent.includes('9012345678901234'));
 assert(!main.querySelector('[data-ad-spend-allocate]'),'read-only viewer has no assignment action');
 assert(root.querySelector('details[data-ad-spend-support]'));assert(!root.querySelector('details[data-ad-spend-support]').open);
 const search=main.querySelector('[aria-label="消耗系列或账户查询"]');search.value='共享';search.closest('form').dispatchEvent(new w.Event('submit',{cancelable:true}));await ready();assert.equal(reads.filter(r=>r.endpoint==='/advertising/spend').at(-1).q.search,'共享');
 main.querySelector('[data-ad-spend-next]').click();await ready();assert.equal(reads.filter(r=>r.endpoint==='/advertising/spend').at(-1).q.page,'2');
 const query=root.querySelector('[aria-label="消耗系列或账户查询"]');query.value='slow';query.closest('form').dispatchEvent(new w.Event('submit',{cancelable:true}));await wait(()=>!!resumeHeld);
 query.value='latest';query.closest('form').dispatchEvent(new w.Event('submit',{cancelable:true}));await new Promise(r=>setTimeout(r,25));assert(reads.some(r=>r.endpoint==='/advertising/spend'&&r.q.search==='latest'),'a newer search must replace an in-flight spending read');await ready();resumeHeld();await new Promise(r=>setTimeout(r,10));assert(root.querySelector('[data-ad-spend-workspace]').textContent.includes('latest'));
 w.currentUser.permissions.push('finance:manage');w.FinanceWorkspace.enter('ads');await ready();root.querySelector('[data-ad-spend-allocate]').click();await wait(()=>d.querySelector('dialog [data-allocation-add]'));
 d.querySelector('[data-allocation-add]').click();const form=d.querySelector('dialog');for(const [field,value]of Object.entries({amount:'10',cash_amount:'0',credit_amount:'9',grant_amount:'1'}))form.querySelector('[data-allocation-field="'+field+'"]').value=value;
 [...form.querySelectorAll('button')].find(b=>b.textContent==='保存核实金额').click();await wait(()=>writes.some(r=>r.endpoint.endsWith('/allocations')));await wait(()=>!d.querySelector('dialog'));
 const sent=JSON.parse(writes.at(-1).options.body);assert.equal(sent.allocations[0].shop_id,'a');assert.equal(sent.allocations[0].credit_amount,'9');assert.equal(sent.fingerprint,'verified');
 w.FinanceWorkspace.leave();w.currentUser={...w.currentUser,organizationId:'another-company'};w.FinanceWorkspace.enter('ads');await ready();assert.equal(reads.filter(r=>r.endpoint==='/advertising/spend').at(-1).q.search,'','spending search does not leak into the next company');assert.equal(reads.filter(r=>r.endpoint==='/advertising/spend').at(-1).q.page,'1');
 [...root.querySelectorAll('button')].find(b=>b.textContent==='导入消耗报表').click();await wait(()=>d.querySelector('dialog [aria-label="平台"]'));const platform=d.querySelector('dialog [aria-label="平台"]');platform.value='SHOPEE';platform.dispatchEvent(new w.Event('change'));
 const source=d.querySelector('dialog [aria-label="已有投放来源（可选）"]');assert(source,'Shopee has a shop source selector');source.value='SHOP_123';source.dispatchEvent(new w.Event('change'));assert.equal(d.querySelector('dialog input').value,'SHOP_123');assert(![...source.options].some(n=>n.value==='100001'),'TikTok accounts are not offered as Shopee source shops');d.querySelector('dialog').close();
 failSpend=true;w.FinanceWorkspace.enter('ads');await ready();assert(root.textContent.includes('店铺消耗读取失败'));assert(!root.querySelector('[data-ad-spend-workspace]'),'failed reads must not display stale primary amounts');
 console.log('Finance advertising spending workspace UI passed');
}finally{w.FinanceWorkspace.leave();dom.window.close();}
