package com.eman.clinic;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

/** Installs database-level change tracking without touching the clinic workflow code. */
public final class SyncBootstrap {
    private SyncBootstrap() {}

    public static void install(Context context) {
        try {
            ClinicDb helper = new ClinicDb(context.getApplicationContext());
            SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try {
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_entity_keys (entity_type TEXT NOT NULL, local_id INTEGER NOT NULL, sync_key TEXT NOT NULL UNIQUE, PRIMARY KEY(entity_type, local_id))");
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_dirty (entity_type TEXT NOT NULL, local_id INTEGER NOT NULL, changed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(entity_type, local_id))");
                db.execSQL("CREATE TABLE IF NOT EXISTS sync_meta (meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)");

                db.execSQL("DROP TRIGGER IF EXISTS trg_sync_patients_insert");
                db.execSQL("DROP TRIGGER IF EXISTS trg_sync_patients_update");
                db.execSQL("DROP TRIGGER IF EXISTS trg_sync_visits_insert");
                db.execSQL("DROP TRIGGER IF EXISTS trg_sync_visits_update");

                String trackingWhen = " WHEN COALESCE((SELECT meta_value FROM sync_meta WHERE meta_key='suppress_tracking'),'0')<>'1' ";
                db.execSQL("CREATE TRIGGER trg_sync_patients_insert AFTER INSERT ON patients" + trackingWhen + "BEGIN " +
                        "INSERT OR IGNORE INTO sync_entity_keys(entity_type,local_id,sync_key) VALUES('patient',NEW.id,lower(hex(randomblob(16)))); " +
                        "INSERT OR REPLACE INTO sync_dirty(entity_type,local_id,changed_at) VALUES('patient',NEW.id,STRFTIME('%Y-%m-%d %H:%M:%f','now')); END");
                db.execSQL("CREATE TRIGGER trg_sync_patients_update AFTER UPDATE ON patients" + trackingWhen + "BEGIN " +
                        "INSERT OR IGNORE INTO sync_entity_keys(entity_type,local_id,sync_key) VALUES('patient',NEW.id,lower(hex(randomblob(16)))); " +
                        "INSERT OR REPLACE INTO sync_dirty(entity_type,local_id,changed_at) VALUES('patient',NEW.id,STRFTIME('%Y-%m-%d %H:%M:%f','now')); END");
                db.execSQL("CREATE TRIGGER trg_sync_visits_insert AFTER INSERT ON visits" + trackingWhen + "BEGIN " +
                        "INSERT OR IGNORE INTO sync_entity_keys(entity_type,local_id,sync_key) VALUES('visit',NEW.id,lower(hex(randomblob(16)))); " +
                        "INSERT OR REPLACE INTO sync_dirty(entity_type,local_id,changed_at) VALUES('visit',NEW.id,STRFTIME('%Y-%m-%d %H:%M:%f','now')); END");
                db.execSQL("CREATE TRIGGER trg_sync_visits_update AFTER UPDATE ON visits" + trackingWhen + "BEGIN " +
                        "INSERT OR IGNORE INTO sync_entity_keys(entity_type,local_id,sync_key) VALUES('visit',NEW.id,lower(hex(randomblob(16)))); " +
                        "INSERT OR REPLACE INTO sync_dirty(entity_type,local_id,changed_at) VALUES('visit',NEW.id,STRFTIME('%Y-%m-%d %H:%M:%f','now')); END");

                db.execSQL("INSERT OR IGNORE INTO sync_entity_keys(entity_type,local_id,sync_key) SELECT 'patient',id,lower(hex(randomblob(16))) FROM patients");
                db.execSQL("INSERT OR IGNORE INTO sync_entity_keys(entity_type,local_id,sync_key) SELECT 'visit',id,lower(hex(randomblob(16))) FROM visits");
                db.execSQL("INSERT OR IGNORE INTO sync_dirty(entity_type,local_id,changed_at) SELECT 'patient',id,STRFTIME('%Y-%m-%d %H:%M:%f','now') FROM patients");
                db.execSQL("INSERT OR IGNORE INTO sync_dirty(entity_type,local_id,changed_at) SELECT 'visit',id,STRFTIME('%Y-%m-%d %H:%M:%f','now') FROM visits");

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception ignored) {
            // Sync tracking must never stop reception or doctor work.
        }
    }
}
