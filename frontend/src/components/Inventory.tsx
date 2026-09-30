"use client";
import { useApi } from "@/lib/use-api";
import {
  inventoryDiverges,
  operationalContexts,
  stockValue,
} from "@/lib/operational-summary";
import { Card, Empty, LoadState } from "./operational-ui";

export default function Inventory({
  navigate,
}: {
  navigate: (path: string) => void;
}) {
  const request = useApi("inventory", operationalContexts);
  return (
    <>
      <header className="page-intro">
        <div>
          <div className="eyebrow">Reconciliação operacional</div>
          <h1>Estoque</h1>
          <p>
            Saldos atuais calculados pelo backend. Contagens confirmadas
            reancoram o esperado; as evidências da detecção permanecem na
            exceção.
          </p>
        </div>
        <button className="button secondary" onClick={request.reload}>
          Atualizar
        </button>
      </header>
      <Card title="Comparação por SKU">
        <LoadState {...request} />
        {request.data?.length === 0 && (
          <Empty>Nenhum produto disponível para comparar estoque.</Empty>
        )}
        {!!request.data?.length && (
          <div className="api-table-wrap">
            <table className="api-table">
              <thead>
                <tr>
                  <th>Produto / SKU</th>
                  <th>Esperado · expected_stock</th>
                  <th>Sistema · system_stock</th>
                  <th>Físico · physical_stock</th>
                  <th>Estado / diferenças</th>
                  <th>Acessos</th>
                </tr>
              </thead>
              <tbody>
                {request.data.map((c) => {
                  const s = c.stock;
                  const divergent = inventoryDiverges(c);
                  const alerts = c.exceptions.filter(
                    (a) =>
                      a.status === "OPEN" &&
                      ["E01", "E02", "E04"].includes(a.code),
                  );
                  const missing =
                    s.expectedStock === null ||
                    s.systemStock === null ||
                    s.physicalStock === null;
                  const delta = (value: number | null) =>
                    value === null || s.expectedStock === null
                      ? "Não calculável"
                      : stockValue(value - s.expectedStock);
                  return (
                    <tr
                      key={c.product.id}
                      className={divergent ? "inventory-divergent" : ""}
                    >
                      <td>
                        <strong>{c.product.sku}</strong>
                        <small>{c.product.name}</small>
                      </td>
                      <td>{stockValue(s.expectedStock)}</td>
                      <td>{stockValue(s.systemStock)}</td>
                      <td>{stockValue(s.physicalStock)}</td>
                      <td>
                        <strong
                          className={
                            "badge " +
                            (divergent
                              ? "critical"
                              : missing
                                ? "warning"
                                : "success")
                          }
                        >
                          {divergent
                            ? "Divergência"
                            : missing
                              ? "Evidência incompleta"
                              : "Saldos coincidentes"}
                        </strong>
                        <small>
                          Sistema − esperado: {delta(s.systemStock)}
                        </small>
                        <small>
                          Físico − esperado: {delta(s.physicalStock)}
                        </small>
                      </td>
                      <td>
                        <button
                          className="text-link"
                          onClick={() => navigate("/products/" + c.product.id)}
                        >
                          Abrir produto
                        </button>
                        {alerts.map((a) => (
                          <button
                            key={a.id}
                            className="text-link"
                            onClick={() => navigate("/exceptions/" + a.id)}
                          >
                            Abrir {a.code}
                          </button>
                        ))}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </>
  );
}
