package com.eman.clinic;

import android.app.Application;

public class ClinicApp extends Application {
    @Override public void onCreate() {
        super.onCreate();
        SyncBootstrap.install(this);
        SyncCoordinator.start(this);
    }
}
