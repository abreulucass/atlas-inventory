-- Global tables: no tenant_id, no RLS (ADR-002). One login per person, so the e-mail is unique across organizations.
create table users (
    id            uuid primary key,
    name          varchar(120) not null,
    email         varchar(254) not null constraint users_email_key unique,
    password_hash varchar(255) not null,
    created_at    timestamptz  not null
);

-- The e-mail is immutable and users are never deleted: deleting one would free its e-mail (AUTH-01).
revoke update on users from atlas_app;
grant update (name, password_hash) on users to atlas_app;
revoke delete on users from atlas_app;

create table memberships (
    id              uuid primary key,
    user_id         uuid        not null references users (id),
    organization_id uuid        not null references organizations (id),
    role            varchar(16) not null check (role in ('OWNER', 'ADMIN', 'MEMBER')),
    created_at      timestamptz not null,
    unique (user_id, organization_id)
);

-- Only the SHA-256 of the token is stored; the family groups the tokens of one session chain (AUTH-06, AUTH-07).
create table refresh_tokens (
    id              uuid primary key,
    token_hash      bytea       not null unique,
    family_id       uuid        not null,
    user_id         uuid        not null references users (id),
    organization_id uuid        not null references organizations (id),
    expires_at      timestamptz not null,
    used_at         timestamptz,
    revoked_at      timestamptz
);

create index refresh_tokens_family_id_idx on refresh_tokens (family_id);
