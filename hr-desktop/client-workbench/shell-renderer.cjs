for (const button of document.querySelectorAll('[data-action]')) {
  button.addEventListener('click', () => window.heyiShell.action(button.dataset.action));
}
window.heyiShell.onState(state => {
  const network = document.getElementById('network');
  network.dataset.state = state.networkState;
  document.getElementById('network-label').textContent =
    state.networkState === 'ready' ? '网卡直连'
      : state.networkState === 'stopped' ? '直连已断开' : '正在连接网卡';
  document.getElementById('account').textContent =
    state.accountState === 'connected' ? 'BOSS 已连接'
      : state.accountState === 'paused' ? 'BOSS 会话待确认' : '等待扫码登录';
  for (const button of document.querySelectorAll('nav button')) {
    button.setAttribute('aria-selected', String(button.dataset.action === state.mode));
  }
});
if (location.hash === '#sidebar') document.body.classList.add('sidebar');
