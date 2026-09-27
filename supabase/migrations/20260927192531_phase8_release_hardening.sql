-- Phase 8 final release hardening: immutable clinical history, assigned-doctor
-- enforcement, non-destructive records and an invoker-safe finance RPC.

alter table public.clinical_records
  add column if not exists visit_change_id text not null default '';

create or replace function private.guard_clinical_record_write()
returns trigger language plpgsql set search_path = '' as $$
declare
  v_visit_clinic uuid;
  v_visit_status text;
  v_assigned_doctor uuid;
  v_visit_change_id text;
  v_same_payload boolean;
begin
  select v.clinic_id, v.status, v.assigned_doctor_user_id, v.client_change_id
    into v_visit_clinic, v_visit_status, v_assigned_doctor, v_visit_change_id
  from public.visits v where v.id = new.visit_id;

  if not found or v_visit_clinic is distinct from new.clinic_id then
    raise exception 'clinical_visit_clinic_mismatch' using errcode = '23514';
  end if;
  if not private.has_clinic_permission(new.clinic_id, 'edit_clinical') then
    raise exception 'clinical_edit_not_allowed' using errcode = '42501';
  end if;
  if not private.is_clinic_owner(new.clinic_id)
     and v_assigned_doctor is distinct from (select auth.uid()) then
    raise exception 'visit_assigned_to_another_doctor' using errcode = '42501';
  end if;
  if v_visit_status not in ('IN_CONSULT', 'COMPLETED') then
    raise exception 'visit_not_editable' using errcode = '42501';
  end if;

  if tg_op = 'UPDATE' then
    if new.id is distinct from old.id or new.clinic_id is distinct from old.clinic_id
       or new.visit_id is distinct from old.visit_id or new.created_at is distinct from old.created_at then
      raise exception 'clinical_record_identity_is_immutable' using errcode = '42501';
    end if;
    v_same_payload := (to_jsonb(new) - 'updated_at' - 'updated_by')
      is not distinct from (to_jsonb(old) - 'updated_at' - 'updated_by');
    if v_visit_status = 'COMPLETED' and not v_same_payload and not (
      old.visit_change_id is distinct from v_visit_change_id
      and new.visit_change_id = v_visit_change_id
    ) then
      raise exception 'completed_clinical_record_is_immutable' using errcode = '42501';
    end if;
  end if;

  if v_visit_status = 'COMPLETED' and new.visit_change_id <> v_visit_change_id then
    raise exception 'clinical_record_visit_version_mismatch' using errcode = '40001';
  end if;
  new.updated_by := (select auth.uid());
  if new.updated_by is null then raise exception 'not_authenticated' using errcode = '42501'; end if;
  return new;
end;
$$;

drop policy if exists clinical_records_delete on public.clinical_records;
drop policy if exists patients_delete on public.patients;
drop policy if exists visits_delete on public.visits;
drop policy if exists closures_delete on public.day_closures;
revoke delete on public.clinical_records, public.patients, public.visits,
  public.payments, public.day_closures from authenticated;

create or replace function private.guard_clinic_owner_update()
returns trigger language plpgsql set search_path = '' as $$
begin
  if exists(select 1 from public.platform_admins a where a.user_id = (select auth.uid())) then
    return new;
  end if;
  if not private.is_clinic_owner(old.id) then
    raise exception 'clinic_update_not_allowed' using errcode = '42501';
  end if;
  if (to_jsonb(new) - 'visit_fee' - 'result_fee' - 'followup_days'
      - 'finance_settings_updated_at' - 'updated_at')
     is distinct from
     (to_jsonb(old) - 'visit_fee' - 'result_fee' - 'followup_days'
      - 'finance_settings_updated_at' - 'updated_at') then
    raise exception 'owner_can_only_update_finance_settings' using errcode = '42501';
  end if;
  if new.visit_fee < 0 or new.result_fee < 0 or new.followup_days not between 1 and 90 then
    raise exception 'invalid_finance_settings' using errcode = '22023';
  end if;
  new.finance_settings_updated_at := now();
  return new;
end;
$$;

drop trigger if exists guard_clinic_owner_update on public.clinics;
create trigger guard_clinic_owner_update before update on public.clinics
for each row execute function private.guard_clinic_owner_update();

drop policy if exists clinics_owner_finance_update on public.clinics;
create policy clinics_owner_finance_update on public.clinics for update to authenticated
using (private.is_clinic_owner(id))
with check (private.is_clinic_owner(id));

create or replace function public.update_clinic_finance_settings(
  p_clinic_id uuid, p_visit_fee integer, p_result_fee integer, p_followup_days integer
) returns boolean language plpgsql security invoker set search_path = '' as $$
begin
  if not private.is_clinic_owner(p_clinic_id) then
    raise exception 'owner_required' using errcode = '42501';
  end if;
  if p_visit_fee < 0 or p_result_fee < 0 or p_followup_days not between 1 and 90 then
    raise exception 'invalid_finance_settings' using errcode = '22023';
  end if;
  update public.clinics
  set visit_fee = p_visit_fee, result_fee = p_result_fee,
      followup_days = p_followup_days, finance_settings_updated_at = now()
  where id = p_clinic_id;
  return found;
end;
$$;

revoke all on function public.update_clinic_finance_settings(uuid,integer,integer,integer)
  from public, anon;
grant execute on function public.update_clinic_finance_settings(uuid,integer,integer,integer)
  to authenticated;

revoke all on function private.guard_clinical_record_write(),
  private.guard_clinic_owner_update() from public, anon, authenticated;
