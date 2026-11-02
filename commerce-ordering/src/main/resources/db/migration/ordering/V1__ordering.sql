-- Ordering context (master prompt §12). Order lines are immutable snapshots: the runtime role may update order and
-- seller-order status but has no UPDATE on order_line.
create table customer_order (
  id                  uuid primary key,
  tenant_id           varchar(160)  not null,
  store_id            uuid          not null,
  number              varchar(32)   not null,
  customer_id         uuid          not null,
  checkout_attempt_id uuid          not null,
  status              varchar(20)   not null check (status in ('PLACED', 'PAID', 'CANCELLED', 'PAID_UNFULFILLABLE', 'COMPLETED')),
  total               numeric(20,4) not null check (total >= 0),
  currency            char(3)       not null,
  payment_id          uuid,
  revision            bigint        not null default 0,
  created_at          timestamptz   not null default now(),
  updated_at          timestamptz   not null default now(),
  constraint customer_order_number_uk unique (tenant_id, store_id, number),
  constraint customer_order_attempt_uk unique (tenant_id, store_id, checkout_attempt_id)
);
create index customer_order_customer_ix on customer_order (tenant_id, store_id, customer_id, created_at desc);

create table seller_order (
  id         uuid primary key,
  tenant_id  varchar(160)  not null,
  store_id   uuid          not null,
  order_id   uuid          not null references customer_order (id),
  seller_id  uuid          not null,
  status     varchar(24)   not null check (status in ('AWAITING_PAYMENT', 'AWAITING_FULFILLMENT', 'SHIPPED', 'DELIVERED', 'CANCELLED')),
  shipping   numeric(20,4) not null check (shipping >= 0),
  created_at timestamptz   not null default now(),
  updated_at timestamptz   not null default now()
);
create index seller_order_seller_ix on seller_order (tenant_id, store_id, seller_id);

create table order_line (
  id                 uuid primary key,
  tenant_id          varchar(160)  not null,
  store_id           uuid          not null,
  seller_order_id    uuid          not null references seller_order (id),
  offer_id           uuid          not null,
  product_id         uuid          not null,
  variant_id         uuid          not null,
  kind               varchar(12)   not null,
  sku                varchar(64)   not null,
  title              jsonb         not null,
  variant_attributes jsonb         not null,
  unit_price         numeric(20,4) not null,
  quantity           int           not null check (quantity > 0),
  line_total         numeric(20,4) not null,
  tax                numeric(20,4) not null,
  created_at         timestamptz   not null default now()
);

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
  foreach t in array array['customer_order', 'seller_order', 'outbox_event'] loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format($p$create policy %I on %I
      using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
      with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)$p$, t || '_scope', t);
  end loop;
end $$;

grant usage on schema ordering to ${runtime_role};
grant select, insert, update on customer_order, seller_order to ${runtime_role};
grant select, insert on outbox_event to ${runtime_role};
grant update (published_at) on outbox_event to ${runtime_role};

alter table order_line enable row level security;
alter table order_line force row level security;
create policy order_line_scope on order_line
  using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
  with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid);
grant select, insert on order_line to ${runtime_role};
