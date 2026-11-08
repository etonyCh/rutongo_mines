create table daily_report (
  id uuid primary key default gen_random_uuid(),
  report_ref text not null unique,
  site_id uuid not null references site(id),
  report_date date not null,
  period text not null check (period in ('DAY','RED','NIGHT','FULL_DAY')),
  prepared_by uuid not null references app_user(id),
  created_at timestamptz not null default now(),
  unique (site_id, report_date, period)
);
create index daily_report_site_date_idx on daily_report(site_id, report_date desc);

create table report_version (
  id uuid primary key default gen_random_uuid(),
  report_id uuid not null references daily_report(id),
  version_no integer not null check (version_no > 0),
  state text not null check (state in ('DRAFT','SUBMITTED','RETURNED_FOR_CORRECTION','RESUBMITTED','APPROVED','SUPERSEDED')),
  narrative jsonb not null default '{}',
  snapshot jsonb not null,
  data_cutoff_at timestamptz not null,
  pdf_storage_key text,
  pdf_sha256 text,
  submitted_at timestamptz,
  approved_by uuid references app_user(id),
  approved_at timestamptz,
  approval_comment text,
  returned_by uuid references app_user(id),
  returned_at timestamptz,
  correction_reason text,
  created_by uuid not null references app_user(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  unique (report_id, version_no)
);
create index report_version_report_idx on report_version(report_id, version_no desc);
create index report_version_state_idx on report_version(state, updated_at desc);

create table report_event (
  id uuid primary key default gen_random_uuid(),
  report_id uuid not null references daily_report(id),
  version_id uuid not null references report_version(id),
  action text not null,
  actor_id uuid not null references app_user(id),
  occurred_at timestamptz not null default now(),
  metadata jsonb not null default '{}'
);
create index report_event_report_idx on report_event(report_id, occurred_at);
create index report_event_version_idx on report_event(version_id, occurred_at);
create trigger report_event_immutable before update or delete on report_event
  for each row execute function forbid_update_delete();

create or replace function enforce_report_version_immutability() returns trigger
language plpgsql as $$
begin
  if old.state = 'SUPERSEDED' then
    raise exception 'superseded report versions are immutable' using errcode = '55000';
  end if;
  if old.state = 'APPROVED' then
    if new.state <> 'SUPERSEDED' then
      raise exception 'approved report versions are immutable' using errcode = '55000';
    end if;
    if new.narrative is distinct from old.narrative
      or new.snapshot is distinct from old.snapshot
      or new.data_cutoff_at is distinct from old.data_cutoff_at
      or new.pdf_storage_key is distinct from old.pdf_storage_key
      or new.pdf_sha256 is distinct from old.pdf_sha256
      or new.submitted_at is distinct from old.submitted_at
      or new.approved_by is distinct from old.approved_by
      or new.approved_at is distinct from old.approved_at
      or new.approval_comment is distinct from old.approval_comment then
      raise exception 'only the state may change when superseding a report version' using errcode = '55000';
    end if;
  end if;
  if old.submitted_at is not null and old.state in ('SUBMITTED','RESUBMITTED') then
    if new.state = 'RETURNED_FOR_CORRECTION' then
      if new.narrative is distinct from old.narrative
        or new.snapshot is distinct from old.snapshot
        or new.data_cutoff_at is distinct from old.data_cutoff_at
        or new.pdf_storage_key is distinct from old.pdf_storage_key
        or new.pdf_sha256 is distinct from old.pdf_sha256
        or new.submitted_at is distinct from old.submitted_at then
        raise exception 'submitted report content is immutable' using errcode = '55000';
      end if;
    elsif new.state = 'APPROVED' then
      if new.narrative is distinct from old.narrative
        or new.snapshot is distinct from old.snapshot
        or new.data_cutoff_at is distinct from old.data_cutoff_at
        or new.submitted_at is distinct from old.submitted_at then
        raise exception 'submitted report content is immutable' using errcode = '55000';
      end if;
    elsif new.narrative is distinct from old.narrative
      or new.snapshot is distinct from old.snapshot
      or new.data_cutoff_at is distinct from old.data_cutoff_at
      or new.pdf_storage_key is distinct from old.pdf_storage_key
      or new.pdf_sha256 is distinct from old.pdf_sha256
      or new.submitted_at is distinct from old.submitted_at then
      raise exception 'submitted report content is immutable' using errcode = '55000';
    end if;
  end if;
  return new;
end;
$$;
create trigger report_version_immutable before update on report_version
  for each row execute function enforce_report_version_immutability();
