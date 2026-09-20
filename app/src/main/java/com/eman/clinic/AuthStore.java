package com.eman.clinic;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores the Clinic Supabase session and selected clinic for this device. */
public final class AuthStore {
    private static final String PREF = "clinic_remote_auth";
    private final SharedPreferences prefs;

    public AuthStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public void saveSession(String accessToken, String refreshToken, String userId) {
        SharedPreferences.Editor e = prefs.edit();
        if (accessToken != null) e.putString("access_token", accessToken);
        if (refreshToken != null && !refreshToken.isEmpty()) e.putString("refresh_token", refreshToken);
        if (userId != null && !userId.isEmpty()) e.putString("user_id", userId);
        e.apply();
    }

    public void saveClinic(String clinicId, String clinicName) {
        prefs.edit()
                .putString("clinic_id", clinicId == null ? "" : clinicId)
                .putString("clinic_name", clinicName == null ? "" : clinicName)
                .apply();
    }

    public String accessToken() { return prefs.getString("access_token", ""); }
    public String refreshToken() { return prefs.getString("refresh_token", ""); }
    public String userId() { return prefs.getString("user_id", ""); }
    public String clinicId() { return prefs.getString("clinic_id", ""); }
    public String clinicName() { return prefs.getString("clinic_name", ""); }

    public boolean hasRemoteIdentity() {
        return !refreshToken().isEmpty() && !userId().isEmpty() && !clinicId().isEmpty();
    }

    public void setPendingClinicName(String name) {
        prefs.edit().putString("pending_clinic_name", name == null ? "" : name.trim()).apply();
    }

    public String pendingClinicName() { return prefs.getString("pending_clinic_name", ""); }
    public void clearPendingClinicName() { prefs.edit().remove("pending_clinic_name").apply(); }

    public void clearRemoteSession() {
        prefs.edit().remove("access_token").remove("refresh_token").remove("user_id").remove("clinic_id").remove("clinic_name").apply();
    }
}
