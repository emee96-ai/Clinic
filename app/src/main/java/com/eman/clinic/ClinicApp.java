package com.eman.clinic;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.os.Bundle;

import java.lang.ref.WeakReference;

public class ClinicApp extends Application implements Application.ActivityLifecycleCallbacks {
    private static WeakReference<Activity> currentActivity = new WeakReference<>(null);

    @Override public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);
        SyncBootstrap.install(this);
        SyncCoordinator.start(this);
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

    @Override public void onActivityResumed(Activity activity) { currentActivity = new WeakReference<>(activity); }
    @Override public void onActivityPaused(Activity activity) {
        Activity current = currentActivity.get();
        if (current == activity) currentActivity.clear();
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
