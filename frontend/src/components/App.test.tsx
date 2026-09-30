import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { api, ApiError } from "../lib/api";
import {
  alert,
  context,
  investigation,
  product,
  stock,
  time,
} from "../test/fixtures";
import { OrderContext, StockComparison } from "./operational-ui";

function location(path: string) {
  window.history.replaceState(null, "", path);
}
beforeEach(() => location("/exceptions"));
afterEach(() => vi.restoreAllMocks());
describe("API-backed investigation", () => {
  it("uses API filters, preserves returned order and displays open and resolved alerts", async () => {
    const resolved = { ...alert, id: "resolved", status: "RESOLVED" as const };
    const list = vi.spyOn(api, "alerts").mockResolvedValue([alert, resolved]);
    render(<App />);
    expect(await screen.findByText("Aberta")).toBeVisible();
    expect(screen.getByText("Resolvida")).toBeVisible();
    const user = userEvent.setup();
    await user.selectOptions(screen.getByLabelText("Status"), "RESOLVED");
    await user.selectOptions(screen.getByLabelText("Tipo"), "E02");
    await waitFor(() =>
      expect(list).toHaveBeenLastCalledWith("RESOLVED", "E02"),
    );
    expect(screen.getAllByRole("row")[1]).toHaveTextContent("Aberta");
  });
  it("shows empty and API failure with retry without manufacturing alerts", async () => {
    vi.spyOn(api, "alerts")
      .mockRejectedValueOnce(
        new ApiError(503, "UNAVAILABLE", "Serviço indisponível"),
      )
      .mockResolvedValue([]);
    render(<App />);
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Serviço indisponível",
    );
    await userEvent.click(
      screen.getByRole("button", { name: "Tentar novamente" }),
    );
    expect(
      await screen.findByText("Nenhum alerta para estes filtros."),
    ).toBeVisible();
  });
  for (const code of ["E01", "E02"] as const)
    it(`${code} separates evidence, three stocks, checkpoint and static fallback`, async () => {
      location("/exceptions/alert-1");
      vi.spyOn(api, "investigation").mockResolvedValue(investigation(code));
      vi.spyOn(api, "resolveProduct").mockResolvedValue(product);
      vi.spyOn(api, "product").mockResolvedValue(product);
      render(<App />);
      expect(
        await screen.findByText("Saldo esperado · expected_stock"),
      ).toBeVisible();
      expect(screen.getByText("95 un.")).toBeVisible();
      expect(screen.getByText("100 un.")).toBeVisible();
      expect(screen.getByText("93 un.")).toBeVisible();
      expect(
        screen.getByText(/Reancora o cálculo dos eventos posteriores/),
      ).toBeVisible();
      expect(screen.getByText(/JEV indisponível/)).toBeVisible();
      expect(screen.getByText("Venda confirmada")).toBeVisible();
      if (code === "E02") {
        expect(screen.getByText(/ANTES desta contagem/)).toBeVisible();
        expect(screen.getByText(/nenhum SALE_CONFIRMED/)).toBeVisible();
      } else expect(screen.getByText("1842")).toBeVisible();
    });
  it("renders optional JEV as hypothesis after evidence with valid references", async () => {
    location("/exceptions/alert-1");
    const data = investigation();
    data.exception.jev = {
      summary: "Análise contextual",
      hypothesis: "Contagem incorreta",
      confidence: "moderada",
      evidenceIds: ["evidence-1", "invented"],
      impact: "Investigar saldo",
      recommendedAction: "Recontar",
      alternatives: ["Movimento não registrado"],
    };
    vi.spyOn(api, "investigation").mockImplementation(async () => data);
    vi.spyOn(api, "resolveProduct").mockResolvedValue(product);
    render(<App />);
    expect(await screen.findByText("JEV · hipótese, não fato")).toBeVisible();
    expect(screen.getByText(/Não é probabilidade calibrada/)).toBeVisible();
    expect(screen.getByText("Evidências citadas: evidence-1")).toBeVisible();
    expect(
      screen
        .getByText("Venda confirmada")
        .compareDocumentPosition(screen.getByText("Análise contextual")) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });
  it("does not infer stock values or order context when evidence is missing", () => {
    render(
      <>
        <StockComparison
          stock={{
            ...stock,
            expectedStock: null,
            systemStock: null,
            physicalStock: null,
            checkpointEventId: null,
          }}
        />
        <OrderContext order={null} alertId="a" />
      </>,
    );
    expect(screen.getAllByText("Não disponível")).toHaveLength(3);
    expect(screen.getByText("Sem snapshot de sistema")).toBeVisible();
    expect(screen.getByText("Sem contagem confirmada")).toBeVisible();
    expect(screen.getByText(/nenhum SALE_CONFIRMED/)).toBeVisible();
  });
  it("renders the versioned backend JEV contract directly with evidence and model ranking", async () => {
    location("/exceptions/alert-1");
    const data = investigation("E02");
    data.jev = {
      contractVersion: "1.0",
      status: "AVAILABLE",
      nature: "HYPOTHESIS_NOT_FACT",
      summary: "A causa requer conferência contextual.",
      mainHypothesis: {
        code: "COUNT_REQUIRES_VERIFICATION",
        statement: "A contagem requer revisão.",
        rationale:
          "A contagem e o delta sustentam a investigação, não provam a causa.",
        confidence: { value: 0.6, meaning: "MODEL_SELF_REPORTED_RANKING" },
        evidenceIds: ["evidence-1"],
      },
      alternatives: [],
      impact: "Saldo pode estar superestimado.",
      recommendedAction: "Recontar antes de ajustar.",
      fallbackReason: null,
    };
    const request = vi.spyOn(api, "investigation").mockResolvedValue(data);
    vi.spyOn(api, "resolveProduct").mockResolvedValue(product);
    render(<App />);
    expect(await screen.findByText("JEV · hipótese, não fato")).toBeVisible();
    expect(
      screen.getByText(/Hipótese principal: A contagem requer revisão/),
    ).toBeVisible();
    expect(
      screen.getByText(/Justificativa: A contagem e o delta/),
    ).toBeVisible();
    expect(
      screen.getByText(/Confiança informada pelo modelo: 0.6/),
    ).toHaveTextContent("Não é probabilidade calibrada.");
    expect(screen.getByText("Evidências citadas: evidence-1")).toBeVisible();
    expect(request).toHaveBeenCalledTimes(1);
    expect(screen.getByText("Venda confirmada")).toBeVisible();
  });
  it("shows TypeSafe probabilities and the PEEK explanation source without treating either as proof", async () => {
    location("/exceptions/alert-1");
    const data = investigation("E02");
    data.jev = {
      contractVersion: "1.0",
      status: "AVAILABLE",
      nature: "HYPOTHESIS_NOT_FACT",
      summary: "Jev priorizou uma hipótese de investigação.",
      mainHypothesis: {
        code: "UNEXPLAINED_DIVERGENCE",
        statement: "A causa permanece indeterminada.",
        rationale: "Esperado 95 e físico 93 não distinguem a causa.",
        confidence: { value: 0.7, meaning: "TYPESAFE_CHOICE_PROBABILITY" },
        evidenceIds: ["evidence-1"],
      },
      alternatives: [],
      impact: "O saldo exige conferência.",
      recommendedAction: "Recontar antes de agir.",
      fallbackReason: null,
      evaluation: {
        provider: "TYPESAFE",
        model: "jev-test",
        confidence: 0.4,
        probabilities: {
          UNEXPLAINED_DIVERGENCE: 0.7,
          COUNT_REQUIRES_VERIFICATION: 0.3,
        },
        explanationSource: "PEEK_EVIDENCE_TEMPLATES",
      },
    };
    vi.spyOn(api, "investigation").mockResolvedValue(data);
    vi.spyOn(api, "resolveProduct").mockResolvedValue(product);
    render(<App />);
    expect(
      await screen.findByText(
        /Probabilidade atribuída pelo Jev à hipótese: 0.7/,
      ),
    ).toHaveTextContent("não comprova a causa");
    expect(screen.getByText(/Modelo TypeSafe: jev-test/)).toHaveTextContent(
      "construídas pelo PEEK a partir das evidências",
    );
    expect(
      screen.queryByText(/Confiança informada pelo modelo/),
    ).not.toBeInTheDocument();
    expect(screen.getByText("Venda confirmada")).toBeVisible();
  });
  it("renders structured fallback without model confidence while keeping the alert actionable", async () => {
    location("/exceptions/alert-1");
    const data = investigation("E02");
    data.jev = {
      contractVersion: "1.0",
      status: "FALLBACK",
      nature: "NO_MODEL_ANALYSIS",
      summary: "Contagem 93, esperado 95. A causa requer investigação.",
      mainHypothesis: null,
      alternatives: [],
      impact: null,
      recommendedAction: data.exception.recommendation,
      fallbackReason: "TIMEOUT",
    };
    vi.spyOn(api, "investigation").mockResolvedValue(data);
    vi.spyOn(api, "resolveProduct").mockResolvedValue(product);
    render(<App />);
    expect(await screen.findByText(/JEV indisponível/)).toBeVisible();
    expect(screen.getByText(data.jev.summary)).toBeVisible();
    expect(
      screen.queryByText(/Confiança informada pelo modelo/),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Resolver alerta" }),
    ).toBeEnabled();
  });
  it("resolves canonical product through external identity and keeps missing mapping explicit", async () => {
    location("/exceptions/alert-1");
    vi.spyOn(api, "investigation").mockResolvedValue(investigation());
    const resolve = vi
      .spyOn(api, "resolveProduct")
      .mockRejectedValue(new ApiError(409, "CONFLICT", "Mapping is inactive"));
    render(<App />);
    expect(
      await screen.findByText(/Mapping ausente, inativo ou indisponível/),
    ).toBeVisible();
    expect(resolve).toHaveBeenCalledWith("demo-sales", "EXT-CAM");
    expect(screen.getByText(/Identificador externo: EXT-CAM/)).toBeVisible();
  });
  it("resolution requires note, preserves error state and refreshes resolved details", async () => {
    location("/exceptions/alert-1");
    const data = investigation();
    vi.spyOn(api, "investigation").mockImplementation(async () => data);
    vi.spyOn(api, "resolveProduct").mockResolvedValue(product);
    const resolve = vi
      .spyOn(api, "resolve")
      .mockRejectedValueOnce(new ApiError(409, "CONFLICT", "Conflict"))
      .mockImplementationOnce(async (_id, note) => {
        data.exception = {
          ...alert,
          status: "RESOLVED",
          resolutionNote: note,
          resolvedAt: time,
        };
        return data.exception;
      });
    render(<App />);
    const user = userEvent.setup();
    await user.click(
      await screen.findByRole("button", { name: "Resolver alerta" }),
    );
    const dialog = screen.getByRole("dialog");
    expect(
      within(dialog).getByRole("button", { name: "Confirmar resolução" }),
    ).toBeDisabled();
    await user.type(
      screen.getByLabelText("Nota de resolução"),
      "Contagem verificada",
    );
    await user.click(
      screen.getByRole("button", { name: "Confirmar resolução" }),
    );
    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "Conflict",
    );
    expect(screen.getByText("Aberta")).toBeVisible();
    await user.click(
      screen.getByRole("button", { name: "Confirmar resolução" }),
    );
    expect(await screen.findByText("Resolvida")).toBeVisible();
    expect(screen.getByText("Contagem verificada")).toBeVisible();
    expect(resolve).toHaveBeenLastCalledWith(alert.id, "Contagem verificada");
  });
  it("only offers eligible retry, confirms before dispatch and preserves attempt history", async () => {
    location("/exceptions/alert-1");
    const data = investigation();
    data.exception.operationCommandId = "command";
    vi.spyOn(api, "investigation").mockResolvedValue(data);
    vi.spyOn(api, "resolveProduct").mockResolvedValue(product);
    const command = {
      id: "command",
      kind: "INVENTORY_SYNC" as const,
      productId: product.id,
      mappingId: "m",
      channel: "ERP",
      status: "FAILED" as "FAILED" | "PENDING_CONFIRMATION",
      requestedAt: time,
      deadlineAt: time,
      confirmedAt: null,
      confirmationOccurredAt: null,
      confirmationEventId: null,
      externalDocumentId: null,
      lastErrorCode: "FAILED",
      expectedStock: 95,
      requestedQuantity: 5,
      attempts: [
        {
          id: "a1",
          attemptNumber: 1,
          idempotencyKey: "original",
          dispatchedAt: time,
          respondedAt: time,
          result: "FAILED",
          errorCode: "FAIL",
          errorMessage: "failure",
        },
      ],
    };
    vi.spyOn(api, "command").mockImplementation(async () => command);
    const retry = vi
      .spyOn(api, "retry")
      .mockImplementation(async (_id, key) => {
        command.status = "PENDING_CONFIRMATION";
        command.attempts.push({
          ...command.attempts[0],
          id: "a2",
          attemptNumber: 2,
          idempotencyKey: key,
          result: "ACCEPTED",
        });
        return command;
      });
    render(<App />);
    const user = userEvent.setup();
    await user.click(
      await screen.findByRole("button", { name: "Reprocessar comando" }),
    );
    expect(retry).not.toHaveBeenCalled();
    await user.click(screen.getByRole("button", { name: "Confirmar envio" }));
    expect(await screen.findByText("#2")).toBeVisible();
    expect(screen.getByText("#1")).toBeVisible();
    expect(
      screen.queryByRole("button", { name: "Reprocessar comando" }),
    ).not.toBeInTheDocument();
    expect(screen.getByText("Aberta")).toBeVisible();
  });
});
describe("Products and mappings", () => {
  it("creates through API and surfaces conflicts without silent linking", async () => {
    location("/products");
    vi.spyOn(api, "products").mockResolvedValue([]);
    const create = vi
      .spyOn(api, "createProduct")
      .mockRejectedValue(new ApiError(409, "CONFLICT", "SKU conflict"));
    render(<App />);
    const user = userEvent.setup();
    await user.click(
      await screen.findByRole("button", { name: "Cadastrar produto" }),
    );
    await user.type(screen.getByLabelText("SKU"), "NEW");
    await user.type(screen.getByLabelText("Nome"), "Novo");
    await user.click(screen.getByRole("button", { name: "Salvar produto" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Conflito: SKU conflict",
    );
    expect(create).toHaveBeenCalledWith(
      expect.objectContaining({ sku: "NEW", name: "Novo" }),
    );
  });
  it("saves a pending mapping with no external id", async () => {
    location("/products/product-1");
    vi.spyOn(api, "context").mockResolvedValue(context);
    vi.spyOn(api, "propagations").mockResolvedValue([]);
    const add = vi.spyOn(api, "addMapping").mockResolvedValue({
      id: "mapping",
      productId: product.id,
      channel: "source",
      externalId: null,
      status: "PENDING",
      version: 0,
      createdAt: time,
      updatedAt: time,
    });
    render(<App />);
    const user = userEvent.setup();
    await user.click(
      await screen.findByRole("button", { name: "Adicionar mapping" }),
    );
    await user.type(screen.getByLabelText("Canal / fonte"), "source");
    await user.selectOptions(
      screen.getByLabelText("Estado do mapping"),
      "PENDING",
    );
    expect(screen.getByLabelText("Identificador externo")).toBeDisabled();
    await user.click(screen.getByRole("button", { name: "Salvar mapping" }));
    await waitFor(() =>
      expect(add).toHaveBeenCalledWith(product.id, {
        channel: "source",
        status: "PENDING",
        externalId: null,
      }),
    );
    expect(await screen.findByText("Mapping salvo.")).toBeVisible();
  });
});
