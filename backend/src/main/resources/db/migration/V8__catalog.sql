create table categories (
    id          uuid primary key,
    tenant_id   uuid not null references organizations (id),
    name        varchar(80) not null,
    archived_at timestamptz,
    -- FK target for products: pairs a category with its own tenant
    constraint categories_tenant_id_key unique (tenant_id, id)
);

create unique index categories_tenant_name_key on categories (tenant_id, lower(name));

create table products (
    id          uuid primary key,
    tenant_id   uuid not null references organizations (id),
    sku         varchar(40) not null,
    name        varchar(200) not null,
    unit        varchar(2) not null check (unit in ('UN', 'CX', 'PC', 'KG', 'G', 'L', 'ML', 'M')),
    category_id uuid,
    archived_at timestamptz,
    version     bigint not null default 0,
    -- FK checks bypass RLS: the composite key keeps a product off another tenant's category; MATCH SIMPLE lets category_id be null
    constraint products_category_fk foreign key (tenant_id, category_id) references categories (tenant_id, id),
    constraint products_tenant_sku_key unique (tenant_id, sku)
);

create index products_tenant_name_idx on products (tenant_id, lower(name), id);
create index products_tenant_category_idx on products (tenant_id, category_id);

alter table categories enable row level security;
alter table categories force row level security;

create policy tenant_isolation on categories
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table products enable row level security;
alter table products force row level security;

create policy tenant_isolation on products
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
