const orb=document.querySelector('#orb');
const status=document.querySelector('#status');
function render(state){
  document.body.classList.toggle('working',state.busy===true);
  document.body.classList.toggle('connected',state.state==='complete'&&!state.paused);
  const label=state.busy?'正在执行':state.loading?'正在打开 BOSS':state.pageError?'页面未加载，右键可重试':
    state.state==='pending'?(state.phase==='verifying'?'正在确认登录':'等待扫码确认'):
    state.paused?'已暂停':state.state==='complete'?'已连接':'准备就绪';
  if(status){status.textContent=label;status.title=state.pageError||state.error||label;}
  if(orb){
    const action=state.expanded?'收起 BOSS 页面':'打开 BOSS 页面';
    orb.setAttribute('aria-label',action);
    orb.title=`${action} · ${label}\n拖动调整位置 · 右键更多`;
  }
}
async function act(action){
  try {const result=await desktop.action(action);if(result?.state)render(result);return result;}
  catch(error){
    const message=error.message.replace(/^Error invoking remote method '[^']+': Error: /,'');
    if(status){status.textContent=message;status.title=message;}
    if(orb)orb.title=message;
  }
}
document.querySelectorAll('[data-action]').forEach(button=>button.addEventListener('click',()=>act(button.dataset.action)));
document.addEventListener('contextmenu',event=>{event.preventDefault();act('menu');});
if(orb){
  let pointer=null;
  orb.addEventListener('pointerdown',event=>{
    if(event.button!==0)return;
    pointer=event.pointerId;orb.setPointerCapture(pointer);document.body.classList.add('dragging');
    act('drag-start');
  });
  const end=async(event,cancelled)=>{
    if(pointer!==event.pointerId)return;
    pointer=null;document.body.classList.remove('dragging');
    if(orb.hasPointerCapture(event.pointerId))orb.releasePointerCapture(event.pointerId);
    const result=await act('drag-end');
    if(!cancelled&&result&&!result.moved)await act('toggle');
  };
  orb.addEventListener('pointerup',event=>end(event,false));
  orb.addEventListener('pointercancel',event=>end(event,true));
  orb.addEventListener('lostpointercapture',event=>end(event,true));
  orb.addEventListener('click',event=>{if(event.detail===0)act('toggle');});
}
desktop.onState(render);
act('state');
