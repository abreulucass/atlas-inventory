import "server-only";

import { backendFetch, ServiceUnavailableError } from "./backend";

export const ACCESS_COOKIE = "atlas_access";
export const REFRESH_COOKIE = "atlas_refresh";

const REFRESH_MAX_AGE = 30 * 24 * 3600;
const SETTLED_TTL_MS = 60_000;

export type TokenPair = {
  accessToken: string;
  refreshToken: string;
  tokenType: "Bearer";
  expiresIn: number;
};

export type SessionCookie = {
  name: string;
  value: string;
  options: {
    httpOnly: true;
    sameSite: "lax";
    path: "/";
    secure: boolean;
    maxAge: number;
  };
};

export function sessionCookies(pair: TokenPair): SessionCookie[] {
  const base = {
    httpOnly: true,
    sameSite: "lax",
    path: "/",
    secure: process.env.NODE_ENV === "production",
  } as const;
  return [
    {
      name: ACCESS_COOKIE,
      value: pair.accessToken,
      options: { ...base, maxAge: Math.max(pair.expiresIn - 30, 1) },
    },
    {
      name: REFRESH_COOKIE,
      value: pair.refreshToken,
      options: { ...base, maxAge: REFRESH_MAX_AGE },
    },
  ];
}

type Flight = { promise: Promise<TokenPair | "rejected">; expiresAt?: number };

// shortcut: single-flight is per Node process, upgrade to a shared store (Redis) when the frontend runs on more than one instance.
const flights = new Map<string, Flight>();

// Keyed by the old token and kept 60 s after settling: replaying a used refresh token revokes the whole family.
export function refreshSession(
  refreshToken: string,
): Promise<TokenPair | "rejected"> {
  const now = Date.now();
  for (const [key, flight] of flights) {
    if (flight.expiresAt !== undefined && flight.expiresAt <= now) flights.delete(key);
  }
  const existing = flights.get(refreshToken);
  if (existing) return existing.promise;

  const flight: Flight = { promise: callRefresh(refreshToken) };
  flights.set(refreshToken, flight);
  flight.promise.then(
    () => {
      flight.expiresAt = Date.now() + SETTLED_TTL_MS;
    },
    (e) => {
      // Never reached the backend: the token was not consumed, so the next request retries.
      if (e instanceof ServiceUnavailableError && !e.reachedBackend) flights.delete(refreshToken);
      // shortcut: an ambiguous failure (5xx, timeout, reset) may have consumed the token; replaying it would revoke the family, so callers get "unavailable" for 60 s, then one retry is allowed.
      else flight.expiresAt = Date.now() + SETTLED_TTL_MS;
    },
  );
  return flight.promise;
}

async function callRefresh(refreshToken: string): Promise<TokenPair | "rejected"> {
  const res = await backendFetch("/api/auth/refresh", {
    method: "POST",
    body: JSON.stringify({ refreshToken }),
  });
  if (res.status === 401) return "rejected";
  if (!res.ok) throw new ServiceUnavailableError();
  return (await res.json()) as TokenPair;
}
