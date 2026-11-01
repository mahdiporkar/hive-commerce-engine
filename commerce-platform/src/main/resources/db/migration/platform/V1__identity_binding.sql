-- Technical tables of the Hive anticorruption layer (ADR-0005). Not store data, so no row-level security.
create table identity_binding (
  issuer       varchar(500) not null,
  subject      varchar(255) not null,
  hive_user_id uuid         not null,
  tenant_id    varchar(160) not null,
  created_at   timestamptz  not null default now(),
  primary key (issuer, subject)
);

-- Append-only record of security-relevant rejections (forged or inconsistent identities).
create table security_event (
  id          bigint generated always as identity primary key,
  event_type  varchar(80)  not null,
  issuer      varchar(500),
  subject     varchar(255),
  details     varchar(1000),
  occurred_at timestamptz  not null default now()
);

grant usage on schema platform to ${runtime_role};
grant select, insert on identity_binding to ${runtime_role};
grant insert on security_event to ${runtime_role};
