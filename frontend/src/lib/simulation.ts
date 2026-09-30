import { api } from "./api";
import type { Alert, Command, DemoScenario, Product } from "./contracts";

export interface SimulationResult {
  scenario: DemoScenario;
  product: Product;
  alerts: Alert[];
  asOf: string;
}
// Every replay owns the same narrow namespace supported by DemoResetService.
export async function runSimulation(
  runId: string,
  scenario: DemoScenario,
): Promise<SimulationResult> {
  await api.resetDemo(runId);
  const product = await api.createProduct({
    sku: "CAM-" + runId,
    name: "Produto da simulação " + scenario,
    category: "Demo",
    description: null,
    price: null,
    gtin: null,
  });
  const externalId = "EXT-" + product.sku;
  for (const channel of ["demo-sales", "demo-inventory", "demo-fiscal"]) {
    await api.addMapping(product.id, { channel, externalId, status: "ACTIVE" });
  }
  // Use the backend clock (including the fixed demo clock), never the browser clock.
  const epoch = Date.parse(product.createdAt);
  const at = (seconds: number) =>
    new Date(epoch + seconds * 1000).toISOString();
  const commands: Command[] = [];
  const inventory = (
    id: string,
    seconds: number,
    onHand: number,
    units = 0,
    orderRef: string | null = null,
    receiptRef: string | null = null,
  ) =>
    api.mockEvent("inventory", {
      changeId: runId + id,
      system: "demo-inventory",
      warehouseSku: externalId,
      occurredUtc: at(seconds),
      onHand,
      registeredUnits: units,
      orderRef,
      receiptRef,
    });
  const physical = (id: string, kind: string, seconds: number, units: number) =>
    api.mockEvent("physical", {
      ticketId: runId + id,
      source: "demo-physical",
      kind,
      sku: product.sku,
      order: runId + id,
      movement: runId + id,
      receipt: runId + id,
      units,
      countConfirmed: kind === "COUNT",
      registeredAt: at(seconds),
    });
  await inventory("baseline", -120, 100);
  if (scenario === "E04") {
    const receipt = await physical("receipt", "RECEIPT", 0, 50);
    await inventory("receipt-update", 60, 147, 47, null, receipt.receiptId);
  } else {
    const sale = await api.mockEvent("sales", {
      messageId: runId + "sale",
      channel: "demo-sales",
      saleNumber: runId + "sale",
      itemId: externalId,
      units: 5,
      happenedAt: at(0),
    });
    const sync = await api.createCommand(
      "inventory-sync",
      sale.id,
      "demo-inventory",
      runId + "-inventory",
    );
    commands.push(sync);
    if (scenario !== "E01")
      await inventory("sale-update", 60, 95, 5, sale.orderId);
    if (scenario === "E02" || scenario === "Normal")
      await physical("count", "COUNT", 90, scenario === "E02" ? 93 : 95);
    if (scenario === "E03" || scenario === "Normal") {
      const exit = await physical("exit", "EXIT", 90, 2);
      commands.push(
        await api.createCommand(
          "fiscal",
          exit.id,
          "demo-fiscal",
          runId + "-fiscal",
        ),
      );
      if (scenario === "Normal")
        await api.mockEvent("fiscal", {
          notificationId: runId + "invoice",
          system: "demo-fiscal",
          documentNumber: runId + "NF",
          orderReference: exit.orderId,
          movementReference: exit.movementId,
          itemCode: externalId,
          pieces: 2,
          issuedAt: at(120),
        });
    }
  }
  // Advance evaluation explicitly beyond the actual command deadlines; no waiting for timeouts.
  const asOf = new Date(
    Math.max(
      epoch + 601000,
      ...commands.map((c) => Date.parse(c.deadlineAt) + 1000),
    ),
  ).toISOString();
  await api.evaluate(asOf);
  const context = await api.context(product.id);
  const alerts = context.exceptions.filter((a) => a.status === "OPEN");
  if (
    scenario === "Normal"
      ? alerts.length !== 0
      : alerts.length !== 1 || alerts[0].code !== scenario
  ) {
    throw new Error(
      "O backend não confirmou o resultado esperado. Abra o produto para investigar e tente resetar a sessão.",
    );
  }
  if (
    scenario === "Normal" &&
    (context.inventorySync.status !== "CONFIRMED" ||
      context.fiscalOrchestration.status !== "CONFIRMED")
  ) {
    throw new Error(
      "Os comandos normais ainda não possuem confirmação correlacionada.",
    );
  }
  return { scenario, product, alerts, asOf };
}
