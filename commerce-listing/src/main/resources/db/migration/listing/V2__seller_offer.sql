-- Seller offers (master prompt §10, ADR-0015 change 2: seller SKUs unique per seller).
create table seller_offer (
  id          uuid primary key,
  tenant_id   varchar(160)  not null,
  store_id    uuid          not null,
  seller_id   uuid          not null,
  product_id  uuid          not null,
  variant_id  uuid          not null,
  price       numeric(20,4) not null check (price > 0),
  currency    char(3)       not null,
  condition   varchar(12)   not null check (condition in ('NEW', 'USED', 'REFURBISHED')),
  unique_item boolean       not null,
  digital     boolean       not null,
  seller_sku  varchar(64),
  status      varchar(12)   not null check (status in ('ACTIVE', 'WITHDRAWN')),
  revision    bigint        not null default 0,
  created_at  timestamptz   not null default now(),
  updated_at  timestamptz   not null default now(),
  constraint seller_offer_variant_uk unique (tenant_id, store_id, seller_id, variant_id)
);
create unique index seller_offer_seller_sku_uk on seller_offer (tenant_id, store_id, seller_id, seller_sku) where seller_sku is not null;
create index seller_offer_product_ix on seller_offer (tenant_id, store_id, product_id) where status = 'ACTIVE';

alter table seller_offer enable row level security;
alter table seller_offer force row level security;
create policy seller_offer_scope on seller_offer
  using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
  with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid);
grant select, insert, update on seller_offer to ${runtime_role};
