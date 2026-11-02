-- Fulfillment context: one shipment per seller order with physical lines.
create table fulfillment (
  id              uuid primary key,
  tenant_id       varchar(160) not null,
  store_id        uuid         not null,
  order_id        uuid         not null,
  seller_order_id uuid         not null,
  seller_id       uuid         not null,
  status          varchar(12)  not null check (status in ('PENDING', 'SHIPPED', 'DELIVERED')),
  carrier         varchar(80),
  tracking_code   varchar(120),
  revision        bigint       not null default 0,
  created_at      timestamptz  not null default now(),
  updated_at      timestamptz  not null default now(),
  constraint fulfillment_seller_order_uk unique (tenant_id, store_id, seller_order_id)
);
create index fulfillment_seller_ix on fulfillment (tenant_id, store_id, seller_id);

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
  foreach t in array array['fulfillment', 'outbox_event'] loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format($p$create policy %I on %I
      using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
      with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)$p$, t || '_scope', t);
  end loop;
end $$;

grant usage on schema fulfillment to ${runtime_role};
grant select, insert, update on fulfillment to ${runtime_role};
grant select, insert on outbox_event to ${runtime_role};
grant update (published_at) on outbox_event to ${runtime_role};
