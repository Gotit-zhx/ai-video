import test from "node:test";
import assert from "node:assert/strict";
import {readImageResponse} from "../src/image-stream-response.js";

function stream(text, chunkSize = 7) {
  const bytes = new TextEncoder().encode(text);
  let offset = 0;
  return new Response(new ReadableStream({pull(controller) {
    if (offset >= bytes.length) return controller.close();
    controller.enqueue(bytes.slice(offset, offset += chunkSize));
  }}), {headers: {"content-type": "text/event-stream"}});
}

test("split UTF8/CRLF events and previews produce only the final original image", async () => {
  let activity = 0;
  const response = await readImageResponse(stream(
    ': heartbeat\r\n\r\nevent: image_generation.partial_image\r\ndata: {"b64_json":"preview"}\r\n\r\n'
    + 'event: image_edit.completed\r\ndata: {"b64_json":"final","revised_prompt":"九宫格"}\r\n\r\n'
    + 'data: [DONE]\r\n\r\n'), {onActivity: () => activity++});
  assert.deepEqual(response.payload.data, [{b64_json: "final", revised_prompt: "九宫格"}]);
  assert.ok(activity > 1);
});

test("completed image is returned without waiting for an upstream stream to close", async () => {
  let cancelled = false;
  const response = new Response(new ReadableStream({
    start(controller) {controller.enqueue(new TextEncoder().encode(
      'data: {"type":"image_generation.completed","b64_json":"saved"}\n\n'));},
    cancel() {cancelled = true;}
  }), {headers: {"content-type": "text/event-stream"}});
  assert.equal((await readImageResponse(response)).payload.data[0].b64_json, "saved");
  assert.equal(cancelled, true);
});

test("final streaming usage is retained for accounting", async () => {
  const payload = (await readImageResponse(stream(
    'data: {"type":"image_generation.completed","b64_json":"final","usage":{"total_tokens":123}}\n\n'))).payload;
  assert.equal(payload.usage.total_tokens, 123);
});

test("partial image followed by disconnect is not accepted as a final result", async () => {
  await assert.rejects(readImageResponse(stream(
    'data: {"type":"image_generation.partial_image","b64_json":"partial"}\n\n')),
    error => error.code === "IMAGE_STREAM_INCOMPLETE");
});

test("gateway result envelope and non-stream JSON retain the original payload", async () => {
  const data = {data: [{url: "https://example.test/final.png"}]};
  assert.deepEqual((await readImageResponse(stream(
    `event: image.edit.result\ndata: ${JSON.stringify(data)}\n\n`))).payload, data);
  const objectResult = {...data, object:"image.edit.result"};
  assert.deepEqual((await readImageResponse(stream(
    `data: ${JSON.stringify(objectResult)}\n\n`))).payload, objectResult);
  assert.equal((await readImageResponse(new Response(JSON.stringify(data)))).text, JSON.stringify(data));
});

test("stream errors and oversized responses stop without returning a preview", async () => {
  await assert.rejects(readImageResponse(stream('event: error\ndata: {"error":{"code":"timeout","message":"provider timeout"}}\n\n')),
    error => error.providerResponseError && error.code === "timeout");
  await assert.rejects(readImageResponse(stream('data: {"padding":"too much"}\n\n'), {maxBytes: 10}), /大小上限/);
});
