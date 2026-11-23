const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const readFrontend = name => fs.readFileSync(path.join(__dirname, '..', name), 'utf8');
const html = readFrontend('index.html');
const app = readFrontend('app.js');
const routes = [
  'product-capture', 'product-print-capture', 'product-capture-templates', 'product-main-image-styles',
  'commerce-designs', 'commerce-templates', 'commerce-batches', 'commerce-ads', 'commerce-assets'
];

test('navigation groups and capture-template sections each expose a single entry', () => {
  const nav = html.slice(html.indexOf('<nav id="primary-navigation"'), html.indexOf('</nav>', html.indexOf('<nav id="primary-navigation"')));
  const groups = [...nav.matchAll(/<details class="nav-group"[\s\S]*?<\/details>/g)].map(match => match[0]);
  assert.equal(groups.length, 8);
  const v2 = groups.find(group => group.includes('data-nav-group="commerce-v2"'));
  assert.ok(v2?.includes('data-commerce-route="product-print-capture"'));
  assert.ok(v2?.includes('data-commerce-route="product-capture-templates"'));
  assert.doesNotMatch(v2, /主图风格模板/);
  assert.equal(groups.filter(group => group.includes('data-commerce-route="product-print-capture"')).length, 1);
  assert.equal(groups.filter(group => group.includes('data-commerce-route="product-main-image-styles"')).length, 1);
  assert.match(html, /id="capture-template-workspace"[\s\S]*?ct-variant-editor[\s\S]*?id="ct-main-style-host"[\s\S]*?id="ct-listing-host"/);
  assert.doesNotMatch(html, /id="print-capture-listing-template"|id="capture-template-listing-config"|id="capture-main-style-tab"/);
  assert.match(html, /id="open-product-listing-templates"/);
  for (const id of ['nav-ai-video-monitor', 'nav-video-scripts', 'nav-ai-video-tasks', 'nav-general-video', 'nav-shops', 'nav-publish', 'nav-automations', 'nav-automation-admin', 'nav-models', 'nav-system', 'nav-assistant-test']) {
    assert.equal(groups.filter(group => group.includes(`id="${id}"`)).length, 1, id);
  }
});

test('commerce routes show the correct content and preserve plugin capture handoffs', () => {
  const element = (id = '', dataset = {}) => {
    const attributes = {};
    return {
      id, dataset, attributes, hidden: false, textContent: '',
      classList: { toggle() {} }, querySelector: () => null, append() {}, addEventListener() {},
      setAttribute: (name, value) => { attributes[name] = value; },
      removeAttribute: name => { delete attributes[name]; }
    };
  };
  const panels = routes.filter(route => route !== 'product-main-image-styles').map(route => element('', { commercePanel: route }));
  const links = routes.slice(1).map(route => element('', { commerceRoute: route }));
  const headings = ['capture-templates-heading', 'capture-template-workspace', 'main-style-workspace', 'main-style-home', 'ct-main-style-host', 'topbar h2'];
  const nodes = Object.fromEntries(headings.map(id => [id, element(id)]));
  const entered = [];
  const location = { hash: '', pathname: '/', search: '' };
  const context = {
    URLSearchParams,
    CustomEvent: class { constructor(type, options) { this.type = type; this.detail = options.detail; } },
    window: {
      location,
      dispatchEvent: event => entered.push(event),
      history: { replaceState(_state, _title, url) { location.hash = new URL(url, 'https://example.org').hash; } }
    },
    document: {
      title: 'test', createElement: element,
      querySelector: selector => nodes[selector.replace(/^\./, '').replace(/^#/, '')] || element(),
      querySelectorAll: selector => selector === '[data-commerce-panel]' ? panels : links
    }
  };
  vm.createContext(context);
  vm.runInContext(app.slice(app.indexOf('const commerceSections ='), app.indexOf('document.querySelectorAll(".nav-group > summary")')), context);
  vm.runInContext(app.slice(app.indexOf('function syncProductCaptureView()'), app.indexOf('function syncProductCapturePagination(')), context);
  function navigate(hash) {
    location.hash = hash;
    vm.runInContext('selectCommerceSection()', context);
  }
  const visiblePanels = () => panels.filter(panel => !panel.hidden).map(panel => panel.dataset.commercePanel);
  for (const route of routes) {
    navigate('#' + route);
    assert.deepEqual(visiblePanels(), [route === 'product-main-image-styles' ? 'product-capture-templates' : route]);
  }

  navigate('#product-main-image-styles');
  assert.equal(nodes['main-style-workspace'].hidden, false);
  assert.equal(nodes['capture-templates-heading'].hidden, true);
  assert.equal(nodes['topbar h2'].textContent, '商品运营 / 主图风格模板');

  for (const url of ['#product-main-image-styles-v2', '#product-capture-templates?tab=main-image-styles']) {
    navigate(url);
    assert.equal(location.hash, '#product-capture-templates');
    assert.equal(nodes['capture-templates-heading'].hidden, false);
    assert.equal(nodes['capture-template-workspace'].hidden, false);
    assert.equal(nodes['main-style-workspace'].hidden, false);
    assert.equal(nodes['topbar h2'].textContent, '商品运营 v2.0 / 采集模板');
    assert.equal(entered.at(-1).detail.route, 'product-capture-templates');
  }

  navigate('#product-capture-templates');
  assert.equal(nodes['capture-template-workspace'].hidden, false);
  assert.equal(nodes['main-style-workspace'].hidden, false);
  assert.equal(entered.at(-1).detail.route, 'product-capture-templates');

  navigate('#product-capture?id=test-product');
  assert.equal(vm.runInContext('syncProductCaptureView()', context), 'test-product');
  navigate('#product-print-capture?capture=11111111-2222-4333-8444-555555555555');
  assert.equal(location.hash, '#product-print-capture?capture=11111111-2222-4333-8444-555555555555');
  assert.deepEqual(visiblePanels(), ['product-print-capture']);
  location.hash = '#untrusted-route';
  assert.equal(vm.runInContext('commerceRouteFromHash()', context), null);
  for (const [route, module] of [['product-capture', 'CAPTURE'], ['commerce-designs', 'FISSION'], ['commerce-templates', 'TEMPLATES'], ['commerce-ads', 'ADS'], ['commerce-assets', 'ASSETS']]) {
    assert.equal(vm.runInContext(`commerceDataModule("${route}")`, context), module);
  }
});

test('v2 capture omits the URL form and loads selected plugin records by ID', () => {
  const workspace = html.slice(html.indexOf('id="print-capture-workspace"'), html.indexOf('id="capture-templates-page"'));
  assert.doesNotMatch(workspace, /pc-lookup|type="url"|粘贴/);
  assert.match(workspace, /下载 v2.0 插件/);
  assert.doesNotMatch(readFrontend('product-print-capture.js'), /['"`]\/lookup|get\('url'\)/);
});
