import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source=fs.readFileSync(new URL('../src/content/shopee.js',import.meta.url),'utf8');
function harness({preopened=false,missing=false,changeProduct=false}={}){
 let opened=preopened,clicks=0,closed=0;
 const image={src:'https://down-ph.img.susercontent.com/file/chart',visible:true};
 const close={textContent:'',getAttribute:()=> 'Close',click(){opened=false;closed++;}};
 const parent={contains:()=>false,querySelector:()=>null,querySelectorAll:q=>q==='img'?[image]:[close],parentElement:null};
 const title={textContent:'Size Chart',parentElement:parent,closest:()=>null,visible:true};
 const button={textContent:'Size Chart',getAttribute:()=>null,closest:()=>null,visible:true,click(){clicks++;opened=!missing;if(changeProduct)context.location.href='https://shopee.ph/product/12/99';}};
 const common={cleanText:s=>String(s||'').trim(),isVisible:n=>n.visible,absoluteUrl:s=>s,registerAdapter(){}};
 const context={location:{href:'https://shopee.ph/product/12/34'},URL,setTimeout:fn=>{fn();},document:{querySelectorAll:()=>opened?[title]:[]},HeyiCollector:common};
 vm.runInNewContext(source,context);
 return {read:already=>common.readShopeeSizeChart({querySelectorAll:()=>[button]},{shopId:'12',productId:'34'},already),state:()=>({clicks,closed,opened})};
}
let h=harness();let result=await h.read(false);assert.equal(result.images[0].role,'SIZE_CHART');assert.equal(result.missing,false);assert.deepEqual(h.state(),{clicks:1,closed:1,opened:false});
h=harness({preopened:true});await h.read(false);assert.deepEqual(h.state(),{clicks:0,closed:0,opened:true});
h=harness();await h.read(true);assert.equal(h.state().clicks,0);
h=harness({missing:true});assert.equal((await h.read(false)).missing,true);
h=harness({changeProduct:true});await assert.rejects(h.read(false),/页面已切换/);
console.log('Shopee size-chart popup: capture, provenance role, closure, missing/identity guards passed');
