const crypto = require("node:crypto");

const payload = Buffer.from(process.env.HEYI_TEST_CIPHER.slice(3), "base64");
const key = crypto.createHash("sha256")
  .update(process.env.HEYI_TEST_SECRET)
  .digest();
const decipher = crypto.createDecipheriv(
  "aes-256-gcm",
  key,
  payload.subarray(0, 12)
);
decipher.setAuthTag(payload.subarray(-16));
const apiKey = Buffer.concat([
  decipher.update(payload.subarray(12, -16)),
  decipher.final()
]).toString("utf8");

async function main() {
  const unit = {
    unitType: "STORE",
    unitId: "shopee:official-store-01",
    unitName: "官方仓测试店",
    platform: "shopee",
    outcome: "NEEDS_ATTENTION",
    status: "manual_required",
    statusText: "未进首页，请检查登录",
    terminalCode: "SHOPEE_SELLER_HOME_NOT_REACHED",
    message: "自动恢复 3 次后仍未进入 Seller Center 首页",
    recoveryAttempts: 3,
    files: [],
    contractHints: [
      "manual_required 表示需要业务人员处理",
      "不能仅凭进程退出码判断成功"
    ]
  };
  const response = await fetch("http://localhost:8090/v1/automation/analyze", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Internal-Token": "heyi-pi-runtime-dev-token"
    },
    body: JSON.stringify({
      mode: "result",
      sessionId: `smoke-result-${Date.now()}`,
      context: {
        signalKind: "UNIT_RESULT_SMOKE",
        triggerPayload: unit,
        recentEvents: []
      },
      model: {
        baseUrl: process.env.HEYI_TEST_BASE_URL,
        modelId: process.env.HEYI_TEST_MODEL_ID,
        apiKey
      }
    })
  });
  const data = await response.json();
  if (!response.ok) throw new Error(data.message || `HTTP ${response.status}`);
  console.log(JSON.stringify({
    unitName: data.result?.unitName,
    businessStatus: data.result?.businessStatus,
    resultDescription: data.result?.resultDescription,
    nextAction: data.result?.nextAction,
    modelId: data.modelId,
    latencyMs: data.latencyMs
  }));
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
