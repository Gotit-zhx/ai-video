import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"><header></header><section class="fi-content"></section></main>',
  {url:'https://example.test/#finance-ads',pretendToBeVisual:true,runScripts:'outside-only'});
const w=dom.window,d=w.document,root=d.querySelector('main'),header=root.querySelector('header'),content=root.querySelector('section');
const calls=[],queued=[];
let state='PROCESSING',jobs=[],reminders=[],delayOld=false;
w.currentUser={userId:'test-reader',organizationId:'test-company',permissions:['finance:view']};
w.api=async(path,options={})=>{
  const body=options.body?JSON.parse(options.body):null;
  calls.push({path,body,headers:options.headers});
  if(path.endsWith('/status')){
    if(delayOld&&body.selection.start==='2026-10-01')return new Promise(resolve=>queued.push(resolve));
    return {data:{state,revision:body.type+'-'+body.selection.start,dataAsOf:'2026-11-20T02:32:00Z',
      gapCount:state==='READY'?0:1,gaps:state==='READY'?[]:[{source:'GMV Max',shop:'示例店',detail:'缺少 1 天报表'}]}};
  }
  if(path.endsWith('/reminders')){
    reminders=[{id:'notice-1',report_type:body.type,state:'WAITING',start_date:body.selection.start,end_date:body.selection.end}];
    return {data:'accepted'};
  }
  if(path.endsWith('/dismiss')){reminders=[];return {data:'dismissed'};}
  if(options.method==='POST'){
    const job={id:'job-1',filename:'部分数据-广告消耗.xlsx',status:'QUEUED',start:body.selection.start,end:body.selection.end,dataStatus:{state}};
    jobs=[job];return {data:job};
  }
  return {data:{jobs,costJobs:[],feeJobs:[],reminders}};
};
const src=await fs.readFile(new URL('../../frontend/finance-report-exports.js',import.meta.url),'utf8');
w.eval(src);
const mod=w.FinanceReportExports;
const testButton=d.createElement('button');testButton.textContent='导出三张表';content.append(testButton);
const november={start:'2026-11-01',end:'2026-11-15',shopId:'target',platform:'TIKTOK_SHOP',currency:'USD'};
const october={...november,start:'2026-10-01',end:'2026-10-31'};
const marker=()=>content.querySelector('[data-finance-export-status]');
const wait=async test=>{
  for(let i=0;i<80;i++){if(test())return;await new Promise(r=>setTimeout(r,5));}
  throw Error('UI wait timed out');
};
const click=(parent,caption)=>{
  const target=[...parent.querySelectorAll('button')].find(button=>button.textContent===caption);
  assert(target,caption);target.click();
};
try{
  const panel=mod.mountTasks(header);
  delayOld=true;mod.decorate(testButton,'ADVERTISING',october);await wait(()=>queued.length===1);
  mod.decorate(testButton,'ADVERTISING',november);
  await wait(()=>marker().textContent.includes('处理中'));
  delayOld=false;queued[0]({data:{state:'READY',revision:'wrong-period'}});
  await wait(()=>marker().textContent.includes('缺口'));
  assert(!marker().textContent.includes('可导出'));
  click(marker(),'完成后提醒');
  await wait(()=>panel.textContent.includes('等待数据就绪'));
  const subscription=calls.find(call=>call.path.endsWith('/reminders'));
  assert.deepEqual(subscription.body.selection,november);
  reminders[0].state='READY';
  click(panel,'刷新');await wait(()=>panel.textContent.includes('处理完成 · 可导出'));
  assert(header.querySelector('[data-finance-report-reminder-notice]').textContent.includes('1 个'));

  state='PARTIAL';
  await assert.rejects(mod.submit('ADVERTISING',november),/核对版/);
  await wait(()=>marker().textContent.includes('部分待补齐'));
  assert(marker().textContent.includes('示例店 · GMV Max · 缺少 1 天报表'));
  click(marker(),'导出核对版');
  await wait(()=>panel.textContent.includes('排队中'));
  const submitted=calls.find(call=>call.path==='/api/v1/finance/report-exports'&&call.body?.partial);
  assert.equal(submitted.body.selection.format,'xlsx');
  assert(submitted.headers['Idempotency-Key']);
  jobs[0].status='READY';click(panel,'刷新');
  await wait(()=>panel.querySelector('a[download]'));
  assert(panel.textContent.includes('可下载'));
  assert.equal(panel.querySelector('a[download]').getAttribute('href'),'/api/v1/finance/report-exports/job-1/download');
  root.classList.add('fi-selection-pending');
  mod.decorate(testButton,'ADVERTISING',november);
  await wait(()=>marker().textContent==='等待当前查询完成');
  assert(!marker().querySelector('button'));
  console.log('Finance export UI passed: scoped readiness, stale-response isolation, reminders, gaps, partial copy and download.');
}finally{dom.window.close();}
