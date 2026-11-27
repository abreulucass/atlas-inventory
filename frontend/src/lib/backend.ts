import "server-only";

// Errors where the request never left this process, so the backend did not see it.
const NOT_SENT = new Set(["ECONNREFUSED", "ENOTFOUND", "EAI_AGAIN"]);

export class ServiceUnavailableError extends Error {
  // false only when the backend surely did not receive the request (safe to retry a refresh).
  readonly reachedBackend: boolean;
  constructor(cause?: unknown, reachedBackend = true) {
    super("Serviço indisponível. Tente novamente em instantes.", { cause });
    this.name = "ServiceUnavailableError";
    this.reachedBackend = reachedBackend;
  }
}

export function apiUrl(): string {
  const url = process.env.ATLAS_API_URL;
  if (!url) throw new ServiceUnavailableError(undefined, false);
  return url.replace(/\/+$/, "");
}

export async function backendFetch(
  path: string,
  init: RequestInit & { token?: string } = {},
): Promise<Response> {
  const { token, headers, ...rest } = init;
  const url = apiUrl() + path;
  const merged = new Headers(headers);
  merged.set("Accept", "application/json");
  if (rest.body !== undefined) merged.set("Content-Type", "application/json");
  if (token) merged.set("Authorization", `Bearer ${token}`);
  let res: Response;
  try {
    res = await fetch(url, { ...rest, headers: merged, cache: "no-store" });
  } catch (e) {
    const code = (e as { cause?: { code?: unknown } })?.cause?.code;
    throw new ServiceUnavailableError(e, !(typeof code === "string" && NOT_SENT.has(code)));
  }
  if (res.status >= 500) throw new ServiceUnavailableError();
  return res;
}

export const UNAVAILABLE = "Serviço indisponível. Tente novamente em instantes.";

export const field = (data: FormData, name: string) => {
  const value = data.get(name);
  return typeof value === "string" ? value : "";
};

// Returns the response, or null when the backend is unavailable; other errors propagate.
export async function post(path: string, body: unknown, token?: string): Promise<Response | null> {
  try {
    return await backendFetch(path, { method: "POST", body: JSON.stringify(body), token });
  } catch (e) {
    if (e instanceof ServiceUnavailableError) return null;
    throw e;
  }
}
