import "server-only";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { COOKIE, configuration, validSession } from "./security.mjs";

export async function requirePageSession() {
  const value = (await cookies()).get(COOKIE)?.value;
  try { if (validSession(value, configuration())) return; } catch { /* Login displays configuration failure. */ }
  redirect("/login");
}
