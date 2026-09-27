-- Data API roles must never own schema-level or destructive table privileges.
revoke all privileges on all tables in schema public from anon;
revoke truncate, references, trigger on all tables in schema public from authenticated;

-- Authenticated access remains explicit; RLS still decides which rows are reachable.
grant select on public.clinics, public.clinic_members, public.clinic_invites,
  public.patients, public.visits, public.payments, public.day_closures,
  public.clinical_records, public.patient_medical_profiles, public.audit_log,
  public.subscription_payments, public.subscription_events, public.platform_admins,
  public.clinic_devices to authenticated;

grant insert, update, delete on public.patients, public.visits, public.payments,
  public.day_closures, public.clinical_records, public.subscription_payments,
  public.subscription_events, public.clinic_devices to authenticated;

-- Identity-backed audit rows are written by trusted trigger functions only.
revoke insert, update, delete on public.audit_log from authenticated;
revoke insert, update, delete on public.clinics, public.clinic_members,
  public.clinic_invites, public.patient_medical_profiles, public.platform_admins
  from authenticated;
