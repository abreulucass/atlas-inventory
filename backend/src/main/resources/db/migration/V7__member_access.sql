-- FK target for warehouse grants: pairs a warehouse with its own company and tenant.
alter table warehouses add constraint warehouses_tenant_company_id_key unique (tenant_id, company_id, id);

-- A MEMBER's visibility (ADR-002): a null warehouse_id grants the whole company.
create table member_access (
    id           uuid primary key,
    tenant_id    uuid not null references organizations (id),
    user_id      uuid not null,
    company_id   uuid not null,
    warehouse_id uuid,
    created_at   timestamptz not null,
    -- grants live and die with the membership (feature 20, Edge 1-2)
    constraint member_access_membership_fk foreign key (user_id, tenant_id)
        references memberships (user_id, organization_id) on delete cascade,
    -- FK checks bypass RLS: the composite keys keep a grant off another tenant's company or warehouse
    constraint member_access_company_fk foreign key (tenant_id, company_id) references companies (tenant_id, id),
    constraint member_access_warehouse_fk foreign key (tenant_id, company_id, warehouse_id)
        references warehouses (tenant_id, company_id, id)
);

create unique index member_access_company_key on member_access (tenant_id, user_id, company_id)
    where warehouse_id is null;
create unique index member_access_warehouse_key on member_access (tenant_id, user_id, warehouse_id)
    where warehouse_id is not null;

alter table member_access enable row level security;
alter table member_access force row level security;

create policy tenant_isolation on member_access
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
