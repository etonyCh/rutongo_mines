create table status_rule (
  id uuid primary key default gen_random_uuid(),
  code text not null unique,
  trigger_result text not null check (trigger_result in ('CRITICAL_FAILURE')),
  trigger_severity text not null check (trigger_severity in ('MINOR','MAJOR','CRITICAL')),
  safety_critical_only boolean not null default true,
  action text not null check (action in ('RECOMMEND_DOWN','AUTO_DOWN')),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

insert into status_rule(code,trigger_result,trigger_severity,safety_critical_only,action)
values ('CRITICAL_SAFETY_AUTO_DOWN','CRITICAL_FAILURE','CRITICAL',true,'AUTO_DOWN')
on conflict (code) do nothing;

create table inspection_answer_photo (
  inspection_id uuid not null references inspection(id),
  checklist_item_id uuid not null references checklist_item(id),
  photo_id uuid not null,
  created_at timestamptz not null default now(),
  primary key (inspection_id,checklist_item_id,photo_id)
);
create index inspection_answer_photo_photo_idx on inspection_answer_photo(photo_id);
