import { NextResponse, type NextRequest } from "next/server";

import { ServiceUnavailableError } from "@/lib/backend";
import {
  ACCESS_COOKIE,
  REFRESH_COOKIE,
  refreshSession,
  sessionCookies,
} from "@/lib/session";

const PUBLIC_PATHS = new Set(["/entrar", "/cadastro"]);

function redirectTo(req: NextRequest, path: string, next?: string) {
  const url = new URL(path, req.url);
  if (next) url.searchParams.set("next", next);
  return NextResponse.redirect(url);
}

function clearSession(res: NextResponse) {
  for (const name of [ACCESS_COOKIE, REFRESH_COOKIE]) {
    res.cookies.set(name, "", { path: "/", maxAge: 0 });
  }
  return res;
}

const SAFE_METHODS = new Set(["GET", "HEAD"]);

export async function proxy(req: NextRequest): Promise<NextResponse> {
  // Refuse a cross-origin state change (Server Actions included) before any backend call (SES-08).
  const origin = req.headers.get("origin");
  if (!SAFE_METHODS.has(req.method) && origin !== null && origin !== req.nextUrl.origin) {
    return new NextResponse(null, { status: 403 });
  }
  const { pathname, search } = req.nextUrl;
  const isPublic = PUBLIC_PATHS.has(pathname);
  const next = pathname + search;
  const access = req.cookies.get(ACCESS_COOKIE)?.value;
  const refresh = req.cookies.get(REFRESH_COOKIE)?.value;

  if (!access && refresh) {
    let result;
    try {
      result = await refreshSession(refresh);
    } catch (e) {
      if (e instanceof ServiceUnavailableError) return NextResponse.next();
      throw e;
    }
    if (result === "rejected") {
      return clearSession(
        isPublic ? NextResponse.next() : redirectTo(req, "/entrar", next),
      );
    }
    const cookies = sessionCookies(result);
    let res: NextResponse;
    if (isPublic) {
      res = redirectTo(req, "/");
    } else {
      // Forward the new tokens upstream so the RSC of this same request is signed in.
      for (const c of cookies) req.cookies.set(c.name, c.value);
      res = NextResponse.next({ request: { headers: new Headers(req.headers) } });
    }
    for (const c of cookies) res.cookies.set(c.name, c.value, c.options);
    return res;
  }

  if (!isPublic && !access) return redirectTo(req, "/entrar", next);
  if (isPublic && access) return redirectTo(req, "/");
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|auth/expired).*)"],
};
