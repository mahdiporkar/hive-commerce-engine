-- Catalog context (ADR-0002, ADR-0009, ADR-0010). Every table is tenant- and store-scoped with forced row-level
-- security; the runtime role may read, insert and update but never delete (archive-not-delete).

create table attribute_definition (
  id          uuid primary key,
  tenant_id   varchar(160) not null,
  store_id    uuid         not null,
  key         varchar(64)  not null,
  type        varchar(20)  not null,
  labels      jsonb        not null,
  options     jsonb        not null default '[]',
  constraints jsonb        not null default '{}',
  flags       jsonb        not null default '{}',
  archived_at timestamptz,
  revision    bigint       not null default 0,
  created_at  timestamptz  not null default now(),
  updated_at  timestamptz  not null default now(),
  constraint attribute_definition_key_uk unique (tenant_id, store_id, key)
);

create table product_type (
  id          uuid primary key,
  tenant_id   varchar(160) not null,
  store_id    uuid         not null,
  key         varchar(64)  not null,
  name        jsonb        not null,
  archived_at timestamptz,
  revision    bigint       not null default 0,
  created_at  timestamptz  not null default now(),
  updated_at  timestamptz  not null default now(),
  constraint product_type_key_uk unique (tenant_id, store_id, key)
);

create table product_type_schema_version (
  product_type_id uuid         not null references product_type (id),
  tenant_id       varchar(160) not null,
  store_id        uuid         not null,
  version         int          not null check (version > 0),
  status          varchar(12)  not null check (status in ('DRAFT', 'PUBLISHED')),
  specs           jsonb        not null,
  published_at    timestamptz,
  created_at      timestamptz  not null default now(),
  primary key (product_type_id, version),
  constraint schema_version_published_at check ((status = 'PUBLISHED') = (published_at is not null))
);

-- A published schema version is immutable (ADR-0010).
create function forbid_published_schema_change() returns trigger language plpgsql as $$
begin
  if old.status = 'PUBLISHED' then
    raise exception 'published schema version %/% is immutable', old.product_type_id, old.version using errcode = 'P0001';
  end if;
  return new;
end $$;
create trigger product_type_schema_version_immutable before update on product_type_schema_version
  for each row execute function forbid_published_schema_change();

create table product (
  id              uuid primary key,
  tenant_id       varchar(160) not null,
  store_id        uuid         not null,
  product_type_id uuid         not null,
  schema_version  int          not null,
  title           jsonb        not null,
  slug            varchar(120) not null,
  attributes      jsonb        not null default '{}',
  status          varchar(12)  not null check (status in ('DRAFT', 'ARCHIVED')),
  created_by      uuid,
  revision        bigint       not null default 0,
  created_at      timestamptz  not null default now(),
  updated_at      timestamptz  not null default now(),
  constraint product_slug_uk unique (tenant_id, store_id, slug),
  constraint product_schema_fk foreign key (product_type_id, schema_version) references product_type_schema_version (product_type_id, version)
);

create table product_variant (
  id          uuid primary key,
  tenant_id   varchar(160) not null,
  store_id    uuid         not null,
  product_id  uuid         not null references product (id),
  sku         varchar(64)  not null,
  attributes  jsonb        not null default '{}',
  variant_key text         not null,
  created_at  timestamptz  not null default now(),
  constraint product_variant_sku_uk unique (tenant_id, store_id, sku),
  constraint product_variant_key_uk unique (product_id, variant_key)
);
create index product_variant_product_ix on product_variant (product_id);

-- Transactional outbox of this context (ADR-0002): written in the same transaction as the aggregate.
create table outbox_event (
  id              uuid primary key,
  tenant_id       varchar(160) not null,
  store_id        uuid         not null,
  aggregate_type  varchar(80)  not null,
  aggregate_id    varchar(80)  not null,
  event_type      varchar(160) not null,
  event_version   int          not null,
  payload         jsonb        not null,
  correlation_id  varchar(128),
  occurred_at     timestamptz  not null,
  published_at    timestamptz
);
create index outbox_event_unpublished_ix on outbox_event (occurred_at) where published_at is null;

do $$
declare t text;
begin
  foreach t in array array['attribute_definition', 'product_type', 'product_type_schema_version', 'product', 'product_variant', 'outbox_event'] loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format($p$create policy %I on %I
      using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
      with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)$p$, t || '_scope', t);
  end loop;
end $$;

grant usage on schema catalog to ${runtime_role};
grant select, insert, update on attribute_definition, product_type, product_type_schema_version, product, product_variant to ${runtime_role};
grant select, insert on outbox_event to ${runtime_role};
grant update (published_at) on outbox_event to ${runtime_role};
