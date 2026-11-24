-- First tenant table: tenant_id leads every unique key, and forced RLS refuses rows of other organizations (ADR-002).
create table companies (
    id          uuid primary key,
    tenant_id   uuid not null references organizations (id),
    name        varchar(120) not null,
    cnpj        char(14),
    archived_at timestamptz,
    constraint companies_tenant_cnpj_key unique (tenant_id, cnpj)
);

create unique index companies_tenant_name_key on companies (tenant_id, lower(name));

alter table companies enable row level security;
alter table companies force row level security;

-- After a transaction-local set_config the session reads '' instead of NULL: nullif keeps it failing closed.
create policy tenant_isolation on companies
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
