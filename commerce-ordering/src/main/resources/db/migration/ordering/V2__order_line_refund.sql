-- Lines covered by a completed refund (insert-only; order lines themselves stay immutable).
create table order_line_refund (
  line_id    uuid primary key references order_line (id),
  tenant_id  varchar(160) not null,
  store_id   uuid         not null,
  order_id   uuid         not null references customer_order (id),
  created_at timestamptz  not null default now()
);
alter table order_line_refund enable row level security;
alter table order_line_refund force row level security;
create policy order_line_refund_scope on order_line_refund
  using (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid)
  with check (tenant_id = current_setting('app.tenant_id') and store_id = current_setting('app.store_id')::uuid);
grant select, insert on order_line_refund to ${runtime_role};
