-- Consumer-side deduplication for at-least-once outbox delivery (docs/domain/domain-events.md).
create table processed_event (
  consumer     varchar(120) not null,
  event_id     uuid         not null,
  processed_at timestamptz  not null default now(),
  primary key (consumer, event_id)
);

grant select, insert on processed_event to ${runtime_role};
