import { act, fireEvent, waitFor, within } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import type { FinanceApi, FinanceRequestParams } from "../src/shared/contracts";
import {
  createFinanceApiFixture,
  createFinanceHostFixture,
  defaultSession,
  renderFinance,
} from "./fixtures";
import { ExportDialog, ExportJobs } from "../src/features/finance/operations";
import { RecordsPage } from "../src/features/finance/records/RecordsPage";
import { useFinance } from "../src/features/finance/context";
function RecordsProbe() {
  const { setFilters } = useFinance();
  return (
    <>
      <button onClick={() => setFilters((f) => ({ ...f, shopId: "b" }))}>
        switch
      </button>
      <RecordsPage view="ORDER" />
    </>
  );
}
it("hides prior rows and disables export when a changed scope fails", async () => {
  const get: FinanceApi["get"] = async <T,>(
    path: string,
    params?: FinanceRequestParams,
  ) => {
    if (path.startsWith("/fields"))
      return {
        fields: [{ id: "order_id", label: "订单编号" }],
        visible: ["order_id"],
      } as T;
    if (params?.shopId === "b") throw new Error("new scope failed");
    return { items: [{ id: "r", order_id: "OLD-PRIVATE" }], total: 1 } as T;
  };
  const v = renderFinance(<RecordsProbe />, {
    api: createFinanceApiFixture({ get }),
  });
  await v.findByText("OLD-PRIVATE");
  fireEvent.click(v.getByText("switch"));
  await v.findByText("new scope failed");
  expect(v.queryByText("OLD-PRIVATE")).not.toBeInTheDocument();
  expect(v.getByRole("button", { name: /导\s*出/ })).toBeDisabled();
});
it("aborts binary download when the finance surface becomes inactive", async () => {
  let signal: AbortSignal | undefined;
  const download: FinanceApi["download"] = async (_p, _q, _f, opts) => {
    signal = opts?.signal;
    return new Promise(() => {});
  };
  const v = renderFinance(
    <ExportDialog
      open
      selection={{ start: "2026-01-01" }}
      onClose={() => {}}
    />,
    { api: createFinanceApiFixture({ download }) },
  );
  fireEvent.click(
    within(v.surface.overlayContainer).getByRole("button", {
      name: "开始导出",
    }),
  );
  await waitFor(() => expect(signal).toBeDefined());
  v.setActive(false);
  expect(signal?.aborted).toBe(true);
});
it("an older job list cannot erase a newly accepted creation", async () => {
  let resolve: ((v: unknown) => void) | undefined;
  const get: FinanceApi["get"] = async <T,>() =>
    new Promise<unknown>((r) => {
      resolve = r;
    }) as Promise<T>;
  const send: FinanceApi["send"] = async <T,>() =>
    ({ id: "new", status: "READY", filename: "accepted.xlsx" }) as T;
  const v = renderFinance(
    <ExportJobs kind="fee" open selection={{}} onClose={() => {}} />,
    { api: createFinanceApiFixture({ get, send }) },
  );
  const overlay = within(v.surface.overlayContainer);
  fireEvent.click(overlay.getByRole("button", { name: "创建导出任务" }));
  await overlay.findByText("accepted.xlsx");
  await act(async () => resolve?.([]));
  expect(overlay.getByText("accepted.xlsx")).toBeInTheDocument();
});
it("owner change removes old job download controls before a fresh list returns", async () => {
  const host = createFinanceHostFixture();
  let count = 0;
  const get: FinanceApi["get"] = async <T,>() => {
    if (count++ === 0)
      return [{ id: "old", status: "READY", filename: "old-owner.xlsx" }] as T;
    return new Promise(() => {});
  };
  const v = renderFinance(
    <ExportJobs kind="cost" open selection={{}} onClose={() => {}} />,
    { host: host.host, api: createFinanceApiFixture({ get }) },
  );
  const overlay = within(v.surface.overlayContainer);
  await overlay.findByText("old-owner.xlsx");
  act(() => host.update({ ...defaultSession, userId: "user-2" }));
  expect(overlay.queryByText("old-owner.xlsx")).not.toBeInTheDocument();
});
function ReceiptProbe() {
  const { filters } = useFinance();
  return (
    <>
      <RecordsPage view="ORDER" />
      <output aria-label="snapshot">{filters.fxSnapshot}</output>
    </>
  );
}
it("carries the resolved historical snapshot into subsequent page requests", async () => {
  const requests: FinanceRequestParams[] = [];
  const get: FinanceApi["get"] = async <T,>(
    path: string,
    params?: FinanceRequestParams,
  ) => {
    if (path.startsWith("/fields"))
      return {
        fields: [{ id: "order_id", label: "订单编号" }],
        visible: ["order_id"],
      } as T;
    requests.push(params || {});
    return {
      items: [{ id: "r", order_id: "ORDER-1" }],
      total: 101,
      fx: { id: "history-october" },
    } as T;
  };
  const v = renderFinance(<ReceiptProbe />, {
    api: createFinanceApiFixture({ get }),
  });
  await v.findByText("ORDER-1");
  await waitFor(() =>
    expect(v.getByLabelText("snapshot")).toHaveTextContent("history-october"),
  );
  fireEvent.click(v.getByRole("button", { name: "Page 2" }));
  await waitFor(() =>
    expect(requests.at(-1)).toMatchObject({
      page: 2,
      fxSnapshot: "history-october",
    }),
  );
});
