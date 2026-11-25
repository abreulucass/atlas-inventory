# Warehouses Specification

**Approved**: 2026-11-25 - user: "pode seguir"

## Problem Statement

ADR-002 places warehouses inside companies, and stock will live in warehouses, but no warehouse exists yet. This feature adds warehouses to the domain and exposes them over HTTP, under the same tenant isolation and role rules as companies.

## Goals

- [ ] An `OWNER` or `ADMIN` can create, archive and unarchive warehouses of a company of their organization; any member can list and read them.
- [ ] No request reaches a warehouse of another organization, and no warehouse points at a company of another organization.

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Per-company / per-warehouse permissions | User chose to defer them to their own feature, before stock |
| Address or other fields | User chose name + code |
| Editing name or code | Same as companies (feature 14) |
| Deleting warehouses | Archive, never delete (same as companies) |
| Cascading company archive to its warehouses | User chose "Bloquear criação": archiving a company leaves its warehouses as they are |
| Pagination, search, filter | Same as companies |
| Stock, transfers | Later features |
| Frontend | Not created yet |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| Scope | Domain and HTTP API in one feature | User chose "Uma feature só" | y |
| Fields | `name` (≤ 120) and `code` (≤ 20), both unique per company | User chose "Nome + código" | y |
| Permissions | Any member reads; only `OWNER`/`ADMIN` write; `MEMBER` writing gets `403` | User chose "Adiar" per-warehouse permissions; keeps feature 14's rule | y |
| Archived company | Creating a warehouse in it answers `409`; archiving a company does not touch its warehouses | User chose "Bloquear criação" | y |
| Endpoints | Nested: `POST` and `GET /api/companies/{companyId}/warehouses`, `GET /api/companies/{companyId}/warehouses/{id}`, `POST .../{id}/archive`, `POST .../{id}/unarchive` | User's chosen option named the nested route | y |
| Code format | Stripped and upper-cased; 1-20 characters from `A-Z`, `0-9`, `-`; anything else `400` | Short, stable identifier for movements and transfers | n |
| Name rules | Stripped, 1-120 characters; unique per company ignoring case, archived included | Same rules as company names | n |
| Code uniqueness | Unique per company, archived included; two companies may share a code | A code identifies a warehouse inside its company | n |
| Archive / unarchive in an archived company | Allowed (`204`); only creation is blocked | User's option blocked creation only | n |
| Read in an archived company | Allowed | Reading never changes state | n |
| Unknown, other-organization or malformed company id or warehouse id | `404`; a warehouse id under another company of the same organization is also `404` | Same answer as feature 14; the path must match the warehouse's company | n |
| Write in an archived organization | `409` | Same as feature 14 | n |
| Archive an archived warehouse / unarchive an active one | `204`, no change | Same as feature 14 | n |
| Response body | `{id, companyId, name, code, status}`, `status` `ACTIVE`/`ARCHIVED` | Mirrors `CompanyView` | n |
| `201` response | Body of the created warehouse and `Location: /api/companies/{companyId}/warehouses/{id}` | Same as feature 14 | n |
| Errors | RFC 9457 `ProblemDetail` | Same as features 12 and 14 | n |
| Create precedence when several rules fail | `401` → `403` role → `409` archived organization → `404` company → `400` input → `409` archived company / duplicate | The path resource is resolved before the body is judged, so Create AC 11 holds with any payload (Verifier F1) | n |
| Module | New `warehouse` module, reaching companies only through `company`'s public API | ADR-001; AD-002 created one module per aggregate | n |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: Create a warehouse ⭐ MVP

**User Story**: As an owner or admin, I want to register a warehouse in one of my organization's companies.

**Why P1**: Stock needs a place to live.

**Acceptance Criteria**:

1. WHEN an `OWNER` or `ADMIN` sends `POST /api/companies/{companyId}/warehouses` with a valid name and code for an active company of the token's organization THEN the system SHALL create the warehouse in that company and respond `201` with the warehouse body and a `Location` header.
2. WHEN the code is sent with lower-case letters or surrounding spaces THEN the system SHALL store and return it stripped and upper-cased.
3. IF the name is already used by a warehouse of the same company, ignoring case and archived included, THEN the system SHALL respond `409`.
4. IF the code, after normalization, is already used by a warehouse of the same company, archived included, THEN the system SHALL respond `409`.
5. WHEN a warehouse of another company of the same organization already uses the name or code THEN the system SHALL create the warehouse and respond `201`.
6. IF the name is blank or longer than 120 characters THEN the system SHALL respond `400`.
7. IF the code is blank, longer than 20 characters or contains a character outside `A-Z`, `0-9` and `-` after normalization THEN the system SHALL respond `400`.
8. IF the company is archived THEN the system SHALL respond `409` and create nothing.
9. IF the caller's role is `MEMBER` THEN the system SHALL respond `403` and create nothing.
10. IF the active organization is archived THEN the system SHALL respond `409` and create nothing.
11. IF the company belongs to another organization, does not exist or its id is not a UUID THEN the system SHALL respond `404` and create nothing.

**Independent Test**: an owner creates a warehouse in a company and then finds it in that company's list.

---

### P1: Read warehouses ⭐ MVP

**User Story**: As a member, I want to see the warehouses of my organization's companies.

**Why P1**: Every later stock screen starts from a warehouse.

**Acceptance Criteria**:

1. WHEN a member sends `GET /api/companies/{companyId}/warehouses` THEN the system SHALL respond `200` with that company's active warehouses ordered by name.
2. WHEN a member sends `GET /api/companies/{companyId}/warehouses?includeArchived=true` THEN the system SHALL also include its archived warehouses, ordered by name.
3. WHEN a member sends `GET /api/companies/{companyId}/warehouses/{id}` for a warehouse of that company THEN the system SHALL respond `200` with it, archived or not.
4. IF the company belongs to another organization, does not exist or its id is not a UUID THEN the system SHALL respond `404`.
5. IF the warehouse id belongs to another organization, to another company, does not exist or is not a UUID THEN the system SHALL respond `404`.
6. The system SHALL never return, in a company's list, a warehouse of another company or another organization.

**Independent Test**: two organizations each create a company with a warehouse; each list shows only its own, and each `GET` of the other's warehouse answers `404`.

---

### P1: Archive and unarchive ⭐ MVP

**User Story**: As an owner or admin, I want to archive a warehouse I no longer use and bring it back.

**Why P1**: Archive replaces delete, so it is the only way out.

**Acceptance Criteria**:

1. WHEN an `OWNER` or `ADMIN` sends `POST /api/companies/{companyId}/warehouses/{id}/archive` THEN the system SHALL archive it and respond `204`.
2. WHEN an `OWNER` or `ADMIN` sends `POST /api/companies/{companyId}/warehouses/{id}/unarchive` THEN the system SHALL make it active and respond `204`.
3. IF the caller's role is `MEMBER` THEN the system SHALL respond `403` and leave the warehouse unchanged.
4. IF the company or warehouse belongs to another organization, the warehouse belongs to another company, or either does not exist THEN the system SHALL respond `404`.
5. IF the active organization is archived THEN the system SHALL respond `409` and leave the warehouse unchanged.
6. WHEN the warehouse's company is archived THEN the system SHALL still archive and unarchive the warehouse with `204`.
7. WHEN a company is archived THEN the system SHALL leave the status of its warehouses unchanged.

**Independent Test**: archive a warehouse, it leaves the default list; unarchive, it returns.

---

## Edge Cases

- The system SHALL reject, at the database, a warehouse whose company belongs to another organization than the warehouse's tenant.
- The system SHALL keep warehouse rows of other organizations invisible to the application role through forced RLS, as for companies (ADR-002).
- WHEN two creates with the same name or code in the same company run at once THEN the system SHALL answer one `201` and one `409`.
- WHEN a member's role changes to `MEMBER` THEN the system SHALL refuse their next write with `403` even with an access token issued before the change.
- IF a request has no valid token THEN the system SHALL respond `401` (existing rule, AUTH-05).

---

## Implicit-requirement sweep

| Dimension | Resolution |
| --------- | ---------- |
| Input validation & bounds | Create 2, 6, 7; malformed ids (Create 11, Read 4-5) |
| Failure / partial failure | N/A because every write is one statement in one transaction |
| Idempotency / retry | Archive/unarchive idempotent (assumption); create is not (duplicate → `409`) |
| Auth boundaries & rate limits | Role (Create 9, Archive 3, Edge 4), tenant (Read 4-6, Edges 1-2); rate limit N/A, out of scope since feature 12 |
| Concurrency | Concurrent creates resolved by unique constraints (Edge 3) |
| Data lifecycle | Archive instead of delete; company archive does not cascade (Archive 7) |
| Observability | N/A because nothing consumes metrics yet |
| External-dependency failure | N/A because there is none |
| State-transition integrity | active ↔ archived only (Archive 1-2); creation guarded by company status (Create 8) |

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| WH-01 | P1: Create (1-7, 11) | Design | Pending |
| WH-02 | P1: Read (1-6) | Design | Pending |
| WH-03 | P1: Archive (1-2, 4, 6-7) | Design | Pending |
| WH-04 | Create 9, Archive 3, Edge 4 (role) | Design | Pending |
| WH-05 | Create 8, 10, Archive 5 (archived company / organization) | Design | Pending |
| WH-06 | Edges 1-3 (database integrity, RLS, concurrency) | Design | Pending |

**Coverage:** 6 total, 0 mapped to tasks, 6 unmapped ⚠️

## Sources

- Issue #16 - scope
- ADR-002 - organization → company → warehouse; RLS on every tenant table; `warehouse_id` scope is application authorization
- Feature 14 - HTTP conventions, role rule, `ProblemDetail`, archive semantics
- Feature 10 - name uniqueness and archive rules for companies

## Success Criteria

- [ ] An HTTP test with two organizations, two companies and the three roles shows each rule above.
