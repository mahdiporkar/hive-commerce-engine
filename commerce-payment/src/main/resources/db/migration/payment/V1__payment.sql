-- Payment context (ADR-0007, ADR-0011). Unique provider references and transaction ids; webhook inbox with replay
-- protection; refunds inside the payment aggregate.
create table payment (
  id                  uuid primary key,
  tenant_id           varchar(160)  not null,
  store_id            uuid          not null,
  order_id            uuid          not null,
  checkout_attempt_id uuid          not null,
  customer_id         uuid          not null,
  amount              numeric(20,4) not null check (amount > 0),
  currency            char(3)       not null,
  provider            varchar(40)   not null,
  provider_ref        varchar(200),
  status              varchar(12)   not null check (status in ('INITIATED', 'CAPTURED', 'FAILED')),
  provider_tx_id      varchar(200),
  revision            bigint        not null default 0,
  created_at          timestamptz   not null default now(),
  updated_at          timestamptz   not null default now(),
  constraint payment_attempt_uk unique (tenant_id, store_id, checkout_attempt_id),
  constraint payment_order_uk unique (tenant_id, store_id, order_id)
);
create unique index payment_provider_ref_uk on payment (provider, provider_ref) where provider_ref is not null;
create unique index payment_provider_tx_uk on payment (provider, provider_tx_id) where provider_tx_id is not null;

create table refund (
  id                 uuid primary key,
  tenant_id          varchar(160)  not null,
  store_id           uuid          not null,
  payment_id         uuid          not null references payment (id),
  idempotency_key    varchar(100)  not null,
  amount             numeric(20,4) not null check (amount > 0),
  order_line_ids     uuid[]        not null default '{}',
  status             varchar(12)   not null check (status in ('REQUESTED', 'COMPLETED', 'FAILED')),
  provider_refund_id varchar(200),
  created_at         timestamptz   not null default now(),
  updated_at         timestamptz   not null default now(),
  constraint refund_key_uk unique (payment_id, idempotency_key)
);

create table webhook_inbox (
  id                uuid primary key,
  tenant_id         varchar(160) not null,
  store_id          uuid         not null,
  provider          varchar(40)  not null,
  provider_event_id varchar(200) not null,
  provider_ref      varchar(200),
  body              text         not null,
  received_at       timestamptz  not null default now(),
  constraint webhook_inbox_event_uk unique (provider, provider_event_id)
);

-- FAKE PSP sessions (development and tests only; never used by real adapters). Not tenant data: no RLS.
create table fake_psp_session (
  ref        varchar(200) primary key,
  payment_id uuid          not null,
  amount     numeric(20,4) not null,
  currency   char(3)       not null,
  return_url varchar(2000) not null,
  status     varchar(12)   not null,
  tx_id      varchar(200),
  created_at timestamptz   not null default now()
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
  foreach t in array array['payment', 'refund', 'webhook_inbox', 'outbox_event'] loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format($p$create policy %I on %I
      using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
      with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)$p$, t || '_scope', t);
  end loop;
end $$;

grant usage on schema payment to ${runtime_role};
grant select, insert, update on payment, refund, webhook_inbox to ${runtime_role};
grant select, insert on outbox_event to ${runtime_role};
grant update (published_at) on outbox_event to ${runtime_role};
grant select, insert, update on fake_psp_session to ${runtime_role};
