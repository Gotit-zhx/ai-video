import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const scripts=await Promise.all(['incremental-dom.js','incremental-charts.js','finance-workspace.js'].map(f=>fs.readFile(new URL('../../frontend/'+f,import.meta.url),'utf8')));
for(const timezone of ['Pacific/Honolulu','UTC','Asia/Shanghai']){
  process.env.TZ=timezone;
  const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test',runScripts:'outside-only',pretendToBeVisual:true});
  const w=dom.window,d=w.document,reads=[];
  const NativeDate=w.Date;
  w.Date=class extends NativeDate{constructor(...args){super(...(args.length?args:['2026-10-31T17:30:00Z']));}static now(){return NativeDate.parse('2026-10-31T17:30:00Z');}};
  w.currentUser={organizationId:'org',userId:'user',permissions:['finance:view']};
  w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
  w.echarts={init:()=>({setOption(){},resize(){},dispose(){}})};
  const shops=[{id:'th',name:'泰国店',timezone:'Asia/Bangkok',platform:'SHOPEE',currency:'THB',enabled:true,last_success_at:'2026-10-31T17:30:00Z'}];
  w.api=async path=>{
    const u=new URL(path,'https://example.test');reads.push(u);
    if(u.pathname.endsWith('/ads'))return {data:[]};
    if(u.pathname.endsWith('/shops'))return {data:{items:shops,groups:[],sync:['ORDER','SETTLEMENT'].map(channel=>({shop_id:'th',channel,previous_month:'2026-10',previous_month_checked:true,previous_month_complete:true,status:'SUCCESS'}))}};
    return {data:{filters:Object.fromEntries(u.searchParams),shops,groups:[],sync:[],currencies:['THB'],totals:[],trend:[],ranking:[],fees:[],statuses:[]}};
  };
  scripts.forEach(s=>w.eval(s));
  const settle=async()=>{for(let i=0;i<100;i++){await new Promise(r=>setTimeout(r,5));if(d.querySelector('main').getAttribute('aria-busy')==='false')return;}throw Error('UI timeout');};
  try{
    w.FinanceWorkspace.enter('overview');await settle();
    assert.equal(reads[0].searchParams.get('start'),'2026-10-01',timezone);
    assert.equal(reads[0].searchParams.get('end'),'2026-10-31',timezone);
    assert.equal(reads[0].searchParams.get('dateBasis'),'shop-calendar-v1');
    assert(d.querySelector('.fi-calendar-note').textContent.includes('多店铺分别取各自当地日期'));
    let month=d.querySelector('[aria-label="统计月份（店铺当地）"]');month.value='2024-02';month.dispatchEvent(new w.Event('change'));await settle();
    assert.equal(reads.at(-1).searchParams.get('end'),'2024-02-29');
    const start=d.querySelector('[aria-label="开始日期"]');start.value='2024-02-03';start.dispatchEvent(new w.Event('change'));await settle();
    assert.equal(d.querySelector('[aria-label="统计月份（店铺当地）"]').value,'','custom range must not claim full month');
    [...d.querySelectorAll('button')].find(b=>b.textContent==='上月整月').click();await settle();
    assert.equal(reads.at(-1).searchParams.get('start'),'2026-10-01');
    w.FinanceWorkspace.enter('shops');await settle();
    assert(d.body.textContent.includes('上月抓取完成'));
    assert(d.body.textContent.includes('不代表官方对账通过'));
    const last=d.querySelector('.fi-shops-table tbody').textContent;
    assert(last.includes('2026/11/1')&&last.includes('01:30:00'),timezone+' operations show Beijing');
  }finally{w.FinanceWorkspace.reset();dom.window.close();}
}
console.log('PASS local-month UI: three browser zones, month/year boundaries, leap month, custom dates and Beijing sync time.');
