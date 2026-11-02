-- Category tree (master prompt §9). Materialized path for subtree queries; archive, never delete.
create table category (
  id             uuid primary key,
  tenant_id      varchar(160) not null,
  store_id       uuid         not null,
  parent_id      uuid references category (id),
  path           varchar(2000) not null,
  slug           varchar(120) not null,
  name           jsonb        not null,
  attribute_keys text[]       not null default '{}',
  position       int          not null default 0,
  status         varchar(12)  not null check (status in ('ACTIVE', 'ARCHIVED')),
  revision       bigint       not null default 0,
  created_at     timestamptz  not null default now(),
  updated_at     timestamptz  not null default now()
);
-- Slugs are unique among siblings (root categories have no parent, hence the coalesce).
create unique index category_sibling_slug_uk on category (tenant_id, store_id, coalesce(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), slug);
create index category_path_ix on category (tenant_id, store_id, path text_pattern_ops);

alter table product add column category_id uuid references category (id);
alter table product_type add column kind varchar(12) not null default 'PHYSICAL' check (kind in ('PHYSICAL', 'DIGITAL'));

alter table category enable row level security;
alter table category force row level security;
create policy category_scope on category
  using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
  with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid);

grant select, insert, update on category to ${runtime_role};
