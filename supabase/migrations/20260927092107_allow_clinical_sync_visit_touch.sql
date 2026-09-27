drop policy if exists visits_update on public.visits;
create policy visits_update on public.visits for update to authenticated
using (
  private.has_clinic_permission(clinic_id, 'register_visits')
  or private.has_clinic_permission(clinic_id, 'manage_queue')
  or private.has_clinic_permission(clinic_id, 'record_payments')
  or private.has_clinic_permission(clinic_id, 'edit_clinical')
)
with check (
  private.has_clinic_permission(clinic_id, 'register_visits')
  or private.has_clinic_permission(clinic_id, 'manage_queue')
  or private.has_clinic_permission(clinic_id, 'record_payments')
  or private.has_clinic_permission(clinic_id, 'edit_clinical')
);
