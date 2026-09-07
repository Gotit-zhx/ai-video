import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import {
  GARMENT_TOPOLOGY_VALIDATION_MAX_TOKENS,
  GARMENT_TOPOLOGY_VALIDATION_PROMPT as prompt
} from "../src/garment-topology-validation.js";

test("validation JSON preserves legacy fields and adds evidence-based correction targets", () => {
  const jsonExample = prompt.match(/\n\{[\s\S]*?\n\}/)?.[0];
  assert.ok(jsonExample, "prompt must contain a valid JSON response example");
  const result = JSON.parse(jsonExample);
  assert.equal(typeof result.valid, "boolean");
  assert.equal(typeof result.violationCount, "number");
  assert.ok(Array.isArray(result.violations));
  assert.equal(typeof result.correctionPrompt, "string");
  assert.equal(typeof result.confidence, "number");
  assert.ok(Array.isArray(result.correctionTargets));
  assert.deepEqual(Object.keys(result.correctionTargets[0]), [
    "location", "sourceEvidence", "observedError", "repairInstruction", "preserveDetails"
  ]);
  assert.match(prompt, /correctionTargets 是向后兼容的可选补充字段，最多 3 项/);
  assert.match(prompt, /valid=true 时[^\n]*correctionTargets 为空数组/);
});

test("targeted retries preserve off-shoulder design and do not invent missing sleeves", () => {
  assert.match(prompt, /若母版有袖片及袖口，而候选把斜肩款误改成该侧无袖/);
  assert.match(prompt, /恢复该侧袖片、袖口和原有连接/);
  assert.match(prompt, /保留母版斜领路径、原有露肩范围和另一侧袖子/);
  assert.match(prompt, /不得通过抬高领口、遮住露肩区域/);
  assert.match(prompt, /仅因姿势遮挡或裁切看不到袖口，不能直接当成删袖/);
  assert.match(prompt, /不得将真正无袖\/单袖款改成双袖/);
  assert.match(prompt, /无法确认的隐藏结构不得编造/);
});

test("locations distinguish candidate, source and wearer coordinates including grid cells", () => {
  assert.match(prompt, /location 必须标明 MODEL_IMAGE 或九宫格具体格号（01–09）/);
  assert.match(prompt, /“原图画面左\/右”“候选图画面左\/右”和“穿着者自身左\/右”/);
  assert.match(prompt, /九宫格画面侧是该格内部画面侧，不是整张宫格位置/);
  assert.match(prompt, /穿着者侧映射未建立/);
  assert.match(prompt, /不得猜测或镜像/);
});

test("bounded corrections keep full violation counts and all image safety checks", () => {
  assert.match(prompt, /violationCount 必须统计整张候选图中所有相互独立的问题总数/);
  assert.match(prompt, /优先提供有证据的服装结构错误，再提供人体或构图错误/);
  assert.match(prompt, /不得因此漏算其他违规/);
  assert.match(prompt, /出现额外手掌、额外手臂、断开的手、复制或镜像肢体时必须 valid=false/);
  assert.match(prompt, /任一格违规即整图失败/);
  assert.match(prompt, /中近景是与商品结构同级的硬验收条件/);
  assert.match(prompt, /重新生成整张主图或整张九宫格，不是蒙版局部编辑/);
  assert.match(prompt, /不得承诺只修改某个像素区域或其他区域必然不变/);
});

test("the runtime uses a bounded output budget sufficient for detailed targets", () => {
  assert.ok(GARMENT_TOPOLOGY_VALIDATION_MAX_TOKENS >= 1600);
  assert.ok(GARMENT_TOPOLOGY_VALIDATION_MAX_TOKENS <= 2400);
  // Read rather than import the server: this test must not listen on a port or call an API.
  const serverSource = readFileSync(new URL("../src/server.js", import.meta.url), "utf8");
  assert.match(serverSource, /from "\.\/garment-topology-validation\.js"/);
  assert.match(serverSource, /attachments,\s*GARMENT_TOPOLOGY_VALIDATION_MAX_TOKENS,\s*PRODUCT_ANALYSIS_TIMEOUT_MS/);
  assert.match(serverSource, /GARMENT_TOPOLOGY_VALIDATION_V1" \? GARMENT_TOPOLOGY_VALIDATION_MAX_TOKENS/);
});

test("back print validation honors rear evidence without copying the front by default", () => {
  assert.match(prompt, /用户原图可能是独立背面图，也可能是一张包含正背多视角的合成图/);
  assert.match(prompt, /不得依据图片数量假定只有正面/);
  assert.match(prompt, /没有明确背面图案参考或无法确认时，后背应无图案、印花、Logo或文字/);
  assert.match(prompt, /候选复制、镜像、环绕延续正面图案到后背，或自行增加背面图案，必须 valid=false/);
  assert.match(prompt, /没有背面图案证据的纯色后背不得被当成丢失正面印花而判失败/);
  assert.match(prompt, /若原图已经明确给出真实背面花纹，候选擅自抹去或改动则必须判失败/);
  assert.match(prompt, /不得将这些物理细节误当成印花清除/);
  assert.match(prompt, /转身或遮挡时无法确认是背面或无法看清表面内容，不得凭猜测报告背面图案违规/);
  assert.match(prompt, /背面图案违规的纠错目标须注明 MODEL_IMAGE 或具体背身格号/);
  assert.match(prompt, /并保留面料、底色、缝线、背部结构及原正面图案/);
});
