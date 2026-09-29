import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";
import App from "./App";

describe("PEEKio frontend mock", () => {
 it("opens a deterministic investigation and places evidence before JEV interpretation", async () => {
  const user = userEvent.setup();
  render(<App />);
  await user.click(screen.getByRole("button", { name: /Exceções/ }));
  await user.click(screen.getByText("Sincronização de estoque não confirmada"));
  await waitFor(() => expect(screen.getByRole("heading", { name: "Evidências rastreáveis" })).toBeInTheDocument());
  const evidence = screen.getByRole("heading", { name: "Evidências rastreáveis" });
  const jev = screen.getByRole("heading", { name: "Análise JEV" });
  expect(evidence.compareDocumentPosition(jev) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(screen.getByText(/Nenhum STOCK_UPDATED de 95 unidades/)).toBeInTheDocument();
  expect(screen.getByText(/regra determinística E01/)).toBeInTheDocument();
 });
 it("requires a resolution note and keeps simulated retry awaiting confirmation", async () => {
  const user = userEvent.setup();
  window.history.replaceState(null, "", "/exceptions/EX-001");
  render(<App />);
  await user.click(await screen.findByRole("button", { name: /Reprocessar/ }));
  const retryDialog = screen.getByRole("dialog");
  expect(within(retryDialog).getByText(/Shopee/)).toBeInTheDocument();
  await user.click(within(retryDialog).getByRole("button", { name: "Simular reprocessamento" }));
  await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(/permanece aberta/));
  expect(screen.getAllByText("Aberta").length).toBeGreaterThan(0);
  await user.click(screen.getByRole("button", { name: "Marcar como resolvida" }));
  const dialog = screen.getByRole("dialog");
  expect(within(dialog).getByRole("button", { name: "Marcar como resolvida" })).toBeDisabled();
  await user.type(within(dialog).getByPlaceholderText(/Descreva a verificação/), "Saldo conferido no canal.");
  await user.click(within(dialog).getByRole("button", { name: "Marcar como resolvida" }));
  await waitFor(() => expect(screen.getByText("Saldo conferido no canal.")).toBeInTheDocument());
 });
 it("creates a minimal product identity and can link an external record", async () => {
  const user = userEvent.setup();
  render(<App />);
  await user.click(screen.getByRole("button", { name: "Produtos" }));
  await user.click(screen.getByRole("button", { name: /Nova identidade/ }));
  const dialog = screen.getByRole("dialog");
  await user.type(within(dialog).getByPlaceholderText("Ex.: Camiseta Basic"), "Bolsa Demo");
  await user.type(within(dialog).getByPlaceholderText("Ex.: CAM-001"), "BOL-007");
  await user.click(within(dialog).getByRole("button", { name: "Criar identidade" }));
  await waitFor(() => expect(screen.getByRole("heading", { name: "Bolsa Demo" })).toBeInTheDocument());
  await user.click(screen.getByRole("button", { name: /Vincular sistema/ }));
  await user.type(screen.getByPlaceholderText("Identificador no sistema"), "SHO-700");
  await user.click(screen.getByRole("button", { name: "Salvar vínculo" }));
  expect(await screen.findByText("SHO-700")).toBeInTheDocument();
 });
});
