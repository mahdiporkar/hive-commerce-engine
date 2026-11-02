-- Checkout context: carts (guest carts are found by the SHA-256 of their token only) and checkout attempts (saga state).
create table cart (
  id               uuid primary key,
  tenant_id        varchar(160) not null,
  store_id         uuid         not null,
  customer_id      uuid,
  guest_token_hash char(64),
  status           varchar(12)  not null check (status in ('ACTIVE', 'MERGED', 'CHECKED_OUT')),
  expires_at       timestamptz,
  revision         bigint       not null default 0,
  created_at       timestamptz  not null default now(),
  updated_at       timestamptz  not null default now(),
  constraint cart_owner check ((customer_id is null) <> (guest_token_hash is null))
);
create unique index cart_guest_token_uk on cart (guest_token_hash) where guest_token_hash is not null;
create unique index cart_customer_active_uk on cart (tenant_id, store_id, customer_id) where status = 'ACTIVE' and customer_id is not null;

create table cart_item (
  id          uuid primary key,
  tenant_id   varchar(160) not null,
  store_id    uuid         not null,
  cart_id     uuid         not null references cart (id),
  offer_id    uuid         not null,
  quantity    int          not null check (quantity between 1 and 99),
  unique_item boolean      not null,
  position    int          not null
);
create index cart_item_cart_ix on cart_item (cart_id);

create table checkout_attempt (
  id              uuid primary key,
  tenant_id       varchar(160)  not null,
  store_id        uuid          not null,
  customer_id     uuid          not null,
  idempotency_key varchar(100)  not null,
  cart_id         uuid          not null,
  state           varchar(20)   not null check (state in ('STARTED', 'RESERVED', 'ORDERED', 'PAYMENT_INITIATED', 'COMPLETED', 'FAILED', 'COMPENSATED')),
  order_id        uuid,
  order_number    varchar(32),
  total           numeric(20,4),
  currency        char(3),
  payment_id      uuid,
  redirect_url    varchar(2000),
  failure_code    varchar(80),
  revision        bigint        not null default 0,
  created_at      timestamptz   not null default now(),
  updated_at      timestamptz   not null default now(),
  constraint checkout_attempt_key_uk unique (tenant_id, store_id, customer_id, idempotency_key)
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
  foreach t in array array['cart', 'cart_item', 'checkout_attempt', 'outbox_event'] loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format($p$create policy %I on %I
      using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
      with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)$p$, t || '_scope', t);
  end loop;
end $$;

grant usage on schema checkout to ${runtime_role};
grant select, insert, update on cart, cart_item, checkout_attempt to ${runtime_role};
grant delete on cart_item to ${runtime_role};
grant select, insert on outbox_event to ${runtime_role};
grant update (published_at) on outbox_event to ${runtime_role};
