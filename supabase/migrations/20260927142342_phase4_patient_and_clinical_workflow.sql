alter table public.patients add column if not exists normalized_phone text not null default '';
update public.patients
set normalized_phone = case
  when regexp_replace(coalesce(phone,''),'[^0-9]','','g') like '0_________'
    then '249' || substr(regexp_replace(coalesce(phone,''),'[^0-9]','','g'),2)
  when regexp_replace(coalesce(phone,''),'[^0-9]','','g') like '00249%'
    then substr(regexp_replace(coalesce(phone,''),'[^0-9]','','g'),3)
  else regexp_replace(coalesce(phone,''),'[^0-9]','','g')
end;
create unique index if not exists uq_patients_clinic_phone
  on public.patients(clinic_id, normalized_phone) where normalized_phone <> '';

create or replace function private.normalize_patient_phone()
returns trigger language plpgsql set search_path=''
as $$
declare v_digits text;
begin
  v_digits := regexp_replace(coalesce(new.phone,''),'[^0-9]','','g');
  if v_digits like '00249%' then v_digits := substr(v_digits,3);
  elsif char_length(v_digits)=10 and v_digits like '0%' then v_digits := '249'||substr(v_digits,2);
  end if;
  new.normalized_phone := v_digits;
  return new;
end; $$;
revoke all on function private.normalize_patient_phone() from public,anon,authenticated;
drop trigger if exists aa_patient_normalize_phone on public.patients;
create trigger aa_patient_normalize_phone before insert or update of phone,normalized_phone
on public.patients for each row execute function private.normalize_patient_phone();

alter table public.visits add column if not exists assigned_doctor_user_id uuid references auth.users(id) on delete set null;
alter table public.visits add column if not exists assigned_doctor_name text not null default '';
alter table public.visits add column if not exists cancellation_reason text not null default '';
alter table public.visits add column if not exists cancelled_at timestamptz;
alter table public.visits add column if not exists reopened_at timestamptz;
alter table public.visits drop constraint if exists visits_status_check;
alter table public.visits add constraint visits_status_check
  check(status in ('REGISTERED','WAITING','IN_CONSULT','COMPLETED','CANCELLED'));
create index if not exists idx_visits_assigned_doctor
  on public.visits(clinic_id,assigned_doctor_user_id,status);

alter table public.clinical_records add column if not exists temperature text not null default '';
alter table public.clinical_records add column if not exists blood_pressure text not null default '';
alter table public.clinical_records add column if not exists pulse text not null default '';
alter table public.clinical_records add column if not exists weight text not null default '';
alter table public.clinical_records add column if not exists oxygen text not null default '';
alter table public.clinical_records add column if not exists medications_text text not null default '';

create or replace function private.guard_phase4_visit_fields()
returns trigger language plpgsql set search_path=''
as $$
begin
  if tg_op='UPDATE' and (
    new.assigned_doctor_user_id is distinct from old.assigned_doctor_user_id
    or new.assigned_doctor_name is distinct from old.assigned_doctor_name
    or new.cancellation_reason is distinct from old.cancellation_reason
    or new.cancelled_at is distinct from old.cancelled_at
    or new.reopened_at is distinct from old.reopened_at
  ) and not (
    private.has_clinic_permission(old.clinic_id,'manage_queue')
    or private.has_clinic_permission(old.clinic_id,'edit_clinical')
  ) then raise exception 'visit_assignment_not_allowed' using errcode='42501';
  end if;
  if new.assigned_doctor_user_id is not null and not exists(
    select 1 from public.clinic_members m
    where m.clinic_id=new.clinic_id and m.user_id=new.assigned_doctor_user_id
      and m.active and m.role in ('owner_doctor','substitute_doctor')
  ) then raise exception 'assigned_doctor_not_active' using errcode='23514';
  end if;
  if new.status='CANCELLED' and char_length(trim(coalesce(new.cancellation_reason,'')))<3 then
    raise exception 'cancellation_reason_required' using errcode='23514';
  end if;
  return new;
end; $$;
revoke all on function private.guard_phase4_visit_fields() from public,anon,authenticated;
drop trigger if exists phase4_visit_fields_guard on public.visits;
create trigger phase4_visit_fields_guard before insert or update on public.visits
for each row execute function private.guard_phase4_visit_fields();
