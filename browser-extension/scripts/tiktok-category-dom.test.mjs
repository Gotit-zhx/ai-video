import assert from 'node:assert/strict';
import vm from 'node:vm';
import {readFileSync} from 'node:fs';
const common = {cleanText:(s,n)=>String(s||'').trim().slice(0,n),isVisible:e=>e.visible!==false,firstText:()=>({text:'',selector:''})};
const c=vm.createContext({HeyiCollector:common,URL,location:{href:'https://shop.tiktok.com/view/product/1730135047201523374?region=MY'}});
vm.runInContext(readFileSync(new URL('../src/content/tiktok.js',import.meta.url),'utf8'),c);
const base='https://shop.tiktok.com';
function fixture(items,other=[]) {
 const links=items.map(([text,url,visible=true])=>({textContent:text,href:url,visible}));
 const extras=other.map(href=>({href}));
 const parent={contains:e=>links.includes(e),querySelectorAll:()=>[...extras,...links]};
 links.forEach(e=>e.parentElement=parent);
 return {querySelectorAll:()=>links};
}
const product=base+'/view/product/1730135047201523374?region=MY';
const items=[['女装与女士内衣',base+'/my/c/womenswear-underwear/600001'],['女士上装',base+'/my/c/women-s-tops/842248'],['女士短袖',base+'/my/c/women-s-t-shirts/600002']];
assert.equal(common.extractTikTokCategory(fixture(items,[base+'/my']),product).text,'女装与女士内衣 > 女士上装 > 女士短袖');
assert.equal(common.extractTikTokCategory(fixture(items,[base+'/my/pdp/unrelated/123456789']),product).text,'','recommendation links must not become a taxonomy path');
assert.equal(common.extractTikTokCategory(fixture([['Wrong','https://other.example/my/c/shirts/123456']]),product).text,'');
assert.equal(common.extractTikTokCategory(fixture(items.map(([t,u])=>[t,u,false])),product).text,'');
assert.equal(common.extractTikTokCategory(fixture(items),base+'/my').text,'');
assert.equal(common.extractTikTokCategory(fixture(Array.from({length:9},(_,i)=>['Category '+i,base+'/my/c/category/'+(1000+i)])),product).text,'','category menu is not the product taxonomy');
console.log('TikTok footer category extraction tests passed');

const inject=readFileSync(new URL('../src/content/inject.js',import.meta.url),'utf8');
const start=inject.indexOf('  async function runCapture(control, root)');
const end=inject.indexOf('\n\n  function isIdempotentDuplicateConflict',start);
const captureState=new Map();let category=null,calls=0;
const ctx=vm.createContext({
 captureState,authenticated:true,MESSAGE_CAPTURE:'capture',
 adapter:{blockedReason:()=>'',extract:async()=>({title:'Shirt',sourcePlatform:'TIKTOK_SHOP',categoryName:category,skuCompleteness:{complete:true}})},
 ensureAuthenticated:async()=>true,
 HeyiVideoSources:{deduplicate:()=>[]},
 setUrlState:(url,state,message)=>captureState.set(url,{state,message}),
 document:{body:{},querySelector:()=>null,querySelectorAll:()=>[]},
 chrome:{runtime:{sendMessage:async()=>{calls++;return {ok:true,data:{skuCompleteness:{complete:true}}};}}}
});
vm.runInContext(inject.slice(start,end)+'\nthis.runCapture=runCapture;',ctx);
const control={productUrl:product,pageType:'DETAIL'};
await ctx.runCapture(control,{});
assert.equal(captureState.get(product).state,'incomplete');
assert.match(captureState.get(product).message,/类目/);
category='Women > T-shirts';
await ctx.runCapture(control,{});
assert.equal(captureState.get(product).state,'success');
assert.equal(calls,2);
console.log('Missing TikTok category stays retryable after saving SKU data');
