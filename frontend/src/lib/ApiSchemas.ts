import { z } from "zod";

const money = z.number().int().min(0).max(Number.MAX_SAFE_INTEGER);
export const sessionSchema = z.object({
  userId: z.string(), displayName: z.string(), roles: z.array(z.string()), csrfToken: z.string(),
});
export const walletSchema = z.object({
  id: z.uuid(), ownerId: z.string(), currency: z.literal("INR"), balanceMinor: money, version: money,
});
export const paymentRequestSchema = z.object({
  sourceWalletId: z.uuid(), targetWalletId: z.uuid(), amountMinor: money.min(1).max(100_000_000),
  currency: z.literal("INR"), description: z.string().trim().min(1).max(200), purpose: z.enum(["TRANSFER", "MERCHANT"]),
}).refine((request) => request.sourceWalletId !== request.targetWalletId, { path: ["targetWalletId"], message: "differentWallets" });
export const paymentSchema = z.object({
  id: z.uuid(), ownerId: z.string(), sourceWalletId: z.uuid(), targetWalletId: z.uuid(), amountMinor: money,
  currency: z.literal("INR"), description: z.string(), purpose: z.enum(["TRANSFER", "MERCHANT"]),
  status: z.enum(["PENDING", "SUCCESS", "FAILED"]), reason: z.string().nullable().optional(), createdAt: z.iso.datetime(),
});
export const notificationSchema = z.object({
  payment_id: z.uuid(), status: z.enum(["PAYMENT_SUCCEEDED", "PAYMENT_FAILED"]), created_at: z.iso.datetime(),
});
export const reportSchema = z.object({
  month: z.string(), currency: z.literal("INR"), classification: z.literal("RULE_BASED"),
  consistency: z.literal("EVENTUALLY_CONSISTENT"),
  categories: z.array(z.object({ category: z.enum(["FOOD", "SHOPPING", "TRAVEL", "BILLS", "OTHER"]), amountMinor: money })),
});
export const summarySchema = z.object({
  report: reportSchema, narrative: z.string(),
  mode: z.enum(["LLM", "AI_DISABLED", "AI_BUSY", "AI_EMPTY_RESPONSE", "AI_UNAVAILABLE"]), disclaimer: z.string(),
});
export const pendingAttemptSchema = z.object({ key: z.uuid(), request: paymentRequestSchema });
export type Session = z.infer<typeof sessionSchema>;
export type Wallet = z.infer<typeof walletSchema>;
export type Payment = z.infer<typeof paymentSchema>;
export type PaymentRequest = z.infer<typeof paymentRequestSchema>;
export type SpendingReport = z.infer<typeof reportSchema>;
export type Summary = z.infer<typeof summarySchema>;
export type Notification = z.infer<typeof notificationSchema>;
export type PendingAttempt = z.infer<typeof pendingAttemptSchema>;
