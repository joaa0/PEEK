"use client";
import { useState, type FormEvent } from "react";
import { api, describeError } from "@/lib/api";
import type {
  Destination,
  Mapping,
  MappingStatus,
  Product,
  ProductInput,
  Propagation,
} from "@/lib/contracts";
import { useApi } from "@/lib/use-api";
import { CommandInvestigation } from "./Investigation";
import {
  AttemptHistory,
  Badge,
  Card,
  ConfirmAction,
  Empty,
  EvidenceTimeline,
  LoadState,
  StockComparison,
  timestamp,
} from "./operational-ui";

type Navigate = (path: string) => void;
function ProductEditor({
  product,
  complete,
  cancel,
}: {
  product?: Product;
  complete: (product: Product) => void;
  cancel: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setBusy(true);
    const values = new FormData(event.currentTarget);
    const input: ProductInput = {
      sku: String(values.get("sku")).trim(),
      name: String(values.get("name")).trim(),
      description: String(values.get("description") || "") || null,
      gtin: String(values.get("gtin") || "") || null,
      category: String(values.get("category") || "") || null,
      price: values.get("price") ? Number(values.get("price")) : null,
    };
    try {
      complete(
        product
          ? await api.updateProduct(product, input)
          : await api.createProduct(input),
      );
    } catch (failure) {
      setError(describeError(failure));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Card
      title={product ? "Editar produto canônico" : "Cadastrar produto canônico"}
    >
      <form onSubmit={submit} className="api-form">
        <label>
          SKU
          <input
            name="sku"
            required
            maxLength={100}
            readOnly={!!product}
            defaultValue={product?.sku}
          />
        </label>
        <label>
          Nome
          <input
            name="name"
            required
            maxLength={200}
            defaultValue={product?.name}
          />
        </label>
        <label>
          Descrição básica
          <textarea
            name="description"
            defaultValue={product?.description ?? ""}
          />
        </label>
        <label>
          Preço de referência
          <input
            name="price"
            type="number"
            min="0"
            step="0.01"
            defaultValue={product?.price ?? ""}
          />
        </label>
        <label>
          GTIN / EAN
          <input
            name="gtin"
            maxLength={32}
            defaultValue={product?.gtin ?? ""}
          />
        </label>
        <label>
          Categoria básica
          <input
            name="category"
            maxLength={100}
            defaultValue={product?.category ?? ""}
          />
        </label>
        {error && (
          <p className="api-error" role="alert">
            {error}
          </p>
        )}
        <div className="api-actions">
          <button
            type="button"
            disabled={busy}
            className="button secondary"
            onClick={cancel}
          >
            Cancelar
          </button>
          <button disabled={busy} className="button primary">
            {busy ? "Salvando…" : "Salvar produto"}
          </button>
        </div>
      </form>
    </Card>
  );
}
function MappingEditor({
  productId,
  mapping,
  complete,
  cancel,
}: {
  productId: string;
  mapping?: Mapping;
  complete: () => void;
  cancel: () => void;
}) {
  const [status, setStatus] = useState<MappingStatus>(
    mapping?.status ?? "ACTIVE",
  );
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setBusy(true);
    setError("");
    const values = new FormData(event.currentTarget);
    const input = {
      channel: String(values.get("channel")).trim(),
      status,
      externalId:
        status === "PENDING"
          ? null
          : String(values.get("externalId") || "").trim(),
    };
    try {
      if (mapping) await api.updateMapping(mapping, input);
      else await api.addMapping(productId, input);
      complete();
    } catch (failure) {
      setError(describeError(failure));
    } finally {
      setBusy(false);
    }
  }
  return (
    <form className="api-form mapping-editor" onSubmit={submit}>
      <h3>{mapping ? "Editar mapping" : "Novo mapping"}</h3>
      <label>
        Canal / fonte
        <input
          required
          name="channel"
          maxLength={100}
          readOnly={!!mapping}
          defaultValue={mapping?.channel}
        />
      </label>
      <label>
        Estado do mapping
        <select
          value={status}
          onChange={(e) => setStatus(e.target.value as MappingStatus)}
        >
          <option value="ACTIVE">Ativo</option>
          <option value="PENDING">Pendente</option>
          <option value="INACTIVE">Inativo</option>
        </select>
      </label>
      <label>
        Identificador externo
        <input
          name="externalId"
          required={status !== "PENDING"}
          disabled={status === "PENDING"}
          maxLength={200}
          defaultValue={mapping?.externalId ?? ""}
        />
      </label>
      <p>
        Pendente não possui ID externo. Inativo não pode resolver eventos nem
        receber propagação.
      </p>
      {error && (
        <p role="alert" className="api-error">
          {error}
        </p>
      )}
      <div className="api-actions">
        <button
          className="button secondary"
          disabled={busy}
          type="button"
          onClick={cancel}
        >
          Cancelar mapping
        </button>
        <button disabled={busy} className="button primary">
          {busy ? "Salvando…" : "Salvar mapping"}
        </button>
      </div>
    </form>
  );
}
function PropagationRow({
  row,
  reload,
}: {
  row: Propagation;
  reload: () => void;
}) {
  const [evidence, setEvidence] = useState(false);
  return (
    <article className="propagation-row">
      <h3>
        {row.channel} · {row.operation} · <Badge value={row.status} />
      </h3>
      <p>
        Comando {row.id} · Versão do produto {row.productVersion}
      </p>
      <p>
        Solicitado: {timestamp(row.requestedAt)} · Resultado:{" "}
        {timestamp(row.completedAt)}
      </p>
      <p>ID externo retornado: {row.externalId ?? "Ainda não retornado"}</p>
      <AttemptHistory attempts={row.attempts} />
      <div className="api-actions">
        <button
          className="text-link"
          onClick={() => setEvidence((value) => !value)}
        >
          Evidências da propagação
        </button>
        {row.status === "FAILED" && (
          <ConfirmAction
            label={"Reprocessar " + row.channel}
            description={
              "Reenviar a versão " +
              row.productVersion +
              " para " +
              row.channel +
              "?"
            }
            execute={(key) => api.retryPropagation(row.id, key)}
            onComplete={reload}
          />
        )}
      </div>
      {evidence && <EvidenceTimeline evidence={row.evidence} />}
    </article>
  );
}
function Propagations({
  product,
  reloadContext,
}: {
  product: Product;
  reloadContext: () => void;
}) {
  const request = useApi(product.id + product.version, () =>
    api.propagations(product.id),
  );
  const [targets, setTargets] = useState<Destination[]>([]);
  const [failShopee, setFailShopee] = useState(false);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [key, setKey] = useState<string>();
  const [result, setResult] = useState<Propagation[]>();
  async function dispatch() {
    const requestKey = key ?? crypto.randomUUID();
    setKey(requestKey);
    setBusy(true);
    setError("");
    try {
      const rows = await api.propagate(
        product,
        targets.map((channel) => ({
          channel,
          simulateFailure: channel === "SHOPEE" && failShopee,
        })),
        requestKey,
      );
      setResult(rows);
      setOpen(false);
      setKey(undefined);
      request.reload();
      reloadContext();
    } catch (failure) {
      setError(describeError(failure));
    } finally {
      setBusy(false);
    }
  }
  return (
    <Card title="Distribuição simulada por destino">
      <p>
        Selecione os destinos. Cada destino mantém comando, resultado e
        tentativas próprios.
      </p>
      <div className="api-filters">
        {(["ERP", "MERCADO_LIVRE", "SHOPEE"] as Destination[]).map(
          (channel) => (
            <label className="api-checkbox" key={channel}>
              <input
                type="checkbox"
                checked={targets.includes(channel)}
                onChange={(e) => {
                  setKey(undefined);
                  setTargets((values) =>
                    e.target.checked
                      ? [...values, channel]
                      : values.filter((value) => value !== channel),
                  );
                }}
              />
              {channel}
            </label>
          ),
        )}
      </div>
      <label className="api-checkbox">
        <input
          type="checkbox"
          checked={failShopee}
          onChange={(e) => {
            setKey(undefined);
            setFailShopee(e.target.checked);
          }}
        />
        Simular falha da Shopee nesta distribuição
      </label>
      <button
        disabled={!targets.length}
        className="button primary"
        onClick={() => {
          setError("");
          setOpen(true);
        }}
      >
        Distribuir produto
      </button>
      {result && (
        <p role="status">
          Resultado por destino:{" "}
          {result.map((row) => row.channel + " " + row.status).join(" · ")}
        </p>
      )}
      {open && (
        <div className="modal-overlay">
          <section
            role="dialog"
            aria-modal="true"
            aria-label="Distribuir produto"
            className="api-dialog"
          >
            <h2>Confirmar distribuição</h2>
            <p>
              Enviar {product.sku}, versão {product.version}, para{" "}
              {targets.join(", ")}?
            </p>
            <p>
              Os efeitos externos são simulados. O backend preserva cada comando
              e tentativa.
            </p>
            {error && (
              <p role="alert" className="api-error">
                {error}
              </p>
            )}
            <div className="api-actions">
              <button
                className="button secondary"
                disabled={busy}
                onClick={() => setOpen(false)}
              >
                Cancelar
              </button>
              <button
                className="button primary"
                disabled={busy}
                onClick={dispatch}
              >
                {busy ? "Enviando…" : "Confirmar distribuição"}
              </button>
            </div>
          </section>
        </div>
      )}
      <LoadState {...request} />
      {request.data?.length === 0 && (
        <Empty>Nenhuma distribuição registrada.</Empty>
      )}
      {request.data?.map((row) => (
        <PropagationRow
          key={row.id}
          row={row}
          reload={() => {
            request.reload();
            reloadContext();
          }}
        />
      ))}
    </Card>
  );
}
export function ProductDetail({
  id,
  navigate,
}: {
  id: string;
  navigate: Navigate;
}) {
  const request = useApi(id, () => api.context(id));
  const [editing, setEditing] = useState(false);
  const [mapping, setMapping] = useState<Mapping | "new">();
  const [notice, setNotice] = useState("");
  const data = request.data;
  return (
    <>
      <button className="text-link" onClick={() => navigate("/products")}>
        ← Voltar aos produtos
      </button>
      <LoadState {...request} />
      {data && (
        <>
          <header className="page-intro">
            <div>
              <div className="eyebrow">Produto canônico e operação</div>
              <h1>{data.product.name}</h1>
              <p>
                {data.product.sku} · {data.product.id}
              </p>
            </div>
            <button
              className="button secondary"
              onClick={() => setEditing(true)}
            >
              Editar produto
            </button>
          </header>
          {notice && <p role="status">{notice}</p>}
          {editing && (
            <ProductEditor
              product={data.product}
              cancel={() => setEditing(false)}
              complete={() => {
                setEditing(false);
                setNotice("Produto atualizado.");
                request.reload();
              }}
            />
          )}
          <Card title="Identidade e mappings">
            <p>
              {data.product.description ?? "Sem descrição"} · GTIN{" "}
              {data.product.gtin ?? "não informado"} · Categoria{" "}
              {data.product.category ?? "não informada"}
            </p>
            <button
              className="button secondary"
              onClick={() => setMapping("new")}
            >
              Adicionar mapping
            </button>
            {data.mappings.length === 0 && (
              <Empty>
                Sem mapping. Associe uma fonte ou selecione um destino para
                distribuição.
              </Empty>
            )}
            <div className="api-table-wrap">
              <table className="api-table">
                <thead>
                  <tr>
                    <th>Canal</th>
                    <th>ID externo</th>
                    <th>Estado</th>
                    <th>Atualizado</th>
                    <th>Ação</th>
                  </tr>
                </thead>
                <tbody>
                  {data.mappings.map((item) => (
                    <tr key={item.id}>
                      <td>{item.channel}</td>
                      <td>{item.externalId ?? "Ausente: mapping pendente"}</td>
                      <td>
                        <Badge value={item.status} />
                      </td>
                      <td>{timestamp(item.updatedAt)}</td>
                      <td>
                        <button
                          className="text-link"
                          onClick={() => setMapping(item)}
                        >
                          Editar mapping {item.channel}
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {mapping && (
              <MappingEditor
                key={mapping === "new" ? "new" : mapping.id}
                productId={id}
                mapping={mapping === "new" ? undefined : mapping}
                cancel={() => setMapping(undefined)}
                complete={() => {
                  setMapping(undefined);
                  setNotice("Mapping salvo.");
                  request.reload();
                }}
              />
            )}
          </Card>
          <StockComparison stock={data.stock} />
          <div className="api-grid">
            {[
              { title: "Estoque", slot: data.inventorySync },
              { title: "Fiscal", slot: data.fiscalOrchestration },
            ].map(({ title, slot }) => (
              <Card key={title} title={"Sincronização · " + title}>
                <Badge value={slot.status} />
                <dl className="api-facts">
                  <dt>Destino</dt>
                  <dd>
                    {slot.channel ??
                      (data.mappings.length
                        ? "Sem comando para os mappings cadastrados"
                        : "Sem mapping")}
                  </dd>
                  <dt>Comando enviado</dt>
                  <dd>{timestamp(slot.requestedAt)}</dd>
                  <dt>Última tentativa</dt>
                  <dd>{timestamp(slot.lastAttemptAt)}</dd>
                  <dt>Confirmação recebida</dt>
                  <dd>{timestamp(slot.confirmedAt)}</dd>
                </dl>
                {slot.commandId && (
                  <CommandInvestigation
                    id={slot.commandId}
                    onChange={request.reload}
                  />
                )}
              </Card>
            ))}
          </div>
          <Card title="Confirmação fiscal">
            <p>
              Documento:{" "}
              {data.fiscal.documentId ?? "Nenhum documento confirmado"}
            </p>
            <p>
              Emissão: {timestamp(data.fiscal.documentOccurredAt)} · Recebimento
              da confirmação: {timestamp(data.fiscal.confirmedAt)}
            </p>
            <p>
              Evento de evidência: {data.fiscal.evidenceEventId ?? "Ausente"}
            </p>
          </Card>
          <Card title="Exceções relacionadas">
            {data.exceptions.length === 0 ? (
              <Empty>Nenhuma exceção relacionada.</Empty>
            ) : (
              data.exceptions.map((alert) => (
                <p key={alert.id}>
                  <button
                    className="text-link"
                    onClick={() => navigate("/exceptions/" + alert.id)}
                  >
                    {alert.code} · {alert.title}
                  </button>{" "}
                  <Badge value={alert.status} />
                </p>
              ))
            )}
          </Card>
          <Propagations product={data.product} reloadContext={request.reload} />
        </>
      )}
    </>
  );
}
export default function Products({ navigate }: { navigate: Navigate }) {
  const request = useApi("products", () => api.products());
  const [creating, setCreating] = useState(false);
  const [search, setSearch] = useState("");
  const filtered = request.data?.filter((product) =>
    (product.sku + " " + product.name)
      .toLocaleLowerCase()
      .includes(search.toLocaleLowerCase()),
  );
  return (
    <>
      <header className="page-intro">
        <div>
          <div className="eyebrow">Identidade operacional</div>
          <h1>Produtos e mappings</h1>
          <p>Cadastro canônico mínimo e status por sistema.</p>
        </div>
        <button className="button primary" onClick={() => setCreating(true)}>
          Cadastrar produto
        </button>
      </header>
      {creating && (
        <ProductEditor
          cancel={() => setCreating(false)}
          complete={(product) => navigate("/products/" + product.id)}
        />
      )}
      <Card title="Produtos">
        <label className="api-search">
          Buscar SKU ou nome
          <input value={search} onChange={(e) => setSearch(e.target.value)} />
        </label>
        <LoadState {...request} />
        {filtered?.length === 0 && <Empty>Nenhum produto encontrado.</Empty>}
        {!!filtered?.length && (
          <div className="api-table-wrap">
            <table className="api-table">
              <thead>
                <tr>
                  <th>SKU</th>
                  <th>Nome</th>
                  <th>Atualizado</th>
                  <th>Operação</th>
                </tr>
              </thead>
              <tbody>
                {filtered.map((product) => (
                  <tr key={product.id}>
                    <td>{product.sku}</td>
                    <td>{product.name}</td>
                    <td>{timestamp(product.updatedAt)}</td>
                    <td>
                      <button
                        className="text-link"
                        onClick={() => navigate("/products/" + product.id)}
                      >
                        Abrir {product.sku}
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
