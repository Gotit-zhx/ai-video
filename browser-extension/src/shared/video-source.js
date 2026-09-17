(() => {
  "use strict";

  // Shopee exposes the same asset through multiple CDNs and MP4 encodings.
  // Only this known path shape permits stripping a rendition suffix; arbitrary
  // URLs (including other platforms) remain distinct unless exactly equal.
  function identity(raw) {
    try {
      const url = new URL(raw);
      const host = url.hostname.toLowerCase();
      const shopeeCdn = ["vod.susercontent.com", "vod.shopee.com"]
        .some(domain => host.endsWith(`.${domain}`));
      const rawPath = raw.match(/^https:\/\/[^/?#]+([^?#]*)/i)?.[1] || "";
      const match = rawPath.match(/^\/api\/v[0-9]+\/[0-9]+\/mms\/([a-z]{2}-[0-9]+-[a-z0-9]+-[a-z0-9]+)(?:\.(?:default|[0-9]+))?\.mp4$/);
      if (url.protocol === "https:" && !url.username && !url.password && !url.port && shopeeCdn && match) {
        return `shopee-video:${match[1]}`;
      }
    } catch {}
    return `url:${raw}`;
  }

  function deduplicate(videos, limit = 5) {
    const seen = new Set();
    const result = [];
    for (const video of Array.isArray(videos) ? videos : []) {
      const url = typeof video === "string" ? video : video?.url;
      if (typeof url !== "string" || !/^https:\/\//i.test(url) || url.length > 4_000) continue;
      const key = identity(url);
      if (seen.has(key)) continue;
      seen.add(key);
      result.push({ url });
      if (result.length >= limit) break;
    }
    return result;
  }

  globalThis.HeyiVideoSources = Object.freeze({ identity, deduplicate });
})();
