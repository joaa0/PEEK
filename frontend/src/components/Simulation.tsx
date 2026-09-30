"use client";
import { useEffect, useState } from "react";
import { api, describeError } from "@/lib/api";
import type { DemoScenario } from "@/lib/contracts";
import { runSimulation, type SimulationResult } from "@/lib/simulation";
import { Card, Empty } from "./operational-ui";
const descriptions: Record<DemoScenario, string> = {
  Normal: "Venda, estoque, contagem e fiscal confirmados, sem exceções.",
  E01: "Venda de 5 unidades sem confirmação de estoque: esperado 95, sistema 100.",
  E02: "Contagem de 93 contra esperado 95 antes do checkpoint.",
  E03: "Saída física sem documento fiscal após o prazo do comando.",
  E04: "Recebimento de 50 unidades com registro de apenas 47.",
};
export default function Simulation({
  navigate,
}: {
  navigate: (path: string) => void;
}) {
  const [runId, setRunId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  const [result, setResult] = useState<SimulationResult>();
  useEffect(() => {
    try {
      let id = sessionStorage.getItem("peek-demo-run");
      if (!id || !/^DEMO-[A-Za-z0-9-]{6,80}$/.test(id)) {
        id = "DEMO-" + crypto.randomUUID();
        sessionStorage.setItem("peek-demo-run", id);
      }
      setRunId(id);
    } catch {
      setError(
        "Não foi possível manter a sessão de simulação neste navegador. Habilite o armazenamento da sessão e recarregue.",
      );
    }
  }, []);
  async function execute(scenario?: DemoScenario) {
    if (busy || !runId) return;
    setBusy(true);
    setError("");
    setMessage("");
    setResult(undefined);
    try {
      if (scenario) {
        const value = await runSimulation(runId, scenario);
        setResult(value);
        setMessage(
          scenario === "Normal"
            ? "Normal confirmado: nenhuma exceção gerada nesta sessão."
            : scenario + " confirmado pelo backend.",
        );
      } else {
        const value = await api.resetDemo(runId);
        setMessage(
          "Sessão resetada. Registros removidos: " + value.totalDeleted + ".",
        );
      }
    } catch (failure) {
      setError(describeError(failure));
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <header className="page-intro">
        <div>
          <div className="eyebrow">Demo do hackathon</div>
          <h1>Simulação do MVP</h1>
          <p>
            Execute cenários com eventos e comandos persistidos na API de
            demonstração.
          </p>
        </div>
      </header>
      <Card title="Sessão de demonstração">
        <p>
          Cada execução substitui os dados desta sessão. O reset remove somente
          seus produtos e registros associados.
        </p>
        <p>
          Use o backend com perfil demo em uma base de demonstração: a avaliação
          existente reconcilia todos os eventos da base.
        </p>
        <small className="demo-run">Sessão: {runId || "Preparando…"}</small>
        <div className="api-actions">
          <button
            className="button secondary"
            disabled={busy || !runId}
            onClick={() => execute()}
          >
            Resetar simulação
          </button>
        </div>
      </Card>
      {busy && (
        <p role="status" className="state-message">
          Executando na API…
        </p>
      )}
      {error && (
        <div role="alert" className="api-error">
          {error}
          <p>
            Os dados parciais da sessão podem ser removidos com Resetar
            simulação. Se a API responder 404 no reset, ative o perfil demo; o
            modo de fixtures é somente leitura.
          </p>
        </div>
      )}
      {message && (
        <p role="status" className="state-message">
          {message}
        </p>
      )}
      {result && (
        <Card title="Resultado confirmado">
          <p>{descriptions[result.scenario]}</p>
          <button
            className="text-link"
            onClick={() => navigate("/products/" + result.product.id)}
          >
            Abrir produto da simulação
          </button>
          {result.alerts.map((a) => (
            <button
              key={a.id}
              className="text-link"
              onClick={() => navigate("/exceptions/" + a.id)}
            >
              Abrir exceção {a.code}
            </button>
          ))}
          {!result.alerts.length && (
            <Empty>Nenhuma exceção nesta execução.</Empty>
          )}
        </Card>
      )}
      <div className="api-grid">
        {(Object.keys(descriptions) as DemoScenario[]).map((scenario) => (
          <Card key={scenario} title={scenario}>
            <p>{descriptions[scenario]}</p>
            <button
              className="button primary"
              disabled={busy || !runId}
              onClick={() => execute(scenario)}
            >
              Executar {scenario}
            </button>
          </Card>
        ))}
      </div>
    </>
  );
}
