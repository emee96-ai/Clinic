package com.eman.clinic;

import android.content.Context;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Lightweight process-lifetime scheduler. Local work never waits for network. */
public final class SyncCoordinator {
    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor();
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static Context app;

    private SyncCoordinator() {}

    public static void start(Context context) {
        app = context.getApplicationContext();
        if (!STARTED.compareAndSet(false, true)) return;
        EXECUTOR.scheduleWithFixedDelay(SyncCoordinator::runSafe, 4, 30, TimeUnit.SECONDS);
    }

    public static void kick(Context context) {
        app = context.getApplicationContext();
        EXECUTOR.execute(SyncCoordinator::runSafe);
    }

    private static void runSafe() {
        Context c = app;
        if (c == null || !RUNNING.compareAndSet(false, true)) return;
        try {
            AuthStore auth = new AuthStore(c);
            if (!auth.hasRemoteIdentity()) return;

            SupabaseApi api = new SupabaseApi(c);
            boolean activeMembership = api.resolveMembership();
            if (!activeMembership) {
                auth.markMembershipInactive();
                return;
            }

            int conflicts = new ResilientRemoteSync(c).syncOnce();
            if (conflicts > 0) ClinicApp.showSyncConflict(conflicts);
            if (auth.isSubscriptionBlocked()) ClinicApp.showSubscriptionBlocked();
        } catch (Exception ignored) {
            // Network failure must never interrupt clinic work or falsely revoke access.
            // The SQLite outbox remains intact and the next pass retries automatically.
        } finally {
            RUNNING.set(false);
        }
    }
}
