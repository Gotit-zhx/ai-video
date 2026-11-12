import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main></main>',{url:'https://example.test/',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,spu='9007199254740993123',second='9007199254740993124';
const catalog=[{sku:'PACK',warehouse:'菲律宾',prices:{'2026-10':7}},{sku:spu,warehouse:'菲律宾',prices:{'2026-10':11}},{sku:'MANUAL',warehouse:'菲律宾',prices:{'2026-10':19}}];
const product={shop_id:'shop',product_id:spu,sku_id:'variant',seller_sku:'PACK',name:'测试商品',spec:'规格一'};
const preview={live:true,shopId:'shop',groupId:'group',costs:catalog,products:[product],manualCosts:[],mappings:{},settings:{warehouse:'菲律宾'}};
const items=[{id:'order',shop_id:'shop',order_id:'ORDER-1',product_skus:'PACK',product_spu_values:[spu,second],product_cost_pending:0},
  {id:'missing',shop_id:'shop',order_id:'ORDER-2',product_skus:'UNKNOWN',product_spu_values:[],product_cost_pending:1}];
const context={shops:[{id:'shop',name:'测试店',platform:'SHOPEE',group_id:'group',region:'PH'}],groups:[{id:'group',name:'测试组'}],
  dates:{start:'2026-10-01',end:'2026-10-31'},preview,canManage:false,
  request:async path=>{assert(path.startsWith('/costs/orders?'));return {items,total:2,productCosts:{total:{order_count:2,pending_count:1,known_amount_cny:7}}};}};
const settle=async()=>{for(let i=0;i<4;i++)await new Promise(resolve=>setTimeout(resolve,0));};
try{
  w.eval(await fs.readFile(new URL('../../frontend/finance-costs.js',import.meta.url),'utf8'));
  w.FinanceCosts.mount(d.querySelector('main'),context);
  [...d.querySelectorAll('[role=tab]')].find(t=>t.textContent.includes('订单成本')).click();await settle();
  const headers=[...d.querySelectorAll('thead th')].map(x=>x.textContent),index=headers.indexOf('平台 SPU');
  assert.equal(index,headers.indexOf('商品 SKU')+1,'platform SPU must immediately follow merchant SKU');
  const rows=[...d.querySelectorAll('tbody tr')];assert.equal(rows[0].children[index].textContent,spu+'\n'+second);
  assert.equal(rows[1].children[index].textContent,'—');
  const sku=w.FinanceCosts.candidate(product,catalog,'菲律宾');assert.equal(sku.code,'PACK');assert.equal(sku.status,'auto');
  for(const seller_sku of ['', 'UNMATCHED']){
    const fallback=w.FinanceCosts.candidate({...product,seller_sku},catalog,'菲律宾');
    assert.equal(fallback.code,spu);assert.equal(fallback.matchedBy,'SPU');assert.equal(fallback.status,'auto');
  }
  const missed=w.FinanceCosts.candidate({...product,seller_sku:'UNKNOWN',product_id:'missing'},catalog,'菲律宾');
  assert.equal(missed.status,'missing');assert.match(missed.note,/SKU.*SPU/);
  preview.mappings={'shop:9007199254740993123:variant:菲律宾':{code:'MANUAL',warehouse:'菲律宾',factor:1,groupId:'group'}};
  w.FinanceCosts.openMapping({shopId:'shop',productId:spu,skuId:'variant'});w.FinanceCosts.mount(d.querySelector('main'),context);
  assert(d.querySelector('tbody').textContent.includes('MANUAL'),'confirmed manual relationship must survive automatic matches');
  let codeReads=0;
  const largeCosts=Array.from({length:1000},(_,i)=>({warehouse:'菲律宾',prices:{'2026-10':7},get sku(){
    assert(++codeReads<=20000,'rendering a large cost catalogue must not rescan every cost row for every product');return 'SPU-'+i;
  }}));
  const largeProducts=Array.from({length:500},(_,i)=>({shop_id:'shop',product_id:'SPU-'+i,sku_id:'v-'+i,seller_sku:'',name:'商品'+i}));
  w.FinanceCosts.reset();w.FinanceCosts.mount(d.querySelector('main'),{...context,preview:{...preview,costs:largeCosts,products:largeProducts,mappings:{}}});
  assert([...d.querySelectorAll('.fc-metric')].find(x=>x.textContent.includes('已关联 SKU')).textContent.includes('500'));
  const automaticMappings=Object.fromEntries(largeProducts.map(p=>[`shop:${p.product_id}:${p.sku_id}:菲律宾`,{code:p.product_id,warehouse:'菲律宾',groupId:'group',method:'SPU_CODE'}]));
  codeReads=0;w.FinanceCosts.reset();w.FinanceCosts.mount(d.querySelector('main'),{...context,preview:{...preview,costs:largeCosts,products:largeProducts,mappings:automaticMappings}});
  assert([...d.querySelectorAll('.fc-metric')].find(x=>x.textContent.includes('已关联 SKU')).textContent.includes('500'));
  console.log('Cost SPU column: exact IDs, SKU priority, SPU fallback, misses and manual relationship preservation passed.');
}finally{w.FinanceCosts?.reset();w.close();}
