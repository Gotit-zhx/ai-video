import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import vm from "node:vm";
const helper = new URL("../browser/helpers.cjs", import.meta.url),
  localRequire = createRequire(helper);
const origin = "http://127.0.0.1:43123",
  api = origin + "/api/v1/finance/fee-exports",
  chunk = origin + "/assets/finance-modern/assets/CostsPage-fixture.js";
function request(url, method = "GET", type = "fetch") {
  return {
    url: () => url,
    method: () => method,
    resourceType: () => type,
    failure: () => ({ errorText: "net::ERR_FAILED" }),
  };
}
async function run(rule, inject) {
  let fixtures;
  const listeners = {};
  const page = {
    on: (event, fn) => (listeners[event] = fn),
    url: () => origin + "/?financeUi=modern#finance-overview",
  };
  const context = {
    route: async () => {},
    on: (event, fn) => {
      if (event === "page") fn(page);
    },
  };
  const fakeExpect = (actual, label) => ({
    toEqual: (expected) => assert.deepEqual(actual, expected, label),
  });
  const sandbox = {
    URL,
    console,
    module: { exports: {} },
    require(name) {
      return name.includes("@playwright/test")
        ? {
            test: {
              extend(value) {
                fixtures = value;
                return value;
              },
            },
            expect: fakeExpect,
          }
        : localRequire(name);
    },
  };
  vm.runInNewContext(readFileSync(helper, "utf8"), sandbox, {
    filename: helper.pathname,
  });
  const consoleError = (message, url = api) =>
    listeners.console({
      type: () => "error",
      text: () => message,
      location: () => ({ url, lineNumber: 0, columnNumber: 0 }),
      args: () => [{ evaluate: async (fn) => fn(message) }],
    });
  const response = (url = api, status = 503, method = "POST") =>
    listeners.response?.({
      url: () => url,
      status: () => status,
      request: () => request(url, method),
      headers: () => ({}),
    });
  let result;
  await fixtures.audit[0](
    { context, fixture: { url: origin } },
    async (audit) => {
      result = audit;
      if (rule === "http")
        audit.injectHttpFailure(
          { method: "POST", path: "/fee-exports" },
          503,
          "fixture result unknown",
        );
      else if (rule === "module")
        audit.expectModuleFailure(request(chunk, "GET", "script"));
      if (rule === "http") response();
      else if (rule === "module")
        listeners.requestfailed?.(request(chunk, "GET", "script"));
      await inject({ listeners, consoleError, response, audit });
    },
  );
  return result;
}
test("one expected HTTP resource error passes", () =>
  run("http", ({ consoleError }) =>
    consoleError(
      "Failed to load resource: the server responded with a status of 503 (Service Unavailable)",
    ),
  ));
test("rejects exact reviewer accounting pageerror canary", () =>
  assert.rejects(
    run("http", ({ listeners }) =>
      listeners.pageerror(
        new Error("unexpected accounting crash for record 503"),
      ),
    ),
    (e) =>
      e.actual.some(
        (event) =>
          event.kind === "pageerror" &&
          event.message === "unexpected accounting crash for record 503",
      ),
  ));
test("rejects exact reviewer unrelated module pageerror canary", () =>
  assert.rejects(
    run("module", ({ listeners }) =>
      listeners.pageerror(new Error("Load failed: unrelated chart worker")),
    ),
    (e) =>
      e.actual.some(
        (event) =>
          event.kind === "pageerror" &&
          event.message === "Load failed: unrelated chart worker",
      ),
  ));
test("rejects an unrelated failed resource during injected module failure", () =>
  assert.rejects(
    run("module", ({ listeners }) =>
      listeners.requestfailed?.(
        request(origin + "/assets/unrelated-worker.js", "GET", "script"),
      ),
    ),
  ));
test("rejects duplicate expected HTTP console errors", () =>
  assert.rejects(
    run("http", ({ consoleError }) => {
      const m =
        "Failed to load resource: the server responded with a status of 503 (Service Unavailable)";
      consoleError(m);
      consoleError(m);
    }),
  ));
test("rejects duplicate expected HTTP responses", () =>
  assert.rejects(run("http", ({ response }) => response())));
test("rejects matching HTTP console text from another resource", () =>
  assert.rejects(
    run("http", ({ consoleError }) =>
      consoleError(
        "Failed to load resource: the server responded with a status of 503 (Service Unavailable)",
        origin + "/api/v1/finance/other",
      ),
    ),
  ));

test("rejects an unregistered ordinary pageerror", () =>
  assert.rejects(
    run(null, ({ listeners }) =>
      listeners.pageerror(new Error("unexpected chart crash")),
    ),
  ));
test("rejects an extra failed HTTP resource", () =>
  assert.rejects(
    run("http", ({ response }) =>
      response(origin + "/api/v1/finance/unrelated", 503),
    ),
  ));
test("rejects duplicate injected module request failures", () =>
  assert.rejects(
    run("module", ({ listeners }) =>
      listeners.requestfailed(request(chunk, "GET", "script")),
    ),
  ));
test("rejects duplicate injected module console errors", () =>
  assert.rejects(
    run("module", ({ consoleError }) => {
      consoleError("Failed to load resource: net::ERR_FAILED", chunk);
      consoleError("Failed to load resource: net::ERR_FAILED", chunk);
    }),
  ));
test("rejects arbitrary console accounting failure even with the matching status resource", () =>
  assert.rejects(
    run("http", ({ consoleError }) =>
      consoleError("unexpected accounting crash for record 503"),
    ),
  ));
test("generic module error requires the loaded React reporting source and one actual failure", () =>
  run("module", ({ audit }) => {
    const source =
      origin + "/assets/finance-modern/assets/presentation-fixture.js";
    audit.record({
      kind: "response",
      url: source,
      status: 200,
      method: "GET",
      resourceType: "script",
    });
    audit.record({
      kind: "console",
      message: "TypeError: Importing a module script failed.",
      exception: {
        name: "TypeError",
        message: "Importing a module script failed.",
      },
      source: { url: source },
    });
  }));
test("rejects a generic module error from unrelated application source", () =>
  assert.rejects(
    run("module", ({ audit }) =>
      audit.record({
        kind: "console",
        message: "TypeError: Importing a module script failed.",
        exception: {
          name: "TypeError",
          message: "Importing a module script failed.",
        },
        source: { url: origin + "/assets/chart-worker.js" },
      }),
    ),
  ));

test("retains structured event kind, source, resource, status and page metadata after allowance", async () => {
  const audit = await run("http", ({ consoleError }) =>
    consoleError(
      "Failed to load resource: the server responded with a status of 503 (Service Unavailable)",
    ),
  );
  const network = audit.events.find((e) => e.kind === "response"),
    diagnostic = audit.events.find((e) => e.kind === "console");
  assert.equal(network.url, api);
  assert.equal(network.method, "POST");
  assert.equal(network.status, 503);
  assert.equal(network.resourceType, "fetch");
  assert.equal(diagnostic.source.url, api);
  assert.equal(diagnostic.source.lineNumber, 0);
  assert.equal(
    diagnostic.pageUrl,
    origin + "/?financeUi=modern#finance-overview",
  );
});

for (const resourceType of ["fetch", "script"]) {
  test(`retains Linux WebKit ${resourceType} cancellation as a lifecycle event`, async () => {
    const url = resourceType === "fetch" ? api : chunk;
    const audit = await run(null, ({ listeners }) =>
      listeners.requestfailed({
        ...request(url, "GET", resourceType),
        failure: () => ({ errorText: "Load request cancelled" }),
      }),
    );
    assert.deepEqual(audit.events.map(({ kind, url, method, resourceType, errorText }) =>
      ({ kind, url, method, resourceType, errorText })), [{
      kind: "requestfailed", url, method: "GET", resourceType,
      errorText: "Load request cancelled",
    }]);
  });
}
for (const errorText of ["Load request cancelled: network failed", "Load failed", "net::ERR_CONNECTION_RESET"]) {
  test(`rejects non-cancellation request failure: ${errorText}`, () =>
    assert.rejects(run(null, ({ listeners }) => listeners.requestfailed({
      ...request(api), failure: () => ({ errorText }),
    }))),
  );
}
for (const kind of ["pageerror", "console"]) {
  test(`does not allow cancellation wording in ${kind}`, () =>
    assert.rejects(run(null, ({ listeners, consoleError }) =>
      kind === "pageerror"
        ? listeners.pageerror(new Error("Load request cancelled"))
        : consoleError("Load request cancelled"))),
  );
}
test("cancellation does not hide a real network failure or wrong HTTP status", async () => {
  for (const actualFailure of [
    ({ listeners }) => listeners.requestfailed(request(api)),
    ({ response }) => response(api, 500),
  ]) await assert.rejects(run("http", (events) => {
    events.listeners.requestfailed({
      ...request(chunk, "GET", "script"),
      failure: () => ({ errorText: "Load request cancelled" }),
    });
    actualFailure(events);
  }));
});
