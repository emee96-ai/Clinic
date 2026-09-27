-- The invoker-safe finance RPC needs only these columns. RLS and the clinic
-- update guard still restrict the operation to the clinic owner.
grant update (visit_fee, result_fee, followup_days, finance_settings_updated_at)
on public.clinics to authenticated;
