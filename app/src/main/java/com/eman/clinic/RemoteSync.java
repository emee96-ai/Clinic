package com.eman.clinic;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Pushes local dirty rows first, then pulls newer remote rows. */
public final class RemoteSync {
    private final SyncStore store;
    private final SupabaseApi api;
    private final AuthStore auth;

    public RemoteSync(Context context) {
        store = new SyncStore(context);
        api = new SupabaseApi(context);
        auth = api.auth();
    }

    public boolean syncOnce() throws Exception {
        if (!auth.hasRemoteIdentity()) return false;
        if (!api.refreshEntitlement()) return false;
        String clinicId = auth.clinicId();
        String deviceId = store.deviceId();

        List<SyncStore.SyncItem> pending = store.pending(300);
        for (SyncStore.SyncItem item : pending) {
            JSONObject payload = new JSONObject(item.payload);
            boolean ok;
            if ("patient".equals(item.entityType)) ok = api.upsertPatient(clinicId, deviceId, payload);
            else if ("visit".equals(item.entityType)) ok = api.upsertVisit(clinicId, deviceId, payload);
            else if ("payment".equals(item.entityType)) ok = api.upsertPayment(clinicId, deviceId, payload);
            else if ("day_closure".equals(item.entityType)) ok = api.upsertDayClosure(clinicId, deviceId, payload);
            else ok = false;
            if (ok) store.markSynced(item);
        }

        pullPatients(clinicId);
        pullVisits(clinicId);
        pullPayments(clinicId);
        pullDayClosures(clinicId);
        return true;
    }

    private void pullPatients(String clinicId) throws Exception {
        String cursor = store.meta("patients_cursor");
        JSONArray rows = api.pullPatients(clinicId, cursor);
        String max = cursor;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            store.applyRemotePatient(row);
            max = newer(max, row.optString("updated_at", ""));
        }
        if (max != null && !max.isEmpty()) store.putMeta("patients_cursor", max);
    }

    private void pullVisits(String clinicId) throws Exception {
        String cursor = store.meta("visits_cursor");
        JSONArray rows = api.pullVisits(clinicId, cursor);
        String max = cursor;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            store.applyRemoteVisit(row);
            max = newer(max, row.optString("updated_at", ""));
        }
        if (max != null && !max.isEmpty()) store.putMeta("visits_cursor", max);
    }

    private void pullPayments(String clinicId) throws Exception {
        String cursor = store.meta("payments_cursor");
        JSONArray rows = api.pullPayments(clinicId, cursor);
        String max = cursor;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            store.applyRemotePayment(row);
            max = newer(max, row.optString("updated_at", ""));
        }
        if (max != null && !max.isEmpty()) store.putMeta("payments_cursor", max);
    }

    private void pullDayClosures(String clinicId) throws Exception {
        String cursor = store.meta("closures_cursor");
        JSONArray rows = api.pullDayClosures(clinicId, cursor);
        String max = cursor;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            store.applyRemoteDayClosure(row);
            max = newer(max, row.optString("updated_at", ""));
        }
        if (max != null && !max.isEmpty()) store.putMeta("closures_cursor", max);
    }

    private static String newer(String current, String candidate) {
        if (candidate == null || candidate.isEmpty()) return current;
        if (current == null || current.isEmpty() || candidate.compareTo(current) > 0) return candidate;
        return current;
    }
}
