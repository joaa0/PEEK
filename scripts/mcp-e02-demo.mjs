// Operator fixture/observation driver. It never calls an MCP write tool.
import { randomUUID } from "node:crypto";
import { setTimeout as wait } from "node:timers/promises";
const base = new URL(process.env.PEEK_DEMO_BASE_URL ?? "http://127.0.0.1:8080");
if (base.protocol !== "http:" || !["localhost", "127.0.0.1", "[::1]"].includes(base.hostname))
  throw new Error("Use a local demo backend only");
const [mode, exceptionId] = process.argv.slice(2);
async function request(path, body, headers = {}) {
  const response = await fetch(new URL(path, base), {
    method: body === undefined ? "GET" : "POST",
    headers: { ...headers, ...(body === undefined ? {} : { "Content-Type": "application/json" }) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) throw new Error(`PEEK returned HTTP ${response.status}`);
  return response.json();
}
async function readTool(name, id) {
  if (!process.env.PEEK_MCP_TOKEN) throw new Error("PEEK_MCP_TOKEN must be available for read-only observations");
  const result = await request("/mcp", { jsonrpc: "2.0", id: randomUUID(), method: "tools/call",
    params: { name, arguments: { exceptionId: id } } },
    { Authorization: `Bearer ${process.env.PEEK_MCP_TOKEN}`, Accept: "application/json, text/event-stream" });
  if (result.error || result.result?.isError) throw new Error("MCP read rejected");
  return result.result.structuredContent;
}
async function prepare() {
  const suffix = randomUUID(), sku = `E02-DEMO-${suffix}`;
  const product = await request("/api/v1/products", { sku, name: "E02 human approval demo", price: 10 });
  await request(`/api/v1/products/${product.id}/mappings`, {
    channel: `demo-inventory-${suffix}`, externalId: `ITEM-${suffix}`, status: "ACTIVE",
  });
  await request("/api/v1/events", { source: `demo-baseline-${suffix}`, externalEventId: "BASE",
    type: "STOCK_UPDATED", occurredAt: new Date(Date.now() - 30000).toISOString(),
    productId: product.id, sku, stockAfter: 100 });
  await request("/api/v1/events", { source: `demo-sales-${suffix}`, externalEventId: "SALE",
    type: "SALE_CONFIRMED", occurredAt: new Date(Date.now() - 20000).toISOString(),
    productId: product.id, sku, orderId: `ORDER-${suffix}`, quantity: 5 });
  // Real fictitious physical-source fixture, through the existing inbound adapter.
  await request("/api/v1/mock/physical", { ticketId: `COUNT-${suffix}`, source: "demo-physical", kind: "COUNT",
    sku, units: 93, countConfirmed: true, registeredAt: new Date(Date.now() - 10000).toISOString() });
  await request("/api/v1/evaluations", { asOf: new Date().toISOString() });
  const alerts = await request("/api/v1/exceptions?status=OPEN&code=E02");
  const alert = alerts.find(e => e.productId === product.id);
  if (!alert) throw new Error("E02 was not detected; configure local physical tolerance below 2");
  const context = await readTool("peek_get_operational_context", alert.id);
  console.log(JSON.stringify({ exceptionId: alert.id, sku,
    FACTS_EVIDENCE: context.factsAndEvidence,
    JEV_INTERPRETATION: context.jevInterpretation,
    codexPrompt: `Investigue E02 ${alert.id}. Apresente fatos/evidências e interpretação JEV separadamente. Explique a aceitação do checkpoint existente e pergunte se eu autorizo. Aguarde minha resposta; nunca infira aprovação.`,
    approvedWatch: `node scripts/mcp-e02-demo.mjs watch ${alert.id}`,
    deniedCheck: `node scripts/mcp-e02-demo.mjs deny ${alert.id}` }, null, 2));
}
async function observe() {
  if (!exceptionId || !/^[a-f0-9-]{36}$/i.test(exceptionId)) throw new Error("Provide the E02 exception UUID");
  const deadline = Date.now() + 75000;
  do {
    const state = await readTool("peek_get_exception_status", exceptionId);
    if (state.code !== "E02") throw new Error("Only E02 is supported");
    if (mode === "deny") {
      if (state.status !== "OPEN" || state.agentActions.length !== 0)
        throw new Error("Denial scenario must remain OPEN with no operational agent action");
      console.log(JSON.stringify({ ...state, result: "Human denied: no MCP write, no action, E02 OPEN" }, null, 2));
      return;
    }
    const action = state.agentActions[0];
    if (action?.verificationStatus === "FAILED") throw new Error("PEEK reports failed verification");
    if (state.status === "RESOLVED" && action?.verificationStatus === "VERIFIED") {
      if (!state.reconciliationEventId || !state.reconciledAt) throw new Error("Missing engine proof");
      console.log(JSON.stringify(state, null, 2)); return;
    }
    await wait(250);
  } while (Date.now() < deadline);
  throw new Error("No VERIFIED proof. Approve in Codex only after review; keep the local reconciliation scheduler enabled.");
}
try {
  if (mode === "prepare") await prepare();
  else if (["watch", "deny"].includes(mode)) await observe();
  else throw new Error("Usage: node scripts/mcp-e02-demo.mjs prepare | watch <exceptionId> | deny <exceptionId>");
} catch (error) { console.error(error.message); process.exitCode = 1; }
