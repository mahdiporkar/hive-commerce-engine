-- One-time database bootstrap for a client deployment, run by a database administrator (not by the engine).
-- psql -v owner_password=... -v runtime_password=... -d <database> -f create-roles.sql
-- The owner role runs migrations and owns every table; the runtime role owns nothing, cannot bypass row-level
-- security, and only receives the grants that each migration gives it (ADR-0009).
create role commerce_owner login password :'owner_password' nosuperuser nocreaterole nobypassrls;
create role commerce_app login password :'runtime_password' nosuperuser nocreaterole nobypassrls;
select format('grant create, connect on database %I to commerce_owner', current_database()) \gexec
select format('grant connect on database %I to commerce_app', current_database()) \gexec
