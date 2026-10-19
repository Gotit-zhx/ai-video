/* Recruiter data stays behind the platform-admin API; no browser CDP or Cookie access. */
(() => {
  const root = document.getElementById('hr-page');
  if (!root) return;
  const hrApi = (path, options) => globalThis.HeyiHrTransport
    ? globalThis.HeyiHrTransport.api(path, options) : api(path, options);
  const hrFetch = (path, options) => globalThis.HeyiHrTransport
    ? globalThis.HeyiHrTransport.request(path, options) : fetch(path, options);
  const routes = new Set(['overview', 'jobs', 'talent', 'applications', 'chats', 'resume', 'pool', 'demands', 'departments', 'onboarding', 'resume-pool', 'speeches']);
  const panels = new Map();
  let current = 'overview', sequence = 0, page = 1, talentAction = 'candidates';
  let loginTimer = null;
  let loginPendingSeen = false, loginCheckInFlight = null;
  let loginActionInFlight = false, loginGeneration = 0;
  let loginPhase = '';
  let resumeTimer = null;
  let jobRecords = [], jobsRequest = null, conversations = [], selectedConversation = null;
  let recoveryRead = null;
  const readNotices = new WeakMap();
  let selectedCandidate = null, sessionGeneration = 0;
  let trackedResumeRequests = new Map(), requestResumeButton = null;
  let resumeLoadSequence = 0;
  const resumeRequestBadges = new Map();
  const resumeRequestsInFlight = new Set();
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
  const allowed = () => (currentUser?.platformAdmin === true && currentUser?.username === '13800000000')
    || currentUser?.permissions?.includes('hr:access') === true;
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
  const header = element('header', '', root, 'hr-workspace-header');
  const heading = element('div', '', header);
  element('span', 'HOYEE / RECRUITMENT', heading, 'hr-eyebrow');
  const pageTitle = element('h1', '招聘工作台', heading);
  const pageSubtitle = element('p', '从职位出发，让每一次沟通都有进展。', heading, 'hr-hint');
  const headerActions = element('div', '', header, 'hr-header-actions');
  const connectionBadge = element('span', '等待连接', headerActions, 'hr-device-badge');
  button('连接设置', headerActions, () => navigate('overview')).className='hr-secondary';
  const downloads = element('details', '', headerActions, 'hr-downloads');
  element('summary', '下载桌面助手', downloads);
  const downloadMenu = element('div', '', downloads, 'hr-download-menu');
  for(const [label,file,version] of [['Mac · Apple 芯片','Heyi-Recruiter-mac-arm64.dmg','0.1.24-c11aab0'],['Windows · 64 位','Heyi-Recruiter-windows-x64-Setup.exe','0.1.24-c11aab0']]){
    const link=element('a',label,downloadMenu);link.href='/downloads/'+file+'?v='+version;
    link.title='Ai boss 0.1.24';
  }
  const note = element('p', '', root, 'hr-note');
  note.setAttribute('role', 'status');
  const loginSuccess = element('p', '招聘账号已连接，可以开始工作。', root, 'hr-success hr-login-success');
  loginSuccess.setAttribute('role', 'status');loginSuccess.hidden=true;

  const overview = section('overview');
  overview.className='hr-panel hr-overview';
  const connectionCard = element('div', '', overview, 'hr-connect-card');
  const connectionArt = element('div', '', connectionCard, 'hr-connect-art');
  connectionArt.setAttribute('aria-hidden','true');
  const desktopIllustration=element('div','',connectionArt,'hr-desktop-illustration');
  element('span','',desktopIllustration,'hr-illustration-toolbar');
  element('span','',desktopIllustration,'hr-illustration-line');
  element('span','',desktopIllustration,'hr-illustration-line is-short');
  element('span','H',connectionArt,'hr-helper-orb');
  const connectionBody=element('div','',connectionCard,'hr-connect-body');
  const connectionPlatform=element('div','',connectionBody,'hr-connect-platform');
  element('span','BOSS 直聘',connectionPlatform,'hr-platform-label');
  const loginProgress=element('span','',connectionPlatform,'hr-login-progress');
  loginProgress.hidden=true;loginProgress.setAttribute('role','status');
  element('span','',loginProgress,'hr-login-spinner');
  const loginProgressTitle=element('span','',loginProgress);
  const loginTitle=element('h2','连接桌面助手，开始招聘',connectionBody);
  const loginMessage=element('p','打开赫逸桌面悬浮球，再连接本机助手。BOSS 登录在助手内完成。',connectionBody,'hr-connect-description');
  const loginActions=element('div','',connectionBody,'hr-actions');
  const loginButton=button('连接本机助手',loginActions,primaryConnectionAction);loginButton.className='hr-primary';
  const checkButton=button('检查连接',loginActions,()=>checkConnection());
  const logoutButton=button('退出 BOSS 登录',loginActions,logoutBoss);logoutButton.className='hr-text-button';logoutButton.hidden=true;
  const status=element('div','',connectionBody,'hr-result hr-status-result');
  status.setAttribute('role','status');
  const guide=element('div','',overview,'hr-connection-guide');
  for(const [number,title,description] of [
    ['01','打开桌面助手','悬浮球常驻桌面，点击即可展开。'],
    ['02','登录 BOSS','在助手页面扫码，并在手机上确认。'],
    ['03','回到这里工作','职位、人才和沟通统一在网页处理。'],
  ]){
    const step=element('div','',guide);element('span',number,step,'hr-step-number');
    const copy=element('div','',step);element('strong',title,copy);element('p',description,copy);
  }
  const shortcuts=element('section','',overview,'hr-shortcuts');
  const shortcutHeader=element('div','',shortcuts,'hr-section-heading');
  element('h2','招聘工作',shortcutHeader);element('span','从需求到沟通，有序推进。',shortcutHeader);
  const shortcutGrid=element('div','',shortcuts,'hr-shortcut-grid');
  const shortcutEntries=[
    ['demands','招聘需求池','明确岗位需求，用 AI 辅助画像与面试。','M5 3h14v18H5z M8 8h8 M8 12h8 M8 16h5'],
    ['departments','部门管理','统一维护部门和经理，保持组织信息一致。','M9 3h6v5H9z M3 16h6v5H3z M15 16h6v5h-6z M12 8v4 M6 16v-4h12v4'],
    ['resume-pool','简历池','查看原件，由 HR 审批和选择 AI 辅助分析。','M6 3h12v18H6z M9 8h6 M9 12h6'],
    ['speeches','话术管理','配置默认婉拒与常用沟通话术。','M4 4h16v13H9l-5 4z M8 9h8'],
    ['pool','储备人才库','按部门归档人才，持续跟进招聘阶段。','M4 5h16v15H4z M4 10h16 M9 10v10'],
    ['jobs','职位管理','管理在招岗位，按职位跟进人才。','M4 7h16v13H4z M8 7V4h8v3 M4 12h16 M10 12v3h4v-3'],
    ['onboarding','入职安排及跟进','确认入职安排，提醒导师准备带教。','M4 4h16v16H4z M7 9h10 M7 13h7 M7 17h4'],
  ];
  for(const [route,title,description,shape] of shortcutEntries){
    const card=button('',shortcutGrid,()=>navigate(route));card.className='hr-shortcut';
    const symbol=element('span','',card,'hr-shortcut-icon');
    const svg=document.createElementNS('http://www.w3.org/2000/svg','svg');svg.setAttribute('viewBox','0 0 24 24');svg.setAttribute('aria-hidden','true');
    const path=document.createElementNS('http://www.w3.org/2000/svg','path');path.setAttribute('d',shape);svg.append(path);symbol.append(svg);
    element('strong',title,card);element('span',description,card,'hr-shortcut-description');element('span','↗',card,'hr-shortcut-arrow');
  }
  let helperConnected=!globalThis.HeyiHrTransport||globalThis.HeyiHrTransport.connection?.().paired===true;
  let connectionState='idle',loginFailures=0;
  function stopLoginPolling(){clearInterval(loginTimer);loginTimer=null;}
  function showLoginProgress(phase=''){
    loginProgress.hidden=!phase;loginProgress.dataset.phase=phase;
    connectionCard.setAttribute('aria-busy',phase?'true':'false');
    loginProgressTitle.textContent=phase==='scanned'?'已扫码':phase==='verifying'?'正在确认':phase==='reconnecting'?'重新检查中':'等待确认';
  }
  function paintConnection(state={state:connectionState}){
    connectionState=state.state||'idle';
    const ready=helperConnected&&connectionState==='complete';
    const pending=helperConnected&&connectionState==='pending';
    connectionCard.classList.toggle('is-ready',ready);
    guide.hidden=ready;
    connectionBadge.textContent=!helperConnected?'助手未连接':ready?'招聘账号已连接':pending?'正在连接 BOSS':'助手已连接';
    connectionBadge.dataset.connected=String(ready);
    connectionArt.classList.toggle('is-connected',ready);
    checkButton.hidden=false;checkButton.disabled=loginActionInFlight;
    logoutButton.hidden=!helperConnected||connectionState==='disconnected'||connectionState==='idle';
    logoutButton.disabled=loginActionInFlight;
    loginButton.disabled=loginActionInFlight||pending;
    loginButton.textContent=!helperConnected?'连接本机助手':ready?'查看我的职位':pending?'请在桌面助手确认':'在助手中登录 BOSS';
    loginTitle.textContent=!helperConnected?'连接桌面助手，开始招聘':ready?'招聘账号已就绪':pending
      ?loginPhase==='scanned'?'请在手机上确认登录':loginPhase==='verifying'?'正在确认招聘账号':'在桌面助手完成登录'
      :connectionState==='verification_pending'?'连接需要确认':'在桌面助手中登录 BOSS';
    loginMessage.textContent=!helperConnected?'打开赫逸桌面助手后会自动连接，断线会自动检查恢复。BOSS 登录需在助手内确认。':ready
      ?'招聘方身份验证通过。选择一个职位，继续查看人才、投递和沟通。':pending
      ?loginPhase==='scanned'?'已收到扫码结果，请在 BOSS App 确认；无需重复扫码。':loginPhase==='verifying'
        ?'手机确认已收到，正在等待招聘方身份验证结果。':'点击桌面悬浮球，在 BOSS 页面扫码并用手机确认。这里会同步连接结果。'
      :state.error||'登录页面将在桌面助手内打开。若已登录，将核对当前招聘账号。';
    showLoginProgress(pending?loginPhase||'qr':'');
    if(state.reconnecting){
      connectionBadge.textContent='助手自动恢复中';loginTitle.textContent='正在恢复本机连接';
      loginButton.textContent='自动恢复中…';loginButton.disabled=true;showLoginProgress('reconnecting');
    }
    note.textContent='';
  }
  async function primaryConnectionAction(){
    if(loginActionInFlight)return;
    if(helperConnected){if(connectionState==='complete')navigate('jobs');else await startLogin();return;}
    loginActionInFlight=true;loginButton.disabled=true;loginButton.textContent='正在连接助手…';
    const generation=++loginGeneration;loginPhase='';loginFailures=0;
    try{
      if(!globalThis.HeyiHrTransport)throw Error('请刷新页面后连接桌面助手');
      await globalThis.HeyiHrTransport.connect();
      if(generation!==loginGeneration||!allowed())return;
      globalThis.HeyiHrTransport.startAutoConnect?.();
      helperConnected=true;status.replaceChildren();await checkLogin();
    }catch(error){if(generation===loginGeneration){status.replaceChildren();status.dataset.connectionError='true';element('p',error.message,status,'hr-error');}}
    finally{if(generation===loginGeneration){loginActionInFlight=false;paintConnection(lastLoginState);}}
  }
  let lastLoginState={state:'idle'};
  async function checkConnection(){
    if(helperConnected){await checkLogin();return;}
    if(!allowed()||loginActionInFlight)return;
    loginActionInFlight=true;const generation=++loginGeneration;
    status.replaceChildren();element('p','正在检查平台与本机助手连接…',status,'hr-hint');
    paintConnection(lastLoginState);
    try{
      if(!globalThis.HeyiHrTransport?.diagnose)throw Error('请刷新页面后检查连接。');
      const result=await globalThis.HeyiHrTransport.diagnose();
      if(generation!==loginGeneration||!allowed()||current!=='overview')return;
      status.replaceChildren();status.dataset.connectionError=String(!result.ok);
      element('p',result.message,status,result.ok?'hr-hint':'hr-error');
    }catch(error){
      if(generation===loginGeneration&&allowed()&&current==='overview'){
        status.replaceChildren();status.dataset.connectionError='true';element('p',error.message,status,'hr-error');
      }
    }finally{if(generation===loginGeneration){loginActionInFlight=false;paintConnection(lastLoginState);}}
  }
  globalThis.HeyiHrTransport?.subscribe?.(connection=>{
    if(!allowed()||loginActionInFlight)return;
    helperConnected=connection.paired;
    const recovering=['recovering','reconnecting'].includes(connection.phase)||connection.state?.reconnecting;
    if(recovering&&lastLoginState.state==='complete'){
      // Keep the current cards, selection and layout during a transient outage.
      // Only the small status text changes after the recovery grace period.
      if(connection.phase!=='recovering')connectionBadge.textContent='助手后台恢复中';
      return;
    }
    if(connection.state&&helperConnected){
      if(status.dataset.connectionError==='true'){
        status.replaceChildren();delete status.dataset.connectionError;
      }
      lastLoginState=connection.state;
      loginPhase=connection.state.phase||'';
    }
    paintConnection(lastLoginState);
    if(helperConnected&&connection.state?.state==='complete'&&!connection.state.paused
      &&!connection.state.busy&&activeWorkspace==='hr'&&recoveryRead?.section===current){
      const pending=recoveryRead;recoveryRead=null;void run(pending.action,true);
    }
    if(!helperConnected&&connection.phase==='reconnecting'){
      connectionBadge.textContent='助手自动重连中';
      loginMessage.textContent=connection.message||'正在检查本机助手，连接恢复后会自动更新。';
    }else if(['unauthorized','device-in-use','update-required'].includes(connection.phase)){
      connectionBadge.textContent=connection.phase==='update-required'?'助手需要更新':'连接需要确认';
      loginMessage.textContent=connection.message;
    }
  });
  function showDisconnected(){
    loginPhase='';loginPendingSeen=false;stopLoginPolling();loginSuccess.hidden=true;
    lastLoginState={state:'disconnected',error:'本机 BOSS Cookie 和保存的登录凭据已清除。再次使用时请重新登录。'};
    paintConnection(lastLoginState);status.replaceChildren();
  }
  async function checkLogin(){
    if(!allowed()){stopLoginPolling();return;}
    if(!helperConnected){paintConnection();return;}
    if(loginCheckInFlight?.generation===loginGeneration)return;
    const generation=loginGeneration;
    const read={generation};loginCheckInFlight=read;
    try{
      const result=await hrApi('/api/v1/hr/login/status');
      if(generation!==loginGeneration||!allowed())return;
      loginFailures=0;status.replaceChildren();
      const state=result.data||{};lastLoginState=state;
      if(state.state==='disconnected'){showDisconnected();return;}
      if(state.state==='pending'){
        loginPendingSeen=true;
        const order={qr:1,scanned:2,verifying:3};
        if((order[state.phase]||1)>(order[loginPhase]||0))loginPhase=state.phase||'qr';
        if(!loginTimer)loginTimer=setInterval(checkLogin,3000);
      }else{
        stopLoginPolling();
        if(state.state==='complete'&&loginPendingSeen)loginSuccess.hidden=false;
        loginPendingSeen=false;loginPhase='';
      }
      paintConnection(state);
    }catch(error){
      if(generation!==loginGeneration)return;
      status.replaceChildren();status.dataset.connectionError='true';element('p',error.message||'连接状态读取失败',status,'hr-error');
      if(loginPendingSeen&&++loginFailures<3){showLoginProgress('reconnecting');return;}
      stopLoginPolling();helperConnected=false;loginPendingSeen=false;paintConnection();
    }finally{if(loginCheckInFlight===read)loginCheckInFlight=null;}
  }
  async function startLogin(){
    if(!allowed()||loginActionInFlight)return;
    loginActionInFlight=true;const generation=++loginGeneration;
    loginPhase='';++sequence;status.replaceChildren();delete status.dataset.connectionError;loginSuccess.hidden=true;
    loginPendingSeen=false;stopLoginPolling();
    loginButton.disabled=checkButton.disabled=logoutButton.disabled=true;
    loginButton.textContent='正在打开桌面助手…';
    try{
      await hrApi('/api/v1/hr/login/start',{method:'POST'});
      if(generation!==loginGeneration||!allowed())return;
      loginPendingSeen=true;lastLoginState={state:'pending',phase:'qr'};paintConnection(lastLoginState);
      loginTimer=setInterval(checkLogin,3000);await checkLogin();
    }catch(error){
      if(generation!==loginGeneration)return;
      status.replaceChildren();element('p',error.message||'未能打开桌面助手',status,'hr-error');
    }finally{if(generation===loginGeneration){loginActionInFlight=false;paintConnection(lastLoginState);}}
  }
  async function logoutBoss(){
    if(!allowed()||loginActionInFlight)return;
    loginActionInFlight=true;const generation=++loginGeneration;
    ++sequence;++sessionGeneration;loginPhase='';loginPendingSeen=false;stopLoginPolling();
    clearInterval(resumeTimer);resumeTimer=null;loginSuccess.hidden=true;
    loginButton.disabled=checkButton.disabled=logoutButton.disabled=true;
    logoutButton.textContent='正在清除登录信息…';status.replaceChildren();delete status.dataset.connectionError;
    try{
      const response=globalThis.HeyiHrTransport
        ?await globalThis.HeyiHrTransport.disconnect()
        :await hrApi('/api/v1/hr/login/logout',{method:'POST'});
      if(generation!==loginGeneration||!allowed())return;
      if(response?.cleared!==true&&(response?.success!==true||response?.data?.state!=='disconnected'))throw Error('未确认 Cookie 已清除，请检查助手连接后重试');
      clearRecruiterData();showDisconnected();
      helperConnected=!globalThis.HeyiHrTransport;
      loginSuccess.textContent='本机 BOSS Cookie 和保存的登录凭据已清除。';
      loginSuccess.hidden=false;
    }catch(error){
      if(generation!==loginGeneration)return;
      status.replaceChildren();element('p',error.message||'登录信息未能清除，请重试',status,'hr-error');
      lastLoginState={state:'verification_pending',error:'未确认登录信息已清除，请检查助手后重试。'};
    }finally{if(generation===loginGeneration){loginActionInFlight=false;logoutButton.textContent='退出 BOSS 登录';paintConnection(lastLoginState);}}
  }
  function clearRecruiterData(){
    jobsRequest=null;conversations=[];trackedResumeRequests.clear();resumeRequestsInFlight.clear();
    attachmentFeedback.hidden=true;selectedConversation=selectedCandidate=null;
    friendId.value=geekId.value=resumeJob.value=securityId.value='';keyword.value='';rememberJobs([]);
    root.querySelectorAll('.hr-result').forEach(box=>box.replaceChildren());
  }
  paintConnection();

  const jobs = section('jobs');
  element('p', '选择职位，查看对应的推荐人才、投递和沟通进展。', jobs, 'hr-hint');
  const jobsRefresh = button('刷新职位', jobs, () => run('jobs'));
  jobsRefresh.className = 'hr-secondary';
  const jobsResult = element('div', '', jobs, 'hr-result');

  const talent = section('talent');
  const talentForm = element('form', '', talent, 'hr-form');
  const keyword = field('搜索关键词', talentForm, '例如：AI 产品经理');
  const talentJob = jobField('招聘职位', talentForm);
  const talentActions = element('div', '', talentForm, 'hr-actions');
  button('搜索人才', talentActions, () => {page=1;run('candidates');}).className = 'hr-primary';
  button('按职位看推荐', talentActions, () => {page=1;run('recommendations');});
  talentForm.addEventListener('submit', event => {event.preventDefault(); page=1;run('candidates');});
  const legacyTalentHint=element('p', '选择职位后查看推荐，或输入岗位关键词搜索。点击候选人可查看平台开放的简历信息。', talent, 'hr-hint');
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
  const attachmentFeedback = element('p', '', chats, 'hr-success');
  attachmentFeedback.hidden = true;
  attachmentFeedback.setAttribute('role', 'status');
  const chatsResult = element('div', '', chats, 'hr-result');
  element('h2', '附件简历跟进', chats);
  element('p', '请求发出后会持续检查该会话；候选人晚些时候发送附件也会显示在这里。', chats, 'hr-hint');
  const attachmentResult = element('div', '', chats, 'hr-result');

  async function requestAttachmentResume() {
    const id = friendId.value.trim();
    if (resumeRequestsInFlight.has(id)) return;
    if (!/^[1-9]\d{0,19}$/.test(id)) {
      element('p', '请先从沟通列表中选择一位候选人。', chatsResult, 'hr-error');
      return;
    }
    const retryUncertain = trackedResumeRequests.get(id)?.state === 'uncertain';
    const warning = retryUncertain
      ? '上次索取结果未确认，可能已经送达。请先核对 BOSS 会话，确定未发送后再重试。\n\n' : '';
    if (!window.confirm(`${warning}确认向「${personName(selectedConversation)}」发送附件简历请求吗？平台会实际发送一条请求。`)) return;
    const generation = sessionGeneration;
    resumeRequestsInFlight.add(id);
    updateResumeRequestButton();
    showAttachmentFeedback('正在准备 BOSS 沟通页面并发送请求，请稍候…');
    try {
      const response = await hrApi('/api/v1/hr/resumes/request', {
        method:'POST', headers:{'Content-Type':'application/json'},
        body:JSON.stringify({friendId:id, ...(retryUncertain ? {retryUncertain:true} : {})})
      });
      if (generation !== sessionGeneration) return;
      if (response?.success !== true || response?.data?.state !== 'waiting'
          || String(response.data.friendId) !== id || !(response.data.requested_at > 0)) {
        throw Error('索取结果未确认，正在读取记录；请先核对 BOSS 会话，避免重复发送。');
      }
      ++resumeLoadSequence;
      trackedResumeRequests.set(id, {...response.data, friend_id:id});
      renderTrackedResumes();
      if (friendId.value === id) showAttachmentFeedback('附件简历请求已发出，已记录为“已索取”，收到后将在下方显示。');
    } catch (error) {
      if (generation !== sessionGeneration) return;
      if (!['waiting','received'].includes(trackedResumeRequests.get(id)?.state)) {
        ++resumeLoadSequence;
        trackedResumeRequests.set(id, {friend_id:id, state:'uncertain',
          requested_at:Math.floor(Date.now()/1000)});
        renderTrackedResumes();
      }
      if (friendId.value === id) showAttachmentFeedback(error.message || '索取结果未确认，请查看官方会话。', true);
    } finally {
      if (generation === sessionGeneration) {
        resumeRequestsInFlight.delete(id);
        await loadTrackedResumes();
        updateResumeRequestButton();
      }
    }
  }
  function showAttachmentFeedback(message, failed = false) {
    attachmentFeedback.className = failed ? 'hr-error' : 'hr-success';
    attachmentFeedback.textContent = message;
    attachmentFeedback.hidden = false;
  }
  function updateResumeRequestButton() {
    for (const [id, node] of resumeRequestBadges) {
      const state = trackedResumeRequests.get(id)?.state;
      node.textContent = state === 'waiting' ? '已索取' : state === 'received' ? '已收简历'
        : state === 'uncertain' ? '索取待核对' : '';
      node.hidden = !node.textContent;
    }
    if (!requestResumeButton || requestResumeButton.dataset.friendId !== friendId.value) return;
    const pending = resumeRequestsInFlight.has(friendId.value);
    const state = trackedResumeRequests.get(friendId.value)?.state;
    requestResumeButton.disabled = pending || state === 'waiting' || state === 'received';
    requestResumeButton.textContent = pending ? '正在索取…'
      : state === 'waiting' ? '已索取，等待附件'
      : state === 'received' ? '已收到附件'
      : state === 'uncertain' ? '重新索取附件简历' : '索取附件简历';
  }

  async function downloadAttachment(friend, mid, action) {
    action.disabled = true;
    try {
      const response = await hrFetch(`/api/v1/hr/resumes/${friend}/${mid}/download`,
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
    const loadSequence = ++resumeLoadSequence;
    try {
      const result = await hrApi('/api/v1/hr/resumes');
      if (generation !== sessionGeneration || loadSequence !== resumeLoadSequence || current !== 'chats'
          || !allowed() || activeWorkspace !== 'hr') return;
      if (!Array.isArray(result?.data)) throw Error('附件状态读取失败，请稍后重试。');
      const items = result.data;
      const previous = trackedResumeRequests.get(friendId.value);
      trackedResumeRequests = new Map(items.map(item => [String(item.friend_id), item]));
      renderTrackedResumes();
      const selected = trackedResumeRequests.get(friendId.value);
      if (previous?.state === 'uncertain' && (selected?.state === 'waiting' || selected?.confirmed_at > 0)) {
        showAttachmentFeedback(`已核对 BOSS 会话，索取成功并已记录${selected.state === 'received' ? '，附件也已收到' : ''}。`);
      }
    } catch (error) {
      if (generation === sessionGeneration && loadSequence === resumeLoadSequence && current === 'chats'
          && allowed() && activeWorkspace === 'hr') {
        renderTrackedResumes();
        element('p', '附件状态暂时读取失败，已保留当前记录，将自动刷新。', attachmentResult, 'hr-error');
      }
    }
  }
  function renderTrackedResumes() {
    attachmentResult.replaceChildren();
    updateResumeRequestButton();
    const items = [...trackedResumeRequests.values()].sort((a,b) => b.requested_at - a.requested_at);
    if (!items.length) {
      element('p', '暂无附件简历请求。', attachmentResult, 'hr-empty');
      return;
    }
    for (const item of items) {
      const card = element('article', '', attachmentResult, 'hr-record');
      const known = conversations.find(row => conversationId(row) === String(item.friend_id))
        || (conversationId(selectedConversation || {}) === String(item.friend_id) ? selectedConversation : null);
      element('strong', known ? personName(known) : '候选人附件简历', card);
      const confirmed = item.state === 'waiting' || item.confirmed_at > 0;
      const state = item.state === 'waiting' ? '已索取 · 等待候选人发送'
        : item.state === 'received' ? `${confirmed ? '已索取 · ' : ''}已收到附件`
        : item.state === 'failed' ? '请求未发送，可重新索取'
        : '索取结果待核对';
      element('p', state, card);
      if (item.requested_at > 0) {
        element('p', `${confirmed ? '索取时间' : '发起时间'}：${formatTime(item.confirmed_at || item.requested_at)}`,
          card, 'hr-hint');
      }
      if (item.state === 'received' && item.updated_at > 0) {
        element('p', `接收时间：${formatTime(item.updated_at)}`, card, 'hr-hint');
      }
      if (item.error) element('p', item.error, card, 'hr-hint');
      if (item.state === 'received' && /^\d+$/.test(item.message_id || '')) {
        button('下载已收到的简历', card,
          event => downloadAttachment(item.friend_id, item.message_id, event.currentTarget))
          .className = 'hr-primary hr-resume-download';
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
  const visibleName = (...values) => values.map(value => scalar(value).trim())
    .find(value => value && !/^[*＊﹡•●·\s_-]+$/u.test(value)
      && !['[REDACTED]','姓名暂未公开'].includes(value)) || '';
  const candidateName = row => visibleName(...[person(row), row].flatMap(info =>
    ['geekName','name','nickname','user.name'].map(key => pick(info,key))));
  const personName = row => candidateName(row) || '候选人';
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
    const avatar = element('span', name === '姓名暂未公开' ? '候' : [...name].slice(0,1).join(''), block, 'hr-avatar');
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
  function navigate(route, context) {
    if(!allowed())return;
    history.pushState(null, '', `${location.pathname}${location.search}#hr-${route}`);
    switchWorkspace('hr');
    enter(route, context);
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
        const response = await hrApi('/api/v1/hr/jobs');
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
    attachmentFeedback.hidden = true;
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
    lastLoginState={state:'complete'};paintConnection(lastLoginState);
  }
  function renderJobs(box, rows) {
    rememberJobs(rows);
    const grid = element('div', '', box, 'hr-card-grid hr-job-grid');
    for (const row of rows) {
      const available = pick(row,'statusDesc','jobStatusDesc') === '开放中';
      const card = element('article', '', grid, 'hr-job-card' + (available ? '' : ' is-unavailable'));
      card.setAttribute('aria-disabled', String(!available));
      const top = element('div', '', card, 'hr-card-heading');
      element('h3', pick(row,'jobName','name','title') || '未命名职位', top);
      badge(pick(row,'statusDesc','jobStatusDesc') || '状态待确认', top);
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
        const footer = element('div', '', card, 'hr-job-footer');
        const actions = element('div', '', footer, 'hr-actions hr-job-actions');
        const source = button(globalThis.createHrSourcing?'AI 寻源':'推荐人才', actions, () => {
          if (!available) return;
          if(sourcing){sourcing.select(row);navigate('talent');return;}
          talentJob.value = jobId(row);
          talentAction = 'recommendations';
          navigate('talent');
          run('recommendations');
        });
        source.className = 'hr-primary';
        const applications = button('查看投递', actions, () => {
          if (!available) return;
          applicationsJob.value=jobId(row);navigate('applications');
        });
        const chats = button('沟通记录', actions, () => {
          if (!available) return;
          chatsJob.value=jobId(row);selectedConversation=null;navigate('chats');
        });
        for (const action of [source, applications, chats]) {
          action.disabled = !available;
          if (!available) action.title = '仅开放中的职位可操作';
        }
        passive?.jobToggle(footer,row);
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
      if(pool)button('加入储备人才库',actions,()=>reserveCandidate(row));
    }
  }
  function reserveCandidate(row){
    const info=person(row);
    const sourceId=pick(info,'encryptGeekId','encGeekId','geekId')||pick(row,'encryptGeekId','encGeekId','geekId')||conversationId(row);
    pool?.add({name:candidateName(row)||'',position:pick(info,'expectPositionName','positionName','expectPosition','position'),
      education:pick(info,'geekDegree','degreeName','degreeDesc'),
      experience:pick(info,'geekWorkYear','workYearDesc','work_years','workYear'),
      city:pick(info,'expectLocationName','locationName','city'),
      company:pick((row.showWorks||info.geekWorks||[])[0],'company'),
      source:'boss',sourceId:sourceId?String(sourceId):''});
  }
  function conversationLayout(box) {
    resumeRequestBadges.clear();
    const layout = element('div', '', box, 'hr-chat-layout');
    const list = element('div', '', layout, 'hr-chat-list');
    list.setAttribute('aria-label', '候选人会话');
    for (const row of conversations) {
      const action = button('', list, () => openConversation(row));
      action.className = 'hr-conversation';
      action.setAttribute('aria-pressed', String(conversationId(row) === friendId.value));
      element('strong', personName(row), action);
      const requestBadge = element('span', '', action, 'hr-badge');
      resumeRequestBadges.set(conversationId(row), requestBadge);
      const preview = pick(row,'last_msg','lastMsg','lastMsgInfo.showText');
      element('p', preview && preview !== '-' ? preview : '点击查看沟通记录', action, 'hr-chat-preview');
      element('small', formatTime(row.last_time || row.updateTime), action);
      if (Number(row.unread) > 0) badge(`${row.unread} 条未读`, action, 'is-attention');
    }
    const detail = element('section', '', layout, 'hr-chat-detail');
    if (selectedConversation) {
      const top = element('div', '', detail, 'hr-chat-header');
      identity(top, personName(selectedConversation), '沟通记录');
      requestResumeButton = button('索取附件简历', top, requestAttachmentResume);
      requestResumeButton.className = 'hr-secondary';
      requestResumeButton.dataset.friendId = friendId.value;
    }
    updateResumeRequestButton();
    renderTrackedResumes();
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
      const recipientId = String(message.to?.uid || '');
      const senderId = String(sender.uid || '');
      const ours = senderId && recipientId
        ? senderId !== friendId.value && recipientId === friendId.value
        : Number(sender.source) === 1;
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
    // The resume endpoint can return "***" while the same candidate's list
    // card still exposes a surname or nickname. Keep that visible identity.
    const name = visibleName(pick(data,'basic.name'), candidateName(selectedCandidate));
    const heading = identity(sheet, name || '姓名暂未公开',
      pick(data,'expectation.position'));
    if (!name || /[*＊﹡•●]/u.test(name)) {
      element('p', 'BOSS 暂未公开完整姓名，按平台可见信息展示。', heading, 'hr-hint');
    }
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
  async function run(action = current, recovered = false) {
    if (!allowed()) return;
    if(action==='jobs'&&jobsRefresh.disabled)return;
    if(action==='jobs')jobsRefresh.disabled=true;
    if (action === 'talent') action = talentAction;
    if (action === 'candidates' || action === 'recommendations') talentAction = action;
    if (action === 'chats') {selectedConversation=null;friendId.value='';}
    const box = resultBoxes[current], ticket = ++sequence;
    box.setAttribute('aria-busy','true');
    let path,loading;
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
      readNotices.get(box)?.remove();
      const retained=box.dataset.hrPath===path&&!!box.dataset.hrSnapshot&&box.children.length>0;
      if(!retained)box.replaceChildren();
      loading=element('p',retained?'正在更新…':'正在读取平台数据…',box,'hr-loading');
      readNotices.set(box,loading);
      loading.setAttribute('role','status');
      const response = await hrApi(path);
      if (ticket !== sequence || activeWorkspace !== 'hr') return;
      if (action === 'overview' && response.data?.error?.code === 'SESSION_DISCONNECTED') {
        showDisconnected();
        return;
      }
      if (action === 'overview' && ['LOGIN_PENDING', 'RECRUITER_VERIFICATION_PENDING']
          .includes(response.data?.error?.code)) {
        box.replaceChildren();
        return;
      }
      const signature=JSON.stringify([action,page,response.data]);
      if(retained&&box.dataset.hrSnapshot===signature){
        loading?.remove();recoveryRead=null;return;
      }
      if(response.data?.ok===false&&retained){
        loading.textContent='本次更新未完成，已保留当前内容。';
        if(!recovered)recoveryRead={section:current,action};
        return;
      }
      const rows = display(box, response.data, action);
      if(response.data?.ok!==false){box.dataset.hrPath=path;box.dataset.hrSnapshot=signature;}
      else{delete box.dataset.hrPath;delete box.dataset.hrSnapshot;}
      if(response.data?.ok===false&&!recovered)recoveryRead={section:current,action};
      else recoveryRead=null;
      pagination(box, action, response.data, rows);
      if (action !== 'overview' || logoutButton.hidden) {
        note.textContent = `最近读取：${new Date().toLocaleTimeString()} · 数据来自 BOSS 直聘`;
      }
    } catch (error) {
      if (ticket !== sequence) return;
      if(box.dataset.hrPath!==path||!box.dataset.hrSnapshot)box.replaceChildren();
      else loading?.remove();
      readNotices.get(box)?.remove();
      readNotices.set(box,element('p', error.message || '读取失败', box, 'hr-error'));
      if(path&&!recovered)recoveryRead={section:current,action};
    } finally {
      if(action==='jobs')jobsRefresh.disabled=false;
      if (ticket === sequence) box.setAttribute('aria-busy','false');
    }
  }
  const onboarding=globalThis.createHrOnboarding?.({root,section,element,button,allowed,hrApi:api,navigate});
  const pool=globalThis.createHrPool?.({root,section,element,button,allowed,navigate,hrApi,
    beginOnboarding:(row,payload,done)=>onboarding?.openPass(row,payload,done)});
  const demands=globalThis.createHrDemands?.({root,section,element,button,allowed,hrApi});
  const departmentManager=globalThis.createHrDepartments?.({root,section,element,button,allowed});
  const passive=globalThis.createHrPassive?.({root,section,element,button,allowed,navigate,hrApi});
  const sourcing=globalThis.createHrSourcing?.({root,panel:talent,element,button,allowed,navigate,hrApi});
  const bi=globalThis.createHrBi?.({root:shortcuts,element,button,allowed,hrApi:api,navigate});
  if(bi){shortcutHeader.hidden=shortcutGrid.hidden=true;}
  if(sourcing){talentForm.hidden=legacyTalentHint.hidden=talentResult.hidden=true;}
  function enter(route, context) {
    if (!allowed()) return;
    pool?.leave();
    onboarding?.leave();bi?.leave();
    demands?.leave();
    departmentManager?.leave();
    passive?.leave();
    sourcing?.leave();
    loginSuccess.hidden = true;
    current = routes.has(route) ? route : 'overview';
    if(!['resume-pool','speeches'].includes(current))globalThis.HeyiHrTransport?.startAutoConnect?.();
    recoveryRead=null;
    clearInterval(resumeTimer);
    resumeTimer = null;
    page = 1;
    ++sequence;
    panels.forEach((panel, key) => panel.hidden = key !== current);
    document.querySelectorAll('[data-hr-route]').forEach(link => {
      const selected=link.dataset.hrRoute === (current==='talent'?'jobs':current);
      link.classList.toggle('active', selected);
      if(selected)link.setAttribute('aria-current','page');
      else link.removeAttribute('aria-current');
    });
    if (['applications','chats',...(!sourcing?['talent']:[])].includes(current) && !jobRecords.length) loadJobOptions();
    const titles={overview:['招聘工作台','从职位出发，让每一次沟通都有进展。'],jobs:['职位管理','从在招职位开始，查看推荐人才和投递。'],talent:['人才寻源','找到适合岗位的人，再开始有价值的沟通。'],applications:['投递申请','及时查看主动投递，跟进合适的候选人。'],chats:['沟通与简历','每一次沟通、每一份简历，在这里继续。'],resume:['在线简历','查看工作、项目与教育经历，了解完整的人才画像。']};
    titles.pool=['储备人才库','按部门归档人才，跟进每一个招聘阶段。'];
    titles.demands=['招聘需求池','梳理岗位需求，生成画像、胜任能力和结构化面试题。'];
    titles.departments=['部门管理','统一部门信息与负责人，供招聘需求直接选用。'];
    titles.onboarding=['入职安排及跟进',''];
    titles['resume-pool']=['简历池','查看简历、人工审批，AI 分析仅供参考。'];
    titles.speeches=['话术管理','统一配置婉拒和常用沟通话术。'];
    pageTitle.textContent=titles[current][0];pageSubtitle.textContent=titles[current][1];
    pageSubtitle.hidden=!titles[current][1];
    if (['resume-pool','speeches'].includes(current)) {
      passive?.enter(current);
    } else if (current === 'departments') {
      departmentManager?.enter();
    } else if (current === 'demands') {
      demands?.enter();
    } else if (current === 'pool') {
      pool?.enter(context);
    } else if (current === 'onboarding') {
      onboarding?.enter();
    } else if (current === 'overview') {
      bi?.enter();
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
    } else if (current === 'talent' && sourcing) {
      sourcing.enter();
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
  globalThis.HrWorkspace = {enter, allowed, beginLogout(){
    globalThis.HeyiHrTransport?.stopAutoConnect?.();
    pool?.reset();
    onboarding?.reset();bi?.reset();
    demands?.reset();
    departmentManager?.reset();
    passive?.reset();
    sourcing?.reset();
    ++sequence;++sessionGeneration;++loginGeneration;
    stopLoginPolling();clearInterval(resumeTimer);resumeTimer=null;
    loginActionInFlight=true;
  },reset(){
    pool?.reset();
    onboarding?.reset();bi?.reset();
    demands?.reset();
    departmentManager?.reset();
    passive?.reset();
    sourcing?.reset();
    globalThis.HeyiHrTransport?.reset();
    ++sequence;
    ++sessionGeneration;
    ++loginGeneration;
    loginPhase = '';
    loginActionInFlight = false;
    stopLoginPolling();
    showLoginProgress();
    loginSuccess.hidden = true;
    clearInterval(resumeTimer);
    resumeTimer = null;
    helperConnected=false;connectionState='idle';lastLoginState={state:'idle'};
    loginPendingSeen = false;
    jobsRequest = null;
    recoveryRead = null;
    conversations = [];
    trackedResumeRequests.clear();
    resumeRequestsInFlight.clear();
    attachmentFeedback.hidden = true;
    selectedConversation = selectedCandidate = null;
    friendId.value = geekId.value = resumeJob.value = securityId.value = '';
    keyword.value = '';
    rememberJobs([]);
    logoutButton.hidden=true;paintConnection(lastLoginState);
    root.querySelectorAll('.hr-result').forEach(box => {
      box.replaceChildren();delete box.dataset.hrPath;delete box.dataset.hrSnapshot;readNotices.delete(box);
    });
  }};
  window.addEventListener('heyi:company-reset', () => HrWorkspace.reset());
})();
