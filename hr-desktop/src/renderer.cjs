const $=selector=>document.querySelector(selector);
const labels={LOGIN_OPENED:'已打开独立 BOSS 浏览器',LOGIN_VERIFICATION:'正在核对招聘方身份',
  CONNECTED:'招聘账号已连接',DISCONNECTED:'本机连接已断开',USER_PAUSED:'已由用户暂停任务',
  BACKGROUND_PAUSED:'后台检查已暂停',BROWSER_RELEASED:'已释放浏览器资源，登录资料保留',
  COMPUTER_SLEEP:'电脑休眠，已暂停任务',RECONNECT_REQUIRED:'电脑已恢复，请手动核对连接',
  PC_LOGIN_RESTRICTED:'官方限制电脑端登录，已停止',LOGIN_TIMEOUT:'扫码等待结束，未自动重试',
  LOCAL_LOGIN_ERROR:'本机连接未完成，页面已保留'};
function render(state){
  $('main').hidden=state.active!=='home';
  document.querySelectorAll('nav button').forEach(button=>button.classList.toggle('selected',button.dataset.action===state.active));
  if(state.version)$('#version').textContent=state.version;
  if(state.platform)$('#platform').textContent=state.platform==='darwin'?'Mac 本机执行':'Windows 本机执行';
  const ready=state.state==='complete'&&!state.paused;
  $('#badge').textContent=ready?'已连接':state.state==='pending'?'连接中':state.paused?'已暂停':'未连接';
  $('#state-title').textContent=ready?'招聘账号已就绪':state.state==='pending'?'请在 BOSS 页面完成登录':state.paused?'当前任务保持暂停':'等待连接招聘账号';
  $('#state-description').textContent=state.error||(ready?'招聘请求由这台电脑执行。关闭主窗口后可驻留托盘，暂停或退出始终可用。':'先进入工作台登录赫逸账号，再由本人扫码连接 BOSS。应用不会自动发起登录。');
  $('#footer-status').textContent=state.busy?'本机任务执行中 · 不会并发发送':state.paused?'本机任务已暂停 · 没有自动重试':'本机执行 · 登录凭据不上传服务器';
  if(state.events?.length){
    $('#events').replaceChildren(...state.events.slice(-4).reverse().map(event=>{
      const item=document.createElement('li'),dot=document.createElement('span'),text=document.createElement('span'),time=document.createElement('time');
      dot.className='event-dot';text.textContent=labels[event.code]||'操作已暂停，请查看连接状态';
      time.textContent=new Date(event.at).toLocaleTimeString('zh-CN',{hour12:false});
      item.append(dot,text,time);return item;
    }));
  }
}
document.querySelectorAll('[data-action]').forEach(button=>button.addEventListener('click',async()=>{
  button.disabled=true;$('#notice').hidden=true;
  try{render(await desktop.action(button.dataset.action));}
  catch(error){$('#notice').textContent=error.message.replace(/^Error invoking remote method '[^']+': Error: /,'');$('#notice').hidden=false;}
  finally{button.disabled=false;}
}));
desktop.onState(render);
desktop.action('state').then(render);
