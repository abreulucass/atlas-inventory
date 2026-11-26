-- A transfer moves stock between two warehouses of one organization; its movements are linked by transfer_id.
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
    -- FK checks bypass RLS: the composite keys keep both ends inside the transfer's own tenant
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

-- type grows from varchar(10): TRANSFER_OUT has 12 characters
alter table stock_movements alter column type type varchar(12);
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

-- Transfers are an immutable audit trail.
revoke update, delete on stock_transfers, stock_transfer_items from atlas_app;

alter table stock_transfers enable row level security;
alter table stock_transfers force row level security;

create policy tenant_isolation on stock_transfers
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table stock_transfer_items enable row level security;
alter table stock_transfer_items force row level security;

create policy tenant_isolation on stock_transfer_items
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
