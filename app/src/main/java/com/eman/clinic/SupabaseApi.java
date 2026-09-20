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

/** Minimal HTTPS client for Supabase Auth and PostgREST. */
public final class SupabaseApi {
    private final AuthStore auth;

    public SupabaseApi(Context context) {
        auth = new AuthStore(context);
    }

    public AuthStore auth() { return auth; }

    public boolean signIn(String email, String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("email", email.trim());
        body.put("password", password);
        Response r = raw("POST", "/auth/v1/token?grant_type=password", body.toString(), false, null, false);
        if (r.code < 200 || r.code >= 300) throw new IOException(errorMessage(r));
        saveSession(new JSONObject(r.body));
        return true;
    }

    /** Returns true when signup immediately produced a session; false means email confirmation is required. */
    public boolean signUp(String email, String password) throws Exception {
        JSONObject body = new JSONObject();
        body.put("email", email.trim());
        body.put("password", password);
        Response r = raw("POST", "/auth/v1/signup", body.toString(), false, null, false);
        if (r.code < 200 || r.code >= 300) throw new IOException(errorMessage(r));
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
            Response r = raw("POST", "/auth/v1/token?grant_type=refresh_token", body.toString(), false, null, false);
            if (r.code < 200 || r.code >= 300) return false;
            saveSession(new JSONObject(r.body));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean resolveOrCreateClinic(String requestedName) throws Exception {
        Response get = request("GET", "/rest/v1/clinics?select=id,name&order=created_at.asc&limit=1", null, null);
        if (get.code >= 200 && get.code < 300) {
            JSONArray arr = new JSONArray(get.body);
            if (arr.length() > 0) {
                JSONObject c = arr.getJSONObject(0);
                auth.saveClinic(c.getString("id"), c.optString("name", "العيادة"));
                return true;
            }
        } else if (get.code != 401) {
            throw new IOException(errorMessage(get));
        }

        String clinicName = requestedName == null ? "" : requestedName.trim();
        if (clinicName.length() < 2) return false;
        if (auth.userId().isEmpty()) return false;

        JSONObject body = new JSONObject();
        body.put("name", clinicName);
        body.put("owner_user_id", auth.userId());
        Response create = request("POST", "/rest/v1/clinics", body.toString(), "return=representation");
        if (create.code < 200 || create.code >= 300) throw new IOException(errorMessage(create));
        JSONArray created = new JSONArray(create.body);
        if (created.length() == 0) return false;
        JSONObject clinic = created.getJSONObject(0);
        String clinicId = clinic.getString("id");

        JSONObject member = new JSONObject();
        member.put("clinic_id", clinicId);
        member.put("user_id", auth.userId());
        member.put("role", "owner");
        member.put("active", true);
        Response membership = request("POST", "/rest/v1/clinic_members?on_conflict=clinic_id,user_id", member.toString(), "resolution=merge-duplicates,return=minimal");
        if (membership.code < 200 || membership.code >= 300) throw new IOException(errorMessage(membership));
        auth.saveClinic(clinicId, clinic.optString("name", clinicName));
        auth.clearPendingClinicName();
        return true;
    }

    public boolean upsertPatient(String clinicId, String deviceId, JSONObject local) throws Exception {
        JSONObject body = new JSONObject(local.toString());
        body.put("clinic_id", clinicId);
        body.put("source_device_id", deviceId);
        Response r = request("POST", "/rest/v1/patients?on_conflict=clinic_id,sync_key", body.toString(), "resolution=merge-duplicates,return=minimal");
        return r.code >= 200 && r.code < 300;
    }

    public boolean upsertVisit(String clinicId, String deviceId, JSONObject local) throws Exception {
        String patientSyncKey = local.optString("patient_sync_key", "");
        String patientId = remotePatientId(clinicId, patientSyncKey);
        if (patientId.isEmpty()) return false;
        JSONObject body = new JSONObject(local.toString());
        body.remove("patient_sync_key");
        body.put("clinic_id", clinicId);
        body.put("patient_id", patientId);
        body.put("source_device_id", deviceId);
        Response r = request("POST", "/rest/v1/visits?on_conflict=clinic_id,sync_key", body.toString(), "resolution=merge-duplicates,return=minimal");
        return r.code >= 200 && r.code < 300;
    }

    public boolean upsertPayment(String clinicId, String deviceId, JSONObject local) throws Exception {
        String visitSyncKey = local.optString("visit_sync_key", "");
        String visitId = remoteVisitId(clinicId, visitSyncKey);
        if (visitId.isEmpty()) return false;
        JSONObject body = new JSONObject(local.toString());
        body.remove("visit_sync_key");
        body.put("clinic_id", clinicId);
        body.put("visit_id", visitId);
        body.put("source_device_id", deviceId);
        Response r = request("POST", "/rest/v1/payments?on_conflict=clinic_id,sync_key", body.toString(), "resolution=merge-duplicates,return=minimal");
        return r.code >= 200 && r.code < 300;
    }

    public boolean upsertDayClosure(String clinicId, String deviceId, JSONObject local) throws Exception {
        JSONObject body = new JSONObject(local.toString());
        body.put("clinic_id", clinicId);
        body.put("source_device_id", deviceId);
        Response r = request("POST", "/rest/v1/day_closures?on_conflict=clinic_id,day", body.toString(), "resolution=merge-duplicates,return=minimal");
        return r.code >= 200 && r.code < 300;
    }

    private String remotePatientId(String clinicId, String syncKey) throws Exception {
        return remoteId("patients", clinicId, syncKey);
    }

    private String remoteVisitId(String clinicId, String syncKey) throws Exception {
        return remoteId("visits", clinicId, syncKey);
    }

    private String remoteId(String table, String clinicId, String syncKey) throws Exception {
        String path = "/rest/v1/" + table + "?select=id&clinic_id=eq." + enc(clinicId) + "&sync_key=eq." + enc(syncKey) + "&limit=1";
        Response r = request("GET", path, null, null);
        if (r.code < 200 || r.code >= 300) return "";
        JSONArray arr = new JSONArray(r.body);
        return arr.length() == 0 ? "" : arr.getJSONObject(0).optString("id", "");
    }

    public JSONArray pullPatients(String clinicId, String cursor) throws Exception {
        String path = "/rest/v1/patients?select=sync_key,card_no,full_name,phone,gender,created_at,updated_at&clinic_id=eq." + enc(clinicId)
                + cursorFilter(cursor) + "&order=updated_at.asc&limit=1000";
        return getArray(path);
    }

    public JSONArray pullVisits(String clinicId, String cursor) throws Exception {
        String select = "sync_key,visit_type,status,fee,paid_amount,complaint,exam,diagnosis,labs,treatment,followup,created_at,started_at,completed_at,updated_at,patient:patients(sync_key)";
        String path = "/rest/v1/visits?select=" + enc(select) + "&clinic_id=eq." + enc(clinicId)
                + cursorFilter(cursor) + "&order=updated_at.asc&limit=1000";
        return getArray(path);
    }

    public JSONArray pullPayments(String clinicId, String cursor) throws Exception {
        String select = "sync_key,amount,method,created_at,updated_at,visit:visits(sync_key)";
        String path = "/rest/v1/payments?select=" + enc(select) + "&clinic_id=eq." + enc(clinicId)
                + cursorFilter(cursor) + "&order=updated_at.asc&limit=1000";
        return getArray(path);
    }

    public JSONArray pullDayClosures(String clinicId, String cursor) throws Exception {
        String select = "sync_key,day,total_visits,total_charges,total_paid,total_waived,outstanding,closed_at,updated_at";
        String path = "/rest/v1/day_closures?select=" + enc(select) + "&clinic_id=eq." + enc(clinicId)
                + cursorFilter(cursor) + "&order=updated_at.asc&limit=1000";
        return getArray(path);
    }

    private JSONArray getArray(String path) throws Exception {
        Response r = request("GET", path, null, null);
        if (r.code < 200 || r.code >= 300) throw new IOException(errorMessage(r));
        return new JSONArray(r.body);
    }

    private static String cursorFilter(String cursor) {
        return cursor == null || cursor.isEmpty() ? "" : "&updated_at=gt." + enc(cursor);
    }

    private Response request(String method, String path, String body, String prefer) throws Exception {
        Response r = raw(method, path, body, true, prefer, false);
        if (r.code == 401 && refreshSession()) {
            r = raw(method, path, body, true, prefer, false);
        }
        return r;
    }

    private Response raw(String method, String path, String body, boolean useAuth, String prefer, boolean ignored) throws IOException {
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
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
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

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line);
        }
        return b.toString();
    }

    private static String errorMessage(Response r) {
        try {
            JSONObject j = new JSONObject(r.body);
            String m = j.optString("msg", "");
            if (m.isEmpty()) m = j.optString("message", "");
            if (m.isEmpty()) m = j.optString("error_description", "");
            return m.isEmpty() ? "HTTP " + r.code : m;
        } catch (Exception e) {
            return "HTTP " + r.code;
        }
    }

    private static String enc(String value) {
        try { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
        catch (Exception e) { return ""; }
    }

    private static final class Response {
        final int code;
        final String body;
        Response(int code, String body) { this.code = code; this.body = body == null ? "" : body; }
    }
}
