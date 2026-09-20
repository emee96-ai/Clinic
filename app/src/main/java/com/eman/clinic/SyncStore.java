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

/** Reads the durable database outbox installed by {@link SyncBootstrap}. */
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
                "SELECT entity_type,local_id,changed_at FROM sync_dirty ORDER BY changed_at ASC LIMIT " + safeLimit,
                null);
        while (dirty.moveToNext()) {
            String type = dirty.getString(0);
            long localId = dirty.getLong(1);
            String changedAt = dirty.getString(2);
            SyncItem item = "patient".equals(type) ? patientItem(localId, changedAt) : visitItem(localId, changedAt);
            if (item != null) items.add(item);
        }
        dirty.close();
        return items;
    }

    private SyncItem patientItem(long id, String changedAt) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT p.id,p.card_no,p.full_name,p.phone,p.gender,p.created_at,k.sync_key " +
                        "FROM patients p JOIN sync_entity_keys k ON k.entity_type='patient' AND k.local_id=p.id WHERE p.id=?",
                new String[]{String.valueOf(id)});
        if (!c.moveToFirst()) { c.close(); return null; }
        try {
            JSONObject json = new JSONObject();
            json.put("sync_key", c.getString(6));
            json.put("card_no", c.getInt(1));
            json.put("full_name", safe(c.getString(2)));
            json.put("phone", safe(c.getString(3)));
            json.put("gender", safe(c.getString(4)));
            json.put("created_at", safe(c.getString(5)));
            SyncItem item = new SyncItem("patient", id, c.getString(6), changedAt, json.toString());
            c.close();
            return item;
        } catch (JSONException e) {
            c.close();
            return null;
        }
    }

    private SyncItem visitItem(long id, String changedAt) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT v.id,v.visit_type,v.status,v.fee,v.paid_amount,v.complaint,v.exam,v.diagnosis,v.labs,v.treatment,v.followup,v.created_at,v.started_at,v.completed_at," +
                        "vk.sync_key,pk.sync_key " +
                        "FROM visits v " +
                        "JOIN sync_entity_keys vk ON vk.entity_type='visit' AND vk.local_id=v.id " +
                        "JOIN sync_entity_keys pk ON pk.entity_type='patient' AND pk.local_id=v.patient_id WHERE v.id=?",
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
        } catch (JSONException e) {
            c.close();
            return null;
        }
    }

    /** Deletes only the exact version uploaded; a newer local edit remains dirty. */
    public void markSynced(SyncItem item) {
        helper.getWritableDatabase().delete(
                "sync_dirty",
                "entity_type=? AND local_id=? AND changed_at=?",
                new String[]{item.entityType, String.valueOf(item.localId), item.changedAt});
    }

    public String deviceId() {
        SQLiteDatabase db = helper.getWritableDatabase();
        Cursor c = db.rawQuery("SELECT meta_value FROM sync_meta WHERE meta_key='device_id'", null);
        if (c.moveToFirst()) {
            String value = c.getString(0);
            c.close();
            return value;
        }
        c.close();
        String value = UUID.randomUUID().toString();
        ContentValues cv = new ContentValues();
        cv.put("meta_key", "device_id");
        cv.put("meta_value", value);
        db.insertWithOnConflict("sync_meta", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
        return value;
    }

    public Long localIdForKey(String entityType, String syncKey) {
        Cursor c = helper.getReadableDatabase().rawQuery(
                "SELECT local_id FROM sync_entity_keys WHERE entity_type=? AND sync_key=?",
                new String[]{entityType, syncKey});
        Long id = c.moveToFirst() ? c.getLong(0) : null;
        c.close();
        return id;
    }

    public void bindRemoteKey(String entityType, long localId, String syncKey) {
        ContentValues cv = new ContentValues();
        cv.put("entity_type", entityType);
        cv.put("local_id", localId);
        cv.put("sync_key", syncKey);
        helper.getWritableDatabase().insertWithOnConflict("sync_entity_keys", null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

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
