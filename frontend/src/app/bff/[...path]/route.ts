import { NextRequest, NextResponse } from "next/server";
import { z } from "zod";
import { paymentRequestSchema } from "@/lib/ApiSchemas";
import { isAllowedProxyRequest } from "@/lib/PaymentUtils";
import { authenticatedSession } from "@/server/AuthenticationService";
import { appOrigin, consumeWriteBudget, sessionCookieName } from "@/server/SessionService";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
type Context = { params: Promise<{ path: string[] }> };
const headers = { "Cache-Control": "no-store", "Content-Type": "application/json" };

function problem(status: number, code: string) {
  return NextResponse.json({ code }, { status, headers });
}

async function limitedBody(request: NextRequest) {
  const reader = request.body?.getReader();
  if (!reader) return "";
  const chunks: Uint8Array[] = [];
  let size = 0;
  for (;;) {
    const chunk = await reader.read();
    if (chunk.done) break;
    size += chunk.value.byteLength;
    if (size > 16384) { await reader.cancel(); throw new Error("BODY_TOO_LARGE"); }
    chunks.push(chunk.value);
  }
  return Buffer.concat(chunks).toString("utf8");
}

async function proxy(request: NextRequest, context: Context) {
  const path = (await context.params).path.join("/");
  if (!isAllowedProxyRequest(request.method, path)) return problem(404, "NOT_FOUND");
  const query = request.nextUrl.searchParams;
  if ([...query.keys()].some((key) => key !== "offset") || query.getAll("offset").length > 1
      || (query.has("offset") && !/^\d{1,6}$/.test(query.get("offset")!))
      || Number(query.get("offset") ?? 0) > 100000) return problem(400, "INVALID_REQUEST");
  try {
    const session = await authenticatedSession(request.cookies.get(sessionCookieName())?.value);
    if (!session) return problem(401, "UNAUTHENTICATED");
    const outgoing: Record<string, string> = { Authorization: `Bearer ${session.accessToken}`, Accept: "application/json" };
    let body: string | undefined;
    if (request.method === "POST") {
      if (request.headers.get("Origin") !== appOrigin() || request.headers.get("X-CSRF-Token") !== session.csrfToken) return problem(403, "CSRF_REJECTED");
      if (!await consumeWriteBudget(session.userId, path.endsWith("/summary"))) return problem(429, "RATE_LIMITED");
      const text = await limitedBody(request);
      if (path === "payments" || path.endsWith("/sandbox-funding")) {
        const key = z.uuid().safeParse(request.headers.get("Idempotency-Key"));
        if (!key.success) return problem(400, "INVALID_REQUEST");
        outgoing["Idempotency-Key"] = key.data;
        let input: unknown;
        try { input = JSON.parse(text); } catch { return problem(400, "INVALID_REQUEST"); }
        const schema = path === "payments" ? paymentRequestSchema : z.object({ amountMinor: z.number().int().min(1).max(5_000_000) });
        const validated = schema.safeParse(input);
        if (!validated.success) return problem(400, "INVALID_REQUEST");
        body = JSON.stringify(validated.data);
        outgoing["Content-Type"] = "application/json";
      }
    }
    const base = process.env.BACKEND_ORIGIN ?? "http://localhost:8080";
    const url = new URL(`/api/${path}`, base);
    url.search = query.toString();
    // Do not retry: a timed-out write may already have committed. The UI must retain its key.
    const upstream = await fetch(url, { method: request.method, headers: outgoing, body,
      redirect: "error", cache: "no-store", signal: AbortSignal.timeout(12000) });
    if (upstream.status >= 500) return problem(502, "UPSTREAM_UNAVAILABLE");
    return new NextResponse(upstream.status === 204 ? null : await upstream.text(), { status: upstream.status,
      headers: { ...headers, "Content-Type": upstream.headers.get("Content-Type") ?? "application/json" } });
  } catch (error) {
    if (error instanceof Error && error.message === "BODY_TOO_LARGE") return problem(413, "INVALID_REQUEST");
    // Internal hostnames, OAuth errors, and financial payloads are not exposed.
    return problem(503, "UPSTREAM_UNAVAILABLE");
  }
}

export const GET = proxy;
export const POST = proxy;
