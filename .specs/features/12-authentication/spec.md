# Authentication Specification

**Approved**: 2026-11-24 - user: "pode seguri"

## Problem Statement

The backend has organizations, companies and tenant isolation (ADR-002), but no users and no HTTP API: nothing can reach the data, and nothing sets `TenantContext` per request. This feature adds self-hosted identity in the `access` module so every later feature has an authenticated user and an active organization.

## Goals

- [ ] A person can sign up, log in and call an authenticated endpoint that runs in their active organization's tenant.
- [ ] The tenant of a request comes only from a signed token issued after a membership check.

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Invitations / adding users to an existing organization | Separate feature (user: "segue as recomendações") |
| Password reset, e-mail verification | Need e-mail delivery; later feature |
| MFA, social login | Not needed for the first cut |
| Login rate limiting / lockout | Deferred by the user; later hardening feature |
| Per-company / per-warehouse permissions | Arrive with warehouses (ADR-002) |
| Role-based authorization of endpoints | Role is stored and exposed only; enforcement comes with the first endpoints that need it |
| Frontend screens | Frontend not created yet |
| HTTP API for companies | Separate feature; this one only proves the tenant wiring via `/api/me` |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| Identity provider | Self-hosted: e-mail + password, JWT signed by the backend | User chose recommendation 1a | y |
| Session format | Access JWT valid 15 min carrying user id and active organization id; opaque refresh token stored hashed, rotated on each use, valid 30 days | User chose recommendation 2a | y |
| Entry into the system | Public sign-up creates user + organization + `OWNER` membership | User chose recommendation 3a | y |
| Roles | One fixed role per membership: `OWNER`, `ADMIN`, `MEMBER` | User chose recommendation 4a | y |
| Password rules | 12 to 128 characters, no composition rules; hashed with Argon2id | NIST 800-63B favors length over composition; Argon2id chosen by user: "vamos usar o Argon2id" | y |
| E-mail | Trimmed and lower-cased before storage and lookup; unique globally | One login per person (ADR-002) | n |
| Active organization at login | The membership created first | Deterministic; user switches afterwards | n |
| Refresh token reuse | Reusing an already rotated token revokes every token of that session chain | Standard theft detection for rotated refresh tokens | n |
| Membership removed or organization archived while a token is live | Access token stays valid until it expires (≤ 15 min); refresh and switch re-check membership | Stateless access tokens; short TTL bounds the window | n |
| Archived organization | Login and switch into it are allowed (reads are allowed, writes are already refused by `requireWritable`) | Matches the archive rule of feature 10 | n |
| Signing key | HMAC-SHA256 secret from configuration; the app refuses to start without one; tests use a fixed test key | Single service, no key distribution needed | n |
| Error format | RFC 9457 `ProblemDetail` | Spring default; frontend gets one shape | n |
| Logs | Never log passwords or tokens | Security baseline | n |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: Sign up ⭐ MVP

**User Story**: As a new customer, I want to create my account and my organization in one step so that I can start using the system.

**Acceptance Criteria**:

1. WHEN a client sends `POST /api/auth/register` with a valid name, e-mail, password, organization name and organization slug THEN the system SHALL create the user, the organization and an `OWNER` membership and respond `201` with an access token and a refresh token whose active organization is the new one.
2. IF the e-mail is already registered THEN the system SHALL respond `409` and create no user, organization or membership.
3. IF the organization slug is already taken or invalid THEN the system SHALL respond `409` (taken) or `400` (invalid) and create no user, organization or membership.
4. IF the password has fewer than 12 or more than 128 characters THEN the system SHALL respond `400`.
5. IF the e-mail is not a valid address or the name is blank THEN the system SHALL respond `400`.
6. The system SHALL store only a password hash, never the password.

**Independent Test**: register, then decode the access token and find the new organization in it.

---

### P1: Log in ⭐ MVP

**User Story**: As a registered user, I want to log in so that I get tokens for my organization.

**Acceptance Criteria**:

1. WHEN a client sends `POST /api/auth/login` with a registered e-mail (any letter case) and its password THEN the system SHALL respond `200` with an access token valid for 15 minutes and a refresh token, the active organization being the user's first membership.
2. IF the e-mail is unknown or the password is wrong THEN the system SHALL respond `401` with the same body in both cases.

**Independent Test**: register, log in with the upper-cased e-mail, receive tokens.

---

### P1: Authenticated requests run in the active tenant ⭐ MVP

**User Story**: As a logged-in user, I want my requests to see only my active organization's data.

**Acceptance Criteria**:

1. WHEN a client calls `GET /api/me` with a valid access token THEN the system SHALL respond `200` with the user's id, name, e-mail, active organization id and role in it.
2. WHILE a request carries a valid access token the system SHALL run it inside `TenantContext` with the token's active organization, so tenant-scoped reads see only that organization's rows.
3. IF a request to a non-public endpoint has no token, a malformed token, a token with a bad signature or an expired token THEN the system SHALL respond `401`.
4. The system SHALL take the tenant only from the access token and ignore any organization id sent in headers, parameters or body.
5. The system SHALL expose `/api/auth/register`, `/api/auth/login`, `/api/auth/refresh`, `/api/auth/logout` and `/actuator/health` without a token.

**Independent Test**: two users in two organizations each create a company; each sees only their own through the tenant context of their token.

---

### P1: Refresh and log out ⭐ MVP

**User Story**: As a logged-in user, I want my session to continue past 15 minutes and to end it when I leave.

**Acceptance Criteria**:

1. WHEN a client sends `POST /api/auth/refresh` with a live refresh token THEN the system SHALL respond `200` with a new access token and a new refresh token for the same active organization and invalidate the one sent.
2. IF a refresh token that was already rotated is sent again THEN the system SHALL respond `401` and invalidate every refresh token of that session chain.
3. IF the refresh token is unknown, expired (older than 30 days) or revoked THEN the system SHALL respond `401`.
4. IF the user no longer has a membership in the token's active organization THEN the system SHALL respond `401` to the refresh.
5. WHEN two refresh requests with the same live token arrive concurrently THEN the system SHALL accept at most one.
6. WHEN a client sends `POST /api/auth/logout` with a refresh token THEN the system SHALL invalidate it and respond `204`, also when the token is already invalid.
7. The system SHALL store refresh tokens only as hashes.

**Independent Test**: log in, refresh, refresh again with the first token → 401, and the second token is dead too.

---

### P1: Switch active organization ⭐ MVP

**User Story**: As a user who belongs to several organizations, I want to switch the active one.

**Acceptance Criteria**:

1. WHEN an authenticated client sends `POST /api/auth/switch-organization` with an organization id where the user has a membership THEN the system SHALL respond `200` with new tokens whose active organization is that one.
2. IF the user has no membership in that organization (including when it does not exist) THEN the system SHALL respond `403` and issue no token.

**Independent Test**: a user with memberships in A and B (seeded, since invitations are out of scope) switches from A to B and `/api/me` shows B.

---

## Edge Cases

- WHEN two sign-ups with the same e-mail arrive concurrently THEN the system SHALL create exactly one user and answer the other `409`.
- IF any step of sign-up fails THEN the system SHALL persist nothing of it (one transaction).

---

## Implicit-requirement sweep

| Dimension | Resolution |
| --------- | ---------- |
| Input validation & bounds | Sign-up criteria 3-5, password and e-mail assumptions |
| Failure / partial failure | Sign-up is one transaction (Edge Cases) |
| Idempotency / retry | Logout idempotent (Refresh 6); refresh is deliberately not retry-safe (rotation) |
| Auth boundaries & rate limits | Tenant criteria 3-5, switch 2; rate limit out of scope |
| Concurrency | Concurrent sign-up and concurrent refresh |
| Data lifecycle / expiry | Access 15 min, refresh 30 days; cleanup of expired rows N/A because volume is negligible at this stage |
| Observability | No secrets in logs (assumption); metrics N/A because nothing consumes them yet |
| External-dependency failure | N/A because there is no external dependency |
| State-transition integrity | Refresh token: live → rotated/revoked, never back (Refresh 1-3) |

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| AUTH-01 | P1: Sign up (1, 6) | Design | Pending |
| AUTH-02 | P1: Sign up (2-5), Edge Cases | Design | Pending |
| AUTH-03 | P1: Log in | Design | Pending |
| AUTH-04 | P1: Authenticated requests (1, 2, 4) | Design | Pending |
| AUTH-05 | P1: Authenticated requests (3, 5) | Design | Pending |
| AUTH-06 | P1: Refresh and log out (1, 3, 4, 7) | Design | Pending |
| AUTH-07 | P1: Refresh and log out (2, 5) | Design | Pending |
| AUTH-08 | P1: Refresh and log out (6) | Design | Pending |
| AUTH-09 | P1: Switch active organization | Design | Pending |

**Coverage:** 9 total, 0 mapped to tasks, 9 unmapped ⚠️

## Sources

- Issue #12 - scope
- ADR-002 - global users and memberships, tenant only from the authenticated context, backend validates membership before issuing context
- `.specs/STATE.md` AD-001, AD-002 - `TenantContext` and the `access` module

## Success Criteria

- [ ] An HTTP-level test signs up two users, and each one's `/api/me` and tenant-scoped data show only their own organization.
- [ ] No endpoint other than the public ones answers without a valid token.
