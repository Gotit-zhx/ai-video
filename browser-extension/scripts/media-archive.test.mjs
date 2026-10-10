import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const values = new Map();
globalThis.chrome = { storage: { local: {
  async get(key) { return { [key]: values.get(key) }; },
  async set(entries) { for (const [key, value] of Object.entries(entries)) values.set(key, value); },
  async remove(key) { values.delete(key); },
  async setAccessLevel() {}
} } };
const { writeAuth } = await import("../src/background/storage.js");
const { archiveCaptureVideos } = await import("../src/background/api.js");
await writeAuth({baseUrl:"http://localhost:3000",accessToken:"active-device-token-media-test",expiresAt:Date.now()+3600000,permissions:["product:collect"]});
const videos = [{url:"https://play-ws.vod.shopee.com/video-1.mp4"},{url:"https://play-ws.vod.shopee.com/video-2.mp4"}];
let failSecondUpload = true, requests = [];
globalThis.fetch = async (url, options) => {
  requests.push({url,method:options?.method || "GET"});
  if (url.startsWith("https://play-ws.vod.shopee.com/")) {
    const response = new Response(new Uint8Array([0,0,0,16,102,116,121,112,105,115,111,109]), {headers:{"Content-Type":"video/mp4"}});
    Object.defineProperty(response,"url",{value:url});
    return response;
  }
  assert.equal(options.headers.Authorization,"Bearer active-device-token-media-test");
  assert.equal(options.body.get("file").size,12);
  if (options.body.get("sourceUrl") === videos[1].url && failSecondUpload) {
    return new Response(JSON.stringify({message:"temporary storage failure"}),{status:503});
  }
  return new Response(JSON.stringify({data:{id:"saved-video"}}),{status:200});
};
const partial = await archiveCaptureVideos("capture-1",videos);
assert.equal(partial.savedCount,1);
assert.equal(partial.totalCount,2);
assert.equal(partial.failedCount,1);
assert.equal(partial.complete,false);
assert.match(partial.message,/1\/2.*重试/);
assert.equal(requests.length,4,"one video failure must not discard the other successful archive");
failSecondUpload = false;
const retried = await archiveCaptureVideos("capture-1",videos);
assert.equal(retried.savedCount,2);
assert.equal(retried.failedCount,0);
assert.equal(retried.complete,true);
assert.equal((await archiveCaptureVideos("capture-1",[])).complete,true);
assert.equal((await archiveCaptureVideos(null,videos)).complete,false);

// The two observed Shopee MP4 encodings are one logical product video.
const fullVideo = "https://down-aka-ph.vod.susercontent.com/api/v4/11110105/mms/ph-11110105-6v6xa-mqzwevrhc35wbd.16000081784791738.mp4";
const previewVideo = "https://mms.vod.susercontent.com/api/v4/11110105/mms/ph-11110105-6v6xa-mqzwevrhc35wbd.default.mp4";
const secondAsset = fullVideo.replace("mqzwevrhc35wbd", "differentasset123");
requests = [];
const savedFetch = globalThis.fetch;
globalThis.fetch = async (url, options) => {
  if (url.startsWith("https://")) {
    requests.push({url,method:"GET"});
    const response = new Response(new Uint8Array([0,0,0,16,102,116,121,112,105,115,111,109]));
    Object.defineProperty(response,"url",{value:url});
    return response;
  }
  return savedFetch(url,options);
};
const deduped = await archiveCaptureVideos("capture-1",[{url:fullVideo},{url:previewVideo},{url:secondAsset}]);
assert.equal(deduped.savedCount,2);
assert.equal(deduped.totalCount,2);
assert.equal(deduped.complete,true);
assert.equal(requests.length,4,"download/upload only once per logical asset");
assert.equal(requests.some(request=>request.url===previewVideo),false,"structured rendition wins over DOM preview");

// Exercise the real click handler: media failure remains clickable; successful
// retry locks the button only once all reported source videos are archived.
const source = readFileSync(new URL("../src/content/inject.js",import.meta.url),"utf8");
const start = source.indexOf("  async function runCapture(control, root)");
const end = source.indexOf("\n\n  function isIdempotentDuplicateConflict",start);
const captureState = new Map(), states = [];
let nextArchive = partial, captureCalls = 0, submittedVideos;
const context = vm.createContext({
  captureState,authenticated:true,MESSAGE_CAPTURE:"capture",
  HeyiVideoSources: globalThis.HeyiVideoSources,
  adapter:{blockedReason:()=>"",extract:async()=>({title:"Product",skuCompleteness:{complete:true},videos:[{url:fullVideo},{url:secondAsset}]})},
  ensureAuthenticated:async()=>true,
  setUrlState:(url,state,message)=>{captureState.set(url,state);states.push({state,message});},
  document:{body:{},querySelector:()=>null,querySelectorAll:selector=>selector === "video" ? [{currentSrc:previewVideo,src:previewVideo,querySelectorAll:()=>[]}] : []},
  chrome:{runtime:{sendMessage:async(message)=>{captureCalls++;submittedVideos=message.payload.videos;return {ok:true,data:{skuCompleteness:{complete:true},videoCompleteness:nextArchive,message:nextArchive.message}};}}}
});
vm.runInContext(`${source.slice(start,end)}\nthis.runCapture=runCapture;`,context);
const control = {productUrl:"https://shopee.ph/full-item-i.123.456",pageType:"DETAIL"};
await context.runCapture(control,{});
assert.equal(captureState.get(control.productUrl),"media_incomplete");
nextArchive = retried;
await context.runCapture(control,{});
assert.equal(captureState.get(control.productUrl),"success");
await context.runCapture(control,{});
assert.equal(captureCalls,2,"retry must run after partial media failure, but not after full success");
assert.deepEqual(submittedVideos,[{url:fullVideo},{url:secondAsset}],"DOM preview must merge into the structured asset before submission");
console.log("Video archive partial failure and retry tests passed");
