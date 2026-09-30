import { test, expect } from "@playwright/test";
import { alert, context, investigation, product } from "../src/test/fixtures";
import { fiscalExamples } from "../src/lib/fiscal-fixtures";

const fiscalAlert = {
  ...alert,
  id: "fiscal-alert",
  code: "E03",
  title: "Saída sem documento fiscal",
  operationCommandId: "fiscal-command",
};
const command = { ...fiscalExamples[3].command!, id: "fiscal-command" };
test.beforeEach(async ({ page }) => {
  await page.route("**/api/v1/**", async (route) => {
    const path = new URL(route.request().url()).pathname.replace("/api/v1", "");
    const data =
      path === "/exceptions"
        ? [fiscalAlert]
        : path === "/commands/fiscal-command"
          ? command
          : path === "/exceptions/fiscal-alert/context"
            ? { ...investigation(), exception: fiscalAlert }
            : path === "/products"
              ? [product]
              : path === "/products/product-1/context"
                ? context
                : path === "/products/product-1/propagations"
                  ? []
                  : path === "/product-mappings/resolve" ||
                      path === "/products/product-1"
                    ? product
                    : null;
    await route.fulfill({
      status: data === null ? 404 : 200,
      json: data ?? { message: "Fixture ausente" },
    });
  });
});

test("six routes, active links, history and preserved product detail", async ({
  page,
}) => {
  await page.goto("/overview");
  const nav = page.getByRole("navigation", { name: "Navegação principal" });
  await expect(nav.getByRole("link")).toHaveCount(6);
  for (const [label, path] of [
    ["Produtos", "/products"],
    ["Estoque", "/inventory"],
    ["Fiscal", "/fiscal"],
    ["Central de exceções", "/exceptions"],
    ["Simulação", "/simulation"],
    ["Visão geral", "/overview"],
  ]) {
    await nav.getByRole("link", { name: label, exact: true }).click();
    await expect(page).toHaveURL(new RegExp(path + "$"));
    await expect(
      page.getByRole("heading", {
        name: label === "Simulação" ? "Simulação do MVP" : label,
        exact: true,
        level: 1,
      }),
    ).toBeVisible();
    await expect(nav.locator('[aria-current="page"]')).toHaveCount(1);
    await expect(
      nav.getByRole("link", { name: label, exact: true }),
    ).toHaveAttribute("aria-current", "page");
  }
  await page.goBack();
  await expect(
    nav.getByRole("link", { name: "Simulação", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  await page.goForward();
  await expect(
    nav.getByRole("link", { name: "Visão geral", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  await page.goto("/products/product-1");
  await expect(
    page.getByRole("heading", { name: product.name, exact: true }),
  ).toBeVisible();
  await expect(
    nav.getByRole("link", { name: "Produtos", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  await expect(page.locator("body")).not.toContainText("PEEKio");
});

test("fiscal states, attempt history, real investigation and transparent official logo", async ({
  page,
}) => {
  const writes: string[] = [];
  const errors: string[] = [];
  page.on("request", (request) => {
    if (request.url().includes("/api/v1/") && request.method() !== "GET")
      writes.push(request.method());
  });
  page.on("pageerror", (error) => errors.push(error.message));
  await page.goto("/fiscal");
  for (const status of ["Pendente", "Confirmado", "Falhou", "Timeout"])
    await expect(
      page.locator(".fiscal-status").filter({ hasText: status }).first(),
    ).toBeVisible();
  const row = page.getByRole("row").filter({ hasText: "fiscal-command" });
  await row.getByText("1 tentativa(s)").click();
  await expect(
    row.locator("details table").getByRole("cell").filter({ hasText: "#1" }),
  ).toBeVisible();
  const logo = page.locator(".brand img");
  await expect(logo).toBeVisible();
  expect(
    await logo.evaluate(
      (element: HTMLImageElement) =>
        element.complete && element.naturalWidth > 0,
    ),
  ).toBe(true);
  expect(
    await logo.evaluate((element: HTMLImageElement) => {
      const canvas = document.createElement("canvas");
      canvas.width = 1;
      canvas.height = 1;
      const drawing = canvas.getContext("2d")!;
      drawing.drawImage(element, 0, 0);
      return drawing.getImageData(0, 0, 1, 1).data[3];
    }),
  ).toBe(0);
  await row.getByText("1 tentativa(s)").click();
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.screenshot({
    path: "test-results/fiscal-desktop.png",
    fullPage: true,
  });
  await row.getByRole("button", { name: "Abrir investigação E03" }).click();
  await expect(page).toHaveURL(/\/exceptions\/fiscal-alert$/);
  await expect(
    page.getByRole("heading", { name: fiscalAlert.title }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Central de exceções", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  expect(writes).toEqual([]);
  expect(errors).toEqual([]);
});

test("mobile layout, direct fiscal route and reload retain the active navigation", async ({
  page,
}) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/fiscal");
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "Fiscal", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Fiscal", exact: true }),
  ).toHaveAttribute("aria-current", "page");
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= window.innerWidth,
    ),
  ).toBe(true);
  await page.screenshot({
    path: "test-results/fiscal-mobile.png",
    fullPage: true,
  });
  await page.getByRole("link", { name: "Produtos", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Produtos", exact: true, level: 1 }),
  ).toBeVisible();
});
