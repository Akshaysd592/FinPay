// @unit
import { describe, expect, it } from "vitest";
import { isAllowedProxyRequest, parseRupees, restoreAttempt } from "./PaymentUtils";

describe("parseRupees", () => {
  it.each([["0.01", 1], ["10", 1000], ["10.1", 1010], ["10.10", 1010], [" 100.99 ", 10099], ["1000000", 100000000]])(
    "parseRupees_%s_returnsExactPaise", (input, expected) => {
      // Given
      const value = input as string;

      // When
      const amount = parseRupees(value);

      // Then
      expect(amount).toBe(expected);
    },
  );
  it.each(["", "0", "-1", "1.001", "1e3", "NaN", "Infinity", "1,000", "1000000.01", "99999999", ".01"])(
    "parseRupees_%s_rejectsInvalidAmount", (value) => {
      // Given / When / Then
      expect(() => parseRupees(value)).toThrow("invalidAmount");
    },
  );
});

describe("restoreAttempt", () => {
  it("restoreAttempt_validSavedRequest_preservesIdentity", () => {
    // Given
    const attempt = { key: "00000000-0000-4000-8000-000000000003", request: {
      sourceWalletId: "00000000-0000-4000-8000-000000000001", targetWalletId: "00000000-0000-4000-8000-000000000002",
      amountMinor: 101, currency: "INR", description: "Groceries", purpose: "TRANSFER",
    } };

    // When
    const restored = restoreAttempt(JSON.stringify(attempt));

    // Then
    expect(restored).toEqual(attempt);
  });
  it.each([null, "", "not-json", "{}", '{"key":"not-a-uuid"}'])("restoreAttempt_invalidStorage_returnsNull", (value) => {
    // Given / When / Then
    expect(restoreAttempt(value)).toBeNull();
  });
});

describe("isAllowedProxyRequest", () => {
  it.each([["GET", "payments"], ["POST", "wallets"], ["POST", "payments"], ["GET", "insights/2026-09"], ["POST", "insights/2026-09/summary"]])(
    "isAllowedProxyRequest_%s_%s_allowsKnownRoute", (method, path) => {
      // Given / When / Then
      expect(isAllowedProxyRequest(method, path)).toBe(true);
    },
  );
  it.each([["DELETE", "payments"], ["GET", "../actuator"], ["GET", "http://example.com"], ["GET", "actuator/prometheus"],
    ["POST", "payments/../wallets"], ["GET", "insights/2026-13"], ["GET", "insights/1900-01"], ["GET", "wallets/not-a-uuid"]])(
    "isAllowedProxyRequest_%s_%s_rejectsUnknownRoute", (method, path) => {
      // Given / When / Then
      expect(isAllowedProxyRequest(method, path)).toBe(false);
    },
  );
});
