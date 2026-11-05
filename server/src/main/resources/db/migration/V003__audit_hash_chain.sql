create or replace function chain_audit_event() returns trigger
language plpgsql as $$
declare
  prior_hash bytea;
  canonical text;
begin
  perform pg_advisory_xact_lock(72419832);
  select event_hash into prior_hash from audit_event order by audit_sequence desc limit 1;
  new.previous_hash := prior_hash;
  canonical := coalesce(encode(prior_hash, 'hex'), '') || '|' || coalesce(new.actor_id::text, '') || '|' ||
    new.action || '|' || new.entity_type || '|' || new.entity_id || '|' || new.occurred_at::text || '|' || new.metadata::text;
  new.event_hash := digest(canonical, 'sha256');
  return new;
end;
$$;

alter table audit_event add column audit_sequence bigserial unique;

create trigger audit_event_hash_chain before insert on audit_event
  for each row execute function chain_audit_event();
