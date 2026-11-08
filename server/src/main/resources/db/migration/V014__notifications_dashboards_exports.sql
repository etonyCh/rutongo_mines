create table notification_rule (
  type text primary key,
  roles text[] not null default '{}',
  site_scoped boolean not null default true,
  channel text not null default 'IN_APP' check (channel in ('IN_APP','PUSH','BOTH')),
  enabled boolean not null default true,
  updated_at timestamptz not null default now()
);

insert into notification_rule(type,roles,site_scoped,channel) values
  ('INSPECTION_MISSING',array['SUPERVISOR'],true,'BOTH'),
  ('CRITICAL_DEFECT',array['SUPERVISOR','MANAGER'],true,'BOTH'),
  ('EQUIPMENT_DOWN',array['SUPERVISOR','MANAGER'],true,'BOTH'),
  ('REPAIR_ATTENTION',array['SUPERVISOR'],true,'BOTH'),
  ('PART_REQUEST',array['SUPERVISOR','MANAGER'],true,'BOTH'),
  ('LOW_STOCK',array['SUPERVISOR','MANAGER'],false,'BOTH'),
  ('SERVICE_DUE',array['SUPERVISOR','MANAGER'],true,'BOTH'),
  ('MAINTENANCE_VERIFY',array['SUPERVISOR'],true,'BOTH'),
  ('REPORT_APPROVAL',array['MANAGER'],true,'BOTH');

create table notification (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references app_user(id),
  type text not null,
  dedupe_key text not null,
  payload jsonb not null default '{}',
  created_at timestamptz not null default now(),
  read_at timestamptz,
  unique (user_id,dedupe_key)
);
create index notification_user_created_idx on notification(user_id,created_at desc,id desc);
create index notification_user_unread_idx on notification(user_id) where read_at is null;

create table push_device (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references app_user(id),
  token text not null unique,
  platform text not null check (platform in ('ANDROID','WEB')),
  created_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now()
);
create index push_device_user_idx on push_device(user_id,last_seen_at desc);
