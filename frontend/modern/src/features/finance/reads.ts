import { FinanceApiError, FinanceScopeChangedError } from "../../shared/api";
import type {
  FinanceApi,
  FinanceFilters,
  FinanceRequestParams,
  Row,
} from "../../shared/contracts";
import { numeric, record, text } from "../../shared/rows";
import { responseScope } from "./domain";

export const workspaceQueryPath = "/workspace/query";
export const costContextPath = "/workspace/costs/context";
export const workspaceStatusPath = "/workspace/status";
const costPath = "/workspace/costs";
export interface WorkspaceRead {
  data: Row;
  legacy: boolean;
}
function invalid(): never {
  throw new Error("财务查询响应不完整，请重新读取");
}
function page(value: unknown): Row {
  const data = record(value);
  if (
    !data ||
    !Array.isArray(data.items) ||
    numeric(data.total) === undefined ||
    numeric(data.page) === undefined ||
    numeric(data.pageSize) === undefined
  )
    invalid();
  return data;
}
function envelope(value: unknown): Row {
  const data = record(value);
  if (
    !data ||
    !record(data.filters) ||
    !Array.isArray(data.shops) ||
    !Array.isArray(data.groups) ||
    !["READY", "PENDING"].includes(text(record(data.readModel)?.status) || "")
  )
    invalid();
  return data;
}
/** Page facts remain nested at the wire boundary; outer scope/FX wins when rendered. */
function report(outer: Row, nested: Row, carryOuter = true): Row {
  const result = { ...(carryOuter ? outer : {}), ...nested };
  for (const key of [
    "filters",
    "fx",
    "dateBasis",
    "dateWindows",
    "readModel",
    "updatedAt",
    "settlementCalculation",
  ]) {
    if (Object.hasOwn(outer, key)) result[key] = outer[key];
  }
  return result;
}
export function completeCostWorkspace(value: unknown): value is Row {
  const data = record(value);
  if (!data || data.catalogLoaded === false || data.settingsLoaded === false)
    return false;
  if (
    (data.catalogLoaded !== undefined || data.settingsLoaded !== undefined) &&
    (data.catalogLoaded !== true || data.settingsLoaded !== true)
  )
    return false;
  // Flag-free legacy responses must contain the actual settings and catalogue.
  return (
    Array.isArray(data.costs) &&
    Array.isArray(data.manualCosts) &&
    Array.isArray(data.products) &&
    !!record(data.settings) &&
    !!record(data.mappings) &&
    numeric(data.settingsVersion) !== undefined &&
    numeric(data.settingsVersion)! >= 0
  );
}
/** One instance per provider/owner lifecycle. Unsupported reads never replay writes. */
export function createFinanceReads(
  api: Pick<FinanceApi, "get">,
  isCurrent: () => boolean = () => true,
) {
  const unsupported = new Set<string>();
  async function optional<T>(
    path: string,
    params: FinanceRequestParams,
    signal: AbortSignal | undefined,
    validate: (data: unknown) => T,
  ): Promise<T | undefined> {
    const fence = () => {
      signal?.throwIfAborted();
      if (!isCurrent()) throw new FinanceScopeChangedError();
    };
    fence();
    if (unsupported.has(path)) return undefined;
    let value: unknown;
    try {
      value = await api.get(path, params, signal);
    } catch (error) {
      fence();
      if (
        !(error instanceof FinanceApiError) ||
        ![404, 405].includes(error.status)
      )
        throw error;
      unsupported.add(path);
      return undefined;
    }
    fence();
    return validate(value);
  }
  return {
    async records(
      params: FinanceRequestParams,
      signal?: AbortSignal,
    ): Promise<WorkspaceRead> {
      const outer = await optional(
        workspaceQueryPath,
        params,
        signal,
        envelope,
      );
      if (outer)
        return { data: report(outer, page(outer.records)), legacy: false };
      const { view, ...legacyParams } = params;
      const reconciliation = view === "reconciliation";
      const needsOverview =
        !reconciliation &&
        !(params.kind === "ORDER" && params.orderScope === "ALL");
      const overview = needsOverview
        ? await api.get<Row>("/overview", legacyParams, signal)
        : undefined;
      signal?.throwIfAborted();
      if (!isCurrent()) throw new FinanceScopeChangedError();
      const resolved = responseScope(
        legacyParams as unknown as FinanceFilters,
        overview,
      );
      const data = await api.get<Row>(
        reconciliation ? "/reconciliation" : "/records",
        {
          ...resolved,
          ...(view === "ads-support" && params.kind === "FEE"
            ? { advertisingOnly: true }
            : {}),
        },
        signal,
      );
      return { data: report(overview || {}, data), legacy: true };
    },
    async advertising(
      params: FinanceRequestParams,
      signal?: AbortSignal,
    ): Promise<WorkspaceRead> {
      const outer = await optional(
        workspaceQueryPath,
        { ...params, view: "ads", kind: "AD" },
        signal,
        envelope,
      );
      if (outer) {
        const spend = page(outer.adSpend);
        if (!Array.isArray(outer.adAccounts) && !record(outer.adAccounts))
          invalid();
        return {
          data: { ...outer, adSpend: report(outer, spend, false) },
          legacy: false,
        };
      }
      const { spendPage, ...scope } = params;
      return {
        data: {
          adSpend: await api.get<Row>(
            "/advertising/spend",
            { ...scope, page: spendPage },
            signal,
          ),
        },
        legacy: true,
      };
    },
    async costContext(
      params: FinanceRequestParams,
      signal?: AbortSignal,
    ): Promise<Row> {
      const context = await optional(
        costContextPath,
        params,
        signal,
        (value) => {
          const data = record(value);
          if (
            !data ||
            data.live !== true ||
            data.catalogLoaded !== false ||
            data.settingsLoaded !== false ||
            typeof data.shopId !== "string" ||
            typeof data.groupId !== "string" ||
            typeof data.groupName !== "string"
          )
            invalid();
          return data;
        },
      );
      return context ?? (await api.get<Row>(costPath, params, signal));
    },
    async status(
      params: FinanceRequestParams,
      signal?: AbortSignal,
    ): Promise<Row | null> {
      return (
        (await optional(workspaceStatusPath, params, signal, (value) => {
          const data = record(value);
          if (!data || !record(data.operations)) invalid();
          return data;
        })) ?? null
      );
    },
  };
}
export type FinanceReads = ReturnType<typeof createFinanceReads>;
