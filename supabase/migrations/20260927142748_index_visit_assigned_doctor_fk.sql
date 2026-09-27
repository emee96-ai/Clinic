create index if not exists idx_visits_assigned_doctor_user
  on public.visits(assigned_doctor_user_id)
  where assigned_doctor_user_id is not null;
