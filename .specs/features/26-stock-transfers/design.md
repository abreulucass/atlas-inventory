# Stock Transfers Design

**Spec**: `.specs/features/26-stock-transfers/spec.md`
**Status**: Draft

---

## Architecture Overview

Transfers live in the `stock` module, on the core of feature 24: the same balance rows, the same movement ledger, the same idempotency key lock. One `@Transactional` call does everything; a failure anywhere rolls back every row.

```
POST /api/transfers ─► TransferController ─► Transfers.record (one transaction)
                                               │ Organizations.requireWritable
                                               │ IdempotencyKey.parse, TransferRequest.parse          (400)
                                               │ Warehouses.find(id) ×2  (visibility → 404)
                                               │ MovementRepository.lockKey  (advisory, shared with movements)
                                               │ TransferRepository.findByKey → replay / 422
                                               │ MovementRepository.findByKey → 422 (shared namespace)
                                               │ Products.findAll(ids) → 404 / 400 whole / 409 archived
                                               │ Companies.find ×2, statuses → 409 archived
                                               │ BalanceRepository.lockForTransfer (ordered FOR UPDATE)
                                               │ check origin balances in request order → 409 {productId}
                                               │ BalanceRepository.apply per op, sorted (warehouse, product)
                                               │ TransferRepository.insert (transfer + items)
                                               └ MovementRepository.insertTransfer ×2 per item
GET /api/transfers/{id} ─► Transfers.find ─► Permissions.visibility().seesWarehouse(from|to)
```

## Code Reuse Analysis

| Reused | From | For |
| ------ | ---- | --- |
| `IdempotencyKey.parse` | stock (24) | header rules |
| `MovementRepository.lockKey` | stock (24) | one advisory lock per `tenant:key` for both movements and transfers |
| `MovementRequest` quantity/note rules | stock (24) | extracted into shared static helpers used by `TransferRequest` (no copy) |
| `BalanceRepository.apply` | stock (24) | each side of each item |
| `Products.findAll(Collection<UUID>)` | catalog (24 T3) | items' products in one query |
| `Companies.find`, `Organizations.requireWritable` | company, access | archive checks |
| `Permissions.visibility()` | access | read visibility over stored warehouse ids |
| `StockExceptionHandler` | stock | extended to `TransferController` |
| `PageParams` | - | not needed (no list) |

### Integration Points

- **warehouse**: new public `WarehouseView Warehouses.find(UUID id)` — the organization's warehouse with that id, archived included, `WarehouseNotFoundException` when unknown or outside the caller's visibility (same filter as `find(companyId, id)`). Transfers carry warehouse ids only.
- **stock movements**: `MovementType` gains `TRANSFER_OUT`, `TRANSFER_IN`; `MovementRequest.parse` rejects them (`400`); `MovementView` gains `@Nullable UUID transferId`; `Movements.record` also answers `422` when the key belongs to a transfer; the history `type` filter accepts the new values.

## Components

### `Transfers` (public service, `stock`)

- `TransferView record(@Nullable String key, @Nullable String body)` — precedence exactly as the spec's write-precedence row. Ids are parsed inside, after the organization check (L-011); a malformed warehouse id is `404`.
- `TransferView find(String id)` — `404` when malformed, unknown, or when the caller sees neither warehouse.

### `TransferRequest` (`stock/internal`)

Parsed body: `fromWarehouseId`, `toWarehouseId` (raw strings, resolved by the service), `note`, `List<Item(String productId, BigDecimal quantity)>`. Rules: both ids present strings, equal strings (case-insensitive after UUID parse when both parse) → `400`; `items` array of 1..100 objects; each `productId` string present; duplicate `productId` (compared as parsed UUID when it parses, else as string) → `400`; quantity via the shared rule (positive, ≤ 1e9, ≤ 3 decimals); note via the shared rule.

`sameRequest(TransferView stored, from, to, request)`: equal ids, equal note, and equal maps `productId → quantity` with `compareTo == 0`.

### Repositories (`stock/internal`, `JdbcClient`)

- `TransferRepository`: `findByKey`, `findById`, `insert(transfer, items)` returning the view; items read back ordered by `line`.
- `BalanceRepository.lockForTransfer(tenant, Set<(warehouse, product)>)`: `select warehouse_id, product_id, quantity from stock_balances where tenant_id = :t and (warehouse_id, product_id) in (...) order by warehouse_id, product_id for update` — returns current quantities (missing origin row = 0).
- `MovementRepository.insertTransfer(tenant, company, warehouse, product, type, delta, balanceAfter, note, transferId, user)` — `idempotency_key` null.

### HTTP

- `TransferController` at `/api/transfers`: `POST` (`Idempotency-Key` header, raw body, `201` + `Location: /api/transfers/{id}`), `GET /{id}`.
- `StockExceptionHandler` covers `TransferController`; `InsufficientBalanceException` gains an optional `productId` set as ProblemDetail property `productId` (movements leave it absent).

## Data Models

`V10__stock_transfers.sql`:

```sql
create table stock_transfers (
    id                uuid primary key,
    tenant_id         uuid         not null references organizations (id),
    from_company_id   uuid         not null,
    from_warehouse_id uuid         not null,
    to_company_id     uuid         not null,
    to_warehouse_id   uuid         not null,
    note              varchar(500),
    idempotency_key   varchar(100) not null,
    created_by        uuid         not null references users (id),
    created_at        timestamptz  not null default clock_timestamp(),
    constraint stock_transfers_distinct check (from_warehouse_id <> to_warehouse_id),
    constraint stock_transfers_from_fk foreign key (tenant_id, from_company_id, from_warehouse_id)
        references warehouses (tenant_id, company_id, id),
    constraint stock_transfers_to_fk foreign key (tenant_id, to_company_id, to_warehouse_id)
        references warehouses (tenant_id, company_id, id),
    constraint stock_transfers_tenant_id_key unique (tenant_id, id),
    constraint stock_transfers_tenant_key_key unique (tenant_id, idempotency_key)
);

create table stock_transfer_items (
    tenant_id   uuid           not null,
    transfer_id uuid           not null,
    line        smallint       not null check (line between 1 and 100),
    product_id  uuid           not null,
    quantity    numeric(18, 3) not null check (quantity > 0),
    primary key (transfer_id, line),
    unique (transfer_id, product_id),
    foreign key (tenant_id, transfer_id) references stock_transfers (tenant_id, id),
    foreign key (tenant_id, product_id) references products (tenant_id, id)
);

alter table stock_movements alter column idempotency_key drop not null;
alter table stock_movements add column transfer_id uuid;
alter table stock_movements add constraint stock_movements_transfer_fk
    foreign key (tenant_id, transfer_id) references stock_transfers (tenant_id, id);
alter table stock_movements drop constraint stock_movements_type_check;
alter table stock_movements add constraint stock_movements_type_check
    check (type in ('IN', 'OUT', 'ADJUSTMENT', 'TRANSFER_OUT', 'TRANSFER_IN'));
-- a movement is either keyed by the client or part of a transfer, never both
alter table stock_movements add constraint stock_movements_origin_check check (
    (transfer_id is null and idempotency_key is not null and type in ('IN', 'OUT', 'ADJUSTMENT'))
    or (transfer_id is not null and idempotency_key is null and type in ('TRANSFER_OUT', 'TRANSFER_IN')));
create index stock_movements_transfer_idx on stock_movements (tenant_id, transfer_id);

revoke update, delete on stock_transfers, stock_transfer_items from atlas_app;
-- + enable/force RLS and tenant_isolation policy on both tables, as V9
```

The implementer confirms the generated name of V9's inline type check (`stock_movements_type_check`) before dropping it.

`TransferView(UUID id, UUID fromWarehouseId, UUID toWarehouseId, @Nullable String note, List<TransferItemView(UUID productId, BigDecimal quantity)> items, UUID createdBy, Instant createdAt)`; quantities at scale 3 so a replay answers the same JSON.

## Concurrency

- **Same key**: the existing advisory lock on `tenant:key` serializes movements and transfers alike; after it, each path checks both tables. No DB constraint spans the two tables; the lock is the guarantee (proved by the shared-namespace and concurrent-same-key tests).
- **Lock order**: before any balance read or write, `Transfers.record` takes one transaction advisory lock per distinct (warehouse, product) slot of the transfer, both sides, present or not. The key is `hashtextextended('stock-slot:' || tenant || ':' || warehouse || ':' || product, 0)`; keys are locked one at a time in ascending signed order (the order of PostgreSQL `bigint`). Two transfers that share any slot request the shared keys in the same order, so neither holds a key the other needs first, even for destination rows that do not exist yet (a row snapshot could not order those and deadlocked; proved by the interleaving in `twoTransfersIntoSameDestinationBothApply`). `lockForTransfer` then only reads the quantities under the slot locks. A plain movement takes a single row statement and never waits while holding a lock, so it cannot close a cycle. Known limit: a 64-bit collision between a slot key and an idempotency-key lock could in theory make two requests wait on each other.
- **Missing destination rows**: inserted by `apply`'s `ON CONFLICT`; a concurrent first insert of the same row waits on the unique index and converges (feature 24).
- **Insufficient balance**: checked on the locked quantities in request order, so the reported `productId` is the first failing item; `apply`'s `quantity + d >= 0` guard stays as the backstop.

## Error Handling Strategy

| Condition | Answer |
| --------- | ------ |
| Bad key / bad body / same warehouse / duplicate product / bad quantity or note | `400` |
| Warehouse unknown, other org, malformed, invisible | `404` (`WarehouseNotFoundException`) |
| Key reused with another request, or used by a movement | `422` |
| Item product unknown, other org, malformed | `404` |
| Non-whole quantity for a non-fractional unit | `400` |
| Archived org / company / warehouse / product | `409` |
| Insufficient origin balance | `409` + `productId` property |
| Transfer type on `POST .../movements` | `400` |

## Risks & Concerns

- **Shared namespace without a cross-table constraint**: relies on the advisory lock; acceptable, the lock already guarded feature 24's replay.
- **Deadlock if a future path locks balance rows in another order**: documented on `lockForTransfer`.
- **100 items × 2 sides = 200 balance writes + 200 movement inserts in one transaction**: bounded; fine at this size.

## Tech Decisions

| Decision | Choice | Why |
| -------- | ------ | --- |
| Where transfers live | `stock` module | Same rows and ledger (ADR-001) |
| Ledger shape | Movement rows `TRANSFER_OUT`/`TRANSFER_IN` with `transfer_id` | Balance stays the sum of its movements; history needs no join |
| Movement key for transfer rows | `null`, enforced by a check | The transfer owns the key |
| Deadlock avoidance | Ordered `FOR UPDATE` before writes | Deterministic lock order across transactions |
