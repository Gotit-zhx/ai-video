import { useEffect, useState } from "react";
import { Alert } from "antd";
import type { FinanceRequestParams, Row } from "../../shared/contracts";
import { record, rows, text } from "../../shared/rows";
import { useFinanceSurface } from "../../shared/shadow";
import { useFinance, useFinanceQuery } from "./context";
import { selectionKey } from "./domain";
import { Failure, ScopeEvidence, useVisible } from "./presentation";
import { workspaceStatusPath } from "./reads";

function useOpenDialog() {
  const { overlayContainer } = useFinanceSurface();
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const read = () =>
      setOpen(
        [...overlayContainer.querySelectorAll('[role="dialog"]')].some(
          (dialog) => {
            for (
              let node: Element | null = dialog;
              node && node !== overlayContainer;
              node = node.parentElement
            ) {
              if (
                node.getAttribute("aria-hidden") === "true" ||
                (node as HTMLElement).hidden ||
                (node as HTMLElement).style.display === "none"
              )
                return false;
            }
            return true;
          },
        ),
      );
    read();
    const observer = new MutationObserver(read);
    observer.observe(overlayContainer, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: ["style", "hidden", "aria-hidden"],
    });
    return () => observer.disconnect();
  }, [overlayContainer]);
  return open;
}
/** Only operational facts poll; financial reports and their receipts stay untouched. */
export function useWorkspaceStatus(view: string, enabled = true) {
  const { reads, filters } = useFinance();
  const visible = useVisible(),
    dialog = useOpenDialog();
  const params: FinanceRequestParams = { ...filters, view };
  const query = useFinanceQuery({
    queryKey: [workspaceStatusPath, selectionKey(params)],
    enabled: enabled && visible && !dialog && view !== "live",
    queryFn: ({ signal }) => reads.status(params, signal),
    refetchInterval: (q) =>
      enabled && visible && !dialog && view !== "live" && q.state.data !== null
        ? 15000
        : false,
    refetchIntervalInBackground: false,
  });
  return { ...query, operations: record(query.data?.operations) };
}
export function OperationalEvidence({
  operations,
  error,
}: {
  operations: Row | undefined;
  error: unknown;
}) {
  const { t } = useFinance();
  const failures = rows(operations?.sync).filter((row) =>
    ["ERROR", "FAILED"].includes(text(row.status) || ""),
  );
  const costs = record(operations?.costState);
  return (
    <>
      <Failure error={error} />
      {operations?.adAccountError === true && (
        <Alert type="warning" title={t("广告账户状态暂时不可用")} />
      )}
      <ScopeEvidence
        data={{ settlementCalculation: operations?.settlementCalculation }}
      />
      {failures.length > 0 && (
        <Alert
          type="warning"
          title={t("同步异常")}
          description={failures
            .map(
              (row) =>
                text(row.shopName) || text(row.shopId) || text(row.status),
            )
            .join(" · ")}
        />
      )}
      {(costs?.rebuilding === true || costs?.hasProductJobs === true) && (
        <Alert type="info" title={t("成本正在更新")} />
      )}
    </>
  );
}
