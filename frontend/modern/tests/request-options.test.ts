import { expect, it, vi } from "vitest";
import { createFinanceApi } from "../src/shared/api";
import { createFinanceHostFixture } from "./fixtures";
it("transmits idempotency headers and caller cancellation through JSON and binary paths", async () => {
  const f = vi.fn<typeof fetch>(
    async () =>
      new Response(JSON.stringify({ data: { id: "j" }, success: true })),
  );
  const save = vi.fn();
  const api = createFinanceApi(createFinanceHostFixture().host, {
    fetch: f,
    saveBlob: save,
  });
  const signal = new AbortController().signal;
  await api.send(
    "/fee-exports",
    "POST",
    { start: "2026-10-01" },
    { headers: { "Idempotency-Key": "test-key-123456789" }, signal },
  );
  expect(
    new Headers(f.mock.calls[0]?.[1]?.headers).get("Idempotency-Key"),
  ).toBe("test-key-123456789");
  expect(f.mock.calls[0]?.[1]?.signal).toBe(signal);
  await api.download("/export", {}, "report.xlsx", { signal });
  expect(f.mock.calls[1]?.[1]?.signal).toBe(signal);
});
it("aborts timed out exports and never requests a browser save", async () => {
  const save = vi.fn();
  const f = vi.fn<typeof fetch>(
    async (_url, init) =>
      new Promise((_resolve, reject) =>
        init?.signal?.addEventListener("abort", () =>
          reject(new DOMException("Timeout", "AbortError")),
        ),
      ),
  );
  const api = createFinanceApi(createFinanceHostFixture().host, {
    fetch: f,
    saveBlob: save,
  });
  await expect(
    api.download("/export", {}, undefined, { timeoutMs: 5 }),
  ).rejects.toMatchObject({ name: "AbortError" });
  expect(save).not.toHaveBeenCalled();
});
