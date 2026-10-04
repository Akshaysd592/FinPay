import "server-only";
import * as oidc from "openid-client";
import { z } from "zod";
import {
  acquireRefreshLock, appOrigin, deleteSession, loadSession, randomSecret, redis,
  releaseRefreshLock, saveSession, type StoredSession,
} from "./SessionService";

const loginSchema = z.object({ state: z.string(), nonce: z.string(), verifier: z.string() });
let configuration: Promise<oidc.Configuration> | undefined;

async function config() {
  if (!configuration) {
    const issuer = new URL(process.env.OIDC_ISSUER ?? "http://localhost:8180/realms/finpay");
    const internal = new URL(process.env.OIDC_INTERNAL_ORIGIN ?? issuer.origin);
    const local = issuer.protocol === "http:" && ["localhost", "127.0.0.1"].includes(issuer.hostname);
    if (!local && (issuer.protocol !== "https:" || internal.protocol !== "https:")) {
      throw new Error("OIDC requires HTTPS outside loopback development");
    }
    configuration = oidc.discovery(issuer, process.env.OIDC_CLIENT_ID ?? "finpay-web", undefined, oidc.None(), {
      ...(local ? { execute: [oidc.allowInsecureRequests] } : {}),
      timeout: 5,
      [oidc.customFetch]: async (url, options) => {
        const target = new URL(url);
        if (target.origin !== issuer.origin) throw new Error("Unexpected identity endpoint origin");
        target.protocol = internal.protocol;
        target.host = internal.host;
        const body = options.body instanceof Uint8Array ? new Uint8Array(options.body).buffer : options.body;
        return fetch(target, { ...options, body, signal: AbortSignal.timeout(5000), redirect: "error" });
      },
    }).catch((error) => { configuration = undefined; throw error; });
  }
  return configuration;
}

export async function beginLogin() {
  const verifier = oidc.randomPKCECodeVerifier();
  const state = oidc.randomState();
  const nonce = oidc.randomNonce();
  const loginId = randomSecret();
  // One-use login binding expires after five minutes; it is never placed in browser-readable storage.
  await (await redis()).set(`finpay:login:${loginId}`, JSON.stringify({ verifier, state, nonce }), { EX: 300 });
  const url = oidc.buildAuthorizationUrl(await config(), {
    redirect_uri: `${appOrigin()}/auth/callback`, scope: "openid profile email", state, nonce,
    code_challenge: await oidc.calculatePKCECodeChallenge(verifier), code_challenge_method: "S256",
  });
  return { loginId, url };
}

export async function completeLogin(callback: URL, loginId?: string) {
  if (!loginId || !/^[A-Za-z0-9_-]{43}$/.test(loginId)) throw new Error("Missing login binding");
  const raw = await (await redis()).getDel(`finpay:login:${loginId}`);
  if (!raw) throw new Error("Login binding expired or already used");
  const login = loginSchema.parse(JSON.parse(raw));
  const tokens = await oidc.authorizationCodeGrant(await config(), callback, {
    expectedState: login.state, expectedNonce: login.nonce, pkceCodeVerifier: login.verifier,
  });
  const claims = tokens.claims();
  if (!claims || !tokens.id_token) throw new Error("Missing verified identity");
  // Roles are display hints only; Java services independently authorize the access token.
  const roles = z.object({ roles: z.array(z.string()) }).safeParse(claims.realm_access);
  const session: StoredSession = {
    userId: claims.sub, displayName: String(claims.name ?? claims.preferred_username ?? ""),
    roles: roles.success ? roles.data.roles : [], csrfToken: randomSecret(),
    accessToken: tokens.access_token, refreshToken: tokens.refresh_token, idToken: tokens.id_token,
    expiresAt: Date.now() + (tokens.expires_in ?? 300) * 1000, absoluteExpiry: Date.now() + 8 * 60 * 60 * 1000,
  };
  const id = randomSecret();
  await saveSession(id, session);
  return id;
}

export async function authenticatedSession(id?: string) {
  let session = await loadSession(id);
  if (!session || !id) return null;
  if (session.expiresAt > Date.now() + 30000) {
    return await saveSession(id, session, true) ? session : null;
  }
  const lock = randomSecret();
  if (!await acquireRefreshLock(id, lock)) throw new Error("Session refresh in progress");
  try {
    session = await loadSession(id);
    if (!session) return null;
    if (session.expiresAt > Date.now() + 30000) return session;
    if (!session.refreshToken) { await deleteSession(id); return null; }
    const tokens = await oidc.refreshTokenGrant(await config(), session.refreshToken);
    const refreshed = { ...session, accessToken: tokens.access_token,
      refreshToken: tokens.refresh_token ?? session.refreshToken, idToken: tokens.id_token ?? session.idToken,
      expiresAt: Date.now() + (tokens.expires_in ?? 300) * 1000 };
    // XX prevents a concurrent refresh from recreating a session that was logged out.
    return await saveSession(id, refreshed, true) ? refreshed : null;
  } catch {
    await deleteSession(id);
    return null;
  } finally {
    await releaseRefreshLock(id, lock);
  }
}

export async function endSession(id: string, session: StoredSession) {
  await deleteSession(id);
  return oidc.buildEndSessionUrl(await config(), {
    id_token_hint: session.idToken, post_logout_redirect_uri: appOrigin(), client_id: process.env.OIDC_CLIENT_ID ?? "finpay-web",
  });
}

