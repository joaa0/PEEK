import { expect, type APIRequestContext } from "@playwright/test";
import type {
  Alert,
  CanonicalEvent,
  Command,
  Investigation,
  Product,
} from "../src/lib/contracts";
const base = process.env.PEEK_BACKEND_URL || "http://127.0.0.1:8080";
export const epoch = "2026-01-01T12:00:00Z";
const at = (seconds: number) =>
  new Date(Date.parse(epoch) + seconds * 1000).toISOString();
export async function send<T>(
  http: APIRequestContext,
  path: string,
  body?: unknown,
): Promise<T> {
  const response =
    body === undefined
      ? await http.get(base + "/api/v1" + path)
      : await http.post(base + "/api/v1" + path, { data: body });
  expect(response.ok(), path + " " + (await response.text())).toBeTruthy();
  return response.json();
}
export async function reset(http: APIRequestContext, runId: string) {
  return send<{ totalDeleted: number }>(http, "/demo/reset", {
    runId,
    confirmation: "RESET_DEMO",
  });
}
export async function seed(http: APIRequestContext, runId: string) {
  const products: Product[] = [];
  for (const sku of ["CAM-", "SKU-E02-", "SKU-E04-"]) {
    const product = await send<Product>(http, "/products", {
      sku: sku + runId,
      name: "Produto fictício " + sku,
      category: "Demo",
    });
    products.push(product);
    for (const channel of ["demo-sales", "demo-inventory", "demo-fiscal"]) {
      await send(http, "/products/" + product.id + "/mappings", {
        channel,
        externalId: "EXT-" + product.sku,
        status: "ACTIVE",
      });
    }
  }
  const inventory = (
    p: Product,
    id: string,
    seconds: number,
    onHand: number,
    units = 0,
    orderRef: string | null = null,
    receiptRef: string | null = null,
  ) =>
    send<CanonicalEvent>(http, "/mock/inventory", {
      changeId: runId + id,
      system: "demo-inventory",
      warehouseSku: "EXT-" + p.sku,
      orderRef,
      receiptRef,
      registeredUnits: units,
      onHand,
      occurredUtc: at(seconds),
    });
  const sale = (p: Product, id: string, seconds: number, units: number) =>
    send<CanonicalEvent>(http, "/mock/sales", {
      messageId: runId + id,
      channel: "demo-sales",
      saleNumber: runId + id,
      itemId: "EXT-" + p.sku,
      units,
      happenedAt: at(seconds),
    });
  const physical = (
    p: Product,
    id: string,
    kind: string,
    seconds: number,
    units: number,
  ) =>
    send<CanonicalEvent>(http, "/mock/physical", {
      ticketId: runId + id,
      source: "demo-physical",
      kind,
      sku: p.sku,
      order: runId + id,
      receipt: runId + id,
      movement: runId + id,
      units,
      countConfirmed: kind === "COUNT",
      registeredAt: at(seconds),
    });
  const command = (
    event: CanonicalEvent,
    kind: "inventory-sync" | "fiscal",
    key: string,
    fail = false,
  ) =>
    send<Command>(http, "/commands/" + kind, {
      triggerEventId: event.id,
      channel: kind === "fiscal" ? "demo-fiscal" : "demo-inventory",
      idempotencyKey: runId + key,
      simulateFailure: fail,
    });
  await inventory(products[0], "baseline", -120, 100);
  const normalSale = await sale(products[0], "normal-sale", -60, 1);
  const normalInventory = await command(
    normalSale,
    "inventory-sync",
    "normal-sync",
  );
  await inventory(products[0], "normal-confirm", 60, 99, 1, normalSale.orderId);
  const missingSale = await sale(products[0], "missing-sale", 0, 5);
  expect((await sale(products[0], "missing-sale", 0, 5)).id).toBe(
    missingSale.id,
  );
  const failedInventory = await command(
    missingSale,
    "inventory-sync",
    "failed-sync",
    true,
  );
  expect(
    (await command(missingSale, "inventory-sync", "failed-sync", true)).id,
  ).toBe(failedInventory.id);
  const normalExit = await physical(products[0], "normal-exit", "EXIT", 0, 1);
  const normalFiscal = await command(normalExit, "fiscal", "normal-fiscal");
  await send(http, "/mock/fiscal", {
    notificationId: runId + "normal-invoice",
    system: "demo-fiscal",
    documentNumber: runId + "NF",
    orderReference: normalExit.orderId,
    movementReference: normalExit.movementId,
    itemCode: "EXT-" + products[0].sku,
    pieces: 1,
    issuedAt: at(60),
  });
  const missingExit = await physical(products[0], "missing-exit", "EXIT", 0, 2);
  const failedFiscal = await command(missingExit, "fiscal", "missing-fiscal");
  await inventory(products[1], "count-baseline", -120, 100);
  await sale(products[1], "count-sale", -60, 5);
  await physical(products[1], "normal-count", "COUNT", -30, 95);
  const count = await physical(products[1], "divergent-count", "COUNT", 0, 93);
  await inventory(products[2], "receipt-baseline", -120, 0);
  await physical(products[2], "normal-receipt", "RECEIPT", -60, 20);
  await inventory(
    products[2],
    "normal-registration",
    -30,
    20,
    20,
    null,
    runId + "normal-receipt",
  );
  const receipt = await physical(
    products[2],
    "divergent-receipt",
    "RECEIPT",
    0,
    50,
  );
  await inventory(
    products[2],
    "divergent-registration",
    60,
    67,
    47,
    null,
    receipt.receiptId,
  );
  const evaluation = await send<{ exceptionIds: string[] }>(
    http,
    "/evaluations",
    { asOf: at(601) },
  );
  const all = await Promise.all(
    evaluation.exceptionIds.map((id) => send<Alert>(http, "/exceptions/" + id)),
  );
  const alerts = all.filter((alert) =>
    products.some((p) => p.id === alert.productId),
  );
  expect(alerts.map((a) => a.code).sort()).toEqual([
    "E01",
    "E02",
    "E03",
    "E04",
  ]);
  expect(
    (await send<Command>(http, "/commands/" + normalInventory.id)).status,
  ).toBe("CONFIRMED");
  expect(
    (await send<Command>(http, "/commands/" + normalFiscal.id)).status,
  ).toBe("CONFIRMED");
  const repeated = await send<{ created: number }>(http, "/evaluations", {
    asOf: at(601),
  });
  expect(repeated.created).toBe(0);
  const contexts = await Promise.all(
    alerts.map((a) =>
      send<Investigation>(http, "/exceptions/" + a.id + "/context"),
    ),
  );
  const e02 = contexts.find((c) => c.exception.code === "E02")!;
  expect(e02.stockAtDetection.expectedStock).toBe(95);
  expect(e02.stockAtDetection.physicalStock).toBe(93);
  expect(e02.stockAtDetection.systemStock).toBe(100);
  expect(e02.currentStock.expectedStock).toBe(93);
  expect(e02.physicalCheckpoint?.id).toBe(count.id);
  return { products, alerts, failedInventory, failedFiscal };
}
