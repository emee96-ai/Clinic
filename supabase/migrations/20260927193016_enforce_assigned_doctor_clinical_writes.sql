-- Even the clinic owner must transfer/take over an active visit before changing
-- its clinical record. Ownership remains sufficient for administrative transfer.
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
  if v_assigned_doctor is distinct from (select auth.uid()) then
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
