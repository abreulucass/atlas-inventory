-- FK target: pairs a product with its tenant (warehouses already have theirs, V7).
alter table products add constraint products_tenant_id_key unique (tenant_id, id);

-- sku and unit are immutable in the catalog; copied here to order and format without crossing modules.
create table stock_balances (
    tenant_id    uuid           not null references organizations (id),
    company_id   uuid           not null,
    warehouse_id uuid           not null,
    product_id   uuid           not null,
    sku          varchar(40)    not null,
    unit         varchar(2)     not null,
    quantity     numeric(18, 3) not null check (quantity >= 0),
    updated_at   timestamptz    not null default clock_timestamp(),
    primary key (tenant_id, warehouse_id, product_id),
    -- FK checks bypass RLS: the composite keys keep a balance inside its own tenant
    constraint stock_balances_warehouse_fk foreign key (tenant_id, company_id, warehouse_id)
        references warehouses (tenant_id, company_id, id),
    constraint stock_balances_product_fk foreign key (tenant_id, product_id)
        references products (tenant_id, id)
);

create index stock_balances_sku_idx on stock_balances (tenant_id, warehouse_id, sku);
create index stock_balances_product_idx on stock_balances (tenant_id, product_id);

-- quantity is the signed effect on the balance (OUT is negative)
create table stock_movements (
    id              uuid primary key,
    tenant_id       uuid           not null references organizations (id),
    company_id      uuid           not null,
    warehouse_id    uuid           not null,
    product_id      uuid           not null,
    type            varchar(10)    not null check (type in ('IN', 'OUT', 'ADJUSTMENT')),
    quantity        numeric(18, 3) not null check (quantity <> 0),
    balance_after   numeric(18, 3) not null check (balance_after >= 0),
    note            varchar(500),
    idempotency_key varchar(100)   not null,
    created_by      uuid           not null references users (id),
    created_at      timestamptz    not null default clock_timestamp(),
    constraint stock_movements_balance_fk foreign key (tenant_id, warehouse_id, product_id)
        references stock_balances (tenant_id, warehouse_id, product_id),
    constraint stock_movements_tenant_key_key unique (tenant_id, idempotency_key)
);

create index stock_movements_history_idx on stock_movements (tenant_id, warehouse_id, created_at desc, id desc);

-- Movements are an immutable audit trail; balances are never deleted.
revoke update, delete on stock_movements from atlas_app;
revoke delete on stock_balances from atlas_app;

alter table stock_balances enable row level security;
alter table stock_balances force row level security;

create policy tenant_isolation on stock_balances
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table stock_movements enable row level security;
alter table stock_movements force row level security;

create policy tenant_isolation on stock_movements
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
