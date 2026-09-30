"use client";
import { useState } from "react";
import { CheckCircle2, Clock3, Hourglass, XCircle } from "lucide-react";
import type { CommandStatus } from "@/lib/contracts";
import { fiscalExamples } from "@/lib/fiscal-fixtures";
import { loadFiscalRecords, type FiscalRecord } from "@/lib/fiscal";
import { useApi } from "@/lib/use-api";
import {
  AttemptHistory,
  Card,
  Empty,
  LoadState,
  timestamp,
} from "./operational-ui";

const states = {
  REQUESTED: { label: "Pendente", tone: "pending", icon: Clock3 },
  PENDING_CONFIRMATION: { label: "Pendente", tone: "pending", icon: Clock3 },
  CONFIRMED: { label: "Confirmado", tone: "confirmed", icon: CheckCircle2 },
  FAILED: { label: "Falhou", tone: "failed", icon: XCircle },
  TIMED_OUT: { label: "Timeout", tone: "timeout", icon: Hourglass },
} as const;

function FiscalStatus({ status }: { status: CommandStatus | undefined }) {
  if (!status) return <span className="badge">Sem comando disponível</span>;
  const { label, tone, icon: Icon } = states[status];
  return (
    <span className={"fiscal-status " + tone}>
      <Icon size={14} aria-hidden="true" />
      {label}
    </span>
  );
}

function Records({
  records,
  navigate,
}: {
  records: FiscalRecord[];
  navigate: (path: string) => void;
}) {
  return (
    <div className="api-table-wrap">
      <table className="api-table fiscal-table">
        <thead>
          <tr>
            <th>Pedido / SKU</th>
            <th>Comando / origem</th>
            <th>Status</th>
            <th>Tentativas</th>
            <th>Solicitado / prazo</th>
            <th>Confirmação / documento externo</th>
            <th>Investigação</th>
          </tr>
        </thead>
        <tbody>
          {records.map((record) => {
            const command = record.command;
            return (
              <tr key={record.id}>
                <td>
                  <strong>{record.orderId ?? "Pedido não informado"}</strong>
                  <small>{record.sku ?? "SKU não informado"}</small>
                </td>
                <td>
                  {command?.id ?? "Não disponível"}
                  <small>{command?.channel}</small>
                </td>
                <td>
                  <FiscalStatus status={command?.status} />
                  {command?.lastErrorCode && (
                    <small>{command.lastErrorCode}</small>
                  )}
                  {record.error && (
                    <p role="alert" className="api-error">
                      {record.error}
                    </p>
                  )}
                </td>
                <td>
                  {command ? (
                    <details>
                      <summary>{command.attempts.length} tentativa(s)</summary>
                      <AttemptHistory attempts={command.attempts} />
                    </details>
                  ) : (
                    "Não informado"
                  )}
                </td>
                <td>
                  {timestamp(command?.requestedAt)}
                  <small>Prazo: {timestamp(command?.deadlineAt)}</small>
                </td>
                <td>
                  {command?.externalDocumentId ?? "Sem documento confirmado"}
                  <small>
                    Confirmação recebida: {timestamp(command?.confirmedAt)}
                  </small>
                  <small>
                    Documento ocorrido:{" "}
                    {timestamp(command?.confirmationOccurredAt)}
                  </small>
                </td>
                <td>
                  {record.exceptionId ? (
                    <button
                      className="text-link"
                      onClick={() =>
                        navigate(
                          "/exceptions/" +
                            encodeURIComponent(record.exceptionId!),
                        )
                      }
                    >
                      Abrir investigação E03
                    </button>
                  ) : (
                    <small>Exemplo ilustrativo</small>
                  )}
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}

export default function Fiscal({
  navigate,
}: {
  navigate: (path: string) => void;
}) {
  const request = useApi("fiscal-e03", loadFiscalRecords);
  const [examples, setExamples] = useState(true);
  return (
    <>
      <header className="page-intro">
        <div>
          <div className="eyebrow">Operação conectada · somente consulta</div>
          <h1>Fiscal</h1>
          <p>
            Acompanhe solicitações, tentativas e confirmações do sistema fiscal
            externo.
          </p>
        </div>
        <button className="button secondary" onClick={request.reload}>
          Atualizar
        </button>
      </header>
      <div className="fiscal-notice">
        <strong>Envio aceito aguarda confirmação.</strong>
        <p>
          O documento e sua confirmação vêm do sistema externo. Esta tela não
          emite documentos nem aplica regras tributárias.
        </p>
      </div>
      <Card title="Exceções fiscais da demo">
        <p>
          Registros E03 disponíveis na API, com seus comandos vinculados. Esta
          consulta não representa todos os pedidos fiscais.
        </p>
        <LoadState {...request} />
        {request.data?.length === 0 && (
          <Empty>Nenhuma exceção fiscal disponível na demo.</Empty>
        )}
        {!!request.data?.length && (
          <Records records={request.data} navigate={navigate} />
        )}
      </Card>
      <Card title="Exemplos de estados fiscais">
        <p>
          Dados fictícios e fixos para visualizar pendente, confirmado, falhou e
          timeout. Não representam operações registradas na API.
        </p>
        <label className="api-checkbox">
          <input
            type="checkbox"
            checked={examples}
            onChange={(event) => setExamples(event.target.checked)}
          />
          Mostrar exemplos ilustrativos
        </label>
        {examples && <Records records={fiscalExamples} navigate={navigate} />}
      </Card>
    </>
  );
}
