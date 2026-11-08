create table service_type (
  id uuid primary key default gen_random_uuid(),
  code text not null unique,
  name text not null,
  hours_based boolean not null,
  satisfies_type_ids uuid[] not null default '{}'
);

create table service_interval (
  id uuid primary key default gen_random_uuid(),
  service_type_id uuid not null references service_type(id),
  equipment_type_id uuid references equipment_type(id),
  equipment_id uuid references equipment(id),
  interval_hours integer not null check (interval_hours > 0),
  due_soon_threshold_hours integer not null default 50 check (due_soon_threshold_hours >= 0)
);
create index service_interval_type_idx on service_interval(equipment_type_id,service_type_id);
create index service_interval_equipment_idx on service_interval(equipment_id,service_type_id);

create table maintenance_record (
  id uuid primary key default gen_random_uuid(),
  equipment_id uuid not null references equipment(id),
  service_type_id uuid not null references service_type(id),
  site_id uuid not null references site(id),
  tunnel_id uuid references tunnel(id),
  performed_at timestamptz not null,
  engine_hours numeric(10,1) check (engine_hours is null or engine_hours >= 0),
  work_performed text not null,
  cause text,
  recorded_by uuid not null references app_user(id),
  technician_id uuid not null references technician(id),
  supervisor_id uuid references app_user(id),
  breakdown_id uuid references breakdown(id),
  replaced_components jsonb not null default '[]' check (jsonb_typeof(replaced_components) = 'array'),
  downtime_minutes integer check (downtime_minutes is null or downtime_minutes >= 0),
  labour_cost bigint not null default 0 check (labour_cost >= 0),
  parts_cost bigint not null default 0 check (parts_cost >= 0),
  total_cost bigint generated always as (labour_cost + parts_cost) stored,
  currency char(3) not null check (currency ~ '^[A-Z]{3}$'),
  verification_status text not null default 'PENDING_VERIFICATION' check (verification_status in
    ('DRAFT','PENDING_VERIFICATION','VERIFIED','REJECTED','CORRECTION_REQUESTED')),
  verified_by uuid references app_user(id),
  verified_at timestamptz,
  verification_comment text,
  notes text,
  version bigint not null default 0,
  idempotency_key text not null unique,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index maintenance_equipment_performed_idx on maintenance_record(equipment_id,performed_at desc);
create index maintenance_site_performed_idx on maintenance_record(site_id,performed_at desc);
create index maintenance_verification_idx on maintenance_record(verification_status,performed_at desc);

alter table equipment add constraint equipment_last_maintenance_fk foreign key (last_maintenance_id) references maintenance_record(id);
