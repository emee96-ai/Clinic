package com.eman.clinic;

import android.content.ContentValues;
import android.content.Context;
import net.zetetic.database.sqlcipher.SQLiteDatabase;

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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Cloud sync that survives timeouts and detects concurrent edits instead of silently
 * overwriting them. The durable SQLite outbox remains authoritative while offline.
 */
public final class ResilientRemoteSync {
    private static final int PUSH_LIMIT = 120;
    private static final int PAGE_SIZE = 200;

    private final Context context;
    private final SyncStore store;
    private final SupabaseApi api;
    private final AuthStore auth;
    private final ClinicDb db;

    public ResilientRemoteSync(Context context) {
        this.context = context.getApplicationContext();
        this.store = new SyncStore(this.context);
        this.api = new SupabaseApi(this.context);
        this.auth = api.auth();
        this.db = new ClinicDb(this.context);
        installConflictStore();
    }

    /** Returns the number of new conflicts preserved during this sync pass. */
    public int syncOnce() throws Exception {
        if (!auth.hasRemoteIdentity()) return 0;
        if (!api.refreshEntitlement()) return 0;

        String clinicId = auth.clinicId();
        ensureCardNumberRange();
        List<SyncStore.SyncItem> pending = store.pending(PUSH_LIMIT);
        Map<String, JSONObject> versions = loadVersions(clinicId, pending);
        int conflicts = 0;

        for (SyncStore.SyncItem item : pending) {
            store.markSyncing(item);
            try {
                JSONObject payload = new JSONObject(item.payload);
                JSONObject remote = "day_closure".equals(item.entityType)
                        ? fetchClosureVersion(clinicId, payload.optString("day", ""))
                        : versions.get(versionMapKey(item.entityType, item.syncKey));

                String localChangeId = localChangeId(item);
                String baseChangeId = store.meta(baseKey(item.entityType, item.syncKey));
                boolean remoteExists = remote != null;
                String remoteChangeId = remoteExists ? remote.optString("client_change_id", "") : "";

                SyncConflictPolicy.Decision decision = SyncConflictPolicy.decide(
                        remoteExists, remoteChangeId, baseChangeId, localChangeId);

                if (decision == SyncConflictPolicy.Decision.CONFLICT) {
                    preserveConflict(item, clinicId, payload, remoteChangeId);
                    conflicts++;
                    continue;
                }

                String originDevice = originDevice(item);
                JSONObject pushed = pushEntity(item.entityType, clinicId, originDevice,
                        localChangeId, payload, remote);
                if (pushed == null) throw new IOException("sync write was not accepted");
                store.putMeta(baseKey(item.entityType, item.syncKey), localChangeId);
                store.setRemoteVersion(item.entityType, item.syncKey,
                        pushed.optLong("record_version", remote == null ? 1 : remote.optLong("record_version", 1)));
                if ("patient".equals(item.entityType))
                    store.updatePatientCard(item.syncKey, pushed.optInt("card_no", payload.optInt("card_no", 0)));
                store.markSynced(item);
            } catch (SyncConflictException conflict) {
                JSONObject payload = new JSONObject(item.payload);
                preserveConflict(item, clinicId, payload, "");
                conflicts++;
            } catch (Exception error) {
                store.markFailed(item, error.getMessage());
            }
        }

        pullPatients(clinicId);
        pullVisits(clinicId);
        pullPayments(clinicId);
        pullClosures(clinicId);
        store.noteSyncSuccess();
        return conflicts;
    }

    private Map<String, JSONObject> loadVersions(String clinicId, List<SyncStore.SyncItem> items) throws Exception {
        Map<String, List<String>> grouped = new HashMap<>();
        for (SyncStore.SyncItem item : items) {
            if ("day_closure".equals(item.entityType)) continue;
            grouped.computeIfAbsent(item.entityType, k -> new ArrayList<>()).add(item.syncKey);
        }
        Map<String, JSONObject> out = new HashMap<>();
        for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
            String table = tableFor(e.getKey());
            if (table.isEmpty() || e.getValue().isEmpty()) continue;
            String versionFields = "patient".equals(e.getKey())
                    ? "sync_key,client_change_id,source_device_id,updated_at,record_version,card_no"
                    : "sync_key,client_change_id,source_device_id,updated_at,record_version";
            for (int from = 0; from < e.getValue().size(); from += 40) {
                int to = Math.min(from + 40, e.getValue().size());
                StringBuilder in = new StringBuilder();
                for (int i = from; i < to; i++) {
                    if (in.length() > 0) in.append(',');
                    in.append(e.getValue().get(i));
                }
                String path = "/rest/v1/" + table
                        + "?select=" + versionFields
                        + "&clinic_id=eq." + enc(clinicId)
                        + "&sync_key=in.(" + in + ")";
                JSONArray rows = getArray(path);
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.getJSONObject(i);
                    out.put(versionMapKey(e.getKey(), row.optString("sync_key", "")), row);
                }
            }
        }
        return out;
    }

    private JSONObject fetchClosureVersion(String clinicId, String day) throws Exception {
        if (day == null || day.isEmpty()) return null;
        String path = "/rest/v1/day_closures?select=sync_key,client_change_id,source_device_id,updated_at,record_version"
                + "&clinic_id=eq." + enc(clinicId) + "&day=eq." + enc(day) + "&limit=1";
        JSONArray a = getArray(path);
        return a.length() == 0 ? null : a.getJSONObject(0);
    }

    private JSONObject pushEntity(String type, String clinicId, String originDevice,
                                  String changeId, JSONObject local, JSONObject remote) throws Exception {
        if ("patient".equals(type)) return pushPatient(clinicId, originDevice, changeId, local, remote);
        if ("visit".equals(type)) return pushVisit(clinicId, originDevice, changeId, local, remote);
        if ("payment".equals(type)) return pushPayment(clinicId, originDevice, changeId, local, remote);
        if ("day_closure".equals(type)) return pushClosure(clinicId, originDevice, changeId, local, remote);
        return null;
    }

    private JSONObject pushPatient(String clinicId, String deviceId, String changeId, JSONObject local, JSONObject remote) throws Exception {
        boolean identityAllowed = auth.can("edit_patients");
        boolean clinicalAllowed = auth.can("edit_clinical");
        if (!identityAllowed && !clinicalAllowed) return null;
        JSONObject body = new JSONObject(local.toString());
        stripMedical(body);
        body.put("clinic_id", clinicId);
        body.put("source_device_id", deviceId);
        body.put("client_change_id", changeId);
        JSONObject written = versionedWrite("patients", clinicId, local.optString("sync_key", ""),
                remote, changeId, body);
        if (clinicalAllowed && !api.updatePatientMedicalSummary(clinicId, local)) return null;
        return written;
    }

    private JSONObject pushVisit(String clinicId, String deviceId, String changeId, JSONObject local, JSONObject remote) throws Exception {
        if (!(auth.can("register_visits") || auth.can("manage_queue") || auth.can("edit_clinical"))) return null;
        String patientId = remoteId("patients", clinicId, local.optString("patient_sync_key", ""));
        if (patientId.isEmpty()) return null;

        JSONObject body = new JSONObject(local.toString());
        body.remove("patient_sync_key");
        String[] clinical = {"complaint", "exam", "diagnosis", "labs", "treatment", "followup"};
        for (String key : clinical) body.remove(key);
        body.put("clinic_id", clinicId);
        body.put("patient_id", patientId);
        body.put("source_device_id", deviceId);
        body.put("client_change_id", changeId);
        JSONObject written = versionedWrite("visits", clinicId, local.optString("sync_key", ""),
                remote, changeId, body);

        if (auth.can("edit_clinical")) {
            String visitId = remoteId("visits", clinicId, local.optString("sync_key", ""));
            if (visitId.isEmpty()) return null;
            JSONObject clinicalBody = new JSONObject();
            clinicalBody.put("clinic_id", clinicId);
            clinicalBody.put("visit_id", visitId);
            clinicalBody.put("complaint", local.optString("complaint", ""));
            clinicalBody.put("exam", local.optString("exam", ""));
            clinicalBody.put("diagnosis", local.optString("diagnosis", ""));
            clinicalBody.put("labs", local.optString("labs", ""));
            clinicalBody.put("treatment", local.optString("treatment", ""));
            clinicalBody.put("followup", local.optString("followup", ""));
            clinicalBody.put("updated_by", auth.userId());
            Response clinicalResult = request("POST", "/rest/v1/clinical_records?on_conflict=visit_id",
                    clinicalBody.toString(), "resolution=merge-duplicates,return=minimal");
            if (!ok(clinicalResult)) return null;
        }
        return written;
    }

    private JSONObject pushPayment(String clinicId, String deviceId, String changeId, JSONObject local, JSONObject remote) throws Exception {
        if (!auth.can("record_payments")) return null;
        String visitId = remoteId("visits", clinicId, local.optString("visit_sync_key", ""));
        if (visitId.isEmpty()) return null;
        JSONObject body = new JSONObject(local.toString());
        body.remove("visit_sync_key");
        body.put("clinic_id", clinicId);
        body.put("visit_id", visitId);
        body.put("source_device_id", deviceId);
        body.put("client_change_id", changeId);
        return versionedWrite("payments", clinicId, local.optString("sync_key", ""),
                remote, changeId, body);
    }

    private JSONObject pushClosure(String clinicId, String deviceId, String changeId, JSONObject local, JSONObject remote) throws Exception {
        if (!auth.can("close_day")) return null;
        JSONObject body = new JSONObject(local.toString());
        body.put("clinic_id", clinicId);
        body.put("source_device_id", deviceId);
        body.put("client_change_id", changeId);
        return versionedWrite("day_closures", clinicId, local.optString("sync_key", ""),
                remote, changeId, body);
    }

    private JSONObject versionedWrite(String table, String clinicId, String syncKey,
                                      JSONObject remote, String changeId, JSONObject body) throws Exception {
        if (remote != null && changeId.equals(remote.optString("client_change_id", ""))) return remote;

        Response response;
        String returning = "patients".equals(table)
                ? "sync_key,record_version,card_no" : "sync_key,record_version";
        if (remote == null) {
            body.put("record_version", 1);
            response = request("POST", "/rest/v1/" + table + "?select=" + returning,
                    body.toString(), "return=representation");
        } else {
            long expected = store.remoteVersion(entityForTable(table), syncKey);
            if (expected <= 0) expected = remote.optLong("record_version", 1);
            body.put("record_version", expected + 1);
            String path = "/rest/v1/" + table + "?select=" + returning
                    + "&clinic_id=eq." + enc(clinicId)
                    + "&sync_key=eq." + enc(syncKey)
                    + "&record_version=eq." + expected;
            response = request("PATCH", path, body.toString(), "return=representation");
        }
        if (response.code == 409) {
            // A timeout or a simultaneous LAN-origin upload may mean the same logical
            // change already committed. Re-read before declaring a real conflict.
            JSONObject committed = fetchVersionRow(table, clinicId, syncKey);
            if (committed != null
                    && changeId.equals(committed.optString("client_change_id", ""))) {
                return committed;
            }
            throw new SyncConflictException();
        }
        if (response.code == 400 && response.body.contains("sync_version_conflict"))
            throw new SyncConflictException();
        if (!ok(response)) throw new IOException(errorMessage(response));
        JSONArray rows = new JSONArray(response.body);
        if (rows.length() == 0) throw new SyncConflictException();
        return rows.getJSONObject(0);
    }

    private JSONObject fetchVersionRow(String table, String clinicId, String syncKey) throws Exception {
        String fields = "patients".equals(table)
                ? "sync_key,client_change_id,record_version,card_no"
                : "sync_key,client_change_id,record_version";
        JSONArray rows = getArray("/rest/v1/" + table + "?select=" + fields
                + "&clinic_id=eq." + enc(clinicId)
                + "&sync_key=eq." + enc(syncKey) + "&limit=1");
        return rows.length() == 0 ? null : rows.getJSONObject(0);
    }

    private void preserveConflict(SyncStore.SyncItem item, String clinicId,
                                  JSONObject payload, String knownRemoteChangeId) throws Exception {
        JSONObject fullRemote = fetchRemoteEntity(item.entityType, clinicId, item.syncKey, payload);
        if (fullRemote == null) throw new IOException("remote conflict row unavailable");
        recordConflict(item, fullRemote);
        store.markSynced(item);
        applyRemote(item.entityType, fullRemote);
        String remoteKey = fullRemote.optString("sync_key", item.syncKey);
        store.putMeta(baseKey(item.entityType, remoteKey),
                fullRemote.optString("client_change_id", knownRemoteChangeId));
        store.setRemoteVersion(item.entityType, remoteKey, fullRemote.optLong("record_version", 1));
    }

    private void ensureCardNumberRange() {
        if (!auth.can("edit_patients") || store.remainingCardNumbers() >= 20) return;
        try {
            JSONObject range = api.reservePatientCardNumbers(store.deviceId(), 100);
            store.saveCardRange(range.optLong("range_start", 0), range.optLong("range_end", -1));
        } catch (Exception ignored) {
            // Registration remains available with temporary negative numbers while offline.
        }
    }

    private String entityForTable(String table) {
        if ("patients".equals(table)) return "patient";
        if ("visits".equals(table)) return "visit";
        if ("payments".equals(table)) return "payment";
        return "day_closure";
    }

    private String remoteId(String table, String clinicId, String syncKey) throws Exception {
        if (syncKey == null || syncKey.isEmpty()) return "";
        JSONArray a = getArray("/rest/v1/" + table + "?select=id&clinic_id=eq." + enc(clinicId)
                + "&sync_key=eq." + enc(syncKey) + "&limit=1");
        return a.length() == 0 ? "" : a.getJSONObject(0).optString("id", "");
    }

    private JSONObject fetchRemoteEntity(String type, String clinicId, String syncKey, JSONObject local) throws Exception {
        String path;
        if ("patient".equals(type)) {
            String select = "sync_key,card_no,full_name,phone,gender,created_at,updated_at,client_change_id,source_device_id,record_version,medical:patient_medical_profiles(age_text,allergies,chronic_conditions,current_medications)";
            path = "/rest/v1/patients?select=" + enc(select)
                    + "&clinic_id=eq." + enc(clinicId) + "&sync_key=eq." + enc(syncKey) + "&limit=1";
        } else if ("visit".equals(type)) {
            String select = "sync_key,visit_type,status,fee,paid_amount,created_at,started_at,completed_at,updated_at,client_change_id,source_device_id,record_version,patient:patients(sync_key),clinical:clinical_records(complaint,exam,diagnosis,labs,treatment,followup)";
            path = "/rest/v1/visits?select=" + enc(select) + "&clinic_id=eq." + enc(clinicId)
                    + "&sync_key=eq." + enc(syncKey) + "&limit=1";
        } else if ("payment".equals(type)) {
            String select = "sync_key,amount,method,created_at,updated_at,client_change_id,source_device_id,record_version,visit:visits(sync_key)";
            path = "/rest/v1/payments?select=" + enc(select) + "&clinic_id=eq." + enc(clinicId)
                    + "&sync_key=eq." + enc(syncKey) + "&limit=1";
        } else if ("day_closure".equals(type)) {
            String day = local.optString("day", "");
            path = "/rest/v1/day_closures?select=sync_key,day,total_visits,total_charges,total_paid,total_waived,outstanding,closed_at,updated_at,client_change_id,source_device_id,record_version"
                    + "&clinic_id=eq." + enc(clinicId) + "&day=eq." + enc(day) + "&limit=1";
        } else return null;
        JSONArray a = getArray(path);
        if (a.length() == 0) return null;
        JSONObject row = a.getJSONObject(0);
        if ("patient".equals(type)) flattenMedical(row);
        if ("visit".equals(type)) flattenVisit(row);
        return row;
    }

    private void pullPatients(String clinicId) throws Exception {
        pullPaged("patient", "patients_cursor", clinicId,
                "patients", "sync_key,card_no,full_name,phone,gender,created_at,updated_at,client_change_id,source_device_id,record_version,medical:patient_medical_profiles(age_text,allergies,chronic_conditions,current_medications)");
    }

    private void pullVisits(String clinicId) throws Exception {
        if (!(auth.can("manage_queue") || auth.can("view_clinical"))) return;
        String select = "sync_key,visit_type,status,fee,paid_amount,created_at,started_at,completed_at,updated_at,client_change_id,source_device_id,record_version,patient:patients(sync_key),clinical:clinical_records(complaint,exam,diagnosis,labs,treatment,followup)";
        pullPaged("visit", "visits_cursor", clinicId, "visits", select);
    }

    private void pullPayments(String clinicId) throws Exception {
        if (!(auth.can("view_finance") || auth.can("record_payments"))) return;
        String select = "sync_key,amount,method,created_at,updated_at,client_change_id,source_device_id,record_version,visit:visits(sync_key)";
        pullPaged("payment", "payments_cursor", clinicId, "payments", select);
    }

    private void pullClosures(String clinicId) throws Exception {
        if (!(auth.can("view_finance") || auth.can("close_day"))) return;
        String select = "sync_key,day,total_visits,total_charges,total_paid,total_waived,outstanding,closed_at,updated_at,client_change_id,source_device_id,record_version";
        pullPaged("day_closure", "closures_cursor", clinicId, "day_closures", select);
    }

    private void pullPaged(String type, String cursorKey, String clinicId, String table, String select) throws Exception {
        if ("patient".equals(type) && !auth.can("view_patients")) return;
        String cursor = store.meta(cursorKey);
        String cursorSyncKey = store.meta(cursorKey + "_sync_key");
        String max = cursor;
        String maxSyncKey = cursorSyncKey;
        while (true) {
            String path = "/rest/v1/" + table + "?select=" + enc(select)
                    + "&clinic_id=eq." + enc(clinicId)
                    + cursorFilter(cursor, cursorSyncKey)
                    + "&order=updated_at.asc,sync_key.asc&limit=" + PAGE_SIZE;
            JSONArray rows = getArray(path);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                if ("patient".equals(type)) flattenMedical(row);
                if ("visit".equals(type)) flattenVisit(row);
                String syncKey = row.optString("sync_key", "");
                Long localId = store.localIdForKey(type, syncKey);
                boolean dirty = localId != null && store.isDirty(type, localId);
                if (!dirty) {
                    applyRemote(type, row);
                    store.putMeta(baseKey(type, syncKey), row.optString("client_change_id", ""));
                    store.setRemoteVersion(type, syncKey, row.optLong("record_version", 1));
                }
                String updated = row.optString("updated_at", "");
                if (!updated.isEmpty()) {
                    max = updated;
                    maxSyncKey = syncKey;
                }
            }
            if (rows.length() < PAGE_SIZE) break;
            cursor = max;
            cursorSyncKey = maxSyncKey;
        }
        if (max != null && !max.isEmpty()) {
            store.putMeta(cursorKey, max);
            store.putMeta(cursorKey + "_sync_key", maxSyncKey == null ? "" : maxSyncKey);
        }
    }

    private void flattenVisit(JSONObject row) throws Exception {
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

    private void flattenMedical(JSONObject row) throws Exception {
        JSONObject medical = row.optJSONObject("medical");
        if (medical == null) {
            JSONArray values = row.optJSONArray("medical");
            if (values != null && values.length() > 0) medical = values.optJSONObject(0);
        }
        if (medical != null && auth.can("view_clinical")) {
            row.put("age_text", medical.optString("age_text", ""));
            row.put("allergies", medical.optString("allergies", ""));
            row.put("chronic_conditions", medical.optString("chronic_conditions", ""));
            row.put("current_medications", medical.optString("current_medications", ""));
        } else {
            row.put("age_text", ""); row.put("allergies", "");
            row.put("chronic_conditions", ""); row.put("current_medications", "");
        }
        row.remove("medical");
    }

    private static void stripMedical(JSONObject body) {
        body.remove("age_text"); body.remove("allergies");
        body.remove("chronic_conditions"); body.remove("current_medications");
    }

    private void applyRemote(String type, JSONObject row) {
        if ("patient".equals(type)) store.applyRemotePatient(row);
        else if ("visit".equals(type)) store.applyRemoteVisit(row);
        else if ("payment".equals(type)) store.applyRemotePayment(row);
        else if ("day_closure".equals(type)) store.applyRemoteDayClosure(row);
    }

    private String localChangeId(SyncStore.SyncItem item) {
        return originDevice(item) + ":" + item.changedAt;
    }

    private String originDevice(SyncStore.SyncItem item) {
        String version = store.meta("lan_v_" + item.entityType + "_" + item.syncKey);
        String origin = store.meta("lan_o_" + item.entityType + "_" + item.syncKey);
        if (item.changedAt != null && item.changedAt.equals(version) && origin != null && !origin.isEmpty()) return origin;
        return store.deviceId();
    }

    private String baseKey(String type, String syncKey) { return "cloud_base_" + type + "_" + syncKey; }
    private String versionMapKey(String type, String syncKey) { return type + ":" + syncKey; }

    private void installConflictStore() {
        db.getWritableDatabase().execSQL("CREATE TABLE IF NOT EXISTS sync_conflicts (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, entity_type TEXT NOT NULL, sync_key TEXT NOT NULL, " +
                "local_payload TEXT NOT NULL, remote_payload TEXT NOT NULL, remote_version INTEGER NOT NULL DEFAULT 0, detected_at TEXT NOT NULL, resolved INTEGER NOT NULL DEFAULT 0)");
        db.getWritableDatabase().execSQL("CREATE INDEX IF NOT EXISTS idx_sync_conflicts_open ON sync_conflicts(resolved,detected_at)");
    }

    private void recordConflict(SyncStore.SyncItem item, JSONObject remote) {
        SQLiteDatabase writable = db.getWritableDatabase();
        writable.delete("sync_conflicts", "entity_type=? AND sync_key=? AND resolved=0",
                new String[]{item.entityType, item.syncKey});
        ContentValues v = new ContentValues();
        v.put("entity_type", item.entityType);
        v.put("sync_key", item.syncKey);
        v.put("local_payload", item.payload == null ? "{}" : item.payload);
        v.put("remote_payload", remote == null ? "{}" : remote.toString());
        v.put("remote_version", remote == null ? 0 : remote.optLong("record_version", 0));
        v.put("detected_at", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        v.put("resolved", 0);
        writable.insert("sync_conflicts", null, v);
        store.putMeta("last_sync_conflict_at", String.valueOf(System.currentTimeMillis()));
    }

    private String tableFor(String type) {
        if ("patient".equals(type)) return "patients";
        if ("visit".equals(type)) return "visits";
        if ("payment".equals(type)) return "payments";
        if ("day_closure".equals(type)) return "day_closures";
        return "";
    }

    private String cursorFilter(String cursor, String syncKey) {
        if (cursor == null || cursor.isEmpty()) return "";
        if (syncKey == null || syncKey.isEmpty()) return "&updated_at=gt." + enc(cursor);
        String expression = "(updated_at.gt." + cursor + ",and(updated_at.eq." + cursor
                + ",sync_key.gt." + syncKey + "))";
        return "&or=" + enc(expression);
    }

    private JSONArray getArray(String path) throws Exception {
        Response r = request("GET", path, null, null);
        if (!ok(r)) throw new IOException(errorMessage(r));
        return new JSONArray(r.body);
    }

    private Response request(String method, String path, String body, String prefer) throws Exception {
        Response r = raw(method, path, body, prefer);
        if (r.code == 401 && api.refreshSession()) r = raw(method, path, body, prefer);
        if (!ok(r) && r.code >= 500) throw new IOException("temporary server error " + r.code);
        return r;
    }

    private Response raw(String method, String path, String body, String prefer) throws IOException {
        URL url = new URL(SupabaseConfig.URL + path);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("apikey", SupabaseConfig.PUBLISHABLE_KEY);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Content-Type", "application/json");
        if (!auth.accessToken().isEmpty()) c.setRequestProperty("Authorization", "Bearer " + auth.accessToken());
        if (prefer != null && !prefer.isEmpty()) c.setRequestProperty("Prefer", prefer);
        if (body != null) {
            c.setDoOutput(true);
            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? c.getInputStream() : c.getErrorStream();
        String text = readAll(stream);
        c.disconnect();
        return new Response(code, text);
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

    private static boolean ok(Response r) { return r.code >= 200 && r.code < 300; }

    private static String errorMessage(Response r) {
        try {
            JSONObject o = new JSONObject(r.body);
            String m = o.optString("message", o.optString("error_description", o.optString("error", "")));
            if (!m.isEmpty()) return m;
        } catch (Exception ignored) {}
        return "HTTP " + r.code;
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return ""; }
    }

    private static final class Response {
        final int code;
        final String body;
        Response(int code, String body) { this.code = code; this.body = body == null ? "" : body; }
    }

    private static final class SyncConflictException extends Exception {}
}
