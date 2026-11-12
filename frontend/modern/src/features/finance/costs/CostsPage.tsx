import { useEffect, useRef, useState } from "react";
import { Alert, Button, Tabs } from "antd";
import { useQueryClient } from "@tanstack/react-query";
import { record, text } from "../../../shared/rows";
import { useFinance } from "../context";
import { selectionKey } from "../domain";
import { Failure, useOwnerFence } from "../presentation";
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
  const routeKey = selectionKey(Object.fromEntries(routeParams.entries()));
  useEffect(() => setTab(initialTab), [initialTab, routeKey]);
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
  const { filters, t, setFilters, session } = useFinance();
  const queryClient = useQueryClient(),
    owner = useOwnerFence();
  const [refreshBlocked, setRefreshBlocked] = useState(false),
    [refreshError, setRefreshError] = useState<unknown>();
  const refreshGeneration = useRef(0);
  const query = useCostRead(costPath, {
    shopId: filters.shopId,
    groupId: filters.groupId,
  });
  const data = query.data;
  async function reloadWorkspace() {
    if (!owner.isCurrent()) return;
    const generation = ++refreshGeneration.current;
    setRefreshBlocked(true);
    setRefreshError(undefined);
    // Invalidate related scoped results, while the editable workspace performs
    // one explicit fresh read and stays unavailable until that read succeeds.
    const related = queryClient.invalidateQueries({
      queryKey: ["finance", session.organizationId, session.userId],
      predicate: (q) => q.queryKey[3] !== costPath,
    });
    try {
      await query.refetch({ throwOnError: true });
      if (owner.isCurrent() && generation === refreshGeneration.current)
        setRefreshBlocked(false);
    } catch (error) {
      if (owner.isCurrent() && generation === refreshGeneration.current)
        setRefreshError(error);
    }
    await related;
  }
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
      <Failure error={refreshError || query.error} />
      {refreshBlocked && (
        <Alert
          type="info"
          title={t("正在重新读取有效成本资料")}
          action={
            <Button onClick={() => void reloadWorkspace()}>
              {t("重试读取有效成本")}
            </Button>
          }
        />
      )}
      <Tabs
        activeKey={tab}
        onChange={setTab}
        destroyOnHidden
        items={entries.map(([key, label]) => ({
          key: key!,
          label:
            key === "input-review"
              ? `${t(label!)} · ${text(record(data?.inputSafety)?.pendingCount) ?? "—"}`
              : t(label!),
          children:
            key === "labels" ? (
              <LabelsPage />
            ) : key === "orders" ? (
              <OrderCosts
                key={selectionKey(Object.fromEntries(routeParams.entries()))}
                initial={Object.fromEntries(routeParams.entries())}
                onMapping={(shopId) => {
                  setTab("mapping");
                  if (shopId !== filters.shopId)
                    setFilters((f) => ({ ...f, shopId }));
                }}
              />
            ) : key === "input-review" ? (
              <InputReview onChanged={reloadWorkspace} />
            ) : data && !refreshBlocked ? (
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
