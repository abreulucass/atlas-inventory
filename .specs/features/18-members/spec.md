# Organization Members Specification

**Approved**: 2026-11-25 - user: "pode seguir"

## Problem Statement

Today a membership exists only for the person who registered the organization (`OWNER`); nobody else can join, so roles `ADMIN` and `MEMBER` are unreachable and per-company / per-warehouse permissions (next feature) cannot be exercised. This feature lets managers add registered users to their organization, change their role and remove them, with removal taking effect immediately.

## Goals

- [ ] An `OWNER` or `ADMIN` can add a registered user to the organization by e-mail, and the user can then log in to it.
- [ ] Every member can list the organization's members; role changes and removals follow the `OWNER` > `ADMIN` > `MEMBER` hierarchy and never leave the organization without an `OWNER`.
- [ ] A removed member's next request to the organization is refused, even with an access token issued before the removal.

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Invitations (token, link, e-mail delivery) | User chose "Adicionar por e-mail" |
| Creating an account for someone else | Same choice; the person registers first |
| Leaving an organization on one's own | User chose "sem saída" |
| Registering without creating an organization | `register` keeps creating one; a separate change if needed |
| Per-company / per-warehouse access | Next feature (agreed split into two features) |
| Ownership transfer as its own operation | Covered by promoting another `OWNER` and then demoting oneself |
| Pagination, search | Same as companies and warehouses |
| Frontend | Not created yet |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| Scope | Organization members only; per-company / per-warehouse scope is the next feature | User chose "Duas features" | y |
| Joining | `OWNER`/`ADMIN` adds an already registered user by e-mail with a role; no invitation | User chose "Adicionar por e-mail" | y |
| Hierarchy | `OWNER` manages anyone and grants any role; `ADMIN` adds and removes `MEMBER`s only and grants only `MEMBER`; `MEMBER` manages nobody; at least one `OWNER` always remains | User chose "OWNER tudo; ADMIN só MEMBER" | y |
| Removal | Revokes the removed user's refresh tokens for the organization, and every authenticated request checks the membership in the database | User chose "Recusar na hora" | y |
| Listing | Any member lists name, e-mail and role of every member | User chose "Todos listam; sem saída" | y |
| Leaving | No endpoint; removing one's own membership answers `403` | User chose "sem saída"; `DELETE` must not become a back door to it | n |
| Endpoints | `GET /api/members`, `POST /api/members`, `PATCH /api/members/{userId}`, `DELETE /api/members/{userId}` | Tenant comes from the token, so no organization id in the path (ADR-002); a member is identified by its user id | n |
| Response body | `{userId, name, email, role}`; list ordered by name, then e-mail | Name is what a person scans; e-mail breaks ties | n |
| `201` response | Body of the new member and `Location: /api/members/{userId}` | Same as features 14 and 16 | n |
| `PATCH` response | `200` with the member's body; same role as before is `200` with no change | Idempotent update | n |
| E-mail input | Stripped and lower-cased before lookup, as at login; blank or invalid `400` | Same normalization as feature 12 | n |
| Role input | `OWNER`, `ADMIN` or `MEMBER`; anything else `400` | The roles that exist | n |
| Unknown e-mail on add | `404` | No invitation flow; the person must register first | n |
| Unknown, other-organization or malformed user id | `404` | Same answer as features 14 and 16 | n |
| Removed member's request | `401` on any authenticated endpoint; `refresh` with a revoked token `401` (existing rule) | The token no longer matches a membership; same answer as an invalid token | n |
| Write in an archived organization | `409` | Same as features 14 and 16 | n |
| Precedence when several rules fail | `401` → `403` caller is `MEMBER` → `409` archived organization → then `POST`: `400` input → `404` unknown e-mail → `403` hierarchy → `409` already a member; `PATCH`/`DELETE`: `404` target → `400` role → `403` self-removal / hierarchy → `409` last `OWNER` | Path resource before body, as in feature 16; on `POST` the e-mail must be valid before it is looked up | n |
| Caller removed or demoted while their request waits | Rules are judged after the organization's member lock is taken, so the waiting request sees the new state (`403` when no longer allowed) | Never authorize on a stale role | n |
| Module | `access` owns members (AD-002) | Memberships already live there | n |
| Errors | RFC 9457 `ProblemDetail` | Same as features 12, 14, 16 | n |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: List members ⭐ MVP

**User Story**: As a member, I want to see who belongs to my organization and with which role.

**Why P1**: Managers need the list to pick whom to change or remove.

**Acceptance Criteria**:

1. WHEN a member sends `GET /api/members` THEN the system SHALL respond `200` with `{userId, name, email, role}` of every member of the token's organization, ordered by name and then e-mail.
2. The system SHALL never return, in the list, a member of another organization.

**Independent Test**: two organizations with different members; each list shows only its own.

---

### P1: Add a member ⭐ MVP

**User Story**: As an owner or admin, I want to add a registered user to my organization with a role.

**Why P1**: Without it nobody else can join.

**Acceptance Criteria**:

1. WHEN an `OWNER` sends `POST /api/members` with the e-mail of a registered user and any role THEN the system SHALL create the membership and respond `201` with the member's body and `Location: /api/members/{userId}`.
2. WHEN an `ADMIN` sends `POST /api/members` with a registered user's e-mail and role `MEMBER` THEN the system SHALL create the membership and respond `201`.
3. IF an `ADMIN` sends role `OWNER` or `ADMIN` THEN the system SHALL respond `403` and create nothing.
4. IF the caller's role is `MEMBER` THEN the system SHALL respond `403` and create nothing.
5. WHEN the e-mail is sent with upper-case letters or surrounding spaces THEN the system SHALL find the user by the stripped, lower-cased e-mail.
6. IF no registered user has the e-mail THEN the system SHALL respond `404` and create nothing.
7. IF the user is already a member of the organization THEN the system SHALL respond `409` and leave the existing membership unchanged.
8. IF the e-mail is blank or invalid, or the role is missing or not `OWNER`, `ADMIN` or `MEMBER` THEN the system SHALL respond `400` and create nothing.
9. IF the active organization is archived THEN the system SHALL respond `409` and create nothing.
10. WHEN a user is added THEN the system SHALL let them log in to and switch to the organization.

**Independent Test**: an owner adds a registered user; the user switches to the organization and appears in `GET /api/members`.

---

### P1: Change a member's role ⭐ MVP

**User Story**: As an owner, I want to change a member's role.

**Why P1**: Promoting and demoting is how responsibility moves.

**Acceptance Criteria**:

1. WHEN an `OWNER` sends `PATCH /api/members/{userId}` with a role THEN the system SHALL set that member's role and respond `200` with the member's body.
2. WHEN the role sent equals the current one THEN the system SHALL respond `200` and change nothing.
3. IF an `ADMIN` changes the role of an `OWNER` or `ADMIN`, or sends role `OWNER` or `ADMIN` THEN the system SHALL respond `403` and change nothing.
4. IF the caller's role is `MEMBER` THEN the system SHALL respond `403` and change nothing.
5. IF the change would leave the organization without an `OWNER` THEN the system SHALL respond `409` and change nothing.
6. IF the user id is not a member of the organization, does not exist or is not a UUID THEN the system SHALL respond `404`.
7. IF the role is missing or not `OWNER`, `ADMIN` or `MEMBER` THEN the system SHALL respond `400` and change nothing.
8. IF the active organization is archived THEN the system SHALL respond `409` and change nothing.
9. WHEN a member is demoted to `MEMBER` THEN the system SHALL refuse their next company or warehouse write with `403`, even with an access token issued before the change.

**Independent Test**: an owner promotes a member to admin; the admin can create a company; the owner demotes them back and the next create answers `403`.

---

### P1: Remove a member ⭐ MVP

**User Story**: As an owner or admin, I want to remove someone from my organization and have their access end at once.

**Why P1**: Losing a member must not leave a 15-minute window of access.

**Acceptance Criteria**:

1. WHEN an `OWNER` sends `DELETE /api/members/{userId}` for another member THEN the system SHALL delete the membership and respond `204`.
2. WHEN an `ADMIN` sends `DELETE /api/members/{userId}` for a `MEMBER` THEN the system SHALL delete the membership and respond `204`.
3. IF an `ADMIN` removes an `OWNER` or `ADMIN` THEN the system SHALL respond `403` and keep the membership.
4. IF the caller's role is `MEMBER` THEN the system SHALL respond `403` and keep the membership.
5. IF the caller removes their own membership THEN the system SHALL respond `403` and keep it.
6. IF the user id is not a member of the organization, does not exist or is not a UUID THEN the system SHALL respond `404`.
7. IF the active organization is archived THEN the system SHALL respond `409` and keep the membership.
8. WHEN a member is removed THEN the system SHALL revoke their refresh tokens for that organization, so `POST /api/auth/refresh` with them answers `401`.
9. WHEN a member is removed THEN the system SHALL answer `401` to their next request to any authenticated endpoint with an access token of that organization issued before the removal.
10. WHEN a member is removed THEN the system SHALL keep their user account and their memberships in other organizations.

**Independent Test**: an owner removes a member; the member's still-valid access token gets `401` on `GET /api/companies`, and their refresh token gets `401`.

---

## Edge Cases

- WHEN the last two `OWNER`s at once each demote or remove the other THEN the system SHALL apply exactly one, refuse the other with `403` or `409`, and leave at least one `OWNER`.
- WHEN a refresh or login runs at the same time as the removal of its membership THEN the system SHALL leave no live refresh token for the removed membership.
- WHEN two requests at once add the same user THEN the system SHALL answer one `201` and one `409`.
- IF a request has no valid token THEN the system SHALL respond `401` (existing rule, AUTH-05).

---

## Implicit-requirement sweep

| Dimension | Resolution |
| --------- | ---------- |
| Input validation & bounds | Add 5, 8; Change 7; malformed user ids (Change 6, Remove 7) |
| Failure / partial failure | Removal deletes the membership and revokes refresh tokens in one transaction (Remove 8, Edge 2) |
| Idempotency / retry | Same-role change is a no-op (Change 2); add is not idempotent (`409`, Add 7); removing a removed member is `404` |
| Auth boundaries & rate limits | Hierarchy (Add 3-4, Change 3-4, Remove 3-5); tenant (List 2); immediate revocation (Remove 8-9, Change 9); rate limit N/A, out of scope since feature 12 |
| Concurrency | Last-`OWNER` race, token issue vs removal, duplicate add (Edges 1-3) |
| Data lifecycle | Membership deleted on removal; user and other memberships kept (Remove 10) |
| Observability | N/A because nothing consumes metrics yet |
| External-dependency failure | N/A because there is none (no e-mail delivery) |
| State-transition integrity | Any role ↔ any role under the hierarchy; at least one `OWNER` (Change 5, Remove 5, Edge 1) |

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| MEM-01 | P1: List (1-2) | Design | Pending |
| MEM-02 | P1: Add (1-2, 5-10) | Design | Pending |
| MEM-03 | P1: Change role (1-2, 6-9) | Design | Pending |
| MEM-04 | P1: Remove (1-2, 6-7, 10) | Design | Pending |
| MEM-05 | Hierarchy: Add 3-4, Change 3-4, Remove 3-5 | Design | Pending |
| MEM-06 | Last `OWNER`: Change 5, Edge 1 | Design | Pending |
| MEM-07 | Immediate revocation: Remove 8-9, Edge 2 | Design | Pending |
| MEM-08 | Edge 3 (concurrent add) | Design | Pending |

**Coverage:** 8 total, 0 mapped to tasks, 8 unmapped ⚠️

## Sources

- Issue #18 - scope
- ADR-002 - user with global identity joins organizations through memberships; tenant only from the authenticated context
- Feature 12 - roles, e-mail normalization, refresh-token revocation, `Permissions` reads the role from the database
- Features 14 and 16 - HTTP conventions, `ProblemDetail`, archived-organization rule

## Success Criteria

- [ ] An HTTP test with two organizations and the three roles shows each rule above, including a removed member's old access token answering `401`.
