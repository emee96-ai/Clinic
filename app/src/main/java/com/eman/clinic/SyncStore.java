package com.eman.clinic;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Reads the durable database outbox and safely applies remote rows. */
public class SyncStore {
    private final ClinicDb helper;

    public SyncStore(Context context) {
        helper = new ClinicDb(context.getApplicationContext());
        SyncBootstrap.install(context.getApplicationContext());
    }

    public int pendingCount() {
        Cursor c = helper.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM sync_dirty", null);
        int count = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return count;
    }

    public List<SyncItem> pending(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        List<SyncItem> items = new ArrayList<>();
        Cursor dirty = helper.getReadableDatabase().rawQuery(
                "SELECT entity_type,local_id,changed_at FROM sync_dirty ORDER BY " +
                        "CASE entity_type WHEN 'patient' THEN 0 WHEN 'visit' THEN 1 WHEN 'payment' THEN 2 ELSE 3 END, changed_at ASC LIMIT " + safeLimit,
                null);
        while (dirty.moveToNext()) {
            String type = dirty.getString(0);
            long localId = dirty.getLong(1);
            String changedAt = dirty.getString(2);
            SyncItem item;
            if ("patient".equals(type)) item = patientItem(localId, changedAt);
            else if ("visit".equals(type)) item = visitItem(localId, changedAt);
            else if ("payment".equals(type)) item = paymentItem(localId, changedAt);
            else if ("day_closure".equals(type)) item = closureItem(localId, changedAt);
            else item = null;
            if (item != null) items.add(item);
        }
        dirty.close();
        return items;
    }

    private SyncItem patientItem(long id, String changedAt) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT p.id,p.card_no,p.full_name,p.phone,p.gender,p.age_text,p.allergies,p.chronic_conditions,p.current_medications,p.created_at,k.sync_key FROM patients p JOIN sync_entity_keys k ON k.entity_type='patient' AND k.local_id=p.id WHERE p.id=?",
                new String[]{String.valueOf(id)});
        if (!c.moveToFirst()) { c.close(); return null; }
        try {
            JSONObject json = new JSONObject();
            json.put("sync_key", c.getString(10));
            json.put("card_no", c.getInt(1));
            json.put("full_name", safe(c.getString(2)));
            json.put("phone", safe(c.getString(3)));
            json.put("gender", safe(c.getString(4)));
            json.put("age_text", safe(c.getString(5)));
            json.put("allergies", safe(c.getString(6)));
            json.put("chronic_conditions", safe(c.getString(7)));
            json.put("current_medications", safe(c.getString(8)));
            json.put("created_at", safe(c.getString(9)));
            SyncItem item = new SyncItem("patient", id, c.getString(10), changedAt, json.toString());
            c.close();
            return item;
        } catch (JSONException e) { c.close(); return null; }
    }

    private SyncItem visitItem(long id, String changedAt) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT v.id,v.visit_type,v.status,v.fee,v.paid_amount,v.complaint,v.exam,v.diagnosis,v.labs,v.treatment,v.followup,v.created_at,v.started_at,v.completed_at,vk.sync_key,pk.sync_key " +
                        "FROM visits v JOIN sync_entity_keys vk ON vk.entity_type='visit' AND vk.local_id=v.id JOIN sync_entity_keys pk ON pk.entity_type='patient' AND pk.local_id=v.patient_id WHERE v.id=?",
                new String[]{String.valueOf(id)});
        if (!c.moveToFirst()) { c.close(); return null; }
        try {
            JSONObject json = new JSONObject();
            json.put("sync_key", c.getString(14));
            json.put("patient_sync_key", c.getString(15));
            json.put("visit_type", safe(c.getString(1)));
            json.put("status", safe(c.getString(2)));
            json.put("fee", c.getInt(3));
            json.put("paid_amount", c.getInt(4));
            json.put("complaint", safe(c.getString(5)));
            json.put("exam", safe(c.getString(6)));
            json.put("diagnosis", safe(c.getString(7)));
            json.put("labs", safe(c.getString(8)));
            json.put("treatment", safe(c.getString(9)));
            json.put("followup", safe(c.getString(10)));
            json.put("created_at", safe(c.getString(11)));
            json.put("started_at", safe(c.getString(12)));
            json.put("completed_at", safe(c.getString(13)));
            SyncItem item = new SyncItem("visit", id, c.getString(14), changedAt, json.toString());
            c.close();
            return item;
        } catch (JSONException e) { c.close(); return null; }
    }

    private SyncItem paymentItem(long id, String changedAt) {
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT p.amount,p.method,p.created_at,pk.sync_key,vk.sync_key FROM payments p " +
                        "JOIN sync_entity_keys pk ON pk.entity_type='payment' AND pk.local_id=p.id " +
                        "JOIN sync_entity_keys vk ON vk.entity_type='visit' AND vk.local_id=p.visit_id WHERE p.id=?",
                new String[]{String.valueOf(id)});
        if (!c.moveToFirst()) { c.close(); return null; }
        try {
            JSONObject json = new JSONObject();
            json.put("sync_key", c.getString(3));
            json.put("visit_sync_key", c.getString(4));
            json.put("amount", c.getInt(0));
            json.put("method", safe(c.getString(1)));
            json.put("created_at", safe(c.getString(2)));
            SyncItem item = new SyncItem("payment", id, c.getString(3), changedAt, json.toString());
            c.close();
            return item;
        } catch (JSONException e) { c.close(); return null; }
    }

    private SyncItem closureItem(long id, String changedAt) {
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT d.day,d.total_visits,d.total_charges,d.total_paid,d.total_waived,d.outstanding,d.closed_at,k.sync_key " +
                        "FROM day_closures d JOIN sync_entity_keys k ON k.entity_type='day_closure' AND k.local_id=d.id WHERE d.id=?",
                new String[]{String.valueOf(id)});
        if (!c.moveToFirst()) { c.close(); return null; }
        try {
            JSONObject json = new JSONObject();
            json.put("sync_key", c.getString(7));
            json.put("day", safe(c.getString(0)));
            json.put("total_visits", c.getInt(1));
            json.put("total_charges", c.getInt(2));
            json.put("total_paid", c.getInt(3));
            json.put("total_waived", c.getInt(4));
            json.put("outstanding", c.getInt(5));
            json.put("closed_at", safe(c.getString(6)));
            SyncItem item = new SyncItem("day_closure", id, c.getString(7), changedAt, json.toString());
            c.close();
            return item;
        } catch (JSONException e) { c.close(); return null; }
    }

    public void markSynced(SyncItem item) {
        helper.getWritableDatabase().delete("sync_dirty", "entity_type=? AND local_id=? AND changed_at=?", new String[]{item.entityType, String.valueOf(item.localId), item.changedAt});
    }

    public String deviceId() {
        String existing = meta("device_id");
        if (!existing.isEmpty()) return existing;
        String value = UUID.randomUUID().toString();
        putMeta("device_id", value);
        return value;
    }

    public String meta(String key) {
        Cursor c = helper.getReadableDatabase().rawQuery("SELECT meta_value FROM sync_meta WHERE meta_key=?", new String[]{key});
        String value = c.moveToFirst() ? safe(c.getString(0)) : "";
        c.close();
        return value;
    }

    public void putMeta(String key, String value) {
        ContentValues cv = new ContentValues();
        cv.put("meta_key", key);
        cv.put("meta_value", value == null ? "" : value);
        helper.getWritableDatabase().insertWithOnConflict("sync_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public Long localIdForKey(String entityType, String syncKey) {
        Cursor c = helper.getReadableDatabase().rawQuery("SELECT local_id FROM sync_entity_keys WHERE entity_type=? AND sync_key=?", new String[]{entityType, syncKey});
        Long id = c.moveToFirst() ? c.getLong(0) : null;
        c.close();
        return id;
    }

    public boolean isDirty(String entityType, long localId) {
        Cursor c = helper.getReadableDatabase().rawQuery("SELECT 1 FROM sync_dirty WHERE entity_type=? AND local_id=? LIMIT 1", new String[]{entityType, String.valueOf(localId)});
        boolean dirty = c.moveToFirst();
        c.close();
        return dirty;
    }

    public void bindRemoteKey(String entityType, long localId, String syncKey) {
        ContentValues cv = new ContentValues();
        cv.put("entity_type", entityType);
        cv.put("local_id", localId);
        cv.put("sync_key", syncKey);
        helper.getWritableDatabase().insertWithOnConflict("sync_entity_keys", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public void applyRemotePatient(JSONObject row) {
        try {
            String syncKey = row.getString("sync_key");
            Long localId = localIdForKey("patient", syncKey);
            if (localId != null && isDirty("patient", localId)) return;
            suppress(true);
            ContentValues v = new ContentValues();
            v.put("card_no", row.getInt("card_no"));
            v.put("full_name", row.optString("full_name", ""));
            v.put("phone", row.optString("phone", ""));
            v.put("gender", row.optString("gender", ""));
            v.put("age_text", row.optString("age_text", ""));
            v.put("allergies", row.optString("allergies", ""));
            v.put("chronic_conditions", row.optString("chronic_conditions", ""));
            v.put("current_medications", row.optString("current_medications", ""));
            v.put("created_at", normalizeTime(row.optString("created_at", "")));
            SQLiteDatabase db = helper.getWritableDatabase();
            long id;
            if (localId == null) id = db.insert("patients", null, v);
            else { db.update("patients", v, "id=?", new String[]{String.valueOf(localId)}); id = localId; }
            if (id > 0) bindRemoteKey("patient", id, syncKey);
        } catch (Exception ignored) {
        } finally { suppress(false); }
    }

    public void applyRemoteVisit(JSONObject row) {
        try {
            String syncKey = row.getString("sync_key");
            Long localId = localIdForKey("visit", syncKey);
            if (localId != null && isDirty("visit", localId)) return;
            JSONObject patient = row.optJSONObject("patient");
            if (patient == null) return;
            Long patientLocalId = localIdForKey("patient", patient.optString("sync_key", ""));
            if (patientLocalId == null) return;
            suppress(true);
            ContentValues v = new ContentValues();
            v.put("patient_id", patientLocalId);
            v.put("visit_type", row.optString("visit_type", ClinicDb.NEW));
            v.put("status", row.optString("status", ClinicDb.REGISTERED));
            v.put("fee", row.optInt("fee", 0));
            v.put("paid_amount", row.optInt("paid_amount", 0));
            v.put("complaint", row.optString("complaint", ""));
            v.put("exam", row.optString("exam", ""));
            v.put("diagnosis", row.optString("diagnosis", ""));
            v.put("labs", row.optString("labs", ""));
            v.put("treatment", row.optString("treatment", ""));
            v.put("followup", row.optString("followup", ""));
            v.put("created_at", normalizeTime(row.optString("created_at", "")));
            v.put("started_at", normalizeNullable(row.optString("started_at", "")));
            v.put("completed_at", normalizeNullable(row.optString("completed_at", "")));
            SQLiteDatabase db = helper.getWritableDatabase();
            long id;
            if (localId == null) id = db.insert("visits", null, v);
            else { db.update("visits", v, "id=?", new String[]{String.valueOf(localId)}); id = localId; }
            if (id > 0) bindRemoteKey("visit", id, syncKey);
        } catch (Exception ignored) {
        } finally { suppress(false); }
    }

    public void applyRemotePayment(JSONObject row) {
        try {
            String syncKey = row.getString("sync_key");
            Long localId = localIdForKey("payment", syncKey);
            if (localId != null && isDirty("payment", localId)) return;
            JSONObject visit = row.optJSONObject("visit");
            if (visit == null) return;
            Long visitLocalId = localIdForKey("visit", visit.optString("sync_key", ""));
            if (visitLocalId == null) return;
            suppress(true);
            ContentValues v = new ContentValues();
            v.put("visit_id", visitLocalId);
            v.put("amount", row.optInt("amount", 0));
            v.put("method", row.optString("method", ""));
            v.put("created_at", normalizeTime(row.optString("created_at", "")));
            SQLiteDatabase db = helper.getWritableDatabase();
            long id;
            if (localId == null) id = db.insert("payments", null, v);
            else { db.update("payments", v, "id=?", new String[]{String.valueOf(localId)}); id = localId; }
            if (id > 0) bindRemoteKey("payment", id, syncKey);
            recomputePaidAmount(db, visitLocalId);
        } catch (Exception ignored) {
        } finally { suppress(false); }
    }

    public void applyRemoteDayClosure(JSONObject row) {
        try {
            String syncKey = row.getString("sync_key");
            Long localId = localIdForKey("day_closure", syncKey);
            String day = row.optString("day", "");
            if (localId == null && !day.isEmpty()) localId = closureIdForDay(day);
            if (localId != null && isDirty("day_closure", localId)) return;
            suppress(true);
            ContentValues v = new ContentValues();
            v.put("day", day);
            v.put("total_visits", row.optInt("total_visits", 0));
            v.put("total_charges", row.optInt("total_charges", 0));
            v.put("total_paid", row.optInt("total_paid", 0));
            v.put("total_waived", row.optInt("total_waived", 0));
            v.put("outstanding", row.optInt("outstanding", 0));
            v.put("closed_at", normalizeTime(row.optString("closed_at", "")));
            SQLiteDatabase db = helper.getWritableDatabase();
            long id;
            if (localId == null) id = db.insert("day_closures", null, v);
            else { db.update("day_closures", v, "id=?", new String[]{String.valueOf(localId)}); id = localId; }
            if (id > 0) bindRemoteKey("day_closure", id, syncKey);
        } catch (Exception ignored) {
        } finally { suppress(false); }
    }

    private Long closureIdForDay(String day) {
        Cursor c = helper.getReadableDatabase().rawQuery("SELECT id FROM day_closures WHERE day=? LIMIT 1", new String[]{day});
        Long id = c.moveToFirst() ? c.getLong(0) : null;
        c.close();
        return id;
    }

    private void recomputePaidAmount(SQLiteDatabase db, long visitId) {
        Cursor c = db.rawQuery("SELECT COALESCE(SUM(amount),0) FROM payments WHERE visit_id=?", new String[]{String.valueOf(visitId)});
        int paid = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        ContentValues v = new ContentValues();
        v.put("paid_amount", paid);
        db.update("visits", v, "id=?", new String[]{String.valueOf(visitId)});
    }

    private void suppress(boolean value) { putMeta("suppress_tracking", value ? "1" : "0"); }
    private static String normalizeTime(String value) { return value == null ? "" : value.replace('T', ' ').replace("Z", "").replaceAll("\\+00:00$", ""); }
    private static String normalizeNullable(String value) { String v = normalizeTime(value); return v.isEmpty() || "null".equals(v) ? null : v; }
    private static String safe(String value) { return value == null ? "" : value; }

    public static class SyncItem {
        public final String entityType;
        public final long localId;
        public final String syncKey;
        public final String changedAt;
        public final String payload;

        SyncItem(String entityType, long localId, String syncKey, String changedAt, String payload) {
            this.entityType = entityType;
            this.localId = localId;
            this.syncKey = syncKey;
            this.changedAt = changedAt;
            this.payload = payload;
        }
    }
}
