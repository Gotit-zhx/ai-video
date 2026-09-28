/* Recruiter data stays behind the platform-admin API; no browser CDP or Cookie access. */
(() => {
  const root = document.getElementById('hr-page');
  if (!root) return;
  const routes = new Set(['overview', 'jobs', 'talent', 'applications', 'chats', 'resume']);
  const panels = new Map();
  let current = 'overview', sequence = 0, page = 1, talentAction = 'candidates';
  let loginTimer = null, qrRefresh = null, autoLoginAttempted = false;
  let loginPendingSeen = false, loginCheckInFlight = false, qrRequestInFlight = false;
  let loginActionInFlight = false, loginGeneration = 0;
  let resumeTimer = null;
  let jobRecords = [], jobsRequest = null, conversations = [], selectedConversation = null;
  let selectedCandidate = null, sessionGeneration = 0;
  const jobSelectors = [];

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
  const jobField = (label, parent) => {
    const wrapper = element('label', '', parent, 'hr-field');
    element('span', label, wrapper);
    const select = element('select', '', wrapper);
    const option = element('option', '全部职位', select);
    option.value = '';
    jobSelectors.push(select);
    return select;
  };
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
  const resetLoginButton = button('清除会话并重新扫码', loginActions, () => logoutBoss(true));
  resetLoginButton.hidden = true;
  const status = element('div', '正在读取账号状态…', overview, 'hr-result hr-status-result');
  const logoutButton = button('断开服务器连接', overview, logoutBoss);
  logoutButton.className = 'hr-logout';
  logoutButton.hidden = true;
  const loginFootnote = element('p', '二维码仅供当前管理员使用；扫码后还需在手机上确认。', overview, 'hr-footnote');

  function stopLoginPolling() {
    clearInterval(loginTimer);
    clearInterval(qrRefresh);
    loginTimer = qrRefresh = null;
  }
  async function refreshQr() {
    if (qrRequestInFlight) return;
    qrRequestInFlight = true;
    const generation = loginGeneration;
    try {
      const response = await fetch('/api/v1/hr/login/qr', {credentials:'same-origin',cache:'no-store'});
      if (generation !== loginGeneration || !allowed()) return;
      if (response.status === 202) {
        const result = await response.json();
        if (generation !== loginGeneration) return;
        if (result.data?.phase === 'scanned' || result.data?.phase === 'verifying') {
          qr.hidden = true;
          loginMessage.textContent = result.data.phase === 'scanned'
            ? '已扫码，请在手机上确认。' : '扫码已确认，正在验证招聘方账号…';
        }
        return;
      }
      if (!response.ok) return;
      const blob = await response.blob();
      if (generation !== loginGeneration) return;
      if (blob.type !== 'image/png') return;
      const url = URL.createObjectURL(blob);
      const previous = qr.dataset.objectUrl;
      qr.src = url;
      qr.dataset.objectUrl = url;
      qr.hidden = false;
      if (previous) URL.revokeObjectURL(previous);
    } catch (_) {
      if (generation === loginGeneration) loginMessage.textContent = '正在等待登录二维码，请稍后…';
    } finally {
      qrRequestInFlight = false;
    }
  }
  async function refreshLogin() {
    if (!allowed() || loginActionInFlight) return;
    const generation = loginGeneration;
    refreshButton.disabled = true;
    loginMessage.textContent = '正在生成新的二维码…';
    try {
      await api('/api/v1/hr/login/refresh', {method:'POST'});
      if (generation !== loginGeneration) return;
      qr.hidden = true;
      await refreshQr();
      if (generation !== loginGeneration) return;
      loginMessage.textContent = '新二维码已生成，请使用 BOSS 直聘 App 扫码确认。';
    } catch (error) {
      if (generation !== loginGeneration) return;
      loginMessage.textContent = error.message || '二维码刷新失败';
    } finally {
      if (generation === loginGeneration) refreshButton.disabled = false;
    }
  }
  function showDisconnected() {
    autoLoginAttempted = true;
    loginPendingSeen = false;
    stopLoginPolling();
    intro.hidden = loginFootnote.hidden = loginBox.hidden = false;
    qr.hidden = refreshButton.hidden = resetLoginButton.hidden = logoutButton.hidden = true;
    loginButton.hidden = false;
    loginButton.textContent = '扫码连接招聘账号';
    loginMessage.textContent = '服务器连接已断开。需要时点击“扫码连接招聘账号”，再用手机确认。';
    note.textContent = '已断开服务器连接';
    status.replaceChildren();
  }
  async function checkLogin() {
    if (!allowed()) { stopLoginPolling(); return; }
    if (loginCheckInFlight) return;
    loginCheckInFlight = true;
    const generation = loginGeneration;
    try {
      const result = await api('/api/v1/hr/login/status');
      if (generation !== loginGeneration || !allowed()) return;
      const state = result.data || {};
      if (state.state === 'disconnected') {showDisconnected();return;}
      if (state.state === 'pending') {
        // A page reload can join an existing server scan. It still needs to
        // poll completion even though this page never called /login/start.
        if (!loginTimer) loginTimer = setInterval(checkLogin, 3000);
        loginPendingSeen = true;
        status.replaceChildren();
        note.textContent = state.phase === 'scanned' ? '扫码已收到 · 等待手机确认'
          : state.phase === 'verifying' ? '正在验证招聘方账号' : '等待扫码连接';
        loginBox.hidden = false;
        loginButton.hidden = true;
        resetLoginButton.hidden = true;
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
          ? '同步浏览器登录并验证' : '重新获取二维码';
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
      if (generation !== loginGeneration) return;
      // A transient request failure must not permanently stop an active scan.
      if (loginPendingSeen && !loginTimer) loginTimer = setInterval(checkLogin, 3000);
      loginButton.disabled = false;
      loginMessage.textContent = loginPendingSeen ? '连接短暂中断，正在重新读取登录状态…'
        : error.message || '登录状态读取失败';
    } finally {
      loginCheckInFlight = false;
    }
  }
  async function startLogin() {
    if (!allowed() || loginActionInFlight) return;
    loginActionInFlight = true;
    const generation = ++loginGeneration;
    ++sequence;
    status.replaceChildren();
    autoLoginAttempted = true;
    loginPendingSeen = true;
    stopLoginPolling();
    resetLoginButton.disabled = logoutButton.disabled = true;
    loginButton.disabled = true;
    loginButton.hidden = true;
    loginBox.hidden = false;
    loginMessage.textContent = loginButton.textContent.includes('验证')
      ? '正在同步浏览器登录并验证招聘方身份…' : '正在打开 BOSS 登录页…';
    try {
      await api('/api/v1/hr/login/start', {method:'POST'});
      if (generation !== loginGeneration || !allowed()) return;
      stopLoginPolling();
      loginTimer = setInterval(checkLogin, 3000);
      await checkLogin();
    } catch (error) {
      if (generation !== loginGeneration) return;
      loginButton.disabled = false;
      loginButton.hidden = false;
      loginMessage.textContent = error.message || '启动扫码失败';
    } finally {
      if (generation === loginGeneration) {
        loginActionInFlight = false;
        resetLoginButton.disabled = logoutButton.disabled = false;
      }
    }
  }
  async function logoutBoss(reconnect = false) {
    if (!allowed() || loginActionInFlight) return;
    loginActionInFlight = true;
    const generation = ++loginGeneration;
    ++sequence;
    ++sessionGeneration;
    autoLoginAttempted = true;
    loginPendingSeen = false;
    stopLoginPolling();
    clearInterval(resumeTimer);
    resumeTimer = null;
    loginButton.disabled = resetLoginButton.disabled = logoutButton.disabled = refreshButton.disabled = true;
    qr.hidden = true;
    if (qr.dataset.objectUrl) URL.revokeObjectURL(qr.dataset.objectUrl);
    delete qr.dataset.objectUrl;
    loginMessage.textContent = reconnect === true ? '正在清理本次连接并准备二维码…' : '正在断开服务器连接…';
    try {
      await api('/api/v1/hr/login/logout', {method:'POST'});
      if (generation !== loginGeneration || !allowed()) return;
      jobsRequest = null;
      conversations = [];
      selectedConversation = selectedCandidate = null;
      friendId.value = geekId.value = resumeJob.value = securityId.value = '';
      keyword.value = '';
      rememberJobs([]);
      root.querySelectorAll('.hr-result').forEach(box => box.replaceChildren());
      showDisconnected();
      if (reconnect === true) {
        // This is one explicit reset action. Keep the action lock across both
        // requests so double clicks cannot repeatedly clear or restart a scan.
        loginPendingSeen = true;
        loginButton.hidden = true;
        loginMessage.textContent = '正在生成登录二维码…';
        note.textContent = '正在准备扫码连接';
        await api('/api/v1/hr/login/start', {method:'POST'});
        if (generation !== loginGeneration || !allowed()) return;
        loginTimer = setInterval(checkLogin, 3000);
        await checkLogin();
      }
    } catch (error) {
      if (generation !== loginGeneration) return;
      loginButton.hidden = false;
      loginMessage.textContent = error.message || '操作未完成，请重试';
    } finally {
      if (generation === loginGeneration) {
        loginActionInFlight = false;
        loginButton.disabled = resetLoginButton.disabled = logoutButton.disabled = refreshButton.disabled = false;
      }
    }
  }

  const jobs = section('jobs');
  element('p', '选择职位，查看对应的推荐人才、投递和沟通进展。', jobs, 'hr-hint');
  const jobsResult = element('div', '', jobs, 'hr-result');

  const talent = section('talent');
  const talentForm = element('form', '', talent, 'hr-form');
  const keyword = field('搜索关键词', talentForm, '例如：AI 产品经理');
  const talentJob = jobField('招聘职位', talentForm);
  const talentActions = element('div', '', talentForm, 'hr-actions');
  button('搜索人才', talentActions, () => {page=1;run('candidates');}).className = 'hr-primary';
  button('按职位看推荐', talentActions, () => {page=1;run('recommendations');});
  talentForm.addEventListener('submit', event => {event.preventDefault(); page=1;run('candidates');});
  element('p', '选择职位后查看推荐，或输入岗位关键词搜索。点击候选人可查看平台开放的简历信息。', talent, 'hr-hint');
  const talentResult = element('div', '', talent, 'hr-result');

  const applications = section('applications');
  const applicationsForm = element('form', '', applications, 'hr-form');
  const applicationsJob = jobField('筛选职位', applicationsForm);
  const applicationsActions = element('div', '', applicationsForm, 'hr-actions');
  button('查询投递', applicationsActions, () => {page=1;run();}).className = 'hr-primary';
  applicationsForm.addEventListener('submit', event => {event.preventDefault();page=1;run();});
  const applicationsResult = element('div', '', applications, 'hr-result');

  const chats = section('chats');
  const chatsForm = element('form', '', chats, 'hr-form');
  const chatsJob = jobField('筛选职位', chatsForm);
  const friendId = {value:''};
  const chatsActions = element('div', '', chatsForm, 'hr-actions');
  button('查看沟通列表', chatsActions, () => {page=1;run('chats');}).className = 'hr-primary';
  chatsForm.addEventListener('submit', event => {event.preventDefault();run('chats');});
  const chatsResult = element('div', '', chats, 'hr-result');
  element('h2', '附件简历跟进', chats);
  element('p', '请求发出后会持续检查该会话；候选人晚些时候发送附件也会显示在这里。', chats, 'hr-hint');
  const attachmentResult = element('div', '', chats, 'hr-result');

  async function requestAttachmentResume() {
    const id = friendId.value.trim();
    if (!/^[1-9]\d{0,19}$/.test(id)) {
      element('p', '请先从沟通列表中选择一位候选人。', chatsResult, 'hr-error');
      return;
    }
    if (!window.confirm(`确认向「${personName(selectedConversation)}」发送附件简历请求吗？平台会实际发送一条请求。`)) return;
    try {
      await api('/api/v1/hr/resumes/request', {
        method:'POST', headers:{'Content-Type':'application/json'},
        body:JSON.stringify({friendId:id})
      });
      element('p', '附件简历请求已发出，收到后将在下方显示。', attachmentResult, 'hr-success');
      await loadTrackedResumes();
    } catch (error) {
      element('p', error.message || '索取结果未确认，请查看官方会话。', attachmentResult, 'hr-error');
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
    const generation = sessionGeneration;
    try {
      const result = await api('/api/v1/hr/resumes');
      if (generation !== sessionGeneration || current !== 'chats'
          || !allowed() || activeWorkspace !== 'hr') return;
      attachmentResult.replaceChildren();
      const items = result.data || [];
      if (!items.length) {
        element('p', '暂无附件简历请求。', attachmentResult, 'hr-empty');
        return;
      }
      for (const item of items) {
        const card = element('article', '', attachmentResult, 'hr-record');
        const known = conversations.find(row => conversationId(row) === String(item.friend_id));
        element('strong', known ? personName(known) : '候选人附件简历', card);
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
      if (generation === sessionGeneration && current === 'chats'
          && allowed() && activeWorkspace === 'hr') {
        attachmentResult.replaceChildren();
        element('p', error.message || '附件状态读取失败', attachmentResult, 'hr-error');
      }
    }
  }

  const resume = section('resume');
  const geekId = {value:''}, resumeJob = {value:''}, securityId = {value:''};
  const resumeActions = element('div', '', resume, 'hr-actions');
  button('返回人才列表', resumeActions, () => navigate('talent'));
  element('p', '从人才列表选择候选人，即可查看其在线简历。', resume, 'hr-hint');
  const resumeResult = element('div', '', resume, 'hr-result');

  const resultBoxes = {overview:status,jobs:jobsResult,talent:talentResult,
    applications:applicationsResult,chats:chatsResult,resume:resumeResult};
  const only = value => value == null ? '' : String(value).trim();
  const params = data => new URLSearchParams(Object.entries(data)
    .filter(([,value]) => value !== '' && value != null)).toString();
  const scalar = value => typeof value === 'string' || typeof value === 'number'
    ? String(value) : typeof value === 'boolean' ? (value ? '是' : '否') : '';
  const pick = (value, ...keys) => {
    for (const key of keys) {
      const item = key.split('.').reduce((item, part) => item?.[part], value);
      if (scalar(item).trim()) return scalar(item).trim();
    }
    return '';
  };
  const person = row => row?.geekCard || row?.geekInfo || row?.geekBaseInfo || row || {};
  const personName = row => pick(person(row), 'geekName','name','nickname','user.name') || '候选人';
  const jobId = row => pick(row, 'encryptJobId','encJobId','job_id','jobId');
  const conversationId = row => pick(row, 'friendId','friend_id');
  function formatTime(value) {
    if (value == null || value === '' || value === '-') return '';
    if (typeof value === 'string' && !/^\d{10,13}$/.test(value)) return value;
    const number = Number(value);
    if (!Number.isFinite(number) || number <= 0) return '';
    const date = new Date(number < 1e12 ? number * 1000 : number);
    return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', {
      month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',hour12:false
    });
  }
  function badge(text, parent, tone = '') {
    if (text) element('span', text, parent, `hr-badge ${tone}`);
  }
  function empty(box, title, description = '') {
    const panel = element('div', '', box, 'hr-empty-state');
    element('strong', title, panel);
    if (description) element('p', description, panel, 'hr-hint');
  }
  function detailsGrid(parent, entries) {
    const rows = entries.filter(([,value]) => scalar(value).trim());
    if (!rows.length) return;
    const list = element('dl', '', parent, 'hr-facts');
    for (const [label, value] of rows) {
      const item = element('div', '', list);
      element('dt', label, item);
      element('dd', scalar(value), item);
    }
  }
  function identity(parent, name, subtitle = '') {
    const block = element('div', '', parent, 'hr-identity');
    const avatar = element('span', [...name].slice(0,1).join(''), block, 'hr-avatar');
    avatar.setAttribute('aria-hidden', 'true');
    const content = element('div', '', block);
    element('h3', name, content);
    if (subtitle) element('p', subtitle, content, 'hr-hint');
    return content;
  }
  function findList(value, depth = 0) {
    if (Array.isArray(value)) return value;
    if (!value || typeof value !== 'object' || depth > 3) return null;
    for (const key of ['geekList','friendList','jobList','messages','messageList',
      'items','list','records','jobs','candidates','applications','conversations',
      'chats','geeks','data','result','zpData']) {
      const nested = findList(value[key], depth + 1);
      if (nested) return nested;
    }
    return null;
  }
  function navigate(route) {
    history.pushState(null, '', `${location.pathname}${location.search}#hr-${route}`);
    enter(route);
  }
  function rememberJobs(rows) {
    jobRecords = rows.filter(row => row && typeof row === 'object' && jobId(row));
    for (const select of jobSelectors) {
      const selected = select.value;
      select.replaceChildren();
      element('option', '全部职位', select).value = '';
      for (const row of jobRecords) {
        const option = element('option', pick(row,'jobName','name','title') || '未命名职位', select);
        option.value = jobId(row);
      }
      select.value = jobRecords.some(row => jobId(row) === selected) ? selected : '';
    }
  }
  async function loadJobOptions() {
    if (jobsRequest) return jobsRequest;
    const generation = sessionGeneration;
    jobsRequest = (async () => {
      try {
        const response = await api('/api/v1/hr/jobs');
        if (generation !== sessionGeneration || !allowed()) return;
        if (response.data?.ok) rememberJobs(findList(response.data.data) || []);
      } catch (_) {
        // The current data view reports platform errors; keep existing selections.
      } finally {
        if (generation === sessionGeneration) jobsRequest = null;
      }
    })();
    return jobsRequest;
  }
  function openConversation(row) {
    const id = conversationId(row);
    if (!/^[1-9]\d{0,19}$/.test(id)) return;
    selectedConversation = row;
    friendId.value = id;
    if (current !== 'chats') navigate('chats');
    else run('messages');
  }
  function openResume(row) {
    const info = person(row);
    geekId.value = pick(info,'encryptGeekId','encGeekId') || pick(row,'encryptGeekId','encGeekId');
    resumeJob.value = jobId(info) || jobId(row) || talentJob.value;
    securityId.value = pick(info,'securityId') || pick(row,'securityId');
    selectedCandidate = row;
    navigate('resume');
  }
  function renderStatus(box, data) {
    const card = element('div', '', box, 'hr-connection-card');
    badge('已连接', card, 'is-success');
    element('h3', 'BOSS 招聘账号已就绪', card);
    element('p', '招聘方身份验证通过，可以查看平台开放的职位、人才和沟通记录。', card, 'hr-hint');
    detailsGrid(card, [['连接平台','BOSS 直聘'],['账号',pick(data,'user_name')],
      ['登录方式','扫码登录'],['验证状态','招聘接口验证通过']]);
    const actions = element('div', '', card, 'hr-actions');
    button('查看我的职位', actions, () => navigate('jobs')).className = 'hr-primary';
    button('查看沟通', actions, () => navigate('chats'));
  }
  function renderJobs(box, rows) {
    rememberJobs(rows);
    const grid = element('div', '', box, 'hr-card-grid');
    for (const row of rows) {
      const card = element('article', '', grid, 'hr-job-card');
      const top = element('div', '', card, 'hr-card-heading');
      element('h3', pick(row,'jobName','name','title') || '未命名职位', top);
      badge(pick(row,'statusDesc','jobStatusDesc') || '已同步', top);
      const salary = pick(row,'salaryDesc','salary','salaryText');
      if (salary) element('p', salary, card, 'hr-salary');
      detailsGrid(card, [['工作地点',pick(row,'address','cityName','locationName')],
        ['经验要求',pick(row,'experienceName','experience','workYearDesc')],
        ['学历要求',pick(row,'degreeName','degreeDesc','education')]]);
      const description = pick(row,'description','jobDescription');
      if (description) {
        const detail = element('details', '', card, 'hr-description');
        element('summary', '职位说明', detail);
        element('p', description, detail, 'hr-prose');
      }
      if (jobId(row)) {
        const actions = element('div', '', card, 'hr-actions');
        button('推荐人才', actions, () => {
          talentJob.value = jobId(row);
          talentAction = 'recommendations';
          navigate('talent');
          run('recommendations');
        }).className = 'hr-primary';
        button('查看投递', actions, () => {applicationsJob.value=jobId(row);navigate('applications');});
        button('沟通记录', actions, () => {
          chatsJob.value=jobId(row);selectedConversation=null;navigate('chats');
        });
      }
    }
  }
  function renderCandidates(box, rows) {
    const grid = element('div', '', box, 'hr-card-grid');
    for (const row of rows) {
      const info = person(row);
      const card = element('article', '', grid, 'hr-person-card');
      const header = element('div', '', card, 'hr-card-heading');
      identity(header, personName(row), pick(info,'expectPositionName','positionName','expectPosition','position'));
      const salary = pick(info,'salary','salaryDesc');
      if (salary) element('strong', salary, header, 'hr-salary');
      const badges = element('div', '', card, 'hr-tags');
      badge(pick(info,'activeTimeDesc') || pick(row,'activeTimeDesc'), badges, 'is-success');
      badge(pick(info,'applyStatusDesc'), badges);
      detailsGrid(card, [['工作经验',pick(info,'geekWorkYear','workYearDesc','work_years','workYear')],
        ['学历',pick(info,'geekDegree','degreeName','degreeDesc')],
        ['期望城市',pick(info,'expectLocationName','locationName','city')],
        ['最近更新',formatTime(row.updateTime)]]);
      const works = row.showWorks || info.geekWorks || [];
      const education = row.showEdus || info.geekEdus || (info.geekEdu ? [info.geekEdu] : []);
      if (works.length) {
        const latest = works[0];
        element('p', [pick(latest,'company'),pick(latest,'positionName','position')].filter(Boolean).join(' · '),
          card, 'hr-career-line');
      }
      if (education.length) {
        element('p', [pick(education[0],'school'),pick(education[0],'major')].filter(Boolean).join(' · '),
          card, 'hr-hint');
      }
      const description = pick(info,'geekDesc.content','middleContent.content','description');
      if (description) element('p', description, card, 'hr-excerpt');
      const tags = element('div', '', card, 'hr-tags');
      const skills = Array.isArray(info.matches) ? info.matches : [];
      for (const skill of skills.slice(0,8)) badge(scalar(skill), tags);
      const actions = element('div', '', card, 'hr-actions');
      const canResume = (pick(info,'encryptGeekId','encGeekId') || pick(row,'encryptGeekId','encGeekId'))
        && (jobId(info) || jobId(row) || talentJob.value)
        && (pick(info,'securityId') || pick(row,'securityId'));
      if (canResume) button('查看简历', actions, () => openResume(row)).className = 'hr-primary';
      if (conversationId(row)) button('查看沟通', actions, () => openConversation(row)).className = 'hr-primary';
      if (!canResume && !conversationId(row)) element('span', '平台暂未开放详细资料入口', actions, 'hr-hint');
    }
  }
  function conversationLayout(box) {
    const layout = element('div', '', box, 'hr-chat-layout');
    const list = element('div', '', layout, 'hr-chat-list');
    list.setAttribute('aria-label', '候选人会话');
    for (const row of conversations) {
      const action = button('', list, () => openConversation(row));
      action.className = 'hr-conversation';
      action.setAttribute('aria-pressed', String(conversationId(row) === friendId.value));
      element('strong', personName(row), action);
      const preview = pick(row,'last_msg','lastMsg','lastMsgInfo.showText');
      element('p', preview && preview !== '-' ? preview : '点击查看沟通记录', action, 'hr-chat-preview');
      element('small', formatTime(row.last_time || row.updateTime), action);
      if (Number(row.unread) > 0) badge(`${row.unread} 条未读`, action, 'is-attention');
    }
    const detail = element('section', '', layout, 'hr-chat-detail');
    if (selectedConversation) {
      const top = element('div', '', detail, 'hr-chat-header');
      identity(top, personName(selectedConversation), '沟通记录');
      button('索取附件简历', top, requestAttachmentResume).className = 'hr-secondary';
    }
    return detail;
  }
  function renderMessages(box, data) {
    const detail = conversationLayout(box);
    const source = findList(data) || [];
    if (!source.length) {empty(detail, '暂无消息记录');return;}
    const thread = element('div', '', detail, 'hr-message-list');
    const messages = [...source].sort((a,b) => Number(a.time || 0)-Number(b.time || 0));
    for (const message of messages) {
      const sender = message.from || {};
      const ours = Number(sender.source) === 1;
      const bubble = element('article', '', thread, `hr-message ${ours ? 'is-own' : ''}`);
      element('p', `${ours ? '招聘方' : pick(sender,'name') || personName(selectedConversation)}${
        formatTime(message.time) ? ' · '+formatTime(message.time) : ''}`, bubble, 'hr-message-meta');
      const body = message.body || {};
      const text = pick(body,'text','content') || pick(message,'text','content');
      if (text) element('p', text, bubble, 'hr-message-text');
      if (body.resume && typeof body.resume === 'object') {
        const resumeCard = element('div', '', bubble, 'hr-message-card');
        element('strong', pick(body.resume,'user.name') || '候选人简历卡片', resumeCard);
        detailsGrid(resumeCard, [['期望职位',pick(body.resume,'position')],
          ['薪资',pick(body.resume,'salary')],['经验',pick(body.resume,'workYear')],
          ['学历',pick(body.resume,'education')],['城市',pick(body.resume,'city')]]);
        const description = pick(body.resume,'description','content1');
        if (description) element('p', description, resumeCard, 'hr-prose');
      } else if (body.hyperLink) {
        const attachment = element('div', '', bubble, 'hr-message-card');
        element('strong', pick(body.hyperLink,'title','text') || '附件 / 平台卡片', attachment);
        element('p', '已接收的附件简历可在下方“附件简历跟进”下载；其他卡片请在 BOSS 官方页面查看。',
          attachment, 'hr-hint');
      } else if (!text) {
        element('p', pick(body,'headTitle') || '图片、附件或平台通知，请在 BOSS 官方会话查看。', bubble, 'hr-hint');
      }
    }
    if (data?.hasMore) element('p', '当前显示最近 20 条，更多历史记录可在 BOSS 官方会话查看。', detail, 'hr-hint');
  }
  function timeline(parent, title, rows, mainKeys, subKeys) {
    if (!Array.isArray(rows) || !rows.length) return;
    const section = element('section', '', parent, 'hr-resume-section');
    element('h3', title, section);
    for (const row of rows) {
      const item = element('article', '', section, 'hr-timeline-item');
      element('h4', pick(row,...mainKeys) || title, item);
      const line = [pick(row,...subKeys),[pick(row,'start'),pick(row,'end')].filter(Boolean).join(' — ')]
        .filter(Boolean).join(' · ');
      if (line) element('p', line, item, 'hr-hint');
      for (const key of ['responsibility','performance','description','achievement']) {
        if (pick(row,key)) element('p', pick(row,key), item, 'hr-prose');
      }
      if (Array.isArray(row.keywords)) {
        const tags = element('div', '', item, 'hr-tags');
        row.keywords.forEach(tag => badge(scalar(tag), tags));
      }
    }
  }
  function renderResume(box, data) {
    const sheet = element('article', '', box, 'hr-resume-sheet');
    identity(sheet, pick(data,'basic.name') || personName(selectedCandidate),
      pick(data,'expectation.position'));
    detailsGrid(sheet, [['工作经验',pick(data,'basic.work_years')],['学历',pick(data,'basic.degree')],
      ['期望薪资',pick(data,'expectation.salary')],['期望城市',pick(data,'expectation.city')],
      ['活跃状态',pick(data,'basic.active_status')]]);
    timeline(sheet,'工作经历',data.work_experience,['company'],['position']);
    timeline(sheet,'项目经历',data.project_experience,['name'],['role']);
    timeline(sheet,'教育经历',data.education,['school'],['major','degree']);
    for (const [key,title] of [['certifications','证书资质'],['competitive_analysis','平台补充信息']]) {
      if (Array.isArray(data[key]) && data[key].length) {
        const section = element('section', '', sheet, 'hr-resume-section');
        element('h3', title, section);
        const list = element('ul', '', section);
        data[key].forEach(item => {if (scalar(item)) element('li',scalar(item),list);});
      }
    }
    if (!data.basic) renderFields(sheet,data);
  }
  const fieldLabels = {name:'名称',title:'标题',description:'说明',content:'内容',text:'内容',
    city:'城市',address:'地址',salary:'薪资',salaryDesc:'薪资',company:'公司',position:'职位',
    degree:'学历',education:'教育信息',experience:'工作经历',status:'状态',message:'说明',
    start:'开始时间',end:'结束时间',school:'学校',major:'专业',department:'部门',
    total:'总数',count:'数量',hasMore:'还有下一页',page:'页码',list:'信息列表',data:'详细信息',
    responsibility:'工作职责',performance:'工作成果',certifications:'证书资质'};
  function renderFields(parent, value, depth = 0) {
    if (depth > 5 || value == null) return;
    if (typeof value !== 'object') {element('p',scalar(value),parent,'hr-prose');return;}
    if (Array.isArray(value)) {
      value.slice(0,100).forEach(item => renderFields(element('div','',parent,'hr-field-group'),item,depth+1));
      return;
    }
    for (const [key,item] of Object.entries(value)) {
      if (item == null || item === '' || /cookie|token|secret|password|securityId|encrypt|(^id$)|Id$|_id$/i.test(key)) continue;
      const label = fieldLabels[key] || key.replace(/([a-z])([A-Z])/g,'$1 $2').replaceAll('_',' ');
      if (typeof item === 'object') {
        const detail = element('details','',parent,'hr-field-group');
        element('summary',label,detail);
        renderFields(detail,item,depth+1);
      } else detailsGrid(parent,[[label,item]]);
    }
  }
  function display(box, envelope, action) {
    box.replaceChildren();
    if (!envelope?.ok) {
      const error = envelope?.error || {};
      const missing = ['AUTH_REQUIRED','AUTH_EXPIRED'].includes(error.code);
      element('p', missing ? 'BOSS 登录尚未完成或已过期，请到招聘工作台扫码连接。'
        : (error.message || '平台读取失败，请查看账号状态。'), box, 'hr-error');
      if (error.code) element('small', `错误代码：${error.code}`, box);
      return null;
    }
    const data = envelope.data;
    if (action === 'overview') {renderStatus(box,data);return null;}
    if (action === 'resume') {renderResume(box,data || {});return null;}
    if (action === 'messages') {renderMessages(box,data || {});return null;}
    const rows = findList(data);
    if (rows) {
      element('p', `本页 ${rows.length} ${action === 'jobs' ? '个职位' : '位候选人'} · BOSS 直聘`, box, 'hr-count');
      if (!rows.length) {
        if (action === 'jobs') rememberJobs([]);
        if (action === 'chats') conversations = [];
        empty(box, '暂无记录', '可以调整筛选条件后重新查询。');
      } else if (action === 'jobs') renderJobs(box,rows);
      else if (action === 'chats') {
        conversations = rows;
        const detail = conversationLayout(box);
        empty(detail,'选择一位候选人','点击左侧会话查看消息，索取简历需要你单独确认。');
      } else renderCandidates(box,rows);
    } else if (data != null) {
      renderFields(box,data);
    } else {
      empty(box,'暂无可展示的信息');
    }
    return rows;
  }
  function pagination(box, route, envelope, rows) {
    if (!envelope?.ok || !Array.isArray(rows)
        || !['applications','chats','candidates','recommendations'].includes(route)) return;
    const controls = element('div', '', box, 'hr-pagination');
    const previous = button('上一页', controls, () => {page--;run(route);});
    previous.disabled = page <= 1;
    element('span', `第 ${page} 页`, controls);
    const next = button('下一页', controls, () => {page++;run(route);});
    next.disabled = page >= 20 || rows.length === 0 || envelope.data?.hasMore === false
      || envelope.data?.has_more === false;
  }
  async function run(action = current) {
    if (!allowed()) return;
    if (action === 'talent') action = talentAction;
    if (action === 'candidates' || action === 'recommendations') talentAction = action;
    if (action === 'chats') {selectedConversation=null;friendId.value='';}
    const box = resultBoxes[current], ticket = ++sequence;
    box.replaceChildren();
    box.setAttribute('aria-busy','true');
    element('p', '正在读取平台数据…', box, 'hr-loading');
    let path;
    try {
      // One recruiter account is serialized by the connector. Load the small
      // position picker first instead of racing it against a candidate request.
      if (jobsRequest && !['overview','jobs'].includes(action)) await jobsRequest;
      if (ticket !== sequence || activeWorkspace !== 'hr' || !allowed()) return;
      if (action === 'overview') path = '/api/v1/hr/status';
      else if (action === 'jobs') path = '/api/v1/hr/jobs';
      else if (action === 'applications') path = '/api/v1/hr/applications?' + params({page, jobId:only(applicationsJob.value)});
      else if (action === 'candidates') path = '/api/v1/hr/candidates?' + params({page, keyword:only(keyword.value), jobId:only(talentJob.value)});
      else if (action === 'recommendations') {
        if (!only(talentJob.value)) throw Error('请先选择一个招聘职位。');
        path = '/api/v1/hr/recommendations?' + params({page, jobId:only(talentJob.value)});
      } else if (action === 'chats') path = '/api/v1/hr/chats?' + params({page, jobId:only(chatsJob.value)});
      else if (action === 'messages') {
        if (!/^[1-9]\d{0,19}$/.test(only(friendId.value))) throw Error('请先选择一位候选人的会话。');
        path = '/api/v1/hr/messages/' + encodeURIComponent(only(friendId.value));
      } else if (action === 'resume') {
        if (!geekId.value.trim() || !resumeJob.value.trim() || !securityId.value.trim()) {
          throw Error('请先从人才列表选择一位已开放简历的候选人。');
        }
        path = '/api/v1/hr/resume?' + params({geekId:only(geekId.value),jobId:only(resumeJob.value),securityId:only(securityId.value)});
      } else return;
      const response = await api(path);
      if (ticket !== sequence || activeWorkspace !== 'hr') return;
      if (action === 'overview' && response.data?.error?.code === 'SESSION_DISCONNECTED') {
        showDisconnected();
        return;
      }
      if (action === 'overview' && response.data?.error?.code === 'LOGIN_PENDING') {
        box.replaceChildren();
        return;
      }
      const rows = display(box, response.data, action);
      if (action === 'overview') {
        // The connector only returns a successful status after its recruiter
        // endpoint check; a missing secondary token alone is not a role failure.
        const recruiterReady = response.data?.ok === true;
        intro.hidden = recruiterReady;
        loginFootnote.hidden = recruiterReady;
        logoutButton.hidden = !recruiterReady;
        if (recruiterReady) {
          loginBox.hidden = true;
          note.textContent = '招聘方账号已连接 · BOSS 直聘';
        }
      }
      if (action === 'overview'
          && ['AUTH_REQUIRED','AUTH_EXPIRED','RECRUITER_REQUIRED'].includes(response.data?.error?.code)
          && !autoLoginAttempted) {
        startLogin();
      }
      pagination(box, action, response.data, rows);
      if (action !== 'overview' || logoutButton.hidden) {
        note.textContent = `最近读取：${new Date().toLocaleTimeString()} · 数据来自 BOSS 直聘`;
      }
    } catch (error) {
      if (ticket !== sequence) return;
      box.replaceChildren();
      element('p', error.message || '读取失败', box, 'hr-error');
    } finally {
      if (ticket === sequence) box.setAttribute('aria-busy','false');
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
    document.querySelectorAll('[data-hr-route]').forEach(link => {
      link.classList.toggle('active', link.dataset.hrRoute === current);
    });
    if (['talent','applications','chats'].includes(current) && !jobRecords.length) loadJobOptions();
    if (current === 'overview') {
      run();
      checkLogin();
    } else if (current === 'jobs' || current === 'applications' || current === 'chats') {
      run(current === 'chats' && selectedConversation ? 'messages' : current);
      if (current === 'chats') {
        loadTrackedResumes();
        resumeTimer = setInterval(loadTrackedResumes, 30000);
      }
    } else if (current === 'resume') {
      if (selectedCandidate) run('resume');
      else {
        resumeResult.replaceChildren();
        empty(resumeResult,'先选择一位候选人','在人才列表中点击“查看简历”，这里会展示工作、项目和教育经历。');
      }
    } else if (current === 'talent' && !talentResult.children.length) {
      empty(talentResult,'找到适合岗位的人才','选择招聘职位查看推荐，或输入关键词开始搜索。');
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
    ++sessionGeneration;
    ++loginGeneration;
    loginActionInFlight = false;
    stopLoginPolling();
    clearInterval(resumeTimer);
    resumeTimer = null;
    autoLoginAttempted = false;
    loginPendingSeen = false;
    jobsRequest = null;
    conversations = [];
    selectedConversation = selectedCandidate = null;
    friendId.value = geekId.value = resumeJob.value = securityId.value = '';
    keyword.value = '';
    rememberJobs([]);
    qr.hidden = true;
    logoutButton.hidden = true;
    if (qr.dataset.objectUrl) URL.revokeObjectURL(qr.dataset.objectUrl);
    delete qr.dataset.objectUrl;
    root.querySelectorAll('.hr-result').forEach(box => box.replaceChildren());
  }};
  window.addEventListener('heyi:company-reset', () => HrWorkspace.reset());
})();
