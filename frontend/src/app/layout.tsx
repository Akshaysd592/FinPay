import type { Metadata } from "next";
import { headers } from "next/headers";
import Providers from "@/components/providers/Providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "FinPay | Payments workspace",
  description: "Self-hosted wallet payments and financial insights sandbox.",
};

export default async function RootLayout({ children }: LayoutProps<"/">) {
  const nonce = (await headers()).get("x-nonce") ?? undefined;
  return (
    <html lang="en">
      <body><Providers nonce={nonce}>{children}</Providers></body>
    </html>
  );
}
