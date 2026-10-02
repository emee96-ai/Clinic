package com.eman.clinic;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.lang.ref.WeakReference;

public class ClinicApp extends Application implements Application.ActivityLifecycleCallbacks {
    private static final WeakReference<Activity> EMPTY_ACTIVITY = new WeakReference<>(null);
    private static WeakReference<Activity> currentActivity = EMPTY_ACTIVITY;
    private static final long ACTIVE_SYNC_CHECK_MS = 5000L;
    private static final long IDLE_PULL_MS = 30000L;

    private Handler syncHandler;
    private long lastIdlePullMs;

    private final Runnable foregroundSyncPulse = new Runnable() {
        @Override public void run() {
            try {
                Activity activity = currentActivity.get();
                if (activity != null && !activity.isFinishing()) {
                    AuthStore auth = new AuthStore(ClinicApp.this);
                    if (auth.hasRemoteIdentity() && auth.isMembershipActive()) {
                        SyncStore store = new SyncStore(ClinicApp.this);
                        boolean pending = store.pendingCount() > 0;
                        long now = System.currentTimeMillis();
                        if (pending || now - lastIdlePullMs >= IDLE_PULL_MS) {
                            SyncCoordinator.kick(ClinicApp.this);
                            if (!pending) lastIdlePullMs = now;
                        }
                    }
                }
            } catch (Exception ignored) {
                // The clinic remains fully usable even if background sync cannot run.
            } finally {
                if (syncHandler != null) syncHandler.postDelayed(this, ACTIVE_SYNC_CHECK_MS);
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        System.loadLibrary("sqlcipher");
        registerActivityLifecycleCallbacks(this);
        SyncBootstrap.install(this);
        SyncCoordinator.start(this);
        LocalSyncManager.start(this);
        syncHandler = new Handler(Looper.getMainLooper());
        syncHandler.postDelayed(foregroundSyncPulse, 1500L);
    }

    public static void showSubscriptionBlocked() {
        Activity activity = currentActivity.get();
        if (activity == null || activity.isFinishing()
                || activity instanceof SubscriptionActivity
                || activity instanceof LoginActivity) return;
        activity.runOnUiThread(() -> {
            if (activity.isFinishing()) return;
            activity.startActivity(new Intent(activity, SubscriptionActivity.class));
            activity.finish();
        });
    }

    public static void showMembershipBlocked() {
        Activity activity = currentActivity.get();
        if (activity == null || activity.isFinishing() || activity instanceof LoginActivity) return;
        activity.runOnUiThread(() -> {
            if (activity.isFinishing()) return;
            Toast.makeText(activity, "تم إيقاف أو إزالة حسابك من العيادة", Toast.LENGTH_LONG).show();
            Intent intent = new Intent(activity, LoginActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            activity.startActivity(intent);
            activity.finish();
        });
    }

    public static void showSyncConflict(int count) {
        Activity activity = currentActivity.get();
        if (activity == null || activity.isFinishing() || count <= 0) return;
        activity.runOnUiThread(() -> Toast.makeText(activity,
                count == 1
                        ? "تم اكتشاف تعديل متعارض وحفظ النسختين للمراجعة"
                        : "تم اكتشاف " + count + " تعديلات متعارضة وحفظ النسخ للمراجعة",
                Toast.LENGTH_LONG).show());
    }

    @Override public void onActivityResumed(Activity activity) {
        currentActivity = new WeakReference<>(activity);
        lastIdlePullMs = 0L;
        SyncCoordinator.kick(this);
    }

    @Override public void onActivityPaused(Activity activity) {
        Activity current = currentActivity.get();
        if (current == activity) currentActivity = EMPTY_ACTIVITY;
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
