import type { FinanceFilters, Row } from "../../../shared/contracts";
import { numeric, rows, text } from "../../../shared/rows";
export function accountShopIds(account: Row): string[] {
  return [
    ...new Set(
      [
        text(account.shop_id),
        ...rows(account.stores)
          .filter((store) => store.active && store.match_status === "MATCHED")
          .map((store) => text(store.shop_id)),
      ].filter((id): id is string => !!id),
    ),
  ];
}
function inGroup(shop: Row | undefined, groupId: string) {
  return (
    !groupId ||
    (groupId === "UNGROUPED" ? !shop?.group_id : shop?.group_id === groupId)
  );
}
export function scopedAdAccounts(
  accounts: unknown,
  shops: Row[],
  filters: FinanceFilters,
): Row[] {
  if (filters.platform && filters.platform !== "TIKTOK_SHOP") return [];
  return rows(accounts).filter((account) => {
    if (filters.currency && filters.currency !== account.currency) return false;
    const ids = accountShopIds(account);
    if (filters.shopId && !ids.includes(filters.shopId)) return false;
    return (
      !filters.groupId ||
      (!account.shop_id && filters.groupId === "UNGROUPED") ||
      ids.some((id) =>
        inGroup(
          shops.find((shop) => shop.id === id),
          filters.groupId,
        ),
      )
    );
  });
}
export function scopedAccountStores(
  account: Row,
  shops: Row[],
  filters: FinanceFilters,
): Row[] {
  return rows(account.stores).filter(
    (store) =>
      store.active &&
      store.match_status !== "REMOVED" &&
      (!filters.shopId || store.shop_id === filters.shopId) &&
      inGroup(
        shops.find((shop) => shop.id === store.shop_id),
        filters.groupId,
      ),
  );
}
export function adCoverageRows(
  account: Row,
  shops: Row[],
  filters: FinanceFilters,
): Row[] {
  return rows(account.streams).flatMap((stream) => {
    const store = rows(account.stores).find(
      (item) => item.store_id === stream.store_id,
    );
    if (
      stream.channel !== "BASIC" &&
      (!store?.active ||
        store.match_status === "REMOVED" ||
        (filters.shopId && store.shop_id !== filters.shopId) ||
        (filters.groupId &&
          store.shop_id &&
          !inGroup(
            shops.find((shop) => shop.id === store.shop_id),
            filters.groupId,
          )))
    )
      return [];
    const coverage = rows(account.coverage).find(
        (row) =>
          row.channel === stream.channel && row.store_id === stream.store_id,
      ),
      days = numeric(coverage?.covered_days) || 0,
      expected = numeric(account.requestedDays) || 0;
    const rawCount = numeric(coverage?.records),
      count =
        rawCount !== undefined &&
        Number.isSafeInteger(rawCount) &&
        rawCount >= 0
          ? rawCount
          : undefined;
    const amount = numeric(coverage?.amount);
    const state = !coverage
      ? "尚未取得范围内报表"
      : count === undefined
        ? "返回记录数未核实"
        : count === 0
          ? "接口未返回记录"
          : amount === 0
            ? "报表合计为0"
            : "已返回报表记录";
    return [
      {
        channel: stream.channel,
        store_id: stream.store_id,
        store_name:
          text(
            shops.find((shop) => shop.id === store?.shop_id)?.display_name,
          ) ||
          text(shops.find((shop) => shop.id === store?.shop_id)?.name) ||
          text(store?.store_name) ||
          text(stream.store_id),
        status: stream.status,
        error: stream.error_message,
        covered_days: days,
        requested_days: expected,
        partial: days > 0 && !(days === expected && expected > 0),
        data_state: state,
        records: count,
        amount:
          count !== 0 &&
          amount !== undefined &&
          ((count !== undefined && count > 0) || amount !== 0)
            ? amount
            : undefined,
        currency: account.currency,
        oldest_observation: coverage?.oldest_observation,
        previous: !!coverage && stream.status === "ERROR",
      },
    ];
  });
}
export function scopedAdShops(
  shops: Row[],
  accounts: unknown,
  filters: FinanceFilters,
): Row[] {
  const scoped = scopedAdAccounts(accounts, shops, filters);
  return shops.filter(
    (shop) =>
      shop.platform === "TIKTOK_SHOP" &&
      (!filters.platform || filters.platform === "TIKTOK_SHOP") &&
      (!filters.shopId || shop.id === filters.shopId) &&
      inGroup(shop, filters.groupId) &&
      (!filters.currency ||
        shop.currency === filters.currency ||
        scoped.some((account) =>
          accountShopIds(account).includes(text(shop.id) || ""),
        )),
  );
}
export function missingAdReason(
  shopId: string,
  kind: "independent" | "gmv",
  accounts: unknown,
  failed: boolean,
): string {
  if (failed)
    return kind === "gmv" ? "广告店铺关联状态暂不可用" : "同步状态暂不可用";
  const linked = rows(accounts).filter(
    (account) => !shopId || accountShopIds(account).includes(shopId),
  );
  if (kind === "independent") {
    if (!linked.length) return "尚无可归属的接口数据";
    if (linked.some((account) => account.discovery_status === "PENDING"))
      return "正在自动发现店铺";
    if (
      linked.some(
        (account) =>
          account.discovery_status === "ERROR" ||
          rows(account.streams).some((stream) => stream.status === "ERROR"),
      )
    )
      return "同步异常，自动重试";
    if (shopId && linked.every((account) => account.shop_id !== shopId))
      return "账户总消耗待归属；GMV Max 单列";
    return linked.some((account) =>
      rows(account.streams).some((stream) =>
        ["READY", "SYNCING"].includes(text(stream.status) || ""),
      ),
    )
      ? "自动补齐中"
      : "待核实覆盖范围";
  }
  const links = linked.flatMap((account) =>
    rows(account.stores)
      .filter(
        (store) =>
          store.active &&
          store.match_status === "MATCHED" &&
          store.shop_id === shopId,
      )
      .map((store) => ({ account, store })),
  );
  if (shopId === "UNASSIGNED" && !links.length)
    return "暂无可归属的 GMV Max 记录";
  const discovery = rows(accounts);
  const issue = !discovery.length
    ? "当前筛选暂无已核实的店铺发现结果"
    : discovery.some((account) => account.discovery_status === "ERROR")
      ? "店铺关系发现失败待重试"
      : discovery.some(
            (account) =>
              !["SUCCESS", "EMPTY"].includes(
                text(account.discovery_status) || "",
              ),
          )
        ? "店铺关系发现待完成"
        : "";
  if (issue) return links.length ? "已保留广告店铺关联 · " + issue : issue;
  if (!links.length) return "未关联广告店铺 · 未在当前发现关系范围";
  const eligible = links.filter(({ store }) => store.report_eligible !== false);
  if (!eligible.length) return "已关联广告店铺 · 采集范围待核实";
  const empty = eligible.every(({ account, store }) =>
    rows(account.coverage).some(
      (row) =>
        row.channel !== "BASIC" &&
        row.store_id === store.store_id &&
        numeric(row.records) === 0 &&
        (numeric(row.covered_days) || 0) > 0,
    ),
  );
  return empty
    ? "已关联广告店铺 · 所采集日期接口未返回记录"
    : "已关联广告店铺 · 当前范围报表待返回或补齐";
}
