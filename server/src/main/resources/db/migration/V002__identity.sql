create extension if not exists citext;

create table app_user (
  id uuid primary key default gen_random_uuid(),
  username citext not null unique,
  full_name text not null,
  employee_id text not null unique,
  role text not null check (role in ('MECHANIC','SUPERVISOR','MANAGER','GENERAL_MANAGER','ADMINISTRATOR')),
  phone text,
  home_site_id uuid references site(id),
  tunnel_id uuid,
  shift_id uuid,
  technician_id uuid,
  password_hash text not null,
  must_change_password boolean not null default true,
  failed_attempts integer not null default 0,
  locked_until timestamptz,
  active boolean not null default true,
  last_login_at timestamptz,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  version bigint not null default 0
);

create table user_site_access (
  user_id uuid not null references app_user(id),
  site_id uuid not null references site(id),
  primary key (user_id, site_id)
);

create table refresh_token (
  id uuid primary key,
  user_id uuid not null references app_user(id),
  token_hash bytea not null unique,
  family_id uuid not null,
  issued_at timestamptz not null,
  expires_at timestamptz not null,
  used_at timestamptz,
  revoked_at timestamptz,
  device_label text,
  client_type text not null check (client_type in ('WEB','ANDROID'))
);
create index refresh_token_user_idx on refresh_token(user_id);

create table login_rate_bucket (
  bucket_key text primary key,
  window_started_at timestamptz not null,
  attempts integer not null default 0
);
