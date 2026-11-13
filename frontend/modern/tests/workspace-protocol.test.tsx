import { act, fireEvent, waitFor, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { FinanceApi, FinanceRequestParams } from "../src/shared/contracts";
import { FinanceApiError } from "../src/shared/api";
import { RecordsPage } from "../src/features/finance/records/RecordsPage";
import { ReportsPage } from "../src/features/finance/reports/ReportsPage";
import { CostsPage } from "../src/features/finance/costs/CostsPage";
import {
  createFinanceApiFixture,
  createFinanceHostFixture,
  defaultSession,
  renderFinance,
} from "./fixtures";
afterEach(() => vi.useRealTimers());
const fields = {
  fields: [
    { id: "external_id", label: "编号" },
    { id: "cny_amount", label: "人民币金额", type: "money", cny: true },
  ],
  visible: ["external_id", "cny_amount"],
};
const selection = {
  start: "2026-10-01",
  end: "2026-10-31",
  shopId: "",
  groupId: "",
  displayCurrency: "CNY",
  fxSnapshot: "historical-fx",
};
const envelope = {
  filters: { ...selection, currency: "PHP" },
  shops: [],
  groups: [],
  dateBasis: { version: "calendar" },
  dateWindows: [],
  fx: { id: "historical-fx", status: "COMPLETE" },
  readModel: { status: "PENDING", revision: "", coverage: "已入库" },
  updatedAt: "2026-11-13",
  records: {
    items: [
      {
        id: "r1",
        external_id: "authoritative-row",
        order_id: "authoritative-row",
        cny_amount: 72,
      },
    ],
    total: 107,
    page: 1,
    pageSize: 50,
    fieldSettings: fields,
    filters: { currency: "BAD" },
    fx: { id: "wrong-nested-fx" },
  },
};
function apiFor(
  read: (path: string, params?: FinanceRequestParams) => unknown,
) {
  const get = vi.fn(
    async <T,>(path: string, params?: FinanceRequestParams) =>
      read(path, params) as T,
  );
  return { api: { ...createFinanceApiFixture(), get } as FinanceApi, get };
}
it.each([
  ["SETTLEMENT", "settlements", "SETTLEMENT"],
  ["FEE", "fees", "FEE"],
  ["ORDER", "orders", "ORDER"],
  ["RECONCILIATION", "reconciliation", "BILL"],
])(
  "queries %s directly and freezes outer FX metadata for export",
  async (view, workspaceView, kind) => {
    const { api, get } = apiFor((path) =>
      path === "/workspace/query"
        ? envelope
        : path.startsWith("/fields")
          ? fields
          : {},
    );
    const download = vi.fn(async () => {});
    api.download = download;
    const v = renderFinance(<RecordsPage view={view!} orderScope="SETTLED" />, {
      api,
      filters: selection,
    });
    expect(await v.findByText("authoritative-row")).toBeInTheDocument();
    expect(v.getByText("72.00")).toBeInTheDocument();
    expect(get).toHaveBeenCalledWith(
      "/workspace/query",
      expect.objectContaining({
        ...selection,
        view: workspaceView,
        kind,
        page: 1,
      }),
      expect.any(AbortSignal),
    );
    expect(
      get.mock.calls.filter(([p]) =>
        ["/overview", "/records", "/reconciliation"].includes(p),
      ),
    ).toHaveLength(0);
    fireEvent.click(v.getByRole("button", { name: /^导\s*出$/ }));
    fireEvent.click(
      within(v.surface.overlayContainer).getByRole("button", {
        name: "开始导出",
      }),
    );
    await waitFor(() =>
      expect(download).toHaveBeenCalledWith(
        "/export",
        expect.objectContaining({
          fxSnapshot: "historical-fx",
          currency: "PHP",
        }),
        undefined,
        expect.anything(),
      ),
    );
  },
);
it("main advertising gets spend, account and shop facts from one envelope", async () => {
  const { records: _, ...outer } = envelope;
  const { api, get } = apiFor((path) =>
    path === "/workspace/query"
      ? {
          ...outer,
          adSpend: {
            totals: [{ currency: "PHP", amount: 123 }],
            items: [],
            total: 107,
            page: 1,
            pageSize: 50,
          },
          adAccounts: [],
        }
      : {},
  );
  const v = renderFinance(<ReportsPage view="ads" />, {
    api,
    filters: selection,
  });
  expect(await v.findByText("PHP 123.00")).toBeInTheDocument();
  expect(get).toHaveBeenCalledWith(
    "/workspace/query",
    expect.objectContaining({ view: "ads", kind: "AD", spendPage: 1 }),
    expect.any(AbortSignal),
  );
  expect(
    get.mock.calls.filter(([p]) =>
      ["/overview", "/ads", "/advertising/spend", "/shops"].includes(p),
    ),
  ).toHaveLength(0);
});
it("advertising fee records use ads-support rather than all fees", async () => {
  const { api, get } = apiFor((path) =>
    path === "/workspace/query"
      ? envelope
      : path.startsWith("/fields")
        ? fields
        : {},
  );
  const v = renderFinance(<RecordsPage view="FEE" advertisingOnly />, {
    api,
    filters: selection,
  });
  await v.findByText("authoritative-row");
  expect(get).toHaveBeenCalledWith(
    "/workspace/query",
    expect.objectContaining({ view: "ads-support", kind: "FEE" }),
    expect.any(AbortSignal),
  );
});
it("thin cost context keeps all-company labels scope and does not fetch catalogue or write", async () => {
  const { api, get } = apiFor((path) =>
    path === "/workspace/costs/context"
      ? {
          live: true,
          catalogLoaded: false,
          settingsLoaded: false,
          shopId: "suggested-shop",
          groupId: "suggested-group",
          groupName: "Hint",
        }
      : { items: [], rows: [], files: [], total: 0 },
  );
  const send = vi.fn();
  api.send = send;
  const v = renderFinance(<CostsPage />, { api, filters: selection });
  await waitFor(() =>
    expect(get).toHaveBeenCalledWith(
      "/workspace/costs/context",
      expect.objectContaining({ shopId: "", groupId: "" }),
      expect.any(AbortSignal),
    ),
  );
  expect(get.mock.calls.some(([p]) => p === "/workspace/costs")).toBe(false);
  expect(
    get.mock.calls.find(([p]) => p === "/workspace/label-costs")?.[1],
  ).toEqual(expect.objectContaining({ shopId: "", groupId: "" }));
  expect(send).not.toHaveBeenCalled();
  expect(
    v.queryByRole("tab", { name: /导入与操作核对/ }),
  ).not.toBeInTheDocument();
});
it("only exact endpoint 404 chooses cached legacy flow, preserving historical FX", async () => {
  const { api, get } = apiFor((path) => {
    if (path === "/workspace/query") throw new FinanceApiError("absent", 404);
    return path === "/overview"
      ? { fx: { id: "historical-fx" }, filters: { currency: "PHP" } }
      : path === "/records"
        ? { ...envelope.records, fx: undefined, filters: undefined }
        : path.startsWith("/fields")
          ? fields
          : {};
  });
  const v = renderFinance(<RecordsPage view="SETTLEMENT" />, {
    api,
    filters: selection,
  });
  await v.findByText("authoritative-row");
  fireEvent.change(v.getByRole("textbox", { name: "精确编号查询" }), {
    target: { value: "typed-id" },
  });
  fireEvent.click(v.getByRole("button", { name: /查\s*询/ }));
  await waitFor(() =>
    expect(get).toHaveBeenCalledWith(
      "/records",
      expect.objectContaining({
        numbers: "typed-id",
        fxSnapshot: "historical-fx",
      }),
      expect.any(AbortSignal),
    ),
  );
  expect(get.mock.calls.filter(([p]) => p === "/workspace/query")).toHaveLength(
    1,
  );
});
it.each([401, 403, 500])(
  "does not mask query HTTP %i with old reads",
  async (status) => {
    const { api, get } = apiFor((path) => {
      if (path === "/workspace/query")
        throw new FinanceApiError("failure-" + status, status);
      return path.startsWith("/fields") ? fields : envelope.records;
    });
    const v = renderFinance(<RecordsPage view="ORDER" />, {
      api,
      filters: selection,
    });
    await v.findByText("failure-" + status);
    expect(get.mock.calls.some(([p]) => p === "/records")).toBe(false);
  },
);

it("operational heartbeat updates progress without repeating money reads or destroying typed inputs and defers in dialogs", async () => {
  const { api, get } = apiFor((path) =>
    path === "/workspace/query"
      ? envelope
      : path === "/workspace/status"
        ? {
            status: "READY",
            revision: "changed",
            operations: {
              settlementCalculation: {
                status: "RUNNING",
                processed: 7,
                total: 20,
              },
              sync: [],
              costState: { rebuilding: true, hasProductJobs: false },
            },
          }
        : path.startsWith("/fields")
          ? fields
          : {},
  );
  const v = renderFinance(<RecordsPage view="SETTLEMENT" />, {
    api,
    filters: selection,
  });
  await v.findByText("authoritative-row");
  await v.findByText("结算金额计算中");
  fireEvent.change(v.getByRole("textbox", { name: "精确编号查询" }), {
    target: { value: "UNSUBMITTED" },
  });
  const moneyReads = () =>
    get.mock.calls.filter(([p]) => p === "/workspace/query").length;
  const statusReads = () =>
    get.mock.calls.filter(([p]) => p === "/workspace/status").length;
  const initialMoney = moneyReads(),
    initialStatus = statusReads();
  vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
  // Re-enter to establish the visible heartbeat interval under the test clock.
  v.setActive(false);
  v.setActive(true);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(15001);
  });
  await waitFor(() => expect(statusReads()).toBeGreaterThan(initialStatus));
  expect(moneyReads()).toBe(initialMoney);
  expect(v.getByRole("textbox", { name: "精确编号查询" })).toHaveValue(
    "UNSUBMITTED",
  );
  fireEvent.click(v.getByRole("button", { name: "字段设置" }));
  await within(v.surface.overlayContainer).findByRole("dialog");
  const dialogCount = statusReads();
  await act(async () => {
    await vi.advanceTimersByTimeAsync(30001);
  });
  expect(statusReads()).toBe(dialogCount);
  v.setActive(false);
  await act(async () => {
    await vi.advanceTimersByTimeAsync(30001);
  });
  expect(statusReads()).toBe(dialogCount);
  vi.useRealTimers();
});

it("a late FX receipt pins reads without remounting typed search", async () => {
  let resolve!: (value: unknown) => void;
  let first = true;
  const { api } = apiFor((path) => {
    if (path === "/workspace/query") {
      if (first) {
        first = false;
        return new Promise((r) => {
          resolve = r;
        });
      }
      return envelope;
    }
    if (path === "/workspace/status")
      return { operations: {}, status: "READY" };
    return fields;
  });
  const v = renderFinance(<RecordsPage view="SETTLEMENT" />, {
    api,
    filters: { ...selection, fxSnapshot: "" },
  });
  fireEvent.change(v.getByRole("textbox", { name: "精确编号查询" }), {
    target: { value: "KEEP-DRAFT" },
  });
  await act(async () => resolve(envelope));
  await v.findByText("authoritative-row");
  expect(v.getByRole("textbox", { name: "精确编号查询" })).toHaveValue(
    "KEEP-DRAFT",
  );
});
it("explicitly incomplete full cost DTO never enables settings or auto-match writes", async () => {
  const { api, get } = apiFor((path) =>
    path === "/workspace/costs"
      ? {
          live: true,
          catalogLoaded: true,
          settingsLoaded: false,
          shopId: "s",
          groupId: "g",
          costs: [],
          manualCosts: [],
          products: [],
          settings: {},
          settingsVersion: 0,
          mappings: {},
        }
      : { operations: {}, items: [] },
  );
  const send = vi.fn();
  api.send = send;
  const v = renderFinance(<CostsPage initialTab="settings" />, {
    api,
    filters: selection,
  });
  await waitFor(() =>
    expect(get).toHaveBeenCalledWith(
      "/workspace/costs",
      expect.anything(),
      expect.any(AbortSignal),
    ),
  );
  expect(
    v.queryByRole("button", { name: "保存成本设置" }),
  ).not.toBeInTheDocument();
  expect(send).not.toHaveBeenCalled();
});

it("hidden pages defer operational polling and status failures retain scoped money and drafts", async () => {
  let fail = false;
  const { api, get } = apiFor((path) => {
    if (path === "/workspace/query") return envelope;
    if (path === "/workspace/status") {
      if (fail) throw new FinanceApiError("status-outage", 500);
      return { status: "READY", operations: {} };
    }
    return fields;
  });
  const v = renderFinance(<RecordsPage view="SETTLEMENT" />, {
    api,
    filters: selection,
  });
  await v.findByText("authoritative-row");
  fireEvent.change(v.getByRole("textbox", { name: "精确编号查询" }), {
    target: { value: "UNCHANGED" },
  });
  vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
  v.setActive(false);
  v.setActive(true);
  const initial = get.mock.calls.filter(
    ([p]) => p === "/workspace/status",
  ).length;
  Object.defineProperty(document, "hidden", {
    configurable: true,
    value: true,
  });
  fireEvent(document, new Event("visibilitychange"));
  await act(async () => {
    await vi.advanceTimersByTimeAsync(30001);
  });
  expect(
    get.mock.calls.filter(([p]) => p === "/workspace/status"),
  ).toHaveLength(initial);
  Object.defineProperty(document, "hidden", {
    configurable: true,
    value: false,
  });
  fireEvent(document, new Event("visibilitychange"));
  fail = true;
  await act(async () => {
    await vi.advanceTimersByTimeAsync(15001);
  });
  await v.findByText("status-outage");
  expect(v.getByText("authoritative-row")).toBeVisible();
  expect(v.getByRole("textbox", { name: "精确编号查询" })).toHaveValue(
    "UNCHANGED",
  );
  expect(get.mock.calls.filter(([p]) => p === "/workspace/query")).toHaveLength(
    1,
  );
});

it("completed operational state clears stale overview progress without replacing financial amounts", async () => {
  const { api } = apiFor((path) =>
    path === "/overview"
      ? {
          ...envelope,
          totals: [],
          profit: { settledGrossAmount: 800 },
          productCosts: { processing: true, total: {} },
          settlementCalculation: { status: "RUNNING" },
        }
      : path === "/workspace/status"
        ? {
            status: "READY",
            operations: {
              settlementCalculation: { status: "COMPLETE" },
              costState: { rebuilding: false, hasProductJobs: false },
              sync: [],
            },
          }
        : {},
  );
  const v = renderFinance(<ReportsPage view="overview" />, {
    api,
    filters: selection,
  });
  await v.findByText("CNY 800.00");
  expect(v.queryByText("结算金额计算中")).not.toBeInTheDocument();
  expect(v.queryByText("成本计算中，已核算部分保留")).not.toBeInTheDocument();
});

it("owner change clears optional endpoint capability and never reuses the prior owner data", async () => {
  let legacy = true;
  const host = createFinanceHostFixture();
  const { api, get } = apiFor((path) => {
    if (path === "/workspace/query") {
      if (legacy) throw new FinanceApiError("old endpoint", 404);
      return {
        ...envelope,
        records: {
          ...envelope.records,
          items: [{ id: "new-owner", external_id: "NEW-OWNER-RECORD" }],
        },
      };
    }
    if (path === "/overview")
      return { fx: envelope.fx, filters: envelope.filters };
    if (path === "/records") return envelope.records;
    if (path === "/workspace/status")
      return { operations: {}, status: "READY" };
    return fields;
  });
  const v = renderFinance(<RecordsPage view="SETTLEMENT" />, {
    api,
    host: host.host,
    filters: selection,
  });
  await v.findByText("authoritative-row");
  const oldReads = get.mock.calls.filter(([p]) => p === "/records").length;
  legacy = false;
  act(() => host.update({ ...defaultSession, userId: "another-user" }));
  await v.findByText("NEW-OWNER-RECORD");
  expect(v.queryByText("authoritative-row")).not.toBeInTheDocument();
  expect(
    get.mock.calls.filter(([p]) => p === "/workspace/query").length,
  ).toBeGreaterThan(1);
  expect(get.mock.calls.filter(([p]) => p === "/records")).toHaveLength(
    oldReads,
  );
});

it("withdrawn review deep links return to lightweight labels on the current server", async () => {
  const { api, get } = apiFor((path) =>
    path === "/workspace/costs/context"
      ? {
          live: true,
          catalogLoaded: false,
          settingsLoaded: false,
          shopId: "s",
          groupId: "g",
          groupName: "group",
        }
      : { operations: {}, items: [], rows: [], files: [], total: 0 },
  );
  const v = renderFinance(<CostsPage initialTab="input-review" />, {
    api,
    filters: selection,
  });
  await waitFor(() =>
    expect(v.getByRole("tab", { name: "月度贴单账单" })).toHaveAttribute(
      "aria-selected",
      "true",
    ),
  );
  expect(
    get.mock.calls.some(
      ([path]) =>
        path === "/workspace/costs" || path.includes("/input-changes"),
    ),
  ).toBe(false);
});
