import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const source=await fs.readFile(new URL('../../frontend/finance-costs.js',import.meta.url),'utf8');

const shops=[
  {id:'a',name:'A 店',group_id:'ga',platform:'SHOPEE',region:'PH'},
  {id:'b',name:'B 店',group_id:'gb',platform:'SHOPEE',region:'TH'}
];
const groups=[{id:'ga',name:'A 组'},{id:'gb',name:'B 组'}];
const context=(shopId,groupId)=>({live:true,catalogLoaded:false,settingsLoaded:false,shopId,groupId,groupName:groupId==='ga'?'A 组':'B 组'});
const catalogue=(shopId,groupId,label)=>({...context(shopId,groupId),catalogLoaded:true,settingsLoaded:true,sourceName:label+'.xlsx',
  costs:[{sku:label,warehouse:'测试仓',prices:{'2026-10':2}}],manualCosts:[],
  products:[{shop_id:shopId,product_id:'product-'+shopId,sku_id:'sku-'+shopId,seller_sku:label,name:label+' 商品',spec:'测试规格'}],
  mappings:{},settings:{},settingsVersion:0,imports:[{id:label,source_name:label+'.xlsx',row_count:1}],
  shopCatalogs:{[shopId]:{sourceName:label+'.xlsx',costRows:1}},groupCatalogs:{[groupId]:{sourceName:label+'.xlsx',costRows:1}}});
const orderResult=page=>({items:[],total:100,page,pageSize:50,productCosts:{total:{pending_count:0,ready_count:0,known_amount_cny:0}}});
const settle=()=>new Promise(resolve=>setTimeout(resolve,25));
function boot(){
  const dom=new JSDOM('<main id="root"></main>',{url:'https://example.test/#finance-costs',runScripts:'outside-only',pretendToBeVisual:true});
  const w=dom.window,d=w.document;
  w.HTMLDialogElement.prototype.showModal=function(){this.open=true;};
  w.HTMLDialogElement.prototype.close=function(){this.open=false;this.dispatchEvent(new w.Event('close'));};
  w.eval(source);
  const button=text=>[...d.querySelectorAll('button')].find(node=>node.textContent.trim()===text||node.textContent.trim().endsWith(text));
  return {dom,w,d,button,root:d.querySelector('#root')};
}

{
  const {dom,w,d,button,root}=boot(),reads=[];
  const request=async path=>{
    const url=new URL(path,'https://example.test');reads.push(url);
    if(url.pathname==='/costs/orders')return orderResult(Number(url.searchParams.get('page')));
    if(url.pathname==='/costs/context'&&url.searchParams.get('shopId')==='a')return context('a','ga');
    if(url.pathname==='/costs'&&url.searchParams.get('shopId')==='a')return catalogue('a','ga','A');
    throw Error('Unexpected request '+path);
  };
  w.FinanceCosts.openOrders({shopId:'a',groupId:'ga',start:'2026-10-01',end:'2026-10-31',costStatus:'PENDING'});
  w.FinanceCosts.mount(root,{shops,groups,canManage:false,preview:context('a','ga'),request});
  await settle();button('下一页').click();await settle();
  assert.equal(reads.at(-1).searchParams.get('page'),'2');
  w.FinanceCosts.openMapping({shopId:'b',productId:'product-b',skuId:'sku-b',returnToOrders:true});
  w.FinanceCosts.mount(root,{shops,groups,canManage:false,preview:catalogue('b','gb','B'),request});
  button('返回待补齐订单').click();await settle();
  assert.equal(d.querySelector('[aria-label="成本分组"]').value,'ga','cross-scope return restores the original group');
  assert.equal(d.querySelector('[aria-label="财务店铺"]').value,'a','cross-scope return restores the original shop');
  assert.equal(reads.at(-1).searchParams.get('page'),'2');
  assert.equal(reads.at(-1).searchParams.get('costStatus'),'PENDING');
  assert.equal(reads.at(-1).searchParams.get('start'),'2026-10-01');
  button('商品成本表').click();await settle();
  assert(d.body.textContent.includes('A.xlsx'));
  assert(!d.body.textContent.includes('B.xlsx'),'the mapping catalogue must not bleed into the restored order scope');
  dom.window.close();
}

{
  const {dom,w,d,button,root}=boot(),reads=[];
  let releaseContext;
  const delayedContext=new Promise(resolve=>{releaseContext=()=>resolve(context('b','gb'));});
  const request=async path=>{
    const url=new URL(path,'https://example.test');reads.push(url);
    if(url.pathname==='/costs/orders')return orderResult(Number(url.searchParams.get('page')));
    if(url.pathname==='/costs/context'&&url.searchParams.get('groupId')==='gb')return delayedContext;
    if(url.pathname==='/costs'&&url.searchParams.get('shopId')==='b')return catalogue('b','gb','B');
    throw Error('Unexpected request '+path);
  };
  w.FinanceCosts.openOrders({shopId:'a',groupId:'ga',start:'2026-10-01',end:'2026-10-31',costStatus:'ALL'});
  w.FinanceCosts.mount(root,{shops,groups,canManage:false,preview:catalogue('a','ga','A'),request});
  await settle();
  const group=d.querySelector('[aria-label="成本分组"]');group.value='gb';group.dispatchEvent(new w.Event('change'));
  button('SKU 关联').click();
  releaseContext();await settle();await settle();
  assert(reads.some(url=>url.pathname==='/costs'&&url.searchParams.get('shopId')==='b'),'mapping selected during a delayed context load must fetch the new scope catalogue');
  assert(d.body.textContent.includes('B 商品'));
  assert(!d.body.textContent.includes('A 商品'),'stale catalogue ownership must not cross shop/group scope');
  dom.window.close();
}

{
  const {dom,w,d,button,root}=boot(),reads=[];
  let releaseContext;
  const delayedContext=new Promise(resolve=>{releaseContext=()=>resolve(context('b','gb'));});
  const request=async path=>{
    const url=new URL(path,'https://example.test');reads.push(url);
    if(url.pathname==='/costs/orders')return orderResult(Number(url.searchParams.get('page')));
    if(url.pathname==='/costs/context'&&url.searchParams.get('groupId')==='gb')return delayedContext;
    if(url.pathname==='/costs'&&url.searchParams.get('shopId')==='b')return catalogue('b','gb','B');
    throw Error('Unexpected request '+path);
  };
  w.FinanceCosts.openOrders({shopId:'a',groupId:'ga',start:'2026-10-01',end:'2026-10-31',costStatus:'ALL'});
  w.FinanceCosts.mount(root,{shops,groups,canManage:false,preview:catalogue('a','ga','A'),request});
  await settle();
  const group=d.querySelector('[aria-label="成本分组"]');group.value='gb';group.dispatchEvent(new w.Event('change'));
  button('SKU 关联').click();button('订单成本').click();
  releaseContext();await settle();await settle();
  assert.equal(d.querySelector('[aria-label="成本分组"]').value,'gb','order panel generations must not discard the pending scope response');
  assert.equal(d.querySelector('[aria-label="财务店铺"]').value,'b');
  button('SKU 关联').click();await settle();
  assert(reads.some(url=>url.pathname==='/costs'&&url.searchParams.get('shopId')==='b'),'mapping after the scope response must load the B catalogue');
  assert(d.body.textContent.includes('B 商品'));
  assert(!d.body.textContent.includes('正在读取当前范围的成本目录…'));
  dom.window.close();
}

{
  const {dom,w,d,root}=boot(),reads=[];
  let releaseAutoMatch,autoMatchStarted=false;
  const matchedB=catalogue('b','gb','B');
  matchedB.mappings['b:product-b:sku-b:测试仓']={code:'B',warehouse:'测试仓',factor:1,groupId:'gb',method:'EXACT_CODE'};
  const request=async(path,options={})=>{
    const url=new URL(path,'https://example.test');reads.push(url);
    if(url.pathname==='/costs/a/auto-match'){
      assert.equal(options.method,'POST');autoMatchStarted=true;
      await new Promise(resolve=>{releaseAutoMatch=()=>resolve(catalogue('a','ga','A'));});
      return catalogue('a','ga','A');
    }
    if(url.pathname==='/costs'&&url.searchParams.get('groupId')==='gb')return matchedB;
    throw Error('Unexpected request '+path);
  };
  w.FinanceCosts.openMapping({shopId:'a',productId:'product-a',skuId:'sku-a'});
  w.FinanceCosts.mount(root,{shops,groups,canManage:true,preview:catalogue('a','ga','A'),request});
  for(let i=0;i<20&&!autoMatchStarted;i++)await settle();
  assert(autoMatchStarted,'A auto-match must be in flight before the scope changes');
  const group=d.querySelector('[aria-label="成本分组"]');group.value='gb';group.dispatchEvent(new w.Event('change'));await settle();
  assert.equal(d.querySelector('[aria-label="成本分组"]').value,'gb');
  releaseAutoMatch();await settle();await settle();
  assert.equal(d.querySelector('[aria-label="成本分组"]').value,'gb','late A auto-match must not replace the newer B catalogue');
  assert.equal(d.querySelector('[aria-label="财务店铺"]').value,'b');
  assert(d.body.textContent.includes('B 商品'));
  assert(!d.body.textContent.includes('A 商品'));
  dom.window.close();
}

console.log('Finance costs lazy scope: cross-scope order return and delayed context-to-mapping catalogue reload passed.');
