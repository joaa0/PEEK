"use client";
import { useState } from "react";
import { api } from "@/lib/api";
import type { ExceptionCode, ExceptionStatus } from "@/lib/contracts";
import { useApi } from "@/lib/use-api";
import { Badge, Card, Empty, LoadState, timestamp } from "./operational-ui";

export default function Alerts({
  navigate,
}: {
  navigate: (path: string) => void;
}) {
  const [status, setStatus] = useState<ExceptionStatus | "">("OPEN");
  const [code, setCode] = useState<ExceptionCode | "">("");
  const request = useApi(status + code, () =>
    api.alerts(status || undefined, code || undefined),
  );
  return (
    <>
      <header className="page-intro">
        <div>
          <div className="eyebrow">Controle operacional</div>
          <h1>Central de exceções</h1>
          <p>Investigue desvios com evidências e ações explícitas.</p>
        </div>
        <button className="button secondary" onClick={request.reload}>
          Atualizar
        </button>
      </header>
      <Card title="Alertas">
        <div className="api-filters">
          <label>
            Status
            <select
              value={status}
              onChange={(e) =>
                setStatus(e.target.value as ExceptionStatus | "")
              }
            >
              <option value="">Todos</option>
              <option value="OPEN">Abertas</option>
              <option value="RESOLVED">Resolvidas</option>
            </select>
          </label>
          <label>
            Tipo
            <select
              value={code}
              onChange={(e) => setCode(e.target.value as ExceptionCode | "")}
            >
              <option value="">Todos</option>
              {["E01", "E02", "E03", "E04"].map((value) => (
                <option key={value}>{value}</option>
              ))}
            </select>
          </label>
          <p>Mais recentes primeiro · ordenação fornecida pela API</p>
        </div>
        <LoadState {...request} />
        {request.data?.length === 0 && (
          <Empty>Nenhum alerta para estes filtros.</Empty>
        )}
        {!!request.data?.length && (
          <div className="api-table-wrap">
            <table className="api-table">
              <thead>
                <tr>
                  <th>Tipo / problema</th>
                  <th>Severidade</th>
                  <th>SKU / pedido</th>
                  <th>Detectado</th>
                  <th>Status</th>
                  <th>Investigação</th>
                </tr>
              </thead>
              <tbody>
                {request.data.map((alert) => (
                  <tr key={alert.id}>
                    <td>
                      <strong>{alert.code}</strong> · {alert.title}
                    </td>
                    <td>
                      <Badge value={alert.severity} />
                    </td>
                    <td>
                      {alert.sku ?? "SKU ausente"}
                      <small>Pedido {alert.orderId ?? "não informado"}</small>
                    </td>
                    <td>{timestamp(alert.detectedAt)}</td>
                    <td>
                      <Badge value={alert.status} />
                    </td>
                    <td>
                      <button
                        className="text-link"
                        onClick={() => navigate("/exceptions/" + alert.id)}
                      >
                        Abrir {alert.code}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
