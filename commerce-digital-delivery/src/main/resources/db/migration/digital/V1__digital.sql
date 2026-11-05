-- Digital Delivery context (master prompt §13, ADR-0008). Asset ids are random and unguessable; content versions and
-- the access log are immutable; entitlements are unique per order line and asset.
create table digital_asset (
  id               varchar(40)   primary key,
  tenant_id        varchar(160)  not null,
  store_id         uuid          not null,
  owner_seller_id  uuid          not null,
  product_id       uuid          not null,
  kind             varchar(8)    not null check (kind in ('FILE', 'TEXT')),
  title            varchar(200)  not null,
  download_limit   int,
  updates_included boolean       not null,
  license_usage    varchar(2000),
  preview_text     varchar(2000),
  revision         bigint        not null default 0,
  created_at       timestamptz   not null default now(),
  updated_at       timestamptz   not null default now()
);
create index digital_asset_product_ix on digital_asset (tenant_id, store_id, product_id);

create table content_version (
  asset_id    varchar(40)  not null references digital_asset (id),
  tenant_id   varchar(160) not null,
  store_id    uuid         not null,
  version     int          not null check (version > 0),
  object_key  varchar(500) not null,
  sha256      char(64)     not null,
  size_bytes  bigint       not null check (size_bytes > 0),
  mime_type   varchar(100) not null,
  scan_status varchar(16)  not null,
  created_at  timestamptz  not null,
  primary key (asset_id, version)
);

create table upload_session (
  id              varchar(40)  primary key,
  tenant_id       varchar(160) not null,
  store_id        uuid         not null,
  asset_id        varchar(40)  not null references digital_asset (id),
  object_key      varchar(500) not null,
  declared_size   bigint       not null,
  declared_sha256 char(64)     not null,
  declared_mime   varchar(100) not null,
  expires_at      timestamptz  not null,
  status          varchar(12)  not null check (status in ('PENDING', 'FINALIZED', 'REJECTED')),
  rejection       varchar(60),
  created_at      timestamptz  not null default now(),
  updated_at      timestamptz  not null default now()
);

create table entitlement (
  id               uuid primary key,
  tenant_id        varchar(160) not null,
  store_id         uuid         not null,
  customer_id      uuid         not null,
  order_id         uuid         not null,
  order_line_id    uuid         not null,
  product_id       uuid         not null,
  asset_id         varchar(40)  not null references digital_asset (id),
  granted_version  int          not null,
  updates_included boolean      not null,
  download_limit   int,
  downloads_used   int          not null check (downloads_used >= 0),
  status           varchar(10)  not null check (status in ('ACTIVE', 'REVOKED')),
  revoked_reason   varchar(200),
  revision         bigint       not null default 0,
  created_at       timestamptz  not null default now(),
  updated_at       timestamptz  not null default now(),
  constraint entitlement_line_asset_uk unique (order_line_id, asset_id),
  constraint entitlement_limit check (download_limit is null or downloads_used <= download_limit)
);
create index entitlement_customer_ix on entitlement (tenant_id, store_id, customer_id);

create table download_access_log (
  id             uuid primary key,
  tenant_id      varchar(160) not null,
  store_id       uuid         not null,
  entitlement_id uuid         not null references entitlement (id),
  customer_id    uuid         not null,
  version        int          not null,
  delivery       varchar(12)  not null,
  expires_at     timestamptz,
  issued_at      timestamptz  not null default now()
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
  foreach t in array array['digital_asset', 'content_version', 'upload_session', 'entitlement', 'download_access_log', 'outbox_event'] loop
    execute format('alter table %I enable row level security', t);
    execute format('alter table %I force row level security', t);
    execute format($p$create policy %I on %I
      using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
      with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)$p$, t || '_scope', t);
  end loop;
end $$;

grant usage on schema digital to ${runtime_role};
grant select, insert, update on digital_asset, content_version, upload_session, entitlement, download_access_log to ${runtime_role};
grant select, insert on outbox_event to ${runtime_role};
grant update (published_at) on outbox_event to ${runtime_role};
revoke update on content_version, download_access_log from ${runtime_role};
