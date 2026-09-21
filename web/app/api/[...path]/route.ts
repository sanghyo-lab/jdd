import "server-only";
import { relay } from "@/lib/backend.mjs";
export const runtime = "nodejs";
export const dynamic = "force-dynamic";
type Context = { params: Promise<{ path: string[] }> };
async function handle(request: Request, context: Context) {
  return relay(request, (await context.params).path);
}
export { handle as GET, handle as POST, handle as PATCH, handle as DELETE, handle as PUT, handle as OPTIONS, handle as HEAD };
