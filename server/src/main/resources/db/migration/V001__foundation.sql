create extension if not exists pgcrypto;

create or replace function forbid_update_delete() returns trigger
language plpgsql as $$
begin
  raise exception 'table % is append-only; % is forbidden', tg_table_name, tg_op
    using errcode = '55000';
end;
$$;

create table audit_event (
  id uuid primary key default gen_random_uuid(),
  actor_id uuid,
  action text not null,
  entity_type text not null,
  entity_id text not null,
  occurred_at timestamptz not null,
  metadata jsonb not null default '{}',
  previous_hash bytea,
  event_hash bytea not null
);
create trigger audit_event_immutable before update or delete on audit_event
  for each row execute function forbid_update_delete();

create table outbox_message (
  id uuid primary key,
  topic text not null,
  payload jsonb not null,
  occurred_at timestamptz not null,
  published_at timestamptz,
  attempts integer not null default 0
);
create index outbox_pending_idx on outbox_message (occurred_at) where published_at is null;

create table site (
  id uuid primary key default gen_random_uuid(),
  code text not null unique,
  name text not null,
  state text not null check (state in ('ACTIVE','INACTIVE','DISABLED','RETIRED')),
  timezone text not null default 'Africa/Kigali',
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version bigint not null default 0
);

insert into site (code, name, state) values
  ('NYAMYUMBA', 'Nyamyumba', 'ACTIVE'),
  ('SANZARI', 'Sanzari', 'ACTIVE'),
  ('MASORO', 'Masoro', 'ACTIVE'),
  ('KARAMBO', 'Karambo', 'ACTIVE'),
  ('GASAMBYA', 'Gasambya', 'ACTIVE'),
  ('WORKSHOP', 'Workshop', 'ACTIVE');
