import { test } from "node:test";
import assert from "node:assert/strict";
import { createFixtureServer } from "./fixture-api.mjs";
test("fixture API starts independently, applies filters and rejects mutations", async () => {
  const server = createFixtureServer();
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const base = "http://127.0.0.1:" + server.address().port + "/api/v1";
    assert.equal(
      (await (await fetch(base + "/products")).json())[0].sku,
      "CAM-001",
    );
    const filtered = await (
      await fetch(base + "/exceptions?status=RESOLVED&code=E02")
    ).json();
    assert.equal(filtered.length, 1);
    assert.equal(filtered[0].status, "RESOLVED");
    assert.equal(
      (await fetch(base + "/exceptions?code=E04").then((r) => r.json())).length,
      0,
    );
    assert.equal(
      (await fetch(base + "/products", { method: "POST" })).status,
      405,
    );
    assert.equal(
      (
        await fetch(
          base + "/product-mappings/resolve?channel=missing&externalId=missing",
        )
      ).status,
      404,
    );
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
