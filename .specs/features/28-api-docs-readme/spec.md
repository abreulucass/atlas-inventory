# API Documentation and MVP README Specification

**Approved**: 2026-11-26 - user: "sim, pode seguir"

## Problem Statement

The backend MVP is complete, but the frontend has no machine-readable contract to type its calls against, and the README still says the backend "ainda não tem funcionalidades de domínio", lists features that will not ship in the MVP and omits most endpoints. This closes the backend's loose ends before the frontend starts.

## Goals

- [ ] A running backend serves an OpenAPI document and Swagger UI that describe every endpoint, including the JSON bodies the controllers read as raw text.
- [ ] The README states what exists, what is roadmap, how the frontend will reach the API, and lists every endpoint.

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| CORS | User chose "BFF no Next, sem CORS": the browser never calls the API directly |
| Generated TypeScript client | Belongs to the frontend feature |
| Committed `openapi.json` snapshot | The frontend can read it from a running backend; deferred |
| Changing how controllers parse bodies | Raw parsing keeps feature 22-26's precedence rules; only documentation is added |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| How the frontend reaches the API | Next.js route handlers call the backend server-side (BFF), tokens in httpOnly cookies; no CORS | User chose "BFF no Next, sem CORS" | y |
| OpenAPI | `springdoc-openapi-starter-webmvc-ui` 3.2.1 (built on Spring Boot 4.2.0), `/v3/api-docs` and `/swagger-ui.html` | User chose "Sim, spec + Swagger UI" | y |
| README features | Split into "Feito" and "Roadmap" (suppliers, reservations, lots/expiry, purchasing, dashboard on Roadmap) | User chose "Separar 'Feito' e 'Roadmap'" | y |
| When the docs are served | Only when `ATLAS_API_DOCS_ENABLED=true`; off by default, so production does not publish its surface unless asked; the README's dev commands set it | "público só em dev" | n |
| Access to docs paths when enabled | `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html` public (no `Bearer`); when disabled they answer `404` | Swagger UI must load before login | n |
| Authorization in the document | A `bearerAuth` HTTP bearer (JWT) scheme applied to every operation except `/api/auth/register`, `/login`, `/refresh`, `/logout` | Lets Swagger UI call protected endpoints | n |
| Raw-text bodies | Documented with doc-only schemas: product create, product update, category create, member access grant, stock movement, stock transfer; each lists its fields and required ones as the spec of its feature defines | Request types for the frontend without touching parsing | n |
| `Idempotency-Key` | Documented as a required header on movement and transfer `POST` | Feature 24/26 contract | n |
| Module boundaries | OpenAPI configuration lives in the `access` module beside `SecurityConfiguration`; doc-only schemas live next to their controllers in each module's `internal` package | No new cross-module dependency | n |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: Machine-readable API contract ⭐ MVP

**User Story**: As the frontend developer, I want an OpenAPI document of the running backend so that I can type every call and try endpoints in Swagger UI.

**Why P1**: The frontend is next and depends on it.

**Acceptance Criteria**:

1. WHILE `ATLAS_API_DOCS_ENABLED` is `true` the system SHALL answer `GET /v3/api-docs` with `200` and an OpenAPI 3 JSON document, without a `Bearer` token.
2. WHILE `ATLAS_API_DOCS_ENABLED` is `true` the system SHALL answer `GET /swagger-ui.html` (following redirects) with `200` and the Swagger UI page, without a `Bearer` token.
3. WHILE `ATLAS_API_DOCS_ENABLED` is unset or `false` the system SHALL answer `GET /v3/api-docs` and `GET /swagger-ui.html` with `404`.
4. The document SHALL list every path and method the controllers expose under `/api`.
5. The document SHALL declare a `bearerAuth` HTTP bearer scheme and apply it to every operation except `POST /api/auth/register`, `/api/auth/login`, `/api/auth/refresh` and `/api/auth/logout`.
6. The document SHALL give the request body of product create, product update, category create, member access grant, stock movement create and stock transfer create an object schema with the fields their features accept.
7. The document SHALL declare `Idempotency-Key` as a required header on `POST /api/companies/{companyId}/warehouses/{warehouseId}/movements` and `POST /api/transfers`.

**Independent Test**: with the flag on, `GET /v3/api-docs` lists `/api/transfers` with an `items` array in its request schema and `bearerAuth` on it.

---

### P1: README for the MVP ⭐ MVP

**User Story**: As a visitor of the repository, I want the README to say what the project does today and how to run it.

**Why P1**: It is the portfolio's front page.

**Acceptance Criteria**:

1. The README SHALL no longer contain "ainda não tem funcionalidades de domínio".
2. The README SHALL list implemented features under "Feito" and suppliers, reservations, lots and expiry, purchasing and replenishment, and dashboard under "Roadmap".
3. The README SHALL list every endpoint under `/api` with who can call it, including companies, warehouses, categories, products, movements, balances and transfers.
4. The README SHALL state that the frontend reaches the API through a Next.js BFF and that the backend has no CORS on purpose.
5. The README SHALL show how to enable and open the API docs (`ATLAS_API_DOCS_ENABLED=true`, `/swagger-ui.html`).

**Independent Test**: reading the README, every controller path appears in the endpoint table.

---

## Edge Cases

- WHEN a `Bearer` token is sent to `/v3/api-docs` with the flag on THEN the system SHALL still answer `200`.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| DOC-01 | P1: Machine-readable API contract | Execute | Pending |
| DOC-02 | P1: README for the MVP | Execute | Pending |
| DOC-03 | Edge Cases | Execute | Pending |

**Coverage:** 3 total, 0 mapped to tasks, 3 unmapped ⚠️

---

## Sources

- User answers: "BFF no Next, sem CORS", "Sim, spec + Swagger UI", "Separar 'Feito' e 'Roadmap'".
- Issue #28 - scope and acceptance summary.
- Maven Central: `springdoc-openapi` 3.2.1 parent is `spring-boot-starter-parent` 4.2.0.
- Features 22, 24, 26 specs - the raw-body fields.

---

## Success Criteria

- [ ] `./mvnw verify` green with the docs tests.
- [ ] The frontend feature can generate its types from `/v3/api-docs`.

---

## Checks

**Build**: `cd backend && ./mvnw -B -q -DskipTests package`
**Run backend**: `cd backend && ./mvnw -B verify`

**C1** - With the flag on, `GET /v3/api-docs` without a token answers `200` with an OpenAPI 3 document (DOC-01, AC 1)
Proof: run backend | io.github.abreulucass.atlas.ApiDocsIT.apiDocsArePublicWhenEnabled -- Time elapsed

**C2** - With the flag on, `GET /swagger-ui.html` (redirects followed) without a token answers `200` with the Swagger UI page (DOC-01, AC 2)
Proof: run backend | io.github.abreulucass.atlas.ApiDocsIT.swaggerUiIsPublicWhenEnabled -- Time elapsed

**C3** - With the flag unset, `GET /v3/api-docs` and `GET /swagger-ui.html` answer `404` (DOC-01, AC 3)
Proof: run backend | io.github.abreulucass.atlas.ApiDocsDisabledIT.docsAre404WhenDisabled -- Time elapsed

**C4** - Every path and method of every `@RestController` under `/api`, read from Spring's handler mappings, appears in the document (DOC-01, AC 4)
Proof: run backend | io.github.abreulucass.atlas.ApiDocsIT.documentListsEveryApiOperation -- Time elapsed

**C5** - `bearerAuth` is an HTTP bearer scheme on every operation except the four public auth operations, which have none (DOC-01, AC 5)
Proof: run backend | io.github.abreulucass.atlas.ApiDocsIT.bearerAuthOnEveryProtectedOperation -- Time elapsed

**C6** - Each of the six raw-body operations has an object request schema with its feature's fields (DOC-01, AC 6)
Proof: run backend | io.github.abreulucass.atlas.ApiDocsIT.rawBodiesHaveObjectSchemas -- Time elapsed

**C7** - `Idempotency-Key` is a required header on movement and transfer `POST` (DOC-01, AC 7)
Proof: run backend | io.github.abreulucass.atlas.ApiDocsIT.idempotencyKeyRequiredOnMovementAndTransfer -- Time elapsed

**C8** - A `Bearer` token on `/v3/api-docs` with the flag on still answers `200` (DOC-03, Edge 1)
Proof: run backend | io.github.abreulucass.atlas.ApiDocsIT.apiDocsAnswerWithBearerToken -- Time elapsed

**C9** - README no longer says the backend has no domain features (DOC-02, AC 1)
Proof: `! grep -q "ainda não tem funcionalidades de domínio" README.md`

**C10** - README has "Feito" and "Roadmap" sections with the five roadmap items (DOC-02, AC 2)
Proof: `grep -q "^## Feito" README.md && grep -q "^## Roadmap" README.md && for t in ornecedores eserva otes ompras ashboard; do awk '/^## Roadmap/{r=1;next} /^## /{r=0} r' README.md | grep -q "$t" || exit 1; done`

**C11** - README endpoint table names every controller base path (DOC-02, AC 3)
Proof: `for p in /api/auth/register /api/me /api/members /api/companies /warehouses /api/categories /api/products /movements /stock /api/transfers; do grep -q "| .*$p" README.md || exit 1; done`

**C12** - README states the Next.js BFF and no CORS on purpose (DOC-02, AC 4)
Proof: `grep -q "BFF" README.md && grep -q "CORS" README.md`

**C13** - README shows how to enable and open the docs (DOC-02, AC 5)
Proof: `grep -q "ATLAS_API_DOCS_ENABLED=true" README.md && grep -q "/swagger-ui.html" README.md`
