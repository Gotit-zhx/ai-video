import { readFileSync } from "node:fs";
import vm from "node:vm";
import test from "node:test";
import assert from "node:assert/strict";

const source = readFileSync(new URL("../app.js", import.meta.url), "utf8");
const context = vm.createContext({});
vm.runInContext(source.slice(source.indexOf("function isAiVideoCopyrightError("),
  source.indexOf("function friendlyAiVideoErrorHint(")), context);

test("video preparation distinguishes analysis, explicit rejection and unknown billing state", () => {
  assert.match(context.friendlyAiVideoError("[STRUCTURE_ANALYSIS_PAUSED] 缺少必填字段"), /尚未提交图片/);
  const rejected = "IMAGE_REQUEST_REJECTED: 图片接口拒绝请求（HTTP 400）：The image request is invalid";
  assert.match(context.friendlyAiVideoError(rejected), /接口拒绝请求/);
  assert.equal(context.aiVideoFailureDetail(rejected), rejected);
  assert.match(context.friendlyAiVideoError("IMAGE_RESULT_UNKNOWN: 未收到图片"), /停止自动重复生成/);
  const networkUnknown = "IMAGE_RESULT_UNKNOWN: fetch failed";
  assert.doesNotMatch(context.friendlyAiVideoError(networkUnknown), /正在自动重试/);
  assert.equal(context.aiVideoFailureDetail(networkUnknown), networkUnknown);
});
