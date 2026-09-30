"use client";
import { api } from "@/lib/api";
import { useApi } from "@/lib/use-api";
import {
  inventoryDiverges,
  operationalContexts,
} from "@/lib/operational-summary";
import { Card, Empty, LoadState } from "./operational-ui";

export default function Overview({
  navigate,
}: {
  navigate: (path: string) => void;
}) {
  const request = useApi("overview", async () => {
    const [contexts, alerts] = await Promise.all([
      operationalContexts(),
      api.alerts(),
    ]);
    return { contexts, alerts };
  });
  const data = request.data;
  const open = data?.alerts.filter((a) => a.status === "OPEN") ?? [];
  const fiscal =
    data?.contexts.filter((c) =>
      ["REQUESTED", "PENDING_CONFIRMATION", "FAILED", "TIMED_OUT"].includes(
        c.fiscalOrchestration.status,
      ),
    ).length ?? 0;
  return (
    <>
      <header className="page-intro">
        <div>
          <div className="eyebrow">Controle operacional</div>
          <h1>Visão geral</h1>
          <p>Resumo do estado atual informado pela API.</p>
        </div>
        <button className="button secondary" onClick={request.reload}>
          Atualizar
        </button>
      </header>
      <LoadState {...request} />
      {data && (
        <>
          {!data.contexts.length && !data.alerts.length && (
            <Empty>
              Nenhum dado operacional disponível. Cadastre produtos ou execute
              uma simulação.
            </Empty>
          )}
          <div className="api-grid">
            <Card title="Produtos">
              <p className="summary-number">{data.contexts.length}</p>
              <p>
                {data.contexts.filter((c) => c.product.active).length} ativos
              </p>
              <button
                className="text-link"
                onClick={() => navigate("/products")}
              >
                Abrir produtos
              </button>
            </Card>
            <Card title="Estoque">
              <p className="summary-number">
                {data.contexts.filter(inventoryDiverges).length}
              </p>
              <p>SKUs com divergência ou exceção de estoque aberta</p>
              <p>
                {
                  data.contexts.filter(
                    (c) =>
                      c.stock.expectedStock === null ||
                      c.stock.systemStock === null ||
                      c.stock.physicalStock === null,
                  ).length
                }{" "}
                com evidência incompleta
              </p>
              <button
                className="text-link"
                onClick={() => navigate("/inventory")}
              >
                Abrir estoque
              </button>
            </Card>
            <Card title="Fiscal">
              <p className="summary-number">{fiscal}</p>
              <p>Produtos com último comando fiscal pendente ou com falha</p>
              <p>
                {open.filter((a) => a.code === "E03").length} exceções E03
                abertas ·{" "}
                {
                  data.contexts.filter((c) => c.fiscal.status === "CONFIRMED")
                    .length
                }{" "}
                produtos com documento confirmado
              </p>
              <button className="text-link" onClick={() => navigate("/fiscal")}>
                Abrir fiscal
              </button>
            </Card>
            <Card title="Exceções">
              <p className="summary-number">{open.length}</p>
              <p>
                {open.filter((a) => a.severity === "CRITICAL").length} críticas
                · {data.alerts.filter((a) => a.status === "RESOLVED").length}{" "}
                resolvidas
              </p>
              <button
                className="text-link"
                onClick={() => navigate("/exceptions")}
              >
                Abrir exceções
              </button>
            </Card>
          </div>
        </>
      )}
    </>
  );
}
