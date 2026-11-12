const assert = require("node:assert/strict");

/** Allow only browser diagnostics for an observed, explicitly injected failure. */
function createErrorAudit(origin) {
  const events = [],
    http = [],
    modules = [],
    scripts = new Set();
  const address = (value) => new URL(value);
  function expectHttpFailure(rule) {
    assert(["GET", "POST", "PUT", "PATCH", "DELETE"].includes(rule.method));
    assert(
      rule.path.startsWith("/api/") &&
        Number.isInteger(rule.status) &&
        rule.status >= 400,
    );
    assert(
      !http.some((r) => r.method === rule.method && r.path === rule.path),
      "duplicate HTTP failure registration",
    );
    http.push({ ...rule });
  }
  function injectHttpFailure(call, status, message) {
    const path = "/api/v1/finance" + call.path;
    expectHttpFailure({ method: call.method, path, status });
    return { fixtureError: message, status };
  }
  function expectModuleFailure(request) {
    const url = request.url();
    assert.equal(address(url).origin, origin);
    assert.equal(request.method(), "GET");
    assert.equal(request.resourceType(), "script");
    assert(
      !modules.some((r) => r.url === url),
      "duplicate module failure injection",
    );
    modules.push({ url, method: "GET", resourceType: "script" });
  }
  function record(event) {
    events.push(event);
    if (
      event.kind === "response" &&
      event.status < 400 &&
      event.resourceType === "script"
    )
      scripts.add(event.url);
  }
  function assertClean() {
    const failures = [],
      allowedHttp = [],
      allowedModules = [];
    const responses = events.filter(
      (e) => e.kind === "response" && e.status >= 400,
    );
    const requestFailures = events.filter((e) => e.kind === "requestfailed");
    for (const rule of http) {
      const matching = responses.filter(
        (e) =>
          e.method === rule.method &&
          address(e.url).origin === origin &&
          address(e.url).pathname === rule.path &&
          e.status === rule.status,
      );
      if (matching.length !== 1)
        failures.push({
          reason: "expected exactly one injected HTTP response",
          rule,
          observed: matching,
        });
      if (matching.length === 1)
        allowedHttp.push({ ...matching[0], consoleCount: 0 });
    }
    for (const rule of modules) {
      const matching = requestFailures.filter(
        (e) =>
          e.url === rule.url &&
          e.method === rule.method &&
          e.resourceType === rule.resourceType,
      );
      if (matching.length !== 1)
        failures.push({
          reason: "expected exactly one injected module failure",
          rule,
          observed: matching,
        });
      if (matching.length === 1)
        allowedModules.push({
          ...matching[0],
          nativeConsoleCount: 0,
          reactConsoleCount: 0,
        });
    }
    for (const event of events) {
      if (event.kind === "pageerror") {
        failures.push(event);
        continue;
      }
      if (event.kind === "response") {
        if (
          event.status >= 400 &&
          !allowedHttp.some(
            (e) =>
              e === event ||
              (e.url === event.url &&
                e.method === event.method &&
                e.status === event.status),
          )
        )
          failures.push(event);
        continue;
      }
      if (event.kind === "requestfailed") {
        // Cancellation is an ordinary lifecycle outcome, retained in the receipt.
        if (
          [
            "net::ERR_ABORTED",
            "NS_BINDING_ABORTED",
            "cancelled",
            "Load cancelled",
          ].includes(event.errorText)
        )
          continue;
        if (
          !allowedModules.some(
            (e) =>
              e.url === event.url &&
              e.method === event.method &&
              e.resourceType === event.resourceType,
          )
        )
          failures.push(event);
        continue;
      }
      if (event.kind !== "console") continue;
      const matchingHttp = allowedHttp.find(
        (e) =>
          !event.exception &&
          event.source.url === e.url &&
          new RegExp(
            "^Failed to load resource: the server responded with a status of " +
              e.status +
              "(?: \\([A-Za-z ]+\\))?$",
          ).test(event.message),
      );
      if (matchingHttp) {
        if (++matchingHttp.consoleCount > 1)
          failures.push({ reason: "duplicate HTTP console error", event });
        continue;
      }
      const matchingModule = allowedModules.find((e) => {
        const native =
          !event.exception &&
          event.source.url === e.url &&
          [
            "Failed to load resource: net::ERR_FAILED",
            "Failed to load resource: Load failed",
            "Failed to load resource: The operation couldn’t be completed. (WebKitInternal error 0.)",
          ].includes(event.message);
        const react =
          event.exception?.name === "TypeError" &&
          scripts.has(event.source.url) &&
          /\/assets\/finance-modern\/assets\/presentation-[^/]+\.js$/.test(
            address(event.source.url).pathname,
          ) &&
          [
            "Failed to fetch dynamically imported module: " + e.url,
            "error loading dynamically imported module: " + e.url,
            "Importing a module script failed.",
          ].includes(event.exception.message);
        if (native) {
          e.nativeConsoleCount++;
          return true;
        }
        if (react) {
          e.reactConsoleCount++;
          return true;
        }
        return false;
      });
      if (!matchingModule) failures.push(event);
      else if (
        matchingModule.nativeConsoleCount > 1 ||
        matchingModule.reactConsoleCount > 1
      )
        failures.push({ reason: "duplicate module console error", event });
    }
    assert.deepEqual(
      failures,
      [],
      "unexpected structured browser error events",
    );
  }
  return {
    events,
    record,
    injectHttpFailure,
    expectModuleFailure,
    assertClean,
  };
}
module.exports = { createErrorAudit };
