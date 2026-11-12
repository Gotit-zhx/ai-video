import { useEffect, useState } from "react";
import { Alert, Tabs } from "antd";
import { useFinance } from "../context";
import { selectionKey } from "../domain";
import { Failure } from "../presentation";
import { LabelsPage } from "../charges/LabelsPage";
import { CostCatalog } from "./CostCatalog";
import { OrderCosts } from "./OrderCosts";
import { InputReview } from "./InputReview";
import { useCostRead } from "./common";
import { costPath, safeInputs } from "./domain";
export function CostsPage({
  initialTab,
  route,
}: {
  initialTab?: string;
  route?: string;
}) {
  const { active, session, route: hostRoute } = useFinance();
  const supplied = new URLSearchParams(
    (route?.includes("?") ? route : hostRoute).split("?")[1] || "",
  );
  const params = new URLSearchParams();
  for (const key of [
    "tab",
    "recordId",
    "kind",
    "numbers",
    "start",
    "end",
    "costStatus",
  ]) {
    if (supplied.has(key)) params.set(key, supplied.get(key)!);
  }
  const requested = initialTab || params.get("tab") || "labels";
  const tab = [
    "labels",
    "costs",
    "manual",
    "mapping",
    "orders",
    "settings",
    "input-review",
  ].includes(requested)
    ? requested
    : "labels";
  return active && session.canView ? (
    <CostNavigation
      key={session.organizationId + ":" + session.userId}
      initialTab={tab}
      routeParams={params}
    />
  ) : null;
}
function CostNavigation({
  initialTab,
  routeParams,
}: {
  initialTab: string;
  routeParams: URLSearchParams;
}) {
  const { filters } = useFinance();
  const [tab, setTab] = useState(initialTab);
  useEffect(() => setTab(initialTab), [initialTab]);
  return (
    <CostWorkspace
      key={selectionKey({ ...filters })}
      tab={tab}
      setTab={setTab}
      routeParams={routeParams}
    />
  );
}
function CostWorkspace({
  tab,
  setTab,
  routeParams,
}: {
  tab: string;
  setTab: (v: string) => void;
  routeParams: URLSearchParams;
}) {
  const { filters, t, setFilters } = useFinance();
  const query = useCostRead(costPath, {
    shopId: filters.shopId,
    groupId: filters.groupId,
  });
  const data = query.data;
  const entries = [
    ["labels", "月度贴单账单"],
    ["costs", "商品成本表"],
    ["manual", "手动管理"],
    ["mapping", "SKU 关联"],
    ["orders", "订单成本"],
    ["settings", "成本表设置"],
    ...(data && safeInputs(data) ? [["input-review", "导入与操作核对"]] : []),
  ];
  return (
    <>
      <Failure error={query.error} />
      <Tabs
        activeKey={tab}
        onChange={setTab}
        destroyOnHidden
        items={entries.map(([key, label]) => ({
          key: key!,
          label: t(label!),
          children:
            key === "labels" ? (
              <LabelsPage />
            ) : key === "orders" ? (
              <OrderCosts
                initial={Object.fromEntries(routeParams.entries())}
                onMapping={(shopId) => {
                  setTab("mapping");
                  if (shopId !== filters.shopId)
                    setFilters((f) => ({ ...f, shopId }));
                }}
              />
            ) : key === "input-review" ? (
              <InputReview />
            ) : data ? (
              <CostCatalog
                key={key}
                data={data}
                tab={key!}
                onRefresh={() => void query.refetch()}
                onTab={setTab}
              />
            ) : (
              <Alert type="info" title={t("正在读取成本资料")} />
            ),
        }))}
      />
    </>
  );
}
