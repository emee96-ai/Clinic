-- Phase 2: server-enforced clinic accounts, roles, permissions and actor auditing.

create or replace function private.sanitize_clinic_permissions(p_role text, p_requested jsonb default '{}'::jsonb)
returns jsonb
language plpgsql
immutable
set search_path = ''
as $$
declare
  v_defaults jsonb := private.default_clinic_permissions(p_role);
  v_result jsonb := '{}'::jsonb;
  v_key text;
  v_allowed boolean;
  v_requested_text text;
begin
  if p_role not in ('owner_doctor', 'receptionist', 'substitute_doctor') then
    raise exception 'invalid_role' using errcode = '22023';
  end if;

  for v_key in select jsonb_object_keys(v_defaults) loop
    v_allowed := coalesce((v_defaults ->> v_key)::boolean, false);
    v_requested_text := coalesce(p_requested ->> v_key, v_allowed::text);
    if v_requested_text not in ('true', 'false') then
      raise exception 'invalid_permission_value' using errcode = '22023';
    end if;
    v_result := v_result || jsonb_build_object(v_key, v_allowed and v_requested_text::boolean);
  end loop;
  return v_result;
end;
$$;

create or replace function private.default_clinic_permissions(p_role text)
returns jsonb
language sql
immutable
set search_path = ''
as $$
  select case p_role
    when 'owner_doctor' then '{"view_patients":true,"edit_patients":true,"register_visits":true,"manage_queue":true,"view_clinical":true,"edit_clinical":true,"view_finance":true,"record_payments":true,"close_day":true,"manage_staff":true,"manage_invites":true}'::jsonb
    when 'receptionist' then '{"view_patients":true,"edit_patients":true,"register_visits":true,"manage_queue":true,"view_clinical":false,"edit_clinical":false,"view_finance":false,"record_payments":true,"close_day":false,"manage_staff":false,"manage_invites":false}'::jsonb
    when 'substitute_doctor' then '{"view_patients":true,"edit_patients":false,"register_visits":false,"manage_queue":true,"view_clinical":true,"edit_clinical":true,"view_finance":false,"record_payments":false,"close_day":false,"manage_staff":false,"manage_invites":false}'::jsonb
    else '{}'::jsonb
  end;
$$;

revoke all on function private.sanitize_clinic_permissions(text,jsonb) from public, anon, authenticated;
revoke all on function private.default_clinic_permissions(text) from public, anon, authenticated;

create unique index if not exists clinic_members_one_active_clinic_per_user
  on public.clinic_members(user_id) where active;
create unique index if not exists clinics_one_owner_account
  on public.clinics(owner_user_id);

create table if not exists public.patient_medical_profiles (
  patient_id uuid primary key references public.patients(id) on delete cascade,
  clinic_id uuid not null references public.clinics(id) on delete cascade,
  age_text text not null default '',
  allergies text not null default '',
  chronic_conditions text not null default '',
  current_medications text not null default '',
  updated_by uuid not null references auth.users(id),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

insert into public.patient_medical_profiles(
  patient_id, clinic_id, age_text, allergies, chronic_conditions, current_medications, updated_by
)
select p.id, p.clinic_id, p.age_text, p.allergies, p.chronic_conditions, p.current_medications,
       coalesce(c.owner_user_id, m.user_id)
from public.patients p
join public.clinics c on c.id = p.clinic_id
left join lateral (
  select cm.user_id from public.clinic_members cm
  where cm.clinic_id = p.clinic_id and cm.active
  order by (cm.role = 'owner_doctor') desc, cm.created_at
  limit 1
) m on true
where coalesce(c.owner_user_id, m.user_id) is not null
  and (p.age_text <> '' or p.allergies <> '' or p.chronic_conditions <> '' or p.current_medications <> '')
on conflict (patient_id) do update set
  age_text = excluded.age_text,
  allergies = excluded.allergies,
  chronic_conditions = excluded.chronic_conditions,
  current_medications = excluded.current_medications,
  updated_by = excluded.updated_by,
  updated_at = now();

update public.patients
set age_text = '', allergies = '', chronic_conditions = '', current_medications = ''
where age_text <> '' or allergies <> '' or chronic_conditions <> '' or current_medications <> '';

alter table public.patient_medical_profiles enable row level security;
drop policy if exists patient_medical_select on public.patient_medical_profiles;
create policy patient_medical_select on public.patient_medical_profiles for select to authenticated
using (private.has_clinic_permission(clinic_id, 'view_clinical'));

grant select on public.patient_medical_profiles to authenticated;
revoke insert, update, delete on public.patient_medical_profiles from anon, authenticated;
create index if not exists idx_patient_medical_clinic on public.patient_medical_profiles(clinic_id);
create trigger patient_medical_updated_at before update on public.patient_medical_profiles
for each row execute function public.set_updated_at();

create or replace function private.guard_patient_insert_columns()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if coalesce(new.age_text, '') <> '' or coalesce(new.allergies, '') <> ''
     or coalesce(new.chronic_conditions, '') <> '' or coalesce(new.current_medications, '') <> '' then
    raise exception 'medical_fields_require_secure_profile' using errcode = '42501';
  end if;
  return new;
end;
$$;

create or replace function private.guard_patient_update_columns()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.id is distinct from old.id or new.clinic_id is distinct from old.clinic_id
     or new.sync_key is distinct from old.sync_key or new.created_at is distinct from old.created_at then
    raise exception 'patient_identity_is_immutable' using errcode = '42501';
  end if;
  if new.age_text is distinct from old.age_text or new.allergies is distinct from old.allergies
     or new.chronic_conditions is distinct from old.chronic_conditions
     or new.current_medications is distinct from old.current_medications then
    raise exception 'medical_fields_require_secure_profile' using errcode = '42501';
  end if;
  if not private.has_clinic_permission(old.clinic_id, 'edit_patients') then
    raise exception 'patient_update_not_allowed' using errcode = '42501';
  end if;
  return new;
end;
$$;

drop trigger if exists patients_insert_column_guard on public.patients;
create trigger patients_insert_column_guard before insert on public.patients
for each row execute function private.guard_patient_insert_columns();
drop trigger if exists patients_column_guard on public.patients;
create trigger patients_column_guard before update on public.patients
for each row execute function private.guard_patient_update_columns();

drop policy if exists patients_update on public.patients;
create policy patients_update on public.patients for update to authenticated
using (private.has_clinic_permission(clinic_id, 'edit_patients'))
with check (private.has_clinic_permission(clinic_id, 'edit_patients'));

drop policy if exists visits_update on public.visits;
create policy visits_update on public.visits for update to authenticated
using (
  private.has_clinic_permission(clinic_id, 'register_visits')
  or private.has_clinic_permission(clinic_id, 'manage_queue')
  or private.has_clinic_permission(clinic_id, 'record_payments')
)
with check (
  private.has_clinic_permission(clinic_id, 'register_visits')
  or private.has_clinic_permission(clinic_id, 'manage_queue')
  or private.has_clinic_permission(clinic_id, 'record_payments')
);

create or replace function private.guard_visit_write()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  v_patient_clinic uuid;
begin
  select p.clinic_id into v_patient_clinic from public.patients p where p.id = new.patient_id;
  if v_patient_clinic is distinct from new.clinic_id then
    raise exception 'visit_patient_clinic_mismatch' using errcode = '23514';
  end if;
  if coalesce(new.complaint,'') <> '' or coalesce(new.exam,'') <> '' or coalesce(new.diagnosis,'') <> ''
     or coalesce(new.labs,'') <> '' or coalesce(new.treatment,'') <> '' or coalesce(new.followup,'') <> '' then
    raise exception 'clinical_fields_require_clinical_record' using errcode = '42501';
  end if;
  if tg_op = 'INSERT' then
    if new.paid_amount <> 0 then raise exception 'visit_paid_amount_must_start_zero' using errcode = '23514'; end if;
    return new;
  end if;
  if new.id is distinct from old.id or new.clinic_id is distinct from old.clinic_id
     or new.patient_id is distinct from old.patient_id or new.sync_key is distinct from old.sync_key
     or new.created_at is distinct from old.created_at then
    raise exception 'visit_identity_is_immutable' using errcode = '42501';
  end if;
  if (new.visit_type is distinct from old.visit_type or new.fee is distinct from old.fee)
     and not private.has_clinic_permission(old.clinic_id, 'register_visits') then
    raise exception 'visit_registration_fields_not_allowed' using errcode = '42501';
  end if;
  if (new.status is distinct from old.status or new.started_at is distinct from old.started_at
      or new.completed_at is distinct from old.completed_at)
     and not private.has_clinic_permission(old.clinic_id, 'manage_queue') then
    raise exception 'visit_queue_fields_not_allowed' using errcode = '42501';
  end if;
  if new.paid_amount is distinct from old.paid_amount
     and not private.has_clinic_permission(old.clinic_id, 'record_payments') then
    raise exception 'visit_payment_fields_not_allowed' using errcode = '42501';
  end if;
  return new;
end;
$$;

drop trigger if exists visits_write_guard on public.visits;
create trigger visits_write_guard before insert or update on public.visits
for each row execute function private.guard_visit_write();

create or replace function private.guard_clinical_record_write()
returns trigger
language plpgsql
set search_path = ''
as $$
declare v_visit_clinic uuid;
begin
  select v.clinic_id into v_visit_clinic from public.visits v where v.id = new.visit_id;
  if v_visit_clinic is distinct from new.clinic_id then
    raise exception 'clinical_visit_clinic_mismatch' using errcode = '23514';
  end if;
  if tg_op = 'UPDATE' and (new.id is distinct from old.id or new.clinic_id is distinct from old.clinic_id
     or new.visit_id is distinct from old.visit_id or new.created_at is distinct from old.created_at) then
    raise exception 'clinical_record_identity_is_immutable' using errcode = '42501';
  end if;
  new.updated_by := (select auth.uid());
  if new.updated_by is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
  return new;
end;
$$;

drop trigger if exists clinical_record_write_guard on public.clinical_records;
create trigger clinical_record_write_guard before insert or update on public.clinical_records
for each row execute function private.guard_clinical_record_write();
drop policy if exists clinical_records_update on public.clinical_records;
create policy clinical_records_update on public.clinical_records for update to authenticated
using (private.has_clinic_permission(clinic_id, 'edit_clinical'))
with check (private.has_clinic_permission(clinic_id, 'edit_clinical') and updated_by = (select auth.uid()));

create or replace function private.guard_payment_write()
returns trigger
language plpgsql
set search_path = ''
as $$
declare v_visit_clinic uuid;
begin
  select v.clinic_id into v_visit_clinic from public.visits v where v.id = new.visit_id;
  if v_visit_clinic is distinct from new.clinic_id then
    raise exception 'payment_visit_clinic_mismatch' using errcode = '23514';
  end if;
  if tg_op = 'UPDATE' and (new.id is distinct from old.id or new.clinic_id is distinct from old.clinic_id
     or new.visit_id is distinct from old.visit_id or new.sync_key is distinct from old.sync_key
     or new.created_at is distinct from old.created_at) then
    raise exception 'payment_identity_is_immutable' using errcode = '42501';
  end if;
  return new;
end;
$$;
drop trigger if exists payments_write_guard on public.payments;
create trigger payments_write_guard before insert or update on public.payments
for each row execute function private.guard_payment_write();

alter table public.audit_log add column if not exists actor_display_name text not null default '';
alter table public.audit_log add column if not exists actor_role text not null default '';

create or replace function private.audit_clinic_change()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_row jsonb := case when tg_op = 'DELETE' then to_jsonb(old) else to_jsonb(new) end;
  v_clinic_id uuid := nullif(v_row ->> 'clinic_id','')::uuid;
  v_actor uuid := (select auth.uid());
  v_name text := '';
  v_role text := '';
  v_entity_key text;
begin
  if v_actor is null or v_clinic_id is null then
    if tg_op = 'DELETE' then return old; end if;
    return new;
  end if;
  select coalesce(m.display_name,''), coalesce(m.role,'') into v_name, v_role
  from public.clinic_members m
  where m.clinic_id = v_clinic_id and m.user_id = v_actor
  order by m.active desc, m.created_at desc limit 1;
  v_entity_key := coalesce(v_row ->> 'sync_key', v_row ->> 'id', v_row ->> 'patient_id', v_row ->> 'visit_id');
  insert into public.audit_log(
    clinic_id, actor_user_id, actor_display_name, actor_role, device_id,
    action, entity_type, entity_sync_key, details
  ) values (
    v_clinic_id, v_actor, coalesce(v_name,''), coalesce(v_role,''), coalesce(v_row ->> 'source_device_id',''),
    upper(tg_op) || '_' || upper(tg_table_name), tg_table_name, v_entity_key,
    jsonb_build_object('operation', tg_op, 'record_id', v_row ->> 'id')
  );
  if tg_op = 'DELETE' then return old; end if;
  return new;
end;
$$;
revoke all on function private.audit_clinic_change() from public, anon, authenticated;

do $$
declare v_table text;
begin
  foreach v_table in array array['patients','visits','payments','day_closures','clinical_records','clinic_members','clinic_invites','patient_medical_profiles']
  loop
    execute format('drop trigger if exists %I on public.%I', 'audit_' || v_table, v_table);
    execute format('create trigger %I after insert or update or delete on public.%I for each row execute function private.audit_clinic_change()', 'audit_' || v_table, v_table);
  end loop;
end;
$$;

drop policy if exists audit_insert on public.audit_log;
drop policy if exists audit_select on public.audit_log;
create policy audit_select on public.audit_log for select to authenticated
using (private.has_clinic_permission(clinic_id, 'manage_staff'));
revoke insert, update, delete on public.audit_log from anon, authenticated;

create or replace function private.create_doctor_clinic_impl(p_name text, p_display_name text default '')
returns table(clinic_id uuid, clinic_name text, member_role text, permissions jsonb)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
  v_clinic public.clinics%rowtype;
  v_permissions jsonb := private.default_clinic_permissions('owner_doctor');
begin
  if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
  if char_length(trim(coalesce(p_name,''))) < 2 or char_length(trim(p_name)) > 120 then
    raise exception 'invalid_clinic_name' using errcode = '22023';
  end if;
  if exists(select 1 from public.clinics where owner_user_id = v_uid)
     or exists(select 1 from public.clinic_members where user_id = v_uid and active) then
    raise exception 'account_already_linked' using errcode = '23505';
  end if;
  insert into public.clinics(name, owner_user_id)
  values(trim(p_name), v_uid) returning * into v_clinic;
  insert into public.clinic_members(clinic_id,user_id,role,active,display_name,permissions,joined_at)
  values(v_clinic.id,v_uid,'owner_doctor',true,left(trim(coalesce(p_display_name,'')),120),v_permissions,now());
  return query select v_clinic.id,v_clinic.name,'owner_doctor'::text,v_permissions;
end;
$$;
revoke all on function private.create_doctor_clinic_impl(text,text) from public, anon;
grant execute on function private.create_doctor_clinic_impl(text,text) to authenticated;

create or replace function public.create_doctor_clinic(p_name text, p_display_name text default '')
returns table(clinic_id uuid, clinic_name text, member_role text, permissions jsonb)
language sql
set search_path = ''
as $$ select * from private.create_doctor_clinic_impl(p_name,p_display_name); $$;

create or replace function private.generate_clinic_invite_impl(
  p_clinic_id uuid, p_role text, p_permissions jsonb default '{}'::jsonb,
  p_expires_hours integer default 72, p_max_uses integer default 1
)
returns table(code text, expires_at timestamptz, role text, permissions jsonb)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_raw text;
  v_code text;
  v_permissions jsonb;
  v_expires timestamptz;
begin
  if (select auth.uid()) is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
  if not private.is_clinic_owner(p_clinic_id) then raise exception 'not_allowed' using errcode = '42501'; end if;
  if p_role not in ('receptionist','substitute_doctor') then raise exception 'invalid_role' using errcode = '22023'; end if;
  if p_expires_hours < 1 or p_expires_hours > 720 then raise exception 'invalid_expiry' using errcode = '22023'; end if;
  if p_max_uses < 1 or p_max_uses > 20 then raise exception 'invalid_max_uses' using errcode = '22023'; end if;
  v_raw := upper(substr(encode(extensions.gen_random_bytes(8),'hex'),1,10));
  v_code := 'CLN-' || substr(v_raw,1,4) || '-' || substr(v_raw,5,6);
  v_permissions := private.sanitize_clinic_permissions(p_role,coalesce(p_permissions,'{}'::jsonb));
  v_expires := now() + make_interval(hours => p_expires_hours);
  insert into public.clinic_invites(clinic_id,code_hash,role,permissions,created_by,expires_at,max_uses)
  values(p_clinic_id,encode(extensions.digest(convert_to(replace(v_code,'-',''),'UTF8'),'sha256'),'hex'),
         p_role,v_permissions,(select auth.uid()),v_expires,p_max_uses);
  return query select v_code,v_expires,p_role,v_permissions;
end;
$$;
revoke all on function private.generate_clinic_invite_impl(uuid,text,jsonb,integer,integer) from public, anon;
grant execute on function private.generate_clinic_invite_impl(uuid,text,jsonb,integer,integer) to authenticated;

create or replace function public.generate_clinic_invite(
  p_clinic_id uuid, p_role text, p_permissions jsonb default '{}'::jsonb,
  p_expires_hours integer default 72, p_max_uses integer default 1
)
returns table(code text, expires_at timestamptz, role text, permissions jsonb)
language sql
set search_path = ''
as $$ select * from private.generate_clinic_invite_impl(p_clinic_id,p_role,p_permissions,p_expires_hours,p_max_uses); $$;

create or replace function private.update_clinic_member_access_impl(p_member_id uuid, p_active boolean, p_permissions jsonb)
returns public.clinic_members
language plpgsql
security definer
set search_path = ''
as $$
declare v_member public.clinic_members%rowtype;
begin
  select * into v_member from public.clinic_members where id = p_member_id;
  if not found then raise exception 'member_not_found' using errcode = 'P0002'; end if;
  if not private.is_clinic_owner(v_member.clinic_id) then raise exception 'not_allowed' using errcode = '42501'; end if;
  if v_member.role = 'owner_doctor' then raise exception 'owner_access_cannot_be_changed' using errcode = '42501'; end if;
  update public.clinic_members set
    active = coalesce(p_active,active),
    permissions = private.sanitize_clinic_permissions(v_member.role,coalesce(p_permissions,permissions))
  where id = p_member_id returning * into v_member;
  return v_member;
end;
$$;
revoke all on function private.update_clinic_member_access_impl(uuid,boolean,jsonb) from public, anon;
grant execute on function private.update_clinic_member_access_impl(uuid,boolean,jsonb) to authenticated;

create or replace function public.update_clinic_member_access(p_member_id uuid, p_active boolean, p_permissions jsonb)
returns public.clinic_members
language sql
set search_path = ''
as $$ select private.update_clinic_member_access_impl(p_member_id,p_active,p_permissions); $$;

create or replace function private.update_patient_medical_summary_impl(
  p_clinic_id uuid, p_sync_key text, p_age_text text, p_allergies text,
  p_chronic_conditions text, p_current_medications text
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare v_patient_id uuid;
begin
  if (select auth.uid()) is null or not private.has_clinic_permission(p_clinic_id,'edit_clinical') then return false; end if;
  select id into v_patient_id from public.patients where clinic_id=p_clinic_id and sync_key=p_sync_key;
  if v_patient_id is null then return false; end if;
  insert into public.patient_medical_profiles(
    patient_id,clinic_id,age_text,allergies,chronic_conditions,current_medications,updated_by
  ) values (
    v_patient_id,p_clinic_id,coalesce(p_age_text,''),coalesce(p_allergies,''),
    coalesce(p_chronic_conditions,''),coalesce(p_current_medications,''),(select auth.uid())
  ) on conflict (patient_id) do update set
    age_text=excluded.age_text, allergies=excluded.allergies,
    chronic_conditions=excluded.chronic_conditions, current_medications=excluded.current_medications,
    updated_by=(select auth.uid()), updated_at=now();
  return true;
end;
$$;
revoke all on function private.update_patient_medical_summary_impl(uuid,text,text,text,text,text) from public, anon;
grant execute on function private.update_patient_medical_summary_impl(uuid,text,text,text,text,text) to authenticated;

create or replace function public.update_patient_medical_summary(
  p_clinic_id uuid, p_sync_key text, p_age_text text, p_allergies text,
  p_chronic_conditions text, p_current_medications text
)
returns boolean
language sql
set search_path = ''
as $$ select private.update_patient_medical_summary_impl(p_clinic_id,p_sync_key,p_age_text,p_allergies,p_chronic_conditions,p_current_medications); $$;

create or replace function private.accept_clinic_invite_impl(p_code text, p_display_name text default '')
returns table(clinic_id uuid, clinic_name text, member_role text, permissions jsonb)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_uid uuid := (select auth.uid());
  v_hash text;
  v_inv public.clinic_invites%rowtype;
  v_permissions jsonb;
begin
  if v_uid is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
  if p_code is null or length(trim(p_code)) < 8 then raise exception 'invalid_invite' using errcode = '22023'; end if;
  v_hash := encode(extensions.digest(convert_to(replace(upper(trim(p_code)),'-',''),'UTF8'),'sha256'),'hex');
  select * into v_inv from public.clinic_invites i
  where i.code_hash=v_hash and i.active and i.expires_at>now() and i.uses<i.max_uses for update;
  if not found then raise exception 'invite_invalid_or_expired' using errcode = 'P0002'; end if;
  if exists(select 1 from public.clinic_members where user_id=v_uid and active and clinic_id<>v_inv.clinic_id) then
    raise exception 'account_already_linked' using errcode = '23505';
  end if;
  v_permissions := private.sanitize_clinic_permissions(v_inv.role,v_inv.permissions);
  insert into public.clinic_members(clinic_id,user_id,role,active,display_name,permissions,invited_by,joined_at)
  values(v_inv.clinic_id,v_uid,v_inv.role,true,left(trim(coalesce(p_display_name,'')),120),v_permissions,v_inv.created_by,now())
  on conflict on constraint clinic_members_clinic_id_user_id_key do update set
    role=excluded.role, active=true, display_name=excluded.display_name,
    permissions=excluded.permissions, invited_by=excluded.invited_by, joined_at=now();
  update public.clinic_invites set uses=uses+1, active=case when uses+1>=max_uses then false else active end
  where id=v_inv.id;
  return query select c.id,c.name,m.role,m.permissions from public.clinics c
  join public.clinic_members m on m.clinic_id=c.id where c.id=v_inv.clinic_id and m.user_id=v_uid;
end;
$$;

drop policy if exists clinics_insert on public.clinics;
drop policy if exists members_insert on public.clinic_members;
drop policy if exists members_update on public.clinic_members;
drop policy if exists members_delete on public.clinic_members;
drop policy if exists clinic_invites_insert on public.clinic_invites;
drop policy if exists clinic_invites_update on public.clinic_invites;
drop policy if exists clinic_invites_delete on public.clinic_invites;

revoke insert, update, delete on public.clinics from anon, authenticated;
revoke insert, update, delete on public.clinic_members from anon, authenticated;
revoke insert, update, delete on public.clinic_invites from anon, authenticated;

do $$
declare r record;
begin
  for r in select p.oid::regprocedure as signature from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='public'
  loop
    execute format('revoke all on function %s from public, anon', r.signature);
  end loop;
end;
$$;

grant execute on function public.create_doctor_clinic(text,text) to authenticated, service_role;
grant execute on function public.generate_clinic_invite(uuid,text,jsonb,integer,integer) to authenticated, service_role;
grant execute on function public.update_clinic_member_access(uuid,boolean,jsonb) to authenticated, service_role;
grant execute on function public.accept_clinic_invite(text,text) to authenticated, service_role;
grant execute on function public.update_patient_medical_summary(uuid,text,text,text,text,text) to authenticated, service_role;
grant execute on function public.current_clinic_entitlement(uuid) to authenticated, service_role;
grant execute on function public.admin_record_subscription_payment(uuid,bigint,text,text,text,text,integer,text) to authenticated, service_role;
grant execute on function public.confirm_subscription_payment(uuid,text,integer) to authenticated, service_role;
grant execute on function public.reject_subscription_payment(uuid,text) to authenticated, service_role;
grant execute on function public.set_clinic_subscription_status(uuid,text,text) to authenticated, service_role;
grant execute on function public.platform_admin_billing_summary() to authenticated, service_role;
grant execute on function public.claim_initial_platform_admin(text,text) to authenticated, service_role;

comment on table public.patient_medical_profiles is 'Clinical-only patient medical summary, separated from reception-visible identity data.';
