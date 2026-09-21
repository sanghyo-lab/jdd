import { requirePageSession } from "@/lib/auth";
import { Shop } from "@/components/shop";
export default async function Page() { await requirePageSession(); return <Shop />; }
