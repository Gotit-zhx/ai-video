import { describe, expect, it, vi } from "vitest";
import { fireEvent } from "@testing-library/react";
import type { FinanceApi } from "../src/shared/contracts";
import {
  FinanceInactiveError,
  useFinance,
  useFinanceQuery,
} from "../src/features/finance/context";
import { FinanceApplication } from "../src/features/finance/FinanceApplication";
import {
  createFinanceApiFixture,
  createFinanceHostFixture,
  renderFinance,
} from "./fixtures";

describe("host integration in finance workspace", () => {
  it("returns through the host with current selection and displays the one-time authorization result", () => {
    const fixture = createFinanceHostFixture();
    const getLegacyUrl = vi.fn(
      () => "/app?financeUi=legacy&shopId=shop-a#finance-overview",
    );
    const consumeAuthorizationNotice = vi.fn(() => ({
      status: "error" as const,
      message: "未取得可用广告账户",
    }));
    const host = { ...fixture.host, getLegacyUrl, consumeAuthorizationNotice };
    const view = renderFinance(<FinanceApplication />, {
      host,
      filters: { shopId: "shop-a", start: "2026-10-01", end: "2026-10-31" },
    });
    expect(view.getByRole("link", { name: "返回旧版" })).toHaveAttribute(
      "href",
      "/app?financeUi=legacy&shopId=shop-a#finance-overview",
    );
    expect(getLegacyUrl).toHaveBeenCalledWith(
      expect.objectContaining({
        shopId: "shop-a",
        start: "2026-10-01",
        end: "2026-10-31",
      }),
    );
    expect(view.getByText("未取得可用广告账户")).toBeVisible();
    expect(consumeAuthorizationNotice).toHaveBeenCalledOnce();
  });
});

it("return navigation synchronously cancels reads and fences late lazy requests, while modified clicks keep this tab active", async () => {
  let exposed: FinanceApi | undefined;
  const get = vi.fn<FinanceApi["get"]>(
    (_path, _params, signal) =>
      new Promise((_, reject) => {
        signal?.addEventListener(
          "abort",
          () => reject(new DOMException("cancelled", "AbortError")),
          { once: true },
        );
      }),
  );
  function PendingPage() {
    const { api } = useFinance();
    exposed = api;
    useFinanceQuery({
      queryKey: ["navigation-pending"],
      queryFn: ({ signal }) => api.get("/navigation-pending", {}, signal),
    });
    return <span>Pending finance page</span>;
  }
  const v = renderFinance(
    <FinanceApplication pages={{ shops: PendingPage }} />,
    {
      route: "shops",
      api: createFinanceApiFixture({ get: get as FinanceApi["get"] }),
    },
  );
  await v.findByText("Pending finance page");
  const signal = get.mock.calls.find(
    ([path]) => path === "/navigation-pending",
  )?.[2];
  expect(signal).toBeInstanceOf(AbortSignal);
  // Stop jsdom's native navigation after the React handler has received the click.
  v.surface.shadowRoot.addEventListener("click", (event) =>
    event.preventDefault(),
  );
  const link = v.getByRole("link", { name: "返回旧版" });
  for (const modifier of ["ctrlKey", "metaKey", "shiftKey", "altKey"]) {
    fireEvent.click(link, { [modifier]: true });
    expect(signal?.aborted).toBe(false);
  }
  fireEvent.click(link);
  expect(signal?.aborted).toBe(true);
  await expect(exposed!.get("/late-lazy-read")).rejects.toBeInstanceOf(
    FinanceInactiveError,
  );
  expect(get.mock.calls.some(([path]) => path === "/late-lazy-read")).toBe(
    false,
  );
});

it("pagehide cancels active reads and a persisted pageshow restores the same owner session", async () => {
  let currentApi: FinanceApi | undefined;
  const get = vi.fn((path: string, _params: unknown, signal?: AbortSignal) =>
    path === "/waiting-page-read"
      ? new Promise((_, reject) =>
          signal?.addEventListener(
            "abort",
            () => reject(new DOMException("cancelled", "AbortError")),
            { once: true },
          ),
        )
      : Promise.resolve({}),
  );
  function Page() {
    const { api, active, session } = useFinance();
    currentApi = api;
    useFinanceQuery({
      queryKey: ["waiting-page-read"],
      queryFn: ({ signal }) => api.get("/waiting-page-read", {}, signal),
    });
    return (
      <span>
        {session.userId}:{active ? "active" : "inactive"}
      </span>
    );
  }
  const v = renderFinance(<Page />, {
    api: createFinanceApiFixture({ get: get as FinanceApi["get"] }),
  });
  await v.findByText("user-1:active");
  const before = get.mock.calls.find(
    ([path]) => path === "/waiting-page-read",
  )?.[2];
  fireEvent(window, new Event("pagehide"));
  await v.findByText("user-1:inactive");
  expect(before?.aborted).toBe(true);
  await expect(currentApi!.get("/after-hide")).rejects.toBeInstanceOf(
    FinanceInactiveError,
  );
  const restored = new Event("pageshow");
  Object.defineProperty(restored, "persisted", { value: true });
  fireEvent(window, restored);
  await v.findByText("user-1:active");
  await expect(currentApi!.get("/after-restore")).resolves.toEqual({});
});
