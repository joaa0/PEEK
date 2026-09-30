import { test, expect } from "@playwright/test";
import { randomUUID } from "node:crypto";
import { seed, reset, send } from "./scenarios";
import type { Command, Propagation } from "../src/lib/contracts";

test("E01/E02: adapters, duplicate, investigation, fallback, retry, resolution and reset/replay", async ({
  page,
  request,
}) => {
  const run = "QA-E2E-" + randomUUID();
  await reset(request, run);
  try {
    for (let replay = 0; replay < 2; replay++) {
      const data = await seed(request, run);
      const e01 = data.alerts.find((a) => a.code === "E01")!;
      await page.goto("/exceptions/" + e01.id);
      await expect(
        page.getByRole("heading", { name: e01.title }),
      ).toBeVisible();
      await expect(
        page.getByText("Saldo esperado · expected_stock"),
      ).toBeVisible();
      await expect(
        page.getByText("Saldo do sistema · system_stock"),
      ).toBeVisible();
      await expect(
        page.getByText(/Identificador externo: EXT-CAM/),
      ).toBeVisible();
      await expect(page.getByText(/JEV indisponível/)).toBeVisible();
      await page.getByRole("button", { name: "Reprocessar comando" }).click();
      const before = await send<Command>(
        request,
        "/commands/" + data.failedInventory.id,
      );
      expect(before.attempts).toHaveLength(1);
      await page.getByRole("button", { name: "Confirmar envio" }).click();
      await expect(page.getByText("#2")).toBeVisible();
      await expect(page.getByText("Aberta", { exact: true })).toBeVisible();
      await page.getByRole("button", { name: "Resolver alerta" }).click();
      await expect(
        page.getByRole("button", { name: "Confirmar resolução" }),
      ).toBeDisabled();
      await page
        .getByLabel("Nota de resolução")
        .fill("Tentativas revisadas; ação registrada.");
      await page.getByRole("button", { name: "Confirmar resolução" }).click();
      await expect(page.getByText("Resolvida", { exact: true })).toBeVisible();
      await page.getByRole("button", { name: "← Voltar às exceções" }).click();
      await page
        .getByRole("combobox", { name: "Status", exact: true })
        .selectOption("RESOLVED");
      await page
        .getByRole("combobox", { name: "Tipo", exact: true })
        .selectOption("E01");
      await expect(
        page.getByRole("row").filter({ hasText: data.products[0].sku }),
      ).toContainText("Resolvida");
      const e02 = data.alerts.find((a) => a.code === "E02")!;
      await page.goto("/exceptions/" + e02.id);
      await expect(page.getByText("95 un.", { exact: true })).toBeVisible();
      await expect(page.getByText("100 un.", { exact: true })).toBeVisible();
      await expect(page.getByText("93 un.", { exact: true })).toBeVisible();
      await expect(page.getByText(/ANTES desta contagem/)).toBeVisible();
      await expect(page.getByText(/nenhum SALE_CONFIRMED/)).toBeVisible();
      if (!replay)
        await page.screenshot({
          path: "test-results/investigation-e02.png",
          fullPage: true,
        });
      expect((await reset(request, run)).totalDeleted).toBeGreaterThan(0);
      expect((await reset(request, run)).totalDeleted).toBe(0);
    }
  } finally {
    await reset(request, run);
  }
});

test("E03/E04: only violated conditions, evidence, parameters, recommendations and replay", async ({
  page,
  request,
}) => {
  const run = "QA-FISCAL-" + randomUUID();
  try {
    for (let replay = 0; replay < 2; replay++) {
      const data = await seed(request, run);
      for (const code of ["E03", "E04"]) {
        const alert = data.alerts.find((a) => a.code === code)!;
        await page.goto("/exceptions/" + alert.id);
        await expect(
          page.getByText(alert.ruleParameter, { exact: true }),
        ).toBeVisible();
        await expect(
          page.getByRole("heading", {
            name: "Evidências da detecção · fatos e cálculos",
          }),
        ).toBeVisible();
        await expect(
          page.getByText(alert.recommendation, { exact: true }),
        ).toBeVisible();
        if (code === "E03") {
          await expect(
            page.getByText(/Ausência de evento correlacionado/),
          ).toBeVisible();
          await expect(
            page.getByRole("button", { name: "Reprocessar comando" }),
          ).toBeVisible();
        } else
          await expect(
            page.getByText("receivedQuantity", { exact: true }),
          ).toBeVisible();
      }
      expect((await reset(request, run)).totalDeleted).toBeGreaterThan(0);
      expect((await reset(request, run)).totalDeleted).toBe(0);
    }
  } finally {
    await reset(request, run);
  }
});

test("register, edit, map and distribute canonical product with partial failure and auditable retry", async ({
  page,
  request,
}) => {
  const run = "QA-PROPAGATION-" + randomUUID();
  const sku = "CAM-" + run;
  try {
    await page.goto("/products");
    await page.getByRole("button", { name: "Cadastrar produto" }).click();
    await page.getByLabel("SKU", { exact: true }).fill(sku);
    await page
      .getByLabel("Nome", { exact: true })
      .fill("Câmera demo integrada");
    await page.getByLabel("Categoria básica").fill("Demo");
    await page.getByRole("button", { name: "Salvar produto" }).click();
    await expect(
      page.getByRole("heading", { name: "Câmera demo integrada" }),
    ).toBeVisible();
    const productId = page.url().split("/").pop()!;
    await expect(page.getByText(/Sem mapping. Associe/)).toBeVisible();
    await page
      .getByRole("button", { name: "Editar produto", exact: true })
      .click();
    await page
      .getByLabel("Nome", { exact: true })
      .fill("Câmera demo atualizada");
    await page.getByRole("button", { name: "Salvar produto" }).click();
    await expect(
      page.getByRole("heading", { name: "Câmera demo atualizada" }),
    ).toBeVisible();
    for (const name of ["ERP", "MERCADO_LIVRE", "SHOPEE"])
      await page.getByLabel(name, { exact: true }).check();
    await page.getByLabel("Simular falha da Shopee nesta distribuição").check();
    await page.getByRole("button", { name: "Distribuir produto" }).click();
    expect(
      await send<Propagation[]>(
        request,
        "/products/" + productId + "/propagations",
      ),
    ).toHaveLength(0);
    await page.getByRole("button", { name: "Confirmar distribuição" }).click();
    await expect(
      page.getByRole("heading", { name: /SHOPEE · CREATE · Falha/ }),
    ).toBeVisible();
    await expect(
      page.getByRole("heading", { name: /ERP · CREATE · Sucesso/ }),
    ).toBeVisible();
    await page.getByRole("button", { name: "Reprocessar SHOPEE" }).click();
    await page.getByRole("button", { name: "Confirmar envio" }).click();
    await expect(
      page.getByRole("heading", { name: /SHOPEE · CREATE · Sucesso/ }),
    ).toBeVisible();
    const rows = await send<Propagation[]>(
      request,
      "/products/" + productId + "/propagations",
    );
    expect(
      rows.find((r) => r.channel === "SHOPEE")?.attempts.map((a) => a.result),
    ).toEqual(["FAILED", "SUCCEEDED"]);
    await page.screenshot({
      path: "test-results/product-propagation.png",
      fullPage: true,
    });
    await page
      .getByRole("button", { name: "Editar mapping ERP", exact: true })
      .click();
    await page.getByLabel("Estado do mapping").selectOption("INACTIVE");
    await page.getByRole("button", { name: "Salvar mapping" }).click();
    await expect(page.getByText("Inativo", { exact: true })).toBeVisible();
    await page.setViewportSize({ width: 390, height: 844 });
    await page.screenshot({
      path: "test-results/product-mobile.png",
      fullPage: true,
    });
    expect(
      await page.evaluate(
        () => document.documentElement.scrollWidth <= window.innerWidth,
      ),
    ).toBeTruthy();
  } finally {
    expect((await reset(request, run)).totalDeleted).toBeGreaterThan(0);
    expect((await reset(request, run)).totalDeleted).toBe(0);
  }
});
