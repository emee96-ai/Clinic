package com.eman.clinic;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Creates and restores logical clinic backups without copying auth tokens or device secrets. */
public final class ClinicBackupManager {
    private static final int FORMAT = 1;

    private static final String[] PATIENT_COLS = {"id","card_no","full_name","phone","gender","created_at"};
    private static final String[] VISIT_COLS = {"id","patient_id","visit_type","status","fee","paid_amount","complaint","exam","diagnosis","labs","treatment","followup","created_at","started_at","completed_at"};
    private static final String[] PAYMENT_COLS = {"id","visit_id","amount","method","created_at"};
    private static final String[] CLOSURE_COLS = {"id","day","total_visits","total_charges","total_paid","total_waived","outstanding","closed_at"};
    private static final String[] AUDIT_COLS = {"id","action","entity_type","entity_id","details","created_at"};
    private static final String[] KEY_COLS = {"entity_type","local_id","sync_key"};
    private static final String[] DIRTY_COLS = {"entity_type","local_id","changed_at"};

    private final Context context;
    private final ClinicDb helper;
    private final AuthStore auth;
    private final SharedPreferences settings;

    public ClinicBackupManager(Context context) {
        this.context = context.getApplicationContext();
        this.helper = new ClinicDb(this.context);
        this.auth = new AuthStore(this.context);
        this.settings = this.context.getSharedPreferences("clinic_settings", Context.MODE_PRIVATE);
        SyncBootstrap.install(this.context);
    }

    public String createEncryptedBackup(char[] password) throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", FORMAT);
        root.put("app", "Clinic");
        root.put("exported_at", timestamp());
        root.put("clinic_id", auth.clinicId());
        root.put("clinic_name", auth.clinicName().isEmpty() ? settings.getString("clinic_name", "العيادة") : auth.clinicName());

        JSONObject cfg = new JSONObject();
        cfg.put("clinic_name", settings.getString("clinic_name", "العيادة"));
        cfg.put("visit_fee", settings.getInt("visit_fee", 10000));
        cfg.put("result_fee", settings.getInt("result_fee", 0));
        cfg.put("followup_days", settings.getInt("followup_days", 7));
        root.put("settings", cfg);

        SQLiteDatabase db = helper.getReadableDatabase();
        JSONObject data = new JSONObject();
        data.put("patients", query(db, "SELECT id,card_no,full_name,phone,gender,created_at FROM patients ORDER BY id", PATIENT_COLS));
        data.put("visits", query(db, "SELECT id,patient_id,visit_type,status,fee,paid_amount,complaint,exam,diagnosis,labs,treatment,followup,created_at,started_at,completed_at FROM visits ORDER BY id", VISIT_COLS));
        data.put("payments", query(db, "SELECT id,visit_id,amount,method,created_at FROM payments ORDER BY id", PAYMENT_COLS));
        data.put("day_closures", query(db, "SELECT id,day,total_visits,total_charges,total_paid,total_waived,outstanding,closed_at FROM day_closures ORDER BY id", CLOSURE_COLS));
        data.put("audit_log", query(db, "SELECT id,action,entity_type,entity_id,details,created_at FROM audit_log ORDER BY id", AUDIT_COLS));
        data.put("sync_entity_keys", query(db, "SELECT entity_type,local_id,sync_key FROM sync_entity_keys ORDER BY entity_type,local_id", KEY_COLS));
        data.put("sync_dirty", query(db, "SELECT entity_type,local_id,changed_at FROM sync_dirty ORDER BY changed_at", DIRTY_COLS));
        root.put("data", data);

        validateBackup(root, false);
        return BackupCrypto.encrypt(root.toString().getBytes(StandardCharsets.UTF_8), password);
    }

    public RestoreSummary restoreEncryptedBackup(String envelope, char[] password) throws Exception {
        byte[] plain = BackupCrypto.decrypt(envelope, password);
        JSONObject root = new JSONObject(new String(plain, StandardCharsets.UTF_8));
        validateBackup(root, true);

        String backupClinicId = root.optString("clinic_id", "");
        if (auth.hasRemoteIdentity() && !backupClinicId.isEmpty() && !backupClinicId.equals(auth.clinicId()))
            throw new IllegalStateException("backup_belongs_to_another_clinic");

        JSONObject data = root.getJSONObject("data");
        JSONArray patients = data.getJSONArray("patients");
        JSONArray visits = data.getJSONArray("visits");
        JSONArray payments = data.getJSONArray("payments");
        JSONArray closures = data.getJSONArray("day_closures");
        JSONArray audit = data.getJSONArray("audit_log");
        JSONArray keys = data.getJSONArray("sync_entity_keys");
        JSONArray dirty = data.getJSONArray("sync_dirty");

        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            putMeta(db, "suppress_tracking", "1");
            db.delete("sync_dirty", null, null);
            db.delete("sync_entity_keys", null, null);
            db.delete("audit_log", null, null);
            db.delete("payments", null, null);
            db.delete("visits", null, null);
            db.delete("patients", null, null);
            db.delete("day_closures", null, null);

            insertRows(db, "patients", patients, PATIENT_COLS);
            insertRows(db, "visits", visits, VISIT_COLS);
            insertRows(db, "payments", payments, PAYMENT_COLS);
            insertRows(db, "day_closures", closures, CLOSURE_COLS);
            insertRows(db, "audit_log", audit, AUDIT_COLS);
            insertRows(db, "sync_entity_keys", keys, KEY_COLS);
            insertRows(db, "sync_dirty", dirty, DIRTY_COLS);

            ensureRelations(db);
            clearRemoteCursors(db);
            putMeta(db, "sync_tracking_seeded", "1");
            putMeta(db, "suppress_tracking", "0");
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        JSONObject cfg = root.optJSONObject("settings");
        if (cfg != null) {
            settings.edit()
                    .putString("clinic_name", cfg.optString("clinic_name", settings.getString("clinic_name", "العيادة")))
                    .putInt("visit_fee", Math.max(0, cfg.optInt("visit_fee", settings.getInt("visit_fee", 10000))))
                    .putInt("result_fee", Math.max(0, cfg.optInt("result_fee", settings.getInt("result_fee", 0))))
                    .putInt("followup_days", Math.max(1, cfg.optInt("followup_days", settings.getInt("followup_days", 7))))
                    .apply();
        }

        SyncBootstrap.install(context);
        return new RestoreSummary(patients.length(), visits.length(), payments.length(), closures.length(), dirty.length());
    }

    private static JSONArray query(SQLiteDatabase db, String sql, String[] columns) throws Exception {
        JSONArray rows = new JSONArray();
        Cursor c = db.rawQuery(sql, null);
        try {
            while (c.moveToNext()) {
                JSONObject row = new JSONObject();
                for (int i = 0; i < columns.length; i++) {
                    switch (c.getType(i)) {
                        case Cursor.FIELD_TYPE_NULL: row.put(columns[i], JSONObject.NULL); break;
                        case Cursor.FIELD_TYPE_INTEGER: row.put(columns[i], c.getLong(i)); break;
                        case Cursor.FIELD_TYPE_FLOAT: row.put(columns[i], c.getDouble(i)); break;
                        case Cursor.FIELD_TYPE_BLOB: throw new IllegalStateException("unsupported_backup_blob");
                        default: row.put(columns[i], c.getString(i));
                    }
                }
                rows.put(row);
            }
        } finally { c.close(); }
        return rows;
    }

    private static void insertRows(SQLiteDatabase db, String table, JSONArray rows, String[] columns) throws Exception {
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            ContentValues values = new ContentValues();
            for (String column : columns) {
                if (!row.has(column) || row.isNull(column)) {
                    values.putNull(column);
                    continue;
                }
                Object value = row.get(column);
                if (value instanceof Integer) values.put(column, (Integer) value);
                else if (value instanceof Long) values.put(column, (Long) value);
                else if (value instanceof Double) values.put(column, (Double) value);
                else if (value instanceof Number) values.put(column, ((Number) value).longValue());
                else values.put(column, String.valueOf(value));
            }
            long result = db.insertOrThrow(table, null, values);
            if (result < 0) throw new IllegalStateException("restore_insert_failed_" + table);
        }
    }

    private static void validateBackup(JSONObject root, boolean strict) throws Exception {
        if (root.optInt("format", -1) != FORMAT || !"Clinic".equals(root.optString("app", "")))
            throw new IllegalArgumentException("unsupported_backup_format");
        JSONObject data = root.optJSONObject("data");
        if (data == null) throw new IllegalArgumentException("invalid_backup");
        String[] required = {"patients","visits","payments","day_closures","audit_log","sync_entity_keys","sync_dirty"};
        for (String name : required) if (data.optJSONArray(name) == null) throw new IllegalArgumentException("invalid_backup");

        if (!strict) return;
        Set<Long> patientIds = ids(data.getJSONArray("patients"));
        Set<Long> visitIds = ids(data.getJSONArray("visits"));
        for (int i = 0; i < data.getJSONArray("visits").length(); i++)
            if (!patientIds.contains(data.getJSONArray("visits").getJSONObject(i).getLong("patient_id")))
                throw new IllegalArgumentException("invalid_backup_visit_patient");
        for (int i = 0; i < data.getJSONArray("payments").length(); i++)
            if (!visitIds.contains(data.getJSONArray("payments").getJSONObject(i).getLong("visit_id")))
                throw new IllegalArgumentException("invalid_backup_payment_visit");
    }

    private static Set<Long> ids(JSONArray rows) throws Exception {
        Set<Long> out = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) out.add(rows.getJSONObject(i).getLong("id"));
        return out;
    }

    private static void ensureRelations(SQLiteDatabase db) {
        if (count(db, "SELECT COUNT(*) FROM visits v LEFT JOIN patients p ON p.id=v.patient_id WHERE p.id IS NULL") != 0)
            throw new IllegalStateException("invalid_backup_visit_patient");
        if (count(db, "SELECT COUNT(*) FROM payments x LEFT JOIN visits v ON v.id=x.visit_id WHERE v.id IS NULL") != 0)
            throw new IllegalStateException("invalid_backup_payment_visit");
    }

    private static long count(SQLiteDatabase db, String sql) {
        Cursor c = db.rawQuery(sql, null);
        try { return c.moveToFirst() ? c.getLong(0) : 0; }
        finally { c.close(); }
    }

    private static void putMeta(SQLiteDatabase db, String key, String value) {
        ContentValues v = new ContentValues();
        v.put("meta_key", key);
        v.put("meta_value", value);
        db.insertWithOnConflict("sync_meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static void clearRemoteCursors(SQLiteDatabase db) {
        db.delete("sync_meta", "meta_key IN (?,?,?,?)", new String[]{"patients_cursor","visits_cursor","payments_cursor","closures_cursor"});
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date());
    }

    public static final class RestoreSummary {
        public final int patients, visits, payments, dayClosures, pendingSync;
        RestoreSummary(int patients, int visits, int payments, int dayClosures, int pendingSync) {
            this.patients = patients;
            this.visits = visits;
            this.payments = payments;
            this.dayClosures = dayClosures;
            this.pendingSync = pendingSync;
        }
    }
}