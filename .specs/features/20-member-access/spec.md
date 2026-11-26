# Member Access by Company and Warehouse Specification

**Approved**: 2026-11-25 - user: "pode seguir"

## Problem Statement

Today every member sees every company and warehouse of the organization. A `MEMBER` working in one site sees all of them, and stock (next features) would follow the same rule. This feature lets managers say which companies and warehouses each `MEMBER` can see, and hides everything else from them.

## Goals

- [ ] An `OWNER` or `ADMIN` can grant and revoke a `MEMBER`'s access to a company (all of its warehouses) or to a single warehouse.
- [ ] A `MEMBER` lists and reads only the companies and warehouses granted to them; anything else answers `404`.
- [ ] `OWNER` and `ADMIN` keep seeing the whole organization.

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Permission levels per grant (view / operate) | User chose "Só acesso, por ora"; levels arrive with stock, which needs them |
| Restricting `ADMIN` to some companies | User chose "Só MEMBER" |
| Default access for new members | User chose "Nenhum": a new `MEMBER` sees nothing until granted |
| Listing who can see a given company or warehouse | User chose the API under the member |
| `MEMBER` writes to companies or warehouses | Unchanged: still `403` |
| Pagination, frontend | Same as previous features |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| Who is restricted | Only `MEMBER`; `OWNER` and `ADMIN` see the whole organization | User chose "Só MEMBER" | y |
| Granularity | A company grant covers the company and all its warehouses, including later ones. A warehouse grant covers that warehouse, and the warehouse's company becomes readable | User chose "Empresa e depósito" | y |
| Grant level | A grant only gives visibility; there are no levels | User chose "Só acesso, por ora" | y |
| Default | A `MEMBER` with no grants sees no company and no warehouse | User chose "Nenhum" | y |
| No access | A company or warehouse outside the `MEMBER`'s grants answers `404` and is left out of lists | User chose "404" | y |
| Who grants | `OWNER` and `ADMIN` | User chose "OWNER e ADMIN" | y |
| API | `GET /api/members/{userId}/access`, `POST /api/members/{userId}/access` with `{companyId}` or `{warehouseId}`, `DELETE /api/members/{userId}/access/companies/{companyId}`, `DELETE /api/members/{userId}/access/warehouses/{warehouseId}` | User chose "Sob o membro"; the exact paths are the agent's | n |
| Grant target's role | Any member can hold grants. They take effect only while the member's role is `MEMBER`, and are kept through promotion and demotion | Avoids losing a setup when someone is promoted and demoted back | n |
| Reading someone's grants | Managers read anyone's; a `MEMBER` reads only their own, others `403` | A member must know what they can see; managers need everyone's | n |
| Grant body | Exactly one of `companyId`, `warehouseId` (UUID); none, both or malformed `400` | One grant per call | n |
| Grant response | `201` with `{type, companyId, warehouseId}` (`type` `COMPANY` or `WAREHOUSE`; `warehouseId` `null` for a company grant) and `Location` of the grant | Same `201` convention as previous features | n |
| `GET` access response | `200` with the member's grants, company grants first, then warehouse grants, each ordered by id | Deterministic order | n |
| Duplicate grant | `409`, nothing changes | Same as adding an existing member | n |
| Company or warehouse of another organization or unknown, in the body | `404` | Same answer as everywhere else | n |
| Archived company or warehouse | Can be granted and revoked; visibility follows the existing archive rules (`includeArchived`, `GET` by id) | Archive is reversible | n |
| Revoking a grant that does not exist | `404` | Same as removing an unknown member | n |
| Member removed | Their grants in that organization are deleted with the membership | No orphan grants | n |
| Overlap | A warehouse grant in a company that is also granted is allowed; revoking one keeps the other effective | Grants are independent | n |
| `MEMBER` write to a company or warehouse | `403` before any visibility check (existing precedence) | Unchanged behaviour | n |
| Write in an archived organization | Grant and revoke `409` | Same as previous features | n |
| Precedence on grant/revoke | `401` → `403` caller not a manager → `409` archived organization → `404` target member → `400` body → `404` company/warehouse → `409` duplicate | Same order as feature 18: path before body | n |
| Errors | RFC 9457 `ProblemDetail` | Same as previous features | n |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: Grant and revoke access ⭐ MVP

**User Story**: As an owner or admin, I want to choose which companies and warehouses a member can see.

**Why P1**: Without grants a `MEMBER` sees nothing after this feature.

**Acceptance Criteria**:

1. WHEN an `OWNER` or `ADMIN` sends `POST /api/members/{userId}/access` with the `companyId` of a company of the organization THEN the system SHALL create a company grant and respond `201` with `{type: "COMPANY", companyId, warehouseId: null}`.
2. WHEN an `OWNER` or `ADMIN` sends `POST /api/members/{userId}/access` with the `warehouseId` of a warehouse of the organization THEN the system SHALL create a warehouse grant and respond `201` with `{type: "WAREHOUSE", companyId, warehouseId}`, where `companyId` is the warehouse's company.
3. IF the caller's role is `MEMBER` THEN the system SHALL respond `403` to grant and revoke, and change nothing.
4. IF the body has neither or both of `companyId` and `warehouseId`, or a value that is not a UUID THEN the system SHALL respond `400` and create nothing.
5. IF the company or warehouse belongs to another organization or does not exist THEN the system SHALL respond `404` and create nothing.
6. IF the same grant already exists THEN the system SHALL respond `409` and change nothing.
7. IF the user id is not a member of the organization, does not exist or is not a UUID THEN the system SHALL respond `404` to grant, revoke and read.
8. IF the active organization is archived THEN the system SHALL respond `409` to grant and revoke, and change nothing.
9. WHEN an `OWNER` or `ADMIN` sends `DELETE /api/members/{userId}/access/companies/{companyId}` or `.../access/warehouses/{warehouseId}` for an existing grant THEN the system SHALL delete it and respond `204`.
10. IF the grant to revoke does not exist THEN the system SHALL respond `404`.
11. WHEN a manager sends `GET /api/members/{userId}/access` THEN the system SHALL respond `200` with that member's grants, company grants first, then warehouse grants, each ordered by id.
12. WHEN a `MEMBER` sends `GET /api/members/{userId}/access` for their own user id THEN the system SHALL respond `200` with their grants.
13. IF a `MEMBER` reads another member's grants THEN the system SHALL respond `403`.
14. WHEN an archived company or warehouse is granted THEN the system SHALL create the grant and respond `201`.

**Independent Test**: an owner grants a company to a member, reads the member's access and sees it, revokes it and sees it gone.

---

### P1: A MEMBER sees only what is granted ⭐ MVP

**User Story**: As a member, I want to see only the companies and warehouses I work with.

**Why P1**: It is the point of the feature.

**Acceptance Criteria**:

1. WHILE a `MEMBER` has no grants the system SHALL answer `GET /api/companies` with an empty list.
2. WHEN a `MEMBER` with a company grant sends `GET /api/companies` THEN the system SHALL list that company and no ungranted company.
3. WHEN a `MEMBER` with a company grant lists that company's warehouses THEN the system SHALL list all of them, including warehouses created after the grant.
4. WHEN a `MEMBER` with only a warehouse grant sends `GET /api/companies` and `GET /api/companies/{companyId}` THEN the system SHALL include and return the warehouse's company.
5. WHEN a `MEMBER` with only a warehouse grant lists that company's warehouses THEN the system SHALL list only the granted warehouses.
6. IF a `MEMBER` sends `GET` for a company or warehouse outside their grants, or lists the warehouses of an ungranted company THEN the system SHALL respond `404`.
7. WHEN a grant is revoked THEN the system SHALL apply it on the `MEMBER`'s next request, even with an access token issued before the change.
8. WHEN a `MEMBER` holds both a company grant and a warehouse grant in that company and the company grant is revoked THEN the system SHALL keep only the granted warehouse visible.
9. WHILE a member's role is `OWNER` or `ADMIN` the system SHALL show them every company and warehouse of the organization, whatever their grants.
10. WHEN an `ADMIN` with grants is demoted to `MEMBER` THEN the system SHALL show them only their grants on the next request.
11. The system SHALL keep `includeArchived` and archived reads working as before within a `MEMBER`'s grants.

**Independent Test**: two companies with warehouses; a member granted one company and one warehouse of the other sees exactly those, and gets `404` on the rest.

---

## Edge Cases

- WHEN a member is removed THEN the system SHALL delete their grants in that organization, so adding them back starts with none.
- WHEN a grant and the removal of its member run at once THEN the system SHALL leave no grant for a user who is not a member.
- IF a request has no valid token THEN the system SHALL respond `401` (existing rule, AUTH-05).

---

## Implicit-requirement sweep

| Dimension | Resolution |
| --------- | ---------- |
| Input validation & bounds | Grant AC 4, 7 |
| Failure / partial failure | N/A because each grant or revoke is one row in one transaction |
| Idempotency / retry | Duplicate grant `409` (Grant AC 6); revoking a missing grant `404` (Grant AC 10) |
| Auth boundaries & rate limits | Who grants (Grant AC 3), who reads (Grant AC 12-13), visibility (Visibility AC 1-11); tenant (Grant AC 5); rate limit N/A, out of scope since feature 12 |
| Concurrency | Grant vs member removal (Edge 2); duplicate grants settled by a unique key |
| Data lifecycle | Grants deleted with the membership (Edge 1); archived targets keep grants (Grant AC 14) |
| Observability | N/A because nothing consumes metrics yet |
| External-dependency failure | N/A because there is none |
| State-transition integrity | Role changes switch visibility at once (Visibility AC 9-10); revocation at once (Visibility AC 7) |

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| ACC-01 | Grant 1-2, 4-7, 14 (grant) | Design | Pending |
| ACC-02 | Grant 9-10 (revoke) | Design | Pending |
| ACC-03 | Grant 11-13 (read grants) | Design | Pending |
| ACC-04 | Grant 3, 8 (who grants, archived organization) | Design | Pending |
| ACC-05 | Visibility 1-6, 8, 11 (companies and warehouses filtered) | Design | Pending |
| ACC-06 | Visibility 7, 9-10 (immediate effect, role changes) | Design | Pending |
| ACC-07 | Edges 1-2 (lifecycle with membership) | Design | Pending |

**Coverage:** 7 total, 0 mapped to tasks, 7 unmapped ⚠️

## Sources

- Issue #20 - scope
- ADR-002 - memberships grant access to companies and warehouses; `warehouse_id` scope is application authorization
- Feature 18 - members, hierarchy, member lock, precedence
- Features 14 and 16 - company and warehouse endpoints, `404` for invisible resources

## Success Criteria

- [ ] An HTTP test with two companies, warehouses and the three roles shows each visibility rule above.
