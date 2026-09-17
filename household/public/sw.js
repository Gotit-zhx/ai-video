'use strict';
const CACHE = 'shijian-static-v1';
const STATIC = ['./', 'index.html', 'styles.css', 'app.js', 'manifest.webmanifest', 'icons/icon.svg', 'icons/icon-192.png', 'icons/icon-512.png', 'icons/maskable-512.png', 'icons/apple-touch-icon.png'];
const publicURLs = new Set(STATIC.map(path => new URL(path, self.registration.scope).href));
self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll(STATIC.map(path => new URL(path, self.registration.scope).href))).then(() => self.skipWaiting()));
});
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(key => key.startsWith('shijian-static-') && key !== CACHE).map(key => caches.delete(key)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', event => {
  // Only an explicit allowlist of public files is cached. API/authenticated data never enters the cache.
  if (event.request.method !== 'GET' || !publicURLs.has(event.request.url)) return;
  event.respondWith(fetch(event.request).then(response => {
    if (response.ok) {
      const copy = response.clone();
      event.waitUntil(caches.open(CACHE).then(cache => cache.put(event.request, copy)));
    }
    return response;
  }).catch(() => caches.match(event.request).then(response => response || Response.error())));
});
self.addEventListener('push', event => {
  let data = {title: '拾间', body: '给家一点照顾，打开看看今天的小事。'};
  try { if (event.data) data = {...data, ...event.data.json()}; } catch (_) {}
  let target = new URL('./', self.registration.scope);
  try {
    const candidate = new URL(data.url || './', self.registration.scope);
    if (candidate.origin === target.origin && candidate.pathname.startsWith(target.pathname)) target = candidate;
  } catch (_) {}
  event.waitUntil(self.registration.showNotification(data.title, {
    body: data.body,
    icon: new URL('icons/icon-192.png', self.registration.scope).href,
    badge: new URL('icons/icon-192.png', self.registration.scope).href,
    tag: 'shijian-reminder',
    data: {url: target.href}
  }));
});
self.addEventListener('notificationclick', event => {
  event.notification.close();
  const target = event.notification.data?.url || self.registration.scope;
  event.waitUntil(self.clients.matchAll({type: 'window', includeUncontrolled: true}).then(async clients => {
    const existing = clients.find(client => client.url.startsWith(self.registration.scope));
    if (existing) { await existing.navigate(target); return existing.focus(); }
    return self.clients.openWindow(target);
  }));
});
