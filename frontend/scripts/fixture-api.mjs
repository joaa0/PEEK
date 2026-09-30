import { createServer } from "node:http";
import { pathToFileURL } from "node:url";
import {
  alert,
  context,
  investigation,
  product,
  sale,
  stock,
  time,
} from "../src/test/fixtures.ts";
const resolved = {
  ...alert,
  id: "alert-2",
  code: "E02",
  title: "Physical stock divergence",
  status: "RESOLVED",
  resolutionNote: "Fixture verificada",
  resolvedAt: time,
};
export function createFixtureServer() {
  return createServer((req, res) => {
    const url = new URL(req.url, "http://127.0.0.1");
    const path = decodeURIComponent(url.pathname.replace(/^\/api\/v1/, ""));
    res.setHeader("Content-Type", "application/json");
    res.setHeader("X-PEEK-Fixture", "read-only");
    const error = (status, message) => {
      res.statusCode = status;
      res.end(
        JSON.stringify({ code: "FIXTURE_MODE", message, timestamp: time }),
      );
    };
    if (req.method !== "GET")
      return error(
        405,
        "API de fixtures somente leitura. Use o backend para salvar, resolver ou reprocessar.",
      );
    let body;
    if (path === "/products") body = [product];
    else if (path === "/products/" + product.id) body = product;
    else if (path === "/products/" + product.id + "/context") body = context;
    else if (
      path === "/products/" + product.id + "/mappings" ||
      path === "/products/" + product.id + "/propagations"
    )
      body = [];
    else if (path === "/stocks/" + product.sku) body = stock;
    else if (path === "/product-mappings/resolve") {
      if (
        url.searchParams.get("channel") !== sale.source ||
        url.searchParams.get("externalId") !== sale.externalProductId
      )
        return error(404, "Mapping de fixture ausente");
      body = product;
    } else if (path === "/exceptions")
      body = [alert, resolved].filter(
        (a) =>
          (!url.searchParams.get("status") ||
            a.status === url.searchParams.get("status")) &&
          (!url.searchParams.get("code") ||
            a.code === url.searchParams.get("code")),
      );
    else if (path === "/exceptions/alert-1/context") body = investigation();
    else if (path === "/exceptions/alert-2/context")
      body = { ...investigation("E02"), exception: resolved };
    else if (path === "/events/sale") body = sale;
    else return error(404, "Recurso não incluído nas fixtures");
    res.end(JSON.stringify(body));
  });
}
if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  createFixtureServer().listen(8081, "127.0.0.1", () =>
    console.log(
      "API de fixtures somente leitura em http://127.0.0.1:8081; configure PEEK_API_URL no Next.js.",
    ),
  );
}
