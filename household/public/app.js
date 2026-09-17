'use strict';
(() => {
  const BASE = new URL('./', document.baseURI);
  const $ = selector => document.querySelector(selector);
  const app = $('#app');
  const taskDialog = $('#task-dialog');
  const helpDialog = $('#help-dialog');
  const periods = {morning: {name: '晚间补充', subtitle: '给家一点额外照顾', icon: 'sunset'}, evening: {name: '晚间收拾', subtitle: '把日常慢慢归位', icon: 'sunset'}, bedtime: {name: '睡前检查', subtitle: '留一个清爽的明天', icon: 'moon'}};
  const activePeriods = ['evening', 'bedtime'];
  const weekdays = ['一', '二', '三', '四', '五', '六', '日'];
  const paths = {
    home: '<path d="m3 10 9-7 9 7M5 9v12h14V9M9 21v-7h6v7"/>',
    today: '<rect x="4" y="5" width="16" height="16" rx="3"/><path d="M8 3v4m8-4v4M4 10h16m-11 5 2 2 4-4"/>',
    chart: '<path d="M4 20h17M7 16v-5m5 5V5m5 11V8"/>',
    list: '<rect x="4" y="4" width="16" height="17" rx="3"/><path d="M9 4V2h6v2M8 10h1m3 0h4m-8 5h1m3 0h4"/>',
    settings: '<path d="m9 3-.7 2.3-2.4 1-2.2-.4-1.5 2.6 1.5 1.8V13l-1.5 1.8 1.5 2.6 2.2-.4 2.4 1L9 21h3l.7-2.3 2.4-1 2.2.4 1.5-2.6-1.5-1.8V11l1.5-1.8-1.5-2.6-2.2.4-2.4-1L12 3Z" transform="translate(1.5)"/><circle cx="12" cy="12" r="3"/>',
    sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2m0 16v2M2 12h2m16 0h2M5 5l1.5 1.5m11 11L19 19M5 19l1.5-1.5m11-11L19 5"/>',
    sunset: '<path d="M3 16h18M6 16a6 6 0 0 1 12 0M12 3v3M4 8l2 2m14-2-2 2M5 20h14"/>',
    moon: '<path d="M20.4 14.2A9 9 0 0 1 9.8 3.6 9 9 0 1 0 20.4 14.2Z"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    chevron: '<path d="m8 10 4 4 4-4"/>',
    right: '<path d="m10 7 5 5-5 5"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    close: '<path d="m6 6 12 12M18 6 6 18"/>',
    bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    leaf: '<path d="M20 3C8 2 3 6 4 13c1 7 14 9 16-10ZM4 21l11-11"/>',
    spark: '<path d="m12 3 2.3 6.7L21 12l-6.7 2.3L12 21l-2.3-6.7L3 12l6.7-2.3Z"/>',
    download: '<path d="M12 3v12m-5-5 5 5 5-5M4 16v5h16v-5"/>',
    phone: '<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M10 18h4M10 5h4"/>',
    refresh: '<path d="M20 7v5h-5M4 17v-5h5M6 7a7 7 0 0 1 12-1l2 6M4 12l2 6a7 7 0 0 0 12-1"/>',
    wifi: '<path d="m3 3 18 18M2 8a17 17 0 0 1 3-2m4-1a17 17 0 0 1 13 3M5 12a12 12 0 0 1 4-2m5 0a12 12 0 0 1 5 2M8.5 15.5a5 5 0 0 1 5.5-.7M12 19h.01"/>',
    minus: '<path d="M6 12h12"/>',
    lock: '<rect x="5" y="10" width="14" height="11" rx="2"/><path d="M8 10V7a4 4 0 0 1 8 0v3M12 14v3"/>',
    edit: '<path d="m15 4 5 5M4 20l5-1L21 7l-5-5L4 14Z"/>'
  };
  const icon = (name, size = 20) => `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.home}</svg>`;
  const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[char]));
  let state = null, page = 'today', essentialsOnly = false, browsingPast = false, lastError = '', toastTimer;
  let saving = false, editorTaskId = null, deferredInstall = null;
  let stateRequest = 0, mutationVersion = 0;
  let expanded = new Set();
  let currentSubscription = null;
  let swRegistration = null;
  const swPromise = ('serviceWorker' in navigator && window.isSecureContext)
    ? navigator.serviceWorker.register(new URL('sw.js', BASE), {scope: BASE.pathname}).then(reg => (swRegistration = reg)).catch(() => null)
    : Promise.resolve(null);

  class ApiError extends Error { constructor(message, status) { super(message); this.status = status; } }
  async function api(path, method = 'GET', body) {
    if (!navigator.onLine) throw new ApiError('当前离线，无法保存或读取。请连接网络后重试。', 0);
    if (method !== 'GET') mutationVersion += 1;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 20000);
    try {
      const response = await fetch(new URL(path, BASE), {
        method, credentials: 'same-origin', cache: 'no-store', signal: controller.signal,
        headers: method === 'GET' ? {'Accept': 'application/json'} : {'Content-Type': 'application/json', 'X-Requested-With': 'HomeCare', 'Accept': 'application/json'},
        ...(body !== undefined ? {body: JSON.stringify(body)} : {})
      });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new ApiError(result.error || '暂时无法完成，请稍后重试。', response.status);
      return result;
    } catch (error) {
      if (error instanceof ApiError) throw error;
      throw new ApiError(method === 'GET' ? '无法连接服务器，请检查网络后重试。' : '未能确认保存结果，请联网刷新后重试。', 0);
    } finally { clearTimeout(timer); }
  }

  function toast(message, isError = false) {
    clearTimeout(toastTimer);
    $('#toast').textContent = message;
    $('#toast').className = `toast${isError ? ' error' : ''}`;
    $('#toast').hidden = false;
    toastTimer = setTimeout(() => { $('#toast').hidden = true; }, isError ? 7000 : 3500);
  }
  function unauthorized(error) {
    if (error.status !== 401) return false;
    state = null;
    taskDialog.close();
    helpDialog.close();
    renderLogin('请重新输入密码，继续照顾你的家。');
    return true;
  }
  function parseDate(date) { return new Date(`${date}T12:00:00Z`); }
  function dateLabel(date, options = {month: 'long', day: 'numeric'}) { return new Intl.DateTimeFormat('zh-CN', {...options, timeZone: 'UTC'}).format(parseDate(date)); }
  function dueTasks() {
    if (!state) return [];
    const day = (parseDate(state.date).getUTCDay() + 6) % 7;
    return state.tasks.filter(task => task.enabled !== false && (task.frequency === 'daily' || task.weekdays.includes(day)));
  }
  function progressFor(task) { return state.progress[task.id] || {steps: [], skipped: false}; }
  function isDone(task) { const p = progressFor(task); return !p.skipped && task.steps.length > 0 && task.steps.every(step => p.steps.includes(step.id)); }
  function totals(tasks = dueTasks()) {
    const active = tasks.filter(task => !progressFor(task).skipped);
    const done = active.filter(isDone);
    return {total: active.length, done: done.length, all: tasks.length, skipped: tasks.length - active.length, minutes: done.reduce((sum, task) => sum + task.minutes, 0), remaining: active.filter(task => !isDone(task)).reduce((sum, task) => sum + task.minutes, 0)};
  }
  const brand = () => `<div class="brand"><img src="${new URL('icons/icon.svg', BASE).href}" alt="" width="38" height="38"><div class="brand-name">拾间<span class="brand-sub">A LITTLE, EVERY DAY</span></div></div>`;

  function renderLogin(error = '') {
    app.innerHTML = `<main class="login-wrap">${brand()}<p class="eyebrow">给生活，留一点秩序</p><h1>每天照顾好一点。<br>家，会慢慢变轻。</h1><p class="intro">把家务拆成做得完的小事。<br>什么时候做、做到什么程度，都帮你记着。</p><form id="login-form" class="login-form"><div class="field"><label for="password">家务本密码</label><input id="password" name="password" type="password" autocomplete="current-password" placeholder="输入密码，回到你的家" required maxlength="512"></div><p id="login-error" class="form-error" ${error ? '' : 'hidden'}>${esc(error)}</p><button class="button full" type="submit">打开我的家务本 ${icon('right', 16)}</button></form><div class="login-art"><span>${icon('check', 14)} 分步完成</span><span>·</span><span>${icon('bell', 14)} 到点提醒</span><span>·</span><span>${icon('chart', 14)} 看见进步</span></div><p class="login-note">${icon('lock', 12)} 这是你家的私人空间，登录后才会显示家务和记录。<br>首次使用的密码由部署时设置。</p></main>`;
    $('#login-form').addEventListener('submit', async event => {
      event.preventDefault();
      const form = event.currentTarget, button = form.querySelector('button');
      button.disabled = true; button.textContent = '正在打开…'; $('#login-error').hidden = true;
      try {
        await api('api/login', 'POST', {password: form.elements.password.value});
        form.elements.password.value = '';
        browsingPast = false;
        await loadState(true);
      } catch (err) {
        $('#login-error').textContent = err.message; $('#login-error').hidden = false;
        button.disabled = false; button.innerHTML = `打开我的家务本 ${icon('right', 16)}`;
      }
    });
  }

  async function loadState(throwErrors = false, selectedDate) {
    const request = ++stateRequest, version = mutationVersion;
    try {
      const date = selectedDate ?? (browsingPast ? state?.date : undefined);
      const next = await api(`api/state${date ? `?date=${encodeURIComponent(date)}` : ''}`);
      if (request !== stateRequest || version !== mutationVersion) return;
      state = next; lastError = '';
      browsingPast = next.date !== next.today;
      render();
      updatePushState();
    } catch (error) {
      if (request !== stateRequest || version !== mutationVersion) return;
      if (throwErrors) throw error;
      if (!unauthorized(error)) {
        lastError = error.message;
        if (state) render(); else renderLogin(error.message);
      }
    }
  }

  function render() {
    if (!state) return;
    const pages = {today: renderToday, history: renderHistory, tasks: renderTasks, settings: renderSettings};
    app.innerHTML = `<div class="app-wrap"><header class="topbar">${brand()}<span class="home-label">${icon('home', 15)} ${esc(state.settings.householdName)}</span></header>${!navigator.onLine || lastError ? `<div class="status-banner" role="status">${icon('wifi', 17)}<span>${esc(!navigator.onLine ? '当前离线 · 显示上次读取的内容，暂时无法保存' : lastError)}</span><button data-action="refresh">重试</button></div>` : ''}<main id="main-content">${pages[page]()}</main></div><div class="nav-shell"><nav class="bottom-nav" aria-label="主导航">${[['today','today','今日'],['history','chart','记录'],['tasks','list','家务'],['settings','settings','设置']].map(([key, symbol, name]) => `<button data-nav="${key}" class="${page === key ? 'active' : ''}" ${page === key ? 'aria-current="page"' : ''}>${icon(symbol, 21)}<span>${name}</span></button>`).join('')}</nav></div>`;
    bindEvents();
  }

  function pageHead(title, subtitle, tag = '') {
    return `<div class="page-head"><div><p class="eyebrow">${esc(dateLabel(state.date, {year:'numeric', month:'long', day:'numeric'}))} · ${esc(dateLabel(state.date, {weekday:'long'}))}</p><h1>${title}</h1>${subtitle ? `<p class="sub">${subtitle}</p>` : ''}</div>${tag ? `<span class="date-badge">${tag}</span>` : ''}</div>`;
  }
  function renderToday() {
    const all = dueTasks(), numbers = totals(all), shown = essentialsOnly ? all.filter(task => task.essential) : all;
    const essentialNumbers = totals(all.filter(task => task.essential));
    const percentage = numbers.total ? Math.round(numbers.done / numbers.total * 100) : 0;
    const headline = numbers.total && numbers.done === numbers.total ? '今天，照顾得很好。' : numbers.done ? '家，在一点点变好。' : '从一件小事开始。';
    const heroNote = numbers.total && numbers.done === numbers.total ? '收好这份轻松，剩下的时间留给自己。' : numbers.remaining ? `还有约 ${numbers.remaining} 分钟，分几次慢慢来。` : '今天没有待办，给自己留一点空白。';
    return `${pageHead(browsingPast ? '把那天补完整' : '今晚的小事', '下班后慢慢收拾，做完一步就勾一步。', browsingPast ? '补记中' : '慢慢来，也很好')}${browsingPast ? `<div class="status-banner past-banner"><span>正在查看 ${esc(dateLabel(state.date))} 的清单与记录</span><button data-action="today">回到今天</button></div>` : ''}<div class="today-grid"><section class="overview" aria-label="当天进度"><div class="hero"><div class="hero-copy"><p class="hero-label">${icon('leaf', 14)} 给家的日常照顾</p><h2>${headline}</h2><p>${heroNote}</p></div><div class="progress-ring" style="--progress:${percentage}%" role="img" aria-label="完成 ${numbers.done} 项，共 ${numbers.total} 项"><div class="progress-core"><strong>${numbers.done}<span style="font-size:15px;margin:0 0 0 3px">/ ${numbers.total}</span></strong><span>件小事已完成</span></div></div></div><div class="stats"><div class="stat"><strong>${numbers.done}</strong><small>项</small><p>已经做好</p></div><div class="stat"><strong>${numbers.remaining}</strong><small>分钟</small><p>预计还需</p></div><div class="stat"><strong>${essentialNumbers.done}<small>/ ${essentialNumbers.total}</small></strong><p>保底小事</p></div></div><p class="end-note">${icon('spark', 15)} 少一点堆积，多一点舒心。<br>每一个勾，都是在照顾生活。</p></section><section class="day-checklist" aria-label="家务清单"><div class="section-heading"><h2>${browsingPast ? '当天清单' : '今晚清单'}</h2><div class="segmented" aria-label="清单范围"><button data-filter="all" class="${!essentialsOnly ? 'active' : ''}" aria-pressed="${!essentialsOnly}">全部小事</button><button data-filter="essential" class="${essentialsOnly ? 'active' : ''}" aria-pressed="${essentialsOnly}">只做保底</button></div></div>${essentialsOnly ? '<p class="hint">精力有限时，先做好这些。其他家务仍会保留待办，顶部显示全部进度。</p>' : ''}${shown.length ? Object.entries(periods).map(([key, period]) => {
      const tasks = shown.filter(task => task.period === key);
      if (!tasks.length) return '';
      const count = totals(tasks);
      return `<section class="period period-${key}"><div class="period-head"><span class="period-icon">${icon(period.icon, 16)}</span><h3>${period.name}</h3><time>${esc(state.settings.reminderTimes[key])}</time><span class="period-count">${count.done} / ${count.total} 项</span></div>${tasks.map(renderTask).join('')}</section>`;
    }).join('') : `<div class="empty">${icon('leaf', 28)}<h3>${essentialsOnly ? '还没有保底小事' : '今天的清单很轻松'}</h3><p>${essentialsOnly ? '在家务设置里，把最重要的事设为保底。' : '可以新增每天要做的事，或安排每周的清洁。'}</p><button class="button secondary small" data-nav="tasks">整理我的家务</button></div>`}${numbers.skipped ? `<p class="end-note">${numbers.skipped} 项标记为今天不适用，未计入完成数和待办总数。</p>` : ''}<p class="end-note">${icon('check', 13)} 每一步都会在联网时保存到你的家务本</p></section></div>`;
  }

  function renderTask(task) {
    const progress = progressFor(task), done = isDone(task), skipped = progress.skipped;
    const completeSteps = task.steps.filter(step => progress.steps.includes(step.id)).length;
    return `<details class="task-card ${done ? 'is-done' : ''} ${skipped ? 'is-skipped' : ''}" data-task-card="${esc(task.id)}" ${expanded.has(task.id) ? 'open' : ''}><summary class="task-summary"><span class="task-marker">${done ? icon('check', 15) : skipped ? icon('minus', 13) : ''}</span><div class="task-main"><div class="task-title">${esc(task.title)}</div><div class="task-meta"><span>${esc(task.area || '家里')}</span><span class="meta-dot"></span><span>${task.minutes} 分钟</span><span class="meta-dot"></span><span>${skipped ? '今天不适用' : `${completeSteps} / ${task.steps.length} 步`}</span>${task.essential ? '<span class="essential-dot">保底</span>' : ''}</div></div><span class="task-chevron">${icon('chevron', 16)}</span></summary><div class="task-detail"><p class="detail-caption">全部步骤做好，才算完成这件小事</p>${skipped ? '<p class="skipped-note">这件事今天不用做。已有步骤记录已保留，点下方可以恢复。</p>' : ''}${task.steps.map(step => `<label class="step"><input type="checkbox" data-step="${esc(step.id)}" data-task="${esc(task.id)}" ${progress.steps.includes(step.id) ? 'checked' : ''} ${skipped || !navigator.onLine ? 'disabled' : ''}><span>${esc(step.text)}</span></label>`).join('')}<div class="task-actions"><button data-skip="${esc(task.id)}" ${!navigator.onLine ? 'disabled' : ''}>${skipped ? '恢复为待办' : '今天不适用'}</button><button data-edit="${esc(task.id)}">${icon('edit', 12)} 编辑细节</button></div></div></details>`;
  }

  function renderHistory() {
    const history = [...state.history].sort((a,b) => a.date.localeCompare(b.date));
    const completed = history.reduce((sum, day) => sum + day.done, 0), minutes = history.reduce((sum, day) => sum + day.minutes, 0);
    return `${pageHead('认真生活的痕迹', '小事有记录，进步就看得见。', '最近 7 天')}<div class="history-grid"><section class="panel history-summary"><h2>${icon('leaf', 18)} 一周的小小积累</h2><div class="weekly-total"><div><strong>${completed}<span>件小事</span></strong><p>每一步，都算数</p></div><div class="weekly-time"><strong>${minutes}<span>分钟</span></strong><p>完成家务的预计用时</p></div></div><div class="chart" role="img" aria-label="过去七天每天家务完成比例">${history.map(day => `<div class="chart-day ${day.date === state.today ? 'current' : ''}"><div class="chart-bar-space"><span class="chart-value">${day.done}/${day.total}</span><div class="chart-bar" style="height:${day.total ? Math.round(day.done/day.total*100) : 0}%"></div></div><span class="chart-label">${day.date === state.today ? '今天' : `周${weekdays[(parseDate(day.date).getUTCDay()+6)%7]}`}</span></div>`).join('')}</div><p class="panel-description" style="margin:20px 0 0">按实际勾选记录统计；分钟数是家务预估用时。未做完的步骤不算完成一项。</p></section><section class="panel"><h2>每天都留下一点</h2><p class="panel-description">按当天的真实完成情况统计，已归档家务的过去记录会保留。</p>${[...history].reverse().map(day => `<div class="record-row"><span class="record-date">${day.date === state.today ? '今天' : esc(dateLabel(day.date))}<small>${esc(dateLabel(day.date, {weekday:'long'}))} · ${day.minutes} 分钟</small></span><span class="record-track"><span style="width:${day.total ? Math.round(day.done/day.total*100) : 0}%"></span></span><span class="record-rate">${day.done}<small>/ ${day.total} 项</small></span></div>`).join('')}</section></div><p class="end-note">不比较，不补满。真实的记录，才知道哪里需要一点帮助。</p>`;
  }

  function frequencyLabel(task) {
    if (task.frequency === 'daily') return '每天';
    return `每周${[...task.weekdays].sort((a,b)=>a-b).map(day => weekdays[day]).join('、')}`;
  }
  function renderTasks() {
    return `${pageHead('把家务安排明白', '写清楚细节，下次就不用靠记忆。', `${state.tasks.length} 件小事`)}<button class="library-add" data-action="add-task">${icon('plus', 17)} 添加一件小事</button><section class="panel"><div class="library-list">${state.tasks.length ? state.tasks.map(task => `<button class="task-library-row" data-edit="${esc(task.id)}"><span class="library-icon">${icon(periods[task.period]?.icon || 'home', 19)}</span><span class="library-main"><strong>${esc(task.title)} ${task.essential ? '<span class="essential-dot">保底</span>' : ''}</strong><p>${esc(task.area || '家里')} · ${esc(frequencyLabel(task))} · ${periods[task.period]?.name || ''}<br>${task.steps.length} 个具体步骤 · 约 ${task.minutes} 分钟</p></span>${icon('right', 15)}</button>`).join('') : `<div class="empty">${icon('home',28)}<h3>从最容易忘的一件事开始</h3><p>给它安排时间，写下步骤，交给清单记住。</p><button class="button secondary small" data-action="add-task">添加第一件家务</button></div>`}</div></section><p class="hint">把「打扫厨房」拆成「收好台面杂物、擦净油渍、冲洗并擦干水槽」。越具体，越容易做好。</p><p class="end-note">每天重复或按星期安排；归档家务会保留过去的完成记录。</p>`;
  }

  function renderSettings() {
    const s = state.settings;
    return `${pageHead('按你的节奏来', '让提醒顺手，让家务少一点负担。')}<div class="settings-grid"><div class="settings-col"><section class="panel"><h2>${icon('home', 18)} 家的日常</h2><p class="panel-description">每天的清单和提醒，按这里的时区计算。</p><form id="settings-form"><div class="field"><label for="household-name">家的名字</label><input id="household-name" name="householdName" value="${esc(s.householdName)}" required maxlength="40"></div><div class="field"><label for="timezone">日常所在时区</label><select id="timezone" name="timezone">${[...new Set(['Asia/Shanghai','Asia/Tokyo','Asia/Hong_Kong','Asia/Taipei','Asia/Singapore','America/Los_Angeles','America/New_York','Europe/London','Australia/Sydney','UTC',s.timezone])].map(zone=>`<option value="${esc(zone)}" ${zone === s.timezone ? 'selected' : ''}>${esc({'Asia/Shanghai':'中国 · 上海 / 北京','Asia/Tokyo':'日本 · 东京','Asia/Hong_Kong':'中国 · 香港','Asia/Taipei':'中国 · 台北','Asia/Singapore':'新加坡','America/Los_Angeles':'美国 · 洛杉矶','America/New_York':'美国 · 纽约','Europe/London':'英国 · 伦敦','Australia/Sydney':'澳大利亚 · 悉尼','UTC':'协调世界时 · UTC'}[zone] || zone)}</option>`).join('')}</select><small>按你的生活地点选择，和服务器在哪里无关。</small></div><div class="time-row">${activePeriods.map(key => { const p = periods[key]; return `<div class="field"><label for="time-${key}">${icon(p.icon, 14)} ${p.name}</label><input id="time-${key}" name="${key}" type="time" value="${esc(s.reminderTimes[key])}" required></div>`; }).join('')}</div><button type="submit" class="button full">保存我的安排</button></form></section><section class="panel"><h2>${icon('bell',18)} 到点，轻轻提醒</h2><p class="panel-description">对应时段还有未做家务时，才会发送提醒。即使关掉页面，也能通过系统通知收到。</p><div id="push-status" class="push-status">正在检查这台手机的通知状态…</div><div class="push-buttons"><button class="button small" data-action="enable-push">${icon('bell',15)} 开启手机通知</button><button class="button secondary small" data-action="test-push" ${!s.remindersEnabled ? 'disabled' : ''}>测试提醒</button>${s.remindersEnabled ? '<button class="text-button" data-action="disable-push">关闭提醒</button>' : ''}</div><p class="panel-description" style="margin:16px 0 0">iPhone / iPad：需要 iOS 16.4 或更新版本，先用 Safari 添加到主屏幕，再从主屏幕打开并允许通知。系统专注模式可能会静音。</p></section></div><div class="settings-col">${renderEmailSettings()}<section class="panel"><h2>${icon('phone',18)} 放到顺手的位置</h2><p class="panel-description">每天少找一次，更容易养成习惯。</p><div class="settings-actions"><button data-action="install"><span>${icon('phone',20)}</span><span class="action-text">添加到手机主屏幕<small>像一个 App 一样打开拾间</small></span>${icon('right',17)}</button><button data-action="calendar"><span>${icon('today',20)}</span><span class="action-text">下载日历提醒<small>将每天 2 个晚间时段导入系统日历</small></span>${icon('download',17)}</button></div></section><section class="panel"><h2>${icon('lock',18)} 记录留在自己家</h2><p class="panel-description">家务与勾选记录保存在你的服务器上。换手机登录，也能接着用。</p><div class="settings-actions"><button data-action="export"><span>${icon('download',20)}</span><span class="action-text">导出家务与记录<small>下载 JSON 备份，保留自己的数据</small></span>${icon('right',17)}</button></div></section><p class="hint">日历提醒每天固定响铃，不会随完成情况取消。修改时间后需重新导入，建议先删除旧提醒，避免重复。</p><button class="logout" data-action="logout">退出这个家务本</button><p class="end-note">拾间 · A LITTLE, EVERY DAY<br>把家收拾好，也把自己照顾好。</p></div></div>`;
  }

  function renderEmailSettings() {
    const s = state.settings;
    return `<section class="panel"><h2>${icon('bell',18)} 未完成邮件提醒</h2><p class="panel-description">晚上还有家务没做完时，发一封邮件帮你记起。当天已全部完成，就不打扰。</p><form id="email-form"><div class="field"><label for="email-to">收件邮箱</label><input id="email-to" name="emailTo" type="email" autocomplete="email" value="${esc(s.emailTo || '')}" placeholder="填写常用邮箱" maxlength="254"><small>邮箱和时间修改后，请先保存再发送测试邮件。</small></div><div class="field"><label for="email-time">每天检查时间</label><input id="email-time" name="emailTime" type="time" value="${esc(s.emailTime || '23:00')}" required><small>使用上方生活时区，每天最多发送一封未完成提醒。</small></div><label class="switch-line"><span>开启邮件提醒<small>和手机推送分别设置，不需要安装 App。</small></span><input type="checkbox" name="emailEnabled" ${s.emailEnabled ? 'checked' : ''}></label>${state.email?.supported ? '' : '<p class="push-status">邮件服务尚未就绪，已保存的邮箱和时间会保留。</p>'}<div class="push-buttons"><button class="button small" type="submit">保存邮件提醒</button><button class="button secondary small" type="button" data-action="test-email" ${!state.email?.supported ? 'disabled' : ''}>发送测试邮件</button></div></form></section>`;
  }
  async function saveEmailSettings(event) {
    event.preventDefault();
    const form = event.currentTarget, button = form.querySelector('[type=submit]');
    const emailTo = form.elements.emailTo.value.trim(), emailEnabled = form.elements.emailEnabled.checked;
    if (emailEnabled && !emailTo) { toast('请填写收件邮箱，再开启邮件提醒。', true); form.elements.emailTo.focus(); return; }
    button.disabled = true; button.textContent = '正在保存…';
    try {
      const result = await api('api/settings', 'PUT', {...state.settings,emailTo,emailEnabled,emailTime:form.elements.emailTime.value});
      state.settings = result.settings;
      await loadState(); toast(emailEnabled ? '邮件提醒已保存。' : '邮件设置已保存，自动提醒已关闭。');
    } catch (error) { if (!unauthorized(error)) { toast(error.message,true);button.disabled=false;button.textContent='保存邮件提醒'; } }
  }
  function bindEvents() {
    app.querySelectorAll('[data-nav]').forEach(button => button.addEventListener('click', () => {
      page = button.dataset.nav; browsingPast = false; render(); if (page === 'settings') updatePushState(); window.scrollTo({top:0,behavior:'auto'}); if (!saving) loadState();
    }));
    app.querySelectorAll('[data-filter]').forEach(button => button.addEventListener('click', () => { essentialsOnly = button.dataset.filter === 'essential'; render(); }));
    app.querySelectorAll('[data-task-card]').forEach(detail => detail.addEventListener('toggle', () => { if (detail.open) expanded.add(detail.dataset.taskCard); else expanded.delete(detail.dataset.taskCard); }));
    app.querySelectorAll('[data-step]').forEach(input => input.addEventListener('change', () => saveStep(input)));
    app.querySelectorAll('[data-skip]').forEach(button => button.addEventListener('click', () => saveSkip(button)));
    app.querySelectorAll('[data-edit]').forEach(button => button.addEventListener('click', () => openEditor(button.dataset.edit)));
    app.querySelectorAll('[data-action]').forEach(button => button.addEventListener('click', () => action(button.dataset.action, button)));
    $('#settings-form')?.addEventListener('submit', saveSettings);
    $('#email-form')?.addEventListener('submit', saveEmailSettings);
  }
  async function saveStep(input) {
    const task = state.tasks.find(task => task.id === input.dataset.task), stepId = input.dataset.step;
    const checked = input.checked;
    if (saving) { input.checked = !checked; toast('正在保存上一步，请稍等。'); return; }
    saving = true; input.disabled = true;
    try {
      await api('api/progress', 'PUT', {date:state.date, taskId:task.id, stepId, done:checked});
      const p = progressFor(task);
      state.progress[task.id] = {steps:checked ? [...new Set([...p.steps, stepId])] : p.steps.filter(id => id !== stepId), skipped: checked ? false : p.skipped};
      if (isDone(task)) toast('这件小事做好了，真轻松。');
      render();
      await loadState();
    } catch (error) { if (!unauthorized(error)) { render(); toast(error.message, true); } }
    finally { saving = false; }
  }
  async function saveSkip(button) {
    if (saving) return;
    const task = state.tasks.find(task => task.id === button.dataset.skip), skipped = !progressFor(task).skipped;
    saving = true; button.disabled = true;
    try {
      await api('api/skip','PUT',{date:state.date,taskId:task.id,skipped});
      state.progress[task.id] = {...progressFor(task),skipped};
      render(); toast(skipped ? '已标记为今天不适用，不计入完成数。' : '已恢复为待办。'); await loadState();
    } catch(error) { if (!unauthorized(error)) { render(); toast(error.message,true); } }
    finally { saving = false; }
  }
  async function saveSettings(event) {
    event.preventDefault();
    const form = event.currentTarget, button = form.querySelector('button[type=submit]');
    const body = {...state.settings,householdName:form.elements.householdName.value.trim(),timezone:form.elements.timezone.value,reminderTimes:{...state.settings.reminderTimes,...Object.fromEntries(activePeriods.map(key=>[key,form.elements[key].value]))}};
    button.disabled = true; button.textContent = '正在保存…';
    try {
      const result = await api('api/settings','PUT',body); state.settings = result.settings;
      browsingPast = false; await loadState(); toast('新的安排已保存。');
    } catch(error) { if (!unauthorized(error)) { toast(error.message,true); button.disabled=false; button.textContent='保存我的安排'; } }
  }

  function openEditor(id) {
    editorTaskId = id || null;
    const task = id ? state.tasks.find(task=>task.id===id) : {title:'',area:'',period:'evening',minutes:5,frequency:'daily',weekdays:[0,1,2,3,4,5,6],essential:false,steps:[]};
    if (!task) return;
    taskDialog.innerHTML = `<div class="dialog-head"><h2 id="task-dialog-title">${id ? '把细节写清楚' : '添加一件小事'}</h2><button class="icon-button" data-close aria-label="关闭">${icon('close',18)}</button></div><p class="dialog-help">写到「看一眼就知道怎么做」，更容易坚持。</p><form id="task-form"><div class="field"><label for="task-title">这件家务叫什么</label><input id="task-title" name="title" value="${esc(task.title)}" placeholder="例如：收拾厨房" required maxlength="80"></div><div class="field-row"><div class="field"><label for="task-area">在哪里做</label><input id="task-area" name="area" value="${esc(task.area)}" placeholder="例如：厨房" required maxlength="30"></div><div class="field"><label for="task-minutes">大约几分钟</label><input id="task-minutes" name="minutes" type="number" value="${task.minutes}" min="1" max="180" step="1" required inputmode="numeric"></div></div><div class="field-row"><div class="field"><label for="task-period">安排在什么时候</label><select id="task-period" name="period">${activePeriods.map(key=>`<option value="${key}" ${task.period===key?'selected':''}>${periods[key].name}</option>`).join('')}</select></div><div class="field"><label for="task-frequency">多久做一次</label><select id="task-frequency" name="frequency"><option value="daily" ${task.frequency==='daily'?'selected':''}>每天</option><option value="weekly" ${task.frequency==='weekly'?'selected':''}>每周指定日期</option></select></div></div><div id="weekday-picker" ${task.frequency==='daily'?'hidden':''}><p class="field-label">选择星期</p><div class="weekdays">${weekdays.map((label,i)=>`<label class="weekday"><input type="checkbox" name="weekday" value="${i}" ${task.weekdays.includes(i)?'checked':''}><span>${label}</span></label>`).join('')}</div></div><div class="field"><label for="task-steps">具体要做到什么程度</label><textarea id="task-steps" name="steps" placeholder="收好台面上的杂物&#10;用清洁布擦净台面油渍&#10;清理水槽滤网，冲洗并擦干水槽" required maxlength="5000">${esc(task.steps.map(step=>step.text).join('\n'))}</textarea><small>每行一个步骤，最多 20 步。所有步骤勾选完成，才算做好这件家务。</small></div><label class="switch-line"><span>设为保底小事<small>忙或累的时候，优先做好这一件。</small></span><input type="checkbox" name="essential" ${task.essential?'checked':''}></label><p id="task-error" class="form-error" hidden></p><div class="dialog-footer"><button type="button" class="button secondary" data-close>先放一放</button><button type="submit" class="button">${id?'保存修改':'加入我的清单'}</button></div>${id?'<button type="button" class="archive-button" id="archive-task">归档这件家务</button>':''}</form>`;
    taskDialog.querySelectorAll('[data-close]').forEach(button=>button.addEventListener('click',()=>taskDialog.close()));
    $('#task-frequency').addEventListener('change',event=>{$('#weekday-picker').hidden=event.target.value==='daily';});
    $('#task-form').addEventListener('submit',submitTask);
    $('#archive-task')?.addEventListener('click',archiveTask);
    taskDialog.showModal();
  }
  async function submitTask(event) {
    event.preventDefault();
    const form=event.currentTarget, values=form.elements;
    const steps=values.steps.value.split('\n').map(s=>s.trim()).filter(Boolean);
    const days=[...form.querySelectorAll('[name=weekday]:checked')].map(input=>Number(input.value));
    const error=$('#task-error'), button=form.querySelector('[type=submit]');
    error.hidden=true;
    if(!steps.length || steps.length>20 || steps.some(step=>step.length>200)) {error.textContent='请填写 1–20 个步骤，每步不超过 200 字。';error.hidden=false;return;}
    if(values.frequency.value==='weekly'&&!days.length){error.textContent='每周家务至少要选择一天。';error.hidden=false;return;}
    if(!values.title.value.trim()||!values.area.value.trim()){error.textContent='家务名称和地点不能为空。';error.hidden=false;return;}
    const body={title:values.title.value.trim(),area:values.area.value.trim(),period:values.period.value,minutes:Number(values.minutes.value),frequency:values.frequency.value,weekdays:days,essential:values.essential.checked,steps};
    button.disabled=true;button.textContent='正在保存…';
    try{
      await api(editorTaskId?`api/tasks/${encodeURIComponent(editorTaskId)}`:'api/tasks',editorTaskId?'PUT':'POST',body);
      taskDialog.close();toast(editorTaskId?'家务细节已更新。':'新家务已加入清单。');await loadState();
    }catch(err){if(!unauthorized(err)){error.textContent=err.message;error.hidden=false;button.disabled=false;button.textContent=editorTaskId?'保存修改':'加入我的清单';}}
  }
  async function archiveTask() {
    const button=$('#archive-task');
    if(button.dataset.confirm!=='yes'){button.dataset.confirm='yes';button.textContent='再点一次确认归档 · 过去记录会保留';return;}
    button.disabled=true;
    try{await api(`api/tasks/${encodeURIComponent(editorTaskId)}`,'DELETE',{});taskDialog.close();toast('已归档，过去的记录仍然保留。');await loadState();}
    catch(error){if(!unauthorized(error)){toast(error.message,true);button.disabled=false;}}
  }

  async function readyWorker() {
    const reg=await swPromise;
    if(!reg) throw new Error('通知服务暂时无法启用，请检查网络并重新打开页面。');
    return Promise.race([navigator.serviceWorker.ready,new Promise((_,reject)=>setTimeout(()=>reject(new Error('通知服务还未准备好，请稍后重试。')),15000))]);
  }
  async function updatePushState() {
    let status='';
    const standalone=window.matchMedia('(display-mode: standalone)').matches||navigator.standalone===true;
    const ios=/iPad|iPhone|iPod/.test(navigator.userAgent)||(navigator.platform==='MacIntel'&&navigator.maxTouchPoints>1);
    if(!window.isSecureContext) status='需要通过 HTTPS 打开，才能使用手机通知。日历提醒仍可下载。';
    else if(ios&&!standalone) status='iPhone 请先添加到主屏幕，再从桌面图标打开，才能开启通知。';
    else if(!('Notification'in window)||!('PushManager'in window)) status='当前浏览器不支持推送通知，可以使用下方的日历提醒。';
    else if(!state?.push.supported) status='服务器通知服务暂未就绪，可以先下载日历提醒。';
    else if(Notification.permission==='denied') status='通知权限已关闭。请到系统或浏览器设置中允许拾间通知，再回来开启。';
    else {
      try{const reg=await swPromise;currentSubscription=reg?await reg.pushManager.getSubscription():null;}catch(_){currentSubscription=null;}
      if(!state)return;
      status=state.settings.remindersEnabled&&currentSubscription?'这台设备已连接通知 · 按你设定的两个晚间时段提醒未完成家务。':currentSubscription?'这台设备已连接，提醒目前关闭。点「开启手机通知」恢复。':state.settings.remindersEnabled?'家务本已开启提醒，这台设备还未连接。点下方按钮接收通知。':'提醒还未开启。开启后，会先向你申请这台设备的通知权限。';
    }
    if($('#push-status'))$('#push-status').textContent=status;
  }
  function applicationKey(base64){const padded=base64+'='.repeat((4-base64.length%4)%4);const raw=atob(padded.replace(/-/g,'+').replace(/_/g,'/'));return Uint8Array.from(raw,c=>c.charCodeAt(0));}
  async function enablePush(button) {
    const ios=/iPad|iPhone|iPod/.test(navigator.userAgent)||(navigator.platform==='MacIntel'&&navigator.maxTouchPoints>1);
    const standalone=window.matchMedia('(display-mode: standalone)').matches||navigator.standalone===true;
    if(ios&&!standalone){showInstall();return;}
    if(!window.isSecureContext||!('Notification'in window)||!('PushManager'in window)){toast('当前环境不支持手机通知，请使用日历提醒。',true);return;}
    if(!state.push.supported||!state.push.publicKey){toast('服务器通知服务暂未就绪，可以先下载日历提醒。',true);return;}
    button.disabled=true;
    try{
      // The permission prompt is only triggered by this explicit user click.
      const permission=await Notification.requestPermission();
      if(permission!=='granted')throw new Error(permission==='denied'?'通知权限未允许。请在系统或浏览器设置里开启后重试。':'还没有允许通知，你可以稍后再开启。');
      const reg=await readyWorker();
      let subscription=await reg.pushManager.getSubscription();
      if(!subscription)subscription=await reg.pushManager.subscribe({userVisibleOnly:true,applicationServerKey:applicationKey(state.push.publicKey)});
      await api('api/push/subscribe','POST',subscription.toJSON());
      const result=await api('api/settings','PUT',{...state.settings,remindersEnabled:true});state.settings=result.settings;currentSubscription=subscription;
      await loadState();toast('手机通知已开启，点「测试提醒」试一下。');
    }catch(error){if(!unauthorized(error)){toast(error.message||'无法开启通知，请稍后重试。',true);button.disabled=false;updatePushState();}}
  }
  async function disablePush(button) {
    button.disabled=true;
    try{
      const result=await api('api/settings','PUT',{...state.settings,remindersEnabled:false});state.settings=result.settings;
      const reg=await swPromise;const subscription=reg?await reg.pushManager.getSubscription():null;
      if(subscription){await api('api/push/unsubscribe','POST',{endpoint:subscription.endpoint});await subscription.unsubscribe();}
      currentSubscription=null;await loadState();toast('家务本的推送提醒已关闭。');
    }catch(error){if(!unauthorized(error)){toast(error.message,true);button.disabled=false;await loadState();}}
  }
  async function download(path,filename,button){
    button.disabled=true;
    try{
      if(!navigator.onLine)throw new Error('当前离线，连接网络后才能下载。');
      const response=await fetch(new URL(path,BASE),{credentials:'same-origin',cache:'no-store'});
      if(!response.ok){const data=await response.json().catch(()=>({}));throw new ApiError(data.error||'下载暂时失败，请重试。',response.status);}
      const url=URL.createObjectURL(await response.blob());const link=document.createElement('a');link.href=url;link.download=filename;document.body.appendChild(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),60000);
      toast(path.includes('reminders')?'日历文件已准备好，请在系统日历中导入。':'备份文件已准备好。');
    }catch(error){if(!unauthorized(error))toast(error.message,true);}finally{button.disabled=false;}
  }
  function showInstall(){
    const standalone=window.matchMedia('(display-mode: standalone)').matches||navigator.standalone===true;
    helpDialog.innerHTML=`<div class="dialog-head"><h2 id="help-dialog-title">把拾间放到主屏幕</h2><button class="icon-button" data-close aria-label="关闭">${icon('close',18)}</button></div>${standalone?'<div class="help-callout">你已经在主屏幕 App 中打开拾间了，可以直接在设置里开启通知。</div>':'<p class="dialog-help">每天一眼看见，少一点忘记。</p><ol class="help-steps"><li><strong>iPhone / iPad</strong><br>在 Safari 打开当前网址，点「分享」，选择「添加到主屏幕」。然后从桌面拾间图标打开。</li><li><strong>Android</strong><br>在 Chrome 打开当前网址，点右上角菜单，选择「安装应用」或「添加到主屏幕」。</li></ol><div class="help-callout">iPhone 通知需要 iOS 16.4 或更新版本。添加完成后，从桌面打开拾间，进入「设置」开启手机通知。</div>'}<button class="button full" data-close>知道了</button>`;
    helpDialog.querySelectorAll('[data-close]').forEach(button=>button.addEventListener('click',()=>helpDialog.close()));helpDialog.showModal();
  }
  async function action(name,button){
    switch(name){
      case'refresh':await loadState();break;
      case'today':browsingPast=false;await loadState();break;
      case'add-task':openEditor();break;
      case'enable-push':await enablePush(button);break;
      case'disable-push':await disablePush(button);break;
      case'test-push':
        button.disabled=true;
        try{const result=await api('api/push/test','POST',{});toast(result.sent?`测试提醒已发送到 ${result.sent} 台设备。`:'没有可接收通知的设备，请先开启手机通知。',!result.sent);}catch(error){if(!unauthorized(error))toast(error.message,true);}finally{button.disabled=false;}
        break;
      case'test-email':
        button.disabled=true;
        try{await api('api/email/test','POST',{});toast('测试邮件已发送，请查看收件箱或垃圾邮件。');}catch(error){if(!unauthorized(error))toast(error.message,true);}finally{button.disabled=false;}
        break;
      case'calendar':await download('api/reminders.ics','拾间-每日提醒.ics',button);break;
      case'export':await download('api/export',`拾间-家务备份-${state.today}.json`,button);break;
      case'install':if(deferredInstall){await deferredInstall.prompt();await deferredInstall.userChoice;deferredInstall=null;}else showInstall();break;
      case'logout':
        button.disabled=true;
        try{await api('api/logout','POST',{});state=null;expanded.clear();page='today';browsingPast=false;renderLogin();}catch(error){if(!unauthorized(error)){toast(error.message,true);button.disabled=false;}}
        break;
    }
  }
  window.addEventListener('beforeinstallprompt',event=>{event.preventDefault();deferredInstall=event;});
  window.addEventListener('online',()=>{if(state&&!saving&&!taskDialog.open&&!helpDialog.open)loadState();else if(state)render();});
  window.addEventListener('offline',()=>{if(state)render();else renderLogin('当前离线，请连接网络后打开家务本。');});
  const refreshVisible=()=>{if(document.visibilityState==='visible'&&state&&!saving&&!taskDialog.open&&!helpDialog.open&&!$('#settings-form')?.contains(document.activeElement)&&!$('#email-form')?.contains(document.activeElement))loadState();};
  document.addEventListener('visibilitychange',refreshVisible);
  window.addEventListener('focus',refreshVisible);
  setInterval(refreshVisible,60000);
  loadState();
})();
