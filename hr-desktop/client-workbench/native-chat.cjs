const crypto = require('node:crypto');
const {NativeResume, isChatURL, preparePage, commitPage} = require('./native-resume.cjs');
const fail = (code, message) => Object.assign(Error(message), {code});

// Runs in the client's existing chat frame. All writes still use its own
// conversation service, including reply/block and chat-start-limit checks.
// The retired main-process MQTT SDK is not the chat page's transport.
async function prepareChatPage(args) {
  const stop = (code, message) => ({ok: false, code, message});
  if (window.__heyiChatAction) return stop('ACCOUNT_BUSY', '原客户端沟通尚未完成');
  const own = () => String(window.iBossRoot?.user?.get('userId') || '');
  if (own() !== args.account) return stop('SESSION_MISMATCH', '原客户端账号已变化');
  const state = {token: args.token, cancelled: false, invoked: false, cleanups: []};
  window.__heyiChatAction = state;
  const alive = () => !state.cancelled && window.__heyiChatAction === state;
  const draft = editor => !!(editor?.$refs?.editbox?.textContent?.trim() ||
    editor?.$refs?.editbox?.querySelector('img') || editor?.quoteMessage$);
  const currentEditor = () => document.querySelector('.chat-conversation-operate')?.__vue__ ||
    document.querySelector('.boss-chat-editor-input')?.parentElement?.__vue__;
  const jobValues = conversation =>
    [conversation?.toPositionId, conversation?.encryptJobId, conversation?.encJobId]
      .filter(value => value !== undefined && value !== null && value !== '').map(String);
  const matchesJob = conversation => {
    // The current client keeps its encrypted job in toPositionId; the list's
    // currentData has encryptJobId. Require every supplied alias to agree.
    const jobs = jobValues(conversation);
    return jobs.length > 0 && jobs.every(job => job === args.job);
  };
  let editor = currentEditor();
  if (draft(editor)) return stop('NATIVE_DRAFT_PRESENT', 'BOSS 输入框有未发送内容，请先处理草稿');
  if (String(editor?.conversation$?.friendId || '') !== args.friendId) {
    const list = document.querySelector('.geek-list-wrapper')?.__vue__ ||
      document.querySelector('.chat-user')?.__vue__;
    if (list?.$options?.name !== 'geek-list' || typeof list.geekClick !== 'function') {
      return stop('NATIVE_CHAT_UNAVAILABLE', '原客户端聊天列表尚未就绪');
    }
    list.geekClick(args.friend);
  }
  const end = Date.now() + 5000;
  while (alive() && Date.now() < end) {
    if (own() !== args.account) return stop('SESSION_MISMATCH', '原客户端账号已变化');
    editor = currentEditor();
    const conversation = editor?.conversation$;
    if (String(conversation?.friendId || '') === args.friendId && !editor.conversationLoading$) {
      if (jobValues(conversation).length && !matchesJob(conversation)) {
        return stop('JOB_CONVERSATION_MISMATCH', '当前会话职位不一致');
      }
      if (matchesJob(conversation) && conversation.toPositionId && conversation.securityId) {
        if (draft(editor)) return stop('NATIVE_DRAFT_PRESENT', 'BOSS 输入框有未发送内容，请先处理草稿');
        // These existing components expose the current conversation service.
        // Never enumerate arbitrary objects, extract credentials, or access a
        // webpack closure to construct a different message transport.
        const queue = [editor.$root || editor,
          ...Array.from(document.querySelectorAll('.not-fit-wrap')).map(node => node.__vue__)],
          seen = new Set(), services = new Set();
        while (queue.length && seen.size < 6000) {
          const vm = queue.shift();
          if (!vm || seen.has(vm)) continue;
          seen.add(vm);
          queue.push(...(vm.$children || []));
          if (['dialog-nofit', 'tooltip-nofit'].includes(vm.$options?.name || vm.$options?._componentTag) &&
              vm.source !== 'global' && typeof vm.getConversationService === 'function') {
            services.add(vm.getConversationService());
          }
        }
        if (services.size !== 1) return stop('NATIVE_CHAT_UNAVAILABLE', '当前会话发送服务尚未就绪');
        const service = [...services][0];
        if (typeof service?.sendMessage !== 'function' ||
            typeof service.onMessageDelivered !== 'function' ||
            String(service.bossId || '') !== args.account ||
            String(service.get?.('friendId') || '') !== args.friendId) {
          return stop('NATIVE_CHAT_UNAVAILABLE', '当前会话发送服务未核实');
        }
        const selectedJobMatches = () => {
          const selected = [service.get('encryptJobId'), service.get('encJobId')]
            .filter(value => value !== undefined && value !== null && value !== '').map(String);
          return selected.every(job => job === args.job);
        };
        const securityId = String(conversation.securityId);
        state.valid = () => {
          const current = editor.conversation$, native = service.conversation;
          return alive() && own() === args.account && !editor.conversationLoading$ &&
            String(service.bossId || '') === args.account &&
            String(service.get('friendId') || '') === args.friendId &&
            String(current?.friendId || '') === args.friendId &&
            matchesJob(current) && matchesJob(native) && selectedJobMatches() &&
            String(current.securityId || '') === securityId &&
            String(native?.securityId || '') === securityId &&
            String(native.toPositionId || '') === String(current.toPositionId || '') &&
            !!current.toPositionId && !current.groupId && !draft(editor);
        };
        if (!state.valid()) return stop('JOB_CONVERSATION_MISMATCH', '当前会话账号或职位未核实');
        state.service = service;
        state.result = new Promise(resolve => {state.finish = resolve;});
        const replace = (name, make) => {
          const descriptor = Object.getOwnPropertyDescriptor(service, name);
          const original = service[name], wrapped = make(original);
          Object.defineProperty(service, name, {configurable: true, writable: true, value: wrapped});
          state.cleanups.push(() => {
            if (service[name] !== wrapped) return;
            if (descriptor) Object.defineProperty(service, name, descriptor);
            else delete service[name];
          });
        };
        replace('onMessageDelivered', original => function(rows) {
          // Preserve native handling and return value. A queued/newSubmit row,
          // a resolved SDK promise, or matching text in history is not success.
          const result = Reflect.apply(original, this, [rows]);
          if (state.invoked && state.valid()) {
            for (const row of Array.isArray(rows) ? rows : []) {
              const mid = String(row.mid || '');
              if (String(row.fromId || '') !== args.account ||
                  String(row.toId || '') !== args.friendId ||
                  String(row.cmid || row.clientMid || '') !== args.clientMid ||
                  row.type !== 'text' || row.text !== args.content ||
                  row.newSubmit === true || !/^[1-9]\d{0,29}$/.test(mid) ||
                  mid === args.clientMid) continue;
              state.finish({ok: true, receipt: {fromId: args.account, toId: args.friendId,
                mid, clientMid: args.clientMid, text: args.content, type: 'text',
                isSelf: true, status: 1, time: Date.now(), transport: 'native-chat-delivery'}});
            }
          }
          return result;
        });
        if (args.resume) {
          replace('sendMessage', original => function(content, type, options = {}) {
            if (content !== args.content || type !== 'text') return Reflect.apply(original, this, arguments);
            if (!state.valid() || state.invoked) throw Error('简历请求已停止，未重复发送');
            state.invoked = true;
            return Reflect.apply(original, this, [content, type, {...options, clientMid: args.clientMid}]);
          });
        }
        state.deadline = setTimeout(() => state.finish(stop('SEND_UNCONFIRMED',
          '未收到实际消息回执，未自动重复发送')), 9500);
        return {ok: true};
      }
    }
    await new Promise(resolve => setTimeout(resolve, 100));
  }
  return stop('JOB_CONVERSATION_MISMATCH', '原客户端未切换到指定候选人与职位');
}

function commitTextPage(args) {
  const state = window.__heyiChatAction;
  if (!state || state.token !== args.token || !state.valid() || state.invoked) {
    return {ok: false, code: 'JOB_CONVERSATION_MISMATCH', message: '会话已变化，未执行发送'};
  }
  state.invoked = true;
  state.service.sendMessage(args.content, 'text', {bgSource: 1, clientMid: args.clientMid,
    onSendBlock: () => state.finish({ok: false, code: 'NATIVE_SEND_BLOCKED',
      message: '原客户端限制了本次发送，请在 BOSS 页面查看原因'})});
  return {ok: true};
}

function waitChatPage(args) {
  const state = window.__heyiChatAction;
  if (!state || state.token !== args.token) return {ok: false, code: 'REQUESTS_PAUSED'};
  return state.result;
}

function cancelChatPage(token) {
  const state = window.__heyiChatAction;
  if (state?.token !== token) return;
  state.cancelled = true;
  clearTimeout(state.deadline);
  state.finish?.({ok: false, code: 'REQUESTS_PAUSED', message: '沟通操作已停止'});
  for (const cleanup of state.cleanups.reverse()) cleanup();
  delete window.__heyiChatAction;
  const resume = window.__heyiResumeAction;
  if (resume?.token === token) {
    resume.cancelled = true;
    delete window.__heyiResumeAction;
  }
}

class NativeChat extends NativeResume {
  async perform({friend, account, content, clientMid, signal, checkAccount, onReady, resume = false}) {
    if (this.busy) throw fail('ACCOUNT_BUSY', '原客户端沟通操作尚未完成');
    const friendId = String(friend?.friendId || friend?.uid || '');
    const job = String(friend?.encryptJobId || friend?.encJobId || '');
    if (!/^[1-9]\d{0,19}$/.test(friendId) || !/^[1-9]\d{0,19}$/.test(account) ||
        !job || job.length > 256 || !/^[1-9]\d{0,29}$/.test(clientMid) ||
        typeof content !== 'string' || !content.trim() || content.length > 3000) {
      throw fail('JOB_CONVERSATION_MISMATCH', '候选人、职位或消息信息不完整');
    }
    this.busy = true;
    let frame, args, cancel, phase = 'select-frame';
    try {
      const allowed = () => {
        checkAccount();
        if (signal?.aborted) throw fail('REQUESTS_PAUSED', '沟通操作已停止');
      };
      allowed();
      frame = this.frame();
      args = {friend, friendId, job, account, content, clientMid, resume,
        token: crypto.randomBytes(24).toString('hex')};
      const execute = async (fn, input = args) => {
        if (!isChatURL(frame.url)) throw fail('NATIVE_CHAT_UNAVAILABLE', '聊天页面已变化');
        return frame.executeJavaScript(`(${fn.toString()})(${JSON.stringify(input)})`);
      };
      cancel = () => {void execute(cancelChatPage, args.token).catch(() => {});};
      signal?.addEventListener('abort', cancel, {once: true});
      const run = async fn => {
        phase = fn.name;
        allowed();
        const result = await execute(fn);
        allowed();
        if (result?.ok !== true) throw fail(result?.code || 'NATIVE_CHAT_UNAVAILABLE',
          result?.message || '原客户端沟通操作未完成');
        return result;
      };
      await run(prepareChatPage);
      if (resume) await run(preparePage);
      onReady?.();
      await run(resume ? commitPage : commitTextPage);
      return (await run(waitChatPage)).receipt;
    } catch (error) {
      console.warn('HEYI_NATIVE_CHAT', JSON.stringify({phase,
        code: /^[A-Z_]+$/.test(error.code || '') ? error.code : 'NATIVE_OPERATION_FAILED',
        kind: resume ? 'resume' : 'text'}));
      if (frame && process.env.HEYI_WORKBENCH_DIAGNOSTICS_OUTPUT) {
        try {
          const page = await frame.executeJavaScript(
            `(${require('./chat-diagnostics.cjs').inspectPage.toString()})()`);
          await require('node:fs/promises').writeFile(process.env.HEYI_WORKBENCH_DIAGNOSTICS_OUTPUT,
            JSON.stringify({at: new Date().toISOString(), readOnly: true, phase,
              code: error.code || 'NATIVE_OPERATION_FAILED', ...page}, null, 2));
        } catch {}
      }
      throw error;
    } finally {
      signal?.removeEventListener('abort', cancel);
      if (frame && args) {
        try {await frame.executeJavaScript(`(${cancelChatPage.toString()})(${JSON.stringify(args.token)})`);} catch {}
      }
      this.busy = false;
    }
  }
}

module.exports = {NativeChat, prepareChatPage, commitTextPage, waitChatPage, cancelChatPage};
