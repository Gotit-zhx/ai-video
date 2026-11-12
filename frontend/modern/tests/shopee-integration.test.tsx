import { act, fireEvent, waitFor, within } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import type { FinanceApi, Row } from "../src/shared/contracts";
import { ShopsPage } from "../src/features/finance/shops/ShopsPage";
import { AdvertisingPage } from "../src/features/finance/reports/AdvertisingPage";
import {
  createFinanceApiFixture,
  createFinanceHostFixture,
  defaultSession,
  renderFinance,
} from "./fixtures";

const shops = [
  {
    id: "s1",
    name: "Finance shop",
    platform: "SHOPEE",
    group_id: "g1",
    currency: "PHP",
  },
];
const source = {
  configured: true,
  coverageBasis: "官方 CPC expense；GMS 覆盖关系待核实",
  items: [
    {
      external_shop_id: "9001",
      name: "Official one",
      shop_id: "s1",
      group_id: "g1",
      currency: "PHP",
      enabled: true,
      status: "SUCCESS",
      records: 0,
      last_success_at: "2026-11-12T01:00:00Z",
    },
    {
      external_shop_id: "9002",
      name: "Other group",
      group_id: "g2",
      currency: "USD",
      enabled: false,
      status: "PAUSED",
      records: 31,
    },
    {
      external_shop_id: "9003",
      name: "Waiting shop",
      currency: "PHP",
      enabled: true,
      status: "WAITING_SHOP",
      error_message: "尚未匹配正式店铺",
    },
  ],
};
function apiFixture(data: Row = source, overrides: Partial<FinanceApi> = {}) {
  return createFinanceApiFixture({
    get: vi.fn(async (path: string) =>
      path === "/shops"
        ? { items: shops, groups: [], sync: [], deletions: [] }
        : path === "/shopee-ads"
          ? data
          : [],
    ) as FinanceApi["get"],
    ...overrides,
  });
}
function overlay(v: ReturnType<typeof renderFinance>) {
  return within(v.surface.overlayContainer);
}

it("readers see scoped Shopee collection facts including real zero, without authorization or write controls", async () => {
  const api = apiFixture(),
    host = createFinanceHostFixture({ ...defaultSession, canManage: false });
  const view = renderFinance(<ShopsPage />, {
    api,
    host: host.host,
    filters: { groupId: "g1", currency: "PHP" },
  });
  await view.findByText("Official one");
  expect(view.queryByText("Other group")).toBeNull();
  expect(view.queryByText("Waiting shop")).toBeNull();
  expect(view.getByText("9001")).toBeVisible();
  expect(view.getByText("9001").closest("tr")).toHaveTextContent("0");
  expect(
    view.queryByRole("button", { name: "生成 Shopee 广告授权链接" }),
  ).toBeNull();
  expect(
    view.queryByRole("button", { name: "暂停 Shopee 广告采集" }),
  ).toBeNull();
  expect(api.send).not.toHaveBeenCalled();
});

it("ungrouped grants and unknown record counts remain distinguishable from zero", async () => {
  const view = renderFinance(<ShopsPage />, {
    api: apiFixture(),
    filters: { groupId: "UNGROUPED" },
  });
  expect(await view.findByText("Waiting shop")).toBeVisible();
  expect(view.getByText("尚未匹配正式店铺")).toBeVisible();
  expect(view.queryByText("Official one")).toBeNull();
  const row = view.getByText("9003").closest("tr")!;
  expect(within(row).getAllByText("—").length).toBeGreaterThan(0);
});

it("Shopee authorization is explicit, organization-scoped and keeps the signed HTTPS URL unchanged", async () => {
  const signed =
    "https://open.shopee.com/auth?state=fixture-state&redirect_uri=https%3A%2F%2Flocal.example%2Fcallback";
  const send = vi.fn<FinanceApi["send"]>(
    async () =>
      ({
        authorizationUrl: signed,
        expiresAt: "2026-11-12T12:00:00Z",
      }) as never,
  );
  const view = renderFinance(<ShopsPage />, {
    api: apiFixture(source, { send: send as FinanceApi["send"] }),
  });
  fireEvent.click(
    await view.findByRole("button", { name: "生成 Shopee 广告授权链接" }),
  );
  const d = overlay(view);
  expect(send).not.toHaveBeenCalled();
  const generate = d.getByRole("button", { name: "生成授权链接" });
  fireEvent.click(generate);
  fireEvent.click(generate);
  expect(await d.findByLabelText("Shopee 官方授权链接")).toHaveValue(signed);
  expect(send).toHaveBeenCalledExactlyOnceWith(
    "/shopee-ads/authorize",
    "POST",
    { organizationId: "org-1" },
    undefined,
  );
  const open = d.getByRole("link", { name: "在当前设备打开授权" });
  expect(open).toHaveAttribute("href", signed);
  expect(open).toHaveAttribute("target", "_blank");
  expect(open).toHaveAttribute("rel", "noopener noreferrer");
});

it.each([
  "http://open.shopee.com/auth",
  "https://user:password@open.shopee.com/auth",
])("rejects unsafe Shopee authorization URL %s", async (authorizationUrl) => {
  const send = vi.fn<FinanceApi["send"]>(
    async () => ({ authorizationUrl }) as never,
  );
  const view = renderFinance(<ShopsPage />, {
    api: apiFixture(source, { send: send as FinanceApi["send"] }),
  });
  fireEvent.click(
    await view.findByRole("button", { name: "生成 Shopee 广告授权链接" }),
  );
  const d = overlay(view);
  fireEvent.click(d.getByRole("button", { name: "生成授权链接" }));
  expect(await d.findByText("平台授权地址无效")).toBeVisible();
  expect(d.queryByLabelText("Shopee 官方授权链接")).toBeNull();
  expect(send).toHaveBeenCalledTimes(1);
});

it("Shopee pause uses only official shop identity, current organization and explicit enabled state", async () => {
  const send = vi.fn<FinanceApi["send"]>(async () => "done" as never),
    api = apiFixture(source, { send: send as FinanceApi["send"] });
  const view = renderFinance(<ShopsPage />, {
    api,
    filters: { groupId: "g1" },
  });
  fireEvent.click(
    await view.findByRole("button", { name: "暂停 Shopee 广告采集" }),
  );
  await waitFor(() =>
    expect(send).toHaveBeenCalledExactlyOnceWith(
      "/shopee-ads/9001",
      "PUT",
      { organizationId: "org-1", enabled: false },
      undefined,
    ),
  );
  await waitFor(() =>
    expect(
      vi.mocked(api.get).mock.calls.filter(([path]) => path === "/shopee-ads")
        .length,
    ).toBe(2),
  );
});

it("a late authorization response after losing manage permission never exposes the link", async () => {
  let resolve!: (value: unknown) => void;
  const send = vi.fn(
    () => new Promise((r) => (resolve = r)),
  ) as FinanceApi["send"];
  const host = createFinanceHostFixture();
  const view = renderFinance(<ShopsPage />, {
    api: apiFixture(source, { send }),
    host: host.host,
  });
  fireEvent.click(
    await view.findByRole("button", { name: "生成 Shopee 广告授权链接" }),
  );
  fireEvent.click(overlay(view).getByRole("button", { name: "生成授权链接" }));
  act(() => host.update({ ...defaultSession, canManage: false }));
  await act(async () =>
    resolve({ authorizationUrl: "https://open.shopee.com/auth" }),
  );
  expect(overlay(view).queryByLabelText("Shopee 官方授权链接")).toBeNull();
  await act(async () => host.update(defaultSession));
  expect(overlay(view).queryByText("请由对应店铺管理员在 Shopee 官方页面完成授权")).toBeNull();
});

function adFixture(item: Row) {
  return createFinanceApiFixture({
    get: vi.fn(async (path: string) =>
      path === "/shops"
        ? { items: shops, groups: [] }
        : path === "/advertising/spend"
          ? {
              items: [item],
              total: 1,
              totals: [{ currency: "PHP", amount: 25 }],
              shopeeAds: source,
            }
          : path === "/advertising/spend/official-tiktok"
            ? { ...item, fingerprint: "f1", allocations: [] }
            : path === "/overview"
              ? {}
              : [],
    ) as FinanceApi["get"],
  });
}
it("official Shopee daily rows show evidence without requesting an unsupported source-detail API", async () => {
  const api = adFixture({
    source: "API_SHOPEE_CPC",
    source_key: "official-shopee",
    shop_id: "s1",
    assignment_status: "OFFICIAL",
    amount: 25,
    currency: "PHP",
    account_id: "9001",
  });
  const view = renderFinance(<AdvertisingPage />, { api });
  expect(await view.findByText("Shopee 店铺 CPC 日报")).toBeVisible();
  expect(view.getByText("官方 CPC expense；GMS 覆盖关系待核实")).toBeVisible();
  expect(view.queryByRole("button", { name: "查看 / 核实分配" })).toBeNull();
  expect(view.queryByRole("button", { name: "来源详情" })).toBeNull();
  expect(
    vi
      .mocked(api.get)
      .mock.calls.some(
        ([path]) => path === "/advertising/spend/official-shopee",
      ),
  ).toBe(false);
});

it("an officially assigned TikTok row permits evidence viewing but never cross-shop allocation", async () => {
  const api = adFixture({
    source: "API_BASIC",
    source_key: "official-tiktok",
    shop_id: "s1",
    assignment_status: "OFFICIAL",
    amount: 25,
    currency: "PHP",
    platform: "TIKTOK_SHOP",
  });
  const view = renderFinance(<AdvertisingPage />, { api });
  fireEvent.click(await view.findByRole("button", { name: "来源详情" }));
  const d = overlay(view);
  await waitFor(() =>
    expect(
      vi
        .mocked(api.get)
        .mock.calls.some(
          ([path]) => path === "/advertising/spend/official-tiktok",
        ),
    ).toBe(true),
  );
  expect(d.queryByRole("button", { name: "保存核实金额" })).toBeNull();
  expect(d.queryByRole("button", { name: "添加店铺分配" })).toBeNull();
});
