import type { Metadata } from "next";
import "./globals.css";
export const metadata: Metadata = { title: "JDD · 문의 작업실", description: "문의와 조사 근거를 함께 관리하는 팀 작업실", robots: { index: false, follow: false } };
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="ko"><body>{children}</body></html>;
}
