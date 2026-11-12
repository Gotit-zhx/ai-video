import { useState } from "react";
import { fireEvent, waitFor, within } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { CostsPage } from "../src/features/finance/costs/CostsPage";
import { QianyiPage } from "../src/features/finance/qianyi/QianyiPage";
import {
  createFinanceApiFixture,
  createFinanceHostFixture,
  defaultSession,
  renderFinance,
} from "./fixtures";
import type { FinanceApi, Row } from "../src/shared/contracts";
const filters = {
  shopId: "s1",
  groupId: "g1",
  start: "2026-10-01",
  end: "2026-10-31",
};
const base: Row = {
  shopId: "s1",
  groupId: "g1",
  costs: [{ sku: "SKU", warehouse: "菲律宾", prices: { "2026-10": "1.01" } }],
  manualCosts: [],
  imports: [],
  products: [
    { product_id: "p1", sku_id: "v1", seller_sku: "SKU", name: "product" },
  ],
  mappings: {},
  settings: {
    warehouse: "菲律宾",
    rules: { currency: "CNY", unit: "包", basis: "order", missing: "pending" },
  },
  settingsVersion: 2,
};
const review: Row = {
  id: "c1",
  status: "READY",
  version: 5,
  canConfirm: true,
  metadata: { reason: "confirmed correction", evidenceRef: "e1" },
  impact: {},
  validation: { issues: [] },
};
const overlay = (r: ReturnType<typeof renderFinance>) =>
  within(r.surface.overlayContainer);
function apiFor(data: Row = base, overrides: Partial<FinanceApi> = {}) {
  return createFinanceApiFixture({
    get: vi.fn(async (path: string) =>
      path === "/workspace/costs"
        ? data
        : path === "/workspace/costs/input-changes"
          ? { items: [review], total: 1 }
          : path === "/workspace/costs/input-changes/c1"
            ? review
            : { items: [], rows: [], total: 0 },
    ) as never,
    send: vi.fn(async () => ({ status: "APPLIED" })) as never,
    ...overrides,
  });
}
async function choose(
  r: ReturnType<typeof renderFinance>,
  label: string,
  title: string,
) {
  fireEvent.mouseDown(await r.findByRole("combobox", { name: label }));
  fireEvent.click(await overlay(r).findByTitle(title));
}
const viewer = createFinanceHostFixture({
  ...defaultSession,
  canManage: false,
}).host;
describe("Task4 review regressions", () => {
  it("deleted manual mapping remains pending even with a same-named Excel price", async () => {
    const data = {
      ...base,
      mappings: {
        "s1:p1:v1:菲律宾": {
          code: "SKU",
          warehouse: "菲律宾",
          factor: 3,
          manualCostId: "deleted",
          version: 7,
        },
      },
    };
    const r = renderFinance(<CostsPage initialTab="mapping" />, {
      api: apiFor(data),
      host: viewer,
      filters,
    });
    expect(await r.findByText(/原关联的手动成本已移除/)).toBeVisible();
    await choose(r, "关联状态", "待关联");
    expect(r.getByText("product")).toBeVisible();
    await choose(r, "关联状态", "已有关系");
    expect(r.queryByText("product")).toBeNull();
  });
  it("removed manually selected Excel mapping stays pending instead of borrowing another warehouse", async () => {
    const data = {
      ...base,
      costs: [{ sku: "SKU", warehouse: "泰国", prices: { "2026-10": 3 } }],
      mappings: {
        "s1:p1:v1:菲律宾": {
          code: "SKU",
          warehouse: "菲律宾",
          factor: 2,
          version: 7,
        },
      },
    };
    const r = renderFinance(<CostsPage initialTab="mapping" />, {
      api: apiFor(data),
      host: viewer,
      filters,
    });
    expect(await r.findByText(/原关联的表格成本已移除/)).toBeVisible();
    await choose(r, "关联状态", "待关联");
    expect(r.getByText("product")).toBeVisible();
  });
  it("recomputes an automatic mapping against the current catalog and labels it as a local candidate", async () => {
    const data = {
      ...base,
      products: [
        {
          product_id: "p1",
          sku_id: "v1",
          seller_sku: "BASE*2",
          name: "bundle",
        },
      ],
      costs: [{ sku: "BASE*2", warehouse: "菲律宾", prices: { "2026-10": 3 } }],
      mappings: {
        "s1:p1:v1:菲律宾": {
          code: "BASE",
          warehouse: "菲律宾",
          factor: 2,
          method: "BUNDLE_CODE",
          version: 7,
        },
      },
    };
    const r = renderFinance(<CostsPage initialTab="mapping" />, {
      api: apiFor(data),
      filters,
    });
    expect(await r.findByText("本地候选 · 商品 SKU")).toBeVisible();
    const row = within(r.getByText("bundle").closest("tr")!);
    expect(row.getAllByText("BASE*2")).toHaveLength(2);
    expect(row.getByText("1")).toBeVisible();
    fireEvent.click(row.getByRole("button", { name: "核对成本关联" }));
    expect(overlay(r).getByLabelText("换算数量")).toHaveValue("1");
  });
  it("a previously saved automatic mapping becomes pending when current normalized codes are ambiguous", async () => {
    const data = {
      ...base,
      products: [
        {
          product_id: "p1",
          sku_id: "v1",
          seller_sku: " (A) ",
          name: "ambiguous",
        },
      ],
      costs: [
        { sku: "A", warehouse: "菲律宾" },
        { sku: "（A）", warehouse: "菲律宾" },
      ],
      mappings: {
        "s1:p1:v1:菲律宾": {
          code: "A",
          warehouse: "菲律宾",
          factor: 1,
          method: "NORMALIZED_CODE",
          version: 7,
        },
      },
    };
    const r = renderFinance(<CostsPage initialTab="mapping" />, {
      api: apiFor(data),
      host: viewer,
      filters,
    });
    await choose(r, "关联状态", "待关联");
    expect(await r.findByText("ambiguous")).toBeVisible();
    expect(r.getByText(/规范化编码对应多个型号/)).toBeVisible();
  });
  it("confirm then open catalog/settings reads applied prices and new settings version", async () => {
    let applied = false;
    const fresh = {
      ...base,
      inputSafety: { enabled: true, pendingCount: 0 },
      costs: [
        { sku: "SKU", warehouse: "菲律宾", prices: { "2026-10": "9.000001" } },
      ],
      settingsVersion: 9,
    };
    const api = apiFor(
      { ...base, inputSafety: { enabled: true, pendingCount: 37 } },
      {
        get: vi.fn(async (path: string) =>
          path === "/workspace/costs"
            ? applied
              ? fresh
              : { ...base, inputSafety: { enabled: true, pendingCount: 37 } }
            : path === "/workspace/costs/input-changes"
              ? { items: [review], total: 1 }
              : path === "/workspace/costs/input-changes/c1"
                ? review
                : { items: [] },
        ) as never,
        send: vi.fn(async () => {
          applied = true;
          return { status: "APPLIED" };
        }) as never,
      },
    );
    const r = renderFinance(<CostsPage initialTab="input-review" />, {
      api,
      filters,
    });
    fireEvent.click(await r.findByRole("button", { name: "查看核对单" }));
    const d = overlay(r);
    fireEvent.click(await d.findByLabelText("我已核对实际影响范围"));
    fireEvent.click(d.getByRole("button", { name: "确认应用" }));
    await waitFor(() => expect(api.send).toHaveBeenCalled());
    fireEvent.click(r.getByRole("tab", { name: "商品成本表" }));
    expect(await r.findByText("9.000001")).toBeVisible();
    fireEvent.click(r.getByRole("tab", { name: "成本表设置" }));
    await r.findByRole("button", { name: "保存成本设置" });
    fireEvent.change(r.getByLabelText("操作原因"), {
      target: { value: "settings" },
    });
    fireEvent.change(r.getByLabelText("依据编号或说明"), {
      target: { value: "settings receipt" },
    });
    fireEvent.click(r.getByRole("button", { name: "保存成本设置" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/costs/s1/settings",
        "PUT",
        expect.objectContaining({ version: 9 }),
        undefined,
      ),
    );
  });
  it.each([
    [37, "37"],
    [0, "0"],
    [undefined, "—"],
  ])("review entry preserves server pendingCount %s", async (count, value) => {
    const api = apiFor({
      ...base,
      inputSafety: {
        enabled: true,
        ...(count === undefined ? {} : { pendingCount: count }),
      },
    });
    const r = renderFinance(<CostsPage />, { api, filters });
    expect(
      await r.findByRole("tab", { name: `导入与操作核对 · ${value}` }),
    ).toBeVisible();
  });
  it("explicit empty route dates survive the shell date defaults", async () => {
    const api = apiFor();
    renderFinance(
      <CostsPage route="costs?tab=orders&start=&end=&numbers=ANY" />,
      { api, filters },
    );
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith(
        "/workspace/costs/orders",
        expect.objectContaining({ start: "", end: "", numbers: "ANY" }),
        expect.any(AbortSignal),
      ),
    );
  });
  it("same-tab route context change resets local selection while ordinary rerenders keep edits", async () => {
    const api = apiFor();
    function Route() {
      const [route, setRoute] = useState(
        "costs?tab=orders&recordId=A&kind=BILL&numbers=OLD&start=2026-01-01&end=2026-01-31",
      );
      const [tick, setTick] = useState(0);
      return (
        <>
          <button
            onClick={() =>
              setRoute(
                "costs?tab=orders&recordId=B&kind=REFUND&numbers=NEW&start=&end=",
              )
            }
          >
            route B
          </button>
          <button onClick={() => setTick(tick + 1)}>rerender</button>
          <CostsPage route={route} />
        </>
      );
    }
    const r = renderFinance(<Route />, { api, filters });
    fireEvent.change(await r.findByLabelText("精确订单编号"), {
      target: { value: "EDIT" },
    });
    fireEvent.click(r.getByRole("button", { name: "查询订单成本" }));
    fireEvent.click(r.getByRole("button", { name: "rerender" }));
    expect(r.getByLabelText("精确订单编号")).toHaveValue("EDIT");
    fireEvent.click(r.getByRole("button", { name: "route B" }));
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith(
        "/workspace/costs/orders",
        expect.objectContaining({
          recordId: "B",
          kind: "REFUND",
          numbers: "NEW",
          start: "",
          end: "",
        }),
        expect.any(AbortSignal),
      ),
    );
    expect(r.getByLabelText("精确订单编号")).toHaveValue("NEW");
    expect(
      vi
        .mocked(api.get)
        .mock.calls.some(
          (c) =>
            c[0] === "/workspace/costs/orders" &&
            c[1]?.recordId === "B" &&
            c[1]?.numbers === "EDIT",
        ),
    ).toBe(false);
  });
  it.each([
    ["售后类型", "退款单", "type", "RETURN"],
    ["退货状态", "WAIT_STORAGE", "returnStatus", "WAIT_STORAGE"],
    ["退回物流状态", "IN_TRANSIT", "receiptStatus", "IN_TRANSIT"],
  ])(
    "incompatible %s switches to ALL and RECEIVED restores the full qualification",
    async (label, title, key, value) => {
      const api = apiFor();
      const r = renderFinance(<QianyiPage />, { api, filters });
      await choose(r, label, title);
      fireEvent.click(r.getByRole("button", { name: "查询售后" }));
      await waitFor(() =>
        expect(api.get).toHaveBeenCalledWith(
          "/qianyi/returns",
          expect.objectContaining({ receiptScope: "ALL", [key]: value }),
          expect.any(AbortSignal),
        ),
      );
      await choose(r, "售后查询范围", "符合收货统计条件");
      fireEvent.click(r.getByRole("button", { name: "查询售后" }));
      // The initial RECEIVED query can still be cached. Export exercises the
      // newly applied selection even when a fresh GET is correctly unnecessary.
      await waitFor(() =>
        expect(r.getByRole("button", { name: "导出千易售后" })).toBeEnabled(),
      );
      fireEvent.click(r.getByRole("button", { name: "导出千易售后" }));
      await waitFor(() =>
        expect(api.download).toHaveBeenCalledWith(
          "/qianyi/returns/export",
          expect.objectContaining({
            receiptScope: "RECEIVED",
            type: "SALES_RETURN",
            returnStatus: "FINISH",
            receiptStatus: "SIGNED",
          }),
          "qianyi-returns.xlsx",
          expect.objectContaining({ signal: expect.any(AbortSignal) }),
        ),
      );
    },
  );
  it("returned SPU_CODE is labeled separately from the recomputed local SPU candidate", async () => {
    const data = {
      ...base,
      products: [
        {
          product_id: "90071992547409931234",
          sku_id: "v1",
          seller_sku: "UNMATCHED",
          name: "spu",
        },
      ],
      costs: [{ sku: "90071992547409931234", warehouse: "菲律宾" }],
      mappings: {
        "s1:90071992547409931234:v1:菲律宾": {
          code: "90071992547409931234",
          warehouse: "菲律宾",
          factor: 1,
          method: "SPU_CODE",
          version: 3,
        },
      },
    };
    const r = renderFinance(<CostsPage initialTab="mapping" />, {
      api: apiFor(data),
      host: viewer,
      filters,
    });
    expect(await r.findByText("平台 SPU 自动匹配（SPU_CODE）")).toBeVisible();
    expect(r.getByText("本地候选 · 平台 SPU")).toBeVisible();
  });
  it("order cost detail displays returned matchedBy SPU, SKU and MANUAL facts", async () => {
    const api = apiFor(base, {
      get: vi.fn(async (path: string) =>
        path === "/workspace/costs"
          ? base
          : path === "/workspace/costs/orders"
            ? { items: [{ id: "o1", order_id: "ORDER" }], total: 1 }
            : path === "/records/o1"
              ? {
                  kind: "ORDER",
                  productCost: {
                    lines: [
                      { name: "spu line", matchedBy: "SPU", costCode: "P1" },
                      { name: "sku line", matchedBy: "SKU", costCode: "S1" },
                      {
                        name: "manual line",
                        matchedBy: "MANUAL",
                        manualCostId: "m1",
                        costCode: "M1",
                      },
                    ],
                  },
                }
              : path === "/fields/ORDER"
                ? { fields: [] }
                : {},
      ) as never,
    });
    const r = renderFinance(<CostsPage initialTab="orders" />, {
      api,
      filters,
    });
    fireEvent.click(await r.findByRole("button", { name: "查看订单成本明细" }));
    const d = overlay(r);
    expect(await d.findByText("SPU")).toBeVisible();
    expect(d.getByText("SKU")).toBeVisible();
    expect(d.getByText("MANUAL")).toBeVisible();
  });
});

it("deleting a valid manual source preserves its now-pending mapping after refresh", async () => {
  let removed = false;
  const workspace = () => ({
    ...base,
    manualCosts: removed
      ? []
      : [
          {
            id: "m1",
            sku: "SKU",
            warehouse: "菲律宾",
            unitPriceCny: 3,
            unit: "包",
            version: 6,
          },
        ],
    mappings: {
      "s1:p1:v1:菲律宾": {
        code: "SKU",
        warehouse: "菲律宾",
        factor: 3,
        manualCostId: "m1",
        version: 4,
      },
    },
  });
  const api = apiFor(workspace(), {
    get: vi.fn(async (path: string) =>
      path === "/workspace/costs" ? workspace() : {},
    ) as never,
    send: vi.fn(async () => {
      removed = true;
      return "已移除";
    }) as never,
  });
  const r = renderFinance(<CostsPage initialTab="mapping" />, { api, filters });
  expect(await r.findByText("已有人工关系，当前来源有效")).toBeVisible();
  fireEvent.click(r.getByRole("tab", { name: "手动管理" }));
  fireEvent.click(await r.findByRole("button", { name: "删除手动成本" }));
  const d = overlay(r);
  fireEvent.click(d.getByLabelText("我已核对删除影响"));
  fireEvent.click(d.getByRole("button", { name: "确认删除" }));
  await waitFor(() =>
    expect(r.queryByRole("button", { name: "删除手动成本" })).toBeNull(),
  );
  fireEvent.click(r.getByRole("tab", { name: "SKU 关联" }));
  expect(await r.findByText(/原关联的手动成本已移除/)).toBeVisible();
  await choose(r, "关联状态", "待关联");
  expect(r.getByText("product")).toBeVisible();
  expect(api.send).toHaveBeenCalledWith(
    "/workspace/costs/groups/g1/manual/m1",
    "DELETE",
    { version: 6 },
    undefined,
  );
});
it("failed applied-workspace refresh never reopens the old settings version and supports read-only retry", async () => {
  let applied = false,
    canRead = false;
  const api = apiFor(base, {
    get: vi.fn(async (path: string) => {
      if (path === "/workspace/costs") {
        if (applied && !canRead) throw Error("fresh workspace unavailable");
        return {
          ...base,
          settingsVersion: applied ? 9 : 2,
          inputSafety: { enabled: true, pendingCount: applied ? 0 : 1 },
        };
      }
      return path.endsWith("/input-changes")
        ? { items: [review], total: 1 }
        : path.endsWith("/c1")
          ? review
          : {};
    }) as never,
    send: vi.fn(async () => {
      applied = true;
      return { status: "APPLIED" };
    }) as never,
  });
  const r = renderFinance(<CostsPage initialTab="input-review" />, {
    api,
    filters,
  });
  fireEvent.click(await r.findByRole("button", { name: "查看核对单" }));
  const d = overlay(r);
  fireEvent.click(await d.findByLabelText("我已核对实际影响范围"));
  fireEvent.click(d.getByRole("button", { name: "确认应用" }));
  await r.findAllByText("fresh workspace unavailable");
  fireEvent.click(r.getByRole("tab", { name: "成本表设置" }));
  expect(r.queryByRole("button", { name: "保存成本设置" })).toBeNull();
  canRead = true;
  fireEvent.click(r.getByRole("button", { name: "重试读取有效成本" }));
  expect(await r.findByRole("button", { name: "保存成本设置" })).toBeEnabled();
  expect(api.send).toHaveBeenCalledTimes(1);
  expect(r.getByRole("tab", { name: "导入与操作核对 · 0" })).toBeVisible();
});

it("rejecting a review refreshes the server pending count without claiming application", async () => {
  let rejected = false;
  const item = { ...review, canConfirm: false, canReject: true };
  const api = apiFor(base, {
    get: vi.fn(async (path: string) =>
      path === "/workspace/costs"
        ? {
            ...base,
            inputSafety: { enabled: true, pendingCount: rejected ? 36 : 37 },
          }
        : path.endsWith("/input-changes")
          ? { items: [item], total: 1 }
          : path.endsWith("/c1")
            ? item
            : {},
    ) as never,
    send: vi.fn(async () => {
      rejected = true;
      return { status: "REJECTED" };
    }) as never,
  });
  const r = renderFinance(<CostsPage initialTab="input-review" />, {
    api,
    filters,
  });
  await r.findByRole("tab", { name: "导入与操作核对 · 37" });
  fireEvent.click(await r.findByRole("button", { name: "查看核对单" }));
  const d = overlay(r);
  fireEvent.click(await d.findByRole("button", { name: /驳\s*回/ }));
  fireEvent.click(d.getByRole("button", { name: "确认驳回" }));
  expect(
    await r.findByRole("tab", { name: "导入与操作核对 · 36" }),
  ).toBeVisible();
  expect(r.queryByText(/操作已应用/)).toBeNull();
});
