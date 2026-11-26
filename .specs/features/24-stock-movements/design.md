# Stock Movements and Balance Design

**Spec**: `.specs/features/24-stock-movements/spec.md`
**Status**: Approved (with the tasks)

---

## Architecture Overview

New module `stock`, same layering as `catalog`: public services (`Movements`, `Balances`), views and exceptions in the module root; repositories (JdbcClient), controllers, exception handler and value parsing in `stock.internal`. It depends on the public APIs of `access` (`TenantContext`, `CurrentUser`, `Permissions`, `Organizations`), `company` (`Companies.find`), `warehouse` (`Warehouses.find`) and `catalog` (`Products.find`, new `Products.findAll`). No events.

Writes are plain SQL through `JdbcClient` inside the tenant transaction (the JPA transaction manager exposes its connection to JDBC, as `Permissions` already does), because the two invariants live in single statements:

- **Balance** (user chose "UPDATE condicional atômico"): one statement changes the balance and checks it; the row lock it takes serializes concurrent movements on the same (warehouse, product); 0 rows means insufficient balance.
- **Idempotency** (user chose "Advisory lock na chave"): `pg_advisory_xact_lock` on (tenant, key) before the key lookup, so a second request with the same key waits for the first to commit and then replays it. The unique key on `stock_movements` is the backstop.

```mermaid
sequenceDiagram
    participant C as Client
    participant Ctl as MovementController
    participant M as Movements
    participant X as access / company / warehouse / catalog
    participant DB as PostgreSQL (RLS)
    C->>Ctl: POST .../movements (Idempotency-Key, raw body)
    Ctl->>M: record(companyId, warehouseId, key, body) [strings]
    M->>X: Organizations.requireWritable → 409
    M->>X: Companies.find + Warehouses.find (visibility) → 404
    M->>M: IdempotencyKey.parse → 400; MovementRequest.parse → 400
    M->>DB: select pg_advisory_xact_lock(hashtextextended(tenant || ':' || key, 0))
    M->>DB: select movement by (tenant, key)
    alt key exists
        M-->>Ctl: same request → original view (201) / else 422
    end
    M->>X: Products.find(productId) → 404
    M->>M: unit not fractional and quantity not whole → 400
    M->>M: company / warehouse / product archived → 409
    alt delta > 0
        M->>DB: insert ... on conflict do update set quantity = quantity + :d returning quantity
    else delta < 0
        M->>DB: update ... set quantity = quantity + :d where ... and quantity + :d >= 0 returning quantity
        DB-->>M: 0 rows → 409 insufficient
    end
    M->>DB: insert into stock_movements (..., balance_after)
    M-->>Ctl: MovementView → 201 + Location
```

Path ids, the key and the body reach the service as strings and are parsed after the organization check (L-011). The whole-quantity rule for non-fractional units needs the product, so that `400` comes after the body `productId` lookup; the spec's precedence row is read with that one exception (spec updated).

## Code Reuse Analysis

| Component | Location | How to Use |
| --------- | -------- | ---------- |
| Module layout, `ProblemDetail` handler, string ids parsed in service | `catalog/*`, `catalog/internal/CatalogExceptionHandler` | Same structure |
| Organization check | `access.Organizations.requireWritable()` | First call in `record` |
| Visibility | `access.Permissions.visibility()`; `Companies.find`, `Warehouses.find` already filter by it and answer `404` | Path resolution; `Visibility.seesWarehouse` filters `GET /api/products/{id}/stock` |
| Product lookup | `catalog.Products.find(String)` (`404` for malformed/unknown) and `ProductView.fractional()`/`status()` | Body `productId` |
| Canonical UUID parsing | `catalog/internal/CatalogIds` | Copy as `stock/internal/StockIds` |
| Raw JSON parsing | `catalog.Products` / `access/internal/MemberAccess` (Jackson `JsonNode`) | `MovementRequest.parse` |
| Page params `page`/`size` → `400` | `catalog.Products.list` | Same parsing; extract nothing, copy the few lines |
| Composite tenant FKs, forced RLS policy | `V6__warehouses.sql`, `V8__catalog.sql` | `V9__stock.sql` |
| Revoke from `atlas_app` | `V4__organizations_no_delete.sql` | Revoke `UPDATE, DELETE` on movements |
| Forced interleaving in tests | `ProductUpdateApiIT` AC 6 (held lock + `pg_locks`, L-006) | Balance races and same-key race |
| Fixtures | `OwnerFixture`, `access/AuthFixture`, `access/AccessITSupport`, `catalog/CatalogITSupport`, `TenantIsolationIT` | Tokens, members with grants, products, native SQL as `atlas_app` |

### Integration Points

| System | Integration Method |
| ------ | ------------------ |
| `access`, `company`, `warehouse`, `catalog` | Public API only (checked by `ModularityTest`) |
| `catalog` | New public `List<ProductView> findAll(Collection<UUID> ids)` for the balance page names |
| Database | `V9__stock.sql`, owned by `atlas_owner`; `V1` default privileges grant `atlas_app`, then V9 revokes |
| Security | `/api/companies/**`, `/api/products/**` already "authenticated"; no config change |

## Components

### `Movements` (public service)

- **Location**: `stock/Movements.java`
- **Interfaces**:
  - `MovementView record(String companyId, String warehouseId, @Nullable String key, @Nullable String body)` - flow above, `@Transactional`.
  - `MovementView find(String companyId, String warehouseId, String id)` - `404` if not in that warehouse.
  - `MovementPage list(String companyId, String warehouseId, page, size, productId, type)` - strings, `400` on invalid.
- **Same request** (replay): `warehouseId`, `type`, `productId` (raw body value parsed; unparseable counts as different), `quantity.compareTo == 0`, `note` after strip/blank→null. Different → `IdempotencyKeyReusedException` (`422`).

### `Balances` (public service)

- **Location**: `stock/Balances.java`
- **Interfaces**:
  - `BalancePage ofWarehouse(String companyId, String warehouseId, page, size)` - rows ordered by `sku`, names from one `Products.findAll(ids)` call per page.
  - `ProductStock ofProduct(String productId)` - `Products.find` (`404`), rows filtered by `Visibility.seesWarehouse(companyId, warehouseId)`, `total` summed after filtering, ordered by company id then warehouse code (codes from `Warehouses.find` per row).

### Value types (`stock/internal`)

- `IdempotencyKey.parse(String)`: `^[\x21-\x7E]{1,100}$` or `InvalidIdempotencyKeyException`.
- `MovementRequest.parse(String body)`: `type` (`IN|OUT|ADJUSTMENT`), `productId` present (string), `quantity` as `BigDecimal` from the JSON number (`stripTrailingZeros`, scale ≤ 3, `0 < |q| ≤ 1e9`, sign rules per type), `note` (strip, ≤ 500 code points, blank → null, required for `ADJUSTMENT`). Returns the signed `delta`.
- `MovementType` (public enum in `stock`): `IN`, `OUT`, `ADJUSTMENT`.

### Repositories (`stock/internal`, `JdbcClient`)

- `BalanceRepository`: `OptionalBigDecimal apply(tenant, companyId, warehouseId, productId, sku, unit, delta)` (the two statements), page queries.
- `MovementRepository`: `lockKey(tenant, key)`, `findByKey`, `insert`, `findById`, page query.

### HTTP

- `MovementController` (`/api/companies/{companyId}/warehouses/{warehouseId}/movements`): `POST` (reads `Idempotency-Key` header, `201` + `Location`, replay also `201`), `GET`, `GET /{id}`.
- `BalanceController`: `GET /api/companies/{companyId}/warehouses/{warehouseId}/stock`, `GET /api/products/{id}/stock`.
- `StockExceptionHandler` (`assignableTypes` = both controllers): `400` invalid input/key, `404` not-found (own + `CompanyNotFoundException`, `WarehouseNotFoundException`, `ProductNotFoundException`), `409` `OrganizationArchivedException`, archived company/warehouse/product, `InsufficientBalanceException`; `422` `IdempotencyKeyReusedException`.

## Data Models

```sql
-- V9__stock.sql
alter table warehouses add constraint warehouses_tenant_company_id_key unique (tenant_id, company_id, id);
alter table products add constraint products_tenant_id_key unique (tenant_id, id);

create table stock_balances (
    tenant_id    uuid not null references organizations (id),
    company_id   uuid not null,
    warehouse_id uuid not null,
    product_id   uuid not null,
    sku          varchar(40) not null,   -- immutable in catalog; copied for ordering without crossing modules
    unit         varchar(2) not null,    -- immutable in catalog
    quantity     numeric(18, 3) not null check (quantity >= 0),
    updated_at   timestamptz not null default clock_timestamp(),
    primary key (tenant_id, warehouse_id, product_id),
    constraint stock_balances_warehouse_fk foreign key (tenant_id, company_id, warehouse_id) references warehouses (tenant_id, company_id, id),
    constraint stock_balances_product_fk foreign key (tenant_id, product_id) references products (tenant_id, id)
);
create index stock_balances_sku_idx on stock_balances (tenant_id, warehouse_id, sku);
create index stock_balances_product_idx on stock_balances (tenant_id, product_id);

create table stock_movements (
    id              uuid primary key,
    tenant_id       uuid not null references organizations (id),
    company_id      uuid not null,
    warehouse_id    uuid not null,
    product_id      uuid not null,
    type            varchar(10) not null check (type in ('IN', 'OUT', 'ADJUSTMENT')),
    quantity        numeric(18, 3) not null check (quantity <> 0),  -- signed effect
    balance_after   numeric(18, 3) not null check (balance_after >= 0),
    note            varchar(500),
    idempotency_key varchar(100) not null,
    created_by      uuid not null references users (id),
    created_at      timestamptz not null default clock_timestamp(),
    constraint stock_movements_balance_fk foreign key (tenant_id, warehouse_id, product_id) references stock_balances (tenant_id, warehouse_id, product_id),
    constraint stock_movements_tenant_key_key unique (tenant_id, idempotency_key)
);
create index stock_movements_history_idx on stock_movements (tenant_id, warehouse_id, created_at desc, id desc);

revoke update, delete on stock_movements from atlas_app;
revoke delete on stock_balances from atlas_app;
-- + enable/force RLS and the tenant_isolation policy on both tables, as in V8
```

Balance statements:

```sql
-- delta > 0 (also creates the first row; concurrent first inserts converge through ON CONFLICT)
insert into stock_balances (tenant_id, company_id, warehouse_id, product_id, sku, unit, quantity)
values (:t, :c, :w, :p, :sku, :unit, :d)
on conflict (tenant_id, warehouse_id, product_id)
do update set quantity = stock_balances.quantity + excluded.quantity, updated_at = clock_timestamp()
returning quantity;

-- delta < 0 (no row = balance 0 = insufficient)
update stock_balances set quantity = quantity + :d, updated_at = clock_timestamp()
where tenant_id = :t and warehouse_id = :w and product_id = :p and quantity + :d >= 0
returning quantity;
```

Views: `MovementView(id, warehouseId, productId, type, quantity, balanceAfter, note, createdBy, createdAt)`, `MovementPage`, `BalanceView(productId, sku, name, unit, quantity, updatedAt)`, `BalancePage`, `ProductStock(productId, total, warehouses: List<WarehouseStock(companyId, warehouseId, quantity)>)`. Quantities are `BigDecimal` serialized as JSON numbers.

## Error Handling Strategy

| Error Scenario | Handling | User Impact |
| -------------- | -------- | ----------- |
| Insufficient balance | `update ... where quantity + d >= 0` returns 0 rows → `InsufficientBalanceException` before the movement insert | `409`, nothing written |
| Same key in flight | Second request waits on the advisory lock, then finds the committed movement | `201` replay with same `id` |
| First request failed (400/404/409) | Nothing inserted; transaction rolls back and releases the advisory lock | Key free for a corrected retry |
| Unique key violated despite the lock | `DataIntegrityViolationException` on `stock_movements_tenant_key_key` → rethrown (should be unreachable) | `500`, logged; proves the lock is missing |
| Numeric overflow of a balance (> 10^15) | `shortcut:` - surfaces as `500`; map to `409` if a tenant ever nears it | Unreachable in practice (1e9 per movement) |
| Malformed JSON / wrong types | Jackson `JsonNode` | `400` |

## Risks & Concerns

| Concern | Location | Impact | Mitigation |
| ------- | -------- | ------ | ---------- |
| Archive racing a movement | `Movements.record` (product/warehouse/company status read without lock) | A movement may land on a just-archived resource | Harmless (archive only freezes new use); `shortcut:` comment, as `Warehouses.create` |
| Advisory lock key is a 64-bit hash | `MovementRepository.lockKey` | Two different keys may collide and serialize | Only extra waiting, never wrong data; unique constraint still decides |
| `Permissions.visibility()` queried per call | `Warehouses.find` per row in `Balances.ofProduct` | N queries for a product in N warehouses | `shortcut:` comment; batch by company if a product sits in many warehouses |
| Products' `version`/`archivedAt` change while moving | `Products.find` is a plain read | Moving a product archived after the check | Same as first row |
| JdbcClient bypasses `@TenantId` | `stock/internal` repositories | A bug could insert a wrong `tenant_id` | RLS `with check` rejects it; `tenant_id` always from `TenantContext.require()`; `TenantIsolationIT` covers both tables |

## Tech Decisions

| Decision | Choice | Rationale |
| -------- | ------ | --------- |
| Balance concurrency | Single conditional SQL statement (user chose "UPDATE condicional atômico") | Row lock taken by the statement itself; no retry; `CHECK (quantity >= 0)` as backstop |
| Same-key concurrency | `pg_advisory_xact_lock(hashtextextended(tenant || ':' || key, 0))` (user chose "Advisory lock na chave") | Second request replays instead of failing; released at commit/rollback |
| Order: balance then movement | Movement insert needs `balance_after`; both in one transaction | A failed balance check writes nothing |
| `sku`, `unit`, `company_id` copied into `stock_balances` | Immutable upstream | Ordering and visibility filter without reading another module's tables |
| JdbcClient, no JPA entities | `RETURNING` and `ON CONFLICT` | The invariants are SQL; entities would add a read-modify-write |
| `GET /api/products/{id}/stock` in `stock` | Separate controller under the catalog's path | Path follows the resource; code follows the module that owns the data |
