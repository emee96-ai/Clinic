create or replace function private.guard_payment_write()
returns trigger language plpgsql set search_path = '' as $$
declare v_visit_clinic uuid; v_original public.payments%rowtype; v_name text; v_role text;
begin
  select v.clinic_id into v_visit_clinic from public.visits v where v.id = new.visit_id;
  if v_visit_clinic is distinct from new.clinic_id then raise exception 'payment_visit_clinic_mismatch' using errcode='23514'; end if;
  if tg_op='UPDATE' and (to_jsonb(new)-'updated_at'-'record_version'-'client_change_id'-'source_device_id')
      is distinct from (to_jsonb(old)-'updated_at'-'record_version'-'client_change_id'-'source_device_id') then
    raise exception 'payment_is_immutable_use_reversal' using errcode='42501';
  end if;
  if tg_op='INSERT' then
    if exists(select 1 from public.day_closures d where d.clinic_id=new.clinic_id and d.day=(new.created_at at time zone 'UTC')::date and not d.is_reopened) then
      raise exception 'financial_day_is_closed' using errcode='42501';
    end if;
    if new.actor_user_id is null then new.actor_user_id := (select auth.uid()); end if;
    if new.actor_user_id is distinct from (select auth.uid()) then raise exception 'invalid_payment_actor' using errcode='42501'; end if;
    select m.display_name,m.role into v_name,v_role from public.clinic_members m
      where m.clinic_id=new.clinic_id and m.user_id=(select auth.uid()) and m.active limit 1;
    if not found then raise exception 'inactive_clinic_member' using errcode='42501'; end if;
    new.actor_display_name:=coalesce(v_name,''); new.actor_role:=v_role;
    if new.event_type='PAYMENT' and new.reversal_of_payment_id is not null then raise exception 'payment_cannot_reference_reversal' using errcode='23514'; end if;
    if new.event_type<>'PAYMENT' then
      if char_length(trim(new.reason))<3 or new.reversal_of_payment_id is null then raise exception 'reversal_reason_required' using errcode='23514'; end if;
      select * into v_original from public.payments p where p.id=new.reversal_of_payment_id for update;
      if not found or v_original.clinic_id<>new.clinic_id or v_original.visit_id<>new.visit_id or v_original.amount<=0 or v_original.event_type<>'PAYMENT' or new.amount<>-v_original.amount then
        raise exception 'invalid_payment_reversal' using errcode='23514';
      end if;
      if not private.is_clinic_owner(new.clinic_id) then raise exception 'owner_required_for_payment_reversal' using errcode='42501'; end if;
    end if;
  end if;
  return new;
end $$;

create or replace function private.guard_day_closure_write()
returns trigger language plpgsql set search_path='' as $$
declare v_name text;
begin
  if tg_op='UPDATE' and new.is_reopened and not old.is_reopened then
    if not private.is_clinic_owner(new.clinic_id) then raise exception 'owner_required_to_reopen_day' using errcode='42501'; end if;
    if char_length(trim(new.last_reopen_reason))<3 or new.last_reopened_by_user_id is distinct from (select auth.uid()) then
      raise exception 'reopen_reason_and_actor_required' using errcode='23514';
    end if;
    if new.reopen_count<>old.reopen_count+1 then raise exception 'invalid_reopen_count' using errcode='23514'; end if;
    select m.display_name into v_name from public.clinic_members m
      where m.clinic_id=new.clinic_id and m.user_id=(select auth.uid()) and m.active limit 1;
    if not found then raise exception 'inactive_clinic_member' using errcode='42501'; end if;
    new.last_reopened_by_name:=coalesce(v_name,'');
  end if;
  return new;
end $$;
