// Run with JSDOM_MODULE pointing at an installed jsdom module, or npm install jsdom.
import fs from 'node:fs/promises';
import assert from 'node:assert/strict';
const {JSDOM}=await import(process.env.JSDOM_MODULE||'jsdom');
const dom=new JSDOM('<!doctype html><main id="view"></main>',{runScripts:'outside-only',pretendToBeVisual:true});
const w=dom.window,d=w.document,root=d.querySelector('main');
w.eval(await fs.readFile(new URL('../frontend/incremental-dom.js',import.meta.url),'utf8'));
const markup=(amount='10',extra='')=>`<section data-update-key="one"><details open><summary>资料</summary><textarea>已保存</textarea></details><span>${amount}</span><video src="/video.mp4"></video><img src="/image.png"><button>操作</button></section>${extra}<section data-update-key="two">第二条</section>`;
w.IncrementalDOM.html(root,markup());
const section=root.firstChild,details=root.querySelector('details'),input=root.querySelector('textarea'),video=root.querySelector('video'),img=root.querySelector('img');
details.open=false;input.value='尚未保存的输入';input.focus();input.setSelectionRange(2,4);root.scrollTop=80;
video.currentTime=12;
const mutations=[];const observer=new w.MutationObserver(records=>mutations.push(...records));observer.observe(root,{subtree:true,childList:true,attributes:true,characterData:true});
w.IncrementalDOM.html(root,markup());await Promise.resolve();
assert.equal(mutations.length,0,'unchanged response must not mutate the live tree');
w.IncrementalDOM.html(root,markup('25','<section data-update-key="three">新记录</section>'));
assert.equal(root.firstChild,section);assert.equal(root.querySelector('video'),video);assert.equal(video.currentTime,12);assert.equal(root.querySelector('img'),img);
assert.equal(d.activeElement,input);assert.equal(input.value,'尚未保存的输入');assert.equal(input.selectionStart,2);assert.equal(input.selectionEnd,4);
assert.equal(details.open,false);assert.equal(root.scrollTop,80);assert.equal(root.querySelector('span').textContent,'25');
const three=root.querySelector('[data-update-key="three"]'),two=root.querySelector('[data-update-key="two"]');
w.IncrementalDOM.html(root,'<section data-update-key="two">第二条已更新</section><section data-update-key="three">新记录</section>');
assert.equal(root.firstChild,two);assert.equal(root.lastChild,three);assert(!section.isConnected);
// Programmatic row handlers must use the newest version and the retained button.
const holder=d.createElement('div');root.replaceChildren(holder);let clicked;
function desired(version){
  const n=d.createElement('div'),b=d.createElement('button');b.textContent='编辑';
  b.onclick=event=>clicked={version,node:event.currentTarget};n.append(b);return n;
}
w.IncrementalDOM.children(holder,desired(1));const button=holder.firstChild;
w.IncrementalDOM.children(holder,desired(2));button.click();
assert.equal(holder.firstChild,button);assert.equal(clicked.version,2);assert.equal(clicked.node,button);
// Selection survives option updates; new options are still available.
root.innerHTML='<select><option value="a">A</option><option value="b">B</option></select>';
root.firstChild.value='b';
const next=d.createElement('div');next.innerHTML='<select><option value="a">A</option><option value="b">B</option><option value="c">C</option></select>';next.firstChild.value='b';
w.IncrementalDOM.children(root,next);assert.equal(root.firstChild.value,'b');assert.equal(root.firstChild.options.length,3);
observer.disconnect();dom.window.close();
console.log('Incremental DOM: unchanged responses, keyed updates, media, drafts, focus, selection, scroll and current handlers passed.');
