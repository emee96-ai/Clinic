package com.eman.clinic;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class ClinicDb extends SQLiteOpenHelper {
    public static final String NEW = "NEW";
    public static final String FREE_FOLLOWUP = "FREE_FOLLOWUP";
    public static final String PAID_FOLLOWUP = "PAID_FOLLOWUP";
    public static final String LAB_RESULT = "LAB_RESULT";

    public static final String REGISTERED = "REGISTERED";
    public static final String WAITING = "WAITING";
    public static final String IN_CONSULT = "IN_CONSULT";
    public static final String COMPLETED = "COMPLETED";

    public ClinicDb(Context context) {
        super(context, "clinic_offline.db", null, 2);
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE patients (id INTEGER PRIMARY KEY AUTOINCREMENT, card_no INTEGER NOT NULL UNIQUE, full_name TEXT NOT NULL, phone TEXT, gender TEXT, created_at TEXT NOT NULL)");
        db.execSQL("CREATE TABLE visits (id INTEGER PRIMARY KEY AUTOINCREMENT, patient_id INTEGER NOT NULL, visit_type TEXT NOT NULL, status TEXT NOT NULL, fee INTEGER NOT NULL DEFAULT 0, paid_amount INTEGER NOT NULL DEFAULT 0, complaint TEXT DEFAULT '', exam TEXT DEFAULT '', diagnosis TEXT DEFAULT '', labs TEXT DEFAULT '', treatment TEXT DEFAULT '', followup TEXT DEFAULT '', created_at TEXT NOT NULL, started_at TEXT, completed_at TEXT, FOREIGN KEY(patient_id) REFERENCES patients(id))");
        db.execSQL("CREATE TABLE payments (id INTEGER PRIMARY KEY AUTOINCREMENT, visit_id INTEGER NOT NULL, amount INTEGER NOT NULL, method TEXT NOT NULL, created_at TEXT NOT NULL)");
        db.execSQL("CREATE TABLE day_closures (id INTEGER PRIMARY KEY AUTOINCREMENT, day TEXT NOT NULL UNIQUE, total_visits INTEGER NOT NULL, total_charges INTEGER NOT NULL, total_paid INTEGER NOT NULL, total_waived INTEGER NOT NULL, outstanding INTEGER NOT NULL, closed_at TEXT NOT NULL)");
        db.execSQL("CREATE TABLE audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT, action TEXT NOT NULL, entity_type TEXT NOT NULL, entity_id INTEGER, details TEXT, created_at TEXT NOT NULL)");
        db.execSQL("CREATE INDEX idx_visits_patient ON visits(patient_id)");
        db.execSQL("CREATE INDEX idx_visits_status ON visits(status)");
        db.execSQL("CREATE INDEX idx_payments_visit ON payments(visit_id)");
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS audit_log");
            db.execSQL("DROP TABLE IF EXISTS day_closures");
            db.execSQL("DROP TABLE IF EXISTS payments");
            db.execSQL("DROP TABLE IF EXISTS visits");
            db.execSQL("DROP TABLE IF EXISTS patients");
            onCreate(db);
        }
    }

    public long createPatient(String name, String phone, String gender) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("card_no", nextCardNo());
        v.put("full_name", name.trim());
        v.put("phone", phone == null ? "" : phone.trim());
        v.put("gender", gender == null ? "" : gender);
        v.put("created_at", now());
        long id = db.insertOrThrow("patients", null, v);
        audit(db, "CREATE_PATIENT", "patient", id, name);
        return id;
    }

    public long createVisit(long patientId, String type, int fee) {
        if (hasOpenVisit(patientId)) return -1;
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("patient_id", patientId);
        v.put("visit_type", type);
        v.put("status", REGISTERED);
        v.put("fee", Math.max(0, fee));
        v.put("paid_amount", 0);
        v.put("created_at", now());
        long id = db.insertOrThrow("visits", null, v);
        audit(db, "CREATE_VISIT", "visit", id, type);
        return id;
    }

    public boolean hasOpenVisit(long patientId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM visits WHERE patient_id=? AND status<>?", new String[]{String.valueOf(patientId), COMPLETED});
        boolean result = c.moveToFirst() && c.getInt(0) > 0;
        c.close();
        return result;
    }

    public void sendToDoctor(long visitId) {
        setStatus(visitId, WAITING, "SEND_TO_DOCTOR");
    }

    public void startVisit(long visitId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("status", IN_CONSULT);
        v.put("started_at", now());
        db.update("visits", v, "id=? AND status=?", new String[]{String.valueOf(visitId), WAITING});
        audit(db, "START_VISIT", "visit", visitId, "");
    }

    public void saveClinical(long visitId, String complaint, String exam, String diagnosis, String labs, String treatment, String followup, boolean complete) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("complaint", safe(complaint));
        v.put("exam", safe(exam));
        v.put("diagnosis", safe(diagnosis));
        v.put("labs", safe(labs));
        v.put("treatment", safe(treatment));
        v.put("followup", safe(followup));
        if (complete) {
            v.put("status", COMPLETED);
            v.put("completed_at", now());
        } else {
            v.put("status", IN_CONSULT);
        }
        db.update("visits", v, "id=?", new String[]{String.valueOf(visitId)});
        audit(db, complete ? "COMPLETE_VISIT" : "SAVE_DRAFT", "visit", visitId, "");
    }

    public boolean recordPayment(long visitId, int requestedAmount, String method) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            Visit visit = getVisitInternal(db, visitId);
            if (visit == null) return false;
            int remaining = Math.max(0, visit.fee - visit.paidAmount);
            int amount = Math.min(Math.max(0, requestedAmount), remaining);
            if (amount <= 0) return false;

            ContentValues pay = new ContentValues();
            pay.put("visit_id", visitId);
            pay.put("amount", amount);
            pay.put("method", safe(method));
            pay.put("created_at", now());
            long paymentId = db.insertOrThrow("payments", null, pay);

            ContentValues upd = new ContentValues();
            upd.put("paid_amount", visit.paidAmount + amount);
            db.update("visits", upd, "id=?", new String[]{String.valueOf(visitId)});
            audit(db, "PAYMENT", "payment", paymentId, "visit=" + visitId + ", amount=" + amount);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    public Patient getPatient(long id) {
        Cursor c = getReadableDatabase().rawQuery("SELECT id, card_no, full_name, phone, gender, created_at FROM patients WHERE id=?", new String[]{String.valueOf(id)});
        Patient p = c.moveToFirst() ? patientFrom(c) : null;
        c.close();
        return p;
    }

    public Patient findPatient(String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) return null;
        Cursor c = getReadableDatabase().rawQuery("SELECT id, card_no, full_name, phone, gender, created_at FROM patients WHERE CAST(card_no AS TEXT)=? OR phone=? OR full_name LIKE ? ORDER BY id DESC LIMIT 1", new String[]{q, q, "%" + q + "%"});
        Patient p = c.moveToFirst() ? patientFrom(c) : null;
        c.close();
        return p;
    }

    public List<Patient> searchPatients(String query) {
        List<Patient> out = new ArrayList<>();
        String q = query == null ? "" : query.trim();
        Cursor c;
        if (q.isEmpty()) {
            c = getReadableDatabase().rawQuery("SELECT id, card_no, full_name, phone, gender, created_at FROM patients ORDER BY id DESC LIMIT 100", null);
        } else {
            c = getReadableDatabase().rawQuery("SELECT id, card_no, full_name, phone, gender, created_at FROM patients WHERE CAST(card_no AS TEXT) LIKE ? OR phone LIKE ? OR full_name LIKE ? ORDER BY id DESC LIMIT 100", new String[]{"%" + q + "%", "%" + q + "%", "%" + q + "%"});
        }
        while (c.moveToNext()) out.add(patientFrom(c));
        c.close();
        return out;
    }

    public Visit getVisit(long id) {
        return getVisitInternal(getReadableDatabase(), id);
    }

    public List<Visit> openQueue() {
        return queryVisits("WHERE v.status<>? ORDER BY v.id ASC", new String[]{COMPLETED});
    }

    public List<Visit> doctorQueue() {
        return queryVisits("WHERE v.status IN (?,?) ORDER BY CASE v.status WHEN 'IN_CONSULT' THEN 0 ELSE 1 END, v.id ASC", new String[]{IN_CONSULT, WAITING});
    }

    public List<Visit> visitsForPatient(long patientId) {
        return queryVisits("WHERE v.patient_id=? ORDER BY v.id DESC", new String[]{String.valueOf(patientId)});
    }

    public List<Visit> unpaidToday() {
        return queryVisits("WHERE date(v.created_at)=date('now','localtime') AND v.fee>v.paid_amount ORDER BY v.id ASC", new String[]{});
    }

    private List<Visit> queryVisits(String where, String[] args) {
        List<Visit> out = new ArrayList<>();
        String sql = "SELECT v.id,v.patient_id,v.visit_type,v.status,v.fee,v.paid_amount,v.complaint,v.exam,v.diagnosis,v.labs,v.treatment,v.followup,v.created_at,v.started_at,v.completed_at,p.full_name,p.card_no FROM visits v JOIN patients p ON p.id=v.patient_id " + where;
        Cursor c = getReadableDatabase().rawQuery(sql, args);
        while (c.moveToNext()) out.add(visitFrom(c));
        c.close();
        return out;
    }

    public Stats todayStats() {
        Stats s = new Stats();
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*), COALESCE(SUM(fee),0), COALESCE(SUM(paid_amount),0), COALESCE(SUM(CASE WHEN fee=0 THEN 1 ELSE 0 END),0) FROM visits WHERE date(created_at)=date('now','localtime')", null);
        if (c.moveToFirst()) {
            s.totalVisits = c.getInt(0);
            s.totalCharges = c.getInt(1);
            s.totalPaid = c.getInt(2);
            s.waivedVisits = c.getInt(3);
        }
        c.close();
        Cursor q = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM visits WHERE status<>?", new String[]{COMPLETED});
        if (q.moveToFirst()) s.openQueue = q.getInt(0);
        q.close();
        s.outstanding = Math.max(0, s.totalCharges - s.totalPaid);
        return s;
    }

    public boolean closeToday() {
        if (isTodayClosed()) return false;
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
        long id = db.insert("day_closures", null, v);
        if (id > 0) audit(db, "CLOSE_DAY", "day_closure", id, today());
        return id > 0;
    }

    public boolean isTodayClosed() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM day_closures WHERE day=?", new String[]{today()});
        boolean r = c.moveToFirst() && c.getInt(0) > 0;
        c.close();
        return r;
    }

    public int daysSinceLastVisit(long patientId) {
        Cursor c = getReadableDatabase().rawQuery("SELECT CAST(julianday('now','localtime') - julianday(MAX(created_at)) AS INTEGER) FROM visits WHERE patient_id=? AND status=?", new String[]{String.valueOf(patientId), COMPLETED});
        int days = 9999;
        if (c.moveToFirst() && !c.isNull(0)) days = Math.max(0, c.getInt(0));
        c.close();
        return days;
    }

    private void setStatus(long visitId, String status, String auditAction) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("status", status);
        db.update("visits", v, "id=?", new String[]{String.valueOf(visitId)});
        audit(db, auditAction, "visit", visitId, status);
    }

    private int nextCardNo() {
        Cursor c = getReadableDatabase().rawQuery("SELECT COALESCE(MAX(card_no),1000)+1 FROM patients", null);
        int n = c.moveToFirst() ? c.getInt(0) : 1001;
        c.close();
        return n;
    }

    private Patient patientFrom(Cursor c) {
        Patient p = new Patient();
        p.id = c.getLong(0);
        p.cardNo = c.getInt(1);
        p.name = c.getString(2);
        p.phone = c.getString(3);
        p.gender = c.getString(4);
        p.createdAt = c.getString(5);
        return p;
    }

    private Visit getVisitInternal(SQLiteDatabase db, long id) {
        Cursor c = db.rawQuery("SELECT v.id,v.patient_id,v.visit_type,v.status,v.fee,v.paid_amount,v.complaint,v.exam,v.diagnosis,v.labs,v.treatment,v.followup,v.created_at,v.started_at,v.completed_at,p.full_name,p.card_no FROM visits v JOIN patients p ON p.id=v.patient_id WHERE v.id=?", new String[]{String.valueOf(id)});
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
        return v;
    }

    private void audit(SQLiteDatabase db, String action, String entityType, long entityId, String details) {
        ContentValues a = new ContentValues();
        a.put("action", action);
        a.put("entity_type", entityType);
        a.put("entity_id", entityId);
        a.put("details", safe(details));
        a.put("created_at", now());
        db.insert("audit_log", null, a);
    }

    private static String safe(String s) { return s == null ? "" : s; }
    private static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()); }
    private static String today() { return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()); }

    public static class Patient {
        public long id;
        public int cardNo;
        public String name = "", phone = "", gender = "", createdAt = "";
    }

    public static class Visit {
        public long id, patientId;
        public int cardNo, fee, paidAmount;
        public String patientName = "", type = "", status = "", complaint = "", exam = "", diagnosis = "", labs = "", treatment = "", followup = "", createdAt = "", startedAt = "", completedAt = "";
        public int remaining() { return Math.max(0, fee - paidAmount); }
    }

    public static class Stats {
        public int totalVisits, totalCharges, totalPaid, waivedVisits, outstanding, openQueue;
    }
}
