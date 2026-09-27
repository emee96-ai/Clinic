create index if not exists idx_payments_actor_user on public.payments(actor_user_id) where actor_user_id is not null;
create index if not exists idx_day_closures_reopened_by on public.day_closures(last_reopened_by_user_id) where last_reopened_by_user_id is not null;
