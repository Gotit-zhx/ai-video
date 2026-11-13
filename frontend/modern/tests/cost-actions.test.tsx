import { act, fireEvent, waitFor, within } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { FinanceApplication } from "../src/features/finance/FinanceApplication";
import { CostsPage } from "../src/features/finance/costs/CostsPage";
import { ChargesPage } from "../src/features/finance/charges/ChargesPage";
import { QianyiPage } from "../src/features/finance/qianyi/QianyiPage";
import { useFinance } from "../src/features/finance/context";
import {
  createFinanceApiFixture,
  createFinanceHostFixture,
  defaultSession,
  renderFinance,
} from "./fixtures";
import type { FinanceApi, Row } from "../src/shared/contracts";
const base: Row = {
  shopId: "s1",
  groupId: "g1",
  costs: [{ sku: "SKU", warehouse: "菲律宾", prices: { "2026-10": 0 } }],
  manualCosts: [],
  imports: [{ id: "f1", source_name: "cost.xlsx", legacy_merged: true }],
  products: [{ product_id: "p1", sku_id: "v1", seller_sku: "SKU" }],
  mappings: {
    "s1:p1:v1:菲律宾": {
      code: "SKU",
      factor: 1,
      warehouse: "菲律宾",
      version: 3,
    },
  },
  settings: {
    warehouse: "菲律宾",
    rules: { currency: "CNY", unit: "包", basis: "order", missing: "pending" },
  },
  settingsVersion: 2,
};
const filters = {
  shopId: "s1",
  groupId: "g1",
  start: "2026-10-01",
  end: "2026-10-31",
};
const change: Row = {
  id: "c1",
  type: "IMPORT",
  status: "REVIEW",
  version: 5,
  canRevise: true,
  canReject: true,
  metadata: {
    reason: "source reason",
    evidenceRef: "receipt",
    unit: "包",
    identifierType: "SKU",
    sourceKey: "source-original",
  },
  impact: { hasHistorical: true },
  validation: { issues: [] },
};
function fixture(
  extra: Record<string, unknown> = {},
  overrides: Partial<FinanceApi> = {},
) {
  return createFinanceApiFixture({
    get: vi.fn(async (path: string) =>
      path in extra
        ? extra[path]
        : path === "/workspace/costs"
          ? base
          : path === "/workspace/costs/input-changes"
            ? { items: [change], total: 1 }
            : path === "/workspace/costs/input-changes/c1"
              ? change
              : path === "/workspace/label-costs"
                ? { files: [], rows: [], total: 0 }
                : path === "/workspace/charges"
                  ? { rows: [], imports: [] }
                  : path === "/qianyi/returns"
                    ? { items: [], total: 0 }
                    : [],
    ) as never,
    send: vi.fn(async () => ({ status: "APPLIED" })) as never,
    upload: vi.fn(async () => ({
      staged: true,
      changeId: "new",
      status: "REVIEW",
    })) as never,
    ...overrides,
  });
}
const overlay = (r: ReturnType<typeof renderFinance>) =>
  within(r.surface.overlayContainer);
function SetShop() {
  const { setFilters } = useFinance();
  return (
    <button onClick={() => setFilters((f) => ({ ...f, shopId: "s2" }))}>
      change shop
    </button>
  );
}
const safe = { ...base, inputSafety: { enabled: true } };
describe("cost action parity", () => {
  it("keeps the chosen tab on shared filter change", async () => {
    const r = renderFinance(
      <>
        <SetShop />
        <CostsPage />
      </>,
      { api: fixture(), filters },
    );
    fireEvent.click(await r.findByRole("tab", { name: "商品成本表" }));
    await r.findByText("cost.xlsx");
    fireEvent.click(r.getByRole("button", { name: "change shop" }));
    expect(r.getByRole("tab", { name: "商品成本表" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
  });
  it("refreshes successful cost imports even when a later file fails", async () => {
    const upload = vi
      .fn()
      .mockResolvedValueOnce({ id: "first" })
      .mockRejectedValueOnce(Error("invalid second"));
    const api = fixture({}, { upload: upload as never });
    const r = renderFinance(<CostsPage initialTab="costs" />, { api, filters });
    fireEvent.click(await r.findByRole("button", { name: "导入分组成本表" }));
    const d = overlay(r);
    fireEvent.change(d.getByLabelText("选择成本文件"), {
      target: {
        files: [new File(["a"], "one.csv"), new File(["b"], "two.csv")],
      },
    });
    fireEvent.click(d.getByLabelText("已核对分组及来源资料"));
    const before = vi
      .mocked(api.get)
      .mock.calls.filter((c) => c[0] === "/workspace/costs").length;
    fireEvent.click(d.getByRole("button", { name: "确认导入成本文件" }));
    await d.findByText(/已接收 1 份，重复 0 份；invalid second/);
    await waitFor(() =>
      expect(
        vi.mocked(api.get).mock.calls.filter((c) => c[0] === "/workspace/costs")
          .length,
      ).toBeGreaterThan(before),
    );
  });
  it("stages import metadata and never auto-confirms blocked input", async () => {
    const api = fixture(
      { "/workspace/costs": safe },
      {
        upload: vi.fn(async () => ({
          staged: true,
          changeId: "c2",
          status: "BLOCKED",
        })) as never,
      },
    );
    const r = renderFinance(<CostsPage initialTab="costs" />, { api, filters });
    fireEvent.click(await r.findByRole("button", { name: "导入分组成本表" }));
    const d = overlay(r);
    fireEvent.change(d.getByLabelText("选择成本文件"), {
      target: { files: [new File(["a"], "one.csv")] },
    });
    for (const [label, value] of [
      ["操作原因", "reason"],
      ["依据编号或说明", "evidence"],
      ["成本计价单位", "包"],
      ["来源标识", "source"],
    ])
      fireEvent.change(d.getByLabelText(label!), { target: { value } });
    fireEvent.click(d.getByLabelText("已核对分组及来源资料"));
    fireEvent.click(d.getByRole("button", { name: "暂存成本文件" }));
    await waitFor(() => expect(api.upload).toHaveBeenCalled());
    const [, form] = vi.mocked(api.upload).mock.calls[0]!;
    expect(form.get("groupId")).toBe("g1");
    expect(JSON.parse(String(form.get("metadata")))).toMatchObject({
      reason: "reason",
      evidenceRef: "evidence",
      unit: "包",
      sourceKey: "source",
    });
    await r.findByText(/BLOCKED/);
    expect(api.send).not.toHaveBeenCalled();
  });
  it("preserves legacy merged source warning and stages only selected source removal", async () => {
    const api = fixture({ "/workspace/costs": safe });
    const r = renderFinance(<CostsPage initialTab="costs" />, { api, filters });
    fireEvent.click(await r.findByRole("button", { name: "移除成本文件" }));
    const d = overlay(r);
    expect(d.getByText(/历史完整合并资料/)).toBeVisible();
    for (const [label, value] of [
      ["操作原因", "remove duplicate"],
      ["依据编号或说明", "e1"],
    ])
      fireEvent.change(d.getByLabelText(label!), { target: { value } });
    fireEvent.click(d.getByLabelText("我已核对删除影响"));
    fireEvent.click(d.getByRole("button", { name: "暂存移除申请" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/costs/groups/g1/files/f1",
        "DELETE",
        { metadata: { reason: "remove duplicate", evidenceRef: "e1" } },
        undefined,
      ),
    );
  });
  it("existing mapping requires confirmation, keeps version and decimal factor", async () => {
    const api = fixture();
    const r = renderFinance(<CostsPage initialTab="mapping" />, {
      api,
      filters,
    });
    fireEvent.click(await r.findByRole("button", { name: "核对成本关联" }));
    const d = overlay(r);
    fireEvent.change(d.getByLabelText("换算数量"), {
      target: { value: "2.000001" },
    });
    fireEvent.click(d.getByRole("button", { name: "提交成本关联" }));
    await d.findByText(/请选择型号和仓库/);
    expect(api.send).not.toHaveBeenCalled();
    fireEvent.click(d.getByLabelText("已核对销售规格、单价和计价单位"));
    fireEvent.click(d.getByRole("button", { name: "提交成本关联" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/costs/s1/mapping",
        "PUT",
        {
          product_id: "p1",
          sku_id: "v1",
          code: "SKU",
          warehouse: "菲律宾",
          factor: "2.000001",
          confirmed: true,
          version: 3,
        },
        undefined,
      ),
    );
  });
  it("disables removal while parsing and uses explicit failed retry/removal endpoints", async () => {
    const api = fixture({
      "/workspace/label-costs": {
        files: [
          { id: "parsing", source_name: "parsing.xlsx", status: "PARSING" },
          { id: "failed", source_name: "failed.xlsx", status: "FAILED" },
        ],
        rows: [],
      },
    });
    const r = renderFinance(<CostsPage />, { api, filters });
    const remove = await r.findAllByRole("button", { name: "移除月度账单" });
    expect(remove[0]).toBeDisabled();
    fireEvent.click(r.getByRole("button", { name: "重试账单处理" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/label-costs/imports/failed/retry",
        "POST",
        undefined,
        undefined,
      ),
    );
    fireEvent.click(remove[1]!);
    const d = overlay(r);
    expect(d.getByText(/后台完成后统一更新/)).toBeVisible();
    fireEvent.click(d.getByLabelText("我已核对删除影响"));
    fireEvent.click(d.getByRole("button", { name: "确认删除" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/label-costs/imports/failed",
        "DELETE",
        undefined,
        undefined,
      ),
    );
  });
  it("brush changing a file invalidates preview and commits the exact new file", async () => {
    const api = fixture(
      {},
      {
        upload: vi.fn(async (path: string) =>
          path.endsWith("/preview")
            ? { count: 1, matched: 1, rows: [] }
            : { id: "import" },
        ) as never,
      },
    );
    const r = renderFinance(<ChargesPage initialTab="brush" />, {
      api,
      filters,
    });
    fireEvent.click(
      await r.findByRole("button", { name: "预览并导入刷单来源" }),
    );
    const d = overlay(r),
      first = new File(["a"], "one.csv"),
      second = new File(["b"], "two.csv");
    fireEvent.change(d.getByLabelText("刷单来源文件"), {
      target: { files: [first] },
    });
    fireEvent.click(d.getByRole("button", { name: "预览刷单来源" }));
    await d.findByRole("button", { name: "确认导入刷单来源" });
    fireEvent.change(d.getByLabelText("刷单来源文件"), {
      target: { files: [second] },
    });
    expect(d.queryByRole("button", { name: "确认导入刷单来源" })).toBeNull();
    fireEvent.click(d.getByRole("button", { name: "预览刷单来源" }));
    fireEvent.click(await d.findByRole("button", { name: "确认导入刷单来源" }));
    await waitFor(() =>
      expect(
        vi
          .mocked(api.upload)
          .mock.calls.some((c) => c[0] === "/workspace/charges/import"),
      ).toBe(true),
    );
    const imported = vi
      .mocked(api.upload)
      .mock.calls.find((c) => c[0].endsWith("/import"))!;
    expect(imported[1].get("file")).toBe(second);
    expect(imported[1].get("groupId")).toBe("g1");
  });
  it("revise and reject keep exact version and original source metadata", async () => {
    const api = fixture({ "/workspace/costs": safe });
    const r = renderFinance(<CostsPage initialTab="input-review" />, {
      api,
      filters,
    });
    fireEvent.click(await r.findByRole("button", { name: "查看核对单" }));
    const d = overlay(r);
    fireEvent.click(await d.findByRole("button", { name: "补充资料" }));
    fireEvent.click(d.getByRole("button", { name: "保存并重新校验" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/costs/input-changes/c1/revise",
        "POST",
        { version: 5, metadata: change.metadata },
        undefined,
      ),
    );
    fireEvent.click(await r.findByRole("button", { name: "查看核对单" }));
    fireEvent.click(await d.findByRole("button", { name: /驳\s*回/ }));
    fireEvent.click(d.getByRole("button", { name: "确认驳回" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/costs/input-changes/c1/reject",
        "POST",
        { version: 5, reason: "source reason" },
        undefined,
      ),
    );
  });
  it("undo generates another review and never claims rollback applied", async () => {
    const applied = {
      ...change,
      status: "APPLIED",
      canUndo: true,
      canRevise: false,
      canReject: false,
    };
    const api = fixture(
      {
        "/workspace/costs": safe,
        "/workspace/costs/input-changes/c1": applied,
      },
      {
        send: vi.fn(async () => ({
          staged: true,
          changeId: "undo-id",
          status: "REVIEW",
        })) as never,
      },
    );
    const r = renderFinance(<CostsPage initialTab="input-review" />, {
      api,
      filters,
    });
    fireEvent.click(await r.findByRole("button", { name: "查看核对单" }));
    const d = overlay(r);
    fireEvent.click(await d.findByRole("button", { name: "撤销本次操作" }));
    expect(d.getByRole("button", { name: "生成撤销核对单" })).toBeDisabled();
    fireEvent.change(d.getByLabelText("操作原因"), {
      target: { value: "undo reason" },
    });
    fireEvent.change(d.getByLabelText("依据编号或说明"), {
      target: { value: "undo receipt" },
    });
    fireEvent.click(d.getByRole("button", { name: "生成撤销核对单" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/costs/input-changes/c1/undo",
        "POST",
        { version: 5, reason: "undo reason", evidenceRef: "undo receipt" },
        undefined,
      ),
    );
    expect(await r.findByText(/已暂存，尚未应用/)).toBeVisible();
  });
  it("keeps unknown review status visible and honors canConfirm false", async () => {
    const api = fixture({
      "/workspace/costs": safe,
      "/workspace/costs/input-changes/c1": {
        ...change,
        status: "FUTURE_STATUS",
        canConfirm: false,
        requiresIndependentReview: true,
      },
    });
    const r = renderFinance(<CostsPage initialTab="input-review" />, {
      api,
      filters,
    });
    fireEvent.click(await r.findByRole("button", { name: "查看核对单" }));
    const d = overlay(r);
    expect(await d.findByText(/FUTURE_STATUS/)).toBeVisible();
    expect(d.queryByRole("button", { name: "复核并应用" })).toBeNull();
  });
  it("blocks late staged results after company switch", async () => {
    let finish!: (v: unknown) => void;
    const send = vi.fn(() => new Promise((r) => (finish = r)));
    const host = createFinanceHostFixture();
    const r = renderFinance(<CostsPage initialTab="settings" />, {
      api: fixture({}, { send: send as never }),
      host: host.host,
      filters,
    });
    fireEvent.click(await r.findByRole("button", { name: "保存成本设置" }));
    act(() => host.update({ ...defaultSession, organizationId: "org-2" }));
    await act(async () =>
      finish({ staged: true, changeId: "old-private", status: "REVIEW" }),
    );
    expect(r.queryByText(/old-private/)).toBeNull();
  });
  it("exports Qianyi with explicit empty dates and exact numbers", async () => {
    const api = fixture();
    const r = renderFinance(<QianyiPage />, { api, filters });
    fireEvent.click(await r.findByRole("button", { name: "查询全部收货期间" }));
    fireEvent.change(r.getByLabelText("平台订单号 / 千易售后单号"), {
      target: { value: "ORDER_A RETURN_B" },
    });
    fireEvent.click(r.getByRole("button", { name: "查询售后" }));
    await waitFor(() =>
      expect(r.getByRole("button", { name: "导出千易售后" })).toBeEnabled(),
    );
    fireEvent.click(r.getByRole("button", { name: "导出千易售后" }));
    await waitFor(() =>
      expect(api.download).toHaveBeenCalledWith(
        "/qianyi/returns/export",
        expect.objectContaining({
          start: "",
          end: "",
          numbers: "ORDER_A RETURN_B",
          dateField: "RECEIPT",
          currency: "",
        }),
        "qianyi-returns.xlsx",
        expect.objectContaining({ signal: expect.any(AbortSignal) }),
      ),
    );
  });
  it("displays Qianyi receiveTime and return evidence without changing long IDs", async () => {
    const api = fixture({
      "/qianyi/returns": {
        items: [{ shop_id: "s1", return_number: "R1" }],
        total: 1,
      },
      "/qianyi/returns/detail": {
        return_number: "R1",
        source_received_at: "2026-11-01T00:00:00Z",
        sourceFields: {
          receiveTime: "2026-11-01T00:00:00Z",
          _returnReceipt: { event: "return delivered" },
          returnSkuList: [
            { sku: "SKU", orderSkuId: "90071992547409931234", quantity: 1 },
          ],
        },
        returnCost: { amountCny: null, knownAmountCny: 2, lines: [] },
      },
    });
    const r = renderFinance(<QianyiPage />, { api, filters });
    fireEvent.click(await r.findByRole("button", { name: "查看售后证据" }));
    const d = overlay(r);
    expect(await d.findByText("return delivered")).toBeVisible();
    expect(d.getByText("90071992547409931234")).toBeVisible();
    expect(
      d.getByRole("columnheader", { name: "千易 receiveTime" }),
    ).toBeVisible();
    expect(api.get).toHaveBeenCalledWith(
      "/qianyi/returns/detail",
      { shopId: "s1", returnNumber: "R1" },
      expect.any(AbortSignal),
    );
  });
});

describe("additional audited actions", () => {
  it("creates an independent manual price and mapping atomically", async () => {
    const api = fixture();
    const r = renderFinance(<CostsPage initialTab="mapping" />, {
      api,
      filters,
    });
    fireEvent.click(await r.findByRole("button", { name: "核对成本关联" }));
    const d = overlay(r);
    fireEvent.mouseDown(d.getByRole("combobox", { name: "成本来源" }));
    fireEvent.click(d.getByTitle("新增手动成本并关联"));
    fireEvent.change(d.getByLabelText("成本型号"), {
      target: { value: "LOCAL" },
    });
    fireEvent.change(d.getByLabelText("人民币单价"), {
      target: { value: "2.000001" },
    });
    fireEvent.change(d.getByLabelText("成本计价单位"), {
      target: { value: "包" },
    });
    fireEvent.click(d.getByLabelText("已核对销售规格、单价和计价单位"));
    fireEvent.click(d.getByRole("button", { name: "提交成本关联" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/costs/s1/manual-mapping",
        "POST",
        {
          product_id: "p1",
          sku_id: "v1",
          code: "LOCAL",
          warehouse: "菲律宾",
          factor: "1",
          confirmed: true,
          version: 3,
          unitPriceCny: "2.000001",
          unit: "包",
          zeroConfirmed: false,
        },
        undefined,
      ),
    );
  });
  it("brush removal preserves group ownership and rematch does not revive retired rules", async () => {
    const api = fixture({
      "/workspace/charges": {
        rows: [],
        imports: [{ id: "f1", source_name: "brush.csv" }],
      },
    });
    const r = renderFinance(<ChargesPage initialTab="brush" />, {
      api,
      filters,
    });
    fireEvent.click(await r.findByRole("button", { name: "重新匹配刷单来源" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/charges/rematch",
        "POST",
        { groupId: "g1" },
        undefined,
      ),
    );
    fireEvent.click(r.getByRole("button", { name: "移除刷单来源" }));
    const d = overlay(r);
    fireEvent.click(d.getByLabelText("我已核对删除影响"));
    fireEvent.click(d.getByRole("button", { name: "确认删除" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/charges/imports/f1?groupId=g1",
        "DELETE",
        undefined,
        undefined,
      ),
    );
    expect(
      vi.mocked(api.send).mock.calls.some((c) => c[0].includes("/rules")),
    ).toBe(false);
  });
  it("monthly rematch is explicitly company-wide and upload preserves partial acceptance", async () => {
    const upload = vi
      .fn()
      .mockResolvedValueOnce({ id: "first", status: "QUEUED" })
      .mockRejectedValueOnce(Error("queue full"));
    const api = fixture({}, { upload: upload as never });
    const r = renderFinance(<CostsPage />, { api, filters });
    fireEvent.click(await r.findByRole("button", { name: "重新匹配全部账单" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/label-costs/rematch",
        "POST",
        undefined,
        undefined,
      ),
    );
    fireEvent.change(r.getByLabelText("选择月度账单"), {
      target: {
        files: [new File(["a"], "one.xlsx"), new File(["b"], "two.xlsx")],
      },
    });
    fireEvent.click(r.getByRole("button", { name: "上传月度账单" }));
    expect(
      await r.findByText(/已接收 1 份，重复 0 份；queue full/),
    ).toBeVisible();
    expect(upload).toHaveBeenCalledTimes(2);
  });
  it("stale confirmation reloads new preview and cannot silently repeat the old confirmation", async () => {
    let stale = false;
    const reviewing = {
      ...change,
      canConfirm: true,
      requiresIndependentReview: false,
      status: "READY",
    };
    const api = fixture(
      { "/workspace/costs": safe },
      {
        get: vi.fn(async (path: string) =>
          path === "/workspace/costs"
            ? safe
            : path.endsWith("/input-changes")
              ? { items: [reviewing], total: 1 }
              : path.endsWith("/c1")
                ? stale
                  ? {
                      ...reviewing,
                      status: "STALE",
                      version: 6,
                      canConfirm: false,
                    }
                  : reviewing
                : [],
        ) as never,
        send: vi.fn(async () => {
          stale = true;
          throw Error("旧预览已过期，请重新预览");
        }) as never,
      },
    );
    const r = renderFinance(<CostsPage initialTab="input-review" />, {
      api,
      filters,
    });
    fireEvent.click(await r.findByRole("button", { name: "查看核对单" }));
    const d = overlay(r);
    await d.findByRole("button", { name: "确认应用" });
    fireEvent.click(d.getByLabelText("我已核对实际影响范围"));
    fireEvent.click(d.getByLabelText("我已确认会影响历史及后续订单"));
    fireEvent.click(d.getByRole("button", { name: "确认应用" }));
    await d.findByText(/预览已过期/);
    expect(d.queryByRole("button", { name: "确认应用" })).toBeNull();
    expect(api.send).toHaveBeenCalledTimes(1);
    expect(api.get).toHaveBeenCalledWith(
      "/workspace/costs/input-changes/c1",
      {},
      expect.any(AbortSignal),
    );
  });
  it("read-only users retain cost evidence while management mutations remain absent", async () => {
    const api = fixture();
    const host = createFinanceHostFixture({
      ...defaultSession,
      canManage: false,
    });
    const r = renderFinance(<CostsPage initialTab="mapping" />, {
      api,
      host: host.host,
      filters,
    });
    await r.findAllByText("SKU", { selector: "td" });
    expect(r.queryByRole("button", { name: "核对成本关联" })).toBeNull();
    expect(r.queryByRole("button", { name: "自动匹配 SKU / SPU" })).toBeNull();
    expect(api.send).not.toHaveBeenCalled();
  });
});

it("shell forwards original cost route context to an extension", async () => {
  const route =
    "costs?tab=orders&recordId=source-1&kind=SETTLED_ORDER&numbers=ORDER_A";
  const r = renderFinance(
    <FinanceApplication
      pages={{ costs: ({ route: received }) => <output>{received}</output> }}
    />,
    { api: fixture(), filters, route },
  );
  expect(await r.findByText(route)).toBeVisible();
});

it("order SPU evidence deduplicates text IDs and ignores missing values", async () => {
  const api = fixture({
    "/workspace/costs/orders": {
      items: [
        {
          id: "o1",
          order_id: "A",
          product_spu_values: [
            "90071992547409931234",
            "90071992547409931234",
            "",
          ],
        },
      ],
      total: 1,
    },
  });
  const r = renderFinance(<CostsPage initialTab="orders" />, { api, filters });
  expect(await r.findByText("90071992547409931234")).toBeVisible();
  expect(
    r.queryByText("90071992547409931234 / 90071992547409931234 / "),
  ).toBeNull();
});
it("completed brush data does not keep polling or recreate mutations", async () => {
  vi.useFakeTimers();
  try {
    const api = fixture({
      "/workspace/charges": {
        rows: [],
        imports: [],
        totals: { unmatched: 0 },
        labelSummary: { processing: false },
      },
    });
    const r = renderFinance(<ChargesPage initialTab="brush" />, {
      api,
      filters,
    });
    await act(async () => vi.advanceTimersByTimeAsync(50));
    const before = vi.mocked(api.get).mock.calls.length;
    await act(async () => vi.advanceTimersByTimeAsync(16000));
    expect(api.get).toHaveBeenCalledTimes(before);
    expect(api.send).not.toHaveBeenCalled();
    r.unmount();
  } finally {
    vi.useRealTimers();
  }
});

it("brush preview uses parsed camel-case source fields and nested match evidence", async () => {
  const api = fixture(
    {},
    {
      upload: vi.fn(async () => ({
        count: 1,
        matched: 0,
        serviceFeeCny: 3,
        rows: [
          {
            orderId: "SOURCE_ORDER",
            shopHint: "Original Shop",
            principalNative: "100.01",
            serviceFeeCny: 3,
            match: {
              matchStatus: "REVIEW",
              matchNote: "exact amount mismatch",
            },
          },
        ],
      })) as never,
    },
  );
  const r = renderFinance(<ChargesPage initialTab="brush" />, { api, filters });
  fireEvent.click(await r.findByRole("button", { name: "预览并导入刷单来源" }));
  const d = overlay(r);
  fireEvent.change(d.getByLabelText("刷单来源文件"), {
    target: { files: [new File(["a"], "one.csv")] },
  });
  fireEvent.click(d.getByRole("button", { name: "预览刷单来源" }));
  expect(await d.findByText("SOURCE_ORDER")).toBeVisible();
  expect(d.getByText("exact amount mismatch")).toBeVisible();
  expect(d.getByText("Original Shop")).toBeVisible();
});
it("brush requires a real group and does not query an unsupported all-groups endpoint", async () => {
  const api = fixture();
  const r = renderFinance(<ChargesPage initialTab="brush" />, {
    api,
    filters: { ...filters, groupId: "" },
  });
  expect(await r.findByText("请选择费用分组后查看刷单来源")).toBeVisible();
  expect(
    vi.mocked(api.get).mock.calls.some((c) => c[0] === "/workspace/charges"),
  ).toBe(false);
});
