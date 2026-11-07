create table breakdown (
  id uuid primary key default gen_random_uuid(),
  equipment_id uuid not null references equipment(id),
  category text not null,
  problem text not null,
  site_id uuid not null references site(id),
  tunnel_id uuid references tunnel(id),
  shift_id uuid references shift(id),
  reported_by uuid not null references app_user(id),
  reported_at timestamptz not null,
  breakdown_start timestamptz not null,
  repair_start timestamptz,
  repair_end timestamptz,
  state text not null check (state in ('REPORTED','DIAGNOSING','AWAITING_PARTS','UNDER_REPAIR','RESOLVED','CLOSED')),
  action_taken text,
  parts_required text,
  assigned_technician_id uuid references technician(id),
  remarks text,
  version bigint not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  idempotency_key text not null unique,
  check (repair_start is null or repair_start >= breakdown_start),
  check (repair_end is null or repair_end >= coalesce(repair_start, breakdown_start))
);
create unique index uq_breakdown_active on breakdown (equipment_id) where state not in ('RESOLVED','CLOSED');
create index breakdown_site_reported_idx on breakdown(site_id,reported_at desc);
create index breakdown_equipment_reported_idx on breakdown(equipment_id,reported_at desc);
create index breakdown_state_idx on breakdown(state);

alter table defect add constraint defect_breakdown_fk foreign key (breakdown_id) references breakdown(id);
create index defect_breakdown_idx on defect(breakdown_id);

alter table equipment_status_event add constraint equipment_status_event_breakdown_fk foreign key (breakdown_id) references breakdown(id);
create index equipment_status_event_breakdown_idx on equipment_status_event(breakdown_id);
