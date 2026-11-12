import type {
  FinanceFilters,
  FinanceRequestParams,
  Row,
} from "../../shared/contracts";
import { emptyFinanceFilters } from "../../shared/contracts";
import { numeric, record, rows, text } from "../../shared/rows";

export function businessToday(now = new Date()): string {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Shanghai",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).format(now);
}
export function defaultFinanceFilters(now = new Date()): FinanceFilters {
  const [year, month] = businessToday(now).split("-").map(Number);
  const end = new Date(Date.UTC(year!, month! - 1, 0))
    .toISOString()
    .slice(0, 10);
  return {
    ...emptyFinanceFilters,
    start: end.slice(0, 8) + "01",
    end,
    displayCurrency: "CNY",
    granularity: "day",
  };
}
export function changeFinanceFilters(
  current: FinanceFilters,
  patch: Partial<FinanceFilters>,
): FinanceFilters {
  const next = { ...current, ...patch, fxSnapshot: "" };
  if (
    ["shopId", "groupId", "platform", "displayCurrency"].some((key) =>
      Object.hasOwn(patch, key),
    )
  )
    next.currency = "";
  if (
    Object.hasOwn(patch, "currency") &&
    !patch.currency &&
    !Object.hasOwn(patch, "displayCurrency")
  )
    next.displayCurrency = "CNY";
  return next;
}
export function exactNumbers(value: string): string {
  const ids = [...new Set(value.split(/[\s,，;；、]+/).filter(Boolean))];
  if (ids.length > 100) throw new Error("一次最多查询 100 个精确编号");
  return ids.join(",");
}
export function selectionKey(params: FinanceRequestParams): string {
  return JSON.stringify(
    Object.entries(params)
      .filter(([key]) => key !== "fxSnapshot")
      .sort(([a], [b]) => a.localeCompare(b)),
  );
}
export function responseScope(
  filters: FinanceFilters,
  data: unknown,
): FinanceRequestParams {
  const source = record(data);
  const resolved = record(source?.filters);
  return {
    ...filters,
    currency: text(resolved?.currency) ?? filters.currency,
    fxSnapshot: text(record(source?.fx)?.id) ?? filters.fxSnapshot,
  };
}
export function liveAverage(total: Row): number | undefined {
  const amount = numeric(total.amount),
    count = numeric(total.known_amounts);
  return amount !== undefined && count !== undefined && count > 0
    ? amount / count
    : undefined;
}
export function bookedAmount(value: unknown): number | undefined {
  const row = record(value);
  if (!row || row.hasRecords === false || numeric(row.records) === 0)
    return undefined;
  if (numeric(row.amount) !== undefined) return numeric(row.amount);
  return numeric(row.known_amounts) === 0
    ? undefined
    : numeric(row.knownAmount);
}
function aggregate(values: unknown[]): number | null {
  return values.length && values.every((value) => numeric(value) !== undefined)
    ? values.reduce<number>((sum, value) => sum + numeric(value)!, 0)
    : null;
}
function knownAggregate(values: unknown[], count = false): number | null {
  if (!values.length) return null;
  const known = values.flatMap((value) =>
    numeric(value) === undefined ? [] : [numeric(value)!],
  );
  return known.length || count
    ? known.reduce((sum, value) => sum + value, 0)
    : null;
}
/** Presentation only: inputs are the complete server ranking, never paginated records. */
export function operatingRows(data: Row, grouped = false): Row[] {
  const shops = rows(data.shops),
    ranking = rows(data.ranking),
    costs = rows(record(data.productCosts)?.shops),
    ads = record(data.advertising);
  const adRows = rows(grouped ? ads?.groups : ads?.shops);
  const groupId = (id: unknown) =>
    text(shops.find((s) => s.id === id)?.group_id) || "";
  const map = new Map<string, Row[]>();
  for (const row of ranking) {
    const id = grouped ? groupId(row.shop_id) : text(row.shop_id) || "";
    const key = id + "\0" + (text(row.currency) || "");
    map.set(key, [...(map.get(key) || []), row]);
  }
  for (const row of [...costs, ...adRows]) {
    const id = grouped
      ? row.group_id !== undefined
        ? text(row.group_id) || ""
        : groupId(row.shop_id)
      : text(row.shop_id) || "";
    if (![...map.keys()].some((key) => key.split("\0")[0] === id))
      map.set(
        id +
          "\0" +
          (text(row.currency) ||
            text(record(data.filters)?.displayCurrency) ||
            "CNY"),
        [],
      );
  }
  return [...map].map(([key, facts]) => {
    const [id, currency] = key.split("\0");
    const kind = (k: string) => facts.filter((r) => r.kind === k);
    const cost = costs.filter((r) =>
      grouped ? groupId(r.shop_id) === id : r.shop_id === id,
    );
    const ad = adRows.find(
      (r) =>
        (text(grouped ? r.group_id : r.shop_id) || "") === id &&
        (!r.currency || r.currency === currency),
    );
    const pending = knownAggregate(
      cost.map((c) => c.pending_count),
      true,
    );
    const partial = (kindName: string) =>
      kind(kindName).some(
        (row) =>
          numeric(row.amount) === undefined ||
          row.fx_complete === false ||
          (numeric(row.known_amounts) !== undefined &&
            numeric(row.records) !== undefined &&
            numeric(row.known_amounts)! < numeric(row.records)!),
      );
    return {
      id,
      currency,
      name: grouped
        ? text(rows(data.groups).find((g) => g.id === id)?.name) || "未分组"
        : text(shops.find((s) => s.id === id)?.display_name) ||
          text(shops.find((s) => s.id === id)?.name) ||
          id,
      order_count: knownAggregate(
        kind("ORDER").map((r) => r.records),
        true,
      ),
      order_amount: knownAggregate(kind("ORDER").map((r) => r.amount)),
      order_amount_partial: partial("ORDER"),
      sales: aggregate(kind("SETTLEMENT").map((r) => r.settled_gross_amount)),
      fees: knownAggregate(kind("FEE").map((r) => r.amount)),
      fees_partial: partial("FEE"),
      advertising: bookedAmount(ad),
      advertising_partial: ad?.complete === false,
      advertising_has_records:
        !!ad && ad.hasRecords !== false && numeric(ad.records) !== 0,
      commission: bookedAmount(record(ad)?.commission),
      commission_partial: record(ad?.commission)?.complete === false,
      gmv: bookedAmount(record(ad)?.gmv),
      gmv_partial: record(ad?.gmv)?.complete === false,
      product_cost_partial: !!pending && pending > 0,
      product_cost_estimated: cost.some(
        (row) => (numeric(row.estimated_count) || 0) > 0,
      ),
      product_cost: knownAggregate(
        cost.map((c) =>
          pending && pending > 0 ? c.known_amount_cny : c.amount_cny,
        ),
      ),
      label_cost: aggregate(cost.map((c) => c.label_cost_cny)),
      pending_count: pending,
    };
  });
}
export const fundingFields = [
  "amount",
  "cash_amount",
  "credit_amount",
  "grant_amount",
] as const;
export function allocationPayload(
  source: Row,
  allocations: Row[],
  shops: Row[],
  organizationId: string,
): Row {
  if (!text(source.fingerprint) || allocations.length > 100)
    throw new Error("来源版本或分配行数无效");
  const used = new Map<string, bigint>(),
    unique = new Set<string>();
  const decimal = (value: unknown) => {
    const str = text(value)?.trim() || "";
    if (!/^-?\d{1,18}(\.\d{1,6})?$/.test(str))
      throw new Error("金额格式、精度或必需分项无效");
    const [whole, fraction = ""] = str.replace("-", "").split(".");
    return BigInt(whole!) * 1000000n + BigInt(fraction.padEnd(6, "0"));
  };
  for (const line of allocations) {
    const id = text(line.shop_id) || "";
    if (
      unique.has(id) ||
      !shops.some((s) => s.id === id && s.platform === source.platform)
    )
      throw new Error("分配店铺重复或平台不一致");
    unique.add(id);
    for (const field of fundingFields) {
      if (numeric(source[field]) === undefined) {
        if (
          line[field] !== undefined &&
          line[field] !== null &&
          line[field] !== ""
        )
          throw new Error("来源未提供的金额不可补造");
        continue;
      }
      const amount = decimal(line[field]),
        max = decimal(source[field]);
      if (
        amount !== 0n &&
        String(line[field]).startsWith("-") !==
          String(source[field]).startsWith("-")
      )
        throw new Error("分配金额符号不一致");
      const sum = (used.get(field) || 0n) + amount;
      if (sum > max) throw new Error("分配金额超过来源");
      used.set(field, sum);
    }
    if (
      fundingFields.slice(1).every((f) => numeric(source[f]) !== undefined) &&
      decimal(line.amount) !==
        decimal(line.cash_amount) +
          decimal(line.credit_amount) +
          decimal(line.grant_amount)
    )
      throw new Error("分项合计不等于总额");
  }
  return { organizationId, fingerprint: source.fingerprint, allocations };
}

/** Calendar dates never use this function; only explicit timestamp fields do. */
export function timestampText(value: unknown, timeZone: string): string {
  const source = text(value);
  if (!source) return "—";
  // JDBC timestamp strings without an offset denote UTC instants in this API.
  const normalized =
    /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}/.test(source) &&
    !/(Z|[+-]\d{2}:?\d{2})$/.test(source)
      ? source.replace(" ", "T") + "Z"
      : source;
  const date = new Date(normalized);
  if (!Number.isFinite(date.getTime())) return source;
  try {
    const parts = Object.fromEntries(
      new Intl.DateTimeFormat("en-CA", {
        timeZone,
        year: "numeric",
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
        hourCycle: "h23",
      })
        .formatToParts(date)
        .map((part) => [part.type, part.value]),
    );
    return `${parts.year}-${parts.month}-${parts.day} ${parts.hour}:${parts.minute}:${parts.second}`;
  } catch {
    return source;
  }
}
