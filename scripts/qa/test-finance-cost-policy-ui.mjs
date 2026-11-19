import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<main></main>',{url:'https://example.test/',runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,variant='9007199254740993124',spu='9007199254740993123';
const product={shop_id:'shop',product_id:spu,sku_id:variant,seller_sku:'',name:'商品',spec:'规格'};
const costs=[{sku:variant,warehouse:'菲律宾',prices:{'2026-10':11}},{sku:spu,warehouse:'菲律宾',prices:{'2026-10':99}}];
const key=`shop:${spu}:${variant}:菲律宾`;
const base={live:true,shopId:'shop',groupId:'group',costs,manualCosts:[],products:[product],settings:{warehouse:'菲律宾'}};
const context={shops:[{id:'shop',name:'测试店',platform:'SHOPEE',region:'PH',group_id:'group'}],groups:[{id:'group',name:'测试组'}],canManage:true};
const settle=async()=>{for(let i=0;i<8;i++)await new Promise(resolve=>setTimeout(resolve,0));};
try{
  w.eval(await fs.readFile(new URL('../../frontend/finance-costs.js',import.meta.url),'utf8'));
  for(const method of ['SPU_CODE','PLATFORM_SKU_CODE',undefined]){
    w.FinanceCosts.reset();w.FinanceCosts.openMapping({shopId:'shop',productId:spu,skuId:variant});
    const saved={code:method==='PLATFORM_SKU_CODE'?variant:spu,warehouse:'菲律宾',groupId:'group',factor:1,...method?{method}:{}};
    let calls=0;
    w.FinanceCosts.mount(d.querySelector('main'),{...context,preview:{...base,mappings:{[key]:saved}},request:async(path,options)=>{
      assert.equal(path,'/costs/shop/auto-match');assert.equal(options.method,'POST');calls++;
      assert(calls<=1,'automatic relationship refresh must converge');
      return {...base,mappings:{[key]:{code:variant,warehouse:'菲律宾',groupId:'group',method:'PLATFORM_SKU_CODE'}}};
    }});
    await settle();
    assert.equal(calls,method==='SPU_CODE'?1:0,'refresh old automatic choices; preserve current automatic and manual choices');
  }
  console.log('Cost policy UI: legacy automatic relationships refresh once; manual choices and current policy remain unchanged.');
}finally{w.FinanceCosts?.reset();w.close();}
