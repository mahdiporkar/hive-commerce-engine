-- Expand only (docs/deployment/release-policy.md): the previous engine ignores these columns and keeps working.
alter table product add column media jsonb not null default '[]';
alter table category add column seo jsonb not null default '{}';
