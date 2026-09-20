package com.eman.clinic;

import android.content.Context;
import android.content.SharedPreferences;

/** Stores the Clinic session, selected clinic and cached subscription state. */
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
        prefs.edit().putString("clinic_id", safe(clinicId)).putString("clinic_name", safe(clinicName)).apply();
    }

    public void saveEntitlement(String plan, String status, boolean allowed, String reason,
                                String trialEndsAt, String paidUntil, String serverTime) {
        prefs.edit()
                .putBoolean("entitlement_known", true)
                .putBoolean("entitlement_allowed", allowed)
                .putString("subscription_plan", safe(plan))
                .putString("subscription_status", safe(status))
                .putString("subscription_reason", safe(reason))
                .putString("trial_ends_at", safe(trialEndsAt))
                .putString("paid_until", safe(paidUntil))
                .putString("entitlement_server_time", safe(serverTime))
                .putLong("entitlement_checked_local_ms", System.currentTimeMillis())
                .apply();
    }

    public String accessToken() { return prefs.getString("access_token", ""); }
    public String refreshToken() { return prefs.getString("refresh_token", ""); }
    public String userId() { return prefs.getString("user_id", ""); }
    public String clinicId() { return prefs.getString("clinic_id", ""); }
    public String clinicName() { return prefs.getString("clinic_name", ""); }
    public String subscriptionPlan() { return prefs.getString("subscription_plan", "trial"); }
    public String subscriptionStatus() { return prefs.getString("subscription_status", ""); }
    public String subscriptionReason() { return prefs.getString("subscription_reason", ""); }
    public String trialEndsAt() { return prefs.getString("trial_ends_at", ""); }
    public String paidUntil() { return prefs.getString("paid_until", ""); }

    public boolean hasRemoteIdentity() {
        return !refreshToken().isEmpty() && !userId().isEmpty() && !clinicId().isEmpty();
    }

    public boolean isSubscriptionBlocked() {
        return prefs.getBoolean("entitlement_known", false) && !prefs.getBoolean("entitlement_allowed", true);
    }

    public void setPendingClinicName(String name) {
        prefs.edit().putString("pending_clinic_name", name == null ? "" : name.trim()).apply();
    }

    public String pendingClinicName() { return prefs.getString("pending_clinic_name", ""); }
    public void clearPendingClinicName() { prefs.edit().remove("pending_clinic_name").apply(); }

    public void clearRemoteSession() {
        prefs.edit().clear().apply();
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
