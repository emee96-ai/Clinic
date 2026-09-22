package com.eman.clinic;

import android.content.Context;
import android.database.Cursor;
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

                boolean initialSeed = !metaEquals(db, "sync_tracking_seeded", "1");

                String[] triggers = {
                        "trg_sync_patients_insert", "trg_sync_patients_update",
                        "trg_sync_visits_insert", "trg_sync_visits_update",
                        "trg_sync_payments_insert", "trg_sync_payments_update",
                        "trg_sync_closures_insert", "trg_sync_closures_update"
                };
                for (String trigger : triggers) db.execSQL("DROP TRIGGER IF EXISTS " + trigger);

                String trackingWhen = " WHEN COALESCE((SELECT meta_value FROM sync_meta WHERE meta_key='suppress_tracking'),'0')<>'1' ";
                createTrackingTriggers(db, "patients", "patient", trackingWhen);
                createTrackingTriggers(db, "visits", "visit", trackingWhen);
                createTrackingTriggers(db, "payments", "payment", trackingWhen);
                createTrackingTriggers(db, "day_closures", "day_closure", trackingWhen);

                ensureKeys(db, "patients", "patient");
                ensureKeys(db, "visits", "visit");
                ensureKeys(db, "payments", "payment");
                ensureKeys(db, "day_closures", "day_closure");

                if (initialSeed) {
                    markExistingDirty(db, "patients", "patient");
                    markExistingDirty(db, "visits", "visit");
                    markExistingDirty(db, "payments", "payment");
                    markExistingDirty(db, "day_closures", "day_closure");
                    db.execSQL("INSERT OR REPLACE INTO sync_meta(meta_key,meta_value) VALUES('sync_tracking_seeded','1')");
                }

                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (Exception ignored) {
            // Sync tracking must never stop reception or doctor work.
        }
    }

    private static void createTrackingTriggers(SQLiteDatabase db, String table, String entity, String when) {
        String prefix;
        if ("patients".equals(table)) prefix = "patients";
        else if ("visits".equals(table)) prefix = "visits";
        else if ("payments".equals(table)) prefix = "payments";
        else prefix = "closures";

        db.execSQL("CREATE TRIGGER trg_sync_" + prefix + "_insert AFTER INSERT ON " + table + when + "BEGIN " +
                "INSERT OR IGNORE INTO sync_entity_keys(entity_type,local_id,sync_key) VALUES('" + entity + "',NEW.id,lower(hex(randomblob(16)))); " +
                "INSERT OR REPLACE INTO sync_dirty(entity_type,local_id,changed_at) VALUES('" + entity + "',NEW.id,STRFTIME('%Y-%m-%d %H:%M:%f','now')); END");
        db.execSQL("CREATE TRIGGER trg_sync_" + prefix + "_update AFTER UPDATE ON " + table + when + "BEGIN " +
                "INSERT OR IGNORE INTO sync_entity_keys(entity_type,local_id,sync_key) VALUES('" + entity + "',NEW.id,lower(hex(randomblob(16)))); " +
                "INSERT OR REPLACE INTO sync_dirty(entity_type,local_id,changed_at) VALUES('" + entity + "',NEW.id,STRFTIME('%Y-%m-%d %H:%M:%f','now')); END");
    }

    private static void ensureKeys(SQLiteDatabase db, String table, String entity) {
        db.execSQL("INSERT OR IGNORE INTO sync_entity_keys(entity_type,local_id,sync_key) SELECT '" + entity + "',id,lower(hex(randomblob(16))) FROM " + table);
    }

    private static void markExistingDirty(SQLiteDatabase db, String table, String entity) {
        db.execSQL("INSERT OR IGNORE INTO sync_dirty(entity_type,local_id,changed_at) SELECT '" + entity + "',id,STRFTIME('%Y-%m-%d %H:%M:%f','now') FROM " + table);
    }

    private static boolean metaEquals(SQLiteDatabase db, String key, String expected) {
        Cursor c = db.rawQuery("SELECT meta_value FROM sync_meta WHERE meta_key=?", new String[]{key});
        try { return c.moveToFirst() && expected.equals(c.getString(0)); }
        finally { c.close(); }
    }
}