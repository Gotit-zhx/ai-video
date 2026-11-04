import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<!doctype html><div id="finance-page"></div>',{url:'https://example.test/#finance-orders',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,requests=[];
w.currentUser={permissions:['finance:view']};w.matchMedia=()=>({matches:false});
w.ResizeObserver=class{observe(){}disconnect(){}};
let overviewGate=null,recordsGate=null;
const shops=[{id:'baby-ph',group_id:'baby',name:'母婴原名',display_name:'母婴别名',platform:'SHOPEE'},
  {id:'baby-tk',group_id:'baby',name:'母婴 TK',platform:'TIKTOK_SHOP'},
  {id:'perfume',group_id:'perfume',name:'香水店',platform:'SHOPEE'},
  {id:'ungrouped',name:'未分组店',platform:'SHOPEE'}];
const groups=[{id:'baby',name:'母婴组'},{id:'perfume',name:'香水组'},{id:'empty',name:'空分组'}];
w.api=async path=>{
  const url=new URL(path,'https://example.test');requests.push(url);
  const cny=url.searchParams.get('displayCurrency')==='CNY';
  if(url.pathname.endsWith('/overview')){
    if(overviewGate)await overviewGate;
    return {data:{filters:{...Object.fromEntries(url.searchParams),currency:cny?'':'PHP',displayCurrency:cny?'CNY':'PHP'},
      ...(cny?{fx:{id:'test-cny-snapshot',time:'2026-10-24T00:00:00Z',provider:'test'}}:{}),
      currencies:['PHP','MYR'],shops,groups,sync:[],totals:[],trend:[],fees:[],statuses:[],ranking:[],
      productCosts:{scope:'SETTLED_ORDER',total:{amount_cny:12,pending_count:0},shops:[],basis:'首次平台结算'}}};
  }
  assert(url.pathname.endsWith('/records'));
  if(recordsGate)await recordsGate;
  assert.equal(url.searchParams.get('currency'),cny?'':'PHP','CNY includes all currencies; native rows use the resolved currency');
  if(cny)assert.equal(url.searchParams.get('fxSnapshot'),'test-cny-snapshot','CNY rows use the overview FX snapshot');
  assert.equal(url.searchParams.get('includeProductCosts'),'false','reuse the dashboard settled cost summary');
  const page=Number(url.searchParams.get('page'));
  return {data:{items:[{id:'row-'+page,order_id:'ORDER-'+page,platform:'SHOPEE'}],page,pageSize:50,total:100,
    fieldSettings:{fields:[{id:'order_id',label:'订单编号',type:'text'}],visible:['order_id']}}};
};
for(const file of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../frontend/'+file,import.meta.url),'utf8'));
const pause=()=>new Promise(r=>setTimeout(r,5));
async function ready(){for(let i=0;i<200;i++){await pause();if(d.querySelector('#finance-page').getAttribute('aria-busy')==='false')return;}throw Error('UI timeout');}
const button=text=>[...d.querySelectorAll('button')].find(n=>n.textContent.trim()===text);
w.FinanceWorkspace.enter('orders');await ready();
assert.equal(d.querySelector('tbody tr').textContent.includes('ORDER-1'),true);
assert(d.querySelector('.fi-product-cost-summary').textContent.includes('12'));
assert(d.querySelector('script[src*="echarts"]'),'table is usable while the chart download is still pending');
assert.equal(requests[0].pathname,'/api/v1/finance/overview');
assert.equal(requests[1].pathname,'/api/v1/finance/records');
assert.equal(requests[0].searchParams.get('displayCurrency'),'CNY');
assert.equal(requests[0].searchParams.get('currency'),'','first entry includes all currencies');
const retained=d.querySelector('tbody tr');
let releaseOverview,releaseRecords;
overviewGate=new Promise(r=>releaseOverview=r);recordsGate=new Promise(r=>releaseRecords=r);
button('下一页').click();await pause();
assert.equal(requests.at(-2).pathname,'/api/v1/finance/overview');
assert.equal(requests.at(-1).pathname,'/api/v1/finance/records','pagination launches both requests before either response');
assert.equal(d.querySelector('tbody tr'),retained);
assert.equal(d.querySelector('#finance-page').getAttribute('aria-busy'),'true');
releaseRecords();await pause();assert.equal(d.querySelector('tbody tr'),retained,'commit the matching summary and rows together');
releaseOverview();overviewGate=null;recordsGate=null;await ready();
assert(d.querySelector('tbody tr').textContent.includes('ORDER-2'));
// A late chart response initializes only the charts, retaining visible rows.
const second=d.querySelector('tbody tr');let paints=0;
w.echarts={init:()=>({setOption(){paints++;},resize(){},dispose(){},getWidth(){return 1;},getHeight(){return 1;}})};
d.querySelector('script[src*="echarts"]').dispatchEvent(new w.Event('load'));await pause();
assert.equal(d.querySelector('tbody tr'),second);
// A superseded page request must not overwrite the newly selected calendar.
overviewGate=new Promise(r=>releaseOverview=r);recordsGate=new Promise(r=>releaseRecords=r);
button('上一页').click();await pause();
const firstOverview=releaseOverview,firstRecords=releaseRecords;
overviewGate=null;recordsGate=null;
const start=d.querySelector('[aria-label="开始日期"]');start.value='2026-10-01';start.dispatchEvent(new w.Event('change'));await ready();
const latest=d.querySelector('tbody tr');firstRecords();firstOverview();await pause();
assert.equal(d.querySelector('tbody tr'),latest);
assert.equal(d.querySelector('[aria-label="开始日期"]').value,'2026-10-01');
const choose=(label,value)=>{const n=d.querySelector(`[aria-label="${label}"]`);n.value=value;n.dispatchEvent(new w.Event('change'));};
const shopValues=()=>[...d.querySelector('[aria-label="店铺"]').options].map(n=>n.value);
choose('店铺分组','baby');await ready();choose('店铺','baby-ph');await ready();
assert.equal(d.querySelector('[aria-label="店铺"]').selectedOptions[0].textContent,'母婴别名');
overviewGate=new Promise(r=>releaseOverview=r);
choose('店铺分组','perfume');
assert.deepEqual(shopValues(),['','perfume'],'group/shop cascade is synchronous even while summary is blocked');
assert.equal(d.querySelector('[aria-label="店铺"]').value,'');
assert.equal(requests.at(-1).searchParams.get('groupId'),'perfume');assert.equal(requests.at(-1).searchParams.get('shopId'),'');
const staleGroup=releaseOverview;overviewGate=null;
choose('店铺分组','baby');choose('平台','TIKTOK_SHOP');
assert.deepEqual(shopValues(),['','baby-tk'],'group and platform filters intersect immediately');
await ready();staleGroup();await pause();
assert.deepEqual(shopValues(),['','baby-tk'],'late results cannot restore obsolete shops');
choose('平台','');await ready();choose('店铺分组','UNGROUPED');
assert.deepEqual(shopValues(),['','ungrouped']);await ready();
choose('店铺分组','empty');assert.deepEqual(shopValues(),['']);await ready();
choose('店铺分组','');assert.deepEqual(shopValues(),['','baby-ph','baby-tk','perfume','ungrouped']);await ready();
const nativeStart=requests.length;
button('原币').click();await ready();
assert.equal(requests[nativeStart].searchParams.get('currency'),'');
assert.equal(requests[nativeStart+1].searchParams.get('currency'),'PHP','native mode still resolves currency before fetching rows');
button('人民币 ¥').click();await ready();
assert.equal(requests.at(-2).searchParams.get('currency'),'','switching to CNY clears the previous native currency');
assert.equal(requests.at(-1).searchParams.get('displayCurrency'),'CNY');
button('原币').click();await ready();
w.FinanceWorkspace.reset();w.FinanceWorkspace.enter('orders');await ready();
assert.equal(requests.at(-2).searchParams.get('displayCurrency'),'CNY','company/login reset restores CNY');
assert.equal(requests.at(-2).searchParams.get('currency'),'','company/login reset clears the native filter');
w.FinanceWorkspace.leave();dom.window.close();
console.log('Finance query UI: currency-safe parallel loads, reused costs, nonblocking charts, stable rows and stale-response protection passed.');
