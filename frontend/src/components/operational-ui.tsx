"use client";
import { useState, type ReactNode } from "react";
import type {
  Attempt,
  CanonicalEvent,
  Evidence,
  ExceptionCode,
  Stock,
} from "@/lib/contracts";
import { api, describeError } from "@/lib/api";
import { useApi } from "@/lib/use-api";

export const timestamp = (value: string | null | undefined) =>
  value
    ? new Intl.DateTimeFormat("pt-BR", {
        dateStyle: "short",
        timeStyle: "medium",
        timeZone: "UTC",
      }).format(new Date(value)) + " UTC"
    : "Não informado";
const statusLabels: Record<string, string> = {
  OPEN: "Aberta",
  RESOLVED: "Resolvida",
  CRITICAL: "Crítica",
  WARNING: "Atenção",
  INFO: "Informativa",
  PENDING: "Mapping pendente",
  ACTIVE: "Ativo",
  INACTIVE: "Inativo",
  NOT_AVAILABLE: "Sem comando",
  REQUESTED: "Solicitado",
  PENDING_CONFIRMATION: "Pendente de confirmação",
  FAILED: "Falha",
  TIMED_OUT: "Prazo excedido",
  CONFIRMED: "Confirmado",
  SUCCEEDED: "Sucesso",
  ACCEPTED: "Envio aceito",
};
export function Badge({ value }: { value: string }) {
  const tone = ["FAILED", "CRITICAL", "TIMED_OUT", "INACTIVE"].includes(value)
    ? "critical"
    : ["RESOLVED", "CONFIRMED", "SUCCEEDED", "ACTIVE"].includes(value)
      ? "success"
      : "warning";
  return (
    <span className={"badge " + tone}>{statusLabels[value] ?? value}</span>
  );
}
export function Card({
  title,
  children,
}: {
  title: string;
  children: ReactNode;
}) {
  return (
    <section className="card api-card">
      <h2>{title}</h2>
      {children}
    </section>
  );
}
export function LoadState({
  loading,
  error,
  reload,
}: {
  loading: boolean;
  error?: string;
  reload: () => void;
}) {
  if (loading)
    return (
      <p role="status" className="state-message">
        Carregando dados da API…
      </p>
    );
  if (error)
    return (
      <div role="alert" className="api-error">
        {error}{" "}
        <button className="button secondary" onClick={reload}>
          Tentar novamente
        </button>
      </div>
    );
  return null;
}
export function Empty({ children }: { children: ReactNode }) {
  return <p className="state-message">{children}</p>;
}
export function ConfirmAction({
  label,
  description,
  execute,
  onComplete,
}: {
  label: string;
  description: string;
  execute: (key: string) => Promise<unknown>;
  onComplete: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [key, setKey] = useState<string>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function confirm() {
    const requestKey = key ?? crypto.randomUUID();
    setKey(requestKey);
    setBusy(true);
    setError("");
    try {
      await execute(requestKey);
      setOpen(false);
      setKey(undefined);
      onComplete();
    } catch (failure) {
      setError(describeError(failure));
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <button className="button secondary" onClick={() => setOpen(true)}>
        {label}
      </button>
      {open && (
        <div className="modal-overlay">
          <section
            role="dialog"
            aria-modal="true"
            aria-label={label}
            className="api-dialog"
          >
            <h2>{label}</h2>
            <p>{description}</p>
            <p>
              O histórico será preservado. O alerta continua aberto até uma
              resolução explícita.
            </p>
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
                disabled={busy}
                className="button primary"
                onClick={confirm}
              >
                {busy ? "Enviando…" : "Confirmar envio"}
              </button>
            </div>
          </section>
        </div>
      )}
    </>
  );
}
export function AttemptHistory({ attempts }: { attempts: Attempt[] }) {
  if (!attempts.length) return <Empty>Nenhuma tentativa registrada.</Empty>;
  return (
    <div className="api-table-wrap">
      <table className="api-table">
        <thead>
          <tr>
            <th>Tentativa / chave</th>
            <th>Enviado</th>
            <th>Resposta</th>
            <th>Resultado</th>
          </tr>
        </thead>
        <tbody>
          {attempts.map((a) => (
            <tr key={a.id}>
              <td>
                #{a.attemptNumber}
                <small>{a.idempotencyKey}</small>
              </td>
              <td>{timestamp(a.dispatchedAt)}</td>
              <td>{timestamp(a.respondedAt)}</td>
              <td>
                <Badge value={a.result} />
                <small>
                  {a.errorCode && a.errorCode + ": "}
                  {a.errorMessage ?? a.externalId ?? a.externalRequestId}
                </small>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
function EventDetails({ id }: { id: string }) {
  const request = useApi(id, () => api.event(id));
  return (
    <div className="event-detail">
      <LoadState {...request} />
      {request.data && (
        <dl className="api-facts">
          <dt>Evento</dt>
          <dd>
            {request.data.type} · {request.data.id}
          </dd>
          <dt>Origem / referência</dt>
          <dd>
            {request.data.source} · {request.data.externalEventId}
          </dd>
          <dt>Ocorrido / recebido</dt>
          <dd>
            {timestamp(request.data.occurredAt)} /{" "}
            {timestamp(request.data.receivedAt)}
          </dd>
          <dt>Correlação</dt>
          <dd>
            SKU {request.data.sku ?? "ausente"} · Pedido{" "}
            {request.data.orderId ?? "ausente"} · Recebimento{" "}
            {request.data.receiptId ?? "ausente"} · Movimento{" "}
            {request.data.movementId ?? "ausente"}
          </dd>
          <dt>Quantidade</dt>
          <dd>{request.data.quantity ?? "Não informada"} un.</dd>
        </dl>
      )}
    </div>
  );
}
export function EvidenceTimeline({ evidence }: { evidence: Evidence[] }) {
  const [event, setEvent] = useState<string>();
  if (!evidence.length)
    return (
      <Empty>
        Sem evidências disponíveis. Nenhuma conclusão adicional pode ser feita.
      </Empty>
    );
  return (
    <>
      <ol className="api-timeline">
        {evidence.map((e, index) => (
          <li key={e.id + ":" + index}>
            <strong>{e.label}</strong>
            <span className="evidence-kind">
              {e.type === "CALCULATION" || e.type === "STATE"
                ? "Cálculo / estado"
                : e.type === "PARAMETER"
                  ? "Configuração"
                  : "Fato / registro"}{" "}
              · {e.type}
            </span>
            <p>{e.value}</p>
            <small>
              {e.source} · {timestamp(e.occurredAt)}
            </small>
            {e.type === "OBSERVATION" && (
              <p>
                Ausência de evento correlacionado na janela avaliada; não
                comprova a causa.
              </p>
            )}
            {e.eventId && (
              <button
                className="text-link"
                onClick={() =>
                  setEvent(event === e.eventId ? undefined : e.eventId!)
                }
              >
                Inspecionar evento {e.eventId}
              </button>
            )}
            {event === e.eventId && <EventDetails id={event!} />}
          </li>
        ))}
      </ol>
    </>
  );
}
export function OrderContext({
  order,
  alertId,
}: {
  order: CanonicalEvent | null;
  alertId: string;
}) {
  return (
    <Card title="Pedido relacionado · somente leitura">
      {order?.type === "SALE_CONFIRMED" ? (
        <dl className="api-facts">
          <dt>Pedido</dt>
          <dd>{order.orderId ?? "Referência ausente"}</dd>
          <dt>SKU / quantidade</dt>
          <dd>
            {order.sku ?? "Ausente"} · {order.quantity ?? "Não informada"} un.
          </dd>
          <dt>Origem / horário</dt>
          <dd>
            {order.source} · {timestamp(order.occurredAt)}
          </dd>
          <dt>Evidência</dt>
          <dd>SALE_CONFIRMED · {order.id}</dd>
          <dt>Alerta</dt>
          <dd>{alertId}</dd>
        </dl>
      ) : (
        <Empty>
          Contexto de venda indisponível: nenhum SALE_CONFIRMED relacionado.
        </Empty>
      )}
    </Card>
  );
}
export function StockComparison({
  stock,
  current,
  code,
  system,
  checkpoint,
  detectedAt,
}: {
  stock: Stock;
  current?: Stock;
  code?: ExceptionCode;
  system?: CanonicalEvent | null;
  checkpoint?: CanonicalEvent | null;
  detectedAt?: string;
}) {
  const numeric = (value: number | null) =>
    value === null ? "Não disponível" : value + " un.";
  const difference = (observed: number | null) =>
    observed === null || stock.expectedStock === null
      ? "Não calculável: evidência ausente"
      : observed - stock.expectedStock + " un.";
  return (
    <Card title="Três estados de estoque">
      <p>
        SKU {stock.sku} ·{" "}
        {detectedAt
          ? "Comparação na detecção: " + timestamp(detectedAt)
          : "Estado atual calculado pela API"}
      </p>
      <div className="api-stock">
        <article>
          <span>Saldo esperado · expected_stock</span>
          <strong>{numeric(stock.expectedStock)}</strong>
          <small>Cálculo PEEKio a partir dos eventos</small>
          <small>Âncora: {stock.baselineEventId ?? "Sem baseline"}</small>
        </article>
        <article className={code === "E01" ? "highlight" : ""}>
          <span>Saldo do sistema · system_stock</span>
          <strong>{numeric(stock.systemStock)}</strong>
          <small>
            {system?.source ??
              (stock.systemStock === null
                ? "Sem snapshot de sistema"
                : "Fonte não informada neste contexto")}
          </small>
          <small>{timestamp(stock.systemUpdatedAt)}</small>
        </article>
        <article className={code === "E02" ? "highlight" : ""}>
          <span>Saldo físico · physical_stock</span>
          <strong>{numeric(stock.physicalStock)}</strong>
          <small>
            {checkpoint?.source ??
              (stock.physicalStock === null
                ? "Sem contagem confirmada"
                : "Contagem física confirmada")}
          </small>
          <small>{timestamp(stock.physicalCountedAt)}</small>
        </article>
      </div>
      <p className={code === "E01" ? "comparison-focus" : ""}>
        Sistema − esperado: {difference(stock.systemStock)}
      </p>
      <p className={code === "E02" ? "comparison-focus" : ""}>
        Físico − esperado: {difference(stock.physicalStock)}
      </p>
      {stock.checkpointEventId && (
        <div className="checkpoint-note">
          Checkpoint confirmado: {stock.checkpointEventId}.
          {code === "E02"
            ? " A comparação acima usa o esperado ANTES desta contagem."
            : " Esta contagem ancora o cálculo esperado."}{" "}
          Reancora o cálculo dos eventos posteriores; o histórico anterior é
          preservado.
          {current && (
            <p>
              Saldo esperado atual após checkpoint e movimentos posteriores:{" "}
              {numeric(current.expectedStock)}.
              {current.checkpointEventId !== stock.checkpointEventId &&
                " Existe um checkpoint mais recente no estado atual: " +
                  current.checkpointEventId}
            </p>
          )}
        </div>
      )}
    </Card>
  );
}
