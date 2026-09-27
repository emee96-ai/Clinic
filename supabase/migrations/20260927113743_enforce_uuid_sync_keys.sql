do $$
declare
  v_table text;
  v_constraint text;
begin
  foreach v_table in array array['patients', 'visits', 'payments', 'day_closures']
  loop
    v_constraint := v_table || '_sync_key_uuid_format';

    execute format(
      'alter table public.%I drop constraint if exists %I',
      v_table,
      v_constraint
    );
    execute format(
      'alter table public.%I add constraint %I check (sync_key ~* %L)',
      v_table,
      v_constraint,
      '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
    );
  end loop;
end
$$;
