import { lazy, Suspense, useState, type ComponentType } from "react";
import { Alert, Button, Input, Select, Space, Typography } from "antd";
import type { FinanceFilters, Row } from "../../shared/contracts";
import { rows, text } from "../../shared/rows";
import { InfoTip, Panel, StatusTag } from "../../shared/components";
import { Activity, ArrowLeft, ChartNoAxesCombined, CircleDollarSign, ClipboardCheck, FileSpreadsheet, Megaphone, PackageSearch, RefreshCw, ShoppingBag, Store } from "lucide-react";
import { useFinance } from "./context";
import {
  businessToday,
  changeFinanceFilters,
  defaultFinanceFilters,
} from "./domain";
import { Failure, useReport } from "./presentation";
const Reports = lazy(() =>
  import("./reports/ReportsPage").then((module) => ({
    default: module.ReportsPage,
  })),
);
const Records = lazy(() =>
  import("./records/RecordsPage").then((module) => ({
    default: module.RecordsPage,
  })),
);
const Shops = lazy(() =>
  import("./shops/ShopsPage").then((module) => ({ default: module.ShopsPage })),
);
const Costs = lazy(() =>
  import("./costs/CostsPage").then((module) => ({ default: module.CostsPage })),
);
const Charges = lazy(() =>
  import("./charges/ChargesPage").then((module) => ({ default: module.ChargesPage })),
);
const Qianyi = lazy(() =>
  import("./qianyi/QianyiPage").then((module) => ({ default: module.QianyiPage })),
);
export interface FinanceExtensionProps {
  route: string;
}
export interface FinancePageExtensions {
  shops?: ComponentType<FinanceExtensionProps>;
  costs?: ComponentType<FinanceExtensionProps>;
  charges?: ComponentType<FinanceExtensionProps>;
  qianyi?: ComponentType<FinanceExtensionProps>;
  refunds?: ComponentType<FinanceExtensionProps>;
}
export const financePageRegistry = [
  ["overview", "财务总览"],
  ["live", "实时大屏"],
  ["settlements", "结算中心"],
  ["reconciliation", "对账中心"],
  ["orders", "订单与退款"],
  ["fee-records", "费用与调整"],
  ["costs", "成本管理"],
  ["ads", "广告分析"],
  ["shops", "财务店铺管理"],
] as const;
const navigationIcons = {overview:ChartNoAxesCombined,live:Activity,settlements:CircleDollarSign,reconciliation:ClipboardCheck,orders:ShoppingBag,"fee-records":FileSpreadsheet,costs:PackageSearch,ads:Megaphone,shops:Store};
export function normalizeFinanceRoute(route: string): string {
  const value =
    route
      .replace(/^#?\/?finance[-/]/, "")
      .replace(/^\//, "")
      .split("?")[0] || "overview";
  return value === "fees"
    ? "fee-records"
    : financePageRegistry.some(([id]) => id === value) ||
        ["expense-ledger", "qianyi"].includes(value)
      ? value
      : "overview";
}
export function FinanceApplication({
  pages = {},
}: {
  pages?: FinancePageExtensions;
}) {
  const { session, active } = useFinance();
  return active && session.canView ? (
    <Workspace
      key={session.organizationId + ":" + session.userId}
      pages={pages}
    />
  ) : null;
}
function Workspace({ pages }: { pages: FinancePageExtensions }) {
  const {
    host,
    route: hostRoute,
    t,
    navigate,
    filters,
    setFilters,
    refresh,
  } = useFinance();
  const [authorizationNotice, setAuthorizationNotice] = useState(() => host.consumeAuthorizationNotice?.() || null);
  const route = normalizeFinanceRoute(hostRoute),
    live = route === "live";
  const metadata = useReport("/shops", {});
  const shops = rows(metadata.data?.items),
    groups = rows(metadata.data?.groups);
  const options = (items: Row[]) =>
    items.map((row) => ({
      value: text(row.id) || "",
      label: text(row.display_name) || text(row.name) || text(row.id) || "",
    }));
  const change = (patch: Partial<FinanceFilters>) =>
    setFilters((current) => changeFinanceFilters(current, patch));
  const Extension =
    route === "shops"
      ? pages.shops || Shops
      : route === "costs"
        ? pages.costs || Costs
        : route === "expense-ledger"
          ? pages.charges || Charges
          : route === "qianyi"
            ? pages.qianyi || Qianyi
            : undefined;
  const Refunds = pages.refunds || pages.qianyi || Qianyi;
  const recordView = (
    {
      settlements: "SETTLEMENT",
      reconciliation: "RECONCILIATION",
      orders: "ORDER",
      "fee-records": "FEE",
    } as Record<string, string>
  )[route];
  return (
    <main className="finance-workspace">
      <header className="finance-workspace-heading">
        <div>
          <Typography.Title level={2}>{t("新版财务 BI")}</Typography.Title>
          <StatusTag status="processing">{t("正式数据")}</StatusTag>
        </div>
        <a className="finance-return-link" href={host.getLegacyUrl?.(filters) || "?financeUi=legacy#finance-overview"}><ArrowLeft size={16} aria-hidden="true" />{t("返回旧版")}</a>
      </header>
      {authorizationNotice && <Alert type={authorizationNotice.status === "success" ? "success" : "error"} showIcon title={authorizationNotice.message} closable={{onClose:() => setAuthorizationNotice(null)}} />}
      <nav aria-label={t("财务导航")} className="finance-navigation">
        {financePageRegistry.map(([id, label]) => { const Icon = navigationIcons[id]; return (
          <Button
            key={id}
            icon={<Icon size={16} aria-hidden="true" />}
            type={
              route === id ||
              (route === "expense-ledger" && id === "fee-records")
                ? "primary"
                : "default"
            }
            onClick={() => navigate(id)}
          >
            {t(label)}
          </Button>
        );})}
      </nav>
      <Panel title={<Space>{t("查询范围")}<InfoTip title={t(live ? "实时视图临时使用今日与人民币，离开后恢复报表日期和币种。" : "人民币按各业务日期的历史汇率快照展示，缺失、待补齐与真实零值分别保留。")} /></Space>}>
        <Space wrap className="finance-filter-bar">
          <label>
            {t("开始日期")}
            <Input
              type="date"
              aria-label={t("开始日期")}
              value={live ? businessToday() : filters.start}
              disabled={live}
              onChange={(e) => {
                const start = e.target.value;
                if (start)
                  change({
                    start,
                    ...(start > filters.end ? { end: start } : {}),
                  });
              }}
            />
          </label>
          <label>
            {t("结束日期")}
            <Input
              type="date"
              aria-label={t("结束日期")}
              value={live ? businessToday() : filters.end}
              disabled={live}
              onChange={(e) => {
                const end = e.target.value;
                if (end)
                  change({
                    end,
                    ...(end < filters.start ? { start: end } : {}),
                  });
              }}
            />
          </label>
          <label>
            {t("店铺分组")}
            <Select
              value={filters.groupId}
              onChange={(value) => change({ groupId: value, shopId: "" })}
              options={[
                { value: "", label: t("全部分组") },
                { value: "UNGROUPED", label: t("未分组") },
                ...options(groups),
              ]}
            />
          </label>
          <label>
            {t("店铺")}
            <Select
              showSearch={{ optionFilterProp: "label" }}
              value={filters.shopId}
              onChange={(value) => change({ shopId: value })}
              options={[
                { value: "", label: t("全部店铺") },
                ...options(
                  shops.filter(
                    (shop) =>
                      (!filters.platform ||
                        shop.platform === filters.platform) &&
                      (!filters.groupId ||
                        (filters.groupId === "UNGROUPED"
                          ? !shop.group_id
                          : shop.group_id === filters.groupId)),
                  ),
                ),
              ]}
            />
          </label>
          <label>
            {t("平台")}
            <Select
              id="finance-platform"
              aria-label={t("平台")}
              value={filters.platform}
              onChange={(value) => change({ platform: value, shopId: "" })}
              options={[
                { value: "", label: t("全部平台") },
                { value: "SHOPEE", label: "Shopee" },
                { value: "TIKTOK_SHOP", label: "TikTok Shop" },
              ]}
            />
          </label>
          <label>
            {t("原币筛选")}
            <Select
              disabled={live}
              id="finance-native-currency"
              aria-label={t("原币筛选")}
              value={live ? "" : filters.currency}
              onChange={(value) => change({ currency: value })}
              options={[
                {
                  value: "",
                  label: t(
                    live || filters.displayCurrency === "CNY"
                      ? "全部币种（人民币汇总）"
                      : route === "ads"
                        ? "全部原币（分币种展示）"
                        : "自动选择原币",
                  ),
                },
                ...[
                  ...new Set(
                    shops
                      .map((shop) => text(shop.currency))
                      .filter((value): value is string => !!value),
                  ),
                ].map((value) => ({ value, label: value })),
              ]}
            />
          </label>
          <label>
            {t("金额展示币种")}
            <Select
              id="finance-display-currency"
              aria-label={t("金额展示币种")}
              disabled={live}
              value={
                live
                  ? "CNY"
                  : filters.displayCurrency === "CNY"
                    ? "CNY"
                    : "ORIGINAL"
              }
              onChange={(value) => change({ displayCurrency: value })}
              options={[
                { value: "CNY", label: t("人民币 ¥") },
                { value: "ORIGINAL", label: t("原币") },
              ]}
            />
          </label>
          <label>
            {t("趋势粒度")}
            <Select
              disabled={live}
              value={filters.granularity || "day"}
              onChange={(value) => change({ granularity: value })}
              options={[
                { value: "day", label: t("日") },
                { value: "week", label: t("周") },
                { value: "month", label: t("月") },
              ]}
            />
          </label>
          <Button icon={<RefreshCw size={16} aria-hidden="true" />} onClick={() => void refresh()}>{t("刷新")}</Button>
          <Button onClick={() => setFilters(defaultFinanceFilters())}>
            {t("重置范围")}
          </Button>
        </Space>
        <Failure error={metadata.error} />
      </Panel>
      {["fee-records", "expense-ledger"].includes(route) && (
        <Space>
          <Button onClick={() => navigate("fee-records")}>
            {t("平台费用")}
          </Button>
          <Button onClick={() => navigate("expense-ledger")}>
            {t("贴单成本与刷单成本")}
          </Button>
        </Space>
      )}
      <Suspense fallback={<Alert type="info" title={t("正在加载工作区")} />}>
        {["overview", "live", "ads"].includes(route) ? (
          <Reports
            view={route as "overview" | "live" | "ads"}
            initialAdTab={
              new URLSearchParams(hostRoute.split("?")[1] || "").get(
                "source",
              ) || "spend"
            }
          />
        ) : recordView ? (
          <Records
            view={recordView}
            orderScope={
              hostRoute.includes("orderScope=SETTLED") ? "SETTLED" : "ALL"
            }
            {...(Refunds ? { refundContent: <Refunds route="refunds" /> } : {})}
          />
        ) : Extension ? (
          <Extension route={hostRoute} />
        ) : (
          <Alert
            type="warning"
            title={t("此业务工作区尚待接入，请使用旧版完整操作。")}
          />
        )}
      </Suspense>
    </main>
  );
}
