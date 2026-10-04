import "server-only";
import { randomBytes } from "node:crypto";
import { createClient } from "redis";
import { z } from "zod";
import { sessionSchema } from "@/lib/ApiSchemas";

export const storedSessionSchema = sessionSchema.extend({
  accessToken: z.string(), refreshToken: z.string().optional(), idToken: z.string(),
  expiresAt: z.number(), absoluteExpiry: z.number(),
});
export type StoredSession = z.infer<typeof storedSessionSchema>;
function createSessionClient() {
  return createClient({
    url: process.env.REDIS_URL ?? "redis://localhost:6379",
    // Fail closed promptly instead of queueing authenticated requests during a session-store outage.
    socket: { connectTimeout: 3000, reconnectStrategy: false }, disableOfflineQueue: true,
  });
}
type RedisClient = ReturnType<typeof createSessionClient>;
let connection: Promise<RedisClient> | undefined;

export function randomSecret() { return randomBytes(32).toString("base64url"); }

export function appOrigin() {
  const origin = new URL(process.env.APP_ORIGIN ?? "http://localhost:3000");
  if (origin.protocol !== "https:" && !(origin.protocol === "http:" && ["localhost", "127.0.0.1"].includes(origin.hostname))) {
    throw new Error("APP_ORIGIN requires HTTPS outside loopback development");
  }
  return origin.origin;
}

export function cookieOptions() {
  return { httpOnly: true, secure: appOrigin().startsWith("https:"), sameSite: "lax" as const, path: "/" };
}

export function sessionCookieName() {
  return cookieOptions().secure ? "__Host-finpay-session" : "finpay-session";
}

export async function redis() {
  if (!connection) {
    const client = createSessionClient();
    client.on("error", () => { console.error("Session store unavailable"); });
    connection = client.connect().then(() => client).catch((error) => { connection = undefined; throw error; });
    client.on("end", () => { connection = undefined; });
  }
  return connection;
}

function sessionKey(id: string) { return `finpay:session:${id}`; }

export async function loadSession(id?: string): Promise<StoredSession | null> {
  if (!id || !/^[A-Za-z0-9_-]{43}$/.test(id)) return null;
  const client = await redis();
  const value = await client.get(sessionKey(id));
  if (!value) return null;
  const session = storedSessionSchema.parse(JSON.parse(value));
  if (session.absoluteExpiry <= Date.now()) { await client.del(sessionKey(id)); return null; }
  return session;
}

export async function saveSession(id: string, session: StoredSession, onlyIfExisting = false) {
  // Thirty-minute idle expiry within an absolute eight-hour session lifetime.
  const seconds = Math.min(1800, Math.floor((session.absoluteExpiry - Date.now()) / 1000));
  if (seconds <= 0) return false;
  const client = await redis();
  const value = JSON.stringify(storedSessionSchema.parse(session));
  const saved = onlyIfExisting
    ? await client.set(sessionKey(id), value, { EX: seconds, XX: true })
    : await client.set(sessionKey(id), value, { EX: seconds });
  return saved === "OK";
}

export async function deleteSession(id: string) { await (await redis()).del(sessionKey(id)); }

export async function acquireRefreshLock(id: string, token: string) {
  return (await redis()).set(`finpay:refresh:${id}`, token, { NX: true, PX: 10000 });
}

export async function releaseRefreshLock(id: string, token: string) {
  await (await redis()).eval(
    "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
    { keys: [`finpay:refresh:${id}`], arguments: [token] },
  );
}

export async function consumeWriteBudget(userId: string, expensive: boolean) {
  // Atomic per-user limits: 30 writes/minute, or 5 local-AI calls/minute.
  const count = await (await redis()).eval(
    "local n = redis.call('INCR', KEYS[1]); if n == 1 then redis.call('EXPIRE', KEYS[1], 60) end; return n",
    { keys: [`finpay:limit:${expensive ? "ai" : "write"}:${userId}`], arguments: [] },
  );
  return Number(count) <= (expensive ? 5 : 30);
}

