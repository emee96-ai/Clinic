package com.eman.clinic;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;

import net.zetetic.database.sqlcipher.SQLiteDatabase;
import net.zetetic.database.sqlcipher.SQLiteOpenHelper;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class ClinicDb extends SQLiteOpenHelper {
    private static final int DATABASE_VERSION = 8;
    private static final String LEGACY_DATABASE = "clinic_offline.db";
    public static final String NEW = "NEW";
    public static final String FREE_FOLLOWUP = "FREE_FOLLOWUP";
    public static final String PAID_FOLLOWUP = "PAID_FOLLOWUP";
    public static final String LAB_RESULT = "LAB_RESULT";

    public static final String REGISTERED = "REGISTERED";
    public static final String WAITING = "WAITING";
    public static final String IN_CONSULT = "IN_CONSULT";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";

    private final AuthStore auth;
    private final Context context;
    private final String scopeId;
    private final String databaseName;

    public ClinicDb(Context context) {
        this(context.getApplicationContext(), configuration(context.getApplicationContext()));
    }

    private ClinicDb(Context context, Configuration configuration) {
        super(context, configuration.databaseName, configuration.password, null,
                DATABASE_VERSION, 0, null, null, false);
        this.context = context;
        this.scopeId = configuration.scopeId;
        this.databaseName = configuration.databaseName;
        auth = new AuthStore(context);
        migrateLegacyDatabaseIfNeeded(configuration.targetExisted);
    }

    private static Configuration configuration(Context context) {
        System.loadLibrary("sqlcipher");
        AuthStore auth = new AuthStore(context);
        String scope = ClinicDatabaseScope.scopeId(auth.clinicId(), auth.userId());
        String name = ClinicDatabaseScope.databaseName(scope);
        boolean existed = context.getDatabasePath(name).exists();
        return new Configuration(scope, name, DatabaseKeyManager.getOrCreate(context, scope), existed);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        createCoreSchema(db);
        createSyncSchema(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            // v1 already contained the core tables. Repair missing objects in place;
            // never drop clinic data during an application upgrade.
            createCoreSchema(db);
        }
        if (oldVersion < 3) {
            addColumnIfMissing(db, "patients", "age_text", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "patients", "allergies", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "patients", "chronic_conditions", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "patients", "current_medications", "TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 4) addPaymentForeignKeyWithoutDataLoss(db);
        if (oldVersion < 5) {
            addColumnIfMissing(db, "audit_log", "actor_user_id", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "audit_log", "actor_display_name", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "audit_log", "actor_role", "TEXT NOT NULL DEFAULT ''");
        }
        if (oldVersion < 6) {
            db.execSQL("CREATE TABLE IF NOT EXISTS sync_conflicts (id INTEGER PRIMARY KEY AUTOINCREMENT, entity_type TEXT NOT NULL, sync_key TEXT NOT NULL, local_payload TEXT NOT NULL, remote_payload TEXT NOT NULL, detected_at TEXT NOT NULL, resolved INTEGER NOT NULL DEFAULT 0)");
            addColumnIfMissing(db, "sync_entity_keys", "remote_version", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(db, "sync_dirty", "attempt_count", "INTEGER NOT NULL DEFAULT 0");
            addColumnIfMissing(db, "sync_dirty", "last_error", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "sync_dirty", "next_retry_at", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "sync_dirty", "sync_status", "TEXT NOT NULL DEFAULT 'pending'");
            addColumnIfMissing(db, "sync_conflicts", "remote_version", "INTEGER NOT NULL DEFAULT 0");
            normalizeLegacySyncKeys(db);
        }
        if (oldVersion < 7) {
            addColumnIfMissing(db, "patients", "normalized_phone", "TEXT NOT NULL DEFAULT ''");
            normalizePatientPhones(db);
            addColumnIfMissing(db, "visits", "assigned_doctor_user_id", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "visits", "assigned_doctor_name", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "visits", "followup_of_visit_id", "INTEGER");
            addColumnIfMissing(db, "visits", "cancellation_reason", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "visits", "cancelled_at", "TEXT");
            addColumnIfMissing(db, "visits", "reopened_at", "TEXT");
            addColumnIfMissing(db, "visits", "temperature", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "visits", "blood_pressure", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "visits", "pulse", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "visits", "weight", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "visits", "oxygen", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "visits", "medications_text", "TEXT NOT NULL DEFAULT ''");
            addColumnIfMissing(db, "visits", "draft_saved_at", "TEXT");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_patients_normalized_phone ON patients(normalized_phone)");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_visits_assigned_doctor ON visits(assigned_doctor_user_id,status)");
        }
        if (oldVersion < 8) addFinanceAuditSchema(db);
        createSyncSchema(db);
    }

    private static void createCoreSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS patients (id INTEGER PRIMARY KEY AUTOINCREMENT, card_no INTEGER NOT NULL UNIQUE, full_name TEXT NOT NULL, phone TEXT, normalized_phone TEXT NOT NULL DEFAULT '', gender TEXT, age_text TEXT NOT NULL DEFAULT '', allergies TEXT NOT NULL DEFAULT '', chronic_conditions TEXT NOT NULL DEFAULT '', current_medications TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS visits (id INTEGER PRIMARY KEY AUTOINCREMENT, patient_id INTEGER NOT NULL, visit_type TEXT NOT NULL, status TEXT NOT NULL, fee INTEGER NOT NULL DEFAULT 0, paid_amount INTEGER NOT NULL DEFAULT 0, complaint TEXT DEFAULT '', exam TEXT DEFAULT '', diagnosis TEXT DEFAULT '', labs TEXT DEFAULT '', treatment TEXT DEFAULT '', followup TEXT DEFAULT '', assigned_doctor_user_id TEXT NOT NULL DEFAULT '', assigned_doctor_name TEXT NOT NULL DEFAULT '', followup_of_visit_id INTEGER, cancellation_reason TEXT NOT NULL DEFAULT '', cancelled_at TEXT, reopened_at TEXT, temperature TEXT NOT NULL DEFAULT '', blood_pressure TEXT NOT NULL DEFAULT '', pulse TEXT NOT NULL DEFAULT '', weight TEXT NOT NULL DEFAULT '', oxygen TEXT NOT NULL DEFAULT '', medications_text TEXT NOT NULL DEFAULT '', draft_saved_at TEXT, created_at TEXT NOT NULL, started_at TEXT, completed_at TEXT, FOREIGN KEY(patient_id) REFERENCES patients(id) ON DELETE RESTRICT, FOREIGN KEY(followup_of_visit_id) REFERENCES visits(id) ON DELETE SET NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS payments (id INTEGER PRIMARY KEY AUTOINCREMENT, visit_id INTEGER NOT NULL, amount INTEGER NOT NULL, method TEXT NOT NULL, event_type TEXT NOT NULL DEFAULT 'PAYMENT', reversal_of_payment_id INTEGER, reason TEXT NOT NULL DEFAULT '', actor_user_id TEXT NOT NULL DEFAULT '', actor_display_name TEXT NOT NULL DEFAULT '', actor_role TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL, FOREIGN KEY(visit_id) REFERENCES visits(id) ON DELETE RESTRICT, FOREIGN KEY(reversal_of_payment_id) REFERENCES payments(id) ON DELETE RESTRICT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS day_closures (id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL UNIQUE, total_visits INTEGER NOT NULL, total_charges INTEGER NOT NULL, total_paid INTEGER NOT NULL, total_waived INTEGER NOT NULL, outstanding INTEGER NOT NULL, closed_at TEXT NOT NULL, is_reopened INTEGER NOT NULL DEFAULT 0, reopen_count INTEGER NOT NULL DEFAULT 0, last_reopened_at TEXT, last_reopened_by_user_id TEXT NOT NULL DEFAULT '', last_reopened_by_name TEXT NOT NULL DEFAULT '', last_reopen_reason TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE IF NOT EXISTS audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT, action TEXT NOT NULL, entity_type TEXT NOT NULL, entity_id INTEGER, details TEXT, actor_user_id TEXT NOT NULL DEFAULT '', actor_display_name TEXT NOT NULL DEFAULT '', actor_role TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_visits_patient ON visits(patient_id)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_visits_status ON visits(status)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_payments_visit ON payments(visit_id)");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_payments_one_reversal ON payments(reversal_of_payment_id) WHERE reversal_of_payment_id IS NOT NULL");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_patients_normalized_phone ON patients(normalized_phone)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_visits_assigned_doctor ON visits(assigned_doctor_user_id,status)");
    }

    private static void addFinanceAuditSchema(SQLiteDatabase db) {
        addColumnIfMissing(db, "payments", "event_type", "TEXT NOT NULL DEFAULT 'PAYMENT'");
        addColumnIfMissing(db, "payments", "reversal_of_payment_id", "INTEGER REFERENCES payments(id) ON DELETE RESTRICT");
        addColumnIfMissing(db, "payments", "reason", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(db, "payments", "actor_user_id", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(db, "payments", "actor_display_name", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(db, "payments", "actor_role", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(db, "day_closures", "is_reopened", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(db, "day_closures", "reopen_count", "INTEGER NOT NULL DEFAULT 0");
        addColumnIfMissing(db, "day_closures", "last_reopened_at", "TEXT");
        addColumnIfMissing(db, "day_closures", "last_reopened_by_user_id", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(db, "day_closures", "last_reopened_by_name", "TEXT NOT NULL DEFAULT ''");
        addColumnIfMissing(db, "day_closures", "last_reopen_reason", "TEXT NOT NULL DEFAULT ''");
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_payments_one_reversal ON payments(reversal_of_payment_id) WHERE reversal_of_payment_id IS NOT NULL");
    }

    private static void createSyncSchema(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_entity_keys (entity_type TEXT NOT NULL, local_id INTEGER NOT NULL, sync_key TEXT NOT NULL UNIQUE, remote_version INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(entity_type, local_id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_dirty (entity_type TEXT NOT NULL, local_id INTEGER NOT NULL, changed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, attempt_count INTEGER NOT NULL DEFAULT 0, last_error TEXT NOT NULL DEFAULT '', next_retry_at TEXT NOT NULL DEFAULT '', sync_status TEXT NOT NULL DEFAULT 'pending', PRIMARY KEY(entity_type, local_id))");
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_meta (meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS sync_conflicts (id INTEGER PRIMARY KEY AUTOINCREMENT, entity_type TEXT NOT NULL, sync_key TEXT NOT NULL, local_payload TEXT NOT NULL, remote_payload TEXT NOT NULL, remote_version INTEGER NOT NULL DEFAULT 0, detected_at TEXT NOT NULL, resolved INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_conflicts_open ON sync_conflicts(resolved,detected_at)");
    }

    private static void normalizeLegacySyncKeys(SQLiteDatabase db) {
        db.execSQL("UPDATE sync_entity_keys SET sync_key=lower(substr(sync_key,1,8)||'-'||substr(sync_key,9,4)||'-'||substr(sync_key,13,4)||'-'||substr(sync_key,17,4)||'-'||substr(sync_key,21,12)) WHERE length(sync_key)=32 AND sync_key NOT LIKE '%-%'");
    }

    private static void addColumnIfMissing(SQLiteDatabase db, String table, String column, String definition) {
        Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        try {
            while (c.moveToNext()) if (column.equals(c.getString(1))) return;
        } finally { c.close(); }
        db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
    }

    private static void normalizePatientPhones(SQLiteDatabase db) {
        Cursor c = db.rawQuery("SELECT id,phone FROM patients", null);
        try {
            while (c.moveToNext()) {
                ContentValues v = new ContentValues();
                v.put("normalized_phone", ClinicWorkflowRules.normalizePhone(c.getString(1)));
                db.update("patients", v, "id=?", new String[]{String.valueOf(c.getLong(0))});
            }
        } finally { c.close(); }
    }

    private static void addPaymentForeignKeyWithoutDataLoss(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS migration_quarantine_payments (id INTEGER PRIMARY KEY, visit_id INTEGER NOT NULL, amount INTEGER NOT NULL, method TEXT NOT NULL, created_at TEXT NOT NULL, reason TEXT NOT NULL)");
        db.execSQL("INSERT OR REPLACE INTO migration_quarantine_payments(id,visit_id,amount,method,created_at,reason) " +
                "SELECT p.id,p.visit_id,p.amount,p.method,p.created_at,'missing_visit_during_v4_migration' FROM payments p LEFT JOIN visits v ON v.id=p.visit_id WHERE v.id IS NULL");
        db.execSQL("CREATE TABLE payments_v4 (id INTEGER PRIMARY KEY AUTOINCREMENT, visit_id INTEGER NOT NULL, amount INTEGER NOT NULL, method TEXT NOT NULL, created_at TEXT NOT NULL, FOREIGN KEY(visit_id) REFERENCES visits(id) ON DELETE RESTRICT)");
        db.execSQL("INSERT INTO payments_v4(id,visit_id,amount,method,created_at) SELECT p.id,p.visit_id,p.amount,p.method,p.created_at FROM payments p JOIN visits v ON v.id=p.visit_id");
        db.execSQL("DROP TABLE payments");
        db.execSQL("ALTER TABLE payments_v4 RENAME TO payments");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_payments_visit ON payments(visit_id)");
    }

    private void migrateLegacyDatabaseIfNeeded(boolean targetExisted) {
        File legacy = context.getDatabasePath(LEGACY_DATABASE);
        if (!legacy.exists()) return;
        SQLiteDatabase target = null;
        android.database.sqlite.SQLiteDatabase source = null;
        try {
            target = getWritableDatabase();
            createSyncSchema(target);
            if (targetExisted) {
                if (!"1".equals(metaValue(target, "legacy_plaintext_migrated"))) {
                    throw new IllegalStateException("Unverified encrypted migration target");
                }
            } else {
                source = android.database.sqlite.SQLiteDatabase.openDatabase(
                        legacy.getAbsolutePath(), null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                target.beginTransaction();
                try {
                    String[] tables = {"patients", "visits", "payments", "day_closures", "audit_log",
                            "sync_entity_keys", "sync_dirty", "sync_meta"};
                    for (String table : tables) copyAndVerifyTable(source, target, table);
                    putMeta(target, "legacy_plaintext_migrated", "1");
                    target.setTransactionSuccessful();
                } finally { target.endTransaction(); }
            }
            if (!context.deleteDatabase(LEGACY_DATABASE) && legacy.exists()) {
                throw new IllegalStateException("Plaintext clinic database could not be removed");
            }
        } catch (Exception e) {
            if (!targetExisted) {
                try { close(); } catch (Exception ignored) {}
                context.deleteDatabase(databaseName);
            }
            throw new IllegalStateException("Clinic database encryption migration failed", e);
        } finally {
            if (source != null) source.close();
        }
    }

    private static void copyAndVerifyTable(android.database.sqlite.SQLiteDatabase source,
                                           SQLiteDatabase target, String table) {
        if (!androidTableExists(source, table)) return;
        Set<String> targetColumns = targetColumns(target, table);
        Cursor rows = source.rawQuery("SELECT * FROM " + table, null);
        int sourceCount = 0;
        try {
            String[] columns = rows.getColumnNames();
            while (rows.moveToNext()) {
                sourceCount++;
                ContentValues values = new ContentValues();
                for (int i = 0; i < columns.length; i++) {
                    if (!targetColumns.contains(columns[i]) || rows.isNull(i)) continue;
                    switch (rows.getType(i)) {
                        case Cursor.FIELD_TYPE_INTEGER: values.put(columns[i], rows.getLong(i)); break;
                        case Cursor.FIELD_TYPE_FLOAT: values.put(columns[i], rows.getDouble(i)); break;
                        case Cursor.FIELD_TYPE_BLOB: values.put(columns[i], rows.getBlob(i)); break;
                        default: values.put(columns[i], rows.getString(i));
                    }
                }
                if (target.insertOrThrow(table, null, values) < 0) {
                    throw new IllegalStateException("Failed to migrate " + table);
                }
            }
        } finally { rows.close(); }
        Cursor count = target.rawQuery("SELECT COUNT(*) FROM " + table, null);
        try {
            if (!count.moveToFirst() || count.getInt(0) != sourceCount) {
                throw new IllegalStateException("Row count mismatch for " + table);
            }
        } finally { count.close(); }
    }

    private static boolean androidTableExists(android.database.sqlite.SQLiteDatabase db, String table) {
        Cursor c = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=? LIMIT 1", new String[]{table});
        try { return c.moveToFirst(); } finally { c.close(); }
    }

    private static Set<String> targetColumns(SQLiteDatabase db, String table) {
        Set<String> columns = new HashSet<>();
        Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
        try { while (c.moveToNext()) columns.add(c.getString(1)); }
        finally { c.close(); }
        return columns;
    }

    void bindToActiveClinic(SQLiteDatabase db) {
        createSyncSchema(db);
        String expected = ClinicDatabaseScope.boundClinicId(scopeId);
        String existing = metaValue(db, "bound_clinic_id");
        if (!existing.isEmpty() && !existing.equals(expected)) {
            throw new SecurityException("Local database belongs to another clinic");
        }
        if (existing.isEmpty() && !expected.isEmpty()) putMeta(db, "bound_clinic_id", expected);
    }

    boolean isBoundToActiveClinic() {
        String expected = auth.clinicId();
        if (expected.isEmpty()) return false;
        return expected.equals(metaValue(getReadableDatabase(), "bound_clinic_id"));
    }

    private static String metaValue(SQLiteDatabase db, String key) {
        Cursor c = db.rawQuery("SELECT meta_value FROM sync_meta WHERE meta_key=?", new String[]{key});
        try { return c.moveToFirst() ? safe(c.getString(0)) : ""; }
        finally { c.close(); }
    }

    private static void putMeta(SQLiteDatabase db, String key, String value) {
        ContentValues values = new ContentValues();
        values.put("meta_key", key);
        values.put("meta_value", safe(value));
        db.insertWithOnConflict("sync_meta", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private static final class Configuration {
        final String scopeId;
        final String databaseName;
        final byte[] password;
        final boolean targetExisted;

        Configuration(String scopeId, String databaseName, byte[] password, boolean targetExisted) {
            this.scopeId = scopeId;
            this.databaseName = databaseName;
            this.password = password;
            this.targetExisted = targetExisted;
        }
    }

    public long createPatient(String name, String phone, String gender) {
        return createPatient(name, phone, gender, "", "", "", "");
    }

    public long createPatient(String name, String phone, String gender, String ageText,
                              String allergies, String chronicConditions, String currentMedications) {
        if (!can("edit_patients")) return -1;
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.length() < 2) return -1;
        SQLiteDatabase db = getWritableDatabase();
        String normalizedPhone = ClinicWorkflowRules.normalizePhone(phone);
        if (!normalizedPhone.isEmpty() && patientIdByPhone(db, normalizedPhone) != null) return -3;
        ContentValues v = new ContentValues();
        v.put("card_no", nextCardNo(db));
        v.put("full_name", cleanName);
        v.put("phone", phone == null ? "" : phone.trim());
        v.put("normalized_phone", normalizedPhone);
        v.put("gender", gender == null ? "" : gender);
        v.put("age_text", safe(ageText).trim());
        v.put("allergies", safe(allergies).trim());
        v.put("chronic_conditions", safe(chronicConditions).trim());
        v.put("current_medications", safe(currentMedications).trim());
        v.put("created_at", now());
        long id = db.insertOrThrow("patients", null, v);
        audit(db, "CREATE_PATIENT", "patient", id, cleanName);
        return id;
    }

    public RegistrationResult registerPatientWithVisit(String name, String phone, String gender,
            String ageText, String type, int fee) {
        if (!can("edit_patients") || !can("register_visits")) return RegistrationResult.failed(-1);
        if (isTodayClosedInternal()) return RegistrationResult.failed(-2);
        String cleanName = safe(name).trim();
        if (cleanName.length() < 2 || !ClinicWorkflowRules.isValidVisitType(type))
            return RegistrationResult.failed(-1);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            String normalizedPhone = ClinicWorkflowRules.normalizePhone(phone);
            Long duplicate = normalizedPhone.isEmpty() ? null : patientIdByPhone(db, normalizedPhone);
            if (duplicate != null) return new RegistrationResult(-3, duplicate, -1);
            ContentValues patient = new ContentValues();
            patient.put("card_no", nextCardNo(db));
            patient.put("full_name", cleanName);
            patient.put("phone", safe(phone).trim());
            patient.put("normalized_phone", normalizedPhone);
            patient.put("gender", safe(gender));
            patient.put("age_text", safe(ageText).trim());
            patient.put("created_at", now());
            long patientId = db.insertOrThrow("patients", null, patient);
            long visitId = insertVisit(db, patientId, type, fee, null);
            audit(db, "CREATE_PATIENT", "patient", patientId, cleanName);
            audit(db, "CREATE_VISIT", "visit", visitId, type);
            db.setTransactionSuccessful();
            return new RegistrationResult(0, patientId, visitId);
        } finally { db.endTransaction(); }
    }

    public boolean updatePatientMedical(long patientId, String ageText, String allergies,
                                        String chronicConditions, String currentMedications) {
        if (!can("edit_clinical")) return false;
        if (!patientExists(patientId)) return false;
        ContentValues v = new ContentValues();
        v.put("age_text", safe(ageText).trim());
        v.put("allergies", safe(allergies).trim());
        v.put("chronic_conditions", safe(chronicConditions).trim());
        v.put("current_medications", safe(currentMedications).trim());
        SQLiteDatabase db = getWritableDatabase();
        int changed = db.update("patients", v, "id=?", new String[]{String.valueOf(patientId)});
        if (changed > 0) audit(db, "UPDATE_PATIENT_MEDICAL", "patient", patientId, "");
        return changed > 0;
    }

    public long createVisit(long patientId, String type, int fee) {
        if (!can("register_visits")) return -1;
        if (isTodayClosedInternal()) return -2;
        if (!patientExists(patientId) || !ClinicWorkflowRules.isValidVisitType(type)) return -1;
        if (hasOpenVisit(patientId)) return -1;
        SQLiteDatabase db = getWritableDatabase();
        Long previous = lastCompletedVisitId(db, patientId);
        long id = insertVisit(db, patientId, type, fee,
                NEW.equals(type) ? null : previous);
        audit(db, "CREATE_VISIT", "visit", id, type);
        return id;
    }

    private long insertVisit(SQLiteDatabase db, long patientId, String type, int fee, Long followupOf) {
        ContentValues v = new ContentValues();
        v.put("patient_id", patientId);
        v.put("visit_type", type);
        v.put("status", REGISTERED);
        v.put("fee", Math.max(0, fee));
        v.put("paid_amount", 0);
        if (followupOf != null) v.put("followup_of_visit_id", followupOf);
        v.put("created_at", now());
        return db.insertOrThrow("visits", null, v);
    }

    public boolean hasOpenVisit(long patientId) {
        if (!(can("view_patients") || can("manage_queue") || can("register_visits"))) return false;
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM visits WHERE patient_id=? AND status NOT IN (?,?)", new String[]{String.valueOf(patientId), COMPLETED, CANCELLED});
        boolean result = c.moveToFirst() && c.getInt(0) > 0;
        c.close();
        return result;
    }

    public boolean sendToDoctor(long visitId) {
        if (!can("manage_queue")) return false;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("status", WAITING);
        int changed = db.update("visits", v, "id=? AND status=?", new String[]{String.valueOf(visitId), REGISTERED});
        if (changed > 0) audit(db, "SEND_TO_DOCTOR", "visit", visitId, WAITING);
        return changed > 0;
    }

    public boolean updateVisitRegistration(long visitId, String type, int fee) {
        if (!can("register_visits") || !ClinicWorkflowRules.isValidVisitType(type)) return false;
        ContentValues v = new ContentValues();
        v.put("visit_type", type); v.put("fee", Math.max(0, fee));
        int changed = getWritableDatabase().update("visits", v, "id=? AND status=?",
                new String[]{String.valueOf(visitId), REGISTERED});
        if (changed > 0) audit(getWritableDatabase(), "EDIT_VISIT", "visit", visitId, type);
        return changed > 0;
    }

    public boolean startVisit(long visitId) {
        if (!can("manage_queue") || !can("edit_clinical")) return false;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("status", IN_CONSULT);
        v.put("started_at", now());
        v.put("assigned_doctor_user_id", auth.userId());
        v.put("assigned_doctor_name", auth.memberDisplayName());
        int changed = db.update("visits", v, "id=? AND status=?", new String[]{String.valueOf(visitId), WAITING});
        if (changed > 0) audit(db, "START_VISIT", "visit", visitId, "");
        return changed > 0;
    }

    public boolean saveClinical(long visitId, String complaint, String exam, String diagnosis, String labs, String treatment, String followup, boolean complete) {
        return saveClinical(visitId, complaint, exam, diagnosis, labs, treatment, followup,
                "", "", "", "", "", "", complete);
    }

    public boolean saveClinical(long visitId, String complaint, String exam, String diagnosis,
            String labs, String treatment, String followup, String temperature,
            String bloodPressure, String pulse, String weight, String oxygen,
            String medications, boolean complete) {
        if (!can("edit_clinical")) return false;
        SQLiteDatabase db = getWritableDatabase();
        Visit current = getVisitInternal(db, visitId);
        if (current == null || !ClinicClinicalAccessRules.canEditVisit(
                auth.memberRole(), auth.userId(), current.assignedDoctorUserId, current.status)) return false;
        if (complete && !ClinicWorkflowRules.canCompleteClinical(
                current.type, complaint, diagnosis, labs)) return false;
        ContentValues v = new ContentValues();
        v.put("complaint", safe(complaint));
        v.put("exam", safe(exam));
        v.put("diagnosis", safe(diagnosis));
        v.put("labs", safe(labs));
        v.put("treatment", safe(treatment));
        v.put("followup", safe(followup));
        v.put("temperature", safe(temperature).trim());
        v.put("blood_pressure", safe(bloodPressure).trim());
        v.put("pulse", safe(pulse).trim());
        v.put("weight", safe(weight).trim());
        v.put("oxygen", safe(oxygen).trim());
        v.put("medications_text", safe(medications).trim());
        v.put("draft_saved_at", now());
        if (complete) {
            v.put("status", COMPLETED);
            v.put("completed_at", now());
        } else {
            v.put("status", IN_CONSULT);
        }
        int changed = db.update("visits", v, "id=? AND status=?", new String[]{String.valueOf(visitId), IN_CONSULT});
        if (changed > 0) audit(db, complete ? "COMPLETE_VISIT" : "SAVE_DRAFT", "visit", visitId, "");
        return changed > 0;
    }

    public boolean cancelVisit(long visitId, String reason) {
        if (!can("manage_queue") || safe(reason).trim().length() < 3) return false;
        ContentValues v = new ContentValues();
        v.put("status", CANCELLED); v.put("cancellation_reason", safe(reason).trim());
        v.put("cancelled_at", now());
        int changed = getWritableDatabase().update("visits", v,
                "id=? AND paid_amount=0 AND status IN (?,?)",
                new String[]{String.valueOf(visitId), REGISTERED, WAITING});
        if (changed > 0) audit(getWritableDatabase(), "CANCEL_VISIT", "visit", visitId, reason);
        return changed > 0;
    }

    public boolean reopenVisit(long visitId) {
        if (!can("manage_queue") || isTodayClosedInternal()) return false;
        ContentValues v = new ContentValues();
        v.put("status", WAITING); v.put("reopened_at", now());
        v.put("cancellation_reason", ""); v.putNull("cancelled_at");
        int changed = getWritableDatabase().update("visits", v, "id=? AND status=?",
                new String[]{String.valueOf(visitId), CANCELLED});
        if (changed > 0) audit(getWritableDatabase(), "REOPEN_VISIT", "visit", visitId, "");
        return changed > 0;
    }

    public boolean transferVisit(long visitId) {
        if (!can("edit_clinical")) return false;
        Visit current = getVisitInternal(getReadableDatabase(), visitId);
        if (current == null || !ClinicClinicalAccessRules.canTransferVisit(
                auth.memberRole(), auth.userId(), current.assignedDoctorUserId, current.status)) return false;
        ContentValues v = new ContentValues();
        v.put("status", WAITING); v.put("assigned_doctor_user_id", "");
        v.put("assigned_doctor_name", ""); v.putNull("started_at");
        int changed = getWritableDatabase().update("visits", v, "id=? AND status=?",
                new String[]{String.valueOf(visitId), IN_CONSULT});
        if (changed > 0) audit(getWritableDatabase(), "TRANSFER_VISIT", "visit", visitId, "");
        return changed > 0;
    }

    public boolean recordPayment(long visitId, int requestedAmount, String method) {
        if (!can("record_payments")) return false;
        if (isTodayClosedInternal()) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Visit visit = getVisitInternal(db, visitId);
            if (visit == null || CANCELLED.equals(visit.status)) return false;
            int remaining = Math.max(0, visit.fee - visit.paidAmount);
            if (!ClinicWorkflowRules.canRecordPayment(requestedAmount, remaining, false)) return false;

            ContentValues pay = new ContentValues();
            pay.put("visit_id", visitId);
            pay.put("amount", requestedAmount);
            pay.put("method", safe(method));
            pay.put("event_type", "PAYMENT");
            putActor(pay);
            pay.put("created_at", now());
            long paymentId = db.insertOrThrow("payments", null, pay);

            ContentValues upd = new ContentValues();
            upd.put("paid_amount", visit.paidAmount + requestedAmount);
            db.update("visits", upd, "id=?", new String[]{String.valueOf(visitId)});
            audit(db, "PAYMENT", "payment", paymentId, "visit=" + visitId + ", amount=" + requestedAmount);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public boolean reversePayment(long paymentId, String eventType, int replacementAmount, String reason) {
        String cleanReason = safe(reason).trim();
        if (!ClinicFinanceRules.canBeginReversal("owner_doctor".equals(auth.memberRole()),
                isTodayClosedInternal(), cleanReason, eventType)) return false;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.rawQuery("SELECT visit_id,amount,method,event_type FROM payments WHERE id=?", new String[]{String.valueOf(paymentId)});
            if (!c.moveToFirst() || c.getInt(1) <= 0 || !"PAYMENT".equals(c.getString(3))) { c.close(); return false; }
            long visitId = c.getLong(0); int originalAmount = c.getInt(1); String method = c.getString(2); c.close();
            Cursor reversed = db.rawQuery("SELECT 1 FROM payments WHERE reversal_of_payment_id=? LIMIT 1", new String[]{String.valueOf(paymentId)});
            boolean already = reversed.moveToFirst(); reversed.close();
            if (already || ("CORRECTION".equals(eventType) && replacementAmount <= 0)) return false;
            Visit visit=getVisitInternal(db,visitId);
            if("CORRECTION".equals(eventType) && (visit==null || !ClinicFinanceRules.canReplacePayment(
                    visit.fee,visit.paidAmount,originalAmount,replacementAmount))) return false;

            ContentValues reversal = new ContentValues();
            reversal.put("visit_id", visitId); reversal.put("amount", -originalAmount); reversal.put("method", method);
            reversal.put("event_type", eventType); reversal.put("reversal_of_payment_id", paymentId);
            reversal.put("reason", cleanReason); putActor(reversal); reversal.put("created_at", now());
            long reversalId = db.insertOrThrow("payments", null, reversal);
            if ("CORRECTION".equals(eventType)) {
                ContentValues replacement = new ContentValues();
                replacement.put("visit_id", visitId); replacement.put("amount", replacementAmount); replacement.put("method", method);
                replacement.put("event_type", "PAYMENT"); replacement.put("reason", "تصحيح للدفعة رقم " + paymentId + ": " + cleanReason);
                putActor(replacement); replacement.put("created_at", now());
                db.insertOrThrow("payments", null, replacement);
            }
            recomputePaidAmount(db, visitId);
            audit(db, eventType + "_PAYMENT", "payment", reversalId,
                    "original=" + paymentId + ", reason=" + cleanReason + ", replacement=" + replacementAmount);
            db.setTransactionSuccessful();
            return true;
        } finally { db.endTransaction(); }
    }

    public List<Payment> recentPaymentsToday() {
        List<Payment> out = new ArrayList<>();
        if (!(canFinanceRead() || can("record_payments"))) return out;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT p.id,p.visit_id,p.amount,p.method,p.event_type,p.reason,p.actor_display_name,p.created_at,pt.full_name," +
                        "EXISTS(SELECT 1 FROM payments r WHERE r.reversal_of_payment_id=p.id) " +
                        "FROM payments p JOIN visits v ON v.id=p.visit_id JOIN patients pt ON pt.id=v.patient_id " +
                        "WHERE date(p.created_at)=date('now','localtime') ORDER BY p.id DESC LIMIT 50", null);
        while (c.moveToNext()) {
            Payment p = new Payment(); p.id=c.getLong(0); p.visitId=c.getLong(1); p.amount=c.getInt(2);
            p.method=safe(c.getString(3)); p.eventType=safe(c.getString(4)); p.reason=safe(c.getString(5));
            p.actorName=safe(c.getString(6)); p.createdAt=safe(c.getString(7)); p.patientName=safe(c.getString(8)); p.reversed=c.getInt(9)>0;
            out.add(p);
        }
        c.close(); return out;
    }

    public List<Debt> olderDebts() {
        List<Debt> out = new ArrayList<>();
        if (!(canFinanceRead() || can("record_payments"))) return out;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT v.id,pt.full_name,pt.card_no,v.created_at,v.fee,v.paid_amount " +
                        "FROM visits v JOIN patients pt ON pt.id=v.patient_id WHERE v.status<>? AND date(v.created_at)<date('now','localtime') " +
                        "AND v.fee>v.paid_amount ORDER BY v.created_at ASC", new String[]{CANCELLED});
        while (c.moveToNext()) { Debt d=new Debt(); d.visitId=c.getLong(0); d.patientName=c.getString(1); d.cardNo=c.getInt(2);
            d.createdAt=c.getString(3); d.fee=c.getInt(4); d.paid=c.getInt(5); out.add(d); }
        c.close(); return out;
    }

    public Patient getPatient(long id) {
        if (!can("view_patients")) return null;
        Cursor c = getReadableDatabase().rawQuery("SELECT id, card_no, full_name, phone, gender, age_text, allergies, chronic_conditions, current_medications, created_at FROM patients WHERE id=?", new String[]{String.valueOf(id)});
        Patient p = c.moveToFirst() ? patientFrom(c) : null;
        c.close();
        return p;
    }

    public Patient findPatient(String query) {
        if (!can("view_patients")) return null;
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return null;
        Cursor c = getReadableDatabase().rawQuery("SELECT id, card_no, full_name, phone, gender, age_text, allergies, chronic_conditions, current_medications, created_at FROM patients WHERE CAST(card_no AS TEXT)=? OR phone=? OR full_name LIKE ? ORDER BY id DESC LIMIT 1", new String[]{q, q, "%" + q + "%"});
        Patient p = c.moveToFirst() ? patientFrom(c) : null;
        c.close();
        return p;
    }

    public Patient findPatientByPhone(String phone) {
        String normalized = ClinicWorkflowRules.normalizePhone(phone);
        if (normalized.isEmpty()) return null;
        Long id = patientIdByPhone(getReadableDatabase(), normalized);
        return id == null ? null : getPatient(id);
    }

    public List<Patient> searchPatients(String query) {
        List<Patient> out = new ArrayList<>();
        if (!can("view_patients")) return out;
        String q = query == null ? "" : query.trim();
        Cursor c;
        if (q.isEmpty()) {
            c = getReadableDatabase().rawQuery("SELECT id, card_no, full_name, phone, gender, age_text, allergies, chronic_conditions, current_medications, created_at FROM patients ORDER BY id DESC LIMIT 100", null);
        } else {
            c = getReadableDatabase().rawQuery("SELECT id, card_no, full_name, phone, gender, age_text, allergies, chronic_conditions, current_medications, created_at FROM patients WHERE CAST(card_no AS TEXT) LIKE ? OR phone LIKE ? OR full_name LIKE ? ORDER BY id DESC LIMIT 100", new String[]{"%" + q + "%", "%" + q + "%", "%" + q + "%"});
        }
        while (c.moveToNext()) out.add(patientFrom(c));
        c.close();
        return out;
    }

    public Visit getVisit(long id) {
        if (!canQueueRead()) return null;
        return sanitizeClinical(getVisitInternal(getReadableDatabase(), id));
    }

    public List<Visit> openQueue() {
        if (!canQueueRead()) return new ArrayList<>();
        return queryVisits("WHERE v.status NOT IN (?,?) ORDER BY v.id ASC", new String[]{COMPLETED, CANCELLED});
    }

    public List<Visit> doctorQueue() {
        if (!can("view_clinical")) return new ArrayList<>();
        return queryVisits("WHERE v.status=? OR (v.status=? AND v.assigned_doctor_user_id=?) " +
                "ORDER BY CASE v.status WHEN 'IN_CONSULT' THEN 0 ELSE 1 END, v.id ASC",
                new String[]{WAITING, IN_CONSULT, auth.userId()});
    }

    public List<Visit> visitsForPatient(long patientId) {
        if (!can("view_patients")) return new ArrayList<>();
        return queryVisits("WHERE v.patient_id=? ORDER BY v.id DESC", new String[]{String.valueOf(patientId)});
    }

    public List<Visit> cancelledVisits() {
        if (!canQueueRead()) return new ArrayList<>();
        return queryVisits("WHERE v.status=? ORDER BY v.id DESC LIMIT 50", new String[]{CANCELLED});
    }

    public List<Visit> unpaidToday() {
        if (!(can("view_finance") || can("record_payments"))) return new ArrayList<>();
        return queryVisits("WHERE v.status<>? AND date(v.created_at)=date('now','localtime') AND v.fee>v.paid_amount ORDER BY v.id ASC", new String[]{CANCELLED});
    }

    private List<Visit> queryVisits(String where, String[] args) {
        List<Visit> out = new ArrayList<>();
        String sql = visitSelect() + where;
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        while (c.moveToNext()) out.add(sanitizeClinical(visitFrom(c)));
        c.close();
        return out;
    }

    public Stats todayStats() {
        Stats s = new Stats();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT COUNT(*), COALESCE(SUM(fee),0), COALESCE(SUM(CASE WHEN fee=0 THEN 1 ELSE 0 END),0), " +
                        "COALESCE(SUM(CASE WHEN fee>paid_amount THEN fee-paid_amount ELSE 0 END),0) " +
                        "FROM visits WHERE status<>? AND date(created_at)=date('now','localtime')", new String[]{CANCELLED});
        if (c.moveToFirst()) {
            s.totalVisits = c.getInt(0);
            if (canFinanceRead()) {
                s.totalCharges = c.getInt(1);
                s.waivedVisits = c.getInt(2);
                s.outstanding = c.getInt(3);
            }
        }
        c.close();
        if (canFinanceRead()) {
            Cursor p = getReadableDatabase().rawQuery(
                    "SELECT COALESCE(SUM(amount),0),COALESCE(SUM(CASE WHEN amount<0 THEN -amount ELSE 0 END),0)," +
                            "COALESCE(SUM(CASE WHEN method='كاش' THEN amount ELSE 0 END),0)," +
                            "COALESCE(SUM(CASE WHEN method='تحويل بنكي' THEN amount ELSE 0 END),0)," +
                            "COALESCE(SUM(CASE WHEN method='محفظة' THEN amount ELSE 0 END),0)," +
                            "COALESCE(SUM(CASE WHEN method NOT IN ('كاش','تحويل بنكي','محفظة') THEN amount ELSE 0 END),0) " +
                            "FROM payments WHERE date(created_at)=date('now','localtime')", null);
            if (p.moveToFirst()) { s.totalPaid=p.getInt(0); s.refunded=p.getInt(1); s.cash=p.getInt(2); s.bank=p.getInt(3); s.wallet=p.getInt(4); s.other=p.getInt(5); }
            p.close();
            Cursor cancelled = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM visits WHERE status=? AND date(created_at)=date('now','localtime')", new String[]{CANCELLED});
            if (cancelled.moveToFirst()) s.cancelledVisits=cancelled.getInt(0); cancelled.close();
        }
        if (canQueueRead()) s.openQueue = openQueueCountInternal();
        return s;
    }

    public boolean closeToday() {
        if (!can("close_day")) return false;
        if (isTodayClosedInternal()) return false;
        int open = openQueueCountInternal();
        if (!ClinicWorkflowRules.canCloseDay(open)) return false;
        Stats s = todayStats();
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("day", today());
        v.put("total_visits", s.totalVisits);
        v.put("total_charges", s.totalCharges);
        v.put("total_paid", s.totalPaid);
        v.put("total_waived", s.waivedVisits);
        v.put("outstanding", s.outstanding);
        v.put("closed_at", now());
        v.put("is_reopened", 0);
        long id;
        Cursor existing = db.rawQuery("SELECT id FROM day_closures WHERE day=?", new String[]{today()});
        if (existing.moveToFirst()) { id=existing.getLong(0); db.update("day_closures",v,"id=?",new String[]{String.valueOf(id)}); }
        else id = db.insert("day_closures", null, v);
        existing.close();
        if (id > 0) audit(db, "CLOSE_DAY", "day_closure", id, today());
        return id > 0;
    }

    public boolean reopenToday(String reason) {
        String clean=safe(reason).trim();
        if (!"owner_doctor".equals(auth.memberRole()) || clean.length()<3 || !isTodayClosedInternal()) return false;
        ContentValues v=new ContentValues(); v.put("is_reopened",1); v.put("last_reopened_at",now());
        v.put("last_reopened_by_user_id",auth.userId()); v.put("last_reopened_by_name",auth.memberDisplayName()); v.put("last_reopen_reason",clean);
        SQLiteDatabase db=getWritableDatabase(); int changed=db.update("day_closures",v,"day=? AND is_reopened=0",new String[]{today()});
        if (changed>0) { db.execSQL("UPDATE day_closures SET reopen_count=reopen_count+1 WHERE day=?",new Object[]{today()}); audit(db,"REOPEN_DAY","day_closure",closureId(db,today()),clean); }
        return changed>0;
    }

    public boolean isTodayClosed() {
        if (!canFinanceRead()) return false;
        return isTodayClosedInternal();
    }

    public boolean isOperationalDayClosed() {
        return isTodayClosedInternal();
    }

    public void markFinanceSettingsDirty() {
        putMeta(getWritableDatabase(), "finance_settings_dirty", "1");
    }

    public int syncCount(String kind) {
        String sql;
        if("failed".equals(kind)) sql="SELECT COUNT(*) FROM sync_dirty WHERE sync_status='failed'";
        else if("conflict".equals(kind)) sql="SELECT COUNT(*) FROM sync_conflicts WHERE resolved=0";
        else sql="SELECT COUNT(*) FROM sync_dirty";
        Cursor c=getReadableDatabase().rawQuery(sql,null); int value=c.moveToFirst()?c.getInt(0):0; c.close(); return value;
    }

    public String syncMeta(String key) { return metaValue(getReadableDatabase(),key); }

    public int daysSinceLastVisit(long patientId) {
        if (!(can("view_patients") || can("register_visits"))) return 9999;
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT CAST(julianday('now','localtime') - julianday(MAX(COALESCE(completed_at,created_at))) AS INTEGER) " +
                        "FROM visits WHERE patient_id=? AND status=?",
                new String[]{String.valueOf(patientId), COMPLETED});
        int days = 9999;
        if (c.moveToFirst() && !c.isNull(0)) days = Math.max(0, c.getInt(0));
        c.close();
        return days;
    }

    private boolean patientExists(long patientId) {
        if (patientId <= 0) return false;
        Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM patients WHERE id=? LIMIT 1", new String[]{String.valueOf(patientId)});
        boolean exists = c.moveToFirst();
        c.close();
        return exists;
    }

    private int openQueueCountInternal() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM visits WHERE status NOT IN (?,?)", new String[]{COMPLETED, CANCELLED});
        int count = c.moveToFirst() ? c.getInt(0) : 0;
        c.close();
        return count;
    }

    private boolean isTodayClosedInternal() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM day_closures WHERE day=? AND is_reopened=0", new String[]{today()});
        boolean closed = c.moveToFirst() && c.getInt(0) > 0;
        c.close();
        return closed;
    }

    private long closureId(SQLiteDatabase db,String day) { Cursor c=db.rawQuery("SELECT id FROM day_closures WHERE day=?",new String[]{day}); long id=c.moveToFirst()?c.getLong(0):-1; c.close(); return id; }
    private void recomputePaidAmount(SQLiteDatabase db,long visitId) { Cursor c=db.rawQuery("SELECT COALESCE(SUM(amount),0) FROM payments WHERE visit_id=?",new String[]{String.valueOf(visitId)}); int paid=c.moveToFirst()?Math.max(0,c.getInt(0)):0; c.close(); ContentValues v=new ContentValues(); v.put("paid_amount",paid); db.update("visits",v,"id=?",new String[]{String.valueOf(visitId)}); }
    private void putActor(ContentValues v) { v.put("actor_user_id",auth.userId()); v.put("actor_display_name",auth.memberDisplayName()); v.put("actor_role",auth.memberRole()); }

    private int nextCardNo(SQLiteDatabase db) {
            long next = metaLong(db, "card_range_next", 0);
            long end = metaLong(db, "card_range_end", -1);
            if (next > 0 && next <= end) {
                putMeta(db, "card_range_next", String.valueOf(next + 1));
                return (int)Math.min(Integer.MAX_VALUE, next);
            }
            long temporary = metaLong(db, "temporary_card_next", -1);
            if (temporary >= 0) temporary = -1;
            putMeta(db, "temporary_card_next", String.valueOf(temporary - 1));
            return (int)Math.max(Integer.MIN_VALUE, temporary);
    }

    private Long patientIdByPhone(SQLiteDatabase db, String normalizedPhone) {
        Cursor c = db.rawQuery("SELECT id FROM patients WHERE normalized_phone=? LIMIT 1",
                new String[]{normalizedPhone});
        Long id = c.moveToFirst() ? c.getLong(0) : null;
        c.close();
        return id;
    }

    private Long lastCompletedVisitId(SQLiteDatabase db, long patientId) {
        Cursor c = db.rawQuery("SELECT id FROM visits WHERE patient_id=? AND status=? ORDER BY id DESC LIMIT 1",
                new String[]{String.valueOf(patientId), COMPLETED});
        Long id = c.moveToFirst() ? c.getLong(0) : null;
        c.close();
        return id;
    }

    private static long metaLong(SQLiteDatabase db, String key, long fallback) {
        try { return Long.parseLong(metaValue(db, key)); }
        catch (Exception ignored) { return fallback; }
    }

    private Patient patientFrom(Cursor c) {
        Patient p = new Patient();
        p.id = c.getLong(0);
        p.cardNo = c.getInt(1);
        p.name = c.getString(2);
        p.phone = c.getString(3);
        p.gender = c.getString(4);
        p.ageText = safe(c.getString(5));
        p.allergies = safe(c.getString(6));
        p.chronicConditions = safe(c.getString(7));
        p.currentMedications = safe(c.getString(8));
        p.createdAt = c.getString(9);
        return p;
    }

    private Visit getVisitInternal(SQLiteDatabase db, long id) {
        Cursor c = db.rawQuery(visitSelect() + "WHERE v.id=?", new String[]{String.valueOf(id)});
        Visit v = c.moveToFirst() ? visitFrom(c) : null;
        c.close();
        return v;
    }

    private Visit visitFrom(Cursor c) {
        Visit v = new Visit();
        v.id = c.getLong(0);
        v.patientId = c.getLong(1);
        v.type = c.getString(2);
        v.status = c.getString(3);
        v.fee = c.getInt(4);
        v.paidAmount = c.getInt(5);
        v.complaint = safe(c.getString(6));
        v.exam = safe(c.getString(7));
        v.diagnosis = safe(c.getString(8));
        v.labs = safe(c.getString(9));
        v.treatment = safe(c.getString(10));
        v.followup = safe(c.getString(11));
        v.createdAt = safe(c.getString(12));
        v.startedAt = safe(c.getString(13));
        v.completedAt = safe(c.getString(14));
        v.patientName = safe(c.getString(15));
        v.cardNo = c.getInt(16);
        v.assignedDoctorUserId = safe(c.getString(17));
        v.assignedDoctorName = safe(c.getString(18));
        if (!c.isNull(19)) v.followupOfVisitId = c.getLong(19);
        v.cancellationReason = safe(c.getString(20));
        v.cancelledAt = safe(c.getString(21));
        v.reopenedAt = safe(c.getString(22));
        v.temperature = safe(c.getString(23));
        v.bloodPressure = safe(c.getString(24));
        v.pulse = safe(c.getString(25));
        v.weight = safe(c.getString(26));
        v.oxygen = safe(c.getString(27));
        v.medications = safe(c.getString(28));
        v.draftSavedAt = safe(c.getString(29));
        return v;
    }

    private static String visitSelect() {
        return "SELECT v.id,v.patient_id,v.visit_type,v.status,v.fee,v.paid_amount,v.complaint,v.exam,v.diagnosis,v.labs,v.treatment,v.followup,v.created_at,v.started_at,v.completed_at,p.full_name,p.card_no," +
                "v.assigned_doctor_user_id,v.assigned_doctor_name,v.followup_of_visit_id,v.cancellation_reason,v.cancelled_at,v.reopened_at,v.temperature,v.blood_pressure,v.pulse,v.weight,v.oxygen,v.medications_text,v.draft_saved_at " +
                "FROM visits v JOIN patients p ON p.id=v.patient_id ";
    }

    private Visit sanitizeClinical(Visit v) {
        if (v == null || can("view_clinical")) return v;
        v.complaint = "";
        v.exam = "";
        v.diagnosis = "";
        v.labs = "";
        v.treatment = "";
        v.followup = "";
        return v;
    }

    private boolean can(String permission) {
        return auth.hasRemoteIdentity() && auth.can(permission);
    }

    private boolean canQueueRead() {
        return can("manage_queue") || can("view_clinical");
    }

    private boolean canFinanceRead() {
        return can("view_finance") || can("close_day");
    }

    private void audit(SQLiteDatabase db, String action, String entityType, long entityId, String details) {
        ContentValues a = new ContentValues();
        a.put("action", action);
        a.put("entity_type", entityType);
        a.put("entity_id", entityId);
        a.put("details", safe(details));
        a.put("actor_user_id", auth.userId());
        a.put("actor_display_name", auth.memberDisplayName());
        a.put("actor_role", auth.memberRole());
        a.put("created_at", now());
        db.insert("audit_log", null, a);
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()); }
    private static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }

    public static class Patient {
        public long id;
        public int cardNo;
        public String name = "", phone = "", gender = "", ageText = "", allergies = "", chronicConditions = "", currentMedications = "", createdAt = "";
    }

    public static class Visit {
        public long id, patientId, followupOfVisitId;
        public int cardNo, fee, paidAmount;
        public String patientName = "", type = "", status = "", complaint = "", exam = "", diagnosis = "", labs = "", treatment = "", followup = "", createdAt = "", startedAt = "", completedAt = "";
        public String assignedDoctorUserId = "", assignedDoctorName = "", cancellationReason = "", cancelledAt = "", reopenedAt = "";
        public String temperature = "", bloodPressure = "", pulse = "", weight = "", oxygen = "", medications = "", draftSavedAt = "";
        public int remaining() { return Math.max(0, fee - paidAmount); }
    }

    public static final class RegistrationResult {
        public final int code;
        public final long patientId, visitId;
        RegistrationResult(int code, long patientId, long visitId) {
            this.code = code; this.patientId = patientId; this.visitId = visitId;
        }
        static RegistrationResult failed(int code) { return new RegistrationResult(code, -1, -1); }
        public boolean success() { return code == 0 && patientId > 0 && visitId > 0; }
    }

    public static class Stats {
        public int totalVisits, totalCharges, totalPaid, waivedVisits, outstanding, openQueue, refunded, cancelledVisits, cash, bank, wallet, other;
    }
    public static class Payment { public long id,visitId; public int amount; public String method,eventType,reason,actorName,createdAt,patientName; public boolean reversed; }
    public static class Debt { public long visitId; public int cardNo,fee,paid; public String patientName,createdAt; public int remaining(){return Math.max(0,fee-paid);} }
}
