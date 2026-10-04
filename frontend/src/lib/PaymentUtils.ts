import { z } from "zod";
import { pendingAttemptSchema } from "./ApiSchemas";

/** Converts decimal rupee text to integer paise without floating-point rounding. */
export function parseRupees(value: string) {
  const normalized = value.trim();
  if (!/^\d{1,7}(\.\d{1,2})?$/.test(normalized)) throw new Error("invalidAmount");
  const [whole, fractional = ""] = normalized.split(".");
  const paise = Number(whole) * 100 + Number(fractional.padEnd(2, "0"));
  if (paise < 1 || paise > 100_000_000) throw new Error("invalidAmount");
  return paise;
}

export const transferFormSchema = z.object({
  target: z.uuid(), amount: z.string().refine((value) => { try { parseRupees(value); return true; } catch { return false; } }),
  description: z.string().trim().min(1).max(200), purpose: z.enum(["TRANSFER", "MERCHANT"]),
});

export function restoreAttempt(value: string | null) {
  if (!value) return null;
  try { return pendingAttemptSchema.parse(JSON.parse(value)); } catch { return null; }
}

export function isAllowedProxyRequest(method: string, path: string) {
  const uuid = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
  if (method === "GET") return new RegExp(`^(payments|notifications|wallets/${uuid}|payments/${uuid}|ledger/${uuid}|insights/(20[0-9]{2}|2100)-(0[1-9]|1[0-2]))$`).test(path);
  if (method === "POST") return new RegExp(`^(wallets|payments|wallets/${uuid}/sandbox-funding|insights/(20[0-9]{2}|2100)-(0[1-9]|1[0-2])/summary)$`).test(path);
  return false;
}
