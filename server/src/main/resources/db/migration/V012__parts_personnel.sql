create table part (
  id uuid primary key default gen_random_uuid(),
  part_number text not null unique,
  description text not null,
  category text,
  unit text not null default 'UNIT',
  min_stock integer not null default 0 check (min_stock >= 0),
  unit_cost bigint check (unit_cost is null or unit_cost >= 0),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version bigint not null default 0
);
create index part_active_category_idx on part(active,category);

create table part_stock (
  id uuid primary key default gen_random_uuid(),
  part_id uuid not null references part(id),
  location text not null,
  on_hand integer not null default 0 check (on_hand >= 0),
  updated_at timestamptz not null default now(),
  version bigint not null default 0,
  unique (part_id,location)
);
create index part_stock_low_idx on part_stock(part_id,on_hand);

create table part_request (
  id uuid primary key default gen_random_uuid(),
  idempotency_key text not null unique,
  site_id uuid not null references site(id),
  equipment_id uuid references equipment(id),
  breakdown_id uuid references breakdown(id),
  maintenance_id uuid references maintenance_record(id),
  state text not null default 'REQUESTED' check (state in ('REQUESTED','APPROVED','REJECTED','ISSUED','USED','RETURNED')),
  requested_by uuid not null references app_user(id),
  requested_at timestamptz not null default now(),
  approved_by uuid references app_user(id),
  approved_at timestamptz,
  decision_comment text,
  issued_by uuid references app_user(id),
  issued_at timestamptz,
  used_by uuid references app_user(id),
  used_at timestamptz,
  returned_by uuid references app_user(id),
  returned_at timestamptz,
  notes text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version bigint not null default 0
);
create index part_request_site_state_idx on part_request(site_id,state,requested_at desc);
create index part_request_requester_idx on part_request(requested_by,requested_at desc);

create table part_request_line (
  id uuid primary key default gen_random_uuid(),
  request_id uuid not null references part_request(id),
  part_id uuid not null references part(id),
  location text not null default 'MAIN',
  requested_qty integer not null check (requested_qty > 0),
  issued_qty integer not null default 0 check (issued_qty >= 0 and issued_qty <= requested_qty),
  used_qty integer not null default 0 check (used_qty >= 0 and used_qty <= issued_qty),
  returned_qty integer not null default 0 check (returned_qty >= 0 and returned_qty <= issued_qty - used_qty),
  unit_cost bigint check (unit_cost is null or unit_cost >= 0),
  notes text,
  unique (request_id,part_id,location)
);
create index part_request_line_request_idx on part_request_line(request_id);
create index part_request_line_part_idx on part_request_line(part_id);

create table stock_movement (
  id uuid primary key default gen_random_uuid(),
  part_id uuid not null references part(id),
  location text not null,
  qty integer not null check (qty <> 0),
  type text not null check (type in ('RECEIPT','ISSUE','RETURN','ADJUSTMENT')),
  request_id uuid references part_request(id),
  request_line_id uuid references part_request_line(id),
  user_id uuid not null references app_user(id),
  occurred_at timestamptz not null default now(),
  reason text,
  check (
    (type in ('RECEIPT','RETURN') and qty > 0)
    or (type = 'ISSUE' and qty < 0)
    or (type = 'ADJUSTMENT')
  )
);
create index stock_movement_part_idx on stock_movement(part_id,occurred_at desc);
create index stock_movement_request_idx on stock_movement(request_id) where request_id is not null;
create trigger stock_movement_immutable before update or delete on stock_movement
  for each row execute function forbid_update_delete();

create or replace function apply_stock_movement() returns trigger
language plpgsql as $$
declare
  new_on_hand integer;
begin
  insert into part_stock(part_id,location,on_hand)
  values (new.part_id,new.location,0)
  on conflict (part_id,location) do nothing;
  update part_stock
    set on_hand = part_stock.on_hand + new.qty,
        updated_at = now(),
        version = part_stock.version + 1
  where part_id = new.part_id and location = new.location
  returning on_hand into new_on_hand;
  if new_on_hand < 0 then
    raise exception 'stock cannot become negative for part % at %', new.part_id, new.location
      using errcode = '23514';
  end if;
  return new;
end;
$$;
create trigger stock_movement_applied after insert on stock_movement
  for each row execute function apply_stock_movement();

alter table operator_equipment add column reason text;

create table technician_equipment (
  id uuid primary key default gen_random_uuid(),
  technician_id uuid not null references technician(id),
  equipment_id uuid not null references equipment(id),
  assigned_from timestamptz not null,
  assigned_to timestamptz,
  assigned_by uuid not null references app_user(id),
  reason text,
  check (assigned_to is null or assigned_to >= assigned_from)
);
create unique index technician_equipment_current_idx on technician_equipment(technician_id,equipment_id) where assigned_to is null;
create index technician_equipment_open_idx on technician_equipment(equipment_id) where assigned_to is null;

create table personnel_transfer (
  id uuid primary key default gen_random_uuid(),
  person_type text not null check (person_type in ('TECHNICIAN','OPERATOR')),
  person_id uuid not null,
  from_site_id uuid references site(id),
  from_tunnel_id uuid references tunnel(id),
  from_shift_id uuid references shift(id),
  to_site_id uuid not null references site(id),
  to_tunnel_id uuid references tunnel(id),
  to_shift_id uuid references shift(id),
  transferred_at timestamptz not null,
  transferred_by uuid not null references app_user(id),
  reason text not null
);
create index personnel_transfer_person_idx on personnel_transfer(person_type,person_id,transferred_at desc);
create trigger personnel_transfer_immutable before update or delete on personnel_transfer
  for each row execute function forbid_update_delete();
