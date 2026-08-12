const { spawn } = require("node:child_process");
const fs = require("node:fs/promises");
const path = require("node:path");

function bundledSkillsRoot() {
  return !process.defaultApp && process.resourcesPath
    ? path.join(process.resourcesPath, "bundled-skills")
    : path.resolve(__dirname, "..", "..", "bundled-skills");
}

function safeSegment(value, label) {
  const result = String(value || "").trim();
  if (!/^[A-Za-z0-9._-]{1,100}$/.test(result)) {
    throw new Error(`${label}格式不正确`);
  }
  return result;
}

async function runLocalSkill(command, hooks = {}) {
  const input = command.input || {};
  const skillCode = safeSegment(input.skillCode, "Skill 标识");
  const parameters = input.parameters || {};
  if (skillCode !== "YUE_BILL_EXPORT") {
    throw new Error(`本机助手尚未安装该 Skill：${skillCode}`);
  }
  if (process.platform !== "win32") {
    throw new Error("月账单导出 Skill 当前仅支持 Windows");
  }

  const skillRoot = path.join(bundledSkillsRoot(), "yue-bill-parallel-10");
  const entryPoint = path.join(skillRoot, "run.ps1");
  await fs.access(entryPoint);
  const workbookPath = path.resolve(String(parameters.workbookPath || ""));
  const outputRoot = path.resolve(String(parameters.outputRoot || ""));
  const workbook = await fs.stat(workbookPath);
  if (!workbook.isFile() || path.extname(workbookPath).toLowerCase() !== ".xlsx") {
    throw new Error("请选择本机存在的 .xlsx 店铺工作簿");
  }
  await fs.mkdir(outputRoot, { recursive: true });

  const concurrency = Math.max(1, Math.min(5, Number(parameters.maxConcurrency || 3)));
  const liveSessions = Math.max(
    1,
    Math.min(5, Number(parameters.maxLiveStoreSessions || concurrency))
  );
  const storeLimit = Math.max(
    0,
    Math.min(10000, Number(parameters.storeLimit || 0))
  );
  const allowedPlatforms = new Set([
    "all", "tiktok", "tiktok-global", "tiktok-all", "shopee", "lazada"
  ]);
  const onlyPlatform = String(parameters.onlyPlatform || "all").trim().toLowerCase();
  if (!allowedPlatforms.has(onlyPlatform)) {
    throw new Error("模型生成的平台范围无效");
  }
  const onlyStore = String(parameters.onlyStore || "").trim();
  const officialWarehouseOnly = parameters.officialWarehouseOnly === true
    || String(parameters.officialWarehouseOnly || "").toLowerCase() === "true";
  const tasksPath = path.join(outputRoot, "_run", "tasks.json");
  const reportedUnits = new Map();
  const unitReportPromises = [];
  const collectFiles = task => [...new Set(Object.entries(task || {})
    .filter(([name]) => name.toLowerCase().includes("file"))
    .flatMap(([, value]) => Array.isArray(value) ? value : value ? [value] : [])
    .filter(value => typeof value === "string"))];
  const matchesExecutionScope = task => (
    (onlyPlatform === "all"
      || task.platformKey === onlyPlatform
      || (onlyPlatform === "tiktok-all"
        && ["tiktok", "tiktok-global"].includes(task.platformKey)))
    && (!onlyStore || String(task.storeName || "") === onlyStore)
    && (!officialWarehouseOnly
      || (task.platformKey === "shopee" && task.officialWarehouseOrders === true))
  );
  const normalizeUnitResult = (task, final = false) => {
    const status = String(task.status || "unknown").toLowerCase();
    const outcome = ["completed", "no_data"].includes(status)
      ? "SUCCESS"
      : status === "manual_required"
        ? "NEEDS_ATTENTION"
        : ["failed", "unsupported"].includes(status)
          ? "FAILED"
          : "UNKNOWN";
    return {
      unitType: "STORE",
      unitId: String(task.taskId || `${task.platformKey || "unknown"}:${task.storeName || ""}`),
      unitName: String(task.storeName || task.executorName || "未命名店铺"),
      platform: String(task.platformKey || ""),
      officialWarehouse: task.officialWarehouseOrders === true,
      outcome,
      status,
      final,
      statusText: String(task.statusText || ""),
      terminalCode: String(task.terminalCode || ""),
      message: String(task.message || ""),
      recoveryAttempts: Number(task.recoveryAttempts || 0),
      files: collectFiles(task),
      contractHints: [
        "completed 只有在预期文件已经确认存在且有效时才成立",
        "no_data 是平台已确认本期无文件的有效业务结果",
        "manual_required 表示自动恢复已结束，需要业务人员按 terminalCode 处理",
        "不能仅凭进程退出码判断店铺成功"
      ]
    };
  };
  const finalizeUnitResult = async task => {
    const unit = normalizeUnitResult(task, true);
    const verifiedFiles = [];
    for (const file of unit.files) {
      try {
        if ((await fs.stat(file)).isFile()) verifiedFiles.push(file);
      } catch {
        // Planned paths are not business outputs until a real file exists.
      }
    }
    unit.files = verifiedFiles;
    unit.verifiedFileCount = verifiedFiles.length;
    if (
      unit.outcome !== "SUCCESS"
      && unit.officialWarehouse
      && unit.verifiedFileCount >= 3
    ) {
      unit.originalOutcome = unit.outcome;
      unit.originalStatus = unit.status;
      unit.originalTerminalCode = unit.terminalCode;
      unit.outcome = "SUCCESS";
      unit.status = "completed";
      unit.statusText = "成功";
      unit.terminalCode = "PLATFORM_REQUIRED_FILES_VERIFIED";
      unit.message = "收入、余额及官方仓订单结果文件均已验证存在。";
      unit.reconciledByEvidence = true;
    }
    return unit;
  };
  const readTaskPlan = async () => JSON.parse(await fs.readFile(tasksPath, "utf8"));
  const emitUnit = async (task, final = false) => {
    if (!matchesExecutionScope(task)) return;
    const unit = normalizeUnitResult(task, final);
    if (unit.outcome === "UNKNOWN") return;
    const fingerprint = JSON.stringify([
      unit.status, unit.terminalCode, unit.recoveryAttempts, unit.final
    ]);
    if (reportedUnits.get(unit.unitId) === fingerprint) return;
    reportedUnits.set(unit.unitId, fingerprint);
    if (final) await hooks.onUnitResult?.(unit);
    else await hooks.onUnitUpdate?.(unit);
  };
  const emitFinishedStore = async storeName => {
    try {
      const plan = await readTaskPlan();
      const task = [...(plan.tasks || [])].reverse().find(item =>
        String(item.storeName || "") === storeName
        && matchesExecutionScope(item)
        && !["planned", "running", "pending"].includes(String(item.status || "").toLowerCase())
      );
      if (task) await emitUnit(task, false);
    } catch {
      // The final plan scan below guarantees eventual result reporting.
    }
  };
  const args = [
    "-NoProfile",
    "-ExecutionPolicy", "Bypass",
    "-File", entryPoint,
    "-Workbook", workbookPath,
    "-StartDate", String(parameters.startDate),
    "-EndDate", String(parameters.endDate),
    "-OutputRoot", outputRoot,
    "-Execute",
    "-Resume",
    "-RecoveryPass",
    "-MaxRecoveryAttempts", "3",
    "-MaxTasks", String(storeLimit),
    "-OnlyPlatform", onlyPlatform,
    "-MaxConcurrency", String(concurrency),
    "-MaxLiveStoreSessions", String(liveSessions)
  ];
  if (onlyStore) args.push("-OnlyStore", onlyStore);
  if (officialWarehouseOnly) args.push("-OfficialWarehouseOnly");

  hooks.onProgress?.(5, "正在初始化月账单任务");
  let lineCount = 0;
  const result = await runProcess("powershell.exe", args, (line) => {
    lineCount += 1;
    const finished = line.match(
      /^PARALLEL_WORKER_FINISHED:\s*(.*?)\s+status=([^\s]+)\s+exit=([^\s]+)/
    );
    if (finished) {
      unitReportPromises.push(emitFinishedStore(finished[1].trim()));
    }
    if (line.includes("tasks_path:")) {
      hooks.onProgress?.(95, "正在生成最终账单工作簿");
    } else if (line.includes("YUE_BILL_PARALLEL_10_COMPLETE")) {
      hooks.onProgress?.(99, "月账单导出已完成");
    } else if (/(RECOVERY|RETRY|STALLED|NOT_REACHED|MANUAL_REQUIRED|TIMEOUT|FAILED|异常|失败|超时)/i.test(line)) {
      hooks.onProgress?.(
        Math.min(90, 10 + Math.floor(lineCount / 8)),
        line.trim().slice(0, 300),
        "ANOMALY_SUSPECTED"
      );
    } else if (line.trim()) {
      hooks.onProgress?.(Math.min(90, 10 + Math.floor(lineCount / 8)), line.trim().slice(0, 300));
    }
  }, {
    signal: hooks.signal,
    getControlAction: hooks.getControlAction
  });
  await Promise.allSettled(unitReportPromises);
  let units = [];
  try {
    let plan = await readTaskPlan();
    const terminalCandidates = (plan.tasks || []).filter(matchesExecutionScope);
    for (const task of terminalCandidates) {
      const candidate = await finalizeUnitResult(task);
      if (candidate.outcome === "SUCCESS" || candidate.outcome === "UNKNOWN") continue;
      const advice = await hooks.onTerminalCandidate?.(candidate);
      if (!advice?.continueExecution) continue;
      hooks.onProgress?.(
        94,
        `${candidate.unitName} 尚未形成完整结果，Pi Agent 正在辅助最后恢复`,
        "MODEL_RECOVERY_APPLIED"
      );
      const retryArgs = [
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", entryPoint,
        "-TasksPath", tasksPath,
        "-Execute",
        "-Resume",
        "-RecoveryPass",
        "-IgnoreExistingRecoveryBackoff",
        "-MaxRecoveryAttempts", "6",
        "-OnlyTaskId", candidate.unitId,
        "-OnlyPlatform", onlyPlatform,
        "-MaxConcurrency", "1",
        "-MaxLiveStoreSessions", "1"
      ];
      if (officialWarehouseOnly) retryArgs.push("-OfficialWarehouseOnly");
      try {
        await runProcess("powershell.exe", retryArgs, line => {
          if (line.trim()) {
            hooks.onProgress?.(94, line.trim().slice(0, 300));
          }
        }, {
          signal: hooks.signal,
          getControlAction: hooks.getControlAction
        });
      } catch (retryError) {
        hooks.onProgress?.(
          94,
          `${candidate.unitName} 的辅助恢复未完成：${String(retryError.message || "").slice(-240)}`,
          "ANOMALY_SUSPECTED"
        );
      }
    }
    plan = await readTaskPlan();
    units = [];
    for (const task of (plan.tasks || []).filter(matchesExecutionScope)) {
      const unit = await finalizeUnitResult(task);
      if (unit.outcome === "UNKNOWN") continue;
      units.push(unit);
      await hooks.onUnitResult?.(unit);
    }
  } catch {
    // A missing task plan is already handled by the Skill's process result.
  }
  const count = outcome => units.filter(unit => unit.outcome === outcome).length;
  return {
    skillCode,
    outputRoot,
    completedAt: new Date().toISOString(),
    exitCode: result.exitCode,
    tasksPath: result.tasksPath,
    businessResult: {
      total: units.length,
      succeeded: count("SUCCESS"),
      needsAttention: count("NEEDS_ATTENTION"),
      failed: count("FAILED"),
      units
    }
  };
}

function runProcess(executable, args, onLine, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, {
      windowsHide: true,
      shell: false,
      stdio: ["ignore", "pipe", "pipe"]
    });
    let output = "";
    let errorOutput = "";
    let controlAction = null;
    let terminating = false;
    const terminate = () => {
      if (terminating || !child.pid) return;
      terminating = true;
      controlAction = options.getControlAction?.() || "INTERRUPT";
      const killer = spawn("taskkill.exe", [
        "/PID", String(child.pid), "/T", "/F"
      ], {
        windowsHide: true,
        shell: false,
        stdio: "ignore"
      });
      killer.on("error", () => child.kill());
    };
    if (options.signal) {
      if (options.signal.aborted) terminate();
      else options.signal.addEventListener("abort", terminate, { once: true });
    }
    const accept = (chunk, isError) => {
      const text = chunk.toString("utf8");
      if (isError) errorOutput = (errorOutput + text).slice(-8000);
      else output = (output + text).slice(-16000);
      for (const line of text.split(/\r?\n/)) {
        if (!line.trim()) continue;
        onLine(line);
      }
    };
    child.stdout.on("data", (chunk) => accept(chunk, false));
    child.stderr.on("data", (chunk) => accept(chunk, true));
    child.on("error", reject);
    child.on("close", (exitCode) => {
      if (controlAction) {
        const error = new Error(
          controlAction === "PAUSE"
            ? "本机执行已暂停"
            : controlAction === "CANCEL"
              ? "本机执行已取消"
              : "本机执行已中断"
        );
        error.controlAction = controlAction;
        reject(error);
        return;
      }
      if (exitCode !== 0) {
        reject(new Error(
          (errorOutput || output || `Skill 执行失败（${exitCode}）`).slice(-2000)
        ));
        return;
      }
      const match = output.match(/tasks_path:\s*(.+)/);
      resolve({ exitCode, tasksPath: match?.[1]?.trim() || null });
    });
  });
}

module.exports = { runLocalSkill };
