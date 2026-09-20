package com.eman.clinic;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class LauncherActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AuthStore auth = new AuthStore(this);
        Class<?> target = auth.hasRemoteIdentity() ? MainActivity.class : LoginActivity.class;
        startActivity(new Intent(this, target));
        finish();
    }
}
