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
