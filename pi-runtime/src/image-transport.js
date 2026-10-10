import { lookup as systemDnsLookup } from "node:dns";
import { request as httpsRequest } from "node:https";
import { isIP } from "node:net";
import { domainToASCII } from "node:url";

const RESOLVERS = Object.freeze([
  { hostname: "cloudflare-dns.com", address: "1.1.1.1", path: "/dns-query" },
  { hostname: "dns.google", address: "8.8.8.8", path: "/resolve" }
]);
const MAX_DOH_BYTES = 64 * 1024;

function dnsError(message, code = "ENOTFOUND", cause) {
  return Object.assign(new Error(message, cause ? { cause } : undefined), { code });
}

function isFakeIp(address) {
  return isIP(address) === 4 && /^198\.(18|19)\./.test(address);
}

function isPublicIpv4(address) {
  if (typeof address !== "string" || isIP(address) !== 4) return false;
  const [a, b, c] = address.split(".").map(Number);
  return !(a === 0 || a === 10 || a === 127 || a >= 224
    || (a === 100 && b >= 64 && b <= 127)
    || (a === 169 && b === 254) || (a === 172 && b >= 16 && b <= 31)
    || (a === 192 && (b === 0 || b === 168 || (b === 88 && c === 99)))
    || (a === 198 && (b === 18 || b === 19 || (b === 51 && c === 100)))
    || (a === 203 && b === 0 && c === 113));
}

function publicAnswer(value) {
  if (!Array.isArray(value?.addresses) || !value.addresses.length || value.addresses.length > 64
      || value.addresses.some(address => !isPublicIpv4(address))) {
    throw dnsError("Image DNS fallback returned a non-public or invalid address");
  }
  return { addresses: [...new Set(value.addresses)], ttlSeconds: Number.isFinite(value.ttlSeconds)
    ? Math.max(0, Math.min(300, value.ttlSeconds)) : 0 };
}

export function parseImageDohAnswer(bytes) {
  if (!bytes.length || bytes.length > MAX_DOH_BYTES) throw dnsError("Invalid image DNS response size");
  let body;
  try { body = JSON.parse(bytes.toString("utf8")); }
  catch (cause) { throw dnsError("Invalid image DNS response", "ENOTFOUND", cause); }
  if (body.Status !== 0 || !Array.isArray(body.Answer) || body.Answer.length > 64) {
    throw dnsError("Image DNS did not return a successful answer");
  }
  const addresses = [];
  let ttlSeconds = 300;
  for (const answer of body.Answer) {
    if (answer.type !== 1 && answer.type !== 5) continue;
    ttlSeconds = Math.min(ttlSeconds, Number.isInteger(answer.TTL) ? Math.max(0, answer.TTL) : 0);
    if (answer.type === 1) addresses.push(answer.data);
  }
  return publicAnswer({ addresses, ttlSeconds });
}

function fetchDoh(resolver, hostname, request, timeoutMs) {
  return new Promise((resolve, reject) => {
    let settled = false;
    let req;
    const finish = (error, bytes) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (error) { reject(error); req?.destroy(); }
      else resolve(bytes);
    };
    const timer = setTimeout(() => finish(dnsError("Image DNS request timed out", "EAI_AGAIN")), timeoutMs);
    try {
      req = request({
        protocol: "https:", hostname: resolver.hostname, port: 443,
        path: `${resolver.path}?name=${encodeURIComponent(hostname)}&type=A`,
        method: "GET", headers: { accept: "application/dns-json" },
        // Only DNS resolver bootstrap addresses are fixed. Provider addresses always come from DNS.
        // Keep the TLS peer name and certificate validation even though DNS is bootstrapped directly.
        servername: resolver.hostname, rejectUnauthorized: true, agent: false,
        lookup: (_hostname, options, callback) => {
          if (_hostname !== resolver.hostname) return callback(dnsError("Unconfigured image DNS resolver"));
          if (options?.all) callback(null, [{ address: resolver.address, family: 4 }]);
          else callback(null, resolver.address, 4);
        }
      }, response => {
        if (response.statusCode !== 200) {
          response.destroy();
          finish(dnsError(`Image DNS HTTP ${response.statusCode}`, "EAI_AGAIN"));
          return;
        }
        const declaredSize = Number(response.headers["content-length"] || 0);
        if (declaredSize > MAX_DOH_BYTES) {
          response.destroy();
          finish(dnsError("Image DNS response too large"));
          return;
        }
        const chunks = [];
        let length = 0;
        response.on("data", chunk => {
          length += chunk.length;
          if (length > MAX_DOH_BYTES) {
            response.destroy();
            finish(dnsError("Image DNS response too large"));
          } else chunks.push(Buffer.from(chunk));
        });
        response.on("end", () => finish(null, Buffer.concat(chunks)));
        response.on("error", error => finish(error));
        response.on("aborted", () => finish(dnsError("Image DNS response disconnected", "EAI_AGAIN")));
        response.on("close", () => {
          if (!response.complete) finish(dnsError("Image DNS response incomplete", "EAI_AGAIN"));
        });
      });
      req.on("error", error => finish(error));
      req.end();
    } catch (error) { finish(error); }
  });
}

export function createImageDohLookup({ request = httpsRequest, timeoutMs = 8_000 } = {}) {
  return async hostname => {
    let bytes;
    for (let index = 0; index < RESOLVERS.length; index += 1) {
      try { bytes = await fetchDoh(RESOLVERS[index], hostname, request, timeoutMs); break; }
      catch (error) { if (index === RESOLVERS.length - 1) throw error; }
    }
    // A private, malformed or unsuccessful DNS answer fails closed; it cannot select another resolver.
    return parseImageDohAnswer(bytes);
  };
}

function withDeadline(promise, timeoutMs, message) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(dnsError(message, "EAI_AGAIN")), timeoutMs);
    promise.then(value => { clearTimeout(timer); resolve(value); }, error => { clearTimeout(timer); reject(error); });
  });
}

/** Node/Undici lookup callback. Normal system DNS is unchanged; only proxy fake-IP answers use DoH. */
export function createImageLookup({
  systemLookup = systemDnsLookup, dohLookup = createImageDohLookup(), now = Date.now,
  systemTimeoutMs = 5_000, fallbackTimeoutMs = 18_000, maxCacheEntries = 256, maxInFlightHosts = 64
} = {}) {
  const cache = new Map();
  const inFlight = new Map();

  async function fallback(hostname) {
    const host = domainToASCII(hostname.replace(/\.$/, "")).toLowerCase();
    if (!host || host.length > 253 || !host.split(".").every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label))) {
      throw dnsError("Invalid image DNS hostname");
    }
    const cached = cache.get(host);
    cache.delete(host);
    if (cached?.expiresAt > now()) {
      cache.set(host, cached);
      return cached.addresses;
    }
    if (inFlight.has(host)) return inFlight.get(host);
    if (inFlight.size >= maxInFlightHosts) throw dnsError("Image DNS fallback is busy", "EAI_AGAIN");
    const pending = withDeadline(Promise.resolve().then(() => dohLookup(host)), fallbackTimeoutMs,
      "Image DNS fallback timed out").then(value => {
      const answer = publicAnswer(value);
      const addresses = answer.addresses.map(address => ({ address, family: 4 }));
      if (answer.ttlSeconds > 0 && maxCacheEntries > 0) {
        cache.set(host, { addresses, expiresAt: now() + answer.ttlSeconds * 1_000 });
        while (cache.size > maxCacheEntries) cache.delete(cache.keys().next().value);
      }
      return addresses;
    }).finally(() => { inFlight.delete(host); });
    inFlight.set(host, pending);
    return pending;
  }

  return function imageLookup(hostname, options, callback) {
    if (typeof options === "function") { callback = options; options = {}; }
    if (typeof options === "number") options = { family: options };
    options ||= {};
    const resolve = async () => {
      const answers = await withDeadline(new Promise((resolveDns, rejectDns) => {
        systemLookup(hostname, { ...options, all: true }, (error, addresses) => {
          if (error) rejectDns(error);
          else resolveDns(addresses);
        });
      }), systemTimeoutMs, "Image system DNS lookup timed out");
      if (!Array.isArray(answers) || !answers.length) throw dnsError("No image DNS addresses");
      let result = answers;
      if (answers.every(answer => isFakeIp(answer.address))) result = await fallback(hostname);
      if (options.family === 4 || options.family === 6) result = result.filter(answer => answer.family === options.family);
      if (!result.length) throw dnsError("No image DNS addresses for the requested family");
      return result;
    };
    resolve().then(addresses => {
      if (options.all) callback(null, addresses.map(address => ({ ...address })));
      else callback(null, addresses[0].address, addresses[0].family);
    }, error => callback(error));
  };
}

export const imageLookup = createImageLookup();
