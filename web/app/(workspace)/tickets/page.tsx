import { requirePageSession } from "@/lib/auth";
import { TicketList } from "@/components/ticket-list";
export default async function Page() { await requirePageSession(); return <TicketList />; }
