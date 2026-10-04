// @unit
import { describe, expect, it, vi } from "vitest";
import * as api from "./finpayService";
import type { PaymentRequest, Session } from "@/lib/ApiSchemas";

const id = "00000000-0000-4000-8000-000000000001";
const target = "00000000-0000-4000-8000-000000000002";
const key = "00000000-0000-4000-8000-000000000003";
const session: Session = { userId: "alice", displayName: "Alice", roles: ["CUSTOMER"], csrfToken: "csrf" };
const wallet = { id, ownerId: "alice", currency: "INR", balanceMinor: 10000, version: 0 };
const request: PaymentRequest = { sourceWalletId: id, targetWalletId: target, amountMinor: 101, currency: "INR", description: "Lunch", purpose: "MERCHANT" };
const payment = { ...request, id: key, ownerId: "alice", status: "PENDING", createdAt: "2026-09-30T10:00:00Z" };
const report = { month: "2026-09", currency: "INR", categories: [], classification: "RULE_BASED", consistency: "EVENTUALLY_CONSISTENT" };

const cases = [
  { name: "getSession", invoke: () => api.getSession(), body: session },
  { name: "createWallet", invoke: () => api.createWallet(session), body: wallet },
  { name: "getWallet", invoke: () => api.getWallet(id), body: wallet },
  { name: "getPayments", invoke: () => api.getPayments(), body: [payment] },
  { name: "getPayment", invoke: () => api.getPayment(key), body: payment },
  { name: "createPayment", invoke: () => api.createPayment(session, key, request), body: payment },
  { name: "getNotifications", invoke: () => api.getNotifications(), body: [{ payment_id: key, status: "PAYMENT_SUCCEEDED", created_at: "2026-09-30T10:00:00Z" }] },
  { name: "getReport", invoke: () => api.getReport("2026-09"), body: report },
  { name: "getSummary", invoke: () => api.getSummary(session, "2026-09"), body: { report, narrative: "Summary", mode: "AI_DISABLED", disclaimer: "No AI" } },
  { name: "logout", invoke: () => api.logout(session), body: { redirect: "http://localhost:3000" } },
];

describe.each(cases)("$name", ({ invoke, body }) => {
  it("request_validResponse_returnsValidatedValue", async () => {
    // Given
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json(body)));

    // When
    const value = await invoke();

    // Then
    expect(value).toEqual(body);
  });
  it("request_invalidResponse_rejectsUnverifiedData", async () => {
    // Given
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json({})));

    // When / Then
    await expect(invoke()).rejects.toMatchObject({ code: "invalidResponse" });
  });
  it("request_networkFailure_returnsSafeError", async () => {
    // Given
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("Internal hostname and secret")));

    // When / Then
    await expect(invoke()).rejects.toMatchObject({ status: 503, message: "unavailable" });
  });
});

it("createPayment_validRequest_preservesIdempotencyAndCsrfHeaders", async () => {
  // Given
  const fetch = vi.fn().mockResolvedValue(Response.json(payment));
  vi.stubGlobal("fetch", fetch);

  // When
  await api.createPayment(session, key, request);

  // Then
  expect(fetch).toHaveBeenCalledWith("/bff/payments", expect.objectContaining({ method: "POST",
    headers: expect.objectContaining({ "Idempotency-Key": key, "X-CSRF-Token": "csrf" }), body: JSON.stringify(request) }));
});

it.each([api.getPayments, api.getNotifications])("history_emptyResponse_returnsEmptyCollection", async (invoke) => {
  // Given
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(Response.json([])));

  // When / Then
  await expect(invoke()).resolves.toEqual([]);
});

it("getSession_expiredSession_returnsNull", async () => {
  // Given
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 401 })));

  // When / Then
  await expect(api.getSession()).resolves.toBeNull();
});

it.each([[403, "forbidden"], [409, "conflict"], [429, "rateLimited"], [502, "unavailable"]])(
  "createPayment_http%s_exposesStableErrorCode", async (status, code) => {
    // Given
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("private upstream error", { status: status as number })));

    // When / Then
    await expect(api.createPayment(session, key, request)).rejects.toMatchObject({ code });
  },
);

it("fundWallet_noContentResponse_returnsNull", async () => {
  // Given
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));

  // When / Then
  await expect(api.fundWallet(session, id, key, 100)).resolves.toBeNull();
});
