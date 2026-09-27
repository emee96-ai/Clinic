package com.eman.clinic;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

/** Durable cloud synchronization that survives process death and device reboot. */
public final class ClinicSyncWorker extends Worker {
    public ClinicSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull @Override public Result doWork() {
        return SyncCoordinator.runBlocking(getApplicationContext()) ? Result.success() : Result.retry();
    }
}
