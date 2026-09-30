import { act, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { api, ApiError } from "../lib/api";
import { alert, investigation, product } from "../test/fixtures";
import { fiscalExamples } from "../lib/fiscal-fixtures";
import { loadFiscalRecords } from "../lib/fiscal";

afterEach(() => vi.restoreAllMocks());
function at(path: string) {
  window.history.replaceState(null, "", path);
}
const fiscalAlert = {
  ...alert,
  id: "fiscal-alert",
  code: "E03" as const,
  operationCommandId: "real-fiscal",
};
const fiscalCommand = { ...fiscalExamples[3].command!, id: "real-fiscal" };

describe("PEEK shell", () => {
  it("exposes six real route links, uses PEEK branding and selects only the current route", async () => {
    at("/overview");
    vi.spyOn(api, "alerts").mockResolvedValue([]);
    vi.spyOn(api, "products").mockResolvedValue([]);
    render(<App />);
    const nav = screen.getByRole("navigation", { name: "Navegação principal" });
    const routes = [
      ["Visão geral", "/overview"],
      ["Produtos", "/products"],
      ["Estoque", "/inventory"],
      ["Fiscal", "/fiscal"],
      ["Central de exceções", "/exceptions"],
      ["Simulação", "/simulation"],
    ];
    expect(within(nav).getAllByRole("link")).toHaveLength(6);
    expect(
      screen.getByRole("link", { name: "PEEK · Visão geral" }),
    ).toContainHTML('src="/peek-logo.png"');
    expect(document.body).not.toHaveTextContent("PEEKio");
    const user = userEvent.setup();
    for (const [label, path] of routes) {
      const link = within(nav).getByRole("link", { name: label });
      expect(link).toHaveAttribute("href", path);
      await user.click(link);
      expect(window.location.pathname).toBe(path);
      expect(link).toHaveAttribute("aria-current", "page");
      expect(nav.querySelectorAll('[aria-current="page"]')).toHaveLength(1);
      expect(
        await screen.findByRole("heading", {
          name: label === "Simulação" ? "Simulação do MVP" : label,
          level: 1,
        }),
      ).toBeVisible();
    }
  });
  it("keeps investigation under exceptions and updates active route on popstate", async () => {
    at("/exceptions/alert-1");
    vi.spyOn(api, "investigation").mockResolvedValue(investigation());
    vi.spyOn(api, "resolveProduct").mockResolvedValue(product);
    vi.spyOn(api, "products").mockResolvedValue([]);
    render(<App />);
    expect(
      await screen.findByRole("heading", { name: alert.title }),
    ).toBeVisible();
    const nav = screen.getByRole("navigation");
    expect(
      within(nav).getByRole("link", { name: "Central de exceções" }),
    ).toHaveAttribute("aria-current", "page");
    expect(
      screen.getByText("Investigação", { selector: ".breadcrumbs span" }),
    ).toBeVisible();
    await act(async () => {
      at("/inventory");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(within(nav).getByRole("link", { name: "Estoque" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    expect(screen.getByRole("heading", { name: "Estoque" })).toBeVisible();
  });
  it("keeps product details under products, and does not route unknown pages to exceptions", async () => {
    at("/products/unknown");
    vi.spyOn(api, "context").mockRejectedValue(
      new ApiError(404, "NOT_FOUND", "Produto ausente"),
    );
    vi.spyOn(api, "propagations").mockResolvedValue([]);
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Produto ausente",
    );
    expect(screen.getByRole("link", { name: "Produtos" })).toHaveAttribute(
      "aria-current",
      "page",
    );
    await act(async () => {
      at("/unknown");
      window.dispatchEvent(new PopStateEvent("popstate"));
    });
    expect(
      screen.getByRole("heading", { name: "Página não encontrada" }),
    ).toBeVisible();
    expect(
      screen.getByRole("navigation").querySelector('[aria-current="page"]'),
    ).toBeNull();
  });
});

describe("Read-only fiscal mock", () => {
  it("shows four distinguishable fixture states without write requests or invented investigations", async () => {
    at("/fiscal");
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async () => new Response("[]", { status: 200 }));
    render(<App />);
    await screen.findByText("Nenhuma exceção fiscal disponível na demo.");
    for (const label of ["Pendente", "Confirmado", "Falhou", "Timeout"])
      expect(
        screen.getByText(label, { selector: ".fiscal-status" }),
      ).toBeVisible();
    expect(screen.getByText("DOC-DEMO-1843")).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Abrir investigação E03" }),
    ).toBeNull();
    expect(fetchMock).toHaveBeenCalled();
    for (const [, init] of fetchMock.mock.calls)
      expect(init?.method).toBe("GET");
    await userEvent.click(
      screen.getByLabelText("Mostrar exemplos ilustrativos"),
    );
    expect(screen.queryByText("EXEMPLO-FISCAL-1")).toBeNull();
  });
  it("shows a real command and its attempts, then opens the matching investigation", async () => {
    at("/fiscal");
    vi.spyOn(api, "alerts").mockResolvedValue([fiscalAlert]);
    vi.spyOn(api, "command").mockResolvedValue(fiscalCommand);
    vi.spyOn(api, "investigation").mockResolvedValue({
      ...investigation(),
      exception: { ...fiscalAlert, title: "Saída sem documento fiscal" },
    });
    vi.spyOn(api, "resolveProduct").mockResolvedValue(product);
    render(<App />);
    const row = (await screen.findByText("real-fiscal")).closest("tr")!;
    expect(row).toHaveTextContent("Timeout");
    expect(row).toHaveTextContent("01/01/2026");
    await userEvent.click(within(row).getByText("1 tentativa(s)"));
    expect(within(row).getByText("#1")).toBeVisible();
    await userEvent.click(
      within(row).getByRole("button", { name: "Abrir investigação E03" }),
    );
    expect(window.location.pathname).toBe("/exceptions/fiscal-alert");
    expect(
      await screen.findByRole("heading", {
        name: "Saída sem documento fiscal",
      }),
    ).toBeVisible();
  });
  it("retains fixtures during API failure and recovers with retry", async () => {
    at("/fiscal");
    vi.spyOn(api, "alerts")
      .mockRejectedValueOnce(
        new ApiError(503, "UNAVAILABLE", "Fiscal indisponível"),
      )
      .mockResolvedValue([]);
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Fiscal indisponível",
    );
    expect(screen.getByText("DOC-DEMO-1843")).toBeVisible();
    await userEvent.click(
      screen.getByRole("button", { name: "Tentar novamente" }),
    );
    expect(
      await screen.findByText("Nenhuma exceção fiscal disponível na demo."),
    ).toBeVisible();
  });
  it("preserves the exception link when a command cannot be loaded, without guessing its status", async () => {
    at("/fiscal");
    vi.spyOn(api, "alerts").mockResolvedValue([fiscalAlert]);
    vi.spyOn(api, "command").mockRejectedValue(
      new ApiError(404, "NOT_FOUND", "Comando ausente"),
    );
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Comando ausente",
    );
    expect(screen.getByText("Sem comando disponível")).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Abrir investigação E03" }),
    ).toBeVisible();
  });
  it("does not infer a confirmed fiscal command from a resolved exception", async () => {
    vi.spyOn(api, "alerts").mockResolvedValue([
      { ...fiscalAlert, status: "RESOLVED", operationCommandId: null },
    ]);
    const command = vi.spyOn(api, "command");
    const records = await loadFiscalRecords();
    expect(records[0].command).toBeNull();
    expect(records[0].exceptionId).toBe(fiscalAlert.id);
    expect(command).not.toHaveBeenCalled();
  });
  it("deduplicates command reads and refuses non-fiscal or mismatched commands", async () => {
    vi.spyOn(api, "alerts").mockResolvedValue([
      fiscalAlert,
      { ...fiscalAlert, id: "another-alert" },
    ]);
    const command = vi
      .spyOn(api, "command")
      .mockResolvedValue({ ...fiscalCommand, kind: "INVENTORY_SYNC" });
    let records = await loadFiscalRecords();
    expect(command).toHaveBeenCalledTimes(1);
    expect(
      records.every((record) => record.command === null && record.error),
    ).toBe(true);
    command.mockResolvedValue({ ...fiscalCommand, id: "wrong-id" });
    records = await loadFiscalRecords();
    expect(
      records.every((record) => record.command === null && record.error),
    ).toBe(true);
  });
});
