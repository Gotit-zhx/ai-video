import type { FinanceRequestParams, Row } from "../../../shared/contracts";
import { record, text } from "../../../shared/rows";
export const costPath = "/workspace/costs";
export const id = (value: unknown) => encodeURIComponent(text(value) || "");
export function costOrderSelection(
  params: FinanceRequestParams,
  pending: boolean,
): FinanceRequestParams {
  const base = {
    ...params,
    kind: params.kind || "SETTLED_ORDER",
    recordId: params.recordId || "",
    costStatus: pending ? "PENDING" : "ALL",
  };
  return pending
    ? {
        ...base,
        shopId: "",
        groupId: "",
        platform: "",
        currency: "",
        recordId: "",
        kind: "SETTLED_ORDER",
      }
    : base;
}
export function operationReceipt(value: unknown): string {
  const r = record(value);
  if (r?.status === "APPLIED")
    return r.duplicate
      ? "相同资料已应用或配置无变化，未重复处理"
      : "操作已应用，相关成本由后台更新";
  if (r?.staged || r?.changeId)
    return `已暂存，尚未应用 · ${text(r.status) || "等待核对"} · ${text(r.changeId) || ""}`;
  if (r?.noChange || r?.nochange) return "配置无变化，未重复应用";
  if (r?.duplicate) return "检测到重复来源，保留原任务";
  return typeof value === "string"
    ? value
    : r?.status
      ? `操作已接收 · ${text(r.status)}`
      : "操作已提交，相关成本由后台更新";
}
const normalize = (s: string) => {
  let v = s
      .replace(/\s+/g, "")
      .replace(/（/g, "(")
      .replace(/）/g, ")")
      .replace(/[×＊]/g, "*"),
    old;
  do {
    old = v;
    v = v.replace(/^\(([^()]+)\)(\*[1-9][0-9]*)?$/, "$1$2");
  } while (old !== v);
  return v;
};
export interface CostCandidate {
  code: string;
  factor: string;
  method: string;
  note: string;
  fixedGift?: boolean;
}
/** Display-only candidate; saving still requires explicit confirmation and server validation. */
export function costCandidate(
  product: Row,
  costs: Row[],
  warehouse: string,
): CostCandidate {
  const sku = (text(product.seller_sku) || "").trim();
  if (sku.toUpperCase() === "GIFT")
    return {
      code: "GIFT",
      factor: "1",
      method: "GIFT",
      note: "赠品 · 固定 0 成本，无需关联",
      fixedGift: true,
    };
  const pending = (note: string): CostCandidate => ({
    code: "",
    factor: "1",
    method: "PENDING",
    note,
  });
  if (!warehouse) return pending("成本仓尚未唯一确定，请核对国家仓");
  const available = costs.filter((c) => c.warehouse === warehouse);
  const match = (code: string, prefix = ""): CostCandidate | undefined => {
    if (!code) return;
    const exact = available.filter((c) => c.sku === code.trim());
    if (exact.length === 1)
      return {
        code: text(exact[0]?.sku)!,
        factor: "1",
        method: prefix + "EXACT_CODE",
        note: "完整编码匹配，需核对单位",
      };
    const same = available.filter(
      (c) => normalize(text(c.sku) || "") === normalize(code),
    );
    if (new Set(same.map((c) => c.sku)).size > 1)
      return pending("规范化编码对应多个型号，需核对");
    if (same.length === 1)
      return {
        code: text(same[0]?.sku)!,
        factor: "1",
        method: prefix + "NORMALIZED_CODE",
        note: "规范化编码匹配，需核对单位",
      };
  };
  const direct = match(sku);
  if (direct?.code) return direct;
  const bundle = normalize(sku).match(/^([^*]+)\*([1-9][0-9]{0,5})$/);
  if (!direct && bundle) {
    const base = match(bundle[1]!);
    if (base?.code)
      return {
        ...base,
        factor: bundle[2]!,
        method: "BUNDLE_CODE",
        note: "组合装候选：请确认换算数量，避免完整套装重复计价",
      };
  }
  const spu = match(text(product.product_id) || "", "SPU_");
  if (spu?.code) return spu;
  return (
    direct ||
    spu ||
    pending("当前成本仓无唯一价格型号，不能自动使用其他国家成本")
  );
}
export function decimalValid(v: string, positive = false) {
  return /^\d{1,18}(\.\d{1,6})?$/.test(v) && (!positive || /[1-9]/.test(v));
}
export const safeInputs = (data: Row) =>
  record(data.inputSafety)?.enabled === true;
export function metadataBody(body: Row, enabled: boolean, metadata: Row): Row {
  return enabled ? { ...body, metadata } : body;
}
