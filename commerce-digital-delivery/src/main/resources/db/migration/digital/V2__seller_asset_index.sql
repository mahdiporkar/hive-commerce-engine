-- Expand only (docs/deployment/release-policy.md): sellers list their own assets.
create index digital_asset_seller_idx on digital_asset (tenant_id, store_id, owner_seller_id);
