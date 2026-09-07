const test=require('node:test'),assert=require('node:assert/strict'),vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
function setup(){
  const nodes=[],calls=[];let fail=true;
  function node(tag){const n={tag,children:[],handlers:{},value:'',append(e){this.children.push(e);if(this.tag==='select'&&!this.value)this.value='STOCK_MARKETING';},replaceChildren(){this.children=[];},addEventListener(k,f){this.handlers[k]=f;}};nodes.push(n);return n;}
  const root=node('div');vm.runInNewContext(fs.readFileSync(path.join(__dirname,'../commerce-designs.js'),'utf8'),{
    document:{getElementById:()=>root,createElement:node},crypto:{randomUUID:()=>`key-${calls.length}`},encodeURIComponent,
    api:async(url,options)=>{calls.push({url,options});if(options){if(fail){fail=false;throw new Error('network');}return {data:[{id:'draft'}]};}
      if(url.includes('?page='))return {data:{page:0,totalPages:1,content:[{id:'p1',title:'商品1',sourcePlatform:'SHOPEE'}]}};
      if(url.endsWith('/p1'))return {data:{capture:{version:7},images:[{}]}};
      return {data:[]};}
  });
  return {nodes,calls,button:label=>nodes.find(n=>n.textContent===label)};
}
test('design creation pins selected version and retries identical request key',async()=>{
  const s=setup();await s.button('搜索商品').handlers.click();await s.button('商品1 · SHOPEE').handlers.click();
  const areas=s.nodes.filter(n=>n.tag==='textarea');areas[0].value='只改变背景';areas[1].value='授权记录';
  const submit=s.nodes.find(n=>n.tag==='form').handlers.submit;
  await submit({preventDefault(){}});await submit({preventDefault(){}});
  const writes=s.calls.filter(c=>c.options);assert.equal(writes.length,2);assert.equal(writes[0].options.body,writes[1].options.body);assert.equal(writes[0].options.headers['Idempotency-Key'],writes[1].options.headers['Idempotency-Key']);
  const body=JSON.parse(writes[0].options.body);assert.equal(body.captureVersion,7);assert.equal(body.captureId,'p1');assert.equal(body.variants,10);
  assert.ok(!s.calls.some(c=>c.url.includes('publish')||c.url.includes('generate')));
});
test('without selected source no draft is submitted',async()=>{
  const s=setup();await s.nodes.find(n=>n.tag==='form').handlers.submit({preventDefault(){}});assert.equal(s.calls.length,0);
});
