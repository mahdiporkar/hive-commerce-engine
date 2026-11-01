create role commerce_app login password 'test-only-runtime-password-0001' nosuperuser nocreaterole nobypassrls;
grant connect on database commerce to commerce_app;
