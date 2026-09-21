package com.eman.clinic;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Minimal HTTPS client for Supabase Auth, team access and PostgREST sync. */
public final class SupabaseApi {
    private final Context context;
    private final AuthStore auth;

    public SupabaseApi(Context context) {
        this.context = context.getApplicationContext();
        auth = new AuthStore(this.context);
    }

    public AuthStore auth() { return auth; }

    public boolean signIn(String email, String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("email", email.trim());
        body.put("password", password);
        Response r = raw("POST", "/auth/v1/token?grant_type=password", body.toString(), false, null);
        if (!ok(r)) throw new IOException(errorMessage(r));
        saveSession(new JSONObject(r.body));
        return true;
    }

    public boolean signUp(String email, String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("email", email.trim());
        body.put("password", password);
        Response r = raw("POST", "/auth/v1/signup", body.toString(), false, null);
        if (!ok(r)) throw new IOException(errorMessage(r));
        JSONObject json = new JSONObject(r.body);
        String access = json.optString("access_token", "");
        if (!access.isEmpty()) {
            saveSession(json);
            return true;
        }
        JSONObject user = json.optJSONObject("user");
        if (user != null) auth.saveSession("", "", user.optString("id", ""));
        return false;
    }

    public boolean refreshSession() {
        try {
            String refresh = auth.refreshToken();
            if (refresh.isEmpty()) return false;
            JSONObject body = new JSONObject();
            body.put("refresh_token", refresh);
            Response r = raw("POST", "/auth/v1/token?grant_type=refresh_token", body.toString(), false, null);
            if (!ok(r)) return false;
            saveSession(new JSONObject(r.body));
            return true;
        } catch (Exception e) { return false; }
    }

    /** Resolves the signed-in user's active clinic membership. */
    public boolean resolveMembership() throws Exception {
        if (auth.userId().isEmpty()) return false;
        String select = "clinic_id,role,permissions,display_name,clinic:clinics(id,name)";
        String path = "/rest/v1/clinic_members?select=" + enc(select)
                + "&user_id=eq." + enc(auth.userId()) + "&active=eq.true&order=created_at.asc&limit=1";
        Response r = request("GET", path, null, null);
        if (!ok(r)) throw new IOException(errorMessage(r));
        JSONArray rows = new JSONArray(r.body);
        if (rows.length() == 0) return false;
        JSONObject m = rows.getJSONObject(0);
        JSONObject c = m.optJSONObject("clinic");
        if (c == null) return false;
        auth.saveClinic(c.optString("id", m.optString("clinic_id", "")), c.optString("name", "العيادة"));
        auth.saveMembership(m.optString("role", ""), m.optJSONObject("permissions"), m.optString("display_name", ""));
        refreshEntitlementQuietly();
        return true;
    }

    /** Creates a new clinic owned by the signed-in doctor. */
    public boolean createDoctorClinic(String requestedName, String displayName) throws Exception {
        String clinicName = requestedName == null ? "" : requestedName.trim();
        if (clinicName.length() < 2 || auth.userId().isEmpty()) return false;

        JSONObject body = new JSONObject();
        body.put("name", clinicName);
        body.put("owner_user_id", auth.userId());
        Response create = request("POST", "/rest/v1/clinics", body.toString(), "return=representation");
        if (!ok(create)) throw new IOException(errorMessage(create));
        JSONArray created = new JSONArray(create.body);
        if (created.length() == 0) return false;
        JSONObject clinic = created.getJSONObject(0);
        String clinicId = clinic.getString("id");

        JSONObject member = new JSONObject();
        member.put("clinic_id", clinicId);
        member.put("user_id", auth.userId());
        member.put("role", "owner_doctor");
        member.put("active", true);
        member.put("display_name", displayName == null ? "" : displayName.trim());
        member.put("permissions", ownerPermissions());
        Response membership = request("POST", "/rest/v1/clinic_members?on_conflict=clinic_id,user_id",
                member.toString(), "resolution=merge-duplicates,return=minimal");
        if (!ok(membership)) throw new IOException(errorMessage(membership));
        auth.saveClinic(clinicId, clinic.optString("name", clinicName));
        auth.saveMembership("owner_doctor", ownerPermissions(), displayName);
        auth.clearPendingClinicName();
        refreshEntitlementQuietly();
        return true;
    }

    /** Backward-compatible owner flow. */
    public boolean resolveOrCreateClinic(String requestedName) throws Exception {
        if (resolveMembership()) return true;
        if (requestedName == null || requestedName.trim().isEmpty()) return false;
        return createDoctorClinic(requestedName, "");
    }

    /** Joins an existing clinic using the doctor's invitation code. */
    public boolean acceptInvite(String code, String displayName) throws Exception {
        JSONObject body = new JSONObject();
        body.put("p_code", code == null ? "" : code.trim());
        body.put("p_display_name", displayName == null ? "" : displayName.trim());
        Response r = request("POST", "/rest/v1/rpc/accept_clinic_invite", body.toString(), null);
        if (!ok(r)) throw new IOException(errorMessage(r));
        return resolveMembership();
    }

    public JSONObject generateInvite(String role, JSONObject permissions, int expiresHours, int maxUses) throws Exception {
        JSONObject body = new JSONObject();
        body.put("p_clinic_id", auth.clinicId());
        body.put("p_role", role);
        body.put("p_permissions", permissions == null ? new JSONObject() : permissions);
        body.put("p_expires_hours", expiresHours);
        body.put("p_max_uses", maxUses);
        Response r = request("POST", "/rest/v1/rpc/generate_clinic_invite", body.toString(), null);
        if (!ok(r)) throw new IOException(errorMessage(r));
        JSONArray rows = new JSONArray(r.body);
        return rows.length() == 0 ? new JSONObject() : rows.getJSONObject(0);
    }

    public JSONArray listMembers() throws Exception {
        String select = "id,user_id,role,active,display_name,permissions,joined_at";
        String path = "/rest/v1/clinic_members?select=" + enc(select) + "&clinic_id=eq." + enc(auth.clinicId()) + "&order=created_at.asc";
        return getArray(path);
    }

    public JSONObject updateMemberAccess(String memberId, boolean active, JSONObject permissions) throws Exception {
        JSONObject body = new JSONObject();
        body.put("p_member_id", memberId);
        body.put("p_active", active);
        body.put("p_permissions", permissions == null ? new JSONObject() : permissions);
        Response r = request("POST", "/rest/v1/rpc/update_clinic_member_access", body.toString(), null);
        if (!ok(r)) throw new IOException(errorMessage(r));
        JSONObject result = parseObjectOrFirstArray(r.body);
        return result == null ? new JSONObject() : result;
    }

    public boolean refreshEntitlement() throws Exception {
        if (auth.clinicId().isEmpty()) return true;
        JSONObject body = new JSONObject();
        body.put("p_clinic_id", auth.clinicId());
        Response r = request("POST", "/rest/v1/rpc/current_clinic_entitlement", body.toString(), null);
        if (!ok(r)) throw new IOException(errorMessage(r));
        JSONArray rows = new JSONArray(r.body);
        if (rows.length() == 0) return true;
        JSONObject e = rows.getJSONObject(0);
        boolean allowed = e.optBoolean("allowed", true);
        auth.saveEntitlement(
                e.optString("plan", "trial"), e.optString("subscription_status", ""), allowed,
                e.optString("reason", ""), e.optString("trial_ends_at", ""),
                e.optString("paid_until", ""), e.optString("server_time", "")
        );
        return allowed;
    }

    private void refreshEntitlementQuietly() { try { refreshEntitlement(); } catch (Exception ignored) {} }

    public boolean upsertPatient(String clinicId, String deviceId, JSONObject local) throws Exception {
        if (!auth.can("edit_patients")) return false;
        JSONObject body = new JSONObject(local.toString());
        body.put("clinic_id", clinicId);
        body.put("source_device_id", deviceId);
        Response r = request("POST", "/rest/v1/patients?on_conflict=clinic_id,sync_key", body.toString(), "resolution=merge-duplicates,return=minimal");
        return ok(r);
    }

    public boolean upsertVisit(String clinicId, String deviceId, JSONObject local) throws Exception {
        if (!(auth.can("register_visits") || auth.can("manage_queue") || auth.can("edit_clinical"))) return false;
        String patientId = remoteId("patients", clinicId, local.optString("patient_sync_key", ""));
        if (patientId.isEmpty()) return false;

        JSONObject body = new JSONObject(local.toString());
        body.remove("patient_sync_key");
        String[] clinical = {"complaint","exam","diagnosis","labs","treatment","followup"};
        for (String key : clinical) body.remove(key);
        body.put("clinic_id", clinicId);
        body.put("patient_id", patientId);
        body.put("source_device_id", deviceId);
        Response r = request("POST", "/rest/v1/visits?on_conflict=clinic_id,sync_key", body.toString(), "resolution=merge-duplicates,return=minimal");
        if (!ok(r)) return false;

        if (auth.can("edit_clinical")) {
            String visitId = remoteId("visits", clinicId, local.optString("sync_key", ""));
            if (!visitId.isEmpty()) upsertClinicalRecord(clinicId, visitId, local);
        }
        return true;
    }

    private boolean upsertClinicalRecord(String clinicId, String visitId, JSONObject local) throws Exception {
        JSONObject body = new JSONObject();
        body.put("clinic_id", clinicId);
        body.put("visit_id", visitId);
        body.put("complaint", local.optString("complaint", ""));
        body.put("exam", local.optString("exam", ""));
        body.put("diagnosis", local.optString("diagnosis", ""));
        body.put("labs", local.optString("labs", ""));
        body.put("treatment", local.optString("treatment", ""));
        body.put("followup", local.optString("followup", ""));
        body.put("updated_by", auth.userId());
        Response r = request("POST", "/rest/v1/clinical_records?on_conflict=visit_id", body.toString(), "resolution=merge-duplicates,return=minimal");
        return ok(r);
    }

    public boolean upsertPayment(String clinicId, String deviceId, JSONObject local) throws Exception {
        if (!auth.can("record_payments")) return false;
        String visitId = remoteId("visits", clinicId, local.optString("visit_sync_key", ""));
        if (visitId.isEmpty()) return false;
        JSONObject body = new JSONObject(local.toString());
        body.remove("visit_sync_key");
        body.put("clinic_id", clinicId);
        body.put("visit_id", visitId);
        body.put("source_device_id", deviceId);
        Response r = request("POST", "/rest/v1/payments?on_conflict=clinic_id,sync_key", body.toString(), "resolution=merge-duplicates,return=minimal");
        return ok(r);
    }

    public boolean upsertDayClosure(String clinicId, String deviceId, JSONObject local) throws Exception {
        if (!auth.can("close_day")) return false;
        JSONObject body = new JSONObject(local.toString());
        body.put("clinic_id", clinicId);
        body.put("source_device_id", deviceId);
        Response r = request("POST", "/rest/v1/day_closures?on_conflict=clinic_id,day", body.toString(), "resolution=merge-duplicates,return=minimal");
        return ok(r);
    }

    private String remoteId(String table, String clinicId, String syncKey) throws Exception {
        if (syncKey == null || syncKey.isEmpty()) return "";
        String path = "/rest/v1/" + table + "?select=id&clinic_id=eq." + enc(clinicId) + "&sync_key=eq." + enc(syncKey) + "&limit=1";
        Response r = request("GET", path, null, null);
        if (!ok(r)) return "";
        JSONArray arr = new JSONArray(r.body);
        return arr.length() == 0 ? "" : arr.getJSONObject(0).optString("id", "");
    }

    public JSONArray pullPatients(String clinicId, String cursor) throws Exception {
        if (!auth.can("view_patients")) return new JSONArray();
        String path = "/rest/v1/patients?select=sync_key,card_no,full_name,phone,gender,created_at,updated_at&clinic_id=eq." + enc(clinicId)
                + cursorFilter(cursor) + "&order=updated_at.asc&limit=1000";
        return getArray(path);
    }

    public JSONArray pullVisits(String clinicId, String cursor) throws Exception {
        if (!(auth.can("manage_queue") || auth.can("view_clinical"))) return new JSONArray();
        String select = "sync_key,visit_type,status,fee,paid_amount,created_at,started_at,completed_at,updated_at,patient:patients(sync_key),clinical:clinical_records(complaint,exam,diagnosis,labs,treatment,followup)";
        String path = "/rest/v1/visits?select=" + enc(select) + "&clinic_id=eq." + enc(clinicId)
                + cursorFilter(cursor) + "&order=updated_at.asc&limit=1000";
        JSONArray rows = getArray(path);
        for (int i=0;i<rows.length();i++) {
            JSONObject row = rows.getJSONObject(i);
            JSONObject clinical = row.optJSONObject("clinical");
            if (clinical == null) {
                JSONArray a = row.optJSONArray("clinical");
                if (a != null && a.length() > 0) clinical = a.optJSONObject(0);
            }
            if (clinical != null && auth.can("view_clinical")) {
                row.put("complaint", clinical.optString("complaint", ""));
                row.put("exam", clinical.optString("exam", ""));
                row.put("diagnosis", clinical.optString("diagnosis", ""));
                row.put("labs", clinical.optString("labs", ""));
                row.put("treatment", clinical.optString("treatment", ""));
                row.put("followup", clinical.optString("followup", ""));
            } else {
                row.put("complaint", ""); row.put("exam", ""); row.put("diagnosis", "");
                row.put("labs", ""); row.put("treatment", ""); row.put("followup", "");
            }
            row.remove("clinical");
        }
        return rows;
    }

    public JSONArray pullPayments(String clinicId, String cursor) throws Exception {
        if (!(auth.can("view_finance") || auth.can("record_payments"))) return new JSONArray();
        String select = "sync_key,amount,method,created_at,updated_at,visit:visits(sync_key)";
        String path = "/rest/v1/payments?select=" + enc(select) + "&clinic_id=eq." + enc(clinicId)
                + cursorFilter(cursor) + "&order=updated_at.asc&limit=1000";
        return getArray(path);
    }

    public JSONArray pullDayClosures(String clinicId, String cursor) throws Exception {
        if (!(auth.can("view_finance") || auth.can("close_day"))) return new JSONArray();
        String select = "sync_key,day,total_visits,total_charges,total_paid,total_waived,outstanding,closed_at,updated_at";
        String path = "/rest/v1/day_closures?select=" + enc(select) + "&clinic_id=eq." + enc(clinicId)
                + cursorFilter(cursor) + "&order=updated_at.asc&limit=1000";
        return getArray(path);
    }

    private JSONArray getArray(String path) throws Exception {
        Response r = request("GET", path, null, null);
        if (!ok(r)) throw new IOException(errorMessage(r));
        return new JSONArray(r.body);
    }

    private static String cursorFilter(String cursor) {
        return cursor == null || cursor.isEmpty() ? "" : "&updated_at=gt." + enc(cursor);
    }

    private Response request(String method, String path, String body, String prefer) throws Exception {
        Response r = raw(method, path, body, true, prefer);
        if (r.code == 401 && refreshSession()) r = raw(method, path, body, true, prefer);
        return r;
    }

    private Response raw(String method, String path, String body, boolean useAuth, String prefer) throws IOException {
        URL url = new URL(SupabaseConfig.URL + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setRequestProperty("apikey", SupabaseConfig.PUBLISHABLE_KEY);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json");
        if (useAuth && !auth.accessToken().isEmpty()) c.setRequestProperty("Authorization", "Bearer " + auth.accessToken());
        if (prefer != null && !prefer.isEmpty()) c.setRequestProperty("Prefer", prefer);
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        String text = readAll(stream);
        c.disconnect();
        return new Response(code, text);
    }

    private void saveSession(JSONObject json) {
        JSONObject user = json.optJSONObject("user");
        String userId = user == null ? auth.userId() : user.optString("id", auth.userId());
        auth.saveSession(json.optString("access_token", ""), json.optString("refresh_token", ""), userId);
    }

    private static JSONObject ownerPermissions() {
        try {
            return new JSONObject("{\"view_patients\":true,\"edit_patients\":true,\"register_visits\":true,\"manage_queue\":true,\"view_clinical\":true,\"edit_clinical\":true,\"view_finance\":true,\"record_payments\":true,\"close_day\":true,\"manage_staff\":true,\"manage_invites\":true}");
        } catch (Exception e) { return new JSONObject(); }
    }

    private static JSONObject parseObjectOrFirstArray(String body) {
        try { return new JSONObject(body); } catch (Exception ignored) {}
        try { JSONArray a = new JSONArray(body); return a.length() == 0 ? null : a.getJSONObject(0); }
        catch (Exception ignored) { return null; }
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line; while ((line = r.readLine()) != null) b.append(line);
        }
        return b.toString();
    }

    private static boolean ok(Response r) { return r.code >= 200 && r.code < 300; }

    private static String errorMessage(Response r) {
        try {
            JSONObject j = new JSONObject(r.body);
            String m = j.optString("message", "");
            if (m.isEmpty()) m = j.optString("msg", "");
            if (m.isEmpty()) m = j.optString("error_description", "");
            if (m.isEmpty()) m = j.optString("details", "");
            return m.isEmpty() ? "HTTP " + r.code : m;
        } catch (Exception e) { return "HTTP " + r.code; }
    }

    private static String enc(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    private static final class Response {
        final int code; final String body;
        Response(int code, String body) { this.code = code; this.body = body == null ? "" : body; }
    }
}
