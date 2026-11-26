# Frontend Skeleton and Authentication Design

**Spec**: `.specs/features/30-frontend-auth/spec.md`
**Status**: Approved

---

## Architecture Overview

User chose "Proxy + Server Actions (Recommended)" over route handlers with a generic passthrough (AD-003). Three Next.js 16 facts drive it: `middleware` is now `proxy.ts` and runs on Node.js by default; Server Components cannot set cookies, so the refresh must happen in `proxy.ts`; Server Actions only accept `POST` and abort when `Origin` differs from `Host`, which is SES-08 for free.

```
browser ─► proxy.ts ─┬─ no access cookie + refresh cookie ─► refreshSession() (single-flight) ─► POST /api/auth/refresh
                     │      ok   → Set-Cookie on the response + rewritten Cookie header forwarded upstream
                     │      401  → delete cookies, redirect /entrar?next=<path>
                     ├─ protected route, no session  → redirect /entrar?next=<path>
                     ├─ /entrar|/cadastro with session → redirect /
                     └─ next()
        ─► (app) RSC pages ─► api() [server-only, Bearer from cookie] ─► GET /api/me
                                   401 → redirect /auth/expired?next=<path> (route handler deletes cookies → /entrar)
        ─► Server Actions login/register/logout ─► POST /api/auth/* ─► cookies().set/delete ─► redirect
```

A session is "valid" for routing when the access cookie exists. The access cookie's `maxAge` is `expiresIn - 30` seconds, so it disappears before the backend would reject it and `proxy.ts` never has to decode a JWT (it has no secret and must not need one).

Every rule a user sees (validation, slug derivation, `next` sanitising, error mapping, role labels, session rotation) lives in plain TypeScript modules under `src/lib/` that Vitest reaches; components only render their results.

---

## Code Reuse Analysis

### Existing Components to Leverage

| Component | Location | How to Use |
| --------- | -------- | ---------- |
| Auth contract | `backend/.../access/internal/AuthController.java`, `TokenPair.java` | Request/response shapes for register, login, refresh, logout |
| `GET /api/me` | `backend/.../access/internal/MeController.java` | Shell data: `id, name, email, organizationId, role` |
| Validation rules | `AuthService.java:57-62,130`, `Organizations.java:17,27-33` | Mirrored in `src/lib/validation.ts` (same bounds, same regexes, code-point counting) |
| Error statuses | `AuthExceptionHandler.java` | `400` invalid field, `401` bad credentials / invalid refresh, `409` duplicate e-mail or slug |
| Compose + roles | `backend/compose.yaml`, `backend.yml` workflow | E2E job starts the backend the same way the README does |
| shadcn components | `npx shadcn` registry | `button`, `input`, `label`, `card`, `sidebar`, `sheet`, `dropdown-menu`, `separator` |
| `next-themes` | shadcn dark-mode guide | Theme control (SHL-03) |

No frontend code exists yet; everything else is new.

### Integration Points

| System | Integration Method |
| ------ | ------------------ |
| Backend API | Server-side `fetch` to `ATLAS_API_URL` from `proxy.ts`, Server Actions and RSC only |
| CI | New `.github/workflows/frontend.yml`; backend workflow untouched |
| README | "Frontend e CORS" rewritten for Proxy + Server Actions; "Como rodar" gains the frontend commands |

---

## Components

### `src/lib/backend.ts`

- **Purpose**: The one place that calls the backend.
- **Interfaces**:
  - `apiUrl(): string` - reads `ATLAS_API_URL`; throws `ServiceUnavailableError` when unset (EDG-01).
  - `backendFetch(path: string, init?: RequestInit & { token?: string }): Promise<Response>` - prefixes `apiUrl()`, sets JSON headers and `Authorization: Bearer`, `cache: 'no-store'`; network failure or `5xx` → `ServiceUnavailableError`.
  - `class ServiceUnavailableError extends Error`.
- **Dependencies**: `server-only`, global `fetch` (stubbed in tests with `vi.stubGlobal`).

### `src/lib/session.ts`

- **Purpose**: Cookie contract and refresh rotation (SES-01, SES-05..07).
- **Interfaces**:
  - `ACCESS_COOKIE = 'atlas_access'`, `REFRESH_COOKIE = 'atlas_refresh'`.
  - `sessionCookies(pair: TokenPair): Array<{ name, value, options }>` - `httpOnly`, `sameSite: 'lax'`, `path: '/'`, `secure: NODE_ENV === 'production'`; access `maxAge = max(expiresIn - 30, 1)`, refresh `maxAge = 30 * 24 * 3600`.
  - `refreshSession(refreshToken: string): Promise<TokenPair | 'rejected'>` - single-flight: a module-level `Map<refreshToken, { promise, expiresAt }>`; concurrent and late callers holding the same old token get the same promise; entries live 60 s after settling, so a request that left the browser before the new `Set-Cookie` arrived does not replay the used token (which would revoke the family). `401` → `'rejected'`; `5xx`/network → throws `ServiceUnavailableError` (cookies kept).
- **Dependencies**: `backend.ts`.
- **Note**: `shortcut: single-flight is per Node process, upgrade to a shared store (Redis) when the frontend runs on more than one instance.`

### `src/proxy.ts`

- **Purpose**: Session routing (SES-03, SES-04, SES-05, SES-07).
- **Interfaces**: `export async function proxy(req: NextRequest): Promise<NextResponse>`; `config.matcher` excludes `_next/static`, `_next/image`, `favicon.ico`, `auth/expired`.
- **Behaviour**: public routes = `/entrar`, `/cadastro`. No access cookie + refresh cookie → `refreshSession`; on success writes `sessionCookies` on the response and forwards a rewritten `Cookie` header with `NextResponse.next({ request: { headers } })` so the RSC sees the new access token in the same request; on `'rejected'` deletes both cookies and redirects to `/entrar?next=<path+search>` (protected) or serves the public page; on `ServiceUnavailableError` serves the request unchanged (page shows its own error). Then: protected + no access → redirect `/entrar?next=…`; public + access → redirect `/`.
- **Dependencies**: `session.ts`.

### `src/lib/api.ts`

- **Purpose**: Authenticated reads for RSC.
- **Interfaces**: `getMe(): Promise<Me>` (wrapped in React `cache`) - reads the access cookie, `GET /api/me`; `401` → `redirect('/auth/expired?next=…')` (EDG-02).
- **Dependencies**: `server-only`, `backend.ts`, `next/headers`.

### `src/app/auth/expired/route.ts`

- **Purpose**: Clear cookies from a render path, since RSC cannot (EDG-02).
- **Interfaces**: `GET` - deletes both cookies, redirects to `/entrar?next=<safe next>`. Calls nothing on the backend.

### `src/lib/validation.ts`

- **Purpose**: Field rules and slug derivation shared by client and server (REG-02, REG-04).
- **Interfaces**:
  - `slugify(name: string): string` - NFD + strip combining marks, lowercase, `[^a-z0-9]+` → `-`, trim `-`, cut to 63, trim `-` again.
  - `validateRegister(input): Partial<Record<RegisterField, string>>`, `validateLogin(input)` - pt-BR messages per field; lengths counted in code points after `trim()` like the backend.
- **Dependencies**: none (isomorphic).

### `src/lib/safe-next.ts`

- **Purpose**: LOG-01, EDG-03.
- **Interfaces**: `safeNext(value: string | null | undefined): string` - returns `value` only when it starts with one `/`, not `//`, has no `\` and no control characters; else `/`.

### `src/lib/auth-actions.ts` (`'use server'`)

- **Purpose**: Login, register, logout (REG-01, REG-05/06, LOG-01..03, LOG-05/06).
- **Interfaces**:
  - `login(prev: FormState, data: FormData): Promise<FormState>` - validates; `POST /api/auth/login`; `200` → set cookies, `redirect(safeNext(next))`; `401` → `{ formError: 'E-mail ou senha inválidos.', values: { email } }`; unavailable → `{ formError: 'Serviço indisponível. Tente novamente em instantes.' }`.
  - `register(prev, data)` - validates (trust boundary, same rules as the client); `201` → cookies, `redirect('/')`; `409` → field error on `email` when `detail` starts with `e-mail already registered`, on `organizationSlug` when it starts with `Slug already in use`; `400` → `formError: 'Verifique os dados informados.'`.
  - `logout()` - reads refresh cookie, `POST /api/auth/logout` in `try`, deletes cookies in `finally`, `redirect('/entrar')`.
  - `type FormState = { fieldErrors?: Partial<Record<string,string>>; formError?: string; values?: Record<string,string> }` - never contains tokens (SES-02).
- **Dependencies**: `backend.ts`, `session.ts`, `validation.ts`, `safe-next.ts`.

### UI components (`src/components/`)

- `login-form.tsx`, `register-form.tsx` - client components on `useActionState`; run `validateLogin`/`validateRegister` on submit and stop on errors (REG-04: no request); button disabled with pending label from `useFormStatus` (REG-07, LOG-04, EDG-04); fields use shadcn `Label` + `Input` with `aria-invalid` and `aria-describedby` to the error `<p>` (SHL-06); register derives the slug while a `slugTouched` flag is false (REG-02/03).
- `app-sidebar.tsx` - shadcn `Sidebar` with "Atlas Inventory" and "Início"; shadcn's sidebar already turns into a `Sheet` under 768 px (SHL-04).
- `user-menu.tsx` - name, e-mail, `roleLabel(role)`, "Sair" (`<form action={logout}>`).
- `theme-toggle.tsx` - `next-themes` dropdown "Claro" / "Escuro" / "Sistema" (persisted in `localStorage` by next-themes).
- `src/lib/roles.ts` - `roleLabel(role: Role): string`.

### Routes

- `src/app/(auth)/entrar/page.tsx`, `src/app/(auth)/cadastro/page.tsx` - public.
- `src/app/(app)/layout.tsx` - calls `getMe()`, renders sidebar + header + user menu; `src/app/(app)/page.tsx` - "Olá, <name>".
- `src/app/layout.tsx` - `lang="pt-BR"`, `ThemeProvider`.

---

## Data Models

```typescript
type TokenPair = { accessToken: string; refreshToken: string; tokenType: 'Bearer'; expiresIn: number }
type Role = 'OWNER' | 'ADMIN' | 'MEMBER'
type Me = { id: string; name: string; email: string; organizationId: string; role: Role }
type RegisterField = 'name' | 'email' | 'password' | 'organizationName' | 'organizationSlug'
```

---

## Error Handling Strategy

| Error Scenario | Handling | User Impact |
| -------------- | -------- | ----------- |
| Invalid field (client) | `validate*` blocks submit | Message under the field, no request |
| Invalid field (server, bypassed client) | Same validator in the action | Same messages |
| `401` login | Action returns `formError` | "E-mail ou senha inválidos.", e-mail kept |
| `409` register | Action maps `detail` prefix to field | Message under e-mail or slug |
| Backend down / `5xx` / `ATLAS_API_URL` unset | `ServiceUnavailableError` → `formError` in actions; RSC → `error.tsx` with the same text | "Serviço indisponível. Tente novamente em instantes." |
| Refresh `401` | `proxy.ts` deletes cookies, redirects | Back to `/entrar?next=…` |
| Refresh `5xx` | `proxy.ts` passes through, cookies kept | RSC fails → error page; next request retries |
| `/api/me` `401` | Redirect to `/auth/expired` | Back to `/entrar` with cookies cleared |
| Logout backend failure | `finally` deletes cookies | Signed out locally anyway |

---

## Risks & Concerns

| Concern | Location (file:line) | Impact | Mitigation |
| ------- | -------------------- | ------ | ---------- |
| Refresh reuse revokes the whole family, no grace window | `backend/.../access/internal/AuthService.java:92-111`, `RefreshTokens.java:56` | Parallel or late requests with the old refresh token log the user out | Single-flight map keyed by the old token, kept 60 s after settling; Vitest proves concurrent and late callers trigger one backend call |
| Single-flight is per process | `src/lib/session.ts` | Two frontend instances can still race | `shortcut:` comment; one instance in this MVP |
| 409 mapping depends on backend `detail` text | `DuplicateSlugException.java:6`, `AuthService.java:70` | A backend message change silently moves the error to a generic one | Unit test pins both prefixes; E2E hits the real backend for the e-mail conflict |
| `/auth/expired` is a state-changing `GET` | `src/app/auth/expired/route.ts` | A third-party page can sign the user out locally (no data exposure, backend session untouched) | Accepted: only deletes cookies; logout with backend revocation stays a Server Action |
| Server Action origin check trusts `Host`/`X-Forwarded-Host` | Next.js runtime | Behind a misconfigured reverse proxy the check could pass for a forged host | No `serverActions.allowedOrigins` configured; E2E proves a cross-origin action is refused |
| No frontend tests exist in CI today | `.github/workflows/` | Regressions land unchecked | `frontend.yml` added in this feature (FE-02, FE-03) |

---

## Tech Decisions

| Decision | Choice | Rationale |
| -------- | ------ | --------- |
| BFF shape | `proxy.ts` + Server Actions + RSC reads, no generic API passthrough | User chose "Proxy + Server Actions (Recommended)"; AD-003 |
| Expiry detection | Access cookie `maxAge = expiresIn - 30` | No JWT decoding in the frontend; refresh happens before the backend rejects |
| Forms | `useActionState` + shared plain validators | No form library needed for two forms; same rules client and server |
| Theme | `next-themes` | shadcn's documented dark-mode path |
| E2E backend | Real backend from `backend/` (Compose + `spring-boot:run`) in the CI job | User chose "Vitest + Playwright (Recommended)" against the real backend |
| Package manager | npm, Node 24 | Spec assumption |
