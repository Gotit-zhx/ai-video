import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
import {createAdvertisingFixture} from './fixtures/finance-advertising.mjs';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-shops',runScripts:'outside-only',pretendToBeVisual:true}),w=dom.window,d=w.document,root=d.querySelector('main'),fixture=createAdvertisingFixture();
w.currentUser={organizationId:'qa',userId:'admin',permissions:['finance:view','finance:manage']};w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};w.echarts={init:()=>({setOption(){},resize(){},dispose(){}})};
w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
let delayed;
w.api=async(path,options={})=>{const url=new URL(path,'https://example.test'),endpoint=url.pathname.replace('/api/v1/finance','');
 if(endpoint==='/shops')return {data:{items:fixture.shops,groups:fixture.groups,sync:[],deletions:[]}};
 if(endpoint==='/ads')return {data:[]};
 if(endpoint==='/shopee-ads')return {data:{configured:true,items:[{shop_id:'b',external_shop_id:'10001',name:'QA Shopee',currency:'THB',status:'SUCCESS',enabled:true}]}};
 if(endpoint==='/shopee-ads/authorize')return new Promise(resolve=>delayed=resolve);
 if(endpoint==='/shopee-ads/10001'&&options.method==='PUT')throw Error('暂停失败测试提示');
 throw Error('Unexpected endpoint '+endpoint);
};
for(const name of ['incremental-dom.js','incremental-charts.js','finance-shopee-ads.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../../frontend/'+name,import.meta.url),'utf8'));
const wait=async f=>{for(let i=0;i<200;i++){if(f())return;await new Promise(r=>setTimeout(r,5));}throw Error('UI timed out');};
const ready=()=>wait(()=>root.getAttribute('aria-busy')==='false');const button=text=>[...d.querySelectorAll('button')].find(b=>b.textContent.trim()===text);
try{
 w.FinanceWorkspace.enter('shops');await ready();button('生成 Shopee 广告授权链接').click();await wait(()=>delayed);
 w.FinanceWorkspace.leave();w.FinanceWorkspace.enter('shops');await ready();delayed({data:{authorizationUrl:'https://open.shopee.com/auth?state=qa',expiresAt:'2026-12-01T00:00:00Z'}});await new Promise(r=>setTimeout(r,10));
 assert(!d.querySelector('.fi-authorization-link'),'leaving and re-entering must invalidate the previous dialog');
 w.FinanceWorkspace.enter('shops');await ready();button('暂停').click();await wait(()=>root.textContent.includes('暂停失败测试提示'));
 console.log('PASS real FinanceWorkspace lifecycle and IncrementalDOM reused-action errors');
}finally{w.FinanceWorkspace.leave();dom.window.close();}
