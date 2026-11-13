import { fireEvent, waitFor, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { CostsPage } from "../src/features/finance/costs/CostsPage";
import { ChargesPage } from "../src/features/finance/charges/ChargesPage";
import { QianyiPage } from "../src/features/finance/qianyi/QianyiPage";
import {
  costOrderSelection,
  costCandidate,
  operationReceipt,
} from "../src/features/finance/costs/domain";
import {
  createFinanceApiFixture,
  createFinanceHostFixture,
  defaultSession,
  renderFinance,
} from "./fixtures";
import type { Row } from "../src/shared/contracts";
export const workspace: Row = {
  shopId: "s1",
  groupId: "g1",
  costs: [
    {
      sku: "PACK*2",
      warehouse: "菲律宾",
      prices: { "2026-10": "12.000001" },
      priceSources: { "2026-10": { id: "f1", name: "original.xlsx" } },
    },
  ],
  manualCosts: [
    {
      id: "m1",
      sku: "MANUAL",
      warehouse: "菲律宾",
      unit: "包",
      unitPriceCny: "1.000001",
      version: 7,
    },
  ],
  imports: [{ id: "f1", source_name: "original.xlsx" }],
  settings: {
    warehouse: "菲律宾",
    rules: {
      currency: "CNY",
      unit: "包",
      basis: "order",
      missing: "pending",
      freight: "included",
      inbound: "excluded",
    },
  },
  settingsVersion: 8,
  suggestedWarehouse: "菲律宾",
  products: [
    {
      shop_id: "s1",
      product_id: "90071992547409931234",
      sku_id: "v1",
      seller_sku: "PACK*2",
      name: "套装",
    },
    { shop_id: "s1", product_id: "gift", sku_id: "gift", seller_sku: " gift " },
  ],
  mappings: {},
};
function fixture(extra: Record<string, unknown> = {}) {
  return createFinanceApiFixture({
    get: vi.fn(async (path: string) =>
      path in extra
        ? extra[path]
        : path === "/shops"
          ? {
              items: [
                { id: "s1", group_id: "g1", name: "店铺一", region: "PH" },
              ],
              groups: [{ id: "g1", name: "分组一" }],
            }
          : path === "/workspace/costs"
            ? workspace
            : path === "/workspace/label-costs"
              ? {
                  files: [],
                  rows: [],
                  period: { orders: 0, amount_cny: 0 },
                  total: 0,
                }
              : path.includes("exports")
                ? []
                : { items: [], rows: [], total: 0 },
    ) as never,
    send: vi.fn(async () => ({ status: "APPLIED" })) as never,
    upload: vi.fn(async () => ({ id: "new", status: "QUEUED" })) as never,
  });
}
const filters = {
  shopId: "s1",
  groupId: "g1",
  platform: "SHOPEE",
  currency: "PHP",
  start: "2026-10-01",
  end: "2026-10-31",
};
const overlay = (r: ReturnType<typeof renderFinance>) =>
  within(r.surface.overlayContainer);
describe("cost contracts", () => {
  it("clears all company narrowing fields for pending selection", () => {
    expect(
      costOrderSelection(
        { ...filters, recordId: "r1", kind: "REFUND", numbers: "A B" },
        true,
      ),
    ).toEqual({
      ...filters,
      shopId: "",
      groupId: "",
      platform: "",
      currency: "",
      recordId: "",
      kind: "SETTLED_ORDER",
      numbers: "A B",
      costStatus: "PENDING",
    });
  });
  it("preserves GIFT, full bundles, country and ambiguity rules", () => {
    const costs = workspace.costs as Row[];
    expect(costCandidate({ seller_sku: " gift " }, costs, "")).toMatchObject({
      fixedGift: true,
      code: "GIFT",
    });
    expect(
      costCandidate({ seller_sku: "PACK*2" }, costs, "菲律宾"),
    ).toMatchObject({ code: "PACK*2", factor: "1" });
    expect(costCandidate({ seller_sku: "PACK*2" }, costs, "泰国").code).toBe(
      "",
    );
    expect(costCandidate({ seller_sku: "PACK*2" }, costs, "").code).toBe("");
    expect(
      costCandidate(
        { seller_sku: " (A) " },
        [
          { sku: "A", warehouse: "菲律宾" },
          { sku: "（A）", warehouse: "菲律宾" },
        ],
        "菲律宾",
      ).code,
    ).toBe("");
  });
  it("uses SKU before textual SPU and preserves bundle factor", () => {
    const c = [
      { sku: "12345678901234567890", warehouse: "菲律宾" },
      { sku: "BASE", warehouse: "菲律宾" },
    ];
    expect(
      costCandidate(
        { seller_sku: "BASE*2", product_id: "12345678901234567890" },
        c,
        "菲律宾",
      ),
    ).toMatchObject({ code: "BASE", factor: "2" });
    expect(
      costCandidate(
        { seller_sku: "missing", product_id: "12345678901234567890" },
        c,
        "菲律宾",
      ),
    ).toMatchObject({
      code: "12345678901234567890",
      factor: "1",
      method: "SPU_CODE",
    });
  });
  it("distinguishes blocked staging from applied duplicate", () => {
    expect(
      operationReceipt({ staged: true, changeId: "c1", status: "BLOCKED" }),
    ).toContain("BLOCKED");
    expect(
      operationReceipt({ staged: true, duplicate: true, status: "APPLIED" }),
    ).toContain("已应用");
  });
  it("defaults to company monthly upload without groupId", async () => {
    const api = fixture();
    const r = renderFinance(<CostsPage />, { api, filters });
    fireEvent.change(await r.findByLabelText("选择月度账单"), {
      target: { files: [new File(["xlsx"], "bill.xlsx")] },
    });
    fireEvent.click(r.getByRole("button", { name: "上传月度账单" }));
    await waitFor(() => expect(api.upload).toHaveBeenCalled());
    const [path, form] = vi.mocked(api.upload).mock.calls[0]!;
    expect(path).toBe("/workspace/label-costs/import");
    expect(form.has("groupId")).toBe(false);
  });
  it("preserves hidden rules and optimistic version", async () => {
    const api = fixture();
    const r = renderFinance(<CostsPage initialTab="settings" />, {
      api,
      filters,
    });
    fireEvent.click(await r.findByRole("button", { name: "保存成本设置" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/costs/s1/settings",
        "PUT",
        expect.objectContaining({
          version: 8,
          rules: expect.objectContaining({
            freight: "included",
            inbound: "excluded",
            currency: "CNY",
          }),
        }),
        undefined,
      ),
    );
  });
  it("manual deletion has impact confirmation and version body", async () => {
    const api = fixture();
    const r = renderFinance(<CostsPage initialTab="manual" />, {
      api,
      filters,
    });
    fireEvent.click(await r.findByRole("button", { name: "删除手动成本" }));
    const d = overlay(r);
    expect(d.getByText(/原关联保留为失效引用/)).toBeVisible();
    expect(d.getByRole("button", { name: "确认删除" })).toBeDisabled();
    fireEvent.click(d.getByLabelText("我已核对删除影响"));
    fireEvent.click(d.getByRole("button", { name: "确认删除" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/costs/groups/g1/manual/m1",
        "DELETE",
        { version: 7 },
        undefined,
      ),
    );
  });
  it("manual edits keep decimal text and require zero confirmation", async () => {
    const api = fixture();
    const r = renderFinance(<CostsPage initialTab="manual" />, {
      api,
      filters,
    });
    fireEvent.click(await r.findByRole("button", { name: "修改手动成本" }));
    const d = overlay(r);
    fireEvent.change(d.getByLabelText("人民币单价"), {
      target: { value: "0" },
    });
    fireEvent.click(d.getByLabelText("按新单价重算历史及后续订单"));
    fireEvent.click(d.getByRole("button", { name: "提交手动成本" }));
    await d.findByText("零元价格必须确认真实零成本");
    expect(
      vi.mocked(api.send).mock.calls.some((c) => c[0].includes("/manual/")),
    ).toBe(false);
    fireEvent.click(d.getByLabelText("确认真实零成本"));
    fireEvent.change(d.getByLabelText("人民币单价"), {
      target: { value: "123456789012345678.123456" },
    });
    fireEvent.click(d.getByRole("button", { name: "提交手动成本" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/costs/groups/g1/manual/m1",
        "PUT",
        expect.objectContaining({
          unitPriceCny: "123456789012345678.123456",
          version: 7,
          confirmed: true,
        }),
        undefined,
      ),
    );
  });
  it("pending list and personal export share full-company selection", async () => {
    const api = fixture({
      "/workspace/costs/orders": {
        items: [],
        total: 103,
        costAmountSummary: {
          amount_cny: null,
          known_amount_cny: 987.65,
          pending_count: 103,
        },
      },
    });
    const r = renderFinance(<CostsPage initialTab="orders" />, {
      api,
      filters,
    });
    fireEvent.change(await r.findByLabelText("精确订单编号"), {
      target: { value: "ORDER_A ORDER_B" },
    });
    fireEvent.click(r.getByRole("button", { name: "查询订单成本" }));
    fireEvent.click(r.getByRole("button", { name: "所有店铺待补齐" }));
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith(
        "/workspace/costs/orders",
        expect.objectContaining({
          shopId: "",
          groupId: "",
          platform: "",
          currency: "",
          recordId: "",
          kind: "SETTLED_ORDER",
          numbers: "ORDER_A ORDER_B",
          costStatus: "PENDING",
        }),
        expect.any(AbortSignal),
      ),
    );
    await r.findByText(/987.65/);
    fireEvent.click(r.getByRole("button", { name: "订单成本导出" }));
    fireEvent.click(
      await overlay(r).findByRole("button", { name: "创建导出任务" }),
    );
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/cost-exports",
        "POST",
        expect.objectContaining({
          shopId: "",
          groupId: "",
          kind: "SETTLED_ORDER",
          costStatus: "PENDING",
          numbers: "ORDER_A ORDER_B",
        }),
        expect.objectContaining({ headers: expect.any(Object) }),
      ),
    );
  });
  it("brush metrics use backend nonzero principal count and fee sum across pages", async () => {
    const api = fixture({
      "/workspace/charges": {
        rows: [
          { id: "a", order_id: "A", principal_native: 0, service_fee_cny: 3 },
        ],
        sourceSummary: {
          records: 103,
          principal_rows: 99,
          service_fee_cny: 456.78,
        },
        totals: { records: 200 },
        marked: { orders: 150, service_fee_cny: 800 },
        periodMarked: { orders: 4, service_fee_cny: 20 },
      },
    });
    const r = renderFinance(<ChargesPage initialTab="brush" />, {
      api,
      filters,
    });
    expect(await r.findByText("99")).toBeVisible();
    expect(r.getByText("CNY 456.78")).toBeVisible();
    expect(r.getByText("CNY 800.00")).toBeVisible();
  });
  it("viewer reads Qianyi received evidence without management config", async () => {
    const api = fixture({
      "/qianyi/returns": {
        items: [
          {
            shop_id: "s1",
            return_number: "R1",
            source_received_at: "2026-11-01T00:00:00Z",
            return_receipt_label: "已签收",
            return_state: "FINISH",
          },
        ],
        total: 1,
        summary: {
          records: 1,
          refundTotals: [
            { currency: "UNKNOWN", records: 1, known_count: 0, amount: null },
          ],
          returnCost: { amountCny: null, knownAmountCny: 4, pendingRecords: 1 },
        },
      },
    });
    const host = createFinanceHostFixture({
      ...defaultSession,
      canManage: false,
    });
    const r = renderFinance(<QianyiPage />, { api, filters, host: host.host });
    await r.findByText("R1");
    expect(api.get).toHaveBeenCalledWith(
      "/qianyi/returns",
      expect.objectContaining({
        receiptScope: "RECEIVED",
        dateField: "RECEIPT",
      }),
      expect.any(AbortSignal),
    );
    expect(vi.mocked(api.get).mock.calls.some((c) => c[0] === "/qianyi")).toBe(
      false,
    );
    expect(r.queryByRole("button", { name: "千易全局设置" })).toBeNull();
    expect(r.getByText(/不冲回营业成本/)).toBeVisible();
  });
  it("legacy backend never fetches staged review", async () => {
    const api = fixture();
    const r = renderFinance(<CostsPage />, { api, filters });
    await waitFor(() =>
      expect(api.get).toHaveBeenCalledWith(
        "/workspace/costs",
        expect.any(Object),
        expect.any(AbortSignal),
      ),
    );
    expect(r.queryByRole("tab", { name: "导入与操作核对" })).toBeNull();
    expect(
      vi.mocked(api.get).mock.calls.some((c) => c[0].includes("input-changes")),
    ).toBe(false);
  });
  it("review confirms exact version with explicit actual/historical/revision acknowledgements", async () => {
    const change = {
      id: "c1",
      type: "MANUAL_UPDATE",
      status: "REVIEW",
      version: 4,
      canConfirm: true,
      canRevise: true,
      canReject: true,
      requiresIndependentReview: true,
      submittedBy: "another",
      metadata: { reason: "price fix", evidenceRef: "receipt" },
      inputSummary: { code: "MANUAL", warehouse: "菲律宾", unit: "包" },
      impact: {
        hasHistorical: true,
        scopeOrderCount: 123,
        coverage: "SCOPE_UPPER_BOUND",
      },
      validation: { priceOverride: true, issues: [] },
      diff: [{ field: "unitPriceCny", before: 1, after: 2 }],
    };
    const api = fixture({
      "/workspace/costs": { ...workspace, inputSafety: { enabled: true } },
      "/workspace/costs/input-changes": { items: [change], total: 1 },
      "/workspace/costs/input-changes/c1": change,
    });
    const r = renderFinance(<CostsPage initialTab="input-review" />, {
      api,
      filters,
    });
    fireEvent.click(await r.findByRole("button", { name: "查看核对单" }));
    const d = overlay(r);
    await d.findByText("人工成本操作核对");
    expect(await d.findByRole("button", { name: "复核并应用" })).toBeDisabled();
    fireEvent.click(d.getByLabelText("我已核对实际影响范围"));
    fireEvent.click(d.getByLabelText("我已确认会影响历史及后续订单"));
    fireEvent.click(d.getByLabelText("明确修订已有价格"));
    fireEvent.click(d.getByRole("button", { name: "复核并应用" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/costs/input-changes/c1/confirm",
        "POST",
        {
          version: 4,
          reason: "price fix",
          evidenceRef: "receipt",
          acknowledgeImpact: true,
          acknowledgeHistorical: true,
          revision: true,
        },
        undefined,
      ),
    );
  });
});
