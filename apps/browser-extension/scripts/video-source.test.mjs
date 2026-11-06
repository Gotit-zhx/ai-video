import assert from "node:assert/strict";
import "../src/shared/video-source.js";

const { identity, deduplicate } = globalThis.HeyiVideoSources;
const asset = "ph-11110105-6v6xa-mqzwevrhc35wbd";
const full = `https://down-aka-ph.vod.susercontent.com/api/v4/11110105/mms/${asset}.16000081784791738.mp4`;
const preview = `https://mms.vod.susercontent.com/api/v4/11110105/mms/${asset}.default.mp4`;
const other = full.replace("mqzwevrhc35wbd", "differentasset123");
assert.equal(identity(full), `shopee-video:${asset}`);
assert.equal(identity(full), identity(preview));
assert.equal(identity(full), identity(`${preview}?token=another-signature#t=1`));
assert.equal(identity(full), identity(preview.replace(".default.mp4", ".mp4")));
assert.equal(identity(full), identity(full.replace("down-aka-ph.vod.susercontent.com", "play-ws.vod.shopee.com:443")));
assert.notEqual(identity(full), identity(other));
assert.deepEqual(deduplicate([{ url: full }, { url: preview }, { url: other }, { url: full }]), [{ url: full }, { url: other }]);
assert.deepEqual(deduplicate([full, preview, other], 1), [{ url: full }]);
// Alternate encodings do not consume the cap and exclude a later distinct asset.
assert.equal(deduplicate([full, preview, `${preview}?v=2`, `${preview}?v=3`, `${preview}?v=4`, other]).length, 2);
for (const url of [
  full.replace("down-aka-ph.vod.susercontent.com", "evil.example"),
  full.replace("down-aka-ph.vod.susercontent.com", "down-aka-ph.vod.susercontent.com.evil.example"),
  full.replace("down-aka-ph.vod.susercontent.com", "user@down-aka-ph.vod.susercontent.com"),
  full.replace("down-aka-ph.vod.susercontent.com", "down-aka-ph.vod.susercontent.com:444"),
  full.replace("https:", "http:"),
  full.replace("/api/v4/", "/unrecognized/"),
  full.replace(".16000081784791738.mp4", ".different-name.mp4"),
  full.replace("/api/v4/", "/ignored/../api/v4/"),
  full.replace("mqzwevrhc35wbd", "MQZWEVRHC35WBD")
]) assert.equal(identity(url), `url:${url}`, "only the known Shopee asset path can discard URL differences");
const tiktokOne = "https://v16.tiktokcdn.com/video.mp4?video=one";
const tiktokTwo = "https://v16.tiktokcdn.com/video.mp4?video=two";
assert.equal(deduplicate([tiktokOne, tiktokTwo, tiktokOne]).length, 2);
assert.deepEqual(deduplicate([null, {}, "blob:page/video", "https://example.com/" + "x".repeat(4000)]), []);
console.log("Video identity tests passed: rendition/CDN dedup, separate assets, unknown URLs, and cap ordering");
