# Frontend Skeleton and Authentication Specification

**Approved**: 2026-11-26 - user: "pode seguir"

## Problem Statement

The MVP backend is done, but there is no interface: the API is only usable through Swagger or `curl`. This feature creates `frontend/` (Next.js + shadcn/ui) with the BFF the README defines - the browser talks only to Next, which keeps the tokens in httpOnly cookies - and delivers registration, login, logout and an authenticated shell that the domain screens will build on.

## Goals

- [ ] A visitor creates an account and organization, signs in, sees the shell with their name and role, and signs out - all in the browser, without a token ever reaching JavaScript.
- [ ] The session survives access-token expiry (15 min) without logging the user out, including under several simultaneous requests.
- [ ] CI runs lint, typecheck, unit tests, build and E2E against the real backend.

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Organization switching (selector + `GET /api/me/organizations`) | No endpoint lists the user's organizations; own issue. User chose "Fora desta feature" |
| Domain screens (companies, warehouses, catalog, stock, transfers, members) | Following issues, one per slice. User chose "Esqueleto + auth" |
| i18n / English UI | User chose "pt-BR fixo" |
| Password recovery, e-mail confirmation, "remember me" | The backend does not offer them |
| Rate limiting / lockout after failed attempts | The backend has none; outside the MVP |
| CORS in the backend | Forbidden by the BFF design (README) |
| Captures / custom design | User chose "shadcn padrão" |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| Scope | Skeleton + BFF + register/login/logout + protected shell | User chose "Esqueleto + auth (Recommended)" | y |
| UI language | Fixed pt-BR, no i18n library | User chose "pt-BR fixo (Recommended)" | y |
| Look | Default shadcn, neutral theme, sidebar + header, light/dark/system | User chose "shadcn padrão (Recommended)" | y |
| Tests | Vitest (logic/BFF) + Playwright E2E against the real backend in CI | User chose "Vitest + Playwright (Recommended)" | y |
| Organization switching | Out; the shell shows the current role only | User chose "Fora desta feature (Recommended)" | y |
| Slug on registration | Derived from the organization name until the user edits it; editable | User chose "Sugerido do nome, editável (Recommended)" | y |
| Routes | `/entrar`, `/cadastro`, `/` (home) | The UI is pt-BR and URLs are visible text | n |
| Package manager / versions | npm, Node 24, Next.js 16 (App Router), strict TypeScript | Node 24 installed; `npm` is the repo default (AGENTS) | n |
| Backend URL | `ATLAS_API_URL`, server-only, no `NEXT_PUBLIC_` prefix | The browser never talks to the API | n |
| Organization name in the shell | Not shown; `/api/me` does not return it | Avoids a backend change in this feature | n |
| Error messages | Fixed pt-BR texts defined in this spec; the backend `detail` is never shown raw | The backend answers in English | n |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: Frontend project with CI ⭐ MVP

**User Story**: As a developer, I want a `frontend/` Next.js app with lint, types, tests and CI so that every UI feature lands on a checked base.

**Why P1**: Nothing else is deliverable or verifiable without it.

**Acceptance Criteria**:

1. The `frontend/` project SHALL provide `npm run lint`, `npm run typecheck`, `npm test` (Vitest), `npm run build` and `npm run test:e2e` (Playwright), each exiting 0 on a clean checkout.
2. WHEN a pull request changes `frontend/**` THEN the `Frontend` GitHub Actions workflow SHALL run lint, typecheck, unit tests and build.
3. WHEN a pull request changes `frontend/**` THEN the `Frontend` workflow SHALL run the Playwright suite against the backend started from `backend/` (Compose + `spring-boot:run`).
4. The browser bundle SHALL NOT contain `ATLAS_API_URL` nor any request to the backend origin; all API calls SHALL go through the Next server.

**Independent Test**: CI is green on the PR; after `npm run build`, searching `.next/static` for the backend URL finds nothing.

---

### P1: Registration ⭐ MVP

**User Story**: As a visitor, I want to create my account and organization so that I can start using Atlas.

**Why P1**: The product's front door.

**Acceptance Criteria**:

1. WHEN a visitor submits `/cadastro` with valid name, e-mail, password, organization name and slug THEN the system SHALL create the account via `POST /api/auth/register`, start the session and redirect to `/`.
2. WHILE the visitor has not edited the slug field the system SHALL fill it with the slug derived from the organization name (lowercase, accents removed, each run of characters outside `a-z0-9` turned into one `-`, leading/trailing `-` removed, cut to 63 characters).
3. WHEN the visitor edits the slug field THEN the system SHALL stop deriving it from the organization name.
4. IF a field breaks the backend rule (name or organization name empty or over 120 characters after trimming; e-mail not `x@y.z` or over 254 characters; password under 12 or over 128 characters; slug not 3-63 characters of `^[a-z0-9]+(-[a-z0-9]+)*$`) THEN the system SHALL show that field's message under it and SHALL NOT call the backend.
5. IF the backend answers `409` for the e-mail THEN the system SHALL show "Este e-mail já está cadastrado." under the e-mail field.
6. IF the backend answers `409` for the slug THEN the system SHALL show "Este endereço já está em uso." under the slug field.
7. WHILE the submission is pending the system SHALL disable the submit button and label it "Criando conta…".

**Independent Test**: E2E registers a new user and reaches `/` with their name on screen; registering the same e-mail again shows the conflict message.

---

### P1: Login and logout ⭐ MVP

**User Story**: As a member, I want to sign in and out so that only I use my session.

**Why P1**: No session, no shell.

**Acceptance Criteria**:

1. WHEN a visitor submits `/entrar` with valid credentials THEN the system SHALL start the session and redirect to the `next` query parameter when it is a relative path starting with a single `/`, otherwise to `/`.
2. IF the backend answers `401` to the login THEN the system SHALL show "E-mail ou senha inválidos.", keep the e-mail and clear the password.
3. IF the backend cannot be reached or answers `5xx` THEN the system SHALL show "Serviço indisponível. Tente novamente em instantes." on the submitted form.
4. WHILE the login submission is pending the system SHALL disable the submit button and label it "Entrando…".
5. WHEN a signed-in user uses "Sair" THEN the system SHALL call `POST /api/auth/logout` with the refresh token, clear the session cookies and redirect to `/entrar`.
6. IF the logout call to the backend fails THEN the system SHALL still clear the session cookies and redirect to `/entrar`.

**Independent Test**: E2E signs in, sees the shell, uses "Sair", lands on `/entrar`, and opening `/` again redirects to `/entrar`.

---

### P1: BFF session ⭐ MVP

**User Story**: As a member, I want my session kept by the server so that tokens never reach page scripts and I am not logged out every 15 minutes.

**Why P1**: It is the security contract of the BFF design.

**Acceptance Criteria**:

1. The system SHALL keep the access and refresh tokens only in cookies with `HttpOnly`, `SameSite=Lax`, `Path=/`, and `Secure` when `NODE_ENV=production`; the access cookie SHALL expire after `expiresIn` seconds and the refresh cookie after 30 days.
2. The system SHALL NOT return an access or refresh token in any response body or non-cookie header sent to the browser.
3. WHILE there is no valid session, WHEN a visitor opens a protected route THEN the system SHALL redirect to `/entrar?next=<original path>`.
4. WHILE there is a valid session, WHEN the user opens `/entrar` or `/cadastro` THEN the system SHALL redirect to `/`.
5. WHEN the access token is missing or expired and the refresh cookie exists THEN the system SHALL call `POST /api/auth/refresh`, store the new pair in the cookies and serve the original request.
6. WHEN several requests of the same session need a refresh at the same time in one server process THEN the system SHALL call `POST /api/auth/refresh` once and reuse its result for all of them.
7. IF the backend rejects the refresh with `401` THEN the system SHALL clear the session cookies and redirect to `/entrar?next=<original path>`.
8. IF a state-changing request (login, register, logout) comes from an origin other than the app's THEN the system SHALL reject it without calling the backend.

**Independent Test**: Vitest proves rotation, single-flight and cookie clearing with a mocked `fetch`; E2E with the access cookie deleted stays signed in.

---

### P1: Authenticated shell ⭐ MVP

**User Story**: As a member, I want a consistent app frame showing who I am so that future screens have a home.

**Why P1**: The navigation base for every domain screen.

**Acceptance Criteria**:

1. WHEN a signed-in user opens `/` THEN the system SHALL show a sidebar with "Atlas Inventory" and the "Início" item, and a page reading "Olá, <name>".
2. The shell SHALL show the user's name, e-mail and role as "Proprietário" (`OWNER`), "Administrador" (`ADMIN`) or "Membro" (`MEMBER`), taken from `GET /api/me`.
3. WHEN the user picks "Claro", "Escuro" or "Sistema" in the theme control THEN the system SHALL apply that theme and keep it on the next visit.
4. WHILE the window is narrower than 768 px the system SHALL hide the sidebar behind a menu button that opens it as a sheet.
5. The pages `/entrar`, `/cadastro` and `/` SHALL have no horizontal scroll at 375 px and 1280 px wide.
6. Every form field SHALL have a visible label tied to its input, and error messages SHALL be announced to assistive technology (`aria-describedby` / `aria-invalid`).

**Independent Test**: E2E at 375 px and 1280 px opens `/`, sees "Olá, <name>" and the role; switches to "Escuro" and reloads keeping the theme.

---

## Edge Cases

- IF `ATLAS_API_URL` is not set THEN the system SHALL fail the request on the server with the "Serviço indisponível. Tente novamente em instantes." message instead of calling an undefined URL.
- IF `GET /api/me` answers `401` after a successful refresh THEN the system SHALL clear the session cookies and redirect to `/entrar`.
- IF the `next` parameter is absolute (`https://…`), protocol-relative (`//…`) or contains a backslash THEN the system SHALL redirect to `/` after login.
- WHEN the visitor double-clicks the submit button THEN the system SHALL send a single request.

## Implicit-Requirement Dimensions

| Dimension | Coverage |
| --------- | -------- |
| Input validation & bounds | Registration AC-4; `next` edge case |
| Failure / partial-failure states | Login AC-3, AC-6; `ATLAS_API_URL` edge case |
| Idempotency / retry / duplicate handling | Double-submit edge case; Registration AC-5/6 |
| Auth boundaries & rate limits | Session AC-3, AC-4, AC-8; rate limiting N/A because the backend has none (Out of Scope) |
| Concurrency / ordering | Session AC-6 (reusing a refresh token revokes its family in the backend) |
| Data lifecycle / expiry | Session AC-1, AC-5, AC-7 |
| Observability | N/A because the BFF adds no business events; Next server logs suffice for this slice |
| External-dependency failure | Login AC-3 |
| State-transition integrity | Session AC-3, AC-4, AC-7; Login AC-5 |

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| FE-01 | P1: Frontend project with CI - AC 1 | Design | Pending |
| FE-02 | P1: Frontend project with CI - AC 2 | Design | Pending |
| FE-03 | P1: Frontend project with CI - AC 3 | Design | Pending |
| FE-04 | P1: Frontend project with CI - AC 4 | Design | Pending |
| REG-01 | P1: Registration - AC 1 | Design | Pending |
| REG-02 | P1: Registration - AC 2 | Design | Pending |
| REG-03 | P1: Registration - AC 3 | Design | Pending |
| REG-04 | P1: Registration - AC 4 | Design | Pending |
| REG-05 | P1: Registration - AC 5 | Design | Pending |
| REG-06 | P1: Registration - AC 6 | Design | Pending |
| REG-07 | P1: Registration - AC 7 | Design | Pending |
| LOG-01 | P1: Login and logout - AC 1 | Design | Pending |
| LOG-02 | P1: Login and logout - AC 2 | Design | Pending |
| LOG-03 | P1: Login and logout - AC 3 | Design | Pending |
| LOG-04 | P1: Login and logout - AC 4 | Design | Pending |
| LOG-05 | P1: Login and logout - AC 5 | Design | Pending |
| LOG-06 | P1: Login and logout - AC 6 | Design | Pending |
| SES-01 | P1: BFF session - AC 1 | Design | Pending |
| SES-02 | P1: BFF session - AC 2 | Design | Pending |
| SES-03 | P1: BFF session - AC 3 | Design | Pending |
| SES-04 | P1: BFF session - AC 4 | Design | Pending |
| SES-05 | P1: BFF session - AC 5 | Design | Pending |
| SES-06 | P1: BFF session - AC 6 | Design | Pending |
| SES-07 | P1: BFF session - AC 7 | Design | Pending |
| SES-08 | P1: BFF session - AC 8 | Design | Pending |
| SHL-01 | P1: Authenticated shell - AC 1 | Design | Pending |
| SHL-02 | P1: Authenticated shell - AC 2 | Design | Pending |
| SHL-03 | P1: Authenticated shell - AC 3 | Design | Pending |
| SHL-04 | P1: Authenticated shell - AC 4 | Design | Pending |
| SHL-05 | P1: Authenticated shell - AC 5 | Design | Pending |
| SHL-06 | P1: Authenticated shell - AC 6 | Design | Pending |
| EDG-01 | Edge: `ATLAS_API_URL` missing | Design | Pending |
| EDG-02 | Edge: `/api/me` 401 after refresh | Design | Pending |
| EDG-03 | Edge: unsafe `next` | Design | Pending |
| EDG-04 | Edge: double submit | Design | Pending |

**Coverage:** 35 total, 0 mapped to tasks, 35 unmapped ⚠️ (tasks not written yet)

## Sources

- `README.md` - "Frontend e CORS": BFF with route handlers and httpOnly cookies, no CORS in the backend.
- `backend/.../access/internal/AuthController.java`, `TokenPair.java`, `MeController.java`, `AuthService.java`, `Organizations.java` - contract and validation rules.
- `backend/.../access/internal/RefreshTokens.java` - reusing a refresh token revokes its family (drives SES-06).

---

## Success Criteria

- [ ] The register → home → sign out → sign in flow passes in CI E2E against the real backend.
- [ ] No token shows up in `document.cookie`, in a response body or in the browser bundle.
