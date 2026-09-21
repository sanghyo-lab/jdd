import Link from "next/link";
import { requirePageSession } from "@/lib/auth";
import { Logout } from "@/components/login";
export const dynamic = "force-dynamic";
export default async function Workspace({ children }: { children: React.ReactNode }) {
  await requirePageSession();
  return <div className="workspace"><a className="skip" href="#content">본문으로 이동</a>
    <header className="topbar"><Link className="brand" href="/tickets"><span className="brand-mark">jdd</span><span>문의 작업실</span></Link>
      <nav aria-label="주 메뉴"><Link href="/tickets">문의 티켓</Link></nav><Logout /></header>
    <main id="content">{children}</main><footer>JDD · 문의의 맥락을 기록하고, 근거를 함께 확인합니다.</footer>
  </div>;
}
