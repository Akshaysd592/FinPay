import { NextRequest, NextResponse } from "next/server";
import { authenticatedSession, beginLogin, completeLogin, endSession } from "@/server/AuthenticationService";
import { appOrigin, cookieOptions, sessionCookieName } from "@/server/SessionService";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";
type Context = { params: Promise<{ action: string }> };
const loginCookie = () => cookieOptions().secure ? "__Host-finpay-login" : "finpay-login";

export async function GET(request: NextRequest, context: Context) {
  const { action } = await context.params;
  try {
    if (action === "login") {
      const login = await beginLogin();
      const response = NextResponse.redirect(login.url);
      response.cookies.set(loginCookie(), login.loginId, { ...cookieOptions(), maxAge: 300 });
      response.headers.set("Cache-Control", "no-store");
      return response;
    }
    if (action === "callback") {
      const callback = new URL(`/auth/callback${request.nextUrl.search}`, appOrigin());
      const id = await completeLogin(callback, request.cookies.get(loginCookie())?.value);
      const response = NextResponse.redirect(appOrigin());
      response.cookies.set(sessionCookieName(), id, { ...cookieOptions(), maxAge: 8 * 60 * 60 });
      response.cookies.set(loginCookie(), "", { ...cookieOptions(), maxAge: 0 });
      response.headers.set("Cache-Control", "no-store");
      return response;
    }
    if (action === "session") {
      const session = await authenticatedSession(request.cookies.get(sessionCookieName())?.value);
      if (!session) return NextResponse.json({ code: "UNAUTHENTICATED" }, { status: 401, headers: { "Cache-Control": "no-store" } });
      return NextResponse.json({ userId: session.userId, displayName: session.displayName,
        roles: session.roles, csrfToken: session.csrfToken }, { headers: { "Cache-Control": "no-store" } });
    }
    return new NextResponse(null, { status: 404 });
  } catch {
    // OAuth codes, token responses, and underlying provider errors never enter logs or browser URLs.
    if (action === "callback" || action === "login") return NextResponse.redirect(`${appOrigin()}/?auth=unavailable`);
    return NextResponse.json({ code: "AUTH_UNAVAILABLE" }, { status: 503, headers: { "Cache-Control": "no-store" } });
  }
}

export async function POST(request: NextRequest, context: Context) {
  const { action } = await context.params;
  if (action !== "logout") return new NextResponse(null, { status: 404 });
  try {
    const id = request.cookies.get(sessionCookieName())?.value;
    const session = await authenticatedSession(id);
    if (!id || !session) return NextResponse.json({ redirect: appOrigin() });
    if (request.headers.get("Origin") !== appOrigin() || request.headers.get("X-CSRF-Token") !== session.csrfToken) {
      return new NextResponse(null, { status: 403 });
    }
    const redirect = await endSession(id, session);
    const response = NextResponse.json({ redirect: redirect.toString() }, { headers: { "Cache-Control": "no-store" } });
    response.cookies.set(sessionCookieName(), "", { ...cookieOptions(), maxAge: 0 });
    return response;
  } catch {
    return NextResponse.json({ code: "AUTH_UNAVAILABLE" }, { status: 503 });
  }
}
