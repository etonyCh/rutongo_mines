create table tunnel (
  id uuid primary key default gen_random_uuid(),
  site_id uuid not null references site(id),
  code text not null,
  name text not null,
  state text not null check (state in ('ACTIVE','INACTIVE','DISABLED','RETIRED')),
  created_at timestamptz not null default now(),
  version bigint not null default 0,
  unique (site_id,code)
);

create table shift (
  id uuid primary key default gen_random_uuid(),
  code text not null unique check (code in ('DAY','RED','NIGHT')),
  name text not null,
  version bigint not null default 0
);
insert into shift(code,name) values ('DAY','Day'),('RED','Red'),('NIGHT','Night');

create table shift_schedule (
  id uuid primary key default gen_random_uuid(),
  shift_id uuid not null references shift(id),
  site_id uuid references site(id),
  start_local time not null,
  end_local time not null,
  inspection_window_minutes integer not null check (inspection_window_minutes between 1 and 1440),
  hard_backstop_hours integer not null default 24 check (hard_backstop_hours between 1 and 168),
  effective_from date not null,
  effective_to date,
  check (effective_to is null or effective_to >= effective_from)
);

create table equipment_type (
  id uuid primary key default gen_random_uuid(),
  name text not null unique,
  source text not null default 'MANUAL' check (source in ('MANUAL','IMPORT')),
  active boolean not null default true
);
create unique index equipment_type_name_ci_idx on equipment_type(lower(name));

create table technician (
  id uuid primary key default gen_random_uuid(),
  eid text not null unique,
  name text not null,
  function text,
  phone text,
  site_id uuid references site(id),
  tunnel_id uuid references tunnel(id),
  shift_id uuid references shift(id),
  active boolean not null default true,
  extra jsonb not null default '{}',
  import_row_hash text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version bigint not null default 0
);

create table operator (
  id uuid primary key default gen_random_uuid(),
  employee_id text not null unique,
  name text not null,
  site_id uuid references site(id),
  tunnel_id uuid references tunnel(id),
  shift_id uuid references shift(id),
  active boolean not null default true,
  extra jsonb not null default '{}',
  import_row_hash text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version bigint not null default 0
);

create table equipment (
  id uuid primary key default gen_random_uuid(),
  asset_id text not null unique,
  equipment_code text not null,
  name text not null,
  description text,
  type_id uuid not null references equipment_type(id),
  model text,
  serial_number text,
  manufacture_year integer check (manufacture_year between 1900 and 2200),
  site_id uuid not null references site(id),
  tunnel_id uuid references tunnel(id),
  lifecycle_state text not null default 'ACTIVE' check (lifecycle_state in ('ACTIVE','INACTIVE','RETIRED')),
  requires_daily_inspection boolean not null default true,
  current_status text check (current_status in ('OPERATIONAL','UNDER_REPAIR','DOWN')),
  current_status_event_id uuid,
  current_problem text,
  current_shift_id uuid references shift(id),
  assigned_technician_id uuid references technician(id),
  assigned_operator_id uuid references operator(id),
  engine_hours_current numeric(10,1) check (engine_hours_current is null or engine_hours_current >= 0),
  last_inspection_id uuid,
  last_maintenance_id uuid,
  last_status_update_at timestamptz,
  last_status_reported_by uuid references app_user(id),
  remarks text,
  extra jsonb not null default '{}',
  import_row_hash text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version bigint not null default 0
);
create index equipment_site_lifecycle_idx on equipment(site_id,lifecycle_state);
create index equipment_status_active_idx on equipment(current_status) where lifecycle_state='ACTIVE';

create table equipment_movement (
  id uuid primary key default gen_random_uuid(),
  equipment_id uuid not null references equipment(id),
  from_site_id uuid references site(id),
  from_tunnel_id uuid references tunnel(id),
  to_site_id uuid not null references site(id),
  to_tunnel_id uuid references tunnel(id),
  moved_at timestamptz not null,
  user_id uuid not null references app_user(id),
  reason text not null
);
create trigger equipment_movement_immutable before update or delete on equipment_movement
  for each row execute function forbid_update_delete();

create table operator_equipment (
  id uuid primary key default gen_random_uuid(),
  operator_id uuid not null references operator(id),
  equipment_id uuid not null references equipment(id),
  assigned_from timestamptz not null,
  assigned_to timestamptz,
  assigned_by uuid not null references app_user(id),
  check (assigned_to is null or assigned_to >= assigned_from)
);
create unique index operator_equipment_current_idx on operator_equipment(operator_id,equipment_id) where assigned_to is null;

create table photo (
  id uuid primary key default gen_random_uuid(),
  storage_key text not null unique,
  sha256 text not null,
  mime text not null check (mime in ('image/jpeg','image/png')),
  size_bytes integer not null check (size_bytes between 1 and 10485760),
  uploader_id uuid not null references app_user(id),
  uploaded_at timestamptz not null default now(),
  site_id uuid not null references site(id),
  target_type text not null check (target_type in ('EQUIPMENT','INSPECTION_ANSWER','DEFECT','BREAKDOWN','MAINTENANCE','STATUS_EVENT')),
  target_id uuid not null,
  kind text not null default 'EVIDENCE' check (kind in ('EVIDENCE','BEFORE','AFTER'))
);
create index photo_site_lookup_idx on photo(site_id,target_type,target_id);

create table import_profile (
  id uuid primary key default gen_random_uuid(),
  name text not null unique,
  kind text not null check (kind in ('ASSET','TECHNICIAN','OPERATOR')),
  sheet_name text,
  column_mapping jsonb not null,
  active boolean not null default true,
  created_by uuid not null references app_user(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version bigint not null default 0
);

create table import_batch (
  id uuid primary key default gen_random_uuid(),
  profile_id uuid not null references import_profile(id),
  user_id uuid not null references app_user(id),
  file_name text not null,
  file_sha256 text not null,
  storage_key text not null,
  state text not null check (state in ('PREVIEWED','COMMITTED','FAILED')),
  apply_updates boolean not null default false,
  new_count integer not null default 0,
  updated_count integer not null default 0,
  unchanged_count integer not null default 0,
  rejected_count integer not null default 0,
  created_at timestamptz not null default now(),
  committed_at timestamptz
);
create index import_batch_profile_hash_idx on import_batch(profile_id,file_sha256);

create table import_row (
  id uuid primary key default gen_random_uuid(),
  batch_id uuid not null references import_batch(id),
  row_number integer not null,
  business_key text,
  row_hash text,
  state text not null check (state in ('NEW','UPDATED','UNCHANGED','REJECTED')),
  data jsonb not null default '{}',
  extra jsonb not null default '{}',
  warnings jsonb not null default '[]',
  rejection_reason text,
  unique(batch_id,row_number)
);
create index import_row_batch_state_idx on import_row(batch_id,state,row_number);
