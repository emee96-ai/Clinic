alter table public.clinics
  add column if not exists visit_fee integer not null default 10000 check (visit_fee >= 0),
  add column if not exists result_fee integer not null default 0 check (result_fee >= 0),
  add column if not exists followup_days integer not null default 7 check (followup_days between 1 and 90),
  add column if not exists finance_settings_updated_at timestamptz not null default now();

alter table public.payments drop constraint if exists payments_amount_check;
alter table public.payments
  add column if not exists event_type text not null default 'PAYMENT',
  add column if not exists reversal_of_payment_id uuid references public.payments(id) on delete restrict,
  add column if not exists reason text not null default '',
  add column if not exists actor_user_id uuid references auth.users(id) on delete set null,
  add column if not exists actor_display_name text not null default '',
  add column if not exists actor_role text not null default '';
alter table public.payments
  add constraint payments_nonzero_amount check (amount <> 0),
  add constraint payments_event_type_check check (event_type in ('PAYMENT','VOID','REFUND','CORRECTION')),
  add constraint payments_event_sign_check check ((event_type='PAYMENT' and amount>0) or (event_type<>'PAYMENT' and amount<0));
create unique index if not exists payments_one_reversal on public.payments(reversal_of_payment_id) where reversal_of_payment_id is not null;

alter table public.day_closures
  add column if not exists is_reopened boolean not null default false,
  add column if not exists reopen_count integer not null default 0 check (reopen_count >= 0),
  add column if not exists last_reopened_at timestamptz,
  add column if not exists last_reopened_by_user_id uuid references auth.users(id) on delete set null,
  add column if not exists last_reopened_by_name text not null default '',
  add column if not exists last_reopen_reason text not null default '';

create or replace function private.guard_payment_write()
returns trigger language plpgsql set search_path = '' as $$
declare v_visit_clinic uuid; v_original public.payments%rowtype;
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
    if new.actor_user_id is distinct from (select auth.uid()) then raise exception 'invalid_payment_actor' using errcode='42501'; end if;
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

drop policy if exists payments_update on public.payments;
drop policy if exists payments_delete on public.payments;

create or replace function private.guard_day_closure_write()
returns trigger language plpgsql set search_path='' as $$
begin
  if tg_op='UPDATE' and new.is_reopened and not old.is_reopened then
    if not private.is_clinic_owner(new.clinic_id) then raise exception 'owner_required_to_reopen_day' using errcode='42501'; end if;
    if char_length(trim(new.last_reopen_reason))<3 or new.last_reopened_by_user_id is distinct from (select auth.uid()) then
      raise exception 'reopen_reason_and_actor_required' using errcode='23514';
    end if;
    if new.reopen_count<>old.reopen_count+1 then raise exception 'invalid_reopen_count' using errcode='23514'; end if;
  end if;
  return new;
end $$;

drop trigger if exists guard_day_closure_write on public.day_closures;
create trigger guard_day_closure_write before insert or update on public.day_closures
for each row execute function private.guard_day_closure_write();

create or replace function public.update_clinic_finance_settings(
  p_clinic_id uuid, p_visit_fee integer, p_result_fee integer, p_followup_days integer
) returns boolean language plpgsql security definer set search_path='' as $$
begin
  if not private.is_clinic_owner(p_clinic_id) then raise exception 'owner_required' using errcode='42501'; end if;
  if p_visit_fee<0 or p_result_fee<0 or p_followup_days not between 1 and 90 then raise exception 'invalid_finance_settings' using errcode='22023'; end if;
  update public.clinics set visit_fee=p_visit_fee,result_fee=p_result_fee,followup_days=p_followup_days,
    finance_settings_updated_at=now(),updated_at=now() where id=p_clinic_id;
  return found;
end $$;

revoke all on function public.update_clinic_finance_settings(uuid,integer,integer,integer) from public, anon;
grant execute on function public.update_clinic_finance_settings(uuid,integer,integer,integer) to authenticated;
