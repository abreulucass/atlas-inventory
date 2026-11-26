# Stock Movements and Balance Specification

**Approved**: 2026-11-26 - user: "pode seguir"

## Problem Statement

Products exist (feature 22) and warehouses exist (feature 16), but nothing says how much of a product sits in a warehouse. This feature adds stock movements (entry, exit, adjustment) per warehouse and the balance they produce. A movement recorded twice, or two concurrent exits that both pass the balance check, is exactly the bug that costs money; so every movement is idempotent by key and the balance never goes below zero, even under concurrency.

## Goals

- [ ] A member who sees a warehouse can record entries, exits and adjustments there and read its balance and movement history.
- [ ] Retrying the same movement with the same `Idempotency-Key` never records it twice.
- [ ] No interleaving of concurrent movements loses an update or leaves a balance below zero.
- [ ] No request reaches a movement or balance of another organization, or of a warehouse the caller does not see.

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Transfer between warehouses | User chose "Entrada, saída e ajuste": transfer comes next, on the same core |
| Reservations, lots, expiry dates | Later features (README) |
| Editing, deleting or reversing a movement | Movements are an immutable audit trail; a mistake is corrected by a new adjustment |
| Negative balance | User chose "Bloqueia com 409" |
| Optional or expiring idempotency keys | User chose "Idempotency-Key obrigatória" (stored forever) |
| Cost, price, stock valuation | Catalog has no price (feature 22) |
| Minimum stock, alerts, reorder | Purchasing and dashboard features |
| Domain events to other modules | No consumer yet |
| Frontend | Not created yet |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| Movement types | `IN` (entry), `OUT` (exit), `ADJUSTMENT` | User chose "Entrada, saída e ajuste" | y |
| Negative balance | An `OUT` or negative `ADJUSTMENT` that would leave the balance below 0 answers `409` and records nothing | User chose "Bloqueia com 409" | y |
| Who writes | `OWNER`/`ADMIN` in any warehouse; `MEMBER` only in warehouses visible through their grants (feature 20). Reading balance and history follows the same visibility | User chose "OWNER/ADMIN + MEMBER com acesso" | y |
| Idempotency | `Idempotency-Key` header required on `POST`; missing or invalid answers `400`. Same key with the same request answers the original `201` body without recording again; same key with a different request answers `422`. Key unique per organization, kept forever | User chose "Idempotency-Key obrigatória" | y |
| Invisible warehouse for a `MEMBER` | `404`, never `403` | Same answer as `GET` of a warehouse in feature 20 | n |
| Endpoints | `POST`/`GET /api/companies/{companyId}/warehouses/{warehouseId}/movements`, `GET /api/companies/{companyId}/warehouses/{warehouseId}/stock`, `GET /api/products/{id}/stock` | Warehouses are nested under companies (feature 16) | n |
| Movement body | `{type, productId, quantity, note}`; `note` optional for `IN`/`OUT`, required for `ADJUSTMENT` (the reason) | An adjustment without a reason is not auditable | n |
| Quantity sign | `IN` and `OUT` take a positive `quantity`; `ADJUSTMENT` takes a non-zero signed `quantity` (a delta, not a counted total) | A delta does not depend on the balance read before it, so a retry or a race cannot set a stale total | n |
| Quantity format | JSON number, absolute value greater than 0 and at most 1 000 000 000, at most 3 decimal places; for a non-fractional unit (`UN`, `CX`, `PC`) it must be whole; anything else `400` | Units already say `fractional` (feature 22) | n |
| Note | Stripped; at most 500 characters; blank means absent | Free-text reason or reference | n |
| Idempotency key format | 1-100 characters, printable ASCII (`0x21`-`0x7E`) | Fits a UUID or a client reference | n |
| "Same request" | Same warehouse, `type`, `productId`, `quantity` (numerically equal) and `note` after stripping | What the movement records; header order and whitespace do not matter | n |
| Key scope | One key per organization, regardless of the user or warehouse; the replay returns the original movement to any member who sees its warehouse | A key names one business operation in the organization | n |
| Replay of a key used in a warehouse the caller does not see | `404` for the path warehouse first; a key used in another warehouse answers `422` (different request) | Path is resolved before the key | n |
| Movement response | `201` with `{id, warehouseId, productId, type, quantity, balanceAfter, note, createdBy, createdAt}` and a `Location` header; `quantity` is the signed effect on the balance (`OUT` negative) | `balanceAfter` lets a client show the result without a second read | n |
| Single movement read | `GET .../movements/{id}` returns the movement body; `404` when not in that warehouse | Target of `Location` | n |
| Archived resources | A movement in an archived company, archived warehouse or for an archived product answers `409`; reading balance and history stays allowed | Archive freezes new use without hiding history | n |
| Archived organization | Any movement answers `409` | Same as features 14-22 | n |
| Unknown, other-organization or malformed `productId` in the body | `404` | Same as `categoryId` in feature 22 | n |
| Unknown, other-organization or malformed path id | `404` | Same as features 14-22 | n |
| Warehouse balance list | `GET .../stock` with `page` (0-based, default 0) and `size` (1-100, default 20); items `{productId, sku, name, unit, quantity, updatedAt}` for every product that ever moved in the warehouse (zero included), ordered by SKU; body `{items, page, size, totalItems, totalPages}` | Same paging as products (feature 22) | n |
| Product balance across warehouses | `GET /api/products/{id}/stock` returns `{productId, total, warehouses: [{companyId, warehouseId, quantity}]}` over the warehouses the caller sees, ordered by company then warehouse code; `total` sums only those | A `MEMBER` must not learn totals of warehouses they do not see | n |
| Movement history | `GET .../movements` with `page`, `size` as above and optional `productId` and `type`; ordered by `createdAt` descending, then id descending | Newest first for an audit trail | n |
| Invalid list parameters | `400`; a `productId` filter that is a UUID but unknown answers `200` with an empty page | Same as `categoryId` filter in feature 22 | n |
| Write precedence when several rules fail | `401` → `409` archived organization → `404` path company/warehouse (or invisible) → `400` idempotency key → `400` body → replay `201` / `422` key reuse → `404` body `productId` → `400` non-whole quantity for a non-fractional unit → `409` archived company / warehouse / product → `409` insufficient balance | House order (feature 22); a replay answers before state checks so a retry after the product was archived still gets its original result; the whole-quantity rule needs the product, so it follows its lookup | n |
| Module | New `stock` module using the public APIs of `access`, `company`, `warehouse` and `catalog` only | ADR-001 | n |
| Errors | RFC 9457 `ProblemDetail` | Same as previous features | n |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: Record a movement ⭐ MVP

**User Story**: As a member who works in a warehouse, I want to record entries, exits and adjustments so that the balance matches what is on the shelf.

**Why P1**: Without movements there is no balance.

**Acceptance Criteria**:

1. WHEN an `OWNER` or `ADMIN` sends `POST /api/companies/{companyId}/warehouses/{warehouseId}/movements` with a new `Idempotency-Key` and `{"type":"IN","productId":<active product>,"quantity":10}` THEN the system SHALL record the movement, raise the warehouse balance of that product by 10 and respond `201` with the movement body, `balanceAfter` equal to the new balance, and a `Location` header.
2. WHEN a valid `OUT` with quantity `q` is sent and the balance is at least `q` THEN the system SHALL lower the balance by `q` and respond `201` with `quantity` equal to `-q`.
3. WHEN a valid `ADJUSTMENT` with a signed non-zero `quantity` and a `note` is sent THEN the system SHALL add the signed quantity to the balance and respond `201`.
4. IF an `OUT` or negative `ADJUSTMENT` would leave the balance below 0 THEN the system SHALL respond `409`, record no movement and leave the balance unchanged.
5. WHEN a `MEMBER` with a grant to the warehouse (or to its company) sends a valid movement THEN the system SHALL record it and respond `201`.
6. IF a `MEMBER` without a grant to the warehouse or its company sends a movement THEN the system SHALL respond `404` and record nothing.
7. WHEN a product with no movement in the warehouse receives its first `IN` THEN the system SHALL start its balance at 0 before applying the movement.
8. The system SHALL record `createdBy` as the authenticated user and `createdAt` as the server time.
9. WHEN any member who sees the warehouse sends `GET .../movements/{id}` for a movement of that warehouse THEN the system SHALL return the movement body.

**Independent Test**: an owner records `IN 10` then `OUT 4`; the second response shows `balanceAfter` 6.

---

### P1: Validate a movement ⭐ MVP

**User Story**: As the platform, I want malformed movements refused before they touch the balance.

**Why P1**: A wrong quantity is a wrong balance.

**Acceptance Criteria**:

1. IF `type` is missing or not `IN`, `OUT` or `ADJUSTMENT` THEN the system SHALL respond `400` and record nothing.
2. IF `quantity` is missing, not a number, zero, or above 1 000 000 000 in absolute value THEN the system SHALL respond `400`.
3. IF `quantity` is negative on an `IN` or `OUT` THEN the system SHALL respond `400`.
4. IF `quantity` has more than 3 decimal places THEN the system SHALL respond `400`.
5. IF `quantity` is not whole and the product's unit is not fractional THEN the system SHALL respond `400`.
6. IF `type` is `ADJUSTMENT` and `note` is missing or blank THEN the system SHALL respond `400`.
7. IF `note` is longer than 500 characters after stripping THEN the system SHALL respond `400`.
8. IF `productId` is missing THEN the system SHALL respond `400`; IF it names an unknown or other-organization product or is not a UUID THEN the system SHALL respond `404`.
9. IF the product, the warehouse or the warehouse's company is archived THEN the system SHALL respond `409` and record nothing.
10. IF the active organization is archived THEN the system SHALL respond `409` and record nothing.
11. IF the company or warehouse in the path belongs to another organization, does not exist, is not a UUID, or the warehouse is not in that company THEN the system SHALL respond `404`.

**Independent Test**: `{"type":"IN","quantity":1.5}` for a `UN` product answers `400` and the balance stays unchanged.

---

### P1: Idempotent movement ⭐ MVP

**User Story**: As a client on an unreliable network, I want to retry a movement safely so that a timeout never records it twice.

**Why P1**: User chose a required `Idempotency-Key`; "um movimento registrado duas vezes" is a problem the README names.

**Acceptance Criteria**:

1. IF `Idempotency-Key` is missing, empty, longer than 100 characters or contains a character outside `0x21`-`0x7E` THEN the system SHALL respond `400` and record nothing.
2. WHEN a movement is sent again with a key already used in the organization and the same request THEN the system SHALL respond `201` with the original movement body (same `id`, same `balanceAfter`) and SHALL NOT record a movement or change the balance.
3. IF a key already used in the organization is sent with a different request THEN the system SHALL respond `422` and record nothing.
4. WHEN two requests with the same new key and the same request arrive concurrently THEN the system SHALL record exactly one movement and answer both `201` with the same `id`.
5. WHEN the original request was answered `400`, `404` or `409` THEN the system SHALL NOT reserve its key, so a corrected retry with the same key is recorded.
6. WHEN another organization already used the same key THEN the system SHALL record the movement and respond `201`.
7. WHEN the product was archived after the original movement THEN a replay with the same key and request SHALL respond `201` with the original body.

**Independent Test**: the same request sent twice with one key returns the same `id` and the balance moves once.

---

### P1: Concurrent movements keep the balance consistent ⭐ MVP

**User Story**: As the platform, I want concurrent movements on the same balance to serialize so that no update is lost and no exit oversells.

**Why P1**: The project's stated focus is consistency under concurrency.

**Acceptance Criteria**:

1. WHEN two `OUT` movements of the same product and warehouse run concurrently and the balance covers only one of them THEN the system SHALL record exactly one, answer the other `409`, and leave the balance equal to the starting balance minus the recorded one.
2. WHEN two `IN` movements of the same product and warehouse run concurrently THEN the system SHALL record both and the balance SHALL equal the starting balance plus both quantities.
3. WHEN the first movement of a product in a warehouse runs concurrently with another first movement of the same pair THEN the system SHALL keep a single balance row and apply both.
4. The system SHALL keep every balance equal to the sum of the signed quantities of its movements.
5. The database SHALL reject a balance below 0.

**Independent Test**: with balance 5 and a lock held from a test connection, two `OUT 5` are released together; one answers `201`, the other `409`, balance 0.

---

### P1: Read balance and history ⭐ MVP

**User Story**: As a member, I want to see what a warehouse holds and how it got there.

**Why P1**: A balance nobody can read is useless; the history is the audit trail.

**Acceptance Criteria**:

1. WHEN a member who sees the warehouse sends `GET .../stock` THEN the system SHALL return the first 20 balances of products that ever moved there, ordered by SKU, each with `{productId, sku, name, unit, quantity, updatedAt}`, in `{items, page, size, totalItems, totalPages}`.
2. WHEN a product's balance returned to 0 THEN the system SHALL still list it with `quantity` 0.
3. WHEN a member who sees the warehouse sends `GET .../movements` THEN the system SHALL return its movements newest first, by `createdAt` then id descending, paginated as above.
4. WHEN `productId` or `type` is sent on `GET .../movements` THEN the system SHALL return only matching movements.
5. IF `page`, `size`, `productId` or `type` is invalid THEN the system SHALL respond `400`.
6. IF the caller does not see the warehouse THEN the system SHALL respond `404` on `GET .../stock`, `GET .../movements` and `GET .../movements/{id}`.
7. WHILE the warehouse or its company is archived the system SHALL keep answering its balance and history reads.

**Independent Test**: after `IN 10` and `OUT 10`, `GET .../stock` lists the product with quantity 0 and `GET .../movements` lists `OUT` before `IN`.

---

### P2: Product balance across warehouses

**User Story**: As a member, I want one product's balance in every warehouse I see.

**Why P2**: Useful for finding stock; the per-warehouse view already covers the MVP.

**Acceptance Criteria**:

1. WHEN any member sends `GET /api/products/{id}/stock` THEN the system SHALL return `{productId, total, warehouses}` with one entry `{companyId, warehouseId, quantity}` per warehouse the caller sees where the product ever moved, and `total` equal to the sum of those entries.
2. WHILE the caller is a `MEMBER` the system SHALL exclude warehouses outside their grants from both `warehouses` and `total`.
3. WHEN the product never moved in a warehouse the caller sees THEN the system SHALL respond `200` with `total` 0 and empty `warehouses`.
4. IF the product belongs to another organization, does not exist or its id is not a UUID THEN the system SHALL respond `404`.

**Independent Test**: a `MEMBER` with a grant to one of two stocked warehouses sees one entry and a total of that warehouse only.

---

### P1: Immutable, tenant-isolated ledger ⭐ MVP

**User Story**: As an auditor, I want movements that cannot be changed and that never cross organizations.

**Why P1**: ADR-002 requires RLS; an editable ledger is not an audit trail.

**Acceptance Criteria**:

1. The system SHALL store movements and balances in tables with `tenant_id` and forced RLS, so that a session with another tenant sees none of their rows.
2. The database SHALL refuse `UPDATE` and `DELETE` on movements to the application role.
3. The system SHALL expose no endpoint that edits or deletes a movement.
4. The database SHALL reject a second movement with the same idempotency key in the same organization.

**Independent Test**: as `atlas_app`, `DELETE FROM stock_movements` fails with a permission error.

---

## Edge Cases

- WHEN an `ADJUSTMENT` brings the balance exactly to 0 THEN the system SHALL record it and respond `201`.
- WHEN `quantity` is sent as `10.000` for a `UN` product THEN the system SHALL accept it as whole.
- WHEN the replayed request carries `quantity` `10` and the original `10.0` THEN the system SHALL treat them as the same request.
- WHEN `page` is past the last page THEN the system SHALL respond `200` with empty `items` and the real `totalItems`.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| STK-01 | P1: Record a movement | Design | Pending |
| STK-02 | P1: Validate a movement | Design | Pending |
| STK-03 | P1: Idempotent movement | Design | Pending |
| STK-04 | P1: Concurrent movements keep the balance consistent | Design | Pending |
| STK-05 | P1: Read balance and history | Design | Pending |
| STK-06 | P2: Product balance across warehouses | Design | Pending |
| STK-07 | P1: Immutable, tenant-isolated ledger | Design | Pending |
| STK-08 | Edge Cases | Design | Pending |

**Coverage:** 8 total, 0 mapped to tasks, 8 unmapped ⚠️

---

## Sources

- Issue #24 - scope and acceptance summary.
- README - "um movimento registrado duas vezes", "Histórico auditável de movimentações".
- ADR-001, ADR-002 - module boundaries and tenant isolation.
- Features 16, 20 and 22 - warehouse nesting, grant visibility, units, paging, error answers.

---

## Success Criteria

- [ ] Every criterion above has a passing test in `./mvnw verify`.
- [ ] Forced interleavings of concurrent movements never lose an update or produce a negative balance.
