create table checklist_item (
  id uuid primary key default gen_random_uuid(),
  category text not null check (category in ('SAFETY','MECHANICAL')),
  code text not null unique,
  label text not null,
  sort_order integer not null,
  mandatory boolean not null default true,
  safety_critical boolean not null default false,
  photo_required_on_fail boolean not null default false,
  na_reason_required boolean not null default true,
  applies_to_type_ids uuid[] not null default '{}',
  active boolean not null default true
);

insert into checklist_item(category,code,label,sort_order,safety_critical,photo_required_on_fail) values
  ('SAFETY','SEAT_BELT','Seat Belt',10,true,true),
  ('SAFETY','HORN','Horn',20,true,true),
  ('SAFETY','LIGHTS','Lights',30,true,true),
  ('SAFETY','REVERSE_ALARM','Reverse Alarm',40,true,true),
  ('SAFETY','BRAKING_SYSTEM','Braking System',50,true,true),
  ('SAFETY','FIRE_EXTINGUISHER','Fire Extinguisher',60,true,true),
  ('SAFETY','GENERAL_SAFETY_DEVICES','General Safety Devices',70,true,true),
  ('MECHANICAL','ENGINE_OIL','Engine Oil',110,false,false),
  ('MECHANICAL','HYDRAULIC_OIL','Hydraulic Oil',120,false,false),
  ('MECHANICAL','COOLANT','Coolant',130,false,false),
  ('MECHANICAL','FUEL_LEAKS','Fuel Leaks',140,false,false),
  ('MECHANICAL','HYDRAULIC_LEAKS','Hydraulic Leaks',150,false,false),
  ('MECHANICAL','TYRES_TRACKS','Tyres / Tracks',160,false,false),
  ('MECHANICAL','STEERING','Steering',170,false,false),
  ('MECHANICAL','COOLING_SYSTEM','Cooling System',180,false,false)
on conflict (code) do nothing;

create table inspection_expectation (
  id uuid primary key default gen_random_uuid(),
  equipment_id uuid not null references equipment(id),
  site_id uuid not null references site(id),
  shift_id uuid not null references shift(id),
  report_date date not null,
  window_start timestamptz not null,
  window_end timestamptz not null,
  state text not null default 'NOT_STARTED' check (state in
    ('NOT_STARTED','IN_PROGRESS','COMPLETED','COMPLETED_WITH_DEFECTS','CRITICAL_FAILURE','MISSING')),
  inspection_id uuid,
  missing_marked_at timestamptz,
  unique (equipment_id, shift_id, report_date),
  check (window_end >= window_start)
);
create index inspection_expectation_window_idx on inspection_expectation(state,window_end);
create index inspection_expectation_site_date_idx on inspection_expectation(site_id,report_date,shift_id);

create table inspection_draft (
  id uuid primary key default gen_random_uuid(),
  expectation_id uuid references inspection_expectation(id),
  equipment_id uuid not null references equipment(id),
  user_id uuid not null references app_user(id),
  payload jsonb not null,
  updated_at timestamptz not null default now(),
  unique nulls not distinct (equipment_id,user_id,expectation_id)
);

create table inspection (
  id uuid primary key default gen_random_uuid(),
  client_uuid uuid not null unique,
  expectation_id uuid references inspection_expectation(id),
  equipment_id uuid not null references equipment(id),
  site_id uuid not null references site(id),
  tunnel_id uuid references tunnel(id),
  shift_id uuid not null references shift(id),
  report_date date not null,
  operator_id uuid references operator(id),
  operator_eid text,
  inspector_id uuid not null references app_user(id),
  inspector_eid text not null,
  supervisor_id uuid references app_user(id),
  engine_hours_start numeric(10,1),
  engine_hours_end numeric(10,1),
  fuel_start_percent numeric(5,2),
  fuel_added_litres numeric(8,2),
  result text not null check (result in ('COMPLETED','COMPLETED_WITH_DEFECTS','CRITICAL_FAILURE')),
  submitted_late boolean not null default false,
  hours_warnings text[] not null default '{}',
  captured_at timestamptz not null,
  started_at timestamptz not null,
  submitted_at timestamptz not null default now(),
  attestation jsonb not null
);
create index inspection_equipment_submitted_idx on inspection(equipment_id,submitted_at desc);
create index inspection_site_report_date_idx on inspection(site_id,report_date);
create trigger inspection_immutable before update or delete on inspection
  for each row execute function forbid_update_delete();

create table inspection_answer (
  inspection_id uuid not null references inspection(id),
  checklist_item_id uuid not null references checklist_item(id),
  category text not null check (category in ('SAFETY','MECHANICAL')),
  label_snapshot text not null,
  answer text not null check (answer in ('PASS','FAIL','NA')),
  na_reason text,
  defect_id uuid,
  primary key (inspection_id,checklist_item_id)
);
create trigger inspection_answer_immutable before update or delete on inspection_answer
  for each row execute function forbid_update_delete();

create table defect (
  id uuid primary key default gen_random_uuid(),
  equipment_id uuid not null references equipment(id),
  inspection_id uuid references inspection(id),
  checklist_item_id uuid references checklist_item(id),
  category text not null,
  description text not null,
  severity text not null check (severity in ('MINOR','MAJOR','CRITICAL')),
  immediate_action text,
  state text not null default 'OPEN' check (state in ('OPEN','IN_REPAIR','RESOLVED','CLOSED')),
  breakdown_id uuid,
  reported_by uuid not null references app_user(id),
  reported_at timestamptz not null,
  resolved_at timestamptz,
  resolved_by uuid references app_user(id),
  version bigint not null default 0
);
create index defect_equipment_state_idx on defect(equipment_id,state);
create index defect_inspection_idx on defect(inspection_id);

create table inspection_correction (
  id uuid primary key default gen_random_uuid(),
  original_inspection_id uuid not null references inspection(id),
  changes jsonb not null,
  reason text not null,
  requested_by uuid not null references app_user(id),
  requested_at timestamptz not null,
  decision text check (decision in ('APPROVED','REJECTED')),
  decided_by uuid references app_user(id),
  decided_at timestamptz,
  decision_comment text
);
create index inspection_correction_inspection_idx on inspection_correction(original_inspection_id,requested_at desc);

create table engine_hour_reading (
  id uuid primary key default gen_random_uuid(),
  equipment_id uuid not null references equipment(id),
  reading numeric(10,1) not null check (reading >= 0),
  read_at timestamptz not null,
  source text not null check (source in ('INSPECTION_START','INSPECTION_END','METER_RECORD','MAINTENANCE')),
  source_id uuid not null,
  quality text not null check (quality in ('VALID','SUSPICIOUS','REJECTED')),
  corrects_id uuid references engine_hour_reading(id)
);
create index engine_hour_reading_equipment_read_at_idx on engine_hour_reading(equipment_id,read_at desc);
create trigger engine_hour_reading_immutable before update or delete on engine_hour_reading
  for each row execute function forbid_update_delete();

create table working_hours_entry (
  inspection_id uuid primary key references inspection(id),
  equipment_id uuid not null references equipment(id),
  site_id uuid not null references site(id),
  shift_id uuid not null references shift(id),
  report_date date not null,
  hours_start numeric(10,1),
  hours_end numeric(10,1),
  recorded_hours numeric(8,1),
  status text not null check (status in ('VALID','MISSING_READING','SUSPICIOUS','OVERLAP','CORRECTED'))
);
create index working_hours_entry_equipment_date_idx on working_hours_entry(equipment_id,report_date);
