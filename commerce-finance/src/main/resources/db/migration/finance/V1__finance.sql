-- Finance context (ADR-0011). The ledger is append-only and double-entry, enforced here as well as in code:
-- the runtime role has no UPDATE on ledger tables, triggers refuse changes, and a deferred trigger checks the balance of
-- every journal entry at commit. Balances are always derived from journal lines.
create table journal_entry (
  id         uuid primary key,
  tenant_id  varchar(160) not null,
  store_id   uuid         not null,
  type       varchar(24)  not null,
  source_id  varchar(80)  not null,
  currency   char(3)      not null,
  posted_at  timestamptz  not null default now(),
  constraint journal_entry_source_uk unique (tenant_id, store_id, type, source_id)
);

create table journal_line (
  id        uuid primary key,
  tenant_id varchar(160)  not null,
  store_id  uuid          not null,
  entry_id  uuid          not null references journal_entry (id),
  account   varchar(80)   not null,
  debit     numeric(20,4) not null check (debit >= 0),
  credit    numeric(20,4) not null check (credit >= 0),
  currency  char(3)       not null,
  constraint journal_line_one_side check ((debit = 0) <> (credit = 0))
);
create index journal_line_account_ix on journal_line (tenant_id, store_id, account, currency);
create index journal_line_entry_ix on journal_line (entry_id);

create function forbid_ledger_change() returns trigger language plpgsql as $$
begin
  raise exception 'the ledger is append-only: post a reversing entry instead' using errcode = 'P0001';
end $$;
create trigger journal_entry_immutable before update or delete on journal_entry for each row execute function forbid_ledger_change();
create trigger journal_line_immutable before update or delete on journal_line for each row execute function forbid_ledger_change();

create function check_journal_balance() returns trigger language plpgsql as $$
declare difference numeric;
begin
  select sum(debit) - sum(credit) into difference from finance.journal_line where entry_id = new.entry_id;
  if difference <> 0 then
    raise exception 'journal entry % is not balanced', new.entry_id using errcode = 'P0001';
  end if;
  return null;
end $$;
create constraint trigger journal_line_balanced after insert on journal_line deferrable initially deferred
  for each row execute function check_journal_balance();

create table commission_rule (
  id             uuid primary key,
  tenant_id      varchar(160)  not null,
  store_id       uuid          not null,
  level          varchar(10)   not null check (level in ('PRODUCT', 'SELLER', 'CATEGORY', 'STORE')),
  target_id      uuid,
  rate_percent   numeric(7,4)  not null check (rate_percent between 0 and 100),
  effective_from timestamptz,
  effective_to   timestamptz,
  created_at     timestamptz   not null default now()
);

create table commission_line (
  line_id         uuid primary key,
  tenant_id       varchar(160)  not null,
  store_id        uuid          not null,
  seller_order_id uuid          not null,
  seller_id       uuid          not null,
  line_total      numeric(20,4) not null,
  tax             numeric(20,4) not null,
  commission      numeric(20,4) not null,
  rate_percent    numeric(7,4)  not null,
  rule_id         uuid,
  currency        char(3)       not null,
  created_at      timestamptz   not null default now()
);

create table settlement (
  id        uuid primary key,
  tenant_id varchar(160)  not null,
  store_id  uuid          not null,
  seller_id uuid          not null,
  amount    numeric(20,4) not null,
  currency  char(3)       not null,
  closed_at timestamptz   not null
);
create index settlement_seller_ix on settlement (tenant_id, store_id, seller_id);

create table seller_earning (
  seller_order_id uuid primary key,
  tenant_id       varchar(160)  not null,
  store_id        uuid          not null,
  order_id        uuid          not null,
  seller_id       uuid          not null,
  net             numeric(20,4) not null,
  refunded        numeric(20,4) not null default 0,
  currency        char(3)       not null,
  status          varchar(10)   not null check (status in ('PENDING', 'RELEASED')),
  delivered_at    timestamptz,
  settlement_id   uuid references settlement (id),
  created_at      timestamptz   not null default now()
);
create index seller_earning_seller_ix on seller_earning (tenant_id, store_id, seller_id, status);

-- One payout per settlement: a refund or a retry can never produce a second payout.
create table payout (
  id            uuid primary key,
  tenant_id     varchar(160)  not null,
  store_id      uuid          not null,
  seller_id     uuid          not null,
  settlement_id uuid          not null references settlement (id),
  amount        numeric(20,4) not null check (amount > 0),
  currency      char(3)       not null,
  status        varchar(10)   not null check (status in ('REQUESTED', 'APPROVED', 'PAID', 'REJECTED')),
  reference     varchar(200),
  revision      bigint        not null default 0,
  created_at    timestamptz   not null default now(),
  updated_at    timestamptz   not null default now(),
  constraint payout_settlement_uk unique (tenant_id, store_id, settlement_id)
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
  foreach t in array array['journal_entry', 'journal_line', 'commission_rule', 'commission_line', 'settlement', 'seller_earning', 'payout', 'outbox_event'] loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format($p$create policy %I on %I
      using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
      with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)$p$, t || '_scope', t);
  end loop;
end $$;

grant usage on schema finance to ${runtime_role};
grant select, insert, update on journal_entry, journal_line, commission_rule, commission_line, settlement, seller_earning, payout to ${runtime_role};
grant select, insert on outbox_event to ${runtime_role};
grant update (published_at) on outbox_event to ${runtime_role};
revoke update on journal_entry, journal_line, commission_line, settlement from ${runtime_role};
