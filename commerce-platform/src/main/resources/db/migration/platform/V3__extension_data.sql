-- Documents owned by client extensions (docs/architecture/extension-system.md). Extensions never touch core tables.
create table extension_data (
  tenant_id     varchar(160) not null,
  store_id      uuid         not null,
  extension_key varchar(80)  not null,
  subject_type  varchar(40)  not null,
  subject_id    varchar(120) not null,
  document      jsonb        not null,
  updated_by    uuid,
  updated_at    timestamptz  not null default now(),
  primary key (tenant_id, store_id, extension_key, subject_type, subject_id)
);
alter table extension_data enable row level security;
alter table extension_data force row level security;
create policy extension_data_scope on extension_data
  using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
  with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid);
grant select, insert, update on extension_data to ${runtime_role};
