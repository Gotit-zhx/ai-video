import { fireEvent, waitFor, within } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import type { FinanceApi, FinanceRequestParams } from "../src/shared/contracts";
import {
  createFinanceApiFixture,
  createFinanceHostFixture,
  defaultSession,
  renderFinance,
} from "./fixtures";
import { RecordsPage } from "../src/features/finance/records/RecordsPage";
import { ReportsPage } from "../src/features/finance/reports/ReportsPage";
import {
  ExportDialog,
  ExportJobs,
  FieldSettings,
} from "../src/features/finance/operations";
const catalog = {
  fields: [
    { id: "external_id", label: "编号", type: "text", cny: false },
    { id: "amount", label: "金额", type: "money", cny: false },
    { id: "record_date", label: "日期", type: "date", cny: false },
  ],
  visible: ["external_id", "amount", "record_date"],
  exported: ["external_id", "amount"],
  defaults: { visible: ["external_id"], exported: ["external_id"] },
};
const apiRead =
  (
    read: (path: string, params?: Record<string, unknown>) => unknown,
  ): FinanceApi["get"] =>
  async <T,>(path: string, params?: FinanceRequestParams) =>
    read(path, params) as T;
it("ALL orders renders API facts with exact ID scope without loading overview", async () => {
  const read = vi.fn((path: string) =>
    path.startsWith("/fields")
      ? catalog
      : {
          items: [
            {
              id: "1",
              kind: "ORDER",
              external_id: "001",
              order_id: "001",
              amount: 0,
              record_date: "2026-01-01",
            },
            {
              id: "2",
              kind: "ORDER",
              external_id: "002",
              order_id: "002",
              amount: null,
              record_date: "2026-02-01",
            },
          ],
          total: 107,
          page: 1,
          pageSize: 50,
          fieldSettings: catalog,
        },
  );
  const v = renderFinance(<RecordsPage view="ORDER" />, {
    api: createFinanceApiFixture({ get: apiRead(read) }),
  });
  expect(await v.findByText("0.00")).toBeInTheDocument();
  expect(v.getByText("2026-01-01")).toBeInTheDocument();
  fireEvent.change(v.getByRole("textbox", { name: "精确编号查询" }), {
    target: { value: "001，002" },
  });
  fireEvent.click(v.getByRole("button", { name: /查\s*询/ }));
  await waitFor(() =>
    expect(read).toHaveBeenCalledWith(
      "/records",
      expect.objectContaining({
        numbers: "001,002",
        orderScope: "ALL",
        page: 1,
      }),
    ),
  );
  expect(read.mock.calls.some(([path]) => path === "/overview")).toBe(false);
});
it("overview uses backend all-scope profit and preserves partial advertising", async () => {
  const overview = {
    filters: { displayCurrency: "CNY" },
    totals: [
      { kind: "ORDER", amount: 1000, records: 50 },
      { kind: "FEE", amount: 0 },
    ],
    profit: {
      settledGrossAmount: 800,
      operatingCost: null,
      knownOperatingCost: 50,
      amount: null,
    },
    advertising: {
      total: { amount: null, knownAmount: 20, complete: false, records: 2 },
    },
    ranking: [],
    shops: [],
    groups: [],
    trend: [],
    fees: [],
    statuses: [],
  };
  const v = renderFinance(<ReportsPage view="overview" />, {
    api: createFinanceApiFixture({
      get: apiRead((path) => (path === "/overview" ? overview : {})),
    }),
  });
  expect(await v.findByText("CNY 800.00")).toBeInTheDocument();
  expect(v.getByText("CNY 20.00")).toBeInTheDocument();
  expect(v.getAllByText("已知部分").length).toBeGreaterThan(0);
});
it("advertising uses primary spend and retains it when account discovery fails", async () => {
  const v = renderFinance(<ReportsPage view="ads" />, {
    api: createFinanceApiFixture({
      get: apiRead((path) => {
        if (path === "/ads") throw new Error("账户不可读");
        return path === "/advertising/spend"
          ? {
              totals: [
                {
                  currency: "CNY",
                  amount: 123,
                  cash_amount: null,
                  known_cash_amount: 0,
                },
              ],
              items: [],
              groups: [],
              shops: [],
              total: 0,
            }
          : { totals: [], advertising: {} };
      }),
    }),
  });
  expect(await v.findByText("CNY 123.00")).toBeInTheDocument();
  expect(await v.findByText("账户不可读")).toBeInTheDocument();
  expect(
    v.getByText("投放报表与已入账广告范围不同，不可直接相加。"),
  ).toBeInTheDocument();
});
it("viewer saves independent display and export selections", async () => {
  const send = vi.fn<FinanceApi["send"]>(async <T,>() => "saved" as T);
  const v = renderFinance(
    <FieldSettings view="FEE" open onClose={() => {}} />,
    {
      host: createFinanceHostFixture({ ...defaultSession, canManage: false })
        .host,
      api: createFinanceApiFixture({
        get: apiRead(() => catalog),
        send: send as FinanceApi["send"],
      }),
    },
  );
  const overlay = within(v.surface.overlayContainer);
  await waitFor(() =>
    expect(overlay.getByRole("button", { name: "保存字段" })).toBeEnabled(),
  );
  fireEvent.click(overlay.getByRole("button", { name: "保存字段" }));
  await waitFor(() =>
    expect(send).toHaveBeenCalledWith(
      "/fields/FEE",
      "PUT",
      {
        visible: ["external_id", "amount", "record_date"],
        exported: ["external_id", "amount"],
      },
      undefined,
    ),
  );
});
it("export freezes scope at open and passes a cancellation signal and timeout", async () => {
  const download = vi.fn<FinanceApi["download"]>(async () => {});
  const v = renderFinance(
    <ExportDialog
      open
      selection={{ start: "2026-01-01", fxSnapshot: "old" }}
      onClose={() => {}}
    />,
    { api: createFinanceApiFixture({ download }) },
  );
  v.rerender(
    <ExportDialog
      open
      selection={{ start: "2026-02-01", fxSnapshot: "new" }}
      onClose={() => {}}
    />,
  );
  fireEvent.click(
    within(v.surface.overlayContainer).getByRole("button", {
      name: "开始导出",
    }),
  );
  await waitFor(() =>
    expect(download).toHaveBeenCalledWith(
      "/export",
      expect.objectContaining({
        start: "2026-01-01",
        fxSnapshot: "old",
        format: "xlsx",
      }),
      undefined,
      expect.objectContaining({
        timeoutMs: 180000,
        signal: expect.any(AbortSignal),
      }),
    ),
  );
});
it("uncertain job creation never replays automatically; explicit recovery reuses body and key", async () => {
  const send = vi
    .fn<FinanceApi["send"]>()
    .mockRejectedValueOnce(new TypeError("network"))
    .mockResolvedValue({ id: "j", status: "READY", filename: "fees.xlsx" });
  const v = renderFinance(
    <ExportJobs
      kind="fee"
      open
      selection={{ start: "2026-01-01" }}
      onClose={() => {}}
    />,
    {
      api: createFinanceApiFixture({
        get: apiRead(() => []),
        send: send as FinanceApi["send"],
      }),
    },
  );
  const overlay = within(v.surface.overlayContainer);
  fireEvent.click(overlay.getByRole("button", { name: "创建导出任务" }));
  expect(
    await overlay.findByRole("button", { name: "确认使用同一请求重试" }),
  ).toBeInTheDocument();
  expect(send).toHaveBeenCalledTimes(1);
  fireEvent.click(
    overlay.getByRole("button", { name: "确认使用同一请求重试" }),
  );
  await overlay.findByText("fees.xlsx");
  expect(send.mock.calls[1]?.slice(0, 4)).toEqual(
    send.mock.calls[0]?.slice(0, 4),
  );
});
it("advertising displays backend performance ratios without recomputing partial inputs", async () => {
  const v = renderFinance(<ReportsPage view="ads" />, {
    api: createFinanceApiFixture({
      get: apiRead((path) =>
        path === "/advertising/spend"
          ? { totals: [], items: [], total: 0 }
          : path === "/overview"
            ? {
                advertising: {
                  performance: {
                    clickThroughRate: 2.5,
                    costPerClick: 0,
                    costPerThousand: null,
                    costPerConversion: 12,
                    conversionRate: 0.25,
                  },
                },
              }
            : [],
      ),
    }),
  });
  await v.findByRole("tab", { name: "已入账广告" });
  fireEvent.click(v.getByRole("tab", { name: "已入账广告" }));
  expect(await v.findByText("2.5")).toBeInTheDocument();
  expect(v.getByText("12.00")).toBeInTheDocument();
  expect(v.getByText("0.25")).toBeInTheDocument();
});
it("settlement reads and exports share overview FX even when records omit top-level FX", async () => {
  const read = vi.fn((path: string) =>
    path === "/overview"
      ? {
          filters: { currency: "", displayCurrency: "CNY" },
          fx: { id: "settlement-fx" },
          fees: [],
        }
      : path.startsWith("/fields")
        ? {
            fields: [
              { id: "external_id", label: "编号" },
              {
                id: "cny_amount",
                label: "人民币金额",
                type: "money",
                cny: true,
              },
            ],
            visible: ["external_id", "cny_amount"],
          }
        : {
            items: [
              {
                id: "r",
                external_id: "S-1",
                cny_amount: 72,
                fx: { id: "settlement-fx" },
              },
            ],
            total: 1,
          },
  );
  const v = renderFinance(<RecordsPage view="SETTLEMENT" />, {
    api: createFinanceApiFixture({ get: apiRead(read) }),
  });
  expect(await v.findByText("72.00")).toBeInTheDocument();
  expect(read).toHaveBeenCalledWith(
    "/records",
    expect.objectContaining({ fxSnapshot: "settlement-fx" }),
  );
});
it("live screen uses today CNY POST with cancellation and displays complete backend AOV", async () => {
  const send = vi.fn(async () => ({
    filters: { displayCurrency: "CNY" },
    totals: [{ kind: "ORDER", amount: 120, known_amounts: 3, records: 4 }],
    sync: [],
    hourly: [],
    recent: [],
    ranking: [],
    statuses: [],
  }));
  const v = renderFinance(<ReportsPage view="live" />, {
    filters: {
      start: "2026-01-01",
      end: "2026-01-31",
      displayCurrency: "USD",
      currency: "USD",
    },
    api: createFinanceApiFixture({ send: send as FinanceApi["send"] }),
  });
  expect(await v.findByText("CNY 40.00")).toBeInTheDocument();
  const call = send.mock.calls[0] as unknown as [
    string,
    string,
    unknown,
    { signal: AbortSignal },
  ];
  const params = new URLSearchParams(call[0].split("?")[1]);
  expect(params.get("start")).toBe(params.get("end"));
  expect(params.get("start")).not.toBe("2026-01-01");
  expect(params.get("displayCurrency")).toBe("CNY");
  expect(params.get("currency")).toBe("");
  expect(call[1]).toBe("POST");
  expect(call[3].signal).toBeInstanceOf(AbortSignal);
});
it("booked advertising card drills into available ledger evidence rather than unrelated spend", async () => {
  const host = createFinanceHostFixture();
  const v = renderFinance(<ReportsPage view="overview" />, {
    host: host.host,
    api: createFinanceApiFixture({
      get: apiRead((path) =>
        path === "/overview"
          ? {
              profit: {},
              totals: [],
              advertising: {
                total: { amount: 7, records: 1, hasRecords: true },
                independent: { records: 0, hasRecords: false },
                settlement: { records: 1, hasRecords: true },
                gmv: { records: 0, hasRecords: false },
              },
            }
          : {},
      ),
    }),
  });
  const card = await v.findByRole("button", { name: /广告费用（已入账）/ });
  fireEvent.click(card);
  expect(host.navigate).toHaveBeenCalledWith("ads?source=ledger");
});
it("real allocation form submits original component values with organization and fingerprint", async () => {
  const send = vi.fn(async () => ({ saved: true }));
  const source = {
    source_key: "source-a",
    platform: "SHOPEE",
    fingerprint: "version-a",
    currency: "USD",
    amount: 10,
    cash_amount: 8,
    credit_amount: 2,
    allocations: [
      { shop_id: "s", amount: "10", cash_amount: "8", credit_amount: "2" },
    ],
  };
  const get = apiRead((path) =>
    path === "/advertising/spend"
      ? {
          totals: [],
          items: [{ source_key: "source-a", amount: 10, currency: "USD" }],
          total: 1,
        }
      : path === "/advertising/spend/source-a"
        ? source
        : path === "/shops"
          ? { items: [{ id: "s", platform: "SHOPEE", name: "Store" }] }
          : path === "/ads"
            ? []
            : { advertising: {} },
  );
  const v = renderFinance(<ReportsPage view="ads" />, {
    api: createFinanceApiFixture({ get, send: send as FinanceApi["send"] }),
  });
  fireEvent.click(await v.findByRole("button", { name: "查看 / 核实分配" }));
  const overlay = within(v.surface.overlayContainer);
  await waitFor(() =>
    expect(overlay.getByRole("button", { name: "保存核实金额" })).toBeEnabled(),
  );
  fireEvent.click(overlay.getByRole("button", { name: "保存核实金额" }));
  await waitFor(() =>
    expect(send).toHaveBeenCalledWith(
      "/advertising/spend/source-a/allocations",
      "PUT",
      {
        organizationId: "org-1",
        fingerprint: "version-a",
        allocations: [
          { shop_id: "s", amount: "10", cash_amount: "8", credit_amount: "2" },
        ],
      },
      undefined,
    ),
  );
});
