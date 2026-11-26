import "server-only";

export class ServiceUnavailableError extends Error {
  constructor(cause?: unknown) {
    super("Serviço indisponível. Tente novamente em instantes.", { cause });
    this.name = "ServiceUnavailableError";
  }
}

export function apiUrl(): string {
  const url = process.env.ATLAS_API_URL;
  if (!url) throw new ServiceUnavailableError();
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
    throw new ServiceUnavailableError(e);
  }
  if (res.status >= 500) throw new ServiceUnavailableError();
  return res;
}
