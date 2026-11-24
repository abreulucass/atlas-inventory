-- Global table: no tenant_id, no RLS (ADR-002). The slug is immutable, so atlas_app may update only the other columns.
create table organizations (
    id          uuid primary key,
    name        varchar(120) not null,
    slug        varchar(63)  not null constraint organizations_slug_key unique,
    archived_at timestamptz
);

revoke update on organizations from atlas_app;
grant update (name, archived_at) on organizations to atlas_app;
