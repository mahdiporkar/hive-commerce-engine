-- Inventory context (master prompt §12 rule 6). The check constraints back the aggregate's invariants; optimistic
-- revision makes concurrent reservations of the last unit fail for all but one buyer.
create table stock_item (
  id          uuid primary key,
  tenant_id   varchar(160) not null,
  store_id    uuid         not null,
  offer_id    uuid         not null,
  seller_id   uuid         not null,
  unique_item boolean      not null,
  unlimited   boolean      not null,
  on_hand     int          not null,
  reserved    int          not null,
  revision    bigint       not null default 0,
  created_at  timestamptz  not null default now(),
  updated_at  timestamptz  not null default now(),
  constraint stock_item_offer_uk unique (tenant_id, store_id, offer_id),
  constraint stock_item_counts check (reserved >= 0 and on_hand >= 0 and (unlimited or on_hand >= reserved)),
  constraint stock_item_unique_max_one check (not unique_item or on_hand <= 1)
);

create table reservation (
  id                  uuid primary key,
  tenant_id           varchar(160) not null,
  store_id            uuid         not null,
  stock_item_id       uuid         not null references stock_item (id),
  checkout_attempt_id uuid         not null,
  quantity            int          not null check (quantity > 0),
  expires_at          timestamptz  not null,
  status              varchar(10)  not null check (status in ('RESERVED', 'RELEASED', 'CONSUMED', 'EXPIRED')),
  created_at          timestamptz  not null default now(),
  updated_at          timestamptz  not null default now()
);
create index reservation_attempt_ix on reservation (checkout_attempt_id);
create index reservation_open_ix on reservation (expires_at) where status = 'RESERVED';

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
  foreach t in array array['stock_item', 'reservation', 'outbox_event'] loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format($p$create policy %I on %I
      using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
      with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)$p$, t || '_scope', t);
  end loop;
end $$;

grant usage on schema inventory to ${runtime_role};
grant select, insert, update on stock_item, reservation to ${runtime_role};
grant select, insert on outbox_event to ${runtime_role};
grant update (published_at) on outbox_event to ${runtime_role};
