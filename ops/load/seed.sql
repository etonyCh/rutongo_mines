-- Disposable load-test fixtures for a freshly migrated mmems_load database.
-- The password for every load-supervisor-NNN account is LoadTestPassword1.
begin;

insert into app_user (username, full_name, employee_id, role, password_hash, must_change_password, active)
select
  'load-supervisor-' || lpad(g::text, 3, '0'),
  'Load Supervisor ' || g,
  'LOAD-SUP-' || lpad(g::text, 3, '0'),
  'SUPERVISOR',
  '$argon2id$v=19$m=65536,t=3,p=1$L+eIAFiKB24VX+HShvvpqA$Sh7vLunbWLEsMBMlfl/8pWh9qlZTHJj7GJShPfj9nh8',
  false,
  true
from generate_series(1, 100) g
on conflict do nothing;

insert into user_site_access (user_id, site_id)
select u.id, s.id
from app_user u
cross join site s
where u.username like 'load-supervisor-%'
on conflict do nothing;

insert into equipment_type (name, source) values
  ('LOAD Drill Rig', 'MANUAL'),
  ('LOAD Loader', 'MANUAL'),
  ('LOAD Haul Truck', 'MANUAL'),
  ('LOAD Excavator', 'MANUAL'),
  ('LOAD Compressor', 'MANUAL'),
  ('LOAD Generator', 'MANUAL'),
  ('LOAD Service Vehicle', 'MANUAL'),
  ('LOAD Pump', 'MANUAL')
on conflict do nothing;

insert into technician (eid, name, function, site_id, active)
select
  'LOAD-TECH-' || lpad(g::text, 3, '0'),
  'Load Technician ' || g,
  'Mechanic',
  s.id,
  true
from generate_series(1, 120) g
join lateral (
  select id from site order by code offset ((g - 1) % 6) limit 1
) s on true
on conflict do nothing;

insert into service_type (code, name, hours_based) values
  ('LOAD_OIL', 'Load Engine Oil', true),
  ('LOAD_FILTER', 'Load Filter Service', true),
  ('LOAD_250H', 'Load 250 Hour Service', true),
  ('LOAD_500H', 'Load 500 Hour Service', true),
  ('LOAD_1000H', 'Load 1000 Hour Service', true),
  ('LOAD_INSPECTION', 'Load Inspection Service', false)
on conflict do nothing;

with site_rows as (
  select id, row_number() over (order by code) rn, count(*) over () cnt
  from site where state = 'ACTIVE'
), type_rows as (
  select id, row_number() over (order by name) rn, count(*) over () cnt
  from equipment_type where name like 'LOAD %'
)
insert into equipment (
  asset_id, equipment_code, name, type_id, site_id, lifecycle_state,
  requires_daily_inspection, current_status, engine_hours_current,
  created_at, updated_at
)
select
  'LOAD-' || lpad(g::text, 5, '0'),
  'LOAD-EQ-' || lpad(g::text, 5, '0'),
  'Load Equipment ' || g,
  t.id,
  s.id,
  'ACTIVE',
  true,
  case
    when g % 17 = 0 then 'DOWN'
    when g % 13 = 0 then 'UNDER_REPAIR'
    else 'OPERATIONAL'
  end,
  ((g * 37) % 6000)::numeric(10, 1),
  now() - ((g % 60) || ' days')::interval,
  now() - ((g % 30) || ' days')::interval
from generate_series(1, 1000) g
join site_rows s on s.rn = 1 + ((g - 1) % s.cnt)
join type_rows t on t.rn = 1 + ((g - 1) % t.cnt)
on conflict do nothing;

insert into equipment_status_event (
  equipment_id, seq, previous_status, new_status, site_id,
  user_id, user_eid, occurred_at, source, idempotency_key
)
select
  e.id, 1, null, e.current_status, e.site_id,
  u.id, u.employee_id, now() - interval '1 day', 'MANUAL',
  'LOAD-STATUS-' || e.asset_id
from equipment e
join app_user u on u.username = 'load-supervisor-001'
where e.asset_id like 'LOAD-%'
on conflict do nothing;

update equipment e
set current_status_event_id = ev.id
from equipment_status_event ev
where ev.equipment_id = e.id
  and ev.idempotency_key = 'LOAD-STATUS-' || e.asset_id
  and e.current_status_event_id is null;

insert into inspection_expectation (
  equipment_id, site_id, shift_id, report_date, window_start, window_end, state
)
select
  e.id,
  e.site_id,
  sh.id,
  d.day::date,
  (d.day::date + time '06:00') at time zone st.timezone,
  (d.day::date + time '14:00') at time zone st.timezone,
  case
    when (substring(e.asset_id from 6)::int + extract(day from d.day)::int) % 20 = 0 then 'MISSING'
    when (substring(e.asset_id from 6)::int + extract(day from d.day)::int) % 11 = 0 then 'CRITICAL_FAILURE'
    when (substring(e.asset_id from 6)::int + extract(day from d.day)::int) % 7 = 0 then 'COMPLETED_WITH_DEFECTS'
    when (substring(e.asset_id from 6)::int + extract(day from d.day)::int) % 5 = 0 then 'NOT_STARTED'
    else 'COMPLETED'
  end
from equipment e
join site st on st.id = e.site_id
cross join (select id from shift where code = 'DAY') sh
cross join generate_series(current_date - 6, current_date, interval '1 day') d(day)
where e.asset_id like 'LOAD-%'
on conflict do nothing;

with eq as (
  select id, site_id, row_number() over (order by asset_id) rn
  from equipment where asset_id like 'LOAD-%'
), actor as (
  select id from app_user where username = 'load-supervisor-001'
)
insert into breakdown (
  equipment_id, category, problem, site_id, reported_by, reported_at,
  breakdown_start, repair_start, repair_end, state, action_taken, remarks, idempotency_key
)
select
  eq.id,
  'MECHANICAL',
  'Load fixture breakdown ' || g,
  eq.site_id,
  actor.id,
  now() - ((g % 7) || ' days')::interval - interval '6 hours',
  now() - ((g % 7) || ' days')::interval - interval '6 hours',
  case when g % 4 <> 0 then now() - ((g % 7) || ' days')::interval - interval '5 hours' end,
  case when g % 4 <> 0 then now() - ((g % 7) || ' days')::interval - interval '2 hours' end,
  case when g % 4 <> 0 then 'RESOLVED' else 'UNDER_REPAIR' end,
  'Load fixture action',
  'Load fixture',
  'LOAD-BD-' || g
from generate_series(1, 180) g
join eq on eq.rn = 1 + ((g - 1) % 1000)
cross join actor
on conflict do nothing;

with eq as (
  select id, site_id, row_number() over (order by asset_id) rn
  from equipment where asset_id like 'LOAD-%'
), tech as (
  select id, row_number() over (order by eid) rn
  from technician where eid like 'LOAD-TECH-%'
), service as (
  select id from service_type where code = 'LOAD_OIL'
), actor as (
  select id from app_user where username = 'load-supervisor-001'
)
insert into maintenance_record (
  equipment_id, service_type_id, site_id, performed_at, engine_hours,
  work_performed, recorded_by, technician_id, verification_status,
  labour_cost, parts_cost, currency, idempotency_key
)
select
  eq.id,
  service.id,
  eq.site_id,
  now() - ((g % 7) || ' days')::interval - interval '2 hours',
  ((g * 37) % 6000)::numeric(10, 1),
  'Load fixture service',
  actor.id,
  tech.id,
  case when g % 3 = 0 then 'VERIFIED' else 'PENDING_VERIFICATION' end,
  1000,
  0,
  'RWF',
  'LOAD-MAINT-' || g
from generate_series(1, 180) g
join eq on eq.rn = 1 + ((g - 1) % 1000)
join tech on tech.rn = 1 + ((g - 1) % 120)
cross join service
cross join actor
on conflict do nothing;

insert into part (part_number, description, category, min_stock, unit_cost)
select
  'LOAD-PART-' || lpad(g::text, 3, '0'),
  'Load fixture part ' || g,
  'MECHANICAL',
  5,
  1000
from generate_series(1, 30) g
on conflict do nothing;

insert into stock_movement (part_id, location, qty, type, user_id, occurred_at, reason)
select
  p.id, 'MAIN', 20, 'RECEIPT', u.id, now() - interval '10 days', 'LOAD_FIXTURE'
from part p
join app_user u on u.username = 'load-supervisor-001'
where p.part_number like 'LOAD-PART-%'
  and not exists (
    select 1 from stock_movement sm
    where sm.part_id = p.id and sm.reason = 'LOAD_FIXTURE'
  );

commit;
