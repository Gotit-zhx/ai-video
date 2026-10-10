import test from "node:test";
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { createImageDohLookup, createImageLookup, parseImageDohAnswer } from "../src/image-transport.js";

const fake = [{ address: "198.18.0.12", family: 4 }, { address: "198.19.1.8", family: 4 }];
const publicAnswer = () => ({ addresses: ["104.18.2.10", "104.18.3.10"], ttlSeconds: 60 });
const system = (addresses = fake) => (_hostname, _options, callback) => callback(null, addresses);
const lookup = (resolver, hostname = "image.example.com", options = { all: true }) => new Promise((resolve, reject) => {
  resolver(hostname, options, (error, addresses, family) => error ? reject(error)
    : resolve(options?.all ? addresses : { address: addresses, family }));
});
const jsonAnswer = (addresses = publicAnswer().addresses, TTL = 60) => Buffer.from(JSON.stringify({
  Status: 0, Answer: addresses.map(data => ({ type: 1, data, TTL }))
}));

test("normal system DNS preserves addresses, order and options without DoH", async () => {
  const addresses = [{ address: "2606:4700::6812:20a", family: 6 }, { address: "10.0.0.1", family: 4 }];
  let observed;
  const resolver = createImageLookup({
    systemLookup: (host, options, callback) => { observed = { host, options }; callback(null, addresses); },
    dohLookup: () => { throw Error("normal DNS must not use DoH"); }
  });
  assert.deepEqual(await lookup(resolver, "internal.example", { all: true, verbatim: true }), addresses);
  assert.deepEqual(observed, { host: "internal.example", options: { all: true, verbatim: true } });
  assert.deepEqual(await lookup(resolver, "internal.example", { family: 4 }), { address: "10.0.0.1", family: 4 });
});

test("system DNS error and mixed real/fake answers do not change resolution policy", async () => {
  const failure = Object.assign(Error("DNS failed"), { code: "ENOTFOUND" });
  const options = { dohLookup: () => { throw Error("DoH must not run"); } };
  await assert.rejects(lookup(createImageLookup({ ...options, systemLookup: (_host, _opts, cb) => cb(failure) })), error => error === failure);
  const mixed = [fake[0], { address: "104.18.2.10", family: 4 }];
  assert.deepEqual(await lookup(createImageLookup({ ...options, systemLookup: system(mixed) })), mixed);
});

test("fake-IP resolution normalizes hostname and supports scalar and all callback forms", async () => {
  let host;
  const resolver = createImageLookup({ systemLookup: system(), dohLookup: async value => { host = value; return publicAnswer(); } });
  assert.deepEqual(await lookup(resolver, "IMAGE.Example.COM.", 4), { address: "104.18.2.10", family: 4 });
  assert.equal(host, "image.example.com");
  assert.deepEqual(await lookup(resolver), publicAnswer().addresses.map(address => ({ address, family: 4 })));
  assert.deepEqual(await new Promise((resolve, reject) => resolver("image.example.com", (error, address, family) => error ? reject(error) : resolve({ address, family }))),
    { address: "104.18.2.10", family: 4 });
  await assert.rejects(lookup(resolver, "image.example.com", { family: 6 }), /requested family/);
});

test("coalesces concurrent DoH lookups and returns independent cached results", async () => {
  let finish;
  let calls = 0;
  const resolver = createImageLookup({ systemLookup: system(), dohLookup: () => { calls += 1; return new Promise(resolve => { finish = resolve; }); } });
  const first = lookup(resolver);
  const second = lookup(resolver);
  await new Promise(setImmediate);
  assert.equal(calls, 1);
  finish(publicAnswer());
  const results = await Promise.all([first, second]);
  results[0][0].address = "127.0.0.1";
  assert.equal(results[1][0].address, "104.18.2.10");
  assert.equal((await lookup(resolver))[0].address, "104.18.2.10");
  assert.equal(calls, 1);
});

test("bounds DNS cache by TTL and LRU size, and does not cache zero-TTL answers", async () => {
  let time = 0;
  let calls = 0;
  const resolver = createImageLookup({ systemLookup: system(), now: () => time, maxCacheEntries: 2,
    dohLookup: async host => { calls += 1; return { ...publicAnswer(), ttlSeconds: host.startsWith("zero") ? 0 : 600 }; } });
  await lookup(resolver, "one.example.com");
  await lookup(resolver, "two.example.com");
  await lookup(resolver, "one.example.com");
  await lookup(resolver, "three.example.com");
  await lookup(resolver, "two.example.com");
  assert.equal(calls, 4);
  time = 300_001;
  await lookup(resolver, "two.example.com");
  assert.equal(calls, 5);
  await lookup(resolver, "zero.example.com");
  await lookup(resolver, "zero.example.com");
  assert.equal(calls, 7);
});

test("bounds fallback hosts and releases slots after failure so later attempts can recover", async () => {
  let rejectFirst;
  let attempts = 0;
  const resolver = createImageLookup({ systemLookup: system(), maxInFlightHosts: 1,
    dohLookup: () => ++attempts === 1 ? new Promise((_resolve, reject) => { rejectFirst = reject; }) : publicAnswer() });
  const first = lookup(resolver, "one.example.com");
  await new Promise(setImmediate);
  await assert.rejects(lookup(resolver, "two.example.com"), error => error.code === "EAI_AGAIN" && /busy/.test(error.message));
  rejectFirst(Error("resolver disconnected"));
  await assert.rejects(first, /disconnected/);
  assert.equal((await lookup(resolver, "two.example.com"))[0].address, "104.18.2.10");
});

test("system DNS and fallback deadlines fail without waiting indefinitely", async () => {
  await assert.rejects(lookup(createImageLookup({ systemLookup: () => {}, systemTimeoutMs: 10 })),
    error => error.code === "EAI_AGAIN" && /system DNS lookup timed out/.test(error.message));
  await assert.rejects(lookup(createImageLookup({ systemLookup: system(), dohLookup: () => new Promise(() => {}), fallbackTimeoutMs: 10 })),
    error => error.code === "EAI_AGAIN" && /fallback timed out/.test(error.message));
});

test("validates all fallback addresses and DNS payloads before connecting", async () => {
  for (const address of ["0.1.2.3", "10.1.2.3", "100.64.1.1", "127.0.0.1", "169.254.1.1", "172.20.1.1", "192.0.2.1", "192.168.1.1", "192.88.99.1", "198.18.1.1", "198.19.1.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::1", "104.18.2.999", "104.18.2.10.example.com"]) {
    assert.throws(() => parseImageDohAnswer(jsonAnswer(["104.18.2.10", address])), /non-public or invalid/, address);
  }
  for (const bytes of [Buffer.from("{}"), Buffer.from("{bad"), Buffer.alloc(65537), jsonAnswer([]),
    Buffer.from(JSON.stringify({ Status: 3, Answer: [] }))]) {
    assert.throws(() => parseImageDohAnswer(bytes));
  }
  const cname = JSON.parse(jsonAnswer().toString());
  cname.Answer.unshift({ type: 5, TTL: 12, data: "cdn.example.com" });
  assert.equal(parseImageDohAnswer(Buffer.from(JSON.stringify(cname))).ttlSeconds, 12);
  await assert.rejects(lookup(createImageLookup({ systemLookup: system(), dohLookup: async () => ({ addresses: ["127.0.0.1"], ttlSeconds: 30 }) })), /non-public or invalid/);
});

function mockRequest(replies, observed = []) {
  return (options, callback) => {
    observed.push(options);
    const req = new EventEmitter();
    req.destroy = () => { req.destroyed = true; };
    req.end = () => queueMicrotask(() => {
      const reply = replies.shift();
      if (reply instanceof Error) { req.emit("error", reply); return; }
      if (reply === null) return;
      const response = new EventEmitter();
      response.statusCode = reply.statusCode ?? 200;
      response.headers = reply.headers ?? {};
      response.complete = true;
      response.destroy = () => { response.destroyed = true; };
      callback(response);
      if (!response.destroyed) {
        response.emit("data", reply.bytes ?? jsonAnswer());
        response.emit("end");
        response.emit("close");
      }
    });
    return req;
  };
}

test("DoH bootstrap preserves TLS hostname validation and queries only trusted resolver hosts", async () => {
  const observed = [];
  const doh = createImageDohLookup({ request: mockRequest([{}], observed) });
  assert.deepEqual(await doh("image.example.com"), publicAnswer());
  const options = observed[0];
  assert.equal(options.hostname, "cloudflare-dns.com");
  assert.equal(options.servername, "cloudflare-dns.com");
  assert.equal(options.rejectUnauthorized, true);
  assert.equal(options.method, "GET");
  assert.equal(options.path, "/dns-query?name=image.example.com&type=A");
  assert.equal(options.agent, false);
  assert.deepEqual(await lookup(options.lookup, "cloudflare-dns.com"), [{ address: "1.1.1.1", family: 4 }]);
  await assert.rejects(lookup(options.lookup, "other.example.com"), /Unconfigured/);
});

test("DoH transport failure selects Google; invalid answers fail closed without another resolver", async () => {
  const observed = [];
  assert.deepEqual(await createImageDohLookup({ request: mockRequest([Error("socket disconnected"), {}], observed) })("image.example.com"), publicAnswer());
  assert.equal(observed[1].hostname, "dns.google");
  assert.equal(observed[1].servername, "dns.google");
  assert.deepEqual(await lookup(observed[1].lookup, "dns.google"), [{ address: "8.8.8.8", family: 4 }]);
  for (const bytes of [jsonAnswer(["127.0.0.1"]), Buffer.from("{bad"), Buffer.from(JSON.stringify({ Status: 3, Answer: [] }))]) {
    const invalidCalls = [];
    await assert.rejects(createImageDohLookup({ request: mockRequest([{ bytes }, {}], invalidCalls) })("image.example.com"));
    assert.equal(invalidCalls.length, 1);
  }
});

test("DoH requests enforce absolute timeout, reject redirects, and bound response bytes", async () => {
  await assert.rejects(createImageDohLookup({ request: mockRequest([null, null]), timeoutMs: 10 })("image.example.com"), /timed out/);
  const observed = [];
  await assert.rejects(createImageDohLookup({ request: mockRequest([
    { statusCode: 302, headers: { location: "http://127.0.0.1" } }, { statusCode: 302 }
  ], observed) })("image.example.com"), /HTTP 302/);
  assert.equal(observed.length, 2);
  assert.deepEqual(observed.map(options => options.hostname), ["cloudflare-dns.com", "dns.google"]);
  await assert.rejects(createImageDohLookup({ request: mockRequest([{ bytes: Buffer.alloc(65537) }, { bytes: Buffer.alloc(65537) }]) })("image.example.com"), /too large/);
});
