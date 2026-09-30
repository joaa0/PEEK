import { describe, expect, it, vi } from "vitest";
import { PeekApi, ApiError } from "./api";
import { product } from "../test/fixtures";
describe("typed HTTP client", () => {
  it("encodes filters and mapping identifiers and sends optimistic version headers", async () => {
    const transport = vi
      .fn<typeof fetch>()
      .mockImplementation(
        async () => new Response(JSON.stringify(product), { status: 200 }),
      );
    const api = new PeekApi("/api/v1", transport);
    await api.alerts("RESOLVED", "E02");
    expect(transport.mock.calls[0][0]).toBe(
      "/api/v1/exceptions?status=RESOLVED&code=E02",
    );
    await api.resolveProduct("channel /", "id?1");
    expect(String(transport.mock.calls[1][0])).toContain(
      "channel=channel+%2F&externalId=id%3F1",
    );
    await api.updateProduct(product, product);
    expect(transport.mock.calls[2][1]?.headers).toEqual(
      expect.objectContaining({ "If-Match-Version": "0" }),
    );
  });
  it("preserves backend errors and represents network or malformed responses explicitly", async () => {
    const transport = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({ code: "CONFLICT", message: "Already mapped" }),
          { status: 409 },
        ),
      )
      .mockRejectedValueOnce(new Error("network"))
      .mockResolvedValueOnce(new Response("not-json"));
    const api = new PeekApi("/api/v1", transport);
    await expect(api.products()).rejects.toMatchObject({
      status: 409,
      code: "CONFLICT",
      message: "Already mapped",
    });
    await expect(api.products()).rejects.toMatchObject({
      code: "NETWORK_ERROR",
    });
    await expect(api.products()).rejects.toBeInstanceOf(ApiError);
  });
});

it("posts simulation payloads through the existing typed endpoints", async () => {
  const transport = vi
    .fn<typeof fetch>()
    .mockImplementation(async () => new Response("{}", { status: 200 }));
  const client = new PeekApi("/api/v1", transport);
  await client.resetDemo("DEMO-123456");
  await client.mockEvent("inventory", { onHand: 95 });
  await client.createCommand(
    "inventory-sync",
    "event",
    "demo-inventory",
    "stable-key",
  );
  await client.evaluate("2026-01-01T12:10:01Z");
  expect(transport.mock.calls.map((c) => c[0])).toEqual([
    "/api/v1/demo/reset",
    "/api/v1/mock/inventory",
    "/api/v1/commands/inventory-sync",
    "/api/v1/evaluations",
  ]);
  expect(JSON.parse(transport.mock.calls[0][1]!.body as string)).toEqual({
    runId: "DEMO-123456",
    confirmation: "RESET_DEMO",
  });
  expect(JSON.parse(transport.mock.calls[2][1]!.body as string)).toEqual({
    triggerEventId: "event",
    channel: "demo-inventory",
    idempotencyKey: "stable-key",
    simulateFailure: false,
  });
});
