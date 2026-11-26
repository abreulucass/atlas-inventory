# Product Catalog Specification

**Approved**: 2026-11-26 - user: "pode seguir"

## Problem Statement

Stock, reservations and purchases all count *something*, and that something does not exist yet. This feature adds the organization's catalog: flat categories and products with a SKU, a name, a fixed unit of measure and an optional category. The catalog belongs to the organization (tenant) and is shared by all of its companies; stock per warehouse comes in a later feature.

## Goals

- [ ] An `OWNER` or `ADMIN` can create, edit, archive and unarchive products and create, archive and unarchive categories; any member can list and read them.
- [ ] Two concurrent edits of the same product never silently overwrite each other: the stale one answers `409`.
- [ ] The product list stays usable with thousands of products: paginated, searchable by name or SKU, filterable by category and status.
- [ ] No request reaches a product or category of another organization, and no product points at a category of another organization.

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Suppliers | User chose "Produtos + categorias": suppliers arrive with purchasing |
| Price, cost, barcode, description | User chose "SKU + nome + unidade + categoria" |
| Category hierarchy | User chose "categoria plana" |
| Free-text or custom units | User chose "Unidade fixa" |
| Editing SKU or unit | User chose "SKU e unidade fixos" |
| Editing (renaming) a category | Not asked for; same as companies and warehouses |
| Deleting products or categories | Archive, never delete (same as companies) |
| Pagination of categories | A flat category list stays small |
| Catalog visibility by company/warehouse grants | The catalog belongs to the organization; grants (feature 20) restrict companies and warehouses only |
| Stock, movements | Later features |
| Frontend | Not created yet |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| Scope | Products and categories in one feature | User chose "Produtos + categorias" | y |
| Ownership | Catalog belongs to the organization (tenant), shared by its companies | User chose "Organização" | y |
| Product fields | `sku`, `name`, `unit`, optional `categoryId` | User chose "SKU + nome + unidade + categoria" | y |
| Units and categories | Fixed unit list; flat categories | User chose "Unidade fixa, categoria plana" | y |
| Editing | `name` and `categoryId` editable with optimistic locking; `sku` and `unit` immutable | User chose "Edita nome/categoria; SKU e unidade fixos" | y |
| Product list | Paginated, search by name or SKU, filter by category and status | User chose "Paginada + busca + filtro" | y |
| Permissions | Any member of the organization reads the whole catalog regardless of grants; only `OWNER`/`ADMIN` write; `MEMBER` writing gets `403` | Same role rule as features 14 and 16; grants scope companies/warehouses, not the catalog | n |
| Unit list | `UN`, `CX`, `PC`, `KG`, `G`, `L`, `ML`, `M`; `KG`, `G`, `L`, `ML`, `M` are fractional, the rest whole. Response carries `unit` and `fractional` | Covers common retail/warehouse units; `fractional` is what stock will need | n |
| SKU format | Stripped and upper-cased; 1-40 characters from `A-Z`, `0-9`, `-`, `.`, `_`; anything else `400` | Stable identifier for movements and integrations | n |
| SKU uniqueness | Unique per organization, archived included | A SKU identifies one product in the catalog | n |
| Product name | Stripped, 1-200 characters; not unique | Variants may share a name; SKU is the identity | n |
| Category name | Stripped, 1-80 characters; unique per organization ignoring case, archived included | Same rule as company names | n |
| Archived category | Archiving is allowed with products in it; those products keep it. Assigning an archived category on create or edit answers `409` | Archive hides a category from new use without rewriting history | n |
| Unknown, other-organization or malformed `categoryId` in a body | `404` | Same answer as a path id of another organization | n |
| Edit endpoint | `PATCH /api/products/{id}` with `version` (required) and any of `name`, `categoryId`; an absent field is unchanged; `"categoryId": null` removes the category | Partial update, as chosen with "PATCH" | n |
| Version | Response carries `version` (integer); every change to the product (edit, archive, unarchive) increments it; `PATCH` with a different `version` answers `409` and changes nothing; missing `version` answers `400` | Optimistic locking chosen by the user | n |
| Edit an archived product | `409` | An archived product is frozen until unarchived | n |
| `PATCH` with no editable field | `400` | Nothing to change | n |
| `PATCH` with `sku` or `unit` | `400` | They are immutable; silently ignoring would hide a client bug | n |
| Product list parameters | `page` (0-based, default 0), `size` (1-100, default 20), `q`, `categoryId`, `status` (`ACTIVE` default, `ARCHIVED`, `ALL`); invalid values `400` | Common pagination shape; capped size protects the database | n |
| Search `q` | Stripped; blank means no search; matches products whose name contains `q` ignoring case, or whose SKU starts with `q` upper-cased | Names are searched by fragment, SKUs by prefix | n |
| Product list order | By name ignoring case, then by id | Stable order across pages | n |
| Product list body | `{items, page, size, totalItems, totalPages}` | Enough for a paginated UI | n |
| `categoryId` filter of an unknown or other-organization category | `200` with an empty page | A filter narrows; it does not look up | n |
| Category list | `GET /api/categories` ordered by name; `includeArchived=true` includes archived | Same shape as warehouses | n |
| Endpoints | `POST`/`GET /api/categories`, `GET /api/categories/{id}`, `POST /api/categories/{id}/archive`, `/unarchive`; `POST`/`GET /api/products`, `GET /api/products/{id}`, `PATCH /api/products/{id}`, `POST /api/products/{id}/archive`, `/unarchive` | Same style as features 14 and 16 | n |
| Response bodies | Category `{id, name, status}`; product `{id, sku, name, unit, fractional, categoryId, status, version}`; `status` `ACTIVE`/`ARCHIVED` | Mirrors existing views | n |
| `201` response | Body of the created resource and `Location` header | Same as features 14 and 16 | n |
| Archive an archived resource / unarchive an active one | `204`, no change (version unchanged) | Same as features 14 and 16 | n |
| Write in an archived organization | `409` | Same as features 14 and 16 | n |
| Unknown, other-organization or malformed path id | `404` | Same as features 14 and 16 | n |
| Errors | RFC 9457 `ProblemDetail` | Same as previous features | n |
| Write precedence when several rules fail | `401` → `403` role → `409` archived organization → `404` path resource → `400` input → `404` body `categoryId` → `409` archived product / archived category / stale version / duplicate | Same order as feature 16; the path resource is resolved before the body is judged | n |
| Module | New `catalog` module using `access`'s public API only | ADR-001 | n |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: Manage categories ⭐ MVP

**User Story**: As an owner or admin, I want to group my products in categories so that the catalog stays organized.

**Why P1**: Products reference categories; they must exist first.

**Acceptance Criteria**:

1. WHEN an `OWNER` or `ADMIN` sends `POST /api/categories` with a valid name THEN the system SHALL create the category in the token's organization and respond `201` with `{id, name, status}` and a `Location` header.
2. IF the category name is already used in the organization, ignoring case and archived included, THEN the system SHALL respond `409` and create nothing.
3. IF the category name is blank or longer than 80 characters after stripping THEN the system SHALL respond `400` and create nothing.
4. WHEN any member sends `GET /api/categories` THEN the system SHALL return the organization's active categories ordered by name.
5. WHEN `includeArchived=true` is sent THEN the system SHALL also return archived categories.
6. WHEN any member sends `GET /api/categories/{id}` for a category of their organization THEN the system SHALL return it.
7. WHEN an `OWNER` or `ADMIN` sends `POST /api/categories/{id}/archive` or `/unarchive` THEN the system SHALL set the status to `ARCHIVED` or `ACTIVE` and respond `204`.
8. IF the caller's role is `MEMBER` on any category write THEN the system SHALL respond `403` and change nothing.
9. IF the category belongs to another organization, does not exist or its id is not a UUID THEN the system SHALL respond `404`.
10. IF the active organization is archived on any category write THEN the system SHALL respond `409` and change nothing.

**Independent Test**: an owner creates a category and finds it in the list.

---

### P1: Create a product ⭐ MVP

**User Story**: As an owner or admin, I want to register products with a SKU and a unit so that stock can count them later.

**Why P1**: Every later feature counts products.

**Acceptance Criteria**:

1. WHEN an `OWNER` or `ADMIN` sends `POST /api/products` with a valid SKU, name, unit and no category THEN the system SHALL create the product in the token's organization with `version` 0 and respond `201` with the product body and a `Location` header.
2. WHEN the request carries the id of an active category of the organization THEN the system SHALL create the product in that category.
3. WHEN the SKU is sent with lower-case letters or surrounding spaces THEN the system SHALL store and return it stripped and upper-cased.
4. IF the SKU, after normalization, is already used by a product of the organization, archived included, THEN the system SHALL respond `409` and create nothing.
5. IF the SKU is blank, longer than 40 characters or contains a character outside `A-Z`, `0-9`, `-`, `.`, `_` after normalization THEN the system SHALL respond `400`.
6. IF the name is blank or longer than 200 characters after stripping THEN the system SHALL respond `400`.
7. IF the unit is missing or not one of `UN`, `CX`, `PC`, `KG`, `G`, `L`, `ML`, `M` THEN the system SHALL respond `400`.
8. The system SHALL return `fractional` `true` for `KG`, `G`, `L`, `ML`, `M` and `false` for `UN`, `CX`, `PC`.
9. IF the category is archived THEN the system SHALL respond `409` and create nothing.
10. IF the category belongs to another organization, does not exist or is not a UUID THEN the system SHALL respond `404` and create nothing.
11. IF the caller's role is `MEMBER` THEN the system SHALL respond `403` and create nothing.
12. IF the active organization is archived THEN the system SHALL respond `409` and create nothing.
13. WHEN a product of another organization already uses the SKU THEN the system SHALL create the product and respond `201`.

**Independent Test**: an owner creates a product and reads it back by id.

---

### P1: Edit a product with optimistic locking ⭐ MVP

**User Story**: As an owner or admin, I want to fix a product's name or category without overwriting a colleague's concurrent change.

**Why P1**: The project's focus is data consistency; last-write-wins is the bug we are avoiding.

**Acceptance Criteria**:

1. WHEN an `OWNER` or `ADMIN` sends `PATCH /api/products/{id}` with the current `version` and a valid `name` THEN the system SHALL change the name, increment `version` by 1 and respond `200` with the product body.
2. WHEN the request carries `categoryId` of an active category THEN the system SHALL move the product to that category.
3. WHEN the request carries `"categoryId": null` THEN the system SHALL remove the product's category.
4. WHEN a field is absent from the request THEN the system SHALL leave it unchanged.
5. IF `version` differs from the product's current version THEN the system SHALL respond `409` and leave the whole product row unchanged.
6. WHEN two requests edit the same product with the same `version` concurrently THEN the system SHALL apply exactly one and answer the other `409`.
7. IF `version` is missing THEN the system SHALL respond `400`.
8. IF the request carries neither `name` nor `categoryId` THEN the system SHALL respond `400`.
9. IF the request carries `sku` or `unit` THEN the system SHALL respond `400` and change nothing.
10. IF the product is archived THEN the system SHALL respond `409` and change nothing.
11. IF the new category is archived THEN the system SHALL respond `409`; IF it belongs to another organization, does not exist or is not a UUID THEN the system SHALL respond `404`.
12. IF the name is blank or longer than 200 characters after stripping THEN the system SHALL respond `400`.
13. IF the caller's role is `MEMBER` THEN the system SHALL respond `403` and change nothing.
14. IF the product belongs to another organization, does not exist or its id is not a UUID THEN the system SHALL respond `404`.
15. IF the active organization is archived THEN the system SHALL respond `409` and change nothing.

**Independent Test**: two clients read version 0; the first edit succeeds with version 1, the second answers `409`.

---

### P1: List and find products ⭐ MVP

**User Story**: As a member, I want to page through, search and filter the catalog so that I find a product among thousands.

**Why P1**: Without search the catalog is unusable at real size.

**Acceptance Criteria**:

1. WHEN any member sends `GET /api/products` with no parameters THEN the system SHALL return the first 20 active products of the organization ordered by name ignoring case, then id, with `{items, page, size, totalItems, totalPages}`.
2. WHEN `page` and `size` are sent THEN the system SHALL return that page of that size.
3. IF `page` is negative, or `size` is below 1 or above 100, or either is not an integer THEN the system SHALL respond `400`.
4. WHEN `q` is sent THEN the system SHALL return only products whose name contains `q` ignoring case or whose SKU starts with `q` upper-cased.
5. WHEN `categoryId` is sent THEN the system SHALL return only products of that category.
6. IF `categoryId` is not a UUID THEN the system SHALL respond `400`.
7. WHEN `categoryId` names an unknown or other-organization category THEN the system SHALL respond `200` with an empty page.
8. WHEN `status` is `ARCHIVED` or `ALL` THEN the system SHALL return only archived products or products of both statuses.
9. IF `status` is not `ACTIVE`, `ARCHIVED` or `ALL` THEN the system SHALL respond `400`.
10. WHEN any member sends `GET /api/products/{id}` for a product of their organization THEN the system SHALL return it, archived included.
11. IF the product belongs to another organization, does not exist or its id is not a UUID THEN the system SHALL respond `404`.
12. The system SHALL never return a product of another organization in the list.

**Independent Test**: with 25 products, page 1 of size 20 returns 5 items and `totalPages` 2.

---

### P2: Archive and unarchive a product

**User Story**: As an owner or admin, I want to retire a product without losing its history.

**Why P2**: Needed before stock, but no stock exists yet.

**Acceptance Criteria**:

1. WHEN an `OWNER` or `ADMIN` sends `POST /api/products/{id}/archive` for an active product THEN the system SHALL set its status to `ARCHIVED`, increment `version` and respond `204`.
2. WHEN `POST /api/products/{id}/unarchive` is sent for an archived product THEN the system SHALL set its status to `ACTIVE`, increment `version` and respond `204`.
3. WHEN an archived product is archived again, or an active one unarchived, THEN the system SHALL respond `204` and leave the whole row unchanged.
4. IF the caller's role is `MEMBER` THEN the system SHALL respond `403` and change nothing.
5. IF the product belongs to another organization, does not exist or its id is not a UUID THEN the system SHALL respond `404`.
6. IF the active organization is archived THEN the system SHALL respond `409` and change nothing.

**Independent Test**: an owner archives a product; it leaves the default list and appears with `status=ARCHIVED`.

---

### P1: Tenant isolation in the database ⭐ MVP

**User Story**: As the platform, I want the database itself to refuse cross-tenant catalog data.

**Why P1**: ADR-002 requires RLS on every tenant table.

**Acceptance Criteria**:

1. The system SHALL store categories and products in tables with `tenant_id` and forced RLS, so that a session with another tenant sees none of their rows.
2. IF a product row references a category of another organization THEN the database SHALL reject it.
3. The database SHALL reject a second product with the same SKU in the same organization, and a second category whose name matches ignoring case in the same organization.

**Independent Test**: as `atlas_app` with tenant A set, a query on products returns none of tenant B's rows.

---

## Edge Cases

- WHEN an archived category still has products THEN the system SHALL keep returning its id on those products and SHALL keep them visible in the `categoryId` filter.
- WHEN `q` contains `%` or `_` THEN the system SHALL match them literally.
- WHEN `page` is past the last page THEN the system SHALL respond `200` with empty `items` and the real `totalItems`.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| CAT-01 | P1: Manage categories | Design | Pending |
| CAT-02 | P1: Create a product | Design | Pending |
| CAT-03 | P1: Edit a product with optimistic locking | Design | Pending |
| CAT-04 | P1: List and find products | Design | Pending |
| CAT-05 | P2: Archive and unarchive a product | Design | Pending |
| CAT-06 | P1: Tenant isolation in the database | Design | Pending |
| CAT-07 | Edge Cases | Design | Pending |

**Coverage:** 7 total, 0 mapped to tasks, 7 unmapped ⚠️

---

## Sources

- Issue #22 - scope and acceptance summary.
- ADR-001, ADR-002 - module boundaries and tenant isolation.
- Features 14 and 16 - role rule, archive semantics, error answers.

---

## Success Criteria

- [ ] Every criterion above has a passing test in `./mvnw verify`.
- [ ] A concurrent edit of the same product never loses an update.
