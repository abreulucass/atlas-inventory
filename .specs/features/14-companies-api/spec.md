# Companies API Specification

**Approved**: 2026-11-24 - user: "sim, pode seguir"

## Problem Statement

Companies exist in the domain (`company.Companies`) and requests now run in the token's tenant, but nothing exposes companies over HTTP and the membership role is stored without being enforced. This feature adds the companies endpoints and the first role rule.

## Goals

- [ ] An authenticated member can list and read their organization's companies; an `OWNER` or `ADMIN` can create, archive and unarchive them.
- [ ] No request reaches another organization's company.

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Editing name or CNPJ | User chose endpoints without edit |
| Deleting companies | Domain archives, never deletes (feature 10) |
| Pagination, search, filter | User chose an unpaginated list ordered by name |
| Per-company permissions | Arrive with warehouses (ADR-002) |
| Frontend | Not created yet |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| Endpoints | `POST /api/companies`, `GET /api/companies?includeArchived=`, `GET /api/companies/{id}`, `POST /api/companies/{id}/archive`, `POST /api/companies/{id}/unarchive` | User chose "Atuais + busca por id" | y |
| Permissions | Any member reads; only `OWNER`/`ADMIN` write; `MEMBER` writing gets `403` | User chose "Ler todos; escrever OWNER/ADMIN" | y |
| Listing | Unpaginated, ordered by name; archived only with `includeArchived=true` | User chose "Sem paginação, por nome" | y |
| Another organization's company | `404`, same as nonexistent | User chose "404" | y |
| Role source | Read from the membership in the database per request (not from the token) | A demoted member loses write access at once, not after 15 min | n |
| Write in an archived organization | `409` | The request is valid; the resource state forbids it | n |
| Archive an archived company / unarchive an active one | `204`, no change (idempotent) | Matches the current domain methods | n |
| Response body | `{id, name, cnpj, status}`, `cnpj` as the 14 stored characters (numeric or alphanumeric, upper case, no mask, per feature 10) or `null`, `status` `ACTIVE`/`ARCHIVED` | Mirrors `CompanyView`; corrected from "14 digits" after Verifier F8 - the domain already accepts alphanumeric CNPJ | n |
| `201` response | Body of the created company and `Location: /api/companies/{id}` | REST convention | n |
| Malformed id in path | `404` | Same answer as an unknown id | n |
| Errors | RFC 9457 `ProblemDetail` | Same as auth (feature 12) | n |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: Create a company ⭐ MVP

**User Story**: As an owner or admin, I want to register a company in my organization.

**Acceptance Criteria**:

1. WHEN an `OWNER` or `ADMIN` sends `POST /api/companies` with a valid name and optional valid CNPJ THEN the system SHALL create the company in the token's organization and respond `201` with the company body and a `Location` header.
2. IF the name or CNPJ is already used by a company of the same organization (archived included) THEN the system SHALL respond `409`.
3. IF the name is blank or longer than 120 characters, or the CNPJ fails its check digits THEN the system SHALL respond `400`.
4. IF the caller's role is `MEMBER` THEN the system SHALL respond `403` and create nothing.
5. IF the active organization is archived THEN the system SHALL respond `409` and create nothing.

**Independent Test**: an owner creates a company and then finds it in the list.

---

### P1: Read companies ⭐ MVP

**User Story**: As a member, I want to see my organization's companies.

**Acceptance Criteria**:

1. WHEN a member sends `GET /api/companies` THEN the system SHALL respond `200` with the organization's active companies ordered by name.
2. WHEN a member sends `GET /api/companies?includeArchived=true` THEN the system SHALL also include archived companies, ordered by name.
3. WHEN a member sends `GET /api/companies/{id}` for a company of the active organization THEN the system SHALL respond `200` with it, archived or not.
4. IF the id belongs to another organization, does not exist or is not a UUID THEN the system SHALL respond `404`.
5. The system SHALL never return a company of another organization in the list.

**Independent Test**: users of two organizations each create a company; each list shows only their own, and each `GET` of the other's id answers `404`.

---

### P1: Archive and unarchive ⭐ MVP

**User Story**: As an owner or admin, I want to archive a company I no longer use and bring it back.

**Acceptance Criteria**:

1. WHEN an `OWNER` or `ADMIN` sends `POST /api/companies/{id}/archive` THEN the system SHALL archive it and respond `204`.
2. WHEN an `OWNER` or `ADMIN` sends `POST /api/companies/{id}/unarchive` THEN the system SHALL make it active and respond `204`.
3. IF the caller's role is `MEMBER` THEN the system SHALL respond `403` and leave the company unchanged.
4. IF the company belongs to another organization or does not exist THEN the system SHALL respond `404`.
5. IF the active organization is archived THEN the system SHALL respond `409`.

**Independent Test**: archive a company, it leaves the default list; unarchive, it returns.

---

## Edge Cases

- WHEN a member's role changes to `MEMBER` THEN the system SHALL refuse their next write with `403` even with an access token issued before the change.
- IF a request has no valid token THEN the system SHALL respond `401` (existing rule, AUTH-05).

---

## Implicit-requirement sweep

| Dimension | Resolution |
| --------- | ---------- |
| Input validation & bounds | Create 3, malformed id (Read 4) |
| Failure / partial failure | N/A because every write is one statement in one transaction |
| Idempotency / retry | Archive/unarchive idempotent (assumption); create is not (duplicate name → `409`) |
| Auth boundaries & rate limits | Role rules (Create 4, Archive 3), tenant (Read 4-5), role re-read per request (Edge 1); rate limit N/A, out of scope since feature 12 |
| Concurrency | Concurrent creates with one name: the unique constraint yields one `201` and one `409` (Create 2) |
| Data lifecycle | Archive instead of delete (feature 10) |
| Observability | N/A because nothing consumes metrics yet |
| External-dependency failure | N/A because there is none |
| State-transition integrity | active ↔ archived only (Archive 1-2) |

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| COAPI-01 | P1: Create (1-3) | Design | Pending |
| COAPI-02 | P1: Read (1-5) | Design | Pending |
| COAPI-03 | P1: Archive (1-2, 4) | Design | Pending |
| COAPI-04 | Create 4, Archive 3, Edge 1 (role) | Design | Pending |
| COAPI-05 | Create 5, Archive 5 (archived organization) | Design | Pending |

**Coverage:** 5 total, 0 mapped to tasks, 5 unmapped ⚠️

## Sources

- Issue #14 - scope
- Feature 10 - company rules (name/CNPJ unique per organization, archive)
- Feature 12 - authentication, `TenantContext` per request, `ProblemDetail`

## Success Criteria

- [ ] An HTTP test with two organizations and the three roles shows each rule above.
