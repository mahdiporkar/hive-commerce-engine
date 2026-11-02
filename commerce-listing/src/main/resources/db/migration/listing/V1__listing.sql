-- Listing context: publication of products and the public search projection (ADR-0010, ADR-0015).
create table listing (
  id                  uuid primary key,
  tenant_id           varchar(160) not null,
  store_id            uuid         not null,
  product_id          uuid         not null,
  channel             varchar(40)  not null,
  submitted_by_seller uuid,
  status              varchar(20)  not null check (status in ('PENDING_APPROVAL', 'APPROVED', 'PUBLISHED', 'UNPUBLISHED', 'REJECTED')),
  rejection_reason    varchar(500),
  revision            bigint       not null default 0,
  created_at          timestamptz  not null default now(),
  updated_at          timestamptz  not null default now(),
  constraint listing_product_channel_uk unique (tenant_id, store_id, product_id, channel)
);

-- Derived read model of published listings (rebuildable from Catalog snapshots).
create table listing_view (
  listing_id       uuid primary key references listing (id),
  tenant_id        varchar(160) not null,
  store_id         uuid         not null,
  product_id       uuid         not null,
  slug             varchar(120) not null,
  title            jsonb        not null,
  category_id      uuid,
  kind             varchar(12)  not null,
  product_type_key varchar(64)  not null,
  attributes       jsonb        not null,
  variants         jsonb        not null,
  search_text      text         not null,
  published        boolean      not null,
  published_at     timestamptz  not null
);
create unique index listing_view_slug_uk on listing_view (tenant_id, store_id, slug);
create index listing_view_category_ix on listing_view (tenant_id, store_id, category_id) where published;

create table listing_facet (
  listing_id uuid         not null references listing_view (listing_id),
  tenant_id  varchar(160) not null,
  store_id   uuid         not null,
  key        varchar(64)  not null,
  text_value varchar(500),
  num_value  numeric
);
create index listing_facet_text_ix on listing_facet (tenant_id, store_id, key, text_value);
create index listing_facet_num_ix on listing_facet (tenant_id, store_id, key, num_value);
create index listing_facet_listing_ix on listing_facet (listing_id);

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
  foreach t in array array['listing', 'listing_view', 'listing_facet', 'outbox_event'] loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format($p$create policy %I on %I
      using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
      with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)$p$, t || '_scope', t);
  end loop;
end $$;

grant usage on schema listing to ${runtime_role};
grant select, insert, update on listing, listing_view to ${runtime_role};
-- Facet rows are derived data and are replaced on republish.
grant select, insert, delete on listing_facet to ${runtime_role};
grant select, insert on outbox_event to ${runtime_role};
grant update (published_at) on outbox_event to ${runtime_role};
