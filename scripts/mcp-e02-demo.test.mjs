import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { createServer } from "node:http";
import { fileURLToPath } from "node:url";
import test from "node:test";

const driver = fileURLToPath(new URL("./mcp-e02-demo.mjs", import.meta.url));
const token = "test-only-token-with-at-least-32-characters";

async function fixture(t, available = true, exposeE02 = true) {
  const calls = [],
    productId = randomUUID(),
    exceptionId = randomUUID();
  const server = createServer(async (request, response) => {
    try {
      let raw = "";
      for await (const chunk of request) raw += chunk;
      const body = raw ? JSON.parse(raw) : undefined;
      calls.push({ path: request.url, body });
      let value = {};
      if (request.url === "/mcp") {
        assert.equal(request.headers.authorization, `Bearer ${token}`);
        if (body.method === "initialize")
          value = { protocolVersion: "2025-06-18" };
        else if (body.method === "tools/list")
          value = {
            tools: exposeE02 ? [{ name: "peek_apply_e02_reconciliation" }] : [],
          };
        else {
          assert.equal(body.method, "tools/call");
          assert.ok(
            [
              "peek_get_operational_context",
              "peek_get_exception_status",
            ].includes(body.params.name),
            "The driver may only observe MCP state",
          );
          value = {
            isError: false,
            structuredContent:
              body.params.name === "peek_get_exception_status"
                ? { code: "E02", status: "OPEN", agentActions: [] }
                : {
                    factsAndEvidence: { expectedStock: 95, physicalStock: 93 },
                    jevInterpretation: {
                      status: available ? "AVAILABLE" : "UNAVAILABLE",
                      nature: available
                        ? "HYPOTHESIS_NOT_FACT"
                        : "NO_MODEL_ANALYSIS",
                    },
                  },
          };
        }
        value = { jsonrpc: "2.0", id: body.id, result: value };
      } else if (request.url === "/api/v1/products") {
        assert.match(body.sku, /^SKU-E02-DEMO-E02-[a-f0-9-]{36}$/);
        assert.equal(body.category, "Demo");
        value = { id: productId };
      } else if (request.url === "/api/v1/exceptions?status=OPEN&code=E02") {
        value = [{ id: exceptionId, productId }];
      } else if (request.url === `/api/v1/exceptions/${exceptionId}/context`) {
        value = {
          jev: {
            status: available ? "AVAILABLE" : "FALLBACK",
            fallbackReason: available ? null : "DISABLED",
          },
        };
      } else {
        assert.ok(
          [
            `/api/v1/products/${productId}/mappings`,
            "/api/v1/mock/inventory",
            "/api/v1/mock/sales",
            "/api/v1/mock/physical",
            "/api/v1/evaluations",
          ].includes(request.url),
          "No direct canonical ingestion or exception resolution",
        );
      }
      response.writeHead(200, { "Content-Type": "application/json" });
      response.end(JSON.stringify(value));
    } catch (error) {
      response.writeHead(500);
      response.end(error.message);
    }
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => new Promise((resolve) => server.close(resolve)));
  return {
    calls,
    exceptionId,
    base: `http://127.0.0.1:${server.address().port}`,
  };
}

function run(base, args, withToken = true) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [driver, ...args], {
      windowsHide: true,
      env: {
        ...process.env,
        PEEK_DEMO_BASE_URL: base,
        PEEK_MCP_TOKEN: withToken ? token : "",
      },
    });
    let stdout = "",
      stderr = "";
    child.stdout.on("data", (chunk) => (stdout += chunk));
    child.stderr.on("data", (chunk) => (stderr += chunk));
    child.on("error", reject);
    child.on("close", (code) => resolve({ code, stdout, stderr }));
  });
}

test("prepare creates resettable fictitious fixtures through all source adapters and observes JEV", async (t) => {
  const { base, calls } = await fixture(t);
  const result = await run(base, ["prepare", "--require-jev"]);
  assert.equal(result.code, 0, result.stderr);
  const output = JSON.parse(result.stdout);
  assert.equal(output.jevCheck.ready, true);
  assert.equal(output.sku, `SKU-E02-${output.runId}`);
  const mappings = calls
    .filter((call) => call.path.endsWith("/mappings"))
    .map((call) => call.body);
  assert.equal(mappings.length, 2);
  const inventory = calls.find(
    (call) => call.path === "/api/v1/mock/inventory",
  ).body;
  const sale = calls.find((call) => call.path === "/api/v1/mock/sales").body;
  assert.ok(
    mappings.some(
      (mapping) =>
        mapping.channel === inventory.system &&
        mapping.externalId === inventory.warehouseSku,
    ),
  );
  assert.ok(
    mappings.some(
      (mapping) =>
        mapping.channel === sale.channel && mapping.externalId === sale.itemId,
    ),
  );
  assert.equal(inventory.onHand, 100);
  assert.equal(sale.units, 5);
  const count = calls.find(
    (call) => call.path === "/api/v1/mock/physical",
  ).body;
  assert.equal(count.sku, output.sku);
  assert.equal(count.units, 93);
  assert.equal(count.countConfirmed, true);
});

test("required JEV smoke check fails clearly without approving a checkpoint", async (t) => {
  const { base } = await fixture(t, false);
  const result = await run(base, ["prepare", "--require-jev"]);
  assert.equal(result.code, 1);
  assert.equal(JSON.parse(result.stdout).jevCheck.fallbackReason, "DISABLED");
  assert.match(result.stderr, /Fixture remains OPEN; no agent action/);
  assert.ok(!result.stderr.includes(token));
});

test("ordinary prepare supports the documented deterministic fallback", async (t) => {
  const { base } = await fixture(t, false);
  const result = await run(base, ["prepare"]);
  assert.equal(result.code, 0, result.stderr);
  assert.equal(JSON.parse(result.stdout).jevCheck.ready, false);
});

test("missing MCP token fails before creating any fixtures", async (t) => {
  const { base, calls } = await fixture(t);
  const result = await run(base, ["prepare"], false);
  assert.equal(result.code, 1);
  assert.match(result.stderr, /PEEK_MCP_TOKEN/);
  assert.equal(calls.length, 0);
});

test("missing E02 tool fails before creating any fixtures", async (t) => {
  const { base, calls } = await fixture(t, true, false);
  const result = await run(base, ["prepare"]);
  assert.equal(result.code, 1);
  assert.match(result.stderr, /must expose peek_apply_e02_reconciliation/);
  assert.deepEqual(
    calls.map((call) => call.body.method),
    ["initialize", "tools/list"],
  );
});

test("denial only observes an OPEN exception without agent actions", async (t) => {
  const { base, exceptionId, calls } = await fixture(t);
  const result = await run(base, ["deny", exceptionId]);
  assert.equal(result.code, 0, result.stderr);
  assert.equal(JSON.parse(result.stdout).status, "OPEN");
  assert.equal(calls.length, 1);
  assert.equal(calls[0].body.params.name, "peek_get_exception_status");
});

test("the Codex connection example enables the guarded E02 tool", async () => {
  const config = await readFile(
    new URL("../docs/codex-mcp.example.toml", import.meta.url),
    "utf8",
  );
  const enabled = config.match(/enabled_tools\s*=\s*\[([\s\S]*?)\]/)[1];
  assert.ok(enabled.includes('"peek_apply_e02_reconciliation"'));
  assert.ok(enabled.includes('"peek_retry_inventory_sync"'));
  assert.ok(!enabled.includes("resolve"));
});
