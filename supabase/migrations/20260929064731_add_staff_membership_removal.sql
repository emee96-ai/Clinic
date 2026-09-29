-- The owner doctor may permanently detach a receptionist or substitute doctor.
-- This removes clinic membership only; it does not delete the person's Auth account.
create or replace function private.remove_clinic_member_impl(p_member_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_member public.clinic_members%rowtype;
begin
  if (select auth.uid()) is null then
    raise exception 'not_authenticated' using errcode = '42501';
  end if;

  select * into v_member
  from public.clinic_members
  where id = p_member_id
  for update;

  if not found then
    raise exception 'member_not_found' using errcode = 'P0002';
  end if;
  if not private.is_clinic_owner(v_member.clinic_id) then
    raise exception 'not_allowed' using errcode = '42501';
  end if;
  if v_member.role = 'owner_doctor' then
    raise exception 'owner_cannot_be_removed' using errcode = '42501';
  end if;

  delete from public.clinic_members where id = p_member_id;
  return true;
end;
$$;

revoke all on function private.remove_clinic_member_impl(uuid) from public, anon, authenticated;

create or replace function public.remove_clinic_member(p_member_id uuid)
returns boolean
language sql
set search_path = ''
as $$ select private.remove_clinic_member_impl(p_member_id); $$;

revoke all on function public.remove_clinic_member(uuid) from public, anon;
grant execute on function public.remove_clinic_member(uuid) to authenticated, service_role;
