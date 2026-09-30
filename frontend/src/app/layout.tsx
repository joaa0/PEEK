import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = {
  title: "PEEK · Operação conectada",
  description: "Protótipo da camada de controle operacional PEEK",
};
export default function RootLayout({
  children,
}: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="pt-BR">
      <body>{children}</body>
    </html>
  );
}
