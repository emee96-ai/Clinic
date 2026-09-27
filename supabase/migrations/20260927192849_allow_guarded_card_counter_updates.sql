-- Card assignment and range reservation legitimately advance next_card_no.
-- Keep every other owner-managed clinic column protected.
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
      - 'finance_settings_updated_at' - 'next_card_no' - 'updated_at')
     is distinct from
     (to_jsonb(old) - 'visit_fee' - 'result_fee' - 'followup_days'
      - 'finance_settings_updated_at' - 'next_card_no' - 'updated_at') then
    raise exception 'owner_update_contains_protected_columns' using errcode = '42501';
  end if;
  if new.next_card_no is distinct from old.next_card_no
     and (new.next_card_no <= old.next_card_no or new.next_card_no > old.next_card_no + 500) then
    raise exception 'invalid_card_number_counter_update' using errcode = '22023';
  end if;
  if new.visit_fee < 0 or new.result_fee < 0 or new.followup_days not between 1 and 90 then
    raise exception 'invalid_finance_settings' using errcode = '22023';
  end if;
  if new.visit_fee is distinct from old.visit_fee
     or new.result_fee is distinct from old.result_fee
     or new.followup_days is distinct from old.followup_days then
    new.finance_settings_updated_at := now();
  end if;
  return new;
end;
$$;
