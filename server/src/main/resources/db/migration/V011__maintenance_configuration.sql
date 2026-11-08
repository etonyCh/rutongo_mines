alter table service_type add column version bigint not null default 0;
alter table service_interval add column version bigint not null default 0;

create unique index service_interval_equipment_uq on service_interval(service_type_id, equipment_id) where equipment_id is not null;
create unique index service_interval_type_uq on service_interval(service_type_id, equipment_type_id) where equipment_id is null and equipment_type_id is not null;
create unique index service_interval_global_uq on service_interval(service_type_id) where equipment_id is null and equipment_type_id is null;
