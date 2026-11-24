-- Runs as atlas_owner: every table and sequence it creates from now on is usable by atlas_app (ADR-002),
-- which gets data access only, never DDL.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO atlas_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO atlas_app;
