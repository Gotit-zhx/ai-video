import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main id="finance-page" class="finance-workspace"></main>',{url:'https://example.test/#finance-orders',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document;
w.currentUser={roles:['STAFF'],permissions:['finance:view']};
w.matchMedia=()=>({matches:false});w.ResizeObserver=class{observe(){}disconnect(){}};
w.echarts={init:()=>({setOption(){},resize(){},dispose(){}})};
let totals={known_amount_cny:0,amount_cny:null,ready_count:0,pending_count:1414,estimated_count:0,excluded_count:0};
w.api=async path=>{
  const u=new URL(path,'https://example.test');
  assert(u.pathname.endsWith('/records')||u.pathname.endsWith('/overview'));
  return {data:{filters:{...Object.fromEntries(u.searchParams),currency:'PHP',displayCurrency:'PHP'},
    groups:[],shops:[],sync:[],currencies:['PHP'],totals:[],ranking:[],statuses:[],fees:[],trend:[],items:[],total:0,page:1,pageSize:50,
    fieldSettings:{fields:[],visible:[],exported:[]},productCosts:{scope:'SETTLED_ORDER',basis:'商品成本按首次结算日期归属，仅计一次。',total:{...totals},shops:[]}}};
};
for(const f of ['incremental-dom.js','incremental-charts.js','finance-workspace.js'])w.eval(await fs.readFile(new URL('../frontend/'+f,import.meta.url),'utf8'));
async function ready(){for(let i=0;i<100;i++){await new Promise(r=>setTimeout(r,5));if(d.querySelector('#finance-page').getAttribute('aria-busy')==='false')return;}throw Error('UI timeout');}
const refresh=async()=>{[...d.querySelectorAll('button')].find(n=>n.textContent==='刷新').click();await ready();};
w.FinanceWorkspace.enter('orders');await ready();
const scope=d.querySelector('[aria-label="订单范围"]');scope.value='SETTLED';scope.dispatchEvent(new w.Event('change'));await ready();
let summary=d.querySelector('.fi-product-cost-summary');
assert.equal(summary.querySelector('strong').textContent,'待补齐');
assert(summary.textContent.includes('尚无可确认成本，不能按零成本计算'));
assert(summary.textContent.includes('已完成成本计算'));
assert(summary.textContent.includes('1,414'));
if(process.env.FINANCE_COST_STATUS_PREVIEW_PATH){
  const css=await fs.readFile(new URL('../frontend/finance-workspace.css',import.meta.url),'utf8');
  await fs.writeFile(process.env.FINANCE_COST_STATUS_PREVIEW_PATH,`<!doctype html><meta charset="utf-8"><style>${css}body{margin:30px;background:#f7f8fb;font:14px/1.5 -apple-system,sans-serif}.finance-workspace{max-width:1250px}</style><main class="finance-workspace">${summary.outerHTML}</main>`);
}
totals={...totals,known_amount_cny:12};await refresh();
assert.equal(d.querySelector('.fi-product-cost-summary strong').textContent,'¥ 12','known partial costs remain visible');
totals={...totals,known_amount_cny:0,amount_cny:0,pending_count:0,ready_count:1};await refresh();
assert.equal(d.querySelector('.fi-product-cost-summary strong').textContent,'¥ 0','confirmed GIFT zero stays numeric');
w.FinanceWorkspace.leave();w.close();
console.log('PASS cost summary: missing costs, partial known amounts and confirmed zero are distinct');
