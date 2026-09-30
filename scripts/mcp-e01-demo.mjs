// Local fixture/event driver for the EXISTING REST API and mock inventory adapter.
// This script is run by the demo operator, never exposed as an MCP tool.
import { randomUUID } from "node:crypto";
import { setTimeout as wait } from "node:timers/promises";

const base = new URL(process.env.PEEK_DEMO_BASE_URL ?? "http://127.0.0.1:8080");
if (base.protocol !== "http:" || !["127.0.0.1", "localhost", "[::1]"].includes(base.hostname)) {
  throw new Error("The demo driver only accepts a local HTTP backend");
}
const [mode, commandId] = process.argv.slice(2);
async function request(path, body) {
  const response = await fetch(new URL(path, base), {
    method: body === undefined ? "GET" : "POST",
    headers: body === undefined ? {} : { "Content-Type": "application/json" },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const result = await response.json();
  if (!response.ok) throw new Error(`PEEK returned ${response.status}: ${result.message ?? "request rejected"}`);
  return result;
}

async function prepare() {
  const suffix = randomUUID();
  const sku = `MCP-DEMO-${suffix}`, channel = `demo-inventory-${suffix}`, item = `ITEM-${suffix}`;
  const product = await request("/api/v1/products", { sku, name: "MCP E01 demo", price: 10 });
  await request(`/api/v1/products/${product.id}/mappings`, { channel, externalId: item, status: "ACTIVE" });
  await request("/api/v1/events", {
    source: `demo-baseline-${suffix}`, externalEventId: "BASE", type: "STOCK_UPDATED",
    occurredAt: new Date(Date.now() - 20_000).toISOString(), productId: product.id, sku, stockAfter: 100,
  });
  const sale = await request("/api/v1/events", {
    source: `demo-sales-${suffix}`, externalEventId: "SALE", type: "SALE_CONFIRMED",
    occurredAt: new Date(Date.now() - 1_000).toISOString(), productId: product.id, sku,
    orderId: `ORDER-${suffix}`, quantity: 5,
  });
  const command = await request("/api/v1/commands/inventory-sync", {
    triggerEventId: sale.id, channel, idempotencyKey: `demo-sync-${suffix}`, simulateFailure: true,
  });
  const remaining = Date.parse(command.deadlineAt) - Date.now() + 50;
  if (remaining > 10_000) {
    throw new Error("Set demo_configuration.stock_sync_timeout_seconds=2 in the LOCAL demo database, then run prepare again (see docs/mcp_agent.md).");
  }
  if (remaining > 0) await wait(remaining);
  await request("/api/v1/evaluations", { asOf: new Date().toISOString() });
  const exceptions = await request("/api/v1/exceptions?status=OPEN&code=E01");
  const exception = exceptions.find(row => row.operationCommandId === command.id);
  if (!exception) throw new Error("PEEK did not detect the fixture E01");
  console.log(JSON.stringify({ productId: product.id, sku, commandId: command.id,
    exceptionId: exception.id, status: exception.status, expectedStock: command.expectedStock,
    watchCommand: `node scripts/mcp-e01-demo.mjs watch ${command.id}` }, null, 2));
}

async function watch() {
  if (!commandId || !/^[a-f0-9-]{36}$/i.test(commandId)) throw new Error("A command UUID is required");
  const expires = Date.now() + 120_000;
  console.log("Waiting for the external Codex MCP retry; this driver only delivers the existing mock confirmation.");
  while (Date.now() < expires) {
    const command = await request(`/api/v1/commands/${commandId}`);
    if (command.kind !== "INVENTORY_SYNC") throw new Error("Only INVENTORY_SYNC demo commands are supported");
    if (command.status === "CONFIRMED") {
      console.log("PEEK already has an external confirmation. Ask Codex to check exception status.");
      return;
    }
    const attempt = command.attempts.at(-1);
    if (attempt.attemptNumber > 1 && command.status === "PENDING_CONFIRMATION") {
      if (command.expectedStock === null) throw new Error("Cannot simulate confirmation without an expected stock target");
      await request("/api/v1/mock/inventory", {
        changeId: `mcp-demo-confirm-${command.id}-${attempt.attemptNumber}`, system: command.channel,
        warehouseSku: command.externalProductId, orderRef: command.orderId,
        registeredUnits: command.requestedQuantity, onHand: command.expectedStock,
        occurredUtc: new Date(Math.max(Date.now(), Date.parse(attempt.dispatchedAt) + 1)).toISOString(),
      });
      console.log("Existing mock inventory adapter received STOCK_UPDATED. The PEEK engine must reconcile; Codex must poll again.");
      return;
    }
    if (attempt.attemptNumber > 1 && ["FAILED", "TIMED_OUT"].includes(command.status)) {
      throw new Error(`PEEK reports ${command.status}; no confirmation was fabricated`);
    }
    await wait(250);
  }
  throw new Error("No accepted Codex retry within 120 seconds. Run watch again when ready.");
}

try {
  if (mode === "prepare") await prepare();
  else if (mode === "watch") await watch();
  else throw new Error("Usage: node scripts/mcp-e01-demo.mjs prepare | watch <commandId>");
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
