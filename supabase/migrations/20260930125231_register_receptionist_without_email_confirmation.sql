-- Service-role-only RPC used by the register-receptionist Edge Function.
-- The public mobile client can never call this function directly.
create or replace function public.service_register_receptionist(
  p_user_id uuid,
  p_code text,
  p_display_name text default ''
)
returns table(clinic_id uuid, clinic_name text, member_role text, permissions jsonb)
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_hash text;
  v_inv public.clinic_invites%rowtype;
  v_permissions jsonb;
begin
  if p_user_id is null or not exists(select 1 from auth.users where id = p_user_id) then
    raise exception 'user_not_found' using errcode = 'P0002';
  end if;
  if p_code is null or length(trim(p_code)) < 8 then
    raise exception 'invalid_invite' using errcode = '22023';
  end if;

  v_hash := encode(
    extensions.digest(convert_to(replace(upper(trim(p_code)),'-',''),'UTF8'),'sha256'),
    'hex'
  );
  select * into v_inv
  from public.clinic_invites i
  where i.code_hash = v_hash
    and i.active
    and i.role = 'receptionist'
    and i.expires_at > now()
    and i.uses < i.max_uses
  for update;

  if not found then
    raise exception 'invite_invalid_or_expired' using errcode = 'P0002';
  end if;
  if exists(
    select 1 from public.clinic_members
    where user_id = p_user_id and active and clinic_id <> v_inv.clinic_id
  ) then
    raise exception 'account_already_linked' using errcode = '23505';
  end if;

  v_permissions := private.sanitize_clinic_permissions('receptionist', v_inv.permissions);
  insert into public.clinic_members(
    clinic_id,user_id,role,active,display_name,permissions,invited_by,joined_at
  ) values (
    v_inv.clinic_id,p_user_id,'receptionist',true,
    left(trim(coalesce(p_display_name,'')),120),v_permissions,v_inv.created_by,now()
  )
  on conflict on constraint clinic_members_clinic_id_user_id_key do update set
    role = 'receptionist', active = true, display_name = excluded.display_name,
    permissions = excluded.permissions, invited_by = excluded.invited_by, joined_at = now();

  update public.clinic_invites
  set uses = uses + 1,
      active = case when uses + 1 >= max_uses then false else active end
  where id = v_inv.id;

  return query
  select c.id,c.name,m.role,m.permissions
  from public.clinics c
  join public.clinic_members m on m.clinic_id = c.id
  where c.id = v_inv.clinic_id and m.user_id = p_user_id;
end;
$$;

revoke all on function public.service_register_receptionist(uuid,text,text)
  from public, anon, authenticated;
grant execute on function public.service_register_receptionist(uuid,text,text)
  to service_role;

comment on function public.service_register_receptionist(uuid,text,text)
  is 'Service-only atomic receptionist membership creation after confirmed Auth user creation.';
