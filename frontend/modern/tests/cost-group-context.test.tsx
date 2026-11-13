import { fireEvent, waitFor, within } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { CostsPage } from "../src/features/finance/costs/CostsPage";
import {
  createFinanceApiFixture,
  createFinanceHostFixture,
  defaultSession,
  renderFinance,
} from "./fixtures";
import type { FinanceApi, Row } from "../src/shared/contracts";

const group: Row = {
  live: true,
  shopId: "",
  groupId: "g-empty",
  costs: [
    {
      sku: "GROUP-SKU",
      warehouse: "菲律宾",
      prices: { "2026-10": "12.000001" },
    },
  ],
  manualCosts: [
    {
      id: "m1",
      sku: "GROUP-MANUAL",
      warehouse: "菲律宾",
      unitPriceCny: "7",
      unit: "包",
      version: 4,
    },
  ],
  products: [],
  imports: [{ id: "f1", source_name: "shared-source.csv", row_count: 1 }],
  shopCatalogs: {},
  groupCatalogs: {
    "g-empty": { sourceName: "shared-source.csv", costRows: 2 },
  },
};
function setup(flagged: boolean, manager = true, overrides: Row = {}) {
  const data = {
    ...group,
    ...(flagged ? { catalogLoaded: true, settingsLoaded: true } : {}),
    ...overrides,
  };
  const api = createFinanceApiFixture({
    get: vi.fn(async (path: string) =>
      path === "/workspace/costs" ? data : { items: [], groups: [] },
    ) as FinanceApi["get"],
    send: vi.fn(async () => ({ status: "APPLIED" })) as FinanceApi["send"],
    upload: vi.fn(async () => ({ inserted: 1 })) as FinanceApi["upload"],
  });
  const v = renderFinance(<CostsPage initialTab="costs" />, {
    api,
    filters: { shopId: "", groupId: "g-empty" },
    host: createFinanceHostFixture({ ...defaultSession, canManage: manager })
      .host,
  });
  return { v, api };
}
it.each([true, false])(
  "full group-only catalogue supports confirmed group source import/removal without shop writes (flags=%s)",
  async (flagged) => {
    const { v, api } = setup(flagged);
    await v.findByText("GROUP-SKU");
    expect(v.queryByText("成本资料不完整，请重新读取")).not.toBeInTheDocument();
    expect(
      v.getByRole("button", { name: "自动匹配 SKU / SPU" }),
    ).toBeDisabled();
    fireEvent.click(v.getByRole("button", { name: "导入分组成本表" }));
    let d = within(v.surface.overlayContainer);
    fireEvent.change(d.getByLabelText("选择成本文件"), {
      target: {
        files: [new File(["synthetic"], "group.csv", { type: "text/csv" })],
      },
    });
    expect(d.getByRole("button", { name: "确认导入成本文件" })).toBeDisabled();
    fireEvent.click(d.getByLabelText("已核对分组及来源资料"));
    fireEvent.click(d.getByRole("button", { name: "确认导入成本文件" }));
    await waitFor(() => expect(api.upload).toHaveBeenCalledTimes(1));
    const [path, form] = vi.mocked(api.upload).mock.calls[0]!;
    expect(path).toBe("/workspace/costs/import");
    expect(form.get("groupId")).toBe("g-empty");
    expect(form.has("shopId")).toBe(false);
    await waitFor(() =>
      expect(d.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    fireEvent.click(v.getByRole("button", { name: "移除成本文件" }));
    d = within(v.surface.overlayContainer);
    expect(d.getByRole("button", { name: "确认删除" })).toBeDisabled();
    fireEvent.click(d.getByLabelText("我已核对删除影响"));
    fireEvent.click(d.getByRole("button", { name: "确认删除" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/workspace/costs/groups/g-empty/files/f1",
        "DELETE",
        undefined,
        undefined,
      ),
    );
    await waitFor(() =>
      expect(d.queryByRole("dialog")).not.toBeInTheDocument(),
    );
    fireEvent.click(v.getByRole("tab", { name: "成本表设置" }));
    expect(
      v.queryByRole("button", { name: "保存成本设置" }),
    ).not.toBeInTheDocument();
    fireEvent.click(v.getByRole("tab", { name: "SKU 关联" }));
    expect(
      v.queryByRole("button", { name: "核对成本关联" }),
    ).not.toBeInTheDocument();
    expect(
      vi
        .mocked(api.send)
        .mock.calls.every(([p]) => p.startsWith("/workspace/costs/groups/")),
    ).toBe(true);
  },
);
it.each([true, false])(
  "reader can view full group catalogue and manual costs without management controls (flags=%s)",
  async (flagged) => {
    const { v, api } = setup(flagged, false);
    await v.findByText("GROUP-SKU");
    expect(
      v.queryByRole("button", { name: "导入分组成本表" }),
    ).not.toBeInTheDocument();
    expect(
      v.queryByRole("button", { name: "移除成本文件" }),
    ).not.toBeInTheDocument();
    fireEvent.click(v.getByRole("tab", { name: "手动管理" }));
    await v.findByText("GROUP-MANUAL");
    expect(
      v.queryByRole("button", { name: "修改手动成本" }),
    ).not.toBeInTheDocument();
    expect(api.send).not.toHaveBeenCalled();
  },
);
it.each([
  { settingsLoaded: false },
  { catalogLoaded: false },
  { imports: undefined },
  { products: [{ sku_id: "unexpected" }] },
  { shopId: "selected-but-incomplete" },
])(
  "partial group or selected-shop payload remains blocked (%j)",
  async (partial) => {
    const { v, api } = setup(true, true, partial);
    await v.findByText("成本资料不完整，请重新读取");
    expect(v.queryByText("GROUP-SKU")).not.toBeInTheDocument();
    expect(
      v.queryByRole("button", { name: "导入分组成本表" }),
    ).not.toBeInTheDocument();
    expect(api.send).not.toHaveBeenCalled();
  },
);
