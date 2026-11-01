-- Store context (ADR-0009). store_id equals id for the store row itself, so the same RLS policy shape applies.
create table store (
  id               uuid primary key,
  tenant_id        varchar(160) not null,
  store_id         uuid         not null,
  key              varchar(80)  not null,
  mode             varchar(20)  not null check (mode in ('SINGLE_VENDOR', 'MULTI_VENDOR', 'HYBRID')),
  default_currency char(3)      not null,
  locales          text[]       not null,
  revision         bigint       not null default 0,
  created_at       timestamptz  not null default now(),
  updated_at       timestamptz  not null default now(),
  constraint store_self_scope check (store_id = id),
  constraint store_key_uk unique (tenant_id, key)
);

alter table store enable row level security;
alter table store force row level security;
create policy store_scope on store
  using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
  with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid);

grant usage on schema store to ${runtime_role};
grant select, insert, update on store to ${runtime_role};
