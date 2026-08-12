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
  const sessionId = `smoke-agent-${Date.now()}`;
  const turn = async context => {
    const response = await fetch("http://localhost:8090/v1/automation/analyze", {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Internal-Token": "heyi-pi-runtime-dev-token"
      },
      body: JSON.stringify({
        mode: "diagnose",
        sessionId,
        context,
        model: {
          baseUrl: process.env.HEYI_TEST_BASE_URL,
          modelId: process.env.HEYI_TEST_MODEL_ID,
          apiKey
        }
      })
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.message || `HTTP ${response.status}`);
    return data;
  };
  const checkpoint = await turn({
    signalKind: "CHECKPOINT_25",
    progressPercent: 25,
    progressMessage: "已读取任务清单，当前无异常",
    recentEvents: []
  });
  const recovery = await turn({
    signalKind: "RECOVERY",
    attempt: 1,
    maxAttempts: 4,
    progressPercent: 28,
    errorMessage: "页面加载超时，尚未产生文件写入",
    recentEvents: [{
      source: "MODEL",
      type: "MODEL_CHECKPOINT",
      message: checkpoint.result?.userMessage,
      progress: 25
    }]
  });
  const browserTool = await turn({
    signalKind: "RECOVERY",
    attempt: 2,
    maxAttempts: 4,
    progressPercent: 30,
    errorMessage: "脚本返回页面状态未知且没有元素信息，请先观察当前飞跨页面再决定下一步",
    recentEvents: []
  });
  console.log(JSON.stringify({
    modelId: checkpoint.modelId,
    checkpointAction: checkpoint.result?.action,
    recoveryAction: recovery.result?.action,
    recoveryMessage: recovery.result?.userMessage,
    browserToolAction: browserTool.result?.action,
    hasBrowserToolPayload: Boolean(browserTool.result?.browserAction),
    totalLatencyMs: checkpoint.latencyMs + recovery.latencyMs + browserTool.latencyMs
  }));
}

main().catch(error => {
  console.error(error.message);
  process.exitCode = 1;
});
