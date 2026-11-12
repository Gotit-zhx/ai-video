/* Strict local API fixture. It never forwards a request to a real service. */
const http = require("node:http");
const fs = require("node:fs/promises");
const path = require("node:path");

const frontend = path.resolve(__dirname, "../..");
const types = {
  ".js": "application/javascript",
  ".mjs": "application/javascript",
  ".css": "text/css",
  ".json": "application/json",
  ".html": "text/html",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".webp": "image/webp",
  ".ico": "image/x-icon",
  ".woff2": "font/woff2",
  ".woff": "font/woff",
};

async function createFixtureServer({ user, finance } = {}) {
  const requests = [],
    unhandled = [],
    errors = [];
  let currentUser = user || {
    organizationId: "fixture-company",
    userId: "fixture-user",
    username: "fixture-user",
    displayName: "隔离验收账户",
    organizationName: "隔离验收公司",
    roles: [],
    permissions: ["finance:view", "finance:manage"],
  };
  const server = http.createServer(async (request, response) => {
    try {
      const url = new URL(request.url, "http://127.0.0.1");
      const send = (status, data, headers = {}) => {
        response.writeHead(status, { "Cache-Control": "no-store", ...headers });
        response.end(data);
      };
      const json = (data, status = 200) =>
        send(
          status,
          JSON.stringify({
            success: status < 400,
            data,
            ...(status >= 400 ? { message: String(data) } : {}),
          }),
          { "Content-Type": "application/json" },
        );
      if (url.pathname === "/_dev/revision") {
        send(200, JSON.stringify({ revision: "fixture-stable" }), {
          "Content-Type": "application/json",
        });
        return;
      }
      if (url.pathname.startsWith("/api/")) {
        const chunks = [];
        for await (const chunk of request) chunks.push(chunk);
        const bytes = Buffer.concat(chunks);
        let body;
        if (
          (request.headers["content-type"] || "").includes(
            "application/json",
          ) &&
          bytes.length
        )
          body = JSON.parse(bytes.toString());
        let form;
        if (
          (request.headers["content-type"] || "").includes(
            "multipart/form-data",
          )
        ) {
          const parsed = await new Response(bytes, {
            headers: { "Content-Type": request.headers["content-type"] },
          }).formData();
          form = {};
          for (const [key, value] of parsed)
            form[key] =
              typeof value === "string"
                ? value
                : {
                    name: value.name,
                    size: value.size,
                    text: await value.text(),
                  };
        }
        const call = {
          method: request.method,
          path: url.pathname,
          query: Object.fromEntries(url.searchParams),
          body,
          form,
          bytes,
          headers: request.headers,
          aborted: false,
        };
        response.on("close", () => {
          if (!response.writableEnded) call.aborted = true;
        });
        requests.push(call);
        if (url.pathname === "/api/v1/auth/me") {
          json(currentUser);
          return;
        }
        if (url.pathname === "/api/v1/auth/social/providers") {
          json([]);
          return;
        }
        if (url.pathname.startsWith("/api/v1/finance/")) {
          const result = await finance?.({
            ...call,
            path: url.pathname.slice("/api/v1/finance".length),
          });
          if (result !== undefined) {
            if (result?.delay)
              await new Promise((resolve) => setTimeout(resolve, result.delay));
            if (result?.disconnect) {
              response.destroy();
              return;
            }
            if (result?.binary)
              send(result.status || 200, result.binary, {
                "Content-Type":
                  result.contentType || "application/octet-stream",
                ...(result.headers || {}),
              });
            else if (result?.fixtureError)
              json(result.fixtureError, result.status || 500);
            else json(result);
            return;
          }
        }
        unhandled.push({ method: request.method, path: url.pathname });
        json("未配置隔离测试接口：" + url.pathname, 404);
        return;
      }
      const route = url.pathname === "/" ? "/index.html" : url.pathname;
      const parts = route.split("/").filter(Boolean);
      const extension = path.extname(route).toLowerCase();
      const publicFile =
        (parts.length === 1 &&
          [".html", ".js", ".css", ".svg", ".png", ".ico"].includes(
            extension,
          )) ||
        ["assets", "vendor", "i18n", "downloads", "plugin-downloads"].includes(
          parts[0],
        );
      if (
        !publicFile ||
        parts.some((part) => part.startsWith(".") || part.includes("\\"))
      ) {
        send(404, "");
        return;
      }
      const relative = route.replace(/^\/plugin-downloads\//, "/downloads/");
      const file = path.resolve(frontend, "." + relative);
      if (!file.startsWith(frontend + path.sep)) {
        send(404, "");
        return;
      }
      const bytes = await fs.readFile(file);
      send(200, bytes, {
        "Content-Type": types[extension] || "application/octet-stream",
      });
    } catch (error) {
      if (error.code !== "ENOENT") errors.push(error.message);
      response.statusCode = error.code === "ENOENT" ? 404 : 500;
      response.end(error.code === "ENOENT" ? "" : "隔离测试服务器错误");
    }
  });
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  return {
    url: "http://127.0.0.1:" + server.address().port,
    requests,
    unhandled,
    errors,
    setUser(next) {
      currentUser = next;
    },
    close() {
      return new Promise((resolve, reject) =>
        server.close((error) => (error ? reject(error) : resolve())),
      );
    },
  };
}

module.exports = { createFixtureServer };
