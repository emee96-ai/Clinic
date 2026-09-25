package com.eman.clinic;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import org.json.JSONObject;

/** Stores the Clinic session, selected clinic, member access and cached subscription state. */
public final class AuthStore {
    private static final String PREF = "clinic_remote_auth";
    private final Context context;
    private final SharedPreferences prefs;

    public AuthStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
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
        context.getSharedPreferences("clinic_settings", Context.MODE_PRIVATE).edit()
                .putString("clinic_name", safe(clinicName).isEmpty() ? "العيادة" : safe(clinicName)).apply();
    }

    public void saveMembership(String role, JSONObject permissions, String displayName) {
        JSONObject effective = permissions == null ? new JSONObject() : permissions;
        String json = effective.toString();
        prefs.edit()
                .putString("member_role", safe(role))
                .putString("member_permissions", json)
                .putString("member_display_name", safe(displayName))
                .putBoolean("member_active", true)
                .putLong("membership_checked_local_ms", System.currentTimeMillis())
                .apply();

        String localRole = "RECEPTION";
        if ("substitute_doctor".equals(role) || "owner_doctor".equals(role)) localRole = "DOCTOR";
        context.getSharedPreferences("clinic_settings", Context.MODE_PRIVATE).edit()
                .putString("role", localRole)
                .putBoolean("role_chosen", true)
                .apply();
        updateTeamLauncher(can("manage_staff"));
        if (!can("view_clinical")) purgeClinicalCache();
    }

    public void markMembershipInactive() {
        prefs.edit()
                .putBoolean("member_active", false)
                .putLong("membership_checked_local_ms", System.currentTimeMillis())
                .apply();
        updateTeamLauncher(false);
        purgeClinicalCache();
    }

    private void updateTeamLauncher(boolean enabled) {
        try {
            ComponentName name = new ComponentName(context, context.getPackageName() + ".TeamLauncher");
            context.getPackageManager().setComponentEnabledSetting(
                    name,
                    enabled ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
            );
        } catch (Exception ignored) {}
    }

    private void purgeClinicalCache() {
        try {
            SyncStore store = new SyncStore(context);
            store.putMeta("suppress_tracking", "1");
            try {
                new ClinicDb(context).getWritableDatabase().execSQL(
                        "UPDATE visits SET complaint='',exam='',diagnosis='',labs='',treatment='',followup=''"
                );
            } finally {
                store.putMeta("suppress_tracking", "0");
            }
        } catch (Exception ignored) {}
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
    public String memberRole() { return prefs.getString("member_role", ""); }
    public String memberDisplayName() { return prefs.getString("member_display_name", ""); }
    public boolean isMembershipActive() { return prefs.getBoolean("member_active", !hasRemoteIdentity()); }
    public String subscriptionPlan() { return prefs.getString("subscription_plan", "trial"); }
    public String subscriptionStatus() { return prefs.getString("subscription_status", ""); }
    public String subscriptionReason() { return prefs.getString("subscription_reason", ""); }
    public String trialEndsAt() { return prefs.getString("trial_ends_at", ""); }
    public String paidUntil() { return prefs.getString("paid_until", ""); }

    public JSONObject permissions() {
        try { return new JSONObject(prefs.getString("member_permissions", "{}")); }
        catch (Exception e) { return new JSONObject(); }
    }

    public boolean can(String permission) {
        if (hasRemoteIdentity() && !prefs.getBoolean("member_active", true)) return false;
        if ("owner_doctor".equals(memberRole())) return true;
        return permissions().optBoolean(permission, false);
    }

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
        updateTeamLauncher(false);
        prefs.edit().clear().apply();
    }

    private static String safe(String value) { return value == null ? "" : value; }
}
