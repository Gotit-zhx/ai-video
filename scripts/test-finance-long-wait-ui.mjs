import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page"></main>',{url:'https://example.test/#finance-overview',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,root=d.querySelector('main'),intervals=new Map(),timeouts=new Map();
let id=1,reads=0,release,reject,fail=false;
w.currentUser={permissions:['finance:view']};w.matchMedia=()=>({matches:false});
w.ResizeObserver=class{observe(){}disconnect(){}};
w.echarts={init:()=>({setOption(){},resize(){},dispose(){},getWidth(){return 1;},getHeight(){return 1;}})};
w.setInterval=(fn,ms)=>{const key=id++;intervals.set(key,{fn,ms});return key;};
w.clearInterval=key=>intervals.delete(key);
w.setTimeout=(fn,ms)=>{const key=id++;timeouts.set(key,{fn,ms});return key;};
w.clearTimeout=key=>timeouts.delete(key);
w.api=async path=>{
  reads++;
  const q=Object.fromEntries(new URL(path,'https://example.test').searchParams);
  await new Promise((ok,no)=>{release=ok;reject=no;});
  if(fail)throw Error('Internal server error');
  return {data:{filters:{...q,currency:'',displayCurrency:'CNY'},fx:{id:'fixed',time:'2026-10-01',provider:'test'},
    currencies:['PHP'],shops:[],groups:[],sync:[],totals:[],trend:[],fees:[],statuses:[],ranking:[]}};
};
for(const file of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])
  w.eval(await fs.readFile(new URL('../frontend/'+file,import.meta.url),'utf8'));
const pause=()=>new Promise(resolve=>setTimeout(resolve,5));
async function ready(){for(let i=0;i<200;i++){if(root.getAttribute('aria-busy')==='false')return;await pause();}throw Error('UI did not finish');}
const refresh=()=>[...d.querySelectorAll('button')].find(b=>b.textContent.trim()==='刷新').click();
try{
  w.FinanceWorkspace.enter('overview');await pause();
  assert.equal(root.getAttribute('aria-busy'),'true');
  for(let i=0;i<6;i++)refresh();
  await pause();assert.equal(reads,1,'repeated clicks join the existing request');
  for(const timer of intervals.values())if(timer.ms===10000)timer.fn();
  assert(root.textContent.includes('请保持页面打开，无需重复点击'));
  assert.equal(root.getAttribute('aria-busy'),'true');
  assert.equal(timeouts.size,0,'waiting must not schedule another query');
  release();await ready();
  assert.equal(intervals.size,0,'completed requests stop their waiting timer');
  fail=true;refresh();await pause();release();await ready();
  assert(root.textContent.includes('已停止自动重试'));
  assert.equal(timeouts.size,0,'failure must not create an endless retry loop');
  assert.equal(intervals.size,0);
  fail=false;refresh();await pause();release();await ready();
  assert.equal(reads,3,'explicit refresh is still available after a real failure');
  refresh();await pause();w.FinanceWorkspace.leave();assert.equal(intervals.size,0);
  reject(Error('cancelled'));await pause();assert.equal(timeouts.size,0);
}finally{w.FinanceWorkspace.leave();dom.window.close();}
console.log('Finance waiting UI passed: one request, persistent progress, no repeated clicks/retry loop, manual recovery and cancellation.');
