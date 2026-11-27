import { NextResponse, type NextRequest } from "next/server";

import { safeNext } from "@/lib/safe-next";
import { ACCESS_COOKIE, REFRESH_COOKIE } from "@/lib/session";

// State-changing GET by design: it only clears local cookies, the backend session is untouched.
export function GET(req: NextRequest) {
  const url = new URL("/entrar", req.url);
  const next = req.nextUrl.searchParams.get("next");
  if (next) url.searchParams.set("next", safeNext(next));
  const res = NextResponse.redirect(url);
  for (const name of [ACCESS_COOKIE, REFRESH_COOKIE]) {
    res.cookies.set(name, "", { path: "/", maxAge: 0 });
  }
  return res;
}
