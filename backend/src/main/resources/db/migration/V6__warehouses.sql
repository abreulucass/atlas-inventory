-- FK target for warehouses: pairs a company with its own tenant.
alter table companies add constraint companies_tenant_id_key unique (tenant_id, id);

create table warehouses (
    id          uuid primary key,
    tenant_id   uuid not null references organizations (id),
    company_id  uuid not null,
    name        varchar(120) not null,
    code        varchar(20) not null,
    archived_at timestamptz,
    -- FK checks bypass RLS: the composite key is what keeps a warehouse off another tenant's company
    constraint warehouses_company_fk foreign key (tenant_id, company_id) references companies (tenant_id, id),
    constraint warehouses_tenant_company_code_key unique (tenant_id, company_id, code)
);

create unique index warehouses_tenant_company_name_key on warehouses (tenant_id, company_id, lower(name));

alter table warehouses enable row level security;
alter table warehouses force row level security;

create policy tenant_isolation on warehouses
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
