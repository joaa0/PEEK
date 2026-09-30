import type {
  Alert,
  CanonicalEvent,
  Command,
  Destination,
  ExceptionCode,
  ExceptionStatus,
  Investigation,
  Mapping,
  MappingInput,
  OperationalContext,
  Product,
  ProductInput,
  Propagation,
  Stock,
} from "./contracts";

export class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}
export function describeError(error: unknown): string {
  if (error instanceof ApiError) {
    const prefix =
      error.status === 409
        ? "Conflito: "
        : error.status === 404
          ? "Não encontrado: "
          : "";
    return prefix + error.message;
  }
  return error instanceof Error
    ? error.message
    : "Não foi possível completar a operação.";
}
export class PeekApi {
  constructor(
    private base = "/api/v1",
    private transport: typeof fetch = (...args) => fetch(...args),
  ) {}
  private async request<T>(
    path: string,
    method = "GET",
    body?: unknown,
    version?: number,
  ): Promise<T> {
    let response: Response;
    try {
      response = await this.transport(this.base + path, {
        method,
        cache: "no-store",
        headers: {
          Accept: "application/json",
          ...(body === undefined ? {} : { "Content-Type": "application/json" }),
          ...(version === undefined
            ? {}
            : { "If-Match-Version": String(version) }),
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
    } catch {
      throw new ApiError(
        0,
        "NETWORK_ERROR",
        "API indisponível. Verifique a conexão e tente novamente.",
      );
    }
    const value = await response.json().catch(() => null);
    if (!response.ok)
      throw new ApiError(
        response.status,
        value?.code ?? "HTTP_ERROR",
        value?.message ?? "A API não conseguiu concluir a operação.",
      );
    if (value === null)
      throw new ApiError(
        response.status,
        "INVALID_RESPONSE",
        "A API retornou uma resposta inválida.",
      );
    return value as T;
  }
  alerts(status?: ExceptionStatus, code?: ExceptionCode) {
    const query = new URLSearchParams();
    if (status) query.set("status", status);
    if (code) query.set("code", code);
    return this.request<Alert[]>(
      "/exceptions" + (query.size ? "?" + query : ""),
    );
  }
  alert(id: string) {
    return this.request<Alert>("/exceptions/" + encodeURIComponent(id));
  }
  investigation(id: string) {
    return this.request<Investigation>(
      "/exceptions/" + encodeURIComponent(id) + "/context",
    );
  }
  resolve(id: string, note: string) {
    return this.request<Alert>(
      "/exceptions/" + encodeURIComponent(id) + "/resolve",
      "POST",
      { note },
    );
  }
  products() {
    return this.request<Product[]>("/products");
  }
  product(id: string) {
    return this.request<Product>("/products/" + encodeURIComponent(id));
  }
  createProduct(input: ProductInput) {
    return this.request<Product>("/products", "POST", input);
  }
  updateProduct(product: Product, input: ProductInput) {
    return this.request<Product>(
      "/products/" + encodeURIComponent(product.id),
      "PUT",
      input,
      product.version,
    );
  }
  mappings(id: string) {
    return this.request<Mapping[]>(
      "/products/" + encodeURIComponent(id) + "/mappings",
    );
  }
  addMapping(id: string, input: MappingInput) {
    return this.request<Mapping>(
      "/products/" + encodeURIComponent(id) + "/mappings",
      "POST",
      input,
    );
  }
  updateMapping(mapping: Mapping, input: MappingInput) {
    return this.request<Mapping>(
      "/product-mappings/" + encodeURIComponent(mapping.id),
      "PUT",
      input,
      mapping.version,
    );
  }
  resolveProduct(channel: string, externalId: string) {
    return this.request<Product>(
      "/product-mappings/resolve?" +
        new URLSearchParams({ channel, externalId }),
    );
  }
  context(id: string) {
    return this.request<OperationalContext>(
      "/products/" + encodeURIComponent(id) + "/context",
    );
  }
  stock(sku: string) {
    return this.request<Stock>("/stocks/" + encodeURIComponent(sku));
  }
  event(id: string) {
    return this.request<CanonicalEvent>("/events/" + encodeURIComponent(id));
  }
  command(id: string) {
    return this.request<Command>("/commands/" + encodeURIComponent(id));
  }
  retry(id: string, idempotencyKey: string) {
    return this.request<Command>(
      "/commands/" + encodeURIComponent(id) + "/retry",
      "POST",
      { idempotencyKey, simulateFailure: false },
    );
  }
  propagations(id: string) {
    return this.request<Propagation[]>(
      "/products/" + encodeURIComponent(id) + "/propagations",
    );
  }
  propagate(
    product: Product,
    targets: { channel: Destination; simulateFailure: boolean }[],
    idempotencyKey: string,
  ) {
    return this.request<Propagation[]>(
      "/products/" + encodeURIComponent(product.id) + "/propagations",
      "POST",
      { productVersion: product.version, targets, idempotencyKey },
    );
  }
  retryPropagation(id: string, idempotencyKey: string) {
    return this.request<Propagation>(
      "/product-propagations/" + encodeURIComponent(id) + "/retry",
      "POST",
      { idempotencyKey, simulateFailure: false },
    );
  }
}
export const api = new PeekApi();
