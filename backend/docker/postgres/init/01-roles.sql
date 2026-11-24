-- Roles from ADR-002. Local and test only: the passwords are fictitious.
-- atlas_owner owns the database and runs Flyway; atlas_app is the application and can never bypass RLS.
CREATE ROLE atlas_owner LOGIN PASSWORD 'atlas_owner';
CREATE ROLE atlas_app LOGIN PASSWORD 'atlas_app' NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

-- The public schema belongs to pg_database_owner, so atlas_owner also owns it.
ALTER DATABASE atlas OWNER TO atlas_owner;
REVOKE ALL ON DATABASE atlas FROM PUBLIC;
GRANT CONNECT ON DATABASE atlas TO atlas_app;
