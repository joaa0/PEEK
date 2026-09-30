import { api } from "./api";
import type { OperationalContext, Stock } from "./contracts";

export async function operationalContexts(): Promise<OperationalContext[]> {
  const products = await api.products();
  return Promise.all(products.map((product) => api.context(product.id)));
}
export function stockDiverges(stock: Stock) {
  return (
    stock.expectedStock !== null &&
    ((stock.systemStock !== null &&
      stock.systemStock !== stock.expectedStock) ||
      (stock.physicalStock !== null &&
        stock.physicalStock !== stock.expectedStock))
  );
}
export function inventoryDiverges(context: OperationalContext) {
  return (
    stockDiverges(context.stock) ||
    context.exceptions.some(
      (a) =>
        a.status === "OPEN" &&
        (a.code === "E01" || a.code === "E02" || a.code === "E04"),
    )
  );
}
export const stockValue = (value: number | null) =>
  value === null ? "Não disponível" : `${value} un.`;
