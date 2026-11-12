import { fireEvent, waitFor, within } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { OverviewOperations } from "../src/features/finance/reports/OverviewOperations";
import { ExportDialog } from "../src/features/finance/operations";
import {
  createFinanceApiFixture,
  createFinanceHostFixture,
  defaultSession,
  renderFinance,
} from "./fixtures";
import type { FinanceApi, FinanceRequestParams } from "../src/shared/contracts";
const fields = {
  fields: [
    { id: "external_id", label: "编号", type: "text", cny: false },
    { id: "amount", label: "金额", type: "money", cny: false },
  ],
  visible: ["external_id", "amount"],
  exported: ["external_id", "amount"],
  defaults: { visible: ["external_id"], exported: ["external_id"] },
};
const read = ((path: string) =>
  Promise.resolve(
    path === "/shops" ? { items: [], groups: [] } : fields,
  )) as FinanceApi["get"];

it("overview offers ALL personal fields and all-finance export to viewers without importing", async () => {
  const get = vi.fn(read);
  const host = createFinanceHostFixture({
    ...defaultSession,
    canManage: false,
  }).host;
  const view = renderFinance(
    <OverviewOperations
      data={{ filters: { currency: "PHP" }, fx: { id: "frozen-fx" } }}
    />,
    { host, api: createFinanceApiFixture({ get: get as FinanceApi["get"] }) },
  );
  expect(view.queryByRole("button", { name: "导入账单" })).toBeNull();
  expect(view.getByRole("button", { name: "导出财务报表" })).toBeEnabled();
  fireEvent.click(view.getByRole("button", { name: "字段设置" }));
  await waitFor(() =>
    expect(get.mock.calls.some(([path]) => path === "/fields/ALL")).toBe(true),
  );
});

it("overview exports full selected scope with the report FX receipt and SETTLED date basis", async () => {
  const download = vi.fn<FinanceApi["download"]>(async () => {});
  const view = renderFinance(
    <OverviewOperations
      data={{ filters: { currency: "PHP" }, fx: { id: "frozen-fx" } }}
    />,
    {
      api: createFinanceApiFixture({ get: read, download }),
      filters: { start: "2026-10-01", end: "2026-10-31", shopId: "shop-a" },
    },
  );
  fireEvent.click(view.getByRole("button", { name: "导出财务报表" }));
  const overlays = within(view.surface.overlayContainer);
  fireEvent.click(await overlays.findByRole("button", { name: "开始导出" }));
  await waitFor(() =>
    expect(download).toHaveBeenCalledWith(
      "/export",
      expect.objectContaining({
        view: "ALL",
        orderScope: "SETTLED",
        currency: "PHP",
        fxSnapshot: "frozen-fx",
        shopId: "shop-a",
        start: "2026-10-01",
        end: "2026-10-31",
      }),
      undefined,
      expect.anything(),
    ),
  );
});

it("generic export can choose financial refund detail without changing its frozen query", async () => {
  const download = vi.fn<FinanceApi["download"]>(async () => {});
  const props = {
    open: true,
    onClose: vi.fn(),
    selection: {
      view: "ALL",
      shopId: "shop-a",
      start: "2026-10-01",
      end: "2026-10-31",
      fxSnapshot: "old-snapshot",
    } as FinanceRequestParams,
    viewOptions: [
      { value: "ALL", label: "全部财务明细" },
      { value: "REFUND", label: "平台退款明细" },
    ],
  };
  const view = renderFinance(<ExportDialog {...props} />, {
    api: createFinanceApiFixture({ get: read, download }),
  });
  const overlays = within(view.surface.overlayContainer);
  fireEvent.mouseDown(overlays.getByRole("combobox", { name: "导出类型" }));
  fireEvent.click(
    await overlays.findByText("平台退款明细", {
      selector: ".ant-select-item-option-content",
    }),
  );
  view.rerender(
    <ExportDialog
      {...props}
      selection={{ view: "ALL", shopId: "changed", start: "2026-11-01" }}
    />,
  );
  fireEvent.click(overlays.getByRole("button", { name: "开始导出" }));
  await waitFor(() =>
    expect(download).toHaveBeenCalledWith(
      "/export",
      expect.objectContaining({
        view: "REFUND",
        shopId: "shop-a",
        start: "2026-10-01",
        fxSnapshot: "old-snapshot",
      }),
      undefined,
      expect.anything(),
    ),
  );
});
