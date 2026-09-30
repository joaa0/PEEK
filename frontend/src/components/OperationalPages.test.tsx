import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { api, ApiError } from "@/lib/api";
import { context, alert, product } from "@/test/fixtures";
import App from "./App";
import { stockDiverges } from "@/lib/operational-summary";
import { runSimulation } from "@/lib/simulation";

beforeEach(() => {
  window.history.replaceState(null, "", "/exceptions");
  sessionStorage.clear();
});
afterEach(() => vi.restoreAllMocks());
function page(path: string) {
  window.history.replaceState(null, "", path);
  return render(<App />);
}
function data() {
  vi.spyOn(api, "products").mockResolvedValue([product]);
  vi.spyOn(api, "context").mockResolvedValue(context);
  vi.spyOn(api, "alerts").mockResolvedValue([alert]);
}
describe("Overview and inventory API pages", () => {
  it("loads real counts and links every card, including the fiscal screen", async () => {
    data();
    page("/overview");
    expect(screen.getByRole("status")).toHaveTextContent("Carregando");
    const section = await screen.findByRole("heading", {
      name: "Exceções",
    });
    expect(within(section.closest("section")!).getByText("1")).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Abrir produtos" }),
    ).toBeVisible();
    expect(screen.getByRole("button", { name: "Abrir estoque" })).toBeVisible();

    await userEvent.click(screen.getByRole("button", { name: "Abrir fiscal" }));
    await screen.findByRole("heading", { name: "Fiscal", level: 1 });
    expect(window.location.pathname).toBe("/fiscal");
  });
  for (const path of ["/overview", "/inventory"]) {
    it(
      path + " distinguishes empty from failure and allows retry",
      async () => {
        vi.spyOn(api, "products")
          .mockRejectedValueOnce(new ApiError(503, "FAIL", "API indisponível"))
          .mockResolvedValue([]);
        vi.spyOn(api, "alerts").mockResolvedValue([]);
        page(path);
        expect(await screen.findByRole("alert")).toHaveTextContent(
          "API indisponível",
        );
        await userEvent.click(
          screen.getByRole("button", { name: "Tentar novamente" }),
        );
        expect(
          await screen.findByText(
            path === "/overview"
              ? /Nenhum dado operacional/
              : /Nenhum produto disponível/,
          ),
        ).toBeVisible();
      },
    );
  }
  it("shows three stocks, differences and links the current E02 even after checkpoint rebasing", async () => {
    data();
    vi.mocked(api.context).mockResolvedValue({
      ...context,
      stock: {
        ...context.stock,
        expectedStock: 93,
        systemStock: 93,
        physicalStock: 93,
      },
      exceptions: [{ ...alert, code: "E02" }],
    });
    vi.spyOn(api, "investigation").mockResolvedValue({} as never);
    page("/inventory");
    const row = (await screen.findByText("CAM-001")).closest("tr")!;
    expect(within(row).getByText("Divergência")).toBeVisible();
    expect(within(row).getAllByText("93 un.")).toHaveLength(3);
    expect(
      within(row).getByRole("button", { name: "Abrir E02" }),
    ).toBeVisible();
    vi.spyOn(api, "propagations").mockResolvedValue([]);
    await userEvent.click(
      within(row).getByRole("button", { name: "Abrir produto" }),
    );
    expect(
      await screen.findByRole("heading", { name: product.name }),
    ).toBeVisible();
  });
  it("does not invent zeros or a consistent state without evidence", async () => {
    data();
    vi.mocked(api.context).mockResolvedValue({
      ...context,
      stock: {
        ...context.stock,
        expectedStock: null,
        systemStock: 0,
        physicalStock: null,
      },
    });
    page("/inventory");
    expect(await screen.findByText("Evidência incompleta")).toBeVisible();
    expect(screen.getAllByText("Não disponível")).toHaveLength(2);
    expect(screen.getByText("0 un.")).toBeVisible();
    expect(
      stockDiverges({ ...context.stock, expectedStock: 0, systemStock: 2 }),
    ).toBe(true);
  });
  it("supports sidebar and browser back navigation", async () => {
    data();
    page("/overview");
    await screen.findByRole("button", { name: "Abrir estoque" });
    await userEvent.click(screen.getByRole("link", { name: "Estoque" }));
    expect(
      await screen.findByRole("heading", { name: "Estoque" }),
    ).toBeVisible();
    window.history.replaceState(null, "", "/overview");
    window.dispatchEvent(new PopStateEvent("popstate"));
    expect(
      await screen.findByRole("heading", { name: "Visão geral" }),
    ).toBeVisible();
  });
});
describe("Simulation UI and namespace ownership", () => {
  it("uses one session namespace across navigation and offers an idempotent reset", async () => {
    const reset = vi
      .spyOn(api, "resetDemo")
      .mockResolvedValue({ runId: "demo", totalDeleted: 0 });
    const { unmount } = page("/simulation");
    await userEvent.click(
      await screen.findByRole("button", { name: "Resetar simulação" }),
    );
    expect(await screen.findByText(/Registros removidos: 0/)).toBeVisible();
    const id = reset.mock.calls[0][0];
    expect(id).toMatch(/^DEMO-/);
    unmount();
    page("/simulation");
    await userEvent.click(
      screen.getByRole("button", { name: "Resetar simulação" }),
    );
    expect(reset).toHaveBeenLastCalledWith(id);
  });
  it("disables concurrent mutations and surfaces API failures without claiming success", async () => {
    let reject!: (reason: unknown) => void;
    vi.spyOn(api, "resetDemo").mockImplementation(
      () =>
        new Promise((_resolve, fail) => {
          reject = fail;
        }),
    );
    page("/simulation");
    await userEvent.click(screen.getByRole("button", { name: "Executar E01" }));
    expect(screen.getByRole("button", { name: "Executar E02" })).toBeDisabled();
    reject(new ApiError(404, "NOT_FOUND", "Demo desabilitada"));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Demo desabilitada",
    );
    expect(
      screen.queryByText("E01 confirmado pelo backend."),
    ).not.toBeInTheDocument();
  });
  it("resets its namespace before seeding and stops when reset is unavailable", async () => {
    vi.spyOn(api, "resetDemo").mockRejectedValue(new Error("offline"));
    const create = vi.spyOn(api, "createProduct");
    await expect(runSimulation("DEMO-123456", "E01")).rejects.toThrow(
      "offline",
    );
    expect(create).not.toHaveBeenCalled();
  });
});
