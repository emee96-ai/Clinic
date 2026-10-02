package com.eman.clinic;

import android.content.Context;

import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Persistent WorkManager scheduler. Local clinic work never waits for network. */
public final class SyncCoordinator {
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static final String PERIODIC = "clinic-cloud-sync-periodic";
    private static final String IMMEDIATE = "clinic-cloud-sync-now";

    private SyncCoordinator() {}

    public static void start(Context context) {
        Context app = context.getApplicationContext();
        SyncBootstrap.install(app);
        Constraints connected = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build();
        PeriodicWorkRequest periodic = new PeriodicWorkRequest.Builder(
                ClinicSyncWorker.class, 15, TimeUnit.MINUTES)
                .setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic);
        kick(app);
    }

    public static void kick(Context context) {
        Context app = context.getApplicationContext();
        // The active clinic can become known only after login/invite resolution. Re-installing
        // here guarantees change-tracking triggers are attached to the clinic-scoped database,
        // and seeds any rows created before the clinic identity was available.
        SyncBootstrap.install(app);
        LocalSyncManager.kick(app);

        Constraints connected = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(ClinicSyncWorker.class)
                .setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build();
        WorkManager.getInstance(app)
                .enqueueUniqueWork(IMMEDIATE, ExistingWorkPolicy.KEEP, work);
    }

    static boolean runBlocking(Context c) {
        if (c == null || !RUNNING.compareAndSet(false, true)) return true;
        try {
            AuthStore auth = new AuthStore(c);
            if (!auth.hasRemoteIdentity()) return true;

            SupabaseApi api = new SupabaseApi(c);
            boolean activeMembership = api.resolveMembership();
            if (!activeMembership) {
                auth.markMembershipInactive();
                ClinicApp.showMembershipBlocked();
                return true;
            }

            int conflicts = new ResilientRemoteSync(c).syncOnce();
            if (conflicts > 0) ClinicApp.showSyncConflict(conflicts);
            if (auth.isSubscriptionBlocked()) ClinicApp.showSubscriptionBlocked();
            SyncStore store = new SyncStore(c);
            return store.failedCount() == 0;
        } catch (Exception ignored) {
            return false;
        } finally {
            RUNNING.set(false);
        }
    }
}
