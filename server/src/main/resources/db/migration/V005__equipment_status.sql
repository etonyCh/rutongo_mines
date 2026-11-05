create table equipment_status_event (
  id uuid primary key default gen_random_uuid(),
  equipment_id uuid not null references equipment(id),
  seq bigint not null,
  previous_status text check (previous_status is null or previous_status in ('OPERATIONAL','UNDER_REPAIR','DOWN')),
  new_status text not null check (new_status in ('OPERATIONAL','UNDER_REPAIR','DOWN')),
  reason text,
  problem text,
  action_taken text,
  parts_required text,
  remarks text,
  site_id uuid not null references site(id),
  tunnel_id uuid references tunnel(id),
  shift_id uuid references shift(id),
  technician_ids uuid[] not null default '{}',
  user_id uuid not null references app_user(id),
  user_eid text not null,
  occurred_at timestamptz not null,
  recorded_at timestamptz not null default now(),
  source text not null check (source in ('MANUAL','INSPECTION','BREAKDOWN','SYSTEM_RULE','CORRECTION','IMPORT')),
  breakdown_id uuid,
  corrects_event_id uuid references equipment_status_event(id),
  override_reason text,
  idempotency_key text not null unique,
  unique (equipment_id, seq)
);
create index equipment_status_event_history_idx on equipment_status_event(equipment_id, occurred_at desc, seq desc);
create index equipment_status_event_site_idx on equipment_status_event(site_id, occurred_at desc);
create trigger equipment_status_event_immutable before update or delete on equipment_status_event
  for each row execute function forbid_update_delete();
