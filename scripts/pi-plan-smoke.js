const crypto = require("node:crypto");

const payload = Buffer.from(process.env.HEYI_TEST_CIPHER.slice(3), "base64");
const key = crypto.createHash("sha256")
  .update(process.env.HEYI_TEST_SECRET)
  .digest();
const iv = payload.subarray(0, 12);
const encrypted = payload.subarray(12, -16);
const tag = payload.subarray(-16);
const decipher = crypto.createDecipheriv("aes-256-gcm", key, iv);
decipher.setAuthTag(tag);
const apiKey = Buffer.concat([
  decipher.update(encrypted),
  decipher.final()
]).toString("utf8");

async function main() {
  const requirement = process.env.HEYI_TEST_REQUIREMENT
    || "工作簿里标成官方仓的那些店一个也别漏，全部做完";
  const response = await fetch("http://localhost:8090/v1/automation/analyze", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      "X-Internal-Token": "heyi-pi-runtime-dev-token"
    },
    body: JSON.stringify({
      mode: "plan",
      sessionId: `smoke-plan-${Date.now()}`,
      context: {
        skillCode: "YUE_BILL_EXPORT",
        skillVersion: "1.0.0",
        userRequirement: requirement,
        currentParameters: {
          startDate: "2026-07-01",
          endDate: "2026-07-31",
          maxConcurrency: 3
        },
        skillDefinition: {
          name: "月账单导出",
          description: "按日期批量导出多平台店铺账单",
          executionParameters: {
            storeLimit: "最多处理的店铺任务数；0 表示所有符合条件的店铺",
            maxConcurrency: "同时执行数，范围 1-5",
            onlyPlatform: "平台筛选，机器值只能是 all、tiktok、tiktok-global、tiktok-all、shopee、lazada",
            onlyStore: "明确的单店名称",
            officialWarehouseOnly: "仅处理工作簿备注或标记属于官方仓的店铺"
          }
        }
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
    requirement,
    summary: data.result?.summary,
    parameterOverrides: data.result?.parameterOverrides,
    confidence: data.result?.confidence,
    modelId: data.modelId,
    latencyMs: data.latencyMs
  }));
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
