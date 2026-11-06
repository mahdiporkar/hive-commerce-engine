-- Reviews by customers who paid for the product (ADR-0018). Expand only. A hidden review is kept, never deleted.
create table product_review (
  id          uuid primary key,
  tenant_id   varchar(160) not null,
  store_id    uuid         not null,
  product_id  uuid         not null,
  customer_id uuid         not null,
  rating      smallint     not null check (rating between 1 and 5),
  body        varchar(2000) not null,
  status      varchar(12)  not null check (status in ('VISIBLE', 'HIDDEN')),
  created_at  timestamptz  not null default now(),
  constraint product_review_one_per_customer unique (tenant_id, store_id, product_id, customer_id)
);
create index product_review_product_idx on product_review (tenant_id, store_id, product_id, created_at desc);
alter table product_review enable row level security;
alter table product_review force row level security;
create policy product_review_scope on product_review
  using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
  with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid);
grant select, insert, update on product_review to ${runtime_role};
