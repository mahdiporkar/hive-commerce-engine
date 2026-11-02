-- Seller context (master prompt §14, ADR-0016). Memberships reference Hive user ids only; nothing is deleted.
create table seller (
  id           uuid primary key,
  tenant_id    varchar(160) not null,
  store_id     uuid         not null,
  kind         varchar(12)  not null check (kind in ('INTERNAL', 'EXTERNAL')),
  status       varchar(12)  not null check (status in ('APPLIED', 'VERIFIED', 'ACTIVE', 'SUSPENDED', 'CLOSED')),
  display_name jsonb        not null,
  revision     bigint       not null default 0,
  created_at   timestamptz  not null default now(),
  updated_at   timestamptz  not null default now()
);
-- Exactly one internal seller per store.
create unique index seller_one_internal_uk on seller (tenant_id, store_id) where kind = 'INTERNAL';

create table seller_membership (
  seller_id    uuid         not null references seller (id),
  tenant_id    varchar(160) not null,
  store_id     uuid         not null,
  actor_id     uuid         not null,
  role         varchar(8)   not null check (role in ('OWNER', 'STAFF')),
  capabilities text[]       not null default '{}',
  status       varchar(8)   not null check (status in ('PENDING', 'ACTIVE', 'REVOKED')),
  created_at   timestamptz  not null default now(),
  updated_at   timestamptz  not null default now(),
  primary key (seller_id, actor_id)
);
create index seller_membership_actor_ix on seller_membership (tenant_id, store_id, actor_id) where status = 'ACTIVE';

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
  foreach t in array array['seller', 'seller_membership', 'outbox_event'] loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format($p$create policy %I on %I
      using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
      with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)$p$, t || '_scope', t);
  end loop;
end $$;

grant usage on schema seller to ${runtime_role};
grant select, insert, update on seller, seller_membership to ${runtime_role};
grant select, insert on outbox_event to ${runtime_role};
grant update (published_at) on outbox_event to ${runtime_role};
