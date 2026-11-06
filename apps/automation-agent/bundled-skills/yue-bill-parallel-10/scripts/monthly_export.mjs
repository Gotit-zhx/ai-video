import crypto from "node:crypto";
import fs from "node:fs/promises";
import path from "node:path";
import { FileBlob, SpreadsheetFile } from "@oai/artifact-tool";
import JSZip from "jszip";
import xmlJs from "xml-js";

const { xml2js } = xmlJs;

function parseArgs(argv) {
  const args = {};
  for (let index = 0; index < argv.length; index += 1) {
    const token = argv[index];
    if (!token.startsWith("--")) continue;
    const key = token.slice(2);
    const next = argv[index + 1];
    if (next == null || next.startsWith("--")) {
      args[key] = true;
    } else {
      args[key] = next;
      index += 1;
    }
  }
  return args;
}

function fail(message) {
  console.error(`MONTHLY_BILL_ERROR: ${message}`);
  process.exit(1);
}

function parseDate(value, label) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value ?? "")) {
    fail(`${label} must use YYYY-MM-DD.`);
  }
  const date = new Date(`${value}T00:00:00Z`);
  if (Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== value) {
    fail(`${label} is not a valid calendar date.`);
  }
  return date;
}

function validateDateRange(startDate, endDate) {
  const start = parseDate(startDate, "--start-date");
  const end = parseDate(endDate, "--end-date");
  if (end < start) fail("--end-date must be on or after --start-date.");
  const days = Math.floor((end - start) / 86400000) + 1;
  if (days > 90) fail("The requested date range cannot exceed 90 days.");
}

function normalizeText(value) {
  return value == null ? "" : String(value).trim();
}

function asArray(value) {
  if (value == null) return [];
  return Array.isArray(value) ? value : [value];
}

function parseNameSet(value) {
  return new Set(
    normalizeText(value)
      .split(/[,，;]/)
      .map((item) => normalizeText(item).toLowerCase())
      .filter(Boolean),
  );
}

function normalizeZipTarget(target) {
  let normalized = normalizeText(target).replaceAll("\\", "/").replace(/^\/+/, "");
  if (!normalized.startsWith("xl/")) {
    normalized = path.posix.normalize(path.posix.join("xl", normalized));
  }
  return normalized;
}

function isGrayFill(fill) {
  const patternFill = fill?.patternFill;
  if (normalizeText(patternFill?._attributes?.patternType).toLowerCase() !== "solid") {
    return false;
  }
  const color = patternFill?.fgColor?._attributes ?? {};
  if (color.theme != null) {
    const theme = Number(color.theme);
    const tint = Number(color.tint ?? 0);
    return theme === 0 && tint < -0.05 && tint > -0.95;
  }
  if (color.rgb) {
    const rgb = normalizeText(color.rgb).replace(/^#/, "").slice(-6);
    if (!/^[0-9a-f]{6}$/i.test(rgb)) return false;
    const red = Number.parseInt(rgb.slice(0, 2), 16);
    const green = Number.parseInt(rgb.slice(2, 4), 16);
    const blue = Number.parseInt(rgb.slice(4, 6), 16);
    const nearlyNeutral = Math.max(red, green, blue) - Math.min(red, green, blue) <= 4;
    return nearlyNeutral && red >= 48 && red <= 230;
  }
  const indexed = Number(color.indexed);
  return [22, 23].includes(indexed);
}

async function loadGrayRowKeys(workbookPath) {
  const archive = await JSZip.loadAsync(await fs.readFile(workbookPath));
  const readXml = async (entryName) => {
    const entry = archive.file(entryName);
    if (!entry) fail(`Workbook entry is missing: ${entryName}`);
    return xml2js(await entry.async("string"), { compact: true });
  };

  const workbookXml = await readXml("xl/workbook.xml");
  const relationshipsXml = await readXml("xl/_rels/workbook.xml.rels");
  const stylesXml = await readXml("xl/styles.xml");
  const relationships = new Map(
    asArray(relationshipsXml?.Relationships?.Relationship).map((relationship) => [
      normalizeText(relationship?._attributes?.Id),
      normalizeZipTarget(relationship?._attributes?.Target),
    ]),
  );

  const fills = asArray(stylesXml?.styleSheet?.fills?.fill);
  const grayFillIds = new Set();
  fills.forEach((fill, fillId) => {
    if (isGrayFill(fill)) grayFillIds.add(fillId);
  });
  const cellFormats = asArray(stylesXml?.styleSheet?.cellXfs?.xf);
  const grayStyleIds = new Set();
  cellFormats.forEach((cellFormat, styleId) => {
    const fillId = Number(cellFormat?._attributes?.fillId ?? 0);
    if (grayFillIds.has(fillId)) grayStyleIds.add(styleId);
  });

  const grayRows = new Set();
  for (const sheet of asArray(workbookXml?.workbook?.sheets?.sheet)) {
    const sheetName = normalizeText(sheet?._attributes?.name);
    const relationshipId = normalizeText(
      sheet?._attributes?.["r:id"] ?? sheet?._attributes?.id,
    );
    const sheetEntryName = relationships.get(relationshipId);
    if (!sheetName || !sheetEntryName) continue;
    const sheetXml = await readXml(sheetEntryName);
    for (const row of asArray(sheetXml?.worksheet?.sheetData?.row)) {
      const rowNumber = Number(row?._attributes?.r);
      if (!Number.isInteger(rowNumber) || rowNumber < 2) continue;
      const firstCell = asArray(row?.c).find((cell) => {
        const address = normalizeText(cell?._attributes?.r);
        return /^A\d+$/i.test(address);
      });
      const styleId = Number(
        firstCell?._attributes?.s ?? row?._attributes?.s ?? 0,
      );
      if (grayStyleIds.has(styleId)) {
        grayRows.add(`${sheetName}\u0000${rowNumber}`);
      }
    }
  }
  return grayRows;
}

function extractRemarkPassword(value) {
  const text = normalizeText(value);
  if (!text) return "";
  const match = text.match(
    /(?:导出|站内)?密码\s*[:：]?\s*([^\s,，;；。]+)/i,
  );
  return normalizeText(match?.[1]);
}

function findRemarkColumn(headers) {
  return headers.findIndex((header) => /^(备注|remark|note)$/i.test(header));
}

function isOfficialWarehouseRemark(value) {
  return normalizeText(value).includes("官方仓");
}

function compactDate(value) {
  const [year, month, day] = value.split("-").map(Number);
  return `${year}.${month}.${day}`;
}

function normalizePlatform(value) {
  const text = normalizeText(value).toLowerCase();
  if (!text) return null;
  if (text.includes("tiktok") && (text.includes("跨境") || text.includes("global"))) {
    return "tiktok-global";
  }
  if (text.includes("tiktok") || text === "tk") return "tiktok";
  if (text.includes("shopee") || text.includes("虾皮")) return "shopee";
  if (text.includes("lazada") || text.includes("laz")) return "lazada";
  return "unknown";
}

const platformInfo = {
  tiktok: {
    display: "TikTok",
    executor: "tk-bill",
    supported: true,
    reason: null,
  },
  "tiktok-global": {
    display: "TikTok跨境",
    executor: "tk-bill-cn",
    supported: true,
    reason: null,
  },
  shopee: {
    display: "Shopee",
    executor: "shopp-bill",
    supported: true,
    reason: null,
  },
  lazada: {
    display: "Lazada",
    executor: "lazada-bill",
    supported: true,
    reason: null,
  },
  unknown: {
    display: "未知平台",
    executor: null,
    supported: false,
    reason: "无法识别平台",
  },
};

function supportsTikTokAdCosts(platformKey) {
  return ["tiktok", "tiktok-global"].includes(platformKey);
}

function siteCodesFromText(value) {
  const text = normalizeText(value);
  if (!text) return [];
  const codes = new Set();
  const lines = text.split(/\r?\n/).map((line) => line.trim()).filter(Boolean);
  for (const line of lines) {
    if (/^(PH|菲律宾|菲)$/i.test(line)) codes.add("PH");
    if (/^(TH|泰国|泰)$/i.test(line)) codes.add("TH");
    if (/^(MY|马来西亚|马来)$/i.test(line)) codes.add("MY");
    if (/^(VN|越南)$/i.test(line)) codes.add("VN");
    if (/^(ID|印尼|印度尼西亚)$/i.test(line)) codes.add("ID");
  }
  return [...codes];
}

function embeddedSiteCodes(value) {
  const text = normalizeText(value);
  if (!text) return [];
  const codes = new Set();
  const checks = [
    ["PH", /(^|[\s\-_(（])PH($|[\s\-_)）])/i, /菲律宾/],
    ["TH", /(^|[\s\-_(（])TH($|[\s\-_)）])/i, /泰国/],
    ["MY", /(^|[\s\-_(（])MY($|[\s\-_)）])/i, /马来/],
    ["VN", /(^|[\s\-_(（])VN($|[\s\-_)）])/i, /越南/],
    ["ID", /(^|[\s\-_(（])ID($|[\s\-_)）])/i, /印尼|印度尼西亚/],
  ];
  for (const [code, codePattern, localPattern] of checks) {
    if (codePattern.test(text) || localPattern.test(text)) codes.add(code);
  }
  return [...codes];
}

function inferSite(storeName, rowValues) {
  const exact = [];
  for (const value of rowValues.slice(2)) exact.push(...siteCodesFromText(value));
  const exactUnique = [...new Set(exact)];
  if (exactUnique.length === 1) return exactUnique[0];

  const embedded = embeddedSiteCodes(storeName);
  if (embedded.length === 1) return embedded[0];
  return null;
}

function safeName(value, fallback = "未命名") {
  let result = normalizeText(value)
    .replace(/[<>:"/\\|?*\u0000-\u001f]/g, "_")
    .replace(/[.\s]+$/g, "")
    .trim();
  if (!result) result = fallback;
  if (/^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$/i.test(result)) result = `_${result}`;
  if (result.length > 96) {
    const digest = crypto.createHash("sha256").update(result).digest("hex").slice(0, 10);
    result = `${result.slice(0, 82)}__${digest}`;
  }
  return result;
}

function taskId(task) {
  const raw = [
    task.sheet,
    task.rowNumber,
    task.storeName,
    task.platformKey,
    task.site ?? "",
  ].join("|");
  return crypto.createHash("sha256").update(raw).digest("hex").slice(0, 20);
}

async function saveJsonAtomic(filePath, value) {
  await fs.mkdir(path.dirname(filePath), { recursive: true });
  const temporary = `${filePath}.tmp`;
  await fs.writeFile(temporary, `${JSON.stringify(value, null, 2)}\n`, "utf8");
  await fs.rename(temporary, filePath);
}

async function loadExistingTasks(tasksPath, resume) {
  if (!resume) return new Map();
  try {
    const existing = JSON.parse(await fs.readFile(tasksPath, "utf8"));
    return new Map((existing.tasks ?? []).map((task) => [task.taskId, task]));
  } catch (error) {
    if (error?.code === "ENOENT") return new Map();
    throw error;
  }
}

function mergeExistingState(task, existing) {
  if (!existing) return task;
  if (
    existing.startDate !== task.startDate
    || existing.endDate !== task.endDate
  ) {
    return task;
  }
  const preserved = [
    "status",
    "statusText",
    "terminalCode",
    "files",
    "incomeStatus",
    "incomeTerminalCode",
    "adCostStatus",
    "adCostTerminalCode",
    "adAccountCount",
    "adAccountExports",
    "adCostSummary",
    "shopeeBillingStatus",
    "shopeeBillingTerminalCode",
    "officialWarehouseOrderStatus",
    "officialWarehouseOrderTerminalCode",
    "officialWarehouseDeliveryFilePath",
    "message",
    "updatedAt",
  ];
  for (const key of preserved) {
    if (existing[key] != null) task[key] = existing[key];
  }
  if (supportsTikTokAdCosts(task.platformKey) && existing.incomeStatus == null) {
    if (existing.status === "completed") {
      task.incomeStatus = "completed";
      task.incomeTerminalCode = existing.terminalCode ?? "TIKTOK_EXPORT_FILE_CONFIRMED";
      task.adCostStatus = "planned";
      task.adCostTerminalCode = null;
      task.adAccountExports = [];
      task.status = "planned";
      task.statusText = "待执行";
      task.terminalCode = null;
      task.message = "收入账单已完成；广告成本账单待执行。";
      task.updatedAt = new Date().toISOString();
    } else if (existing.status === "no_data") {
      task.incomeStatus = "no_data";
      task.incomeTerminalCode = existing.terminalCode ?? "TIKTOK_NO_DATA_FOR_PERIOD";
      task.adCostStatus = "planned";
      task.adCostTerminalCode = null;
      task.adAccountExports = [];
      task.status = "planned";
      task.statusText = "待执行";
      task.terminalCode = null;
      task.message = "收入账单无数据；广告成本账单待执行。";
      task.updatedAt = new Date().toISOString();
    }
  }
  if (
    supportsTikTokAdCosts(task.platformKey)
    && task.status === "completed"
    && task.adCostStatus !== "completed"
  ) {
    task.status = "planned";
    task.statusText = "待执行";
    task.terminalCode = null;
    task.message = "广告成本账单尚未完成。";
    task.updatedAt = new Date().toISOString();
  }
  if (
    task.platformKey === "shopee"
    && task.officialWarehouseOrders
    && existing.officialWarehouseOrderStatus == null
    && ["completed", "no_data"].includes(existing.status)
  ) {
    task.shopeeBillingStatus = existing.status === "no_data"
      ? "no_data"
      : "completed";
    task.shopeeBillingTerminalCode = existing.terminalCode;
    task.officialWarehouseOrderStatus = "planned";
    task.officialWarehouseOrderTerminalCode = null;
    task.status = "planned";
    task.statusText = "待执行";
    task.terminalCode = null;
    task.message = "收入和余额已完成；官方仓全部订单待导出。";
    task.updatedAt = new Date().toISOString();
  }
  if (
    task.executorSupported
    && task.status === "unsupported"
    && task.terminalCode === "EXECUTOR_NOT_AVAILABLE"
  ) {
    task.status = "planned";
    task.statusText = "待执行";
    task.terminalCode = null;
    task.files = [];
    task.message = "";
    task.updatedAt = new Date().toISOString();
  }
  if (
    !task.executorSupported
    && (
      existing.executorSupported === true
      || (
        task.status === "unsupported"
        && task.terminalCode === "EXECUTOR_NOT_AVAILABLE"
      )
    )
  ) {
    task.status = "unsupported";
    task.statusText = "暂不支持";
    task.terminalCode = "EXECUTOR_NOT_AVAILABLE";
    task.files = [];
    task.message = task.unsupportedReason;
    task.updatedAt = new Date().toISOString();
  }
  return task;
}

async function fileExists(filePath) {
  try {
    return (await fs.stat(filePath)).isFile();
  } catch (error) {
    if (error?.code === "ENOENT") return false;
    throw error;
  }
}

async function loadJsonIfPresent(filePath) {
  try {
    return JSON.parse(await fs.readFile(filePath, "utf8"));
  } catch (error) {
    if (error?.code === "ENOENT") return null;
    throw error;
  }
}

async function moveFile(sourcePath, targetPath) {
  if (path.resolve(sourcePath).toLowerCase() === path.resolve(targetPath).toLowerCase()) {
    return targetPath;
  }
  await fs.mkdir(path.dirname(targetPath), { recursive: true });
  if (await fileExists(targetPath)) return targetPath;
  try {
    await fs.rename(sourcePath, targetPath);
  } catch (error) {
    if (error?.code !== "EXDEV") throw error;
    await fs.copyFile(sourcePath, targetPath);
    await fs.unlink(sourcePath);
  }
  return targetPath;
}

async function migrateConfirmedFiles(task) {
  const existingFiles = Array.isArray(task.files) ? task.files : [];
  if (
    !["tiktok", "tiktok-global"].includes(task.platformKey)
    || existingFiles.length !== 1
    || path.extname(existingFiles[0]).toLowerCase() !== ".xlsx"
  ) {
    return;
  }
  if (await fileExists(existingFiles[0])) {
    const migrated = await moveFile(existingFiles[0], task.deliveryFilePath);
    task.files = [migrated];
  } else if (await fileExists(task.deliveryFilePath)) {
    task.files = [task.deliveryFilePath];
  }
}

async function removeLegacyStateFiles(directory) {
  let entries;
  try {
    entries = await fs.readdir(directory, { withFileTypes: true });
  } catch (error) {
    if (error?.code === "ENOENT") return;
    throw error;
  }
  for (const entry of entries) {
    const entryPath = path.join(directory, entry.name);
    if (entry.isDirectory()) {
      await removeLegacyStateFiles(entryPath);
    } else if (entry.isFile() && entry.name.toLowerCase() === "任务状态.json") {
      await fs.rm(entryPath, { force: true });
    }
  }
}

async function pruneEmptyDirectories(directory, keepDirectories) {
  let entries;
  try {
    entries = await fs.readdir(directory, { withFileTypes: true });
  } catch (error) {
    if (error?.code === "ENOENT") return;
    throw error;
  }
  for (const entry of entries) {
    if (!entry.isDirectory()) continue;
    await pruneEmptyDirectories(path.join(directory, entry.name), keepDirectories);
  }
  const remaining = await fs.readdir(directory);
  const normalized = path.resolve(directory).toLowerCase();
  if (remaining.length === 0 && !keepDirectories.has(normalized)) {
    await fs.rmdir(directory);
  }
}

async function plan(args) {
  const workbookPath = path.resolve(args.workbook ?? "");
  const outputRoot = path.resolve(args["output-root"] ?? "");
  const startDate = args["start-date"];
  const endDate = args["end-date"];
  const resume = Boolean(args.resume);
  const excludedSheetNames = parseNameSet(args["exclude-sheet"]);
  const skipGrayRows = Boolean(args["skip-gray-rows"]);

  if (!args.workbook) fail("--workbook is required.");
  if (!args["output-root"]) fail("--output-root is required.");
  validateDateRange(startDate, endDate);

  const source = await FileBlob.load(workbookPath);
  const workbook = await SpreadsheetFile.importXlsx(source);
  const grayRowKeys = skipGrayRows
    ? await loadGrayRowKeys(workbookPath)
    : new Set();
  const skippedGrayRows = [];
  const excludedSheets = [];
  const balanceRoot = path.join(outputRoot, "收入板块");
  const balanceDetailRoot = path.join(outputRoot, "余额明细板块");
  const adCostRoot = path.join(outputRoot, "广告收入板块");
  const officialWarehouseRoot = path.join(outputRoot, "官方仓数据");
  const recordRoot = path.join(outputRoot, "_run");
  const tasksPath = path.join(recordRoot, "tasks.json");
  const existingTasks = await loadExistingTasks(tasksPath, resume);
  const tasks = [];

  await fs.mkdir(balanceRoot, { recursive: true });
  await fs.mkdir(balanceDetailRoot, { recursive: true });
  await fs.mkdir(adCostRoot, { recursive: true });
  await fs.mkdir(officialWarehouseRoot, { recursive: true });
  await fs.mkdir(recordRoot, { recursive: true });

  for (const sheet of workbook.worksheets.items) {
    if (excludedSheetNames.has(normalizeText(sheet.name).toLowerCase())) {
      excludedSheets.push(sheet.name);
      continue;
    }
    const used = sheet.getUsedRange(true);
    if (!used) continue;
    const values = used.values;
    const headers = (values[0] ?? []).map(normalizeText);
    const remarkIndex = findRemarkColumn(headers);
    let previousStore = null;
    let previousPlatform = null;

    for (let rowIndex = 1; rowIndex < values.length; rowIndex += 1) {
      const rowValues = values[rowIndex] ?? [];
      const storeName = normalizeText(rowValues[0]);
      if (!storeName) continue;

      const rawPlatform = normalizeText(rowValues[1]);
      let platformKey = normalizePlatform(rawPlatform);
      if (!platformKey && previousStore === storeName) platformKey = previousPlatform;
      if (!platformKey) platformKey = "unknown";
      if (grayRowKeys.has(`${sheet.name}\u0000${rowIndex + 1}`)) {
        skippedGrayRows.push({
          sheet: sheet.name,
          rowNumber: rowIndex + 1,
          storeName,
        });
        previousStore = storeName;
        previousPlatform = platformKey;
        continue;
      }

      const info = platformInfo[platformKey] ?? platformInfo.unknown;
      const site = inferSite(storeName, rowValues);
      const task = {
        taskId: null,
        sheet: sheet.name,
        rowNumber: rowIndex + 1,
        storeName,
        executorName: storeName,
        rawPlatform: rawPlatform || null,
        platformKey,
        platformDisplay: info.display,
        site,
        executor: info.executor,
        executorSupported: info.supported,
        unsupportedReason: info.reason,
        startDate,
        endDate,
        outputDir: null,
        deliveryFilePath: null,
        balanceDetailOutputDir: null,
        balanceDetailDeliveryFilePath: null,
        adCostOutputDir: null,
        officialWarehouseOrders: (
          platformKey === "shopee"
          && remarkIndex >= 0
          && isOfficialWarehouseRemark(rowValues[remarkIndex])
        ),
        officialWarehouseOutputDir: null,
        officialWarehouseDeliveryFilePath: null,
        shopeeBillingStatus: platformKey === "shopee" ? "planned" : null,
        shopeeBillingTerminalCode: null,
        officialWarehouseOrderStatus: null,
        officialWarehouseOrderTerminalCode: null,
        incomeStatus: supportsTikTokAdCosts(platformKey) ? "planned" : null,
        incomeTerminalCode: null,
        adCostStatus: supportsTikTokAdCosts(platformKey) ? "planned" : null,
        adCostTerminalCode: null,
        adAccountCount: null,
        adAccountExports: [],
        adCostSummary: null,
        status: "planned",
        statusText: "待执行",
        terminalCode: null,
        files: [],
        message: info.supported ? "" : info.reason,
        updatedAt: new Date().toISOString(),
      };
      if (task.officialWarehouseOrders) {
        task.officialWarehouseOrderStatus = "planned";
      }
      task.taskId = taskId(task);
      task.groupFolder = safeName(`${sheet.name}${info.display}`);
      tasks.push(task);

      previousStore = storeName;
      previousPlatform = platformKey;
    }
  }

  const duplicateCounts = new Map();
  for (const task of tasks) {
    const key = `${task.groupFolder}\u0000${safeName(task.storeName)}`.toLowerCase();
    duplicateCounts.set(key, (duplicateCounts.get(key) ?? 0) + 1);
  }
  const assignedPaths = new Set();
  const keepDirectories = new Set([path.resolve(balanceRoot).toLowerCase()]);
  const keepBalanceDetailDirectories = new Set([
    path.resolve(balanceDetailRoot).toLowerCase(),
  ]);
  const keepAdCostDirectories = new Set([
    path.resolve(adCostRoot).toLowerCase(),
  ]);
  const keepOfficialWarehouseDirectories = new Set([
    path.resolve(officialWarehouseRoot).toLowerCase(),
  ]);
  for (const task of tasks) {
    const storeBase = safeName(task.storeName);
    const duplicateKey = `${task.groupFolder}\u0000${storeBase}`.toLowerCase();
    const duplicateHint = (duplicateCounts.get(duplicateKey) ?? 0) > 1
      ? `__${task.site ?? `行${task.rowNumber}`}`
      : "";
    let storeFolder = safeName(`${storeBase}${duplicateHint}`);
    let outputDir = path.join(balanceRoot, task.groupFolder, storeFolder);
    if (assignedPaths.has(outputDir.toLowerCase())) {
      storeFolder = safeName(`${storeBase}${duplicateHint}__行${task.rowNumber}`);
      outputDir = path.join(balanceRoot, task.groupFolder, storeFolder);
    }
    assignedPaths.add(outputDir.toLowerCase());
    task.outputDir = outputDir;
    task.deliveryFilePath = path.join(
      outputDir,
      `${safeName(task.storeName)}${endDate}.xlsx`,
    );
    if (["shopee", "lazada"].includes(task.platformKey)) {
      task.balanceDetailOutputDir = path.join(
        balanceDetailRoot,
        task.groupFolder,
        storeFolder,
      );
      task.balanceDetailDeliveryFilePath = path.join(
        task.balanceDetailOutputDir,
        `${safeName(task.storeName)}${endDate}.xlsx`,
      );
    }
    if (supportsTikTokAdCosts(task.platformKey)) {
      task.adCostOutputDir = path.join(
        adCostRoot,
        task.groupFolder,
        storeFolder,
      );
    }
    if (task.officialWarehouseOrders) {
      task.officialWarehouseOutputDir = path.join(
        officialWarehouseRoot,
        task.groupFolder,
        storeFolder,
      );
      task.officialWarehouseDeliveryFilePath = path.join(
        task.officialWarehouseOutputDir,
        `${safeName(task.storeName)}+${compactDate(startDate)}-${compactDate(endDate)}`,
      );
    }
    delete task.groupFolder;
    mergeExistingState(task, existingTasks.get(task.taskId));
    await fs.mkdir(task.outputDir, { recursive: true });
    if (task.balanceDetailOutputDir) {
      await fs.mkdir(task.balanceDetailOutputDir, { recursive: true });
      keepBalanceDetailDirectories.add(
        path.resolve(path.dirname(task.balanceDetailOutputDir)).toLowerCase(),
      );
      keepBalanceDetailDirectories.add(
        path.resolve(task.balanceDetailOutputDir).toLowerCase(),
      );
    }
    if (task.adCostOutputDir) {
      await fs.mkdir(task.adCostOutputDir, { recursive: true });
      keepAdCostDirectories.add(
        path.resolve(path.dirname(task.adCostOutputDir)).toLowerCase(),
      );
      keepAdCostDirectories.add(
        path.resolve(task.adCostOutputDir).toLowerCase(),
      );
    }
    if (task.officialWarehouseOutputDir) {
      await fs.mkdir(task.officialWarehouseOutputDir, { recursive: true });
      keepOfficialWarehouseDirectories.add(
        path.resolve(
          path.dirname(task.officialWarehouseOutputDir),
        ).toLowerCase(),
      );
      keepOfficialWarehouseDirectories.add(
        path.resolve(task.officialWarehouseOutputDir).toLowerCase(),
      );
    }
    await migrateConfirmedFiles(task);
    keepDirectories.add(path.resolve(path.dirname(task.outputDir)).toLowerCase());
    keepDirectories.add(path.resolve(task.outputDir).toLowerCase());
  }
  await removeLegacyStateFiles(balanceRoot);
  await pruneEmptyDirectories(balanceRoot, keepDirectories);
  await removeLegacyStateFiles(balanceDetailRoot);
  await pruneEmptyDirectories(balanceDetailRoot, keepBalanceDetailDirectories);
  await removeLegacyStateFiles(adCostRoot);
  await pruneEmptyDirectories(adCostRoot, keepAdCostDirectories);
  await removeLegacyStateFiles(officialWarehouseRoot);
  await pruneEmptyDirectories(
    officialWarehouseRoot,
    keepOfficialWarehouseDirectories,
  );

  const planDocument = {
    schemaVersion: 7,
    sourceWorkbook: workbookPath,
    outputRoot,
    balanceRoot,
    balanceDetailRoot,
    adCostRoot,
    officialWarehouseRoot,
    startDate,
    endDate,
    dateColumnLabel: `${startDate.replaceAll("-", ".")}-${endDate.replaceAll("-", ".")}`,
    createdAt: new Date().toISOString(),
    selection: {
      excludedSheets,
      skipGrayRows,
      skippedGrayRows,
    },
    tasks,
  };
  await saveJsonAtomic(tasksPath, planDocument);

  const platformCounts = {};
  for (const task of tasks) {
    platformCounts[task.platformDisplay] = (platformCounts[task.platformDisplay] ?? 0) + 1;
  }
  const summary = {
    status: "planned",
    sourceWorkbook: workbookPath,
    outputRoot,
    balanceRoot,
    balanceDetailRoot,
    adCostRoot,
    officialWarehouseRoot,
    tasksPath,
    totalTasks: tasks.length,
    supportedTasks: tasks.filter((task) => task.executorSupported).length,
    unsupportedTasks: tasks.filter((task) => !task.executorSupported).length,
    excludedSheets,
    skippedGrayRows: skippedGrayRows.length,
    platformCounts,
  };
  console.log(`MONTHLY_BILL_PLAN_JSON: ${JSON.stringify(summary)}`);
}

function safeRenderName(value) {
  return normalizeText(value).replace(/[<>:"/\\|?*\u0000-\u001f]/g, "_") || "sheet";
}

function parseCellAddress(value) {
  const match = /^\$?([A-Z]+)\$?(\d+)$/i.exec(value ?? "");
  if (!match) fail(`Unsupported cell address: ${value}`);
  let columnIndex = 0;
  for (const character of match[1].toUpperCase()) {
    columnIndex = columnIndex * 26 + character.charCodeAt(0) - 64;
  }
  return { columnIndex: columnIndex - 1, rowNumber: Number(match[2]) };
}

function columnLabel(columnIndex) {
  let value = columnIndex + 1;
  let label = "";
  while (value > 0) {
    const remainder = (value - 1) % 26;
    label = String.fromCharCode(65 + remainder) + label;
    value = Math.floor((value - 1) / 26);
  }
  return label;
}

async function finalize(args) {
  if (!args.tasks) fail("--tasks is required.");

  const tasksPath = path.resolve(args.tasks);
  const document = JSON.parse(await fs.readFile(tasksPath, "utf8"));
  const sourceWorkbook = path.resolve(document.sourceWorkbook ?? "");
  if (!document.sourceWorkbook) fail("The task plan does not contain sourceWorkbook.");
  if (!Array.isArray(document.tasks)) fail("The task plan does not contain a tasks array.");

  const outputWorkbook = path.resolve(
    args["output-workbook"]
      ?? path.join(
        document.outputRoot,
        `${path.parse(sourceWorkbook).name}_${document.startDate}_${document.endDate}.xlsx`,
      ),
  );
  const renderDir = path.resolve(
    args["render-dir"] ?? path.join(document.outputRoot, "_run", "rendered-workbook"),
  );
  const resultPath = path.join(path.dirname(tasksPath), "finalize-result.json");
  const previousResult = await loadJsonIfPresent(resultPath);
  const sourceStat = await fs.stat(sourceWorkbook);
  const sourceFingerprint = {
    size: sourceStat.size,
    mtimeMs: Math.trunc(sourceStat.mtimeMs),
  };
  const sourceUnchanged = previousResult?.sourceFingerprint?.size === sourceFingerprint.size
    && previousResult?.sourceFingerprint?.mtimeMs === sourceFingerprint.mtimeMs;

  const source = await FileBlob.load(sourceWorkbook);
  const workbook = await SpreadsheetFile.importXlsx(source);
  const excelStatusText = (task) => {
    switch (normalizeText(task.terminalCode)) {
      case "TIKTOK_SELLER_HOME_NOT_REACHED":
      case "SHOPEE_SELLER_HOME_NOT_REACHED":
      case "LAZADA_SELLER_HOME_NOT_REACHED":
        return "未进首页，请检查登录";
      case "SHOPEE_INVALID_PASSWORD":
        return "密码错误，已退出";
      case "SHOPEE_MISSING_PASSWORD":
        return "站内密码为空，已退出";
      case "SHOPEE_PASSWORD_VERIFICATION_NOT_COMPLETED":
        return "站内密码验证未完成，已退出";
      case "LAZADA_INVALID_CREDENTIALS":
        return "账号或密码错误，已退出";
      case "LAZADA_ACCOUNT_DEACTIVATED":
        return "店铺已停用，已退出";
      default:
        return normalizeText(task.statusText);
    }
  };
  const tasksBySheet = new Map();
  for (const task of document.tasks) {
    if (!tasksBySheet.has(task.sheet)) tasksBySheet.set(task.sheet, new Map());
    tasksBySheet.get(task.sheet).set(Number(task.rowNumber), excelStatusText(task));
  }
  const sheetStatusSignatures = {};
  for (const [sheetName, statuses] of tasksBySheet) {
    const serialized = [...statuses.entries()]
      .sort(([left], [right]) => left - right)
      .map(([rowNumber, statusText]) => `${rowNumber}:${statusText}`)
      .join("\n");
    sheetStatusSignatures[sheetName] = crypto
      .createHash("sha256")
      .update(serialized)
      .digest("hex");
  }

  const verification = [];
  for (const sheet of workbook.worksheets.items) {
    const used = sheet.getUsedRange();
    if (!used) continue;
    const values = used.values ?? [];
    const formulas = used.formulas ?? [];
    const rowCount = values.length;
    const columnCount = Math.max(0, ...values.map((row) => row?.length ?? 0));
    if (rowCount === 0 || columnCount < 2) continue;

    const shiftedMergedAreas = [];
    const mergedAreas = typeof sheet.__getMergedCells === "function"
      ? sheet.__getMergedCells()
      : [];
    for (const area of mergedAreas) {
      const start = parseCellAddress(area.startAddress);
      const end = parseCellAddress(area.endAddress);
      if (end.columnIndex < 2) continue;
      sheet.getRange(`${area.startAddress}:${area.endAddress}`).unmerge();
      const shiftedStartColumn = start.columnIndex >= 2
        ? start.columnIndex + 1
        : start.columnIndex;
      const shiftedEndColumn = end.columnIndex + 1;
      shiftedMergedAreas.push(
        `${columnLabel(shiftedStartColumn)}${start.rowNumber}:`
        + `${columnLabel(shiftedEndColumn)}${end.rowNumber}`,
      );
    }

    for (let columnIndex = columnCount - 1; columnIndex >= 2; columnIndex -= 1) {
      const sourceColumn = sheet.getRangeByIndexes(0, columnIndex, rowCount, 1);
      const targetColumn = sheet.getRangeByIndexes(0, columnIndex + 1, rowCount, 1);
      targetColumn.copyFrom(sourceColumn, "all");
    }
    for (let columnIndex = 2; columnIndex < columnCount; columnIndex += 1) {
      let runStart = null;
      let runValues = [];
      const flushRun = () => {
        if (runStart == null || runValues.length === 0) return;
        sheet.getRangeByIndexes(runStart, columnIndex + 1, runValues.length, 1).values = runValues;
        runStart = null;
        runValues = [];
      };
      for (let rowIndex = 0; rowIndex < rowCount; rowIndex += 1) {
        const formula = normalizeText(formulas[rowIndex]?.[columnIndex]);
        if (formula) {
          flushRun();
          continue;
        }
        if (runStart == null) runStart = rowIndex;
        runValues.push([values[rowIndex]?.[columnIndex] ?? null]);
      }
      flushRun();
    }

    const statusByRow = tasksBySheet.get(sheet.name) ?? new Map();
    const statusValues = Array.from({ length: rowCount }, (_, rowIndex) => {
      if (rowIndex === 0) return [document.dateColumnLabel];
      return [statusByRow.get(rowIndex + 1) ?? ""];
    });
    const statusColumn = sheet.getRangeByIndexes(0, 2, rowCount, 1);
    statusColumn.values = statusValues;
    statusColumn.format.columnWidthPx = 420;
    for (const address of shiftedMergedAreas) {
      sheet.getRange(address).merge();
    }

    const checkRange = sheet.getRangeByIndexes(0, 0, Math.min(rowCount, 8), 3);
    const check = await workbook.inspect({
      kind: "region",
      sheetId: sheet.name,
      range: checkRange.address,
      maxChars: 4000,
      tableMaxRows: 8,
      tableMaxCols: 6,
      tableMaxCellChars: 120,
    });
    verification.push({
      sheet: sheet.name,
      range: checkRange.address,
      verified: Boolean(check.ndjson),
    });
  }

  const formulaErrors = await workbook.inspect({
    kind: "match",
    searchTerm: "#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A",
    options: { useRegex: true, maxResults: 300 },
    summary: "final formula error scan",
  });

  await fs.mkdir(path.dirname(outputWorkbook), { recursive: true });
  await fs.mkdir(renderDir, { recursive: true });
  const renderedSheets = [];
  const reusedRenderedSheets = [];
  for (const sheet of workbook.worksheets.items) {
    const renderPath = path.join(renderDir, `${safeRenderName(sheet.name)}.png`);
    const canReuseRender = sourceUnchanged
      && previousResult?.sheetStatusSignatures?.[sheet.name]
        === sheetStatusSignatures[sheet.name]
      && await fileExists(renderPath);
    if (canReuseRender) {
      reusedRenderedSheets.push(sheet.name);
      continue;
    }
    const preview = await workbook.render({
      sheetName: sheet.name,
      autoCrop: "all",
      scale: 1,
      format: "png",
    });
    await fs.writeFile(
      renderPath,
      new Uint8Array(await preview.arrayBuffer()),
    );
    renderedSheets.push(sheet.name);
  }

  const output = await SpreadsheetFile.exportXlsx(workbook);
  await output.save(outputWorkbook);
  await fs.rm(`${outputWorkbook}.inspect.ndjson`, { force: true });

  const summary = {
    status: "finalized",
    sourceWorkbook,
    outputWorkbook,
    renderDir,
    sheetCount: workbook.worksheets.items.length,
    taskCount: document.tasks.length,
    formulaErrors: formulaErrors.ndjson,
    verification,
    sourceFingerprint,
    sheetStatusSignatures,
    renderedSheets,
    reusedRenderedSheets,
  };
  await saveJsonAtomic(resultPath, summary);
  console.log(`MONTHLY_BILL_WORKBOOK_JSON: ${JSON.stringify(summary)}`);
}

async function readSecret(args) {
  const workbookPath = path.resolve(args.workbook ?? "");
  const sheetName = normalizeText(args.sheet);
  const rowNumber = Number.parseInt(args.row ?? "", 10);
  if (!args.workbook || !sheetName || !Number.isInteger(rowNumber) || rowNumber < 2) {
    fail("--workbook, --sheet, and a 1-based --row >= 2 are required.");
  }
  const source = await FileBlob.load(workbookPath);
  const workbook = await SpreadsheetFile.importXlsx(source);
  const sheet = workbook.worksheets.getItem(sheetName);
  const values = sheet.getUsedRange(true)?.values ?? [];
  const headers = (values[0] ?? []).map(normalizeText);
  const remarkIndex = findRemarkColumn(headers);
  if (remarkIndex < 0) return;
  const password = extractRemarkPassword(values[rowNumber - 1]?.[remarkIndex]);
  if (!password) return;
  const encoded = Buffer.from(password, "utf8").toString("base64");
  console.log(`MONTHLY_BILL_SECRET_B64:${encoded}`);
}

const args = parseArgs(process.argv.slice(2));
const command = args.command ?? "plan";
if (command === "plan") {
  await plan(args);
} else if (command === "read-secret") {
  await readSecret(args);
  process.exit(0);
} else if (command === "finalize") {
  await finalize(args);
  process.exit(0);
} else {
  fail(`Unsupported command: ${command}`);
}
