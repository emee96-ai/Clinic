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
  if tg_op='UPDATE' and new.status is distinct from old.status and not (
    (old.status='REGISTERED' and new.status in ('WAITING','CANCELLED'))
    or (old.status='WAITING' and new.status in ('IN_CONSULT','CANCELLED'))
    or (old.status='IN_CONSULT' and new.status in ('COMPLETED','WAITING'))
    or (old.status='CANCELLED' and new.status='WAITING')
  ) then raise exception 'invalid_visit_status_transition' using errcode='23514';
  end if;
  if new.assigned_doctor_user_id is not null and not exists(
    select 1 from public.clinic_members m
    where m.clinic_id=new.clinic_id and m.user_id=new.assigned_doctor_user_id
      and m.active and m.role in ('owner_doctor','substitute_doctor')
  ) then raise exception 'assigned_doctor_not_active' using errcode='23514';
  end if;
  if new.status='IN_CONSULT' and new.assigned_doctor_user_id is null then
    raise exception 'assigned_doctor_required' using errcode='23514';
  end if;
  if new.status='CANCELLED' and (
    char_length(trim(coalesce(new.cancellation_reason,'')))<3 or new.paid_amount<>0
  ) then raise exception 'invalid_visit_cancellation' using errcode='23514';
  end if;
  return new;
end; $$;
revoke all on function private.guard_phase4_visit_fields() from public,anon,authenticated;
