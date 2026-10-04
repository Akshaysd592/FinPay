import { z } from "zod";
import {
  sessionSchema, walletSchema, paymentSchema, paymentRequestSchema, notificationSchema, reportSchema, summarySchema,
  type PaymentRequest, type Session,
} from "@/lib/ApiSchemas";

export class ApiError extends Error {
  constructor(public status: number, public code: string) { super(code); }
}

async function request<T>(url: string, schema: z.ZodType<T>, options?: RequestInit): Promise<T> {
  let response: Response;
  try { response = await fetch(url, { ...options, cache: "no-store", signal: AbortSignal.timeout(15000) }); }
  catch { throw new ApiError(503, "unavailable"); }
  if (!response.ok) {
    const codes: Record<number, string> = { 400: "invalidRequest", 401: "sessionExpired", 403: "forbidden", 404: "notFound", 409: "conflict", 429: "rateLimited" };
    throw new ApiError(response.status, codes[response.status] ?? "unavailable");
  }
  let parsed: unknown;
  try { parsed = response.status === 204 ? null : await response.json(); }
  catch { throw new ApiError(502, "invalidResponse"); }
  const validated = schema.safeParse(parsed);
  if (!validated.success) throw new ApiError(502, "invalidResponse");
  return validated.data;
}

function write(session: Session, body?: unknown, key?: string): RequestInit {
  return { method: "POST", headers: { "Content-Type": "application/json", "X-CSRF-Token": session.csrfToken,
    ...(key ? { "Idempotency-Key": key } : {}) }, body: body === undefined ? undefined : JSON.stringify(body) };
}

export async function getSession() {
  try { return await request("/auth/session", sessionSchema); }
  catch (error) { if (error instanceof ApiError && error.status === 401) return null; throw error; }
}
export function createWallet(session: Session) { return request("/bff/wallets", walletSchema, write(session)); }
export function getWallet(id: string) { return request(`/bff/wallets/${z.uuid().parse(id)}`, walletSchema); }
export function getPayments(offset = 0) { return request(`/bff/payments?offset=${offset}`, z.array(paymentSchema)); }
export function getPayment(id: string) { return request(`/bff/payments/${z.uuid().parse(id)}`, paymentSchema); }
export function createPayment(session: Session, key: string, body: PaymentRequest) {
  return request("/bff/payments", paymentSchema, write(session, paymentRequestSchema.parse(body), z.uuid().parse(key)));
}
export function getNotifications() { return request("/bff/notifications", z.array(notificationSchema)); }
export function getReport(month: string) { return request(`/bff/insights/${encodeURIComponent(month)}`, reportSchema); }
export function getSummary(session: Session, month: string) { return request(`/bff/insights/${encodeURIComponent(month)}/summary`, summarySchema, write(session)); }
export function fundWallet(session: Session, id: string, key: string, amountMinor: number) {
  return request(`/bff/wallets/${z.uuid().parse(id)}/sandbox-funding`, z.null(), write(session, { amountMinor }, z.uuid().parse(key)));
}
export function logout(session: Session) {
  return request("/auth/logout", z.object({ redirect: z.url() }), write(session));
}
