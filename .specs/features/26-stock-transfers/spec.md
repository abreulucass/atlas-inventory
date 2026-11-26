# Stock Transfers Specification

**Approved**: 2026-11-26 - user: "pode seguir"

## Problem Statement

Stock lives per warehouse (feature 24), but moving goods from one warehouse to another today takes an `OUT` in one and an `IN` in the other: two requests, and a failure between them leaves stock that left and never arrived - the bug the README names. This feature adds a transfer that moves one or more products between two warehouses of the organization in a single atomic, idempotent operation.

## Goals

- [ ] A member who sees both warehouses can transfer several products from one to the other in one request.
- [ ] A transfer either moves every item or moves nothing; no stock leaves without arriving.
- [ ] Retrying a transfer with the same `Idempotency-Key` never moves it twice.
- [ ] Concurrent transfers and movements never lose an update, oversell, or deadlock into an error.

## Out of Scope

| Feature | Reason |
| ------- | ------ |
| Two-step transfer (in transit, receipt, cancellation) | User chose "Atômica, um passo" |
| Transfer between organizations | ADR-002: never across organizations |
| Listing transfers (`GET /api/transfers`) | The per-warehouse movement history already shows them; deferred |
| Editing, deleting or reversing a transfer | Immutable audit trail; a mistake is corrected by a transfer back |
| Reservations, lots, expiry, valuation | Later features (README) |
| Domain events to other modules | No consumer yet |
| Frontend | Not created yet |

---

## Assumptions & Open Questions

| Assumption / decision | Chosen default | Rationale | Confirmed? |
| --------------------- | -------------- | --------- | ---------- |
| Model | One transaction moves every item out of the origin and into the destination; no in-transit state | User chose "Atômica, um passo" | y |
| Items | 1 to 100 items per transfer; any failing item fails the whole transfer and nothing is recorded | User chose "Vários itens, tudo ou nada"; the bound keeps one transaction short | y (limit n) |
| Reach | Any two distinct warehouses of the active organization, across companies included | User chose "Qualquer da organização"; ADR-002 | y |
| Who transfers | `OWNER`/`ADMIN` between any warehouses; `MEMBER` only when their grants (feature 20) show both origin and destination | User chose "OWNER/ADMIN + MEMBER nos dois" | y |
| Endpoints | `POST /api/transfers`, `GET /api/transfers/{id}` | A transfer can span companies, so it is not nested under one | n |
| Body | `{fromWarehouseId, toWarehouseId, note, items: [{productId, quantity}]}`; `note` optional | One note for the whole transfer | n |
| Quantity format | Same as feature 24 for `IN`/`OUT`: positive JSON number, at most 1 000 000 000, at most 3 decimal places, whole for a non-fractional unit | Same core | n |
| Duplicate product in `items` | `400` | One line per product keeps the ledger unambiguous | n |
| Note | Same rules as feature 24: stripped, at most 500 characters, blank means absent, U+0000 answers `400` | Same core | n |
| Idempotency | `Idempotency-Key` required, same format as feature 24; same key + same request replays the original `201`; same key + different request `422`; failed requests (`400`/`404`/`409`) do not reserve the key | Feature 24 contract | n |
| Key namespace | One namespace per organization shared with movements: a key already used by a movement answers `422` on a transfer, and the reverse | A key names one business operation in the organization | n |
| "Same request" | Same `fromWarehouseId`, `toWarehouseId`, `note` after stripping, and the same set of `(productId, quantity)` with quantities numerically equal, in any order | What the transfer records | n |
| Ledger | Each item records a `TRANSFER_OUT` movement (`quantity` `-q`) in the origin and a `TRANSFER_IN` movement (`quantity` `q`) in the destination, each with its `balanceAfter` and the `transferId` | Balances stay the sum of their movements; history shows where stock came from | n |
| Movement body | Every movement gains `transferId` (null for `IN`/`OUT`/`ADJUSTMENT`); the history `type` filter accepts `TRANSFER_OUT` and `TRANSFER_IN` | Link from a movement to its transfer | n |
| Transfer types on the movement endpoint | `POST .../movements` with `type` `TRANSFER_OUT` or `TRANSFER_IN` answers `400` | A half transfer is the bug this feature removes | n |
| Transfer response | `201` with `{id, fromWarehouseId, toWarehouseId, note, items: [{productId, quantity}], createdBy, createdAt}`, items in request order, and `Location: /api/transfers/{id}` | Balances after each side are on the movements | n |
| Transfer read | `GET /api/transfers/{id}`: `OWNER`/`ADMIN` always; `MEMBER` when they see the origin or the destination; otherwise `404` | The receiving side needs to see what arrived; the body carries no balance of the other warehouse | n |
| Unknown, other-organization, malformed or invisible warehouse id in the body | `404` | Same answer as an invisible warehouse in feature 24 | n |
| Unknown, other-organization or malformed `productId` | `404` | Feature 24 | n |
| Archived resources | Archived origin or destination warehouse, its company, or any item's product answers `409`; reads stay allowed | Feature 24 | n |
| Archived organization | `409` | Features 14-24 | n |
| Insufficient balance | Any item whose origin balance is below its quantity answers `409` with the ProblemDetail property `productId` naming the first such item in request order; nothing recorded | The client must know which line to fix | n |
| Write precedence | `401` → `409` archived organization → `400` idempotency key → `400` body → `404` origin/destination warehouse → replay `201` / `422` key reuse → `404` item product → `400` non-whole quantity for a non-fractional unit → `409` archived company / warehouse / product → `409` insufficient balance | Feature 24's order; warehouses are in the body, so body shape is checked first | n |
| Concurrency | Balance rows of a transfer are locked in one fixed order (warehouse id, then product id), so opposite transfers never deadlock | A deadlock would surface as a `500` | n |
| Module | Inside the `stock` module | Same core as movements (ADR-001) | n |
| Errors | RFC 9457 `ProblemDetail` | Previous features | n |

**Open questions:** none - all resolved or logged above.

---

## User Stories

### P1: Transfer stock between warehouses ⭐ MVP

**User Story**: As a member who works with two warehouses, I want to move several products from one to the other in one operation so that stock never leaves without arriving.

**Why P1**: The feature itself.

**Acceptance Criteria**:

1. WHEN an `OWNER` or `ADMIN` sends `POST /api/transfers` with a new `Idempotency-Key` and two items whose origin balances cover their quantities THEN the system SHALL lower each item's origin balance by its quantity, raise its destination balance by the same quantity, and respond `201` with the transfer body and a `Location` header.
2. WHEN a transfer is recorded THEN the system SHALL record, per item, one `TRANSFER_OUT` movement in the origin with `quantity` `-q` and one `TRANSFER_IN` movement in the destination with `quantity` `q`, both carrying the transfer's `transferId` and their own `balanceAfter`.
3. WHEN the origin and destination belong to different companies of the organization THEN the system SHALL record the transfer and respond `201`.
4. WHEN a product has no balance in the destination THEN the system SHALL start its destination balance at 0 before applying the entry.
5. WHEN a `MEMBER` whose grants show both origin and destination sends a valid transfer THEN the system SHALL record it and respond `201`.
6. IF a `MEMBER` whose grants do not show the origin, or do not show the destination, sends a transfer THEN the system SHALL respond `404` and record nothing.
7. The system SHALL record `createdBy` as the authenticated user and `createdAt` as the server time.

**Independent Test**: an owner with 10 of product A and 5 of B in warehouse W1 transfers A 4 and B 5 to W2; W1 shows A 6, B 0 and W2 shows A 4, B 5.

---

### P1: All or nothing ⭐ MVP

**User Story**: As the platform, I want a transfer that cannot move every item to move none.

**Why P1**: User chose "Vários itens, tudo ou nada".

**Acceptance Criteria**:

1. IF any item's origin balance is below its quantity THEN the system SHALL respond `409` with the ProblemDetail property `productId` naming the first such item in request order, record no transfer and no movement, and leave every origin and destination balance row unchanged.
2. IF any item's product is archived THEN the system SHALL respond `409` and record nothing for any item.
3. WHEN a transfer fails on its last item after earlier items were applied in the same transaction THEN the system SHALL leave no destination balance row created by that transfer.

**Independent Test**: with A 10 and B 1 in W1, a transfer of A 4 and B 2 answers `409` naming B, and W1 and W2 rows are identical before and after.

---

### P1: Validate a transfer ⭐ MVP

**User Story**: As the platform, I want malformed transfers refused before they touch any balance.

**Why P1**: A wrong line is a wrong balance in two warehouses.

**Acceptance Criteria**:

1. IF `fromWarehouseId` or `toWarehouseId` is missing THEN the system SHALL respond `400`.
2. IF `fromWarehouseId` equals `toWarehouseId` THEN the system SHALL respond `400`.
3. IF `items` is missing, empty or has more than 100 entries THEN the system SHALL respond `400`.
4. IF an item's `productId` is missing, or two items name the same `productId`, THEN the system SHALL respond `400`.
5. IF an item's `quantity` is missing, not a number, zero, negative, above 1 000 000 000, or has more than 3 decimal places THEN the system SHALL respond `400`.
6. IF an item's `quantity` is not whole and its product's unit is not fractional THEN the system SHALL respond `400`.
7. IF `note` is longer than 500 characters after stripping or contains U+0000 THEN the system SHALL respond `400`.
8. IF the origin or destination warehouse is unknown, of another organization, not a UUID, or invisible to the caller THEN the system SHALL respond `404`.
9. IF an item's `productId` names an unknown or other-organization product or is not a UUID THEN the system SHALL respond `404`.
10. IF the origin or destination warehouse, or either warehouse's company, is archived THEN the system SHALL respond `409` and record nothing.
11. IF the active organization is archived THEN the system SHALL respond `409` and record nothing.
12. IF `POST .../movements` is sent with `type` `TRANSFER_OUT` or `TRANSFER_IN` THEN the system SHALL respond `400` and record nothing.
13. WHEN several rules fail at once THEN the system SHALL answer the first in the write precedence of the Assumptions table.

**Independent Test**: a transfer from W1 to W1 answers `400`; a transfer naming product A twice answers `400`; neither changes a balance.

---

### P1: Idempotent transfer ⭐ MVP

**User Story**: As a client on an unreliable network, I want to retry a transfer safely so that a timeout never moves stock twice.

**Why P1**: Feature 24's contract applies to the operation that moves the most stock.

**Acceptance Criteria**:

1. IF `Idempotency-Key` is missing, empty, longer than 100 characters or contains a character outside `0x21`-`0x7E` THEN the system SHALL respond `400` and record nothing.
2. WHEN a transfer is sent again with a key already used in the organization and the same request THEN the system SHALL respond `201` with the original transfer body (same `id`) and SHALL NOT record a movement or change a balance.
3. WHEN the replay lists the same items in a different order and with numerically equal quantities THEN the system SHALL treat it as the same request.
4. IF a key already used in the organization is sent with a different request THEN the system SHALL respond `422` and record nothing.
5. IF a key already used by a movement is sent on a transfer, or a key already used by a transfer is sent on a movement, THEN the system SHALL respond `422` and record nothing.
6. WHEN two requests with the same new key and the same request arrive concurrently THEN the system SHALL record exactly one transfer and answer both `201` with the same `id`.
7. WHEN the original request was answered `400`, `404` or `409` THEN the system SHALL NOT reserve its key, so a corrected retry with the same key is recorded.
8. WHEN a product or warehouse was archived after the original transfer THEN a replay with the same key and request SHALL respond `201` with the original body.

**Independent Test**: the same transfer sent twice with one key returns the same `id` and each balance moves once.

---

### P1: Concurrent transfers keep balances consistent ⭐ MVP

**User Story**: As the platform, I want transfers and movements on shared balances to serialize so that none oversells, loses an update or deadlocks.

**Why P1**: The project's stated focus is consistency under concurrency.

**Acceptance Criteria**:

1. WHEN a transfer and an `OUT` movement on the same origin balance run concurrently and the balance covers only one of them THEN the system SHALL record exactly one, answer the other `409`, and leave the balance equal to the starting balance minus the recorded one.
2. WHEN a transfer W1→W2 and a transfer W2→W1 of the same products run concurrently and both are covered THEN the system SHALL record both, answer both `201`, and leave each balance equal to its start plus its entries minus its exits.
3. WHEN two transfers into the same destination balance run concurrently THEN the system SHALL record both and the destination balance SHALL equal its start plus both quantities.
4. The system SHALL keep every balance equal to the sum of the signed quantities of its movements.
5. The system SHALL keep the signed quantities of each transfer's movements summing to 0 per product.

**Independent Test**: with a lock on W1's balance held from a test connection, a transfer W1→W2 and a transfer W2→W1 both wait, then both answer `201` once the lock is released.

---

### P1: Read a transfer ⭐ MVP

**User Story**: As a member on either side, I want to see what a transfer moved.

**Why P1**: Target of `Location`; the receiving side must see what arrived.

**Acceptance Criteria**:

1. WHEN an `OWNER` or `ADMIN` sends `GET /api/transfers/{id}` THEN the system SHALL return the transfer body.
2. WHEN a `MEMBER` whose grants show the origin or the destination sends `GET /api/transfers/{id}` THEN the system SHALL return the transfer body.
3. IF a `MEMBER` whose grants show neither warehouse sends `GET /api/transfers/{id}` THEN the system SHALL respond `404`.
4. IF the transfer belongs to another organization, does not exist or its id is not a UUID THEN the system SHALL respond `404`.
5. WHEN a member who sees a warehouse reads its movement history or a movement THEN the system SHALL include `transferId` on every movement, null for `IN`, `OUT` and `ADJUSTMENT`.
6. WHEN `type` `TRANSFER_OUT` or `TRANSFER_IN` is sent on `GET .../movements` THEN the system SHALL return only movements of that type.
7. WHILE a warehouse, company or product of the transfer is archived the system SHALL keep answering `GET /api/transfers/{id}`.

**Independent Test**: after a transfer W1→W2, a `MEMBER` with a grant only to W2 reads it, and W2's history lists a `TRANSFER_IN` with its `transferId`.

---

### P1: Immutable, tenant-isolated transfers ⭐ MVP

**User Story**: As an auditor, I want transfers that cannot be changed and never cross organizations.

**Why P1**: ADR-002 requires RLS; an editable ledger is not an audit trail.

**Acceptance Criteria**:

1. The system SHALL store transfers and their items in tables with `tenant_id` and forced RLS, so that a session with another tenant sees none of their rows.
2. The database SHALL refuse `UPDATE` and `DELETE` on transfers and their items to the application role.
3. The database SHALL reject a transfer whose origin or destination warehouse belongs to another organization.
4. The system SHALL expose no endpoint that edits or deletes a transfer.

**Independent Test**: as `atlas_app`, `DELETE FROM stock_transfers` fails with a permission error.

---

## Edge Cases

- WHEN a transfer moves an item's whole origin balance THEN the system SHALL record it and leave the origin balance at 0.
- WHEN an item's `quantity` is `10.000` for a `UN` product THEN the system SHALL accept it as whole.
- WHEN a transfer has exactly 100 items THEN the system SHALL accept it.

---

## Requirement Traceability

| Requirement ID | Story | Phase | Status |
| -------------- | ----- | ----- | ------ |
| TRF-01 | P1: Transfer stock between warehouses | Design | Pending |
| TRF-02 | P1: All or nothing | Design | Pending |
| TRF-03 | P1: Validate a transfer | Design | Pending |
| TRF-04 | P1: Idempotent transfer | Design | Pending |
| TRF-05 | P1: Concurrent transfers keep balances consistent | Design | Pending |
| TRF-06 | P1: Read a transfer | Design | Pending |
| TRF-07 | P1: Immutable, tenant-isolated transfers | Design | Pending |
| TRF-08 | Edge Cases | Design | Pending |

**Coverage:** 8 total, 0 mapped to tasks, 8 unmapped ⚠️

---

## Sources

- Issue #26 - scope and acceptance summary (to be opened).
- README - "uma transferência que sai de um depósito e não chega ao outro".
- ADR-001, ADR-002 - module boundaries; transfers between companies of one organization, never between organizations.
- Feature 24 - quantity format, idempotency, precedence, concurrency proofs.
- Feature 20 - grant visibility.

---

## Success Criteria

- [ ] Every criterion above has a passing test in `./mvnw verify`.
- [ ] Forced interleavings of concurrent transfers and movements never lose an update, oversell or deadlock.
