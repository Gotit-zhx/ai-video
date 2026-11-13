import { act, fireEvent, waitFor, within } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { ShopsPage } from "../src/features/finance/shops/ShopsPage";
import { AuthorizationModal } from "../src/features/finance/shops/AuthorizationModal";
import { QianyiSettingsModal } from "../src/features/finance/shops/QianyiSettingsModal";
import { FinanceApplication } from "../src/features/finance/FinanceApplication";
import {
  createFinanceApiFixture,
  createFinanceHostFixture,
  defaultSession,
  renderFinance,
} from "./fixtures";
const shop = {
  id: "s1",
  name: "Official shop",
  display_name: "Exact Shop",
  platform: "SHOPEE",
  group_id: "g1",
  enabled: true,
  currency: "USD",
  timezone: "Asia/Shanghai",
};
const groups = [{ id: "g1", name: "Group", shop_count: 1 }];
const config = {
  configured: true,
  hasSecret: true,
  app_id: "existing",
  environment: "CN",
  sync_start: "2026-10-01",
  enabled: false,
  status: "PAUSED",
  shop_count: 1,
  bound_count: 0,
};
function fixture(
  send: (...args: never[]) => Promise<unknown> = vi.fn(async () => "done"),
) {
  return createFinanceApiFixture({
    get: vi.fn(async (path: string) =>
      path === "/shops"
        ? { items: [shop], groups, sync: [], deletions: [] }
        : path === "/groups"
          ? groups
          : path === "/ads/application"
            ? { configured: true }
            : path === "/qianyi"
              ? config
              : [],
    ) as never,
    send: send as never,
  });
}
function overlay(result: ReturnType<typeof renderFinance>) {
  return within(result.surface.overlayContainer);
}
describe("modern shops operations", () => {
  it("readers retain details and filters while all management commands are absent", async () => {
    const api = fixture();
    const host = createFinanceHostFixture({
      ...defaultSession,
      canManage: false,
    });
    const r = renderFinance(<ShopsPage />, { api, host: host.host });
    await r.findByText("Exact Shop");
    expect(r.queryByRole("button", { name: /^配\s*置$/ })).toBeNull();
    expect(r.queryByRole("button", { name: "连接店铺" })).toBeNull();
    expect(r.getByRole("button", { name: "同步详情" })).toBeEnabled();
    expect(r.getByLabelText("查询店铺")).toBeEnabled();
    expect(api.send).not.toHaveBeenCalled();
  });
  it("requires exact untrimmed shop name and prevents duplicate deletion submissions", async () => {
    let resolve!: (v: string) => void;
    const send = vi.fn(() => new Promise<string>((r) => (resolve = r)));
    const r = renderFinance(<ShopsPage />, { api: fixture(send) });
    fireEvent.click(await r.findByRole("button", { name: /移\s*除/ }));
    const d = overlay(r);
    expect(d.getByText(/此操作不可恢复/)).toBeVisible();
    const input = d.getByLabelText("输入店铺名称确认删除");
    fireEvent.change(input, { target: { value: " Exact Shop" } });
    expect(d.getByRole("button", { name: /永久删除/ })).toBeDisabled();
    fireEvent.change(input, { target: { value: "Exact Shop" } });
    fireEvent.click(d.getByRole("button", { name: /永久删除/ }));
    fireEvent.click(d.getByRole("button", { name: /永久删除/ }));
    expect(send).toHaveBeenCalledExactlyOnceWith(
      "/shops/s1",
      "DELETE",
      undefined,
      undefined,
    );
    await act(async () => resolve("已停止同步"));
  });
  it("loads the default shops route lazily", async () => {
    const r = renderFinance(<FinanceApplication />, {
      api: fixture(),
      route: "shops",
    });
    expect(await r.findByRole("button", { name: "连接店铺" })).toBeVisible();
  });
  it("does not generate advertising authorization on mount and preserves update identity", async () => {
    const send = vi.fn(async () => ({
      authorizationUrl: "https://example.com/auth",
      expiresAt: "2026-11-12T12:00:00Z",
    }));
    const r = renderFinance(
      <AuthorizationModal
        kind="ad"
        account={{ advertiser_id: "a1", shop_id: "s1", gmv_store_id: "store1" }}
        onClose={() => {}}
      />,
      { api: fixture(send) },
    );
    const d = overlay(r);
    await waitFor(() =>
      expect(d.getByRole("button", { name: "生成授权链接" })).toBeEnabled(),
    );
    expect(send).not.toHaveBeenCalled();
    fireEvent.click(d.getByRole("button", { name: "生成授权链接" }));
    expect(await d.findByLabelText("官方授权链接")).toHaveValue(
      "https://example.com/auth",
    );
    expect(send).toHaveBeenCalledExactlyOnceWith(
      "/ads/authorize",
      "POST",
      { advertiserId: "a1", shopId: "s1", gmvStoreId: "store1" },
      undefined,
    );
  });
  it("rejects stale authorization results after options change", async () => {
    let resolve!: (v: unknown) => void;
    const send = vi.fn(() => new Promise((r) => (resolve = r)));
    const r = renderFinance(
      <AuthorizationModal kind="ad" onClose={() => {}} />,
      { api: fixture(send) },
    );
    const d = overlay(r);
    await waitFor(() =>
      expect(d.getByRole("button", { name: "生成授权链接" })).toBeEnabled(),
    );
    fireEvent.change(d.getByLabelText("广告账户 ID"), {
      target: { value: "one" },
    });
    fireEvent.click(d.getByRole("button", { name: "生成授权链接" }));
    fireEvent.change(d.getByLabelText("广告账户 ID"), {
      target: { value: "two" },
    });
    await act(async () =>
      resolve({ authorizationUrl: "https://example.com/stale" }),
    );
    expect(d.queryByLabelText("官方授权链接")).toBeNull();
    expect(send).toHaveBeenCalledTimes(1);
  });
  it("rejects insecure links and never retries failed authorization", async () => {
    const send = vi.fn(async () => ({
      authorizationUrl: "http://example.com/auth",
    }));
    const r = renderFinance(
      <AuthorizationModal kind="ad" onClose={() => {}} />,
      { api: fixture(send) },
    );
    const d = overlay(r);
    await waitFor(() =>
      expect(d.getByRole("button", { name: "生成授权链接" })).toBeEnabled(),
    );
    fireEvent.click(d.getByRole("button", { name: "生成授权链接" }));
    expect(await d.findByText("平台授权地址无效")).toBeVisible();
    expect(d.queryByLabelText("官方授权链接")).toBeNull();
    expect(send).toHaveBeenCalledTimes(1);
  });
  it("keeps Qianyi secret blank only for unchanged identity and preserves pause", async () => {
    const send = vi.fn(async () => "saved");
    const r = renderFinance(<QianyiSettingsModal open onClose={() => {}} />, {
      api: fixture(send),
    });
    const d = overlay(r);
    await d.findByLabelText("千易 App ID");
    fireEvent.click(d.getByRole("button", { name: "保存全局配置" }));
    await waitFor(() =>
      expect(send).toHaveBeenCalledWith(
        "/qianyi",
        "PUT",
        {
          environment: "CN",
          appId: "existing",
          appSecret: "",
          syncStart: "2026-10-01",
          enabled: false,
        },
        undefined,
      ),
    );
    fireEvent.change(d.getByLabelText("千易 App ID"), {
      target: { value: "changed" },
    });
    fireEvent.click(d.getByRole("button", { name: "保存全局配置" }));
    expect(
      await d.findByText("更换环境或 App ID 必须重新输入 Secret"),
    ).toBeVisible();
    expect(send).toHaveBeenCalledTimes(1);
  });
  it("never fetches privileged Qianyi settings for a reader", () => {
    const api = fixture();
    const host = createFinanceHostFixture({
      ...defaultSession,
      canManage: false,
    });
    renderFinance(<QianyiSettingsModal open onClose={() => {}} />, {
      api,
      host: host.host,
    });
    expect(api.get).not.toHaveBeenCalled();
  });
});

describe("shops complete operation coverage", () => {
  it("saves the original shop contract with paused state and normalized currency", async () => {
    const api = fixture();
    const r = renderFinance(<ShopsPage />, { api });
    fireEvent.click(await r.findByRole("button", { name: /^配\s*置$/ }));
    const d = overlay(r);
    fireEvent.change(d.getByLabelText("店铺别名"), {
      target: { value: " Alias " },
    });
    fireEvent.change(d.getByLabelText("店铺结算币种"), {
      target: { value: " php " },
    });
    fireEvent.click(d.getByRole("switch", { name: "自动同步" }));
    fireEvent.click(d.getByRole("button", { name: "保存配置" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/shops/s1",
        "PUT",
        {
          groupId: "g1",
          currency: "PHP",
          timezone: "Asia/Shanghai",
          enabled: false,
          alias: "Alias",
        },
        undefined,
      ),
    );
  });
  it("reads group count, blocks nonempty deletion, and creates and renames groups", async () => {
    const api = fixture();
    const r = renderFinance(<ShopsPage />, { api });
    fireEvent.click(r.getByRole("button", { name: "分组管理" }));
    const d = overlay(r);
    await d.findByLabelText("分组名称 Group");
    expect(d.getByRole("button", { name: /删\s*除/ })).toBeDisabled();
    fireEvent.change(d.getByLabelText("新分组名称"), {
      target: { value: " New group " },
    });
    fireEvent.click(d.getByRole("button", { name: "添加分组" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/groups",
        "POST",
        { name: "New group" },
        undefined,
      ),
    );
    await waitFor(() =>
      expect(d.getByRole("button", { name: /保\s*存/ })).not.toHaveClass(
        "ant-btn-loading",
      ),
    );
    fireEvent.change(d.getByLabelText("分组名称 Group"), {
      target: { value: "Renamed" },
    });
    fireEvent.click(d.getByRole("button", { name: /保\s*存/ }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/groups/g1",
        "PUT",
        { name: "Renamed" },
        undefined,
      ),
    );
  });
  it("does not show unknown previous month as complete and preserves scheduler evidence", async () => {
    const api = fixture();
    const base = api.get;
    api.get = vi.fn(async (path: string, ...args: never[]) =>
      path === "/shops"
        ? {
            items: [shop],
            groups,
            sync: [
              {
                shop_id: "s1",
                channel: "ORDER",
                status: "SYNCING",
                execution_state: "RATE_LIMITED",
                previous_month: "2026-10",
                previous_month_checked: true,
                previous_month_complete: true,
                initial_sync_complete: false,
                platform_retry_at: "2026-11-12T01:00:00Z",
                error_message: "limited",
                pending_date_bills: 0,
              },
            ],
            deletions: [
              {
                shop_id: "gone",
                shop_label: "Deleted shop",
                status: "RETRY",
                deleted_rows: 0,
                requested_at: "2026-11-12T00:00:00Z",
              },
            ],
          }
        : base(path, ...args),
    ) as never;
    const r = renderFinance(<ShopsPage />, { api });
    await r.findByText("Exact Shop");
    expect(r.getAllByText(/上月抓取未完成/).length).toBeGreaterThan(0);
    expect(r.getByText("正在重试，进度已保留")).toBeVisible();
    fireEvent.click(r.getByRole("button", { name: "同步详情" }));
    const d = overlay(r);
    expect(d.getByText("平台限流等待")).toBeVisible();
    expect(d.getByText("limited")).toBeVisible();
    expect(d.getByText(/结算的上月明细尚未核验完成/)).toBeVisible();
  });
  it("priority command preserves progress and a failed command is not retried", async () => {
    const send = vi.fn(async () => {
      throw new Error("平台等待，请稍后检查同步状态");
    });
    const r = renderFinance(<ShopsPage />, { api: fixture(send) });
    fireEvent.click(await r.findByRole("button", { name: "立即同步" }));
    expect(await r.findByText("平台等待，请稍后检查同步状态")).toBeVisible();
    expect(send).toHaveBeenCalledExactlyOnceWith(
      "/shops/s1/sync",
      "POST",
      undefined,
      undefined,
    );
  });
  it("keeps company accounts separate from hidden scope and resumes only by explicit click", async () => {
    const api = fixture();
    const base = api.get;
    api.get = vi.fn(async (path: string, ...args: never[]) =>
      path === "/ads"
        ? [
            {
              advertiser_id: "a1",
              name: "Visible ad",
              shop_id: "s1",
              enabled: false,
              currency: "USD",
              status: "PAUSED",
            },
            {
              advertiser_id: "a2",
              name: "Unassigned ad",
              enabled: true,
              currency: "USD",
              status: "READY",
            },
          ]
        : base(path, ...args),
    ) as never;
    const r = renderFinance(<ShopsPage />, { api, filters: { groupId: "g1" } });
    await r.findByRole("button", { name: "查看全部广告账户" });
    expect(r.queryByText(/Unassigned ad/)).toBeNull();
    expect(api.send).not.toHaveBeenCalled();
    fireEvent.click(r.getByRole("button", { name: /启\s*用/ }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/ads/a1",
        "PUT",
        { enabled: true },
        undefined,
      ),
    );
    fireEvent.click(r.getByRole("button", { name: "查看全部广告账户" }));
    expect(await r.findByText("Unassigned ad")).toBeVisible();
  });
  it("shop authorization requires group, preserves shopId and chosen market", async () => {
    const send = vi.fn(async () => ({
      authorizationUrl: "https://example.com/shop",
    }));
    const r = renderFinance(
      <AuthorizationModal kind="shop" shop={shop} onClose={() => {}} />,
      { api: fixture(send) },
    );
    const d = overlay(r);
    await d.findByText("请选择分组");
    expect(d.getByRole("button", { name: "生成授权链接" })).toBeDisabled();
    fireEvent.mouseDown(d.getByRole("combobox", { name: "店铺分组（必选）" }));
    fireEvent.click(await d.findByText("Group"));
    fireEvent.mouseDown(d.getByRole("combobox", { name: "授权市场" }));
    fireEvent.click(await d.findByText("美国"));
    fireEvent.click(d.getByRole("button", { name: "生成授权链接" }));
    expect(await d.findByLabelText("官方授权链接")).toHaveValue(
      "https://example.com/shop",
    );
    expect(send).toHaveBeenCalledWith(
      "/shops/authorize",
      "POST",
      { platform: "SHOPEE", market: "US", groupId: "g1", shopId: "s1" },
      undefined,
    );
  });
  it("discard pending authorization after owner changes and after deactivation", async () => {
    let resolve!: (v: unknown) => void;
    const send = vi.fn(() => new Promise((r) => (resolve = r)));
    const host = createFinanceHostFixture();
    const r = renderFinance(
      <AuthorizationModal kind="ad" onClose={() => {}} />,
      { api: fixture(send), host: host.host },
    );
    let d = overlay(r);
    await waitFor(() =>
      expect(d.getByRole("button", { name: "生成授权链接" })).toBeEnabled(),
    );
    fireEvent.click(d.getByRole("button", { name: "生成授权链接" }));
    act(() => host.update({ ...defaultSession, organizationId: "other" }));
    await act(async () =>
      resolve({ authorizationUrl: "https://example.com/old-company" }),
    );
    expect(d.queryByLabelText("官方授权链接")).toBeNull();
    r.setActive(false);
    expect(d.queryByRole("dialog")).toBeNull();
    expect(send).toHaveBeenCalledTimes(1);
  });
  it("does not create links when the server application is unconfigured", async () => {
    const api = fixture();
    api.get = vi.fn(async () => ({ configured: false })) as never;
    const r = renderFinance(
      <AuthorizationModal kind="ad" onClose={() => {}} />,
      { api },
    );
    const d = overlay(r);
    expect(
      await d.findByText("广告授权暂未准备好，请联系管理员完成服务端配置。"),
    ).toBeVisible();
    expect(d.getByRole("button", { name: "生成授权链接" })).toBeDisabled();
    expect(api.send).not.toHaveBeenCalled();
  });
  it("rejects future Qianyi dates and clears secrets upon close", async () => {
    const api = fixture();
    const r = renderFinance(<QianyiSettingsModal open onClose={() => {}} />, {
      api,
    });
    const d = overlay(r);
    await d.findByLabelText("千易 App ID");
    fireEvent.change(d.getByLabelText("历史同步开始日期"), {
      target: { value: "2999-01-01" },
    });
    fireEvent.change(d.getByLabelText("千易 App Secret"), {
      target: { value: "sensitive-test-value" },
    });
    fireEvent.click(d.getByRole("button", { name: "保存全局配置" }));
    expect(
      await d.findByText("历史同步开始日期必须在最近五年内且不晚于今天"),
    ).toBeVisible();
    expect(api.send).not.toHaveBeenCalled();
    r.rerender(<QianyiSettingsModal open={false} onClose={() => {}} />);
    expect(d.queryByLabelText("千易 App Secret")).toBeNull();
    r.rerender(<QianyiSettingsModal open onClose={() => {}} />);
    expect(await d.findByLabelText("千易 App Secret")).toHaveValue("");
  });
});

it("an accepted shop save closes its own dialog before refresh and cannot close a subsequent dialog", async () => {
  const api = fixture();
  const originalGet = api.get;
  let holdReads = false;
  let release!: (value: unknown) => void;
  api.get = vi.fn(async (path: string, ...args: never[]) =>
    path === "/shops" && holdReads
      ? new Promise((resolve) => {
          release = resolve;
        })
      : originalGet(path, ...args),
  ) as never;
  const r = renderFinance(<ShopsPage />, { api });
  fireEvent.click(await r.findByRole("button", { name: /^配\s*置$/ }));
  const d = overlay(r);
  holdReads = true;
  fireEvent.click(d.getByRole("button", { name: "保存配置" }));
  await waitFor(() => expect(api.send).toHaveBeenCalledTimes(1));
  await waitFor(() => expect(d.queryByLabelText("店铺别名")).toBeNull());
  fireEvent.click(r.getByRole("button", { name: /移\s*除/ }));
  expect(d.getByLabelText("输入店铺名称确认删除")).toBeVisible();
  await act(async () =>
    release({ items: [shop], groups, sync: [], deletions: [] }),
  );
  expect(d.getByLabelText("输入店铺名称确认删除")).toBeVisible();
});

it("keeps a failed advertising read unknown instead of declaring no connected accounts", async () => {
  const api = fixture();
  const originalGet = api.get;
  api.get = vi.fn(async (path: string, ...args: never[]) => {
    if (path === "/ads") throw new Error("广告状态读取失败");
    return originalGet(path, ...args);
  }) as never;
  const r = renderFinance(<ShopsPage />, { api });
  expect(await r.findByText("广告状态读取失败")).toBeVisible();
  expect(
    r.queryByText(
      "当前范围没有已连接广告账户；账单和已导入费用仍按实际记录展示。",
    ),
  ).toBeNull();
});

it("polls unfinished deletion every three seconds, stops when hidden, and unmounts on inactivity", async () => {
  const api = fixture();
  const originalGet = api.get;
  api.get = vi.fn(async (path: string, ...args: never[]) =>
    path === "/shops"
      ? {
          items: [shop],
          groups,
          sync: [],
          deletions: [{ shop_id: "gone", status: "RUNNING" }],
        }
      : originalGet(path, ...args),
  ) as never;
  vi.useFakeTimers();
  const r = renderFinance(<ShopsPage />, { api });
  await act(async () => {
    await vi.advanceTimersByTimeAsync(50);
  });
  expect(r.getByText("Exact Shop")).toBeVisible();
  const count = () =>
    vi.mocked(api.get).mock.calls.filter((call) => call[0] === "/shops").length;
  const before = count();
  try {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(3001);
    });
    expect(count()).toBeGreaterThan(before);
    Object.defineProperty(document, "hidden", {
      configurable: true,
      value: true,
    });
    act(() => document.dispatchEvent(new Event("visibilitychange")));
    const hidden = count();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(16000);
    });
    expect(count()).toBe(hidden);
    Object.defineProperty(document, "hidden", {
      configurable: true,
      value: false,
    });
    act(() => document.dispatchEvent(new Event("visibilitychange")));
    r.setActive(false);
    const inactive = count();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(16000);
    });
    expect(count()).toBe(inactive);
  } finally {
    Object.defineProperty(document, "hidden", {
      configurable: true,
      value: false,
    });
    vi.useRealTimers();
  }
});

describe("Qianyi fresh editing sessions", () => {
  it("requires a fresh held read on quick reopen and submits B pause/start rather than cached A", async () => {
    const a = {
      ...config,
      enabled: true,
      status: "READY",
      sync_start: "2026-08-01",
    };
    const b = {
      ...config,
      enabled: false,
      status: "PAUSED",
      sync_start: "2026-10-15",
    };
    let reads = 0;
    let release!: (value: unknown) => void;
    const api = fixture();
    api.get = vi.fn(async (path: string) =>
      path === "/qianyi"
        ? ++reads === 1
          ? a
          : new Promise((resolve) => {
              release = resolve;
            })
        : [],
    ) as never;
    const r = renderFinance(<QianyiSettingsModal open onClose={() => {}} />, {
      api,
    });
    const d = overlay(r);
    expect(await d.findByLabelText("历史同步开始日期")).toHaveValue(
      a.sync_start,
    );
    expect(d.getByRole("switch", { name: "千易自动同步" })).toBeChecked();
    r.rerender(<QianyiSettingsModal open={false} onClose={() => {}} />);
    r.rerender(<QianyiSettingsModal open onClose={() => {}} />);
    await waitFor(() => expect(reads).toBe(2));
    expect(d.queryByLabelText("千易 App ID")).toBeNull();
    expect(d.queryByRole("button", { name: "保存全局配置" })).toBeNull();
    expect(api.send).not.toHaveBeenCalled();
    await act(async () => release(b));
    expect(await d.findByLabelText("历史同步开始日期")).toHaveValue(
      b.sync_start,
    );
    expect(d.getByRole("switch", { name: "千易自动同步" })).not.toBeChecked();
    fireEvent.click(d.getByRole("button", { name: "保存全局配置" }));
    await waitFor(() =>
      expect(api.send).toHaveBeenCalledWith(
        "/qianyi",
        "PUT",
        {
          environment: "CN",
          appId: "existing",
          appSecret: "",
          syncStart: b.sync_start,
          enabled: false,
        },
        undefined,
      ),
    );
  });
  it("refreshes paused settings and current association page read-only while preserving draft edits", async () => {
    let server = { ...config };
    const calls: Array<{ path: string; params: unknown }> = [];
    const api = fixture();
    api.get = vi.fn(async (path: string, params: unknown) => {
      calls.push({ path, params });
      return path === "/qianyi"
        ? server
        : path === "/qianyi/shops"
          ? JSON.stringify(params).includes("2")
            ? [{ id: "page2", name: "Page two shop", status: server.status }]
            : Array.from({ length: 50 }, (_, i) => ({
                id: String(i),
                name: "Shop " + i,
              }))
          : [];
    }) as never;
    const r = renderFinance(<QianyiSettingsModal open onClose={() => {}} />, {
      api,
    });
    const d = overlay(r);
    await d.findByLabelText("千易 App ID");
    await waitFor(() =>
      expect(d.getByRole("button", { name: "下一页" })).toBeEnabled(),
    );
    fireEvent.click(d.getByRole("button", { name: "下一页" }));
    await d.findByText("Page two shop");
    fireEvent.change(d.getByLabelText("历史同步开始日期"), {
      target: { value: "2026-09-11" },
    });
    fireEvent.change(d.getByLabelText("千易 App ID"), {
      target: { value: "draft-app" },
    });
    server = { ...server, status: "ERROR", sync_start: "2026-11-01" };
    const before = calls.length;
    fireEvent.click(d.getByRole("button", { name: "刷新关联状态" }));
    await d.findByText("ERROR");
    expect(calls.slice(before)).toEqual(
      expect.arrayContaining([
        { path: "/qianyi", params: {} },
        { path: "/qianyi/shops", params: { page: 2 } },
      ]),
    );
    expect(d.getByLabelText("千易 App ID")).toHaveValue("draft-app");
    expect(d.getByLabelText("历史同步开始日期")).toHaveValue("2026-09-11");
    expect(api.send).not.toHaveBeenCalled();
  });
  it("recovers an initial settings read failure with explicit refresh and no mutation", async () => {
    let fail = true;
    const api = fixture();
    api.get = vi.fn(async (path: string) => {
      if (path === "/qianyi") {
        if (fail) throw new Error("公司配置读取失败");
        return config;
      }
      return [];
    }) as never;
    const r = renderFinance(<QianyiSettingsModal open onClose={() => {}} />, {
      api,
    });
    const d = overlay(r);
    await d.findByText("公司配置读取失败");
    expect(d.queryByRole("button", { name: "保存全局配置" })).toBeNull();
    fail = false;
    fireEvent.click(d.getByRole("button", { name: "刷新关联状态" }));
    expect(await d.findByLabelText("千易 App ID")).toHaveValue("existing");
    expect(api.send).not.toHaveBeenCalled();
  });
});

it("keeps intentional Qianyi draft edits through later enabled status polls", async () => {
  let server = { ...config, enabled: true, status: "READY" };
  const api = fixture();
  api.get = vi.fn(async (path: string) =>
    path === "/qianyi" ? server : [],
  ) as never;
  vi.useFakeTimers();
  const r = renderFinance(<QianyiSettingsModal open onClose={() => {}} />, {
    api,
  });
  const d = overlay(r);
  try {
    await act(async () => {
      await vi.advanceTimersByTimeAsync(50);
    });
    fireEvent.change(d.getByLabelText("千易 App ID"), {
      target: { value: "my-draft" },
    });
    fireEvent.change(d.getByLabelText("历史同步开始日期"), {
      target: { value: "2026-09-11" },
    });
    fireEvent.click(d.getByRole("switch", { name: "千易自动同步" }));
    server = { ...server, status: "SYNCING", sync_start: "2026-11-01" };
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10001);
    });
    expect(
      vi.mocked(api.get).mock.calls.filter((call) => call[0] === "/qianyi")
        .length,
    ).toBe(2);
    expect(d.getByText(/状态.*SYNCING/)).toBeVisible();
    expect(d.getByLabelText("千易 App ID")).toHaveValue("my-draft");
    expect(d.getByLabelText("历史同步开始日期")).toHaveValue("2026-09-11");
    expect(d.getByRole("switch", { name: "千易自动同步" })).not.toBeChecked();
    expect(api.send).not.toHaveBeenCalled();
  } finally {
    r.unmount();
    vi.useRealTimers();
  }
});

it("recovers paused association read failures with the explicit read-only refresh", async () => {
  let fail = true;
  const api = fixture();
  api.get = vi.fn(async (path: string) => {
    if (path === "/qianyi") return config;
    if (path === "/qianyi/shops") {
      if (fail) throw new Error("关联状态读取失败");
      return [{ id: "recovered", name: "Recovered association" }];
    }
    return [];
  }) as never;
  const r = renderFinance(<QianyiSettingsModal open onClose={() => {}} />, {
    api,
  });
  const d = overlay(r);
  await d.findByText("关联状态读取失败");
  fail = false;
  fireEvent.click(d.getByRole("button", { name: "刷新关联状态" }));
  expect(await d.findByText("Recovered association")).toBeVisible();
  expect(d.getByRole("switch", { name: "千易自动同步" })).not.toBeChecked();
  expect(api.send).not.toHaveBeenCalled();
});
