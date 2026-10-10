/* Model response viewer. Decorative code rain never enters the response text. */
(() => {
  const views = new WeakMap();
  const stages = { QUEUED: '等待处理', EXTRACTING_FRAMES: '正在提取视频画面', AI_ANALYZING: '模型正在生成分镜', READY: '拆解完成', FAILED: '拆解未完成' };
  const analysisStages = { WATCH_RETRYING_RESPONSE: '上游连接中断，正在重试当前请求，已完成证据保留', WATCH_RECHECKING_EVIDENCE: '正在复核有分歧的画面与相邻动作', WATCH_PARALLEL_ANALYSIS: '正在并行分析视频片段，逐批返回画面证据', WATCH_SYNTHESIZING: '片段分析完成，正在统一编排九个分镜', WATCH_REASONING: '模型正在推理，已收到活动信号，等待正文', WATCH_WAITING_RESPONSE: '请求已发出，等待模型接口响应', WATCH_STREAM_CONNECTED: '流式连接已建立，等待模型输出', WATCH_BUFFERED_RESPONSE: '模型接口返回非流式响应，等待完整结果', WATCH_TOOL_STREAMING: '模型正在返回读图指令', WATCH_LOADING_IMAGES: 'watch 正在加载模型请求的关键帧', WATCH_TEXT_STREAMING: '正在接收模型正文', CORRECTING_WATCH_EVIDENCE: 'watch 正在校正分镜时间与画面依据', WATCH_EXTRACTING_KEYFRAMES: 'watch 正在提取视频关键帧', WATCH_KEYFRAMES_READY: 'watch 关键帧已准备好', WATCH_READING_KEYFRAMES: 'watch 正在阅读关键帧并分析动作', WATCH_ANALYSIS_RECEIVED: 'watch 分析已返回，正在校验分镜', REQUEST_ACCEPTED: '模型已接收分析请求', AI_ANALYZING: '正在生成首版 9 镜', AI_RESPONSE_RECEIVED: '首版已返回，正在校验', REPAIRING_JSON: '正在修正分镜格式', JSON_REPAIRED: '分镜格式已修正', ADJUSTING_SHOT_COUNT: '正在补齐 9 个分镜', SHOT_COUNT_RESPONSE_RECEIVED: '9 镜已返回', VERIFYING_ACTION_STATES: '正在复核人物动作与镜头衔接', CONDENSING_NARRATION: '正在精简口播', RESULT_READY: '分镜已生成，正在保存' };
  const glyphs = '01ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿ0123456789';
  const reducedMotion = () => window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;

  function reset(host) {
    if (!host) return;
    const state = views.get(host);
    if (state?.frame) cancelAnimationFrame(state.frame);
    views.delete(host);
    host.innerHTML = '';
  }

  function create(host, script) {
    const previous = views.get(host);
    if (previous?.frame) cancelAnimationFrame(previous.frame);
    host.innerHTML = `<section class="model-terminal" aria-label="模型输出">
      <div class="model-terminal-rain" aria-hidden="true">${Array.from({ length: 28 }, (_, i) =>
        `<i style="--column:${i};--duration:${7 + i % 7}s;--delay:-${i * 1.37}s">${Array.from({ length: 24 }, (_, j) => glyphs[(i * 7 + j * 13) % glyphs.length]).join('\n')}</i>`).join('')}</div>
      <header class="model-terminal-head"><div><span class="model-terminal-eyebrow">MODEL OUTPUT <b>● LIVE</b></span><h3>正在生成分镜</h3><p class="model-terminal-model"></p></div>
        <div class="model-terminal-progress"><strong></strong><span>当前阶段</span></div></header>
      <div class="model-terminal-steps" aria-label="分析流程"><span data-phase="0"><b>01</b>准备画面</span><span data-phase="1"><b>02</b>理解视频</span><span data-phase="2"><b>03</b>生成分镜</span><span data-phase="3"><b>04</b>校验保存</span></div>
      <div class="model-terminal-meter" role="progressbar" aria-label="视频拆解进度" aria-valuemin="0" aria-valuemax="100"><i></i></div>
      <div class="model-terminal-toolbar"><span>模型返回原文</span><div><button type="button" data-terminal-follow aria-pressed="true">跟随输出 ↓</button><button type="button" data-terminal-copy disabled>复制文字</button></div></div>
      <div class="model-terminal-screen" tabindex="0" role="region" aria-label="可滚动的模型返回原文"><div class="model-terminal-waiting"><span class="model-terminal-orbit" aria-hidden="true"><i></i><b>✦</b></span><div><strong>等待模型返回文字</strong><p>收到内容后会在这里逐字显示</p></div></div><pre><code></code><span class="model-terminal-cursor" aria-hidden="true">▊</span></pre></div>
      <footer class="model-terminal-foot"><span class="model-terminal-status" role="status"></span><span class="model-terminal-count">尚未收到文字</span></footer>
    </section>`;
    const state = { key: String(script.id), run: script.analysisRunId || '', text: '', target: '', frame: 0, following: true, initialized: false, count: 0, outputUpdatedAt: 0 };
    views.set(host, state);
    const screen = host.querySelector('.model-terminal-screen');
    const follow = host.querySelector('[data-terminal-follow]');
    const setFollowing = value => { state.following = value; follow.setAttribute('aria-pressed', String(value)); follow.textContent = value ? '跟随输出 ↓' : '回到最新 ↓'; };
    screen.addEventListener('scroll', () => setFollowing(screen.scrollHeight - screen.scrollTop - screen.clientHeight < 40), { passive: true });
    follow.addEventListener('click', () => { setFollowing(true); screen.scrollTop = screen.scrollHeight; });
    host.querySelector('[data-terminal-copy]').addEventListener('click', async event => {
      try { await navigator.clipboard.writeText(state.target); event.target.textContent = '已复制'; }
      catch { event.target.textContent = '请选中文字复制'; }
    });
    return state;
  }

  function write(host, state, text) {
    state.text = text;
    host.querySelector('code').textContent = text;
    const screen = host.querySelector('.model-terminal-screen');
    if (state.following) screen.scrollTop = screen.scrollHeight;
  }

  function update(host, script, progress) {
    if (!host || !script) return;
    let state = views.get(host);
    if (!state || state.key !== String(script.id) || state.run !== (script.analysisRunId || '') || !host.querySelector('.model-terminal')) state = create(host, script);
    const terminal = host.querySelector('.model-terminal');
    const active = ['QUEUED', 'EXTRACTING_FRAMES', 'AI_ANALYZING'].includes(script.status);
    const failed = script.status === 'FAILED';
    terminal.classList.toggle('is-active', active);
    terminal.classList.toggle('is-failed', failed);
    terminal.querySelector('.model-terminal-eyebrow b').textContent = active ? '● LIVE' : failed ? '● STOPPED' : '● SAVED';
    const nextPhase = !active ? 3 : script.status === 'QUEUED' || script.status === 'EXTRACTING_FRAMES' || /EXTRACTING|KEYFRAMES_READY/.test(script.analysisStage || '') ? 0 : /REPAIR|CORRECT|ADJUST|CONDENS|RESULT_READY|RECEIVED|VERIFY/.test(script.analysisStage || '') ? 3 : ['WATCH_TEXT_STREAMING', 'WATCH_SYNTHESIZING'].includes(script.analysisStage) ? 2 : 1;
    const phase = Math.max(state.phase || 0, nextPhase);
    state.phase = phase;
    const phaseTitles = ['正在准备视频画面', '正在理解视频内容', '正在生成分镜文字', '正在校验并保存'];
    terminal.querySelector('h3').textContent = active ? (script.analysisStage === 'WATCH_PARALLEL_ANALYSIS' ? '正在并行分析视频片段' : phaseTitles[phase]) : failed ? '这次拆解没有完成' : '模型输出已保存';
    terminal.querySelector('.model-terminal-model').textContent = `${script.modelId || '驱动模型'} · ${active ? '正在根据视频画面编排 9 个分镜' : failed ? '已保留收到的文字，可重试当前任务' : '本次拆解的模型返回原文'}`;
    const amount = Math.max(0, Math.min(100, Number(progress) || 0));
    terminal.querySelector('.model-terminal-progress strong').textContent = active ? `0${phase + 1} / 04` : failed ? '未完成' : '已完成';
    for (let i = 0; i < 4; i++) {
      const step = terminal.querySelector(`[data-phase="${i}"]`);
      step.classList.toggle('is-current', active && i === phase);
      step.classList.toggle('is-done', i < phase || (!active && !failed));
      step.setAttribute('aria-current', active && i === phase ? 'step' : 'false');
    }
    terminal.querySelector('.model-terminal-meter').setAttribute('aria-valuetext', active ? phaseTitles[phase] + '，阶段进度不代表剩余时间' : failed ? '未完成' : '已完成');
    terminal.querySelector('.model-terminal-meter').setAttribute('aria-valuenow', String(amount));
    terminal.querySelector('.model-terminal-meter i').style.width = `${amount}%`;
    terminal.querySelector('.model-terminal-status').textContent = failed ? (script.errorMessage || stages.FAILED) : !active ? (stages[script.status] || '模型输出已保存') : (analysisStages[script.analysisStage] || stages[script.status] || '等待模型返回');
    // List responses omit the text: keep the latest detail snapshot until it arrives.
    const hasSnapshot = typeof script.analysisOutput === 'string';
    const incomingCount = Number(script.analysisOutputCharacters) || (script.analysisOutput || '').length;
    const incomingUpdatedAt = Date.parse(script.analysisOutputUpdatedAt) || 0;
    if (hasSnapshot && incomingCount >= state.count && (!incomingUpdatedAt || incomingUpdatedAt >= state.outputUpdatedAt)) {
      state.target = script.analysisOutput;
      state.count = incomingCount;
      state.outputUpdatedAt = Math.max(state.outputUpdatedAt, incomingUpdatedAt);
    }
    const text = state.target;
    terminal.classList.toggle('has-output', Boolean(text));
    terminal.querySelector('.model-terminal-waiting strong').textContent = active ? (phase === 0 ? '画面准备好后，开始分析' : phase === 3 ? '正在检查分镜完整性' : /WAITING_RESPONSE|STREAM_CONNECTED/.test(script.analysisStage || '') ? '等待模型开始返回内容' : '正在看懂动作、运镜与节奏') : '本次任务未记录模型输出';
    terminal.querySelector('.model-terminal-waiting p').textContent = active ? (phase === 0 ? '正在提取视频中的关键画面' : '正文尚未返回 · 返回后将实时显示，无需等待全部完成') : '可以在分镜区查看已保存的分析结果';
    terminal.querySelector('.model-terminal-count').textContent = state.count ? `${state.count.toLocaleString((globalThis.HoyeeLanguage?.locale || "zh-CN"))} 字符${state.count > text.length ? ' · 显示最近片段' : ''}` : '尚未收到文字';
    const copy = terminal.querySelector('[data-terminal-copy]');
    copy.disabled = !text;
    if (text !== state.text && (window.HoyeeLanguage?.originalText(copy.firstChild) || copy.textContent) === '已复制') copy.textContent = '复制文字';
    if (!state.initialized || !active || reducedMotion() || !text.startsWith(state.text)) {
      if (state.frame) cancelAnimationFrame(state.frame);
      state.frame = 0;
      write(host, state, text);
    } else if (text !== state.text && !state.frame) {
      let last = performance.now();
      const reveal = now => {
        if (!host.isConnected || host.hidden || views.get(host) !== state) { state.frame = 0; return; }
        const remaining = state.target.length - state.text.length;
        const step = Math.max(1, Math.ceil(remaining * Math.min(1, (now - last) / 140)));
        last = now;
        write(host, state, state.target.slice(0, state.text.length + step));
        state.frame = state.text.length < state.target.length ? requestAnimationFrame(reveal) : 0;
      };
      state.frame = requestAnimationFrame(reveal);
    }
    state.initialized = true;
  }
  window.addEventListener('heyi:company-reset', () => {
    document.querySelectorAll('.model-terminal').forEach(terminal => reset(terminal.parentElement));
  });
  window.VideoScriptTerminal = { update, reset };
})();
