-- Phase 3: collision-free card numbers, canonical UUID sync keys and optimistic versions.

alter table public.clinics add column if not exists next_card_no bigint not null default 1001;
update public.clinics c set next_card_no=greatest(c.next_card_no,
  coalesce((select max(p.card_no)::bigint+1 from public.patients p where p.clinic_id=c.id),1001));

create table if not exists private.clinic_card_leases(
  id uuid primary key default extensions.gen_random_uuid(),
  clinic_id uuid not null references public.clinics(id) on delete cascade,
  device_id text not null,
  range_start bigint not null,
  range_end bigint not null,
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now(),
  constraint clinic_card_lease_range_valid check(range_start>0 and range_end>=range_start),
  unique(clinic_id,device_id,range_start)
);
create index if not exists idx_card_leases_lookup
  on private.clinic_card_leases(clinic_id,device_id,range_start,range_end);

create or replace function private.reserve_patient_card_numbers_impl(
  p_clinic_id uuid,p_device_id text,p_count integer default 100
) returns table(range_start bigint,range_end bigint)
language plpgsql security definer set search_path=''
as $$
declare v_start bigint; v_end bigint;
begin
  if (select auth.uid()) is null or not private.has_clinic_permission(p_clinic_id,'edit_patients') then
    raise exception 'not_allowed' using errcode='42501';
  end if;
  if char_length(trim(coalesce(p_device_id,'')))<16 then raise exception 'invalid_device_id' using errcode='22023'; end if;
  if p_count<20 or p_count>500 then raise exception 'invalid_card_range_size' using errcode='22023'; end if;
  update public.clinics set next_card_no=next_card_no+p_count where id=p_clinic_id
    returning next_card_no-p_count,next_card_no-1 into v_start,v_end;
  if v_start is null then raise exception 'clinic_not_found' using errcode='P0002'; end if;
  insert into private.clinic_card_leases(clinic_id,device_id,range_start,range_end,created_by)
    values(p_clinic_id,trim(p_device_id),v_start,v_end,(select auth.uid()));
  return query select v_start,v_end;
end; $$;
revoke all on function private.reserve_patient_card_numbers_impl(uuid,text,integer) from public,anon;
grant execute on function private.reserve_patient_card_numbers_impl(uuid,text,integer) to authenticated;

create or replace function public.reserve_patient_card_numbers(
  p_clinic_id uuid,p_device_id text,p_count integer default 100
) returns table(range_start bigint,range_end bigint)
language sql set search_path=''
as $$ select * from private.reserve_patient_card_numbers_impl(p_clinic_id,p_device_id,p_count); $$;
revoke all on function public.reserve_patient_card_numbers(uuid,text,integer) from public,anon;
grant execute on function public.reserve_patient_card_numbers(uuid,text,integer) to authenticated,service_role;

create or replace function private.assign_patient_card_number()
returns trigger language plpgsql security definer set search_path=''
as $$
declare v_allowed boolean;
begin
  if (select auth.uid()) is null or not private.has_clinic_permission(new.clinic_id,'edit_patients') then
    raise exception 'not_allowed' using errcode='42501';
  end if;
  select exists(select 1 from private.clinic_card_leases l where l.clinic_id=new.clinic_id
    and l.device_id=new.source_device_id and new.card_no between l.range_start and l.range_end) into v_allowed;
  if new.card_no<=0 or not v_allowed then
    update public.clinics set next_card_no=next_card_no+1 where id=new.clinic_id
      returning next_card_no-1 into new.card_no;
  end if;
  if new.card_no is null then raise exception 'clinic_not_found' using errcode='P0002'; end if;
  return new;
end; $$;
revoke all on function private.assign_patient_card_number() from public,anon,authenticated;
drop trigger if exists patient_card_number_assignment on public.patients;
create trigger patient_card_number_assignment before insert on public.patients
  for each row execute function private.assign_patient_card_number();

do $$ declare v_table text; begin
  foreach v_table in array array['patients','visits','payments','day_closures'] loop
    execute format('alter table public.%I add column if not exists record_version bigint not null default 1',v_table);
    execute format('update public.%I set sync_key=lower(substr(sync_key,1,8)||''-''||substr(sync_key,9,4)||''-''||substr(sync_key,13,4)||''-''||substr(sync_key,17,4)||''-''||substr(sync_key,21,12)) where sync_key~''^[0-9A-Fa-f]{32}$''',v_table);
    execute format('create index if not exists %I on public.%I(clinic_id,updated_at,sync_key)','idx_'||v_table||'_sync_cursor',v_table);
  end loop;
end $$;

create or replace function private.guard_sync_record_version()
returns trigger language plpgsql set search_path=''
as $$
declare v_old_payload jsonb; v_new_payload jsonb;
begin
  if tg_op='INSERT' then new.record_version:=1; return new; end if;
  v_old_payload:=to_jsonb(old)-'updated_at'-'record_version';
  v_new_payload:=to_jsonb(new)-'updated_at'-'record_version';
  if v_new_payload is distinct from v_old_payload then
    if new.record_version is distinct from old.record_version+1 then
      raise exception 'sync_version_conflict' using errcode='40001';
    end if;
  elsif new.record_version is distinct from old.record_version then
    raise exception 'invalid_record_version' using errcode='22023';
  end if;
  return new;
end; $$;
revoke all on function private.guard_sync_record_version() from public,anon,authenticated;

do $$ declare v_table text; begin
  foreach v_table in array array['patients','visits','payments','day_closures'] loop
    execute format('drop trigger if exists %I on public.%I','zz_'||v_table||'_sync_version',v_table);
    execute format('create trigger %I before insert or update on public.%I for each row execute function private.guard_sync_record_version()','zz_'||v_table||'_sync_version',v_table);
  end loop;
end $$;

create or replace function private.guard_patient_update_columns()
returns trigger language plpgsql set search_path=''
as $$
declare v_identity_changed boolean; v_sync_changed boolean;
begin
  if new.id is distinct from old.id or new.clinic_id is distinct from old.clinic_id
    or new.sync_key is distinct from old.sync_key or new.created_at is distinct from old.created_at
    or new.card_no is distinct from old.card_no then raise exception 'patient_identity_is_immutable' using errcode='42501'; end if;
  if new.age_text is distinct from old.age_text or new.allergies is distinct from old.allergies
    or new.chronic_conditions is distinct from old.chronic_conditions
    or new.current_medications is distinct from old.current_medications then
    raise exception 'medical_fields_require_secure_profile' using errcode='42501'; end if;
  v_identity_changed:=new.full_name is distinct from old.full_name or new.phone is distinct from old.phone or new.gender is distinct from old.gender;
  v_sync_changed:=new.source_device_id is distinct from old.source_device_id
    or new.client_change_id is distinct from old.client_change_id or new.record_version is distinct from old.record_version;
  if v_identity_changed and not private.has_clinic_permission(old.clinic_id,'edit_patients') then
    raise exception 'patient_update_not_allowed' using errcode='42501'; end if;
  if v_sync_changed and not private.has_clinic_permission(old.clinic_id,'edit_patients')
    and not private.has_clinic_permission(old.clinic_id,'edit_clinical') then
    raise exception 'patient_sync_update_not_allowed' using errcode='42501'; end if;
  return new;
end; $$;

drop policy if exists patients_update on public.patients;
create policy patients_update on public.patients for update to authenticated
using(private.has_clinic_permission(clinic_id,'edit_patients') or private.has_clinic_permission(clinic_id,'edit_clinical'))
with check(private.has_clinic_permission(clinic_id,'edit_patients') or private.has_clinic_permission(clinic_id,'edit_clinical'));

create or replace function private.update_patient_medical_summary_impl(
  p_clinic_id uuid,p_sync_key text,p_age_text text,p_allergies text,
  p_chronic_conditions text,p_current_medications text
) returns boolean language plpgsql security definer set search_path=''
as $$
declare v_patient_id uuid;
begin
  if (select auth.uid()) is null or not private.has_clinic_permission(p_clinic_id,'edit_clinical') then return false; end if;
  select id into v_patient_id from public.patients where clinic_id=p_clinic_id and sync_key=p_sync_key;
  if v_patient_id is null then return false; end if;
  insert into public.patient_medical_profiles(patient_id,clinic_id,age_text,allergies,chronic_conditions,current_medications,updated_by)
  values(v_patient_id,p_clinic_id,coalesce(p_age_text,''),coalesce(p_allergies,''),coalesce(p_chronic_conditions,''),coalesce(p_current_medications,''),(select auth.uid()))
  on conflict(patient_id) do update set age_text=excluded.age_text,allergies=excluded.allergies,
    chronic_conditions=excluded.chronic_conditions,current_medications=excluded.current_medications,
    updated_by=(select auth.uid()),updated_at=now();
  update public.patients set updated_at=now() where id=v_patient_id;
  return true;
end; $$;

comment on column public.patients.record_version is 'Optimistic concurrency version used by offline clients.';
comment on column public.visits.record_version is 'Optimistic concurrency version used by offline clients.';
comment on column public.payments.record_version is 'Optimistic concurrency version used by offline clients.';
comment on column public.day_closures.record_version is 'Optimistic concurrency version used by offline clients.';
