import { test, expect } from "@playwright/test";
import { reset, send } from "./scenarios";
import type { OperationalContext, Product } from "../src/lib/contracts";

test("Overview, Inventory and Simulation: real API, all scenarios, navigation and reproducible reset", async ({
  page,
  request,
}) => {
  test.setTimeout(180000);
  await page.goto("/simulation");
  await expect(page.locator(".demo-run")).toContainText("DEMO-");
  const session = await page.locator(".demo-run").innerText();
  const runId = session.replace("Sessão: ", "");
  expect(runId).toMatch(/^DEMO-/);
  try {
    for (let replay = 0; replay < 2; replay++) {
      for (const scenario of ["Normal", "E01", "E02", "E03", "E04"]) {
        await page
          .getByRole("button", { name: "Executar " + scenario, exact: true })
          .click();
        await expect(page.getByRole("status")).toHaveText(
          scenario === "Normal"
            ? "Normal confirmado: nenhuma exceção gerada nesta sessão."
            : scenario + " confirmado pelo backend.",
          { timeout: 20000 },
        );
        const products = await send<Product[]>(request, "/products");
        const product = products.find((p) => p.sku === "CAM-" + runId)!;
        expect(product).toBeTruthy();
        const context = await send<OperationalContext>(
          request,
          "/products/" + product.id + "/context",
        );
        expect(context.exceptions.map((a) => a.code)).toEqual(
          scenario === "Normal" ? [] : [scenario],
        );
        if (scenario === "Normal") {
          expect(context.inventorySync.status).toBe("CONFIRMED");
          expect(context.fiscalOrchestration.status).toBe("CONFIRMED");
        } else {
          await page
            .getByRole("button", { name: "Abrir exceção " + scenario })
            .click();
          await expect(
            page.getByRole("heading", { name: context.exceptions[0].title }),
          ).toBeVisible();
          await expect(
            page.getByRole("heading", {
              name: "Evidências da detecção · fatos e cálculos",
            }),
          ).toBeVisible();
          if (scenario === "E02") {
            await expect(
              page
                .locator(".api-stock article")
                .filter({ hasText: "Saldo esperado" })
                .getByText("95 un.", { exact: true }),
            ).toBeVisible();
            await expect(
              page
                .locator(".api-stock article")
                .filter({ hasText: "Saldo físico" })
                .getByText("93 un.", { exact: true }),
            ).toBeVisible();
          }
          await page
            .getByRole("link", { name: "Estoque", exact: true })
            .click();
          const row = page.getByRole("row").filter({ hasText: product.sku });
          await expect(row).toContainText(
            scenario === "E03" ? "Evidência incompleta" : "Divergência",
          );
          if (scenario !== "E03")
            await expect(
              row.getByRole("button", { name: "Abrir " + scenario }),
            ).toBeVisible();
          if (!replay && scenario === "E02")
            await page.screenshot({
              path: "test-results/inventory.png",
              fullPage: true,
            });
          await row.getByRole("button", { name: "Abrir produto" }).click();
          await expect(
            page.getByRole("heading", { name: product.name }),
          ).toBeVisible();
        }
        await page
          .getByRole("link", { name: "Visão geral", exact: true })
          .click();
        await expect(
          page.getByRole("heading", { name: "Visão geral" }),
        ).toBeVisible();
        await expect(
          page.getByRole("button", { name: "Abrir estoque" }),
        ).toBeVisible();
        if (!replay && scenario === "E03") {
          await page.screenshot({
            path: "test-results/overview.png",
            fullPage: true,
          });
          await page.getByRole("button", { name: "Abrir fiscal" }).click();
          await expect(page).toHaveURL(/\/fiscal$/);
          await expect(
            page.getByRole("row").filter({ hasText: product.sku }),
          ).toBeVisible();
        }
        await page
          .getByRole("link", { name: "Simulação", exact: true })
          .click();
        expect(await page.locator(".demo-run").innerText()).toBe(session);
      }
      await page.getByRole("button", { name: "Resetar simulação" }).click();
      await expect(page.getByRole("status")).toContainText("Sessão resetada.");
      await page.getByRole("button", { name: "Resetar simulação" }).click();
      await expect(page.getByRole("status")).toContainText(
        "Registros removidos: 0.",
      );
    }
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({
      path: "test-results/simulation-mobile.png",
      fullPage: true,
    });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBeTruthy();
  } finally {
    await reset(request, runId);
  }
});
