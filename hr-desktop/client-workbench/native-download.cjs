const MAX_BYTES = 20 * 1024 * 1024;
const DOWNLOAD_ORIGIN = 'https://docdownload.zhipin.com';
const DOWNLOAD_PATH = '/wflow/zpgeek/download/download4boss/';
const failure = (code, message) => Object.assign(Error(message), {code});

function attachmentDisposition(value) {
  return typeof value === 'string' && value.length <= 8192 && /^[\x20-\x7e]+$/.test(value)
    ? value : '';
}

function resumeURL(value) {
  let url;
  try {url = new URL(value);} catch {throw failure('INVALID_ATTACHMENT', '附件地址不正确');}
  const segment = url.pathname.slice(DOWNLOAD_PATH.length);
  let decoded;
  try {decoded = decodeURIComponent(segment);} catch {}
  if (url.origin !== DOWNLOAD_ORIGIN || url.username || url.password || url.hash ||
      !url.pathname.startsWith(DOWNLOAD_PATH) || !decoded || decoded.length > 512 ||
      /[/\\\x00-\x20\x7f]/.test(decoded) || ['.', '..'].includes(decoded) ||
      !url.searchParams.get('id') || url.search.length > 16384) {
    throw failure('INVALID_ATTACHMENT', '只允许下载已核对的 BOSS 简历附件');
  }
  const seen = new Set();
  for (const [key, value] of url.searchParams) {
    if (!['id', 'authType', 'd'].includes(key) || seen.has(key) || !value || value.length > 8192) {
      throw failure('INVALID_ATTACHMENT', '附件参数不正确');
    }
    seen.add(key);
  }
  return url.href;
}

function readCredentials(credentials, signal, timeout) {
  return new Promise((resolve, reject) => {
    let settled = false;
    const finish = (error, value) => {
      if (settled) return;
      settled = true;clearTimeout(timer);signal?.removeEventListener('abort', cancel);
      if (error) reject(error);else resolve(value);
    };
    const cancel = () => finish(failure('REQUESTS_PAUSED', '附件下载已取消'));
    const timer = setTimeout(() => finish(failure('DOWNLOAD_TIMEOUT', '附件下载超时')), timeout);
    signal?.addEventListener('abort', cancel, {once: true});
    if (signal?.aborted) {cancel();return;}
    Promise.resolve().then(() => credentials()).then(value => finish(null, value), error => {
      if (['AUTH_REQUIRED', 'SESSION_MISMATCH', 'ACCOUNT_RISK',
        'SECURITY_VERIFICATION_REQUIRED', 'REQUESTS_PAUSED'].includes(error?.code)) finish(error);
      else finish(failure('DOWNLOAD_FAILED', '原客户端附件准备未完成'));
    });
  });
}

// A single native Chromium request with the original client's real session and
// headers. No download-manager retries, redirects, or caller-supplied headers.
async function downloadResume({net, session, url, credentials, checkAccount,
  signal, timeout = 65000, maxBytes = MAX_BYTES}) {
  url = resumeURL(url);
  checkAccount();
  if (!Number.isInteger(timeout) || timeout < 1 || timeout > 75000 ||
      !Number.isInteger(maxBytes) || maxBytes < 1 || maxBytes > MAX_BYTES) {
    throw failure('INVALID_ATTACHMENT', '附件下载限制不正确');
  }
  const deadline = Date.now() + timeout;
  const auth = await readCredentials(credentials, signal, timeout);
  checkAccount();
  if (!auth?.token || !auth.userAgent) throw failure('AUTH_REQUIRED', '原客户端登录态尚未建立');
  if (signal?.aborted) throw failure('REQUESTS_PAUSED', '附件下载已取消');
  const remaining = deadline - Date.now();
  if (remaining <= 0) throw failure('DOWNLOAD_TIMEOUT', '附件下载超时');
  return new Promise((resolve, reject) => {
    const request = net.request({url, method: 'GET', session, redirect: 'manual', useSessionCookies: true});
    let response, settled = false;
    const finish = (error, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      signal?.removeEventListener('abort', cancel);
      if (error) {
        request.abort();
        reject(error);
      } else resolve(value);
    };
    const cancel = () => finish(failure('REQUESTS_PAUSED', '附件下载已取消'));
    const timer = setTimeout(() => finish(failure('DOWNLOAD_TIMEOUT', '附件下载超时')), remaining);
    signal?.addEventListener('abort', cancel, {once: true});
    request.on('error', () => finish(failure('DOWNLOAD_FAILED', '原客户端附件下载未完成')));
    request.on('abort', () => finish(failure('DOWNLOAD_FAILED', '原客户端附件下载已中断')));
    request.on('redirect', status => {
      try {checkAccount();} catch (error) {finish(error);return;}
      // Report the real redirect without following it or returning its URL.
      finish(null, {status, body: Buffer.alloc(0)});
      request.abort();
    });
    request.on('response', incoming => {
      response = incoming;
      const chunks = [];
      let size = 0;
      const check = () => {
        try {checkAccount();return true;} catch (error) {finish(error);return false;}
      };
      if (!check()) return;
      const header = name => {
        const key = Object.keys(response.headers || {}).find(key => key.toLowerCase() === name);
        const value = key ? response.headers[key] : undefined;
        return Array.isArray(value) ? value[0] : value;
      };
      const declared = Number(header('content-length'));
      if (declared > maxBytes) return finish(failure('ATTACHMENT_TOO_LARGE', '附件超过 20 MiB 上限'));
      response.on('data', chunk => {
        if (settled || !check()) return;
        size += chunk.length;
        if (size > maxBytes) return finish(failure('ATTACHMENT_TOO_LARGE', '附件超过 20 MiB 上限'));
        chunks.push(Buffer.from(chunk));
      });
      response.on('error', () => finish(failure('DOWNLOAD_FAILED', '附件读取中断')));
      response.on('aborted', () => finish(failure('DOWNLOAD_FAILED', '附件读取中断')));
      response.on('end', () => {
        if (settled || !check()) return;
        if (Number.isFinite(declared) && declared >= 0 && !header('content-encoding') && declared !== size) {
          finish(failure('DOWNLOAD_FAILED', '附件长度不完整，未保存'));
        } else {
          const disposition = attachmentDisposition(header('content-disposition'));
          finish(null, {status: response.statusCode, body: Buffer.concat(chunks),
            ...(disposition ? {contentDisposition: disposition} : {})});
        }
      });
      response.on('close', () => {
        if (!settled) finish(failure('DOWNLOAD_FAILED', '附件未完整接收'));
      });
    });
    try {
      request.setHeader('Cookie', `wt2=${auth.token}`);
      if (auth.zpToken) request.setHeader('zp_token', auth.zpToken);
      request.setHeader('User-Agent', auth.userAgent);
      request.setHeader('Referer', 'https://www.zhipin.com/web/chat/index');
      checkAccount();
      request.end();
    } catch (error) {finish(error);}
  });
}

module.exports = {downloadResume, resumeURL, MAX_BYTES, attachmentDisposition};
