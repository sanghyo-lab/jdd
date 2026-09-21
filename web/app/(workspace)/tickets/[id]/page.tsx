import { requirePageSession } from "@/lib/auth";
import { TicketEditor } from "@/components/ticket-editor";
export default async function Page({ params }: { params: Promise<{ id: string }> }) {
  await requirePageSession(); return <TicketEditor id={(await params).id} />;
}
