import { execFile } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createRequire } from 'node:module';
import { readFile, realpath } from 'node:fs/promises';
import { performance } from 'node:perf_hooks';
import { fileURLToPath } from 'node:url';
import { parseEnv, promisify } from 'node:util';

const require = createRequire(import.meta.url);
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const base = new URL(process.env.FINANCE_BI_BASE_URL || 'http://localhost:18087');
const local = ['localhost', '127.0.0.1', '[::1]'].includes(base.hostname);
const env = process.env.FINANCE_BI_LOGIN_FILE
  ? parseEnv(await readFile(process.env.FINANCE_BI_LOGIN_FILE, 'utf8')) : {};
const routes = {
  overview: '.fi-metrics.fi-metrics-operating',
  live: '.fi-live-grid',
  settlements: '.fi-settlement-totals',
  reconciliation: '.fi-mini-stats',
  orders: '.fi-pagebar',
  fees: '.fi-pagebar',
  costs: '.fl-content[aria-busy="false"] .fc-metrics',
  ads: '[data-ad-spend-workspace]',
  shops: '.fi-shop-stats',
};
const tableRoutes = new Set(['live', 'settlements', 'reconciliation', 'orders', 'fees', 'costs', 'ads', 'shops']);
const selected = (process.env.FINANCE_BI_ROUTES || Object.keys(routes).join(',')).split(',');
const samples = Number(process.env.FINANCE_BI_SAMPLES || 1);
const concurrency = Number(process.env.FINANCE_BI_CONCURRENCY || 1);
const timeout = Number(process.env.FINANCE_BI_TIMEOUT_MS || 45000);
const month = process.env.FINANCE_BI_MONTH || '';
const coldRedis = process.env.FINANCE_BI_COLD_REDIS === '1';
const command = promisify(execFile);
let redis;
if (coldRedis) {
  if (!local) throw Error('Redis cache reset requires the local isolated Compose project');
  const root = await realpath(fileURLToPath(new URL('../../', import.meta.url)));
  const project = 'heyi-dev-' + createHash('sha256').update(root).digest('hex').slice(0, 10);
  redis = project + '-redis-1';
  const { stdout } = await command('docker', ['inspect', '--format',
    '{{index .Config.Labels "com.docker.compose.project"}} {{index .Config.Labels "com.docker.compose.service"}}', redis]);
  if (stdout.trim() !== project + ' redis') throw Error('Development Redis container identity does not match');
}
if (!selected.length || selected.some(route => !Object.hasOwn(routes, route))
    || !Number.isInteger(samples) || samples < 1 || samples > 1000
    || !Number.isInteger(concurrency) || concurrency < 1 || concurrency > samples
    || !Number.isFinite(timeout) || timeout < 5000) throw Error('Invalid finance benchmark parameters');
if (!local && (concurrency > 1 || samples > 1 || base.protocol !== 'https:')) {
  throw Error('Run repeated or concurrent benchmarks on an isolated local service');
}
if (month && !/^\d{4}-(0[1-9]|1[0-2])$/.test(month)) throw Error('Invalid fixture month');
const password = process.env.FINANCE_BI_PASSWORD || env.APP_ADMIN_PASSWORD;
if (!password) throw Error('Finance benchmark login is required');

const browser = await chromium.launch(process.env.FINANCE_BI_CHROME
  ? { executablePath: process.env.FINANCE_BI_CHROME } : {});
try {
  const login = await browser.newContext();
  const response = await login.request.post(new URL('/api/v1/auth/login', base).href, {
    data: {
      organizationCode: process.env.FINANCE_BI_ORG || 'heyi',
      phone: process.env.FINANCE_BI_PHONE || '13800000000',
      password,
    },
  });
  if (!response.ok()) throw Error('Finance benchmark login returned HTTP ' + response.status());
  const { data: user } = await response.json();
  if (!user?.organizationId || !user?.userId) throw Error('Finance session has no company scope');
  const storageState = await login.storageState();
  await login.close();

  async function clearResponseCache() {
    if (!/^[a-zA-Z0-9-]{1,36}$/.test(user.organizationId)) throw Error('Unrecognized finance company scope');
    const pattern = 'heyi:finance:snapshot:*{' + user.organizationId + ':*}:*';
    await command('docker', ['exec', redis, 'sh', '-ec',
      'export REDISCLI_AUTH="$REDIS_PASSWORD"; redis-cli --scan --pattern "$1" | sed -E "/:(revision|data-revision)$/d" | xargs -r redis-cli UNLINK >/dev/null',
      'finance-cache', pattern]);
  }

  async function sample(route) {
    const context = await browser.newContext({ storageState });
    const page = await context.newPage();
    const requests = [];
    const requestStarts = new WeakMap();
    const responses = [];
    let sessionHttp;
    let scriptFailures = 0;
    page.on('requestfailed', request => {
      if (request.resourceType() === 'script') scriptFailures++;
    });
    function endpoint(request) {
      const path = new URL(request.url()).pathname;
      return path.startsWith('/api/v1/finance/') ? path.slice('/api/v1/finance'.length) : null;
    }
    page.on('request', request => {
      if (endpoint(request)) requestStarts.set(request, performance.now());
    });
    page.on('response', response => {
      const request = response.request();
      const path = endpoint(request);
      if (new URL(request.url()).pathname === '/api/v1/auth/me') sessionHttp = response.status();
      if (path === '/overview' || path === '/workspace/query') responses.push(response);
      if (path) requests.push({
        path,
        status: response.status(),
        headerMs: Math.round(performance.now() - (requestStarts.get(request) ?? performance.now())),
      });
    });
    if (month && route !== 'live') {
      const [year, number] = month.split('-').map(Number);
      const end = String(new Date(Date.UTC(year, number, 0)).getUTCDate()).padStart(2, '0');
      const key = 'heyi:finance-filters:v1:' + JSON.stringify([user.organizationId, user.userId]);
      await page.addInitScript(({ key, month, end }) => {
        sessionStorage.setItem(key, JSON.stringify({ pinnedDates: true, start: month + '-01', end: month + '-' + end }));
      }, { key, month, end });
    }
    const started = performance.now();
    let outcome = 'timeout';
    try {
      await page.goto(new URL('/#finance-' + route, base).href, { waitUntil: 'commit', timeout });
      const ready = await page.waitForFunction(({ route, selector, needsTable }) => {
        const root = document.querySelector('#finance-page');
        if (!root || root.hidden || !root.getClientRects().length) return false;
        if (root.querySelector('.fi-state.is-error:not([hidden]), .fi-live-recovery')) return 'page-error';
        if (root.getAttribute('aria-busy') !== 'false' || root.classList.contains('fi-selection-pending')) return false;
        const body = root.querySelector(selector);
        if (!body?.getClientRects().length) return false;
        const tables = [...root.querySelectorAll('table')].filter(table => table.getClientRects().length);
        if (needsTable && !tables.length && route !== 'costs') return false;
        for (const chart of root.querySelectorAll('.fi-chart')) {
          if (chart.financePlot?.hasData && chart.getClientRects().length
              && !chart.querySelector('svg, canvas')) return false;
        }
        return 'ready';
      }, { route, selector: routes[route], needsTable: tableRoutes.has(route) }, { timeout, polling: 40 });
      outcome = await ready.jsonValue();
    } catch (error) {
      if (error.name !== 'TimeoutError') outcome = 'browser-error';
    }
    const ms = Math.round(performance.now() - started);
    const model = [];
    if (outcome === 'ready') {
      if (requests.some(({ path, status }) => path !== '/fx/market' && status >= 400)) outcome = 'request-error';
      for (const response of responses) {
        const payload = await response.json().catch(() => null);
        if (payload?.data?.readModel?.status) model.push(payload.data.readModel.status);
      }
    }
    await context.close();
    return { ms, outcome, requests, model, ...(outcome === 'ready' ? {} : { sessionHttp, scriptFailures }) };
  }

  let failed = false;
  for (const route of selected) {
    const runs = [];
    for (let offset = 0; offset < samples; offset += concurrency) {
      if (coldRedis) await clearResponseCache();
      runs.push(...await Promise.all(Array.from({ length: Math.min(concurrency, samples - offset) }, () => sample(route))));
    }
    const sorted = runs.map(run => run.ms).sort((a, b) => a - b);
    const p = rank => sorted[Math.ceil(sorted.length * rank) - 1];
    const errors = runs.filter(run => run.outcome !== 'ready').length;
    const overBudget = runs.filter(run => run.ms >= 5000 || run.outcome !== 'ready').length;
    if (overBudget || p(0.95) > 3000) failed = true;
    console.log(JSON.stringify({ route, month: route === 'live' ? 'today' : month || 'default', concurrency,
      coldRedis, samples, errors, overBudget, p50Ms: p(0.5), p95Ms: p(0.95), p99Ms: p(0.99), maxMs: sorted.at(-1), runs }));
  }
  if (failed) process.exitCode = 1;
} finally {
  await browser.close();
}
