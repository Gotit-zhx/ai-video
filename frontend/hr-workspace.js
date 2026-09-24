/* Recruiter data stays behind the platform-admin API; no browser CDP or Cookie access. */
(() => {
  const root = document.getElementById('hr-page');
  if (!root) return;
  const routes = new Set(['overview', 'jobs', 'talent', 'applications', 'chats', 'resume']);
  const labels = {overview:'招聘工作台', jobs:'职位管理', talent:'人才寻源',
    applications:'投递申请', chats:'沟通记录', resume:'在线简历'};
  const panels = new Map();
  let current = 'overview', sequence = 0, page = 1, talentAction = 'candidates';
  let loginTimer = null, qrRefresh = null, autoLoginAttempted = false;
  let loginPendingSeen = false, loginCheckInFlight = false, qrRequestInFlight = false;
  let resumeTimer = null;

  const element = (tag, text, parent, className) => {
    const node = document.createElement(tag);
    if (text != null) node.textContent = text;
    if (className) node.className = className;
    if (parent) parent.append(node);
    return node;
  };
  const button = (text, parent, handler) => {
    const node = element('button', text, parent);
    node.type = 'button';
    node.addEventListener('click', handler);
    return node;
  };
  const allowed = () => currentUser?.platformAdmin === true
    && currentUser?.username === '13800000000';
  const section = (key) => {
    const panel = element('section', '', root, 'hr-panel');
    panel.hidden = key !== 'overview';
    panels.set(key, panel);
    return panel;
  };
  const field = (label, parent, placeholder = '') => {
    const wrapper = element('label', '', parent, 'hr-field');
    element('span', label, wrapper);
    const input = element('input', '', wrapper);
    input.placeholder = placeholder;
    return input;
  };
  const heading = element('header', '', root, 'hr-heading');
  const titleBox = element('div', '', heading);
  element('p', 'HOYEE  /  TALENT STUDIO', titleBox);
  const title = element('h1', '招聘工作台', titleBox);
  element('span', '从职位到人才，把每一步招聘进展放在同一个工作台。', titleBox, 'hr-subtitle');
  const refresh = button('刷新当前数据', heading, () => run());
  refresh.className = 'hr-primary';
  const note = element('p', 'BOSS 直聘连接状态正在检查…', root, 'hr-note');
  note.setAttribute('role', 'status');

  const overview = section('overview');
  const intro = element('div', '', overview, 'hr-overview-intro');
  element('span', '●  安全连接', intro, 'hr-eyebrow');
  element('h2', '连接你的招聘账号', intro);
  element('p', '使用 BOSS 直聘 App 扫码，确认后即可查看真实职位、投递和候选人数据。', intro, 'hr-hint');
  const loginBox = element('div', '', overview, 'hr-login');
  element('div', 'BOSS 直聘', loginBox, 'hr-login-brand');
  element('strong', '扫码连接招聘账号', loginBox, 'hr-login-title');
  const qr = element('img', '', loginBox, 'hr-qr');
  qr.alt = 'BOSS 直聘登录二维码';
  qr.hidden = true;
  const loginMessage = element('p', '正在准备专属二维码…', loginBox, 'hr-hint');
  const loginActions = element('div', '', loginBox, 'hr-login-actions');
  const loginButton = button('重新获取二维码', loginActions, startLogin);
  loginButton.hidden = true;
  const refreshButton = button('刷新二维码', loginActions, refreshLogin);
  refreshButton.hidden = true;
  const resetLoginButton = button('清除会话并重新扫码', loginActions, resetLogin);
  resetLoginButton.hidden = true;
  const status = element('div', '正在读取账号状态…', overview, 'hr-result hr-status-result');
  const logoutButton = button('退出 BOSS 登录', overview, logoutBoss);
  logoutButton.className = 'hr-logout';
  logoutButton.hidden = true;
  element('p', '二维码仅供当前管理员使用；扫码后还需在手机上确认。', overview, 'hr-footnote');

  function stopLoginPolling() {
    clearInterval(loginTimer);
    clearInterval(qrRefresh);
    loginTimer = qrRefresh = null;
  }
  async function refreshQr() {
    if (qrRequestInFlight) return;
    qrRequestInFlight = true;
    try {
      const response = await fetch('/api/v1/hr/login/qr', {credentials:'same-origin',cache:'no-store'});
      if (response.status === 202) {
        const result = await response.json();
        if (result.data?.phase === 'scanned' || result.data?.phase === 'verifying') {
          qr.hidden = true;
          loginMessage.textContent = result.data.phase === 'scanned'
            ? '已扫码，请在手机上确认。' : '扫码已确认，正在验证招聘方账号…';
        }
        return;
      }
      if (!response.ok) return;
      const blob = await response.blob();
      if (blob.type !== 'image/png') return;
      const url = URL.createObjectURL(blob);
      const previous = qr.dataset.objectUrl;
      qr.src = url;
      qr.dataset.objectUrl = url;
      qr.hidden = false;
      if (previous) URL.revokeObjectURL(previous);
    } catch (_) {
      loginMessage.textContent = '正在等待登录二维码，请稍后…';
    } finally {
      qrRequestInFlight = false;
    }
  }
  async function refreshLogin() {
    if (!allowed()) return;
    refreshButton.disabled = true;
    loginMessage.textContent = '正在生成新的二维码…';
    try {
      await api('/api/v1/hr/login/refresh', {method:'POST'});
      qr.hidden = true;
      await refreshQr();
      loginMessage.textContent = '新二维码已生成，请使用 BOSS 直聘 App 扫码确认。';
    } catch (error) {
      loginMessage.textContent = error.message || '二维码刷新失败';
    } finally {
      refreshButton.disabled = false;
    }
  }
  async function resetLogin() {
    if (!allowed()) return;
    resetLoginButton.disabled = true;
    try {
      await api('/api/v1/hr/login/logout', {method:'POST'});
      resetLoginButton.hidden = true;
      loginButton.textContent = '重新获取二维码';
      await startLogin();
    } catch (error) {
      loginMessage.textContent = error.message || '清除会话失败';
    } finally {
      resetLoginButton.disabled = false;
    }
  }
  async function checkLogin() {
    if (!allowed()) { stopLoginPolling(); return; }
    if (loginCheckInFlight) return;
    loginCheckInFlight = true;
    try {
      const result = await api('/api/v1/hr/login/status');
      const state = result.data || {};
      if (state.state === 'pending') {
        loginPendingSeen = true;
        loginBox.hidden = false;
        loginButton.hidden = true;
        const waitingForPhone = state.phase === 'scanned' || state.phase === 'verifying';
        refreshButton.hidden = waitingForPhone;
        loginMessage.textContent = state.phase === 'scanned' ? '已扫码，请在手机上确认。'
          : state.phase === 'verifying' ? '扫码已确认，正在验证招聘方账号…'
          : '请在 BOSS 直聘 App 扫码并确认；二维码过期后会自动更新。';
        if (waitingForPhone) {
          qr.hidden = true;
          clearInterval(qrRefresh);
          qrRefresh = null;
        } else {
          if (!qrRefresh) qrRefresh = setInterval(refreshQr, 12000);
          if (qr.hidden) refreshQr();
        }
      } else {
        stopLoginPolling();
        loginBox.hidden = state.state === 'complete';
        qr.hidden = true;
        refreshButton.hidden = true;
        loginButton.hidden = state.state === 'complete';
        resetLoginButton.hidden = state.state !== 'verification_pending';
        loginButton.textContent = state.state === 'verification_pending'
          ? '重试招聘方验证' : '重新获取二维码';
        loginButton.disabled = false;
        loginMessage.textContent = state.state === 'complete' ? '扫码成功，正在刷新账号状态…'
          : state.state === 'verification_pending' ? (state.error || '已扫码，正在核对招聘方接口')
          : state.state === 'failed' ? (state.error || '扫码未完成，请重新获取二维码') : '';
        if (state.state === 'complete') {
          if (loginPendingSeen) {
            loginPendingSeen = false;
            history.replaceState(null, '', `${location.pathname}${location.search}#hr-jobs`);
            enter('jobs');
          } else {
            run('overview');
          }
        } else {
          loginPendingSeen = false;
        }
      }
    } catch (error) {
      stopLoginPolling();
      loginButton.disabled = false;
      loginMessage.textContent = error.message || '登录状态读取失败';
    } finally {
      loginCheckInFlight = false;
    }
  }
  async function startLogin() {
    if (!allowed()) return;
    autoLoginAttempted = true;
    loginPendingSeen = true;
    loginButton.disabled = true;
    loginButton.hidden = true;
    loginBox.hidden = false;
    loginMessage.textContent = loginButton.textContent.includes('验证')
      ? '正在重试招聘方身份验证…' : '正在打开 BOSS 登录页…';
    try {
      await api('/api/v1/hr/login/start', {method:'POST'});
      stopLoginPolling();
      loginTimer = setInterval(checkLogin, 3000);
      await checkLogin();
    } catch (error) {
      loginButton.disabled = false;
      loginButton.hidden = false;
      loginMessage.textContent = error.message || '启动扫码失败';
    }
  }
  async function logoutBoss() {
    if (!allowed()) return;
    logoutButton.disabled = true;
    try {
      await api('/api/v1/hr/login/logout', {method:'POST'});
      autoLoginAttempted = true;
      stopLoginPolling();
      qr.hidden = true;
      loginBox.hidden = false;
      loginButton.hidden = false;
      resetLoginButton.hidden = true;
      refreshButton.hidden = true;
      logoutButton.hidden = true;
      loginMessage.textContent = '已退出招聘账号，服务器浏览器资源已释放。需要时可重新获取二维码。';
      await run('overview');
    } catch (error) {
      loginMessage.textContent = error.message || '退出失败，请稍后重试';
    } finally {
      logoutButton.disabled = false;
    }
  }

  const jobs = section('jobs');
  element('p', '读取招聘方职位列表。平台未返回的数据不会显示为已同步。', jobs, 'hr-hint');
  const jobsResult = element('div', '', jobs, 'hr-result');

  const talent = section('talent');
  const talentForm = element('form', '', talent, 'hr-form');
  const keyword = field('搜索关键词', talentForm, '例如：AI 产品经理');
  const talentJob = field('职位加密 ID（推荐人才时必填）', talentForm, '从职位列表获取');
  const talentActions = element('div', '', talentForm, 'hr-actions');
  button('搜索人才', talentActions, () => run('candidates')).className = 'hr-primary';
  button('按职位看推荐', talentActions, () => run('recommendations'));
  talentForm.addEventListener('submit', event => {event.preventDefault(); run('candidates');});
  element('p', '根据 JD 先确定关键词与对应职位，再查看平台的真实候选人。发送招呼需另行审核具体人选和原文。', talent, 'hr-hint');
  const talentResult = element('div', '', talent, 'hr-result');

  const applications = section('applications');
  const applicationsForm = element('form', '', applications, 'hr-form');
  const applicationsJob = field('职位加密 ID（可选）', applicationsForm, '留空查看全部');
  const applicationsActions = element('div', '', applicationsForm, 'hr-actions');
  button('查询投递', applicationsActions, () => {page=1;run();}).className = 'hr-primary';
  applicationsForm.addEventListener('submit', event => {event.preventDefault();page=1;run();});
  const applicationsResult = element('div', '', applications, 'hr-result');

  const chats = section('chats');
  const chatsForm = element('form', '', chats, 'hr-form');
  const chatsJob = field('职位加密 ID（可选）', chatsForm, '留空查看全部沟通');
  const friendId = field('会话 friend ID（查看消息时填写）', chatsForm, '仅数字');
  const chatsActions = element('div', '', chatsForm, 'hr-actions');
  button('查看沟通列表', chatsActions, () => {page=1;run('chats');}).className = 'hr-primary';
  button('查看指定会话消息', chatsActions, () => run('messages'));
  button('向该候选人索取附件简历', chatsActions, requestAttachmentResume);
  chatsForm.addEventListener('submit', event => {event.preventDefault();run('chats');});
  const chatsResult = element('div', '', chats, 'hr-result');
  element('h2', '附件简历跟进', chats);
  element('p', '请求发出后会持续检查该会话；候选人晚些时候发送附件也会显示在这里。', chats, 'hr-hint');
  const attachmentResult = element('div', '', chats, 'hr-result');

  async function requestAttachmentResume() {
    const id = friendId.value.trim();
    if (!/^[1-9]\d{0,19}$/.test(id)) {
      element('p', '请先填写数字格式的候选人会话 friend ID。', chatsResult, 'hr-error');
      return;
    }
    if (!window.confirm(`确认向会话 ${id} 的候选人发送附件简历请求吗？平台会实际发送一条请求。`)) return;
    try {
      await api('/api/v1/hr/resumes/request', {
        method:'POST', headers:{'Content-Type':'application/json'},
        body:JSON.stringify({friendId:id})
      });
      chatsResult.replaceChildren();
      element('p', `已向会话 ${id} 发起索取请求。等待候选人发送附件，系统会持续检查。`, chatsResult);
      await loadTrackedResumes();
    } catch (error) {
      chatsResult.replaceChildren();
      element('p', error.message || '索取结果未确认，请查看官方会话。', chatsResult, 'hr-error');
      await loadTrackedResumes();
    }
  }

  async function downloadAttachment(friend, mid, action) {
    action.disabled = true;
    try {
      const response = await fetch(`/api/v1/hr/resumes/${friend}/${mid}/download`,
        {credentials:'same-origin',cache:'no-store'});
      if (!response.ok) {
        const error = await response.json().catch(() => ({}));
        if (response.status === 401) handleSessionExpired();
        throw Error(error.message || '附件获取失败');
      }
      const blob = await response.blob();
      const disposition = response.headers.get('Content-Disposition') || '';
      const name = /filename="(resume-[\d-]+\.(?:pdf|docx|doc|png|jpg))"/i.exec(disposition)?.[1]
        || `resume-${friend}-${mid}`;
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = name;
      document.body.append(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) {
      element('p', error.message || '附件获取失败', attachmentResult, 'hr-error');
    } finally {
      action.disabled = false;
    }
  }

  async function loadTrackedResumes() {
    if (!allowed() || activeWorkspace !== 'hr') return;
    try {
      const result = await api('/api/v1/hr/resumes');
      if (current !== 'chats') return;
      attachmentResult.replaceChildren();
      const items = result.data || [];
      if (!items.length) {
        element('p', '暂无附件简历请求。', attachmentResult, 'hr-empty');
        return;
      }
      for (const item of items) {
        const card = element('article', '', attachmentResult, 'hr-record');
        element('strong', `会话 ${item.friend_id}`, card);
        const state = item.state === 'received' ? '已收到附件'
          : item.state === 'uncertain' ? '索取结果待核对'
          : '等待候选人发送';
        element('p', `${state} · ${new Date(item.updated_at * 1000).toLocaleString()}`, card);
        if (item.error) element('p', item.error, card, 'hr-hint');
        if (item.state === 'received' && /^\d+$/.test(item.message_id || '')) {
          button('下载已收到的简历', card,
            event => downloadAttachment(item.friend_id, item.message_id, event.currentTarget))
            .className = 'hr-primary hr-resume-download';
        }
      }
    } catch (error) {
      if (current === 'chats') {
        attachmentResult.replaceChildren();
        element('p', error.message || '附件状态读取失败', attachmentResult, 'hr-error');
      }
    }
  }

  const resume = section('resume');
  const resumeForm = element('form', '', resume, 'hr-form');
  const geekId = field('候选人加密 ID', resumeForm);
  const resumeJob = field('职位加密 ID', resumeForm);
  const securityId = field('安全 ID（如果平台返回）', resumeForm);
  button('查看在线简历', resumeForm, () => run()).className = 'hr-primary';
  resumeForm.addEventListener('submit', event => {event.preventDefault();run();});
  element('p', '在线简历按平台权限读取；本页面不下载附件或请求联系方式。', resume, 'hr-hint');
  const resumeResult = element('div', '', resume, 'hr-result');

  const resultBoxes = {overview:status,jobs:jobsResult,talent:talentResult,
    applications:applicationsResult,chats:chatsResult,resume:resumeResult};
  const only = value => String(value || '').trim();
  const params = data => new URLSearchParams(Object.entries(data)
    .filter(([,value]) => value !== '' && value != null)).toString();
  function findList(value, depth = 0) {
    if (Array.isArray(value)) return value;
    if (!value || typeof value !== 'object' || depth > 3) return null;
    for (const key of ['items','list','records','jobs','candidates','applications',
      'conversations','chats','messages','geeks','data','result']) {
      const nested = findList(value[key], depth + 1);
      if (nested) return nested;
    }
    return null;
  }
  function display(box, envelope) {
    box.replaceChildren();
    if (!envelope?.ok) {
      const error = envelope?.error || {};
      const missing = ['AUTH_REQUIRED','AUTH_EXPIRED'].includes(error.code);
      element('p', missing ? 'BOSS 账号尚未登录或登录已过期。请先在服务器专用浏览器扫码。'
        : (error.message || '平台读取失败，请查看账号状态。'), box, 'hr-error');
      if (error.code) element('small', `错误代码：${error.code}`, box);
      return;
    }
    const data = envelope.data;
    const rows = findList(data);
    if (rows) {
      element('p', `本次返回 ${rows.length} 条 · 来源：BOSS 连接器`, box, 'hr-count');
      if (!rows.length) element('p', '平台当前没有返回记录。', box, 'hr-empty');
      rows.slice(0, 30).forEach((row, index) => {
        const card = element('article', '', box, 'hr-record');
        if (row && typeof row === 'object') {
          const name = row.name || row.title || row.jobName || row.geekName
            || row.expectPosition || row.positionName || row.nickname || `记录 ${index + 1}`;
          element('strong', String(name), card);
          const detail = element('details', '', card);
          element('summary', '查看平台返回字段', detail);
          element('pre', JSON.stringify(row, null, 2), detail);
        } else {
          element('p', String(row), card);
        }
      });
      if (rows.length > 30) element('p', '当前只展示前 30 条，请缩小条件或翻页。', box);
    } else if (data != null) {
      const detail = element('details', '', box);
      detail.open = true;
      element('summary', '平台返回数据', detail);
      element('pre', JSON.stringify(data, null, 2), detail);
    } else {
      element('p', '平台没有返回内容。', box, 'hr-empty');
    }
  }
  function pagination(box, route) {
    if (!['applications','chats','talent'].includes(route)) return;
    const controls = element('div', '', box, 'hr-pagination');
    const previous = button('上一页', controls, () => {page--;run();});
    previous.disabled = page <= 1;
    element('span', `第 ${page} 页`, controls);
    const next = button('下一页', controls, () => {page++;run();});
    next.disabled = page >= 20;
  }
  async function run(action = current) {
    if (!allowed()) return;
    if (action === 'talent') action = talentAction;
    if (action === 'candidates' || action === 'recommendations') talentAction = action;
    const box = resultBoxes[current], ticket = ++sequence;
    box.replaceChildren();
    element('p', '正在读取平台数据…', box);
    let path;
    try {
      if (action === 'overview') path = '/api/v1/hr/status';
      else if (action === 'jobs') path = '/api/v1/hr/jobs';
      else if (action === 'applications') path = '/api/v1/hr/applications?' + params({page, jobId:only(applicationsJob.value)});
      else if (action === 'candidates') path = '/api/v1/hr/candidates?' + params({page, keyword:only(keyword.value), jobId:only(talentJob.value)});
      else if (action === 'recommendations') {
        if (!only(talentJob.value)) throw Error('请先填入职位加密 ID');
        path = '/api/v1/hr/recommendations?' + params({page, jobId:only(talentJob.value)});
      } else if (action === 'chats') path = '/api/v1/hr/chats?' + params({page, jobId:only(chatsJob.value)});
      else if (action === 'messages') {
        if (!/^\d{1,20}$/.test(only(friendId.value))) throw Error('请填写数字格式的会话 friend ID');
        path = '/api/v1/hr/messages/' + encodeURIComponent(only(friendId.value));
      } else if (action === 'resume') {
        if (!geekId.value.trim() || !resumeJob.value.trim()) throw Error('请填写候选人 ID 和职位 ID');
        path = '/api/v1/hr/resume?' + params({geekId:only(geekId.value),jobId:only(resumeJob.value),securityId:only(securityId.value)});
      } else return;
      const response = await api(path);
      if (ticket !== sequence || activeWorkspace !== 'hr') return;
      display(box, response.data);
      if (action === 'overview') {
        // The connector only returns a successful status after its recruiter
        // endpoint check; a missing secondary token alone is not a role failure.
        const recruiterReady = response.data?.ok === true;
        logoutButton.hidden = !recruiterReady;
        if (recruiterReady) {
          loginBox.hidden = true;
          note.textContent = '招聘方账号已连接 · 平台只读';
        }
      }
      if (action === 'overview'
          && ['AUTH_REQUIRED','AUTH_EXPIRED','RECRUITER_REQUIRED'].includes(response.data?.error?.code)
          && !autoLoginAttempted) {
        startLogin();
      }
      pagination(box, current);
      if (action !== 'overview' || logoutButton.hidden) {
        note.textContent = `最近读取：${new Date().toLocaleTimeString()} · 平台只读`;
      }
    } catch (error) {
      if (ticket !== sequence) return;
      box.replaceChildren();
      element('p', error.message || '读取失败', box, 'hr-error');
    }
  }
  function enter(route) {
    if (!allowed()) return;
    current = routes.has(route) ? route : 'overview';
    clearInterval(resumeTimer);
    resumeTimer = null;
    page = 1;
    ++sequence;
    panels.forEach((panel, key) => panel.hidden = key !== current);
    title.textContent = labels[current];
    document.querySelectorAll('[data-hr-route]').forEach(link => {
      link.classList.toggle('active', link.dataset.hrRoute === current);
    });
    if (current === 'overview') {
      run();
      checkLogin();
    } else if (current === 'jobs' || current === 'applications' || current === 'chats') {
      run();
      if (current === 'chats') {
        loadTrackedResumes();
        resumeTimer = setInterval(loadTrackedResumes, 30000);
      }
    }
  }
  document.querySelectorAll('[data-hr-route]').forEach(link => link.addEventListener('click', event => {
    event.preventDefault();
    if (!allowed()) return;
    history.pushState(null, '', `${location.pathname}${location.search}#hr-${link.dataset.hrRoute}`);
    switchWorkspace('hr');
    enter(link.dataset.hrRoute);
  }));
  globalThis.HrWorkspace = {enter, allowed, reset(){
    ++sequence;
    stopLoginPolling();
    clearInterval(resumeTimer);
    resumeTimer = null;
    autoLoginAttempted = false;
    loginPendingSeen = false;
    qr.hidden = true;
    logoutButton.hidden = true;
    if (qr.dataset.objectUrl) URL.revokeObjectURL(qr.dataset.objectUrl);
    delete qr.dataset.objectUrl;
    root.querySelectorAll('.hr-result').forEach(box => box.replaceChildren());
  }};
  window.addEventListener('heyi:company-reset', () => HrWorkspace.reset());
})();
