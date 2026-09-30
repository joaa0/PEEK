"use client";
import { useState } from "react";
import { api, describeError } from "@/lib/api";
import type { Alert, CanonicalEvent } from "@/lib/contracts";
import { useApi } from "@/lib/use-api";
import {
  AttemptHistory,
  Badge,
  Card,
  ConfirmAction,
  Empty,
  EvidenceTimeline,
  LoadState,
  OrderContext,
  StockComparison,
  timestamp,
} from "./operational-ui";

function ProductContext({
  trigger,
  navigate,
}: {
  trigger: CanonicalEvent;
  navigate: (path: string) => void;
}) {
  const request = useApi(trigger.id, async () => {
    if (trigger.externalProductId)
      return api.resolveProduct(trigger.source, trigger.externalProductId);
    if (trigger.productId) return api.product(trigger.productId);
    return null;
  });
  return (
    <Card title="Produto canônico">
      <p>
        Origem: {trigger.source} · Identificador externo:{" "}
        {trigger.externalProductId ?? "Não informado"}
      </p>
      <LoadState loading={request.loading} reload={request.reload} />
      {request.error && (
        <p role="alert" className="api-error">
          Mapping ausente, inativo ou indisponível: {request.error}
          <button className="text-link" onClick={request.reload}>
            Consultar novamente
          </button>
        </p>
      )}
      {request.data === null && (
        <Empty>Produto não identificado. Nenhum vínculo foi inferido.</Empty>
      )}
      {request.data && (
        <>
          <strong>
            {request.data.sku} · {request.data.name}
          </strong>
          <p>
            <button
              className="text-link"
              onClick={() => navigate("/products/" + request.data!.id)}
            >
              Abrir produto e mappings
            </button>
          </p>
        </>
      )}
    </Card>
  );
}
function ResolveAlert({
  alert,
  onComplete,
}: {
  alert: Alert;
  onComplete: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function resolve() {
    setBusy(true);
    setError("");
    try {
      await api.resolve(alert.id, note.trim());
      setOpen(false);
      onComplete();
    } catch (failure) {
      setError(describeError(failure));
    } finally {
      setBusy(false);
    }
  }
  if (alert.status === "RESOLVED")
    return (
      <Card title="Resolução">
        <p>{alert.resolutionNote}</p>
        <small>Resolvida em {timestamp(alert.resolvedAt)}</small>
        {alert.reconciledAt && alert.reconciliationEventId ? (
          <p>
            Confirmada pelo motor de reconciliação em{" "}
            {timestamp(alert.reconciledAt)}. Evento de prova:{" "}
            {alert.reconciliationEventId}.
          </p>
        ) : (
          <p>
            Resolução manual registrada. Não representa prova de confirmação
            externa.
          </p>
        )}
      </Card>
    );
  return (
    <>
      <button className="button primary" onClick={() => setOpen(true)}>
        Resolver alerta
      </button>
      {open && (
        <div className="modal-overlay">
          <section
            className="api-dialog"
            role="dialog"
            aria-modal="true"
            aria-label="Resolver alerta"
          >
            <h2>Resolver alerta</h2>
            <p>
              Registre o que foi verificado. Resolver não altera os eventos nem
              confirma comandos externos.
            </p>
            <label>
              Nota de resolução
              <textarea
                value={note}
                onChange={(e) => setNote(e.target.value)}
                required
              />
            </label>
            {error && (
              <p role="alert" className="api-error">
                {error}
              </p>
            )}
            <div className="api-actions">
              <button
                disabled={busy}
                className="button secondary"
                onClick={() => setOpen(false)}
              >
                Cancelar
              </button>
              <button
                disabled={busy || !note.trim()}
                className="button primary"
                onClick={resolve}
              >
                {busy ? "Salvando…" : "Confirmar resolução"}
              </button>
            </div>
          </section>
        </div>
      )}
    </>
  );
}
export function CommandInvestigation({
  id,
  onChange,
}: {
  id: string;
  onChange?: () => void;
}) {
  const request = useApi(id, () => api.command(id));
  const command = request.data;
  return (
    <Card title="Execução e histórico de tentativas">
      <LoadState {...request} />
      {command && (
        <>
          <p>
            {command.kind} · {command.channel} ·{" "}
            <Badge value={command.status} />
          </p>
          <dl className="api-facts">
            <dt>Comando enviado</dt>
            <dd>{timestamp(command.requestedAt)}</dd>
            <dt>Prazo da confirmação</dt>
            <dd>{timestamp(command.deadlineAt)}</dd>
            <dt>Confirmação recebida</dt>
            <dd>{timestamp(command.confirmedAt)}</dd>
            <dt>Evento / documento externo</dt>
            <dd>
              {command.confirmationEventId ?? "Sem evento de confirmação"} /{" "}
              {command.externalDocumentId ?? "Sem documento"}
            </dd>
          </dl>
          <p>Envio aceito não significa confirmação externa.</p>
          <AttemptHistory attempts={command.attempts} />
          {["FAILED", "TIMED_OUT"].includes(command.status) && (
            <ConfirmAction
              label="Reprocessar comando"
              description={
                "Enviar uma nova tentativa para " + command.channel + "?"
              }
              execute={(key) => api.retry(id, key)}
              onComplete={() => {
                request.reload();
                onChange?.();
              }}
            />
          )}
        </>
      )}
    </Card>
  );
}
export default function Investigation({
  id,
  navigate,
}: {
  id: string;
  navigate: (path: string) => void;
}) {
  const request = useApi(id, () => api.investigation(id));
  const data = request.data;
  const alert = data?.exception;
  const analysis = data?.jev;
  return (
    <>
      <button className="text-link" onClick={() => navigate("/exceptions")}>
        ← Voltar às exceções
      </button>
      <LoadState {...request} />
      {data && alert && (
        <>
          <header className="page-intro">
            <div>
              <div className="eyebrow">Investigação · {alert.code}</div>
              <h1>{alert.title}</h1>
              <p>
                {alert.id} · {timestamp(alert.detectedAt)}
              </p>
            </div>
            <div className="api-actions">
              <Badge value={alert.severity} />
              <Badge value={alert.status} />
            </div>
          </header>
          <div className="api-grid">
            <Card title="Regra determinística e impacto">
              <dl className="api-facts">
                <dt>Esperado pela regra</dt>
                <dd>{alert.expectedState}</dd>
                <dt>Registrado na detecção</dt>
                <dd>{alert.observedState}</dd>
                <dt>Configuração aplicada</dt>
                <dd>{alert.ruleParameter}</dd>
                <dt>Impacto</dt>
                <dd>{alert.impact ?? "Não informado"}</dd>
              </dl>
              <p>
                Detecção por regra. Uma causa não é comprovada pela divergência.
              </p>
            </Card>
            <ProductContext trigger={data.trigger} navigate={navigate} />
          </div>
          <StockComparison
            stock={data.stockAtDetection}
            current={data.currentStock}
            code={alert.code}
            system={data.systemSnapshot}
            checkpoint={data.physicalCheckpoint}
            detectedAt={alert.detectedAt}
          />
          <OrderContext order={data.order} alertId={alert.id} />
          <Card title="Evidências da detecção · fatos e cálculos">
            <EvidenceTimeline evidence={alert.evidence} />
          </Card>
          {alert.operationCommandId && (
            <CommandInvestigation
              id={alert.operationCommandId}
              onChange={request.reload}
            />
          )}
          <Card title="Recomendação">
            <p>{alert.recommendation}</p>
            {analysis?.status === "AVAILABLE" && analysis.mainHypothesis ? (
              <section className="jev-hypothesis">
                <h3>JEV · hipótese, não fato</h3>
                <p>{analysis.summary}</p>
                <p>Hipótese principal: {analysis.mainHypothesis.statement}</p>
                <p>Justificativa: {analysis.mainHypothesis.rationale}</p>
                {analysis.mainHypothesis.confidence.meaning ===
                "TYPESAFE_CHOICE_PROBABILITY" ? (
                  <p>
                    Probabilidade atribuída pelo Jev à hipótese:{" "}
                    {analysis.mainHypothesis.confidence.value}. O valor compara
                    as opções avaliadas e não comprova a causa.
                  </p>
                ) : (
                  <p>
                    Confiança informada pelo modelo:{" "}
                    {analysis.mainHypothesis.confidence.value}. Não é
                    probabilidade calibrada.
                  </p>
                )}
                {analysis.evaluation && (
                  <p>
                    Modelo TypeSafe: {analysis.evaluation.model}. Explicação e
                    recomendação construídas pelo PEEK a partir das evidências.
                  </p>
                )}
                <p>Impacto sugerido: {analysis.impact}</p>
                <p>Ação sugerida: {analysis.recommendedAction}</p>
                <p>
                  Evidências citadas:{" "}
                  {analysis.mainHypothesis.evidenceIds
                    .filter((e) => alert.evidence.some((item) => item.id === e))
                    .join(", ") || "Sem referências válidas"}
                </p>
                {analysis.alternatives.length > 0 && (
                  <ul>
                    {analysis.alternatives.map((alternative) => (
                      <li key={alternative.code}>
                        Hipótese alternativa: {alternative.statement} ·{" "}
                        {alternative.rationale} ·{" "}
                        {alternative.confidence.meaning ===
                        "TYPESAFE_CHOICE_PROBABILITY"
                          ? "Probabilidade atribuída pelo Jev: "
                          : "Ranking do modelo: "}
                        {alternative.confidence.value} · Evidências:{" "}
                        {alternative.evidenceIds
                          .filter((e) =>
                            alert.evidence.some((item) => item.id === e),
                          )
                          .join(", ")}
                      </li>
                    ))}
                  </ul>
                )}
              </section>
            ) : alert.jev && alert.evidence.length > 0 ? (
              <section className="jev-hypothesis">
                <h3>JEV · hipótese, não fato</h3>
                <p>{alert.jev.summary}</p>
                <p>Hipótese: {alert.jev.hypothesis}</p>
                <p>
                  Confiança informada pelo modelo: {alert.jev.confidence}. Não é
                  probabilidade calibrada.
                </p>
                <p>Impacto sugerido: {alert.jev.impact}</p>
                <p>Ação sugerida: {alert.jev.recommendedAction}</p>
                <p>
                  Evidências citadas:{" "}
                  {alert.jev.evidenceIds
                    .filter((e) => alert.evidence.some((item) => item.id === e))
                    .join(", ") || "Sem referências válidas"}
                </p>
                <ul>
                  {alert.jev.alternatives.map((value, index) => (
                    <li key={index}>{value}</li>
                  ))}
                </ul>
              </section>
            ) : (
              <>
                <p>
                  JEV indisponível. A recomendação estática e as evidências
                  continuam disponíveis.
                </p>
                {analysis?.status === "FALLBACK" && <p>{analysis.summary}</p>}
              </>
            )}
          </Card>
          <ResolveAlert alert={alert} onComplete={request.reload} />
        </>
      )}
    </>
  );
}
