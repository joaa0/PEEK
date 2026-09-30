// Operator fixture/observation driver. It never calls an MCP write tool.
import { randomUUID } from "node:crypto";
import { setTimeout as wait } from "node:timers/promises";
const base = new URL(process.env.PEEK_DEMO_BASE_URL ?? "http://127.0.0.1:8080");
if (
  base.protocol !== "http:" ||
  !["localhost", "127.0.0.1", "[::1]"].includes(base.hostname)
)
  throw new Error("Use a local demo backend only");
const [mode, argument] = process.argv.slice(2);
const exceptionId = mode === "prepare" ? undefined : argument;
const requireJev = mode === "prepare" && argument === "--require-jev";
async function request(path, body, headers = {}) {
  const response = await fetch(new URL(path, base), {
    method: body === undefined ? "GET" : "POST",
    headers: {
      ...headers,
      ...(body === undefined ? {} : { "Content-Type": "application/json" }),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`PEEK returned HTTP ${response.status}`);
  return response.json();
}
async function readRpc(method, params) {
  if (!process.env.PEEK_MCP_TOKEN)
    throw new Error(
      "PEEK_MCP_TOKEN must be available for read-only observations",
    );
  const result = await request(
    "/mcp",
    { jsonrpc: "2.0", id: randomUUID(), method, params },
    {
      Authorization: `Bearer ${process.env.PEEK_MCP_TOKEN}`,
      Accept: "application/json, text/event-stream",
    },
  );
  if (result.error || result.result?.isError)
    throw new Error("MCP read rejected");
  return result.result;
}
async function readTool(name, id) {
  return (await readRpc("tools/call", { name, arguments: { exceptionId: id } }))
    .structuredContent;
}
async function prepare() {
  if (argument && argument !== "--require-jev")
    throw new Error("Use prepare [--require-jev]");
  // Check the local MCP connection before creating any fixture data.
  await readRpc("initialize", {
    protocolVersion: "2025-06-18",
    capabilities: {},
    clientInfo: { name: "peek-e02-demo", version: "1.0" },
  });
  const catalog = await readRpc("tools/list", {});
  if (
    !catalog.tools.some((tool) => tool.name === "peek_apply_e02_reconciliation")
  )
    throw new Error("The local MCP must expose peek_apply_e02_reconciliation");
  const suffix = randomUUID(),
    runId = `DEMO-E02-${suffix}`,
    sku = `SKU-E02-${runId}`;
  const inventoryChannel = `demo-inventory-${suffix}`,
    salesChannel = `demo-sales-${suffix}`;
  const stockItem = `STOCK-${suffix}`,
    saleItem = `SALE-${suffix}`;
  const product = await request("/api/v1/products", {
    sku,
    name: "Fictitious E02 human approval demo",
    price: 10,
    category: "Demo",
  });
  for (const [channel, externalId] of [
    [inventoryChannel, stockItem],
    [salesChannel, saleItem],
  ]) {
    await request(`/api/v1/products/${product.id}/mappings`, {
      channel,
      externalId,
      status: "ACTIVE",
    });
  }
  // All evidence originates in the existing source adapters; never label canonical input as an adapter fixture.
  await request("/api/v1/mock/inventory", {
    system: inventoryChannel,
    changeId: `BASE-${suffix}`,
    warehouseSku: stockItem,
    onHand: 100,
    occurredUtc: new Date(Date.now() - 30000).toISOString(),
  });
  await request("/api/v1/mock/sales", {
    channel: salesChannel,
    messageId: `SALE-${suffix}`,
    saleNumber: `ORDER-${suffix}`,
    itemId: saleItem,
    units: 5,
    happenedAt: new Date(Date.now() - 20000).toISOString(),
  });
  await request("/api/v1/mock/physical", {
    ticketId: `COUNT-${suffix}`,
    source: "demo-physical",
    kind: "COUNT",
    sku,
    units: 93,
    countConfirmed: true,
    registeredAt: new Date(Date.now() - 10000).toISOString(),
  });
  await request("/api/v1/evaluations", { asOf: new Date().toISOString() });
  const alerts = await request("/api/v1/exceptions?status=OPEN&code=E02");
  const alert = alerts.find((e) => e.productId === product.id);
  if (!alert)
    throw new Error(
      "E02 was not detected; configure local physical tolerance below 2",
    );
  const context = await readTool("peek_get_operational_context", alert.id);
  // REST exposes a safe fallback reason; MCP preserves its AVAILABLE/UNAVAILABLE contract.
  const investigation = await request(`/api/v1/exceptions/${alert.id}/context`);
  const jevStatus = context.jevInterpretation.status;
  const fallbackReason = investigation.jev?.fallbackReason ?? null;
  console.log(
    JSON.stringify(
      {
        exceptionId: alert.id,
        sku,
        runId,
        FACTS_EVIDENCE: context.factsAndEvidence,
        JEV_INTERPRETATION: context.jevInterpretation,
        jevCheck: {
          status: jevStatus,
          restStatus: investigation.jev?.status ?? null,
          fallbackReason,
          ready:
            jevStatus === "AVAILABLE" &&
            investigation.jev?.status === "AVAILABLE",
        },
        codexPrompt: `Investigue E02 ${alert.id}. Apresente fatos/evidências e interpretação JEV separadamente. Explique a aceitação do checkpoint existente e pergunte se eu autorizo. Aguarde minha resposta; nunca infira aprovação.`,
        approvedWatch: `node scripts/mcp-e02-demo.mjs watch ${alert.id}`,
        deniedCheck: `node scripts/mcp-e02-demo.mjs deny ${alert.id}`,
      },
      null,
      2,
    ),
  );
  if (
    requireJev &&
    (jevStatus !== "AVAILABLE" || investigation.jev?.status !== "AVAILABLE")
  )
    throw new Error(
      `JEV smoke check failed (${fallbackReason ?? "UNAVAILABLE"}); verify demo,mcp profiles and PEEK_LLM configuration. Fixture remains OPEN; no agent action was requested.`,
    );
}
async function observe() {
  if (!exceptionId || !/^[a-f0-9-]{36}$/i.test(exceptionId))
    throw new Error("Provide the E02 exception UUID");
  const deadline = Date.now() + 75000;
  do {
    const state = await readTool("peek_get_exception_status", exceptionId);
    if (state.code !== "E02") throw new Error("Only E02 is supported");
    if (mode === "deny") {
      if (state.status !== "OPEN" || state.agentActions.length !== 0)
        throw new Error(
          "Denial scenario must remain OPEN with no operational agent action",
        );
      console.log(
        JSON.stringify(
          {
            ...state,
            result: "Human denied: no MCP write, no action, E02 OPEN",
          },
          null,
          2,
        ),
      );
      return;
    }
    const action = state.agentActions[0];
    if (action?.verificationStatus === "FAILED")
      throw new Error("PEEK reports failed verification");
    if (
      state.status === "RESOLVED" &&
      action?.verificationStatus === "VERIFIED"
    ) {
      if (!state.reconciliationEventId || !state.reconciledAt)
        throw new Error("Missing engine proof");
      console.log(JSON.stringify(state, null, 2));
      return;
    }
    await wait(250);
  } while (Date.now() < deadline);
  throw new Error(
    "No VERIFIED proof. Approve in Codex only after review; keep the local reconciliation scheduler enabled.",
  );
}
try {
  if (mode === "prepare") await prepare();
  else if (["watch", "deny"].includes(mode)) await observe();
  else
    throw new Error(
      "Usage: node scripts/mcp-e02-demo.mjs prepare [--require-jev] | watch <exceptionId> | deny <exceptionId>",
    );
} catch (error) {
  console.error(error.message);
  process.exitCode = 1;
}
