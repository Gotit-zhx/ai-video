import { expect, it, vi } from "vitest";
import { FinanceApiError, FinanceScopeChangedError } from "../src/shared/api";
import type { FinanceApi } from "../src/shared/contracts";
import {
  createFinanceReads,
  completeCostWorkspace,
} from "../src/features/finance/reads";
import { createFinanceApiFixture } from "./fixtures";

function reader(get: FinanceApi["get"], current = () => true) {
  return createFinanceReads({ ...createFinanceApiFixture(), get }, current);
}
it.each([404, 405])(
  "caches only optional HTTP %i across page reads and independent endpoint capabilities",
  async (status) => {
    const get = vi.fn(async (path: string) => {
      if (
        [
          "/workspace/query",
          "/workspace/costs/context",
          "/workspace/status",
        ].includes(path)
      )
        throw new FinanceApiError("missing", status);
      return { items: [], total: 0 };
    }) as unknown as FinanceApi["get"];
    const reads = reader(get);
    await reads.records({ view: "orders", kind: "ORDER", orderScope: "ALL" });
    await reads.advertising({ spendPage: 2 });
    await reads.costContext({ shopId: "", groupId: "" });
    await reads.costContext({ shopId: "other", groupId: "" });
    expect(await reads.status({ view: "orders" })).toBeNull();
    expect(await reads.status({ view: "fees" })).toBeNull();
    const paths = vi.mocked(get).mock.calls.map(([path]) => path);
    for (const path of [
      "/workspace/query",
      "/workspace/costs/context",
      "/workspace/status",
    ])
      expect(paths.filter((p) => p === path)).toHaveLength(1);
    expect(get).toHaveBeenCalledWith(
      "/advertising/spend",
      { page: 2 },
      undefined,
    );
  },
);
it.each([
  401,
  403,
  500,
  new TypeError("transport"),
  new DOMException("aborted", "AbortError"),
  new FinanceScopeChangedError(),
])("does not cache or fall back for %s", async (failure) => {
  const error =
    typeof failure === "number"
      ? new FinanceApiError("failed", failure)
      : failure;
  const get = vi.fn(async () => {
    throw error;
  }) as FinanceApi["get"];
  const reads = reader(get);
  await expect(
    reads.records({ view: "orders", kind: "ORDER", orderScope: "ALL" }),
  ).rejects.toBe(error);
  await expect(
    reads.records({ view: "orders", kind: "ORDER", orderScope: "ALL" }),
  ).rejects.toBe(error);
  expect(vi.mocked(get).mock.calls.map(([path]) => path)).toEqual([
    "/workspace/query",
    "/workspace/query",
  ]);
});
it("late unsupported response cannot seed capability or run legacy calls after owner changes or abort", async () => {
  let current = true;
  let reject!: (error: unknown) => void;
  const get = vi.fn(
    () =>
      new Promise((_, r) => {
        reject = r;
      }),
  ) as FinanceApi["get"];
  const reads = reader(get, () => current);
  const pending = reads.records({
    view: "orders",
    kind: "ORDER",
    orderScope: "ALL",
  });
  current = false;
  reject(new FinanceApiError("missing", 404));
  await expect(pending).rejects.toBeInstanceOf(FinanceScopeChangedError);
  expect(get).toHaveBeenCalledTimes(1);
  const controller = new AbortController();
  controller.abort();
  await expect(
    reader(get).records({}, controller.signal),
  ).rejects.toMatchObject({ name: "AbortError" });
  expect(get).toHaveBeenCalledTimes(1);
});
it("successful malformed new envelopes fail explicitly instead of claiming legacy support", async () => {
  const get = vi.fn(async () => ({
    items: [],
    total: 0,
  })) as unknown as FinanceApi["get"];
  const reads = reader(get);
  await expect(reads.records({ view: "orders" })).rejects.toThrow("响应不完整");
  await expect(reads.advertising({})).rejects.toThrow("响应不完整");
  await expect(reads.costContext({})).rejects.toThrow("响应不完整");
  expect(
    vi.mocked(get).mock.calls.every(([p]) => p.startsWith("/workspace/")),
  ).toBe(true);
});
it("new context and partial flags never unlock catalogue writes; complete legacy payload remains supported", () => {
  const full = {
    shopId: "s",
    groupId: "g",
    costs: [],
    manualCosts: [],
    products: [],
    settings: {},
    settingsVersion: 0,
    mappings: {},
  };
  expect(
    completeCostWorkspace({
      live: true,
      catalogLoaded: false,
      settingsLoaded: false,
      shopId: "s",
      groupId: "g",
      groupName: "g",
    }),
  ).toBe(false);
  expect(completeCostWorkspace(full)).toBe(true);
  expect(
    completeCostWorkspace({
      ...full,
      catalogLoaded: true,
      settingsLoaded: true,
    }),
  ).toBe(true);
  for (const bad of [
    { settingsVersion: undefined },
    { settings: undefined },
    { products: undefined },
    { catalogLoaded: false },
    { settingsLoaded: false },
    { catalogLoaded: true },
    { settingsVersion: -1 },
  ])
    expect(completeCostWorkspace({ ...full, ...bad })).toBe(false);
});
