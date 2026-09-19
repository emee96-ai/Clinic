package com.eman.clinic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int NAV_HOME = 0, NAV_QUEUE = 1, NAV_PATIENTS = 2, NAV_FINANCE = 3;
    private static final String ROLE_RECEPTION = "RECEPTION";
    private static final String ROLE_DOCTOR = "DOCTOR";
    private static final String ROLE_ADMIN = "ADMIN";

    private final int primary = Color.rgb(14, 113, 105);
    private final int primaryDark = Color.rgb(8, 78, 73);
    private final int surface = Color.WHITE;
    private final int bg = Color.rgb(245, 247, 248);
    private final int ink = Color.rgb(24, 35, 39);
    private final int muted = Color.rgb(103, 116, 121);
    private final int line = Color.rgb(226, 231, 233);
    private final int warning = Color.rgb(175, 105, 16);

    private ClinicDb db;
    private SharedPreferences prefs;
    private LinearLayout content;
    private String role;

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Window window = getWindow();
        window.setStatusBarColor(surface);
        window.setNavigationBarColor(surface);
        db = new ClinicDb(this);
        prefs = getSharedPreferences("clinic_settings", MODE_PRIVATE);
        role = prefs.getString("role", ROLE_RECEPTION);
        showHome();
        if (!prefs.contains("role_chosen")) selectRoleDialog(true);
    }

    private void base(String title, String subtitle, int selectedNav) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(16), dp(20), dp(14));
        header.setBackgroundColor(surface);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = tv(prefs.getString("clinic_name", "العيادة"), 21, ink, true);
        top.addView(brand, new LinearLayout.LayoutParams(0, dp(42), 1));
        TextView roleChip = chip(roleLabel(role), primary, Color.WHITE);
        roleChip.setOnClickListener(v -> selectRoleDialog(false));
        top.addView(roleChip);
        header.addView(top);
        header.addView(tv(title, 26, ink, true));
        TextView sub = tv(subtitle, 13, muted, false);
        sub.setPadding(0, dp(3), 0, 0);
        header.addView(sub);
        root.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(26));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        root.addView(bottomNav(selectedNav), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)));
        setContentView(root);
    }

    private LinearLayout bottomNav(int selected) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(6), dp(4), dp(6), dp(7));
        bar.setBackgroundColor(surface);
        bar.addView(navItem("الرئيسية", selected == NAV_HOME, v -> showHome()), navParams());
        bar.addView(navItem("الطابور", selected == NAV_QUEUE, v -> showQueue()), navParams());
        bar.addView(navItem("المرضى", selected == NAV_PATIENTS, v -> showPatients("")), navParams());
        if (ROLE_DOCTOR.equals(role)) {
            bar.addView(navItem("الطبيب", false, v -> showDoctor()), navParams());
        } else {
            bar.addView(navItem("الحسابات", selected == NAV_FINANCE, v -> showFinance()), navParams());
        }
        return bar;
    }

    private LinearLayout.LayoutParams navParams() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1); }

    private TextView navItem(String label, boolean selected, View.OnClickListener listener) {
        TextView t = tv(label, 13, selected ? primary : muted, selected);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(selected ? Color.rgb(232, 246, 244) : Color.TRANSPARENT, 14));
        t.setOnClickListener(listener);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        p.setMargins(dp(3), dp(4), dp(3), dp(4));
        t.setLayoutParams(p);
        return t;
    }

    private void showHome() {
        base("لوحة اليوم", "إدارة المرضى والطابور والكشف والحسابات بدون إنترنت", NAV_HOME);
        ClinicDb.Stats s = db.todayStats();
        content.addView(statRow("زيارات اليوم", String.valueOf(s.totalVisits), "في الطابور", String.valueOf(s.openQueue)));
        content.addView(space(10));
        content.addView(statRow("المحصّل", money(s.totalPaid), "المتبقي", money(s.outstanding)));
        content.addView(space(16));

        if (ROLE_RECEPTION.equals(role)) {
            section("شغل المسجلة", "سجلي المريض ثم أرسليه للطبيب");
            content.addView(primaryButton("تسجيل مريض جديد", v -> newPatientDialog()));
            content.addView(space(8));
            content.addView(secondaryButton("مريض مسجل / مقابلة / نتيجة فحوصات", v -> lookupPatientDialog()));
        } else if (ROLE_DOCTOR.equals(role)) {
            section("شغل الطبيب", "الحالات المرسلة من المسجلة تظهر هنا");
            content.addView(primaryButton("فتح طابور الطبيب", v -> showDoctor()));
        } else {
            section("الإدارة", "متابعة التشغيل والتحصيل وإعدادات العيادة");
            content.addView(primaryButton("الحسابات وإغلاق اليوم", v -> showFinance()));
            content.addView(space(8));
            content.addView(secondaryButton("إعدادات العيادة", v -> showSettings()));
        }

        content.addView(space(18));
        section("آخر حركة في الطابور", "الحالات المفتوحة الآن");
        List<ClinicDb.Visit> q = db.openQueue();
        if (q.isEmpty()) empty("الطابور فاضي حالياً");
        else {
            int max = Math.min(4, q.size());
            for (int i = 0; i < max; i++) content.addView(queueCard(q.get(i), false));
        }
        content.addView(space(8));
        TextView settings = link("الإعدادات وتغيير الدور");
        settings.setOnClickListener(v -> showSettings());
        content.addView(settings);
    }

    private void showQueue() {
        base("طابور العيادة", "من التسجيل إلى دخول الطبيب", NAV_QUEUE);
        if (!ROLE_DOCTOR.equals(role)) {
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            TextView a = compactButton("+ مريض جديد", true, v -> newPatientDialog());
            TextView b = compactButton("مريض مسجل", false, v -> lookupPatientDialog());
            actions.addView(a, new LinearLayout.LayoutParams(0, dp(48), 1));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(48), 1); bp.setMargins(dp(8),0,0,0);
            actions.addView(b, bp);
            content.addView(actions);
            content.addView(space(14));
        }
        List<ClinicDb.Visit> visits = db.openQueue();
        if (visits.isEmpty()) {
            empty("لا توجد حالات مفتوحة");
            return;
        }
        for (ClinicDb.Visit v : visits) content.addView(queueCard(v, true));
    }

    private View queueCard(ClinicDb.Visit visit, boolean actions) {
        LinearLayout card = card();
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = tv(visit.patientName, 18, ink, true);
        head.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(chip(statusLabel(visit.status), statusColor(visit.status), Color.WHITE));
        card.addView(head);
        card.addView(tv("كرت #" + visit.cardNo + "  •  " + typeLabel(visit.type), 14, muted, false));
        if (visit.fee == 0) card.addView(tv("معفاة من الرسوم", 13, primary, true));
        else card.addView(tv("الرسوم: " + money(visit.fee) + "  |  المدفوع: " + money(visit.paidAmount), 13, muted, false));

        if (actions) {
            card.addView(space(8));
            if (ClinicDb.REGISTERED.equals(visit.status) && !ROLE_DOCTOR.equals(role)) {
                card.addView(primaryButton("إرسال للطبيب", v -> { db.sendToDoctor(visit.id); toast("تم الإرسال للطبيب"); showQueue(); }));
            } else if (ClinicDb.WAITING.equals(visit.status) && (ROLE_DOCTOR.equals(role) || ROLE_ADMIN.equals(role))) {
                card.addView(primaryButton("بدء الكشف", v -> { db.startVisit(visit.id); showDoctorVisit(visit.id); }));
            } else if (ClinicDb.IN_CONSULT.equals(visit.status) && (ROLE_DOCTOR.equals(role) || ROLE_ADMIN.equals(role))) {
                card.addView(primaryButton("متابعة الكشف", v -> showDoctorVisit(visit.id)));
            }
        }
        return card;
    }

    private void showDoctor() {
        base("شاشة الطبيب", "المرضى المنتظرون والكشف الجاري", -1);
        List<ClinicDb.Visit> list = db.doctorQueue();
        if (list.isEmpty()) { empty("لا يوجد مريض بانتظار الطبيب حالياً"); return; }
        for (ClinicDb.Visit visit : list) content.addView(queueCard(visit, true));
    }

    private void showDoctorVisit(long visitId) {
        ClinicDb.Visit visit = db.getVisit(visitId);
        if (visit == null) { showDoctor(); return; }
        ClinicDb.Patient patient = db.getPatient(visit.patientId);
        base("كشف المريض", "كرت #" + visit.cardNo + " • " + visit.patientName + " • " + typeLabel(visit.type), -1);

        LinearLayout identity = card();
        identity.addView(tv(visit.patientName, 21, ink, true));
        identity.addView(tv((patient == null ? "" : safe(patient.phone)) + "   " + (patient == null ? "" : safe(patient.gender)), 13, muted, false));
        identity.addView(tv("الزيارة: " + typeLabel(visit.type), 14, primary, true));
        content.addView(identity);

        List<ClinicDb.Visit> history = db.visitsForPatient(visit.patientId);
        int previous = Math.max(0, history.size() - 1);
        content.addView(tv("زيارات سابقة: " + previous, 13, muted, false));
        content.addView(space(10));

        EditText complaint = field("الشكوى الرئيسية والأعراض", true); complaint.setText(visit.complaint);
        EditText exam = field("الفحص السريري", true); exam.setText(visit.exam);
        EditText diagnosis = field("التشخيص", true); diagnosis.setText(visit.diagnosis);
        EditText labs = field("الفحوصات المطلوبة / نتائج الفحوصات", true); labs.setText(visit.labs);
        EditText treatment = field("العلاج والروشتة", true); treatment.setText(visit.treatment);
        EditText followup = field("خطة المتابعة والتعليمات", true); followup.setText(visit.followup);
        content.addView(labeled("الشكوى", complaint));
        content.addView(labeled("الفحص السريري", exam));
        content.addView(labeled("التشخيص", diagnosis));
        content.addView(labeled("الفحوصات", labs));
        content.addView(labeled("العلاج", treatment));
        content.addView(labeled("المتابعة", followup));

        content.addView(secondaryButton("حفظ كمسودة", v -> {
            db.saveClinical(visitId, str(complaint), str(exam), str(diagnosis), str(labs), str(treatment), str(followup), false);
            toast("تم حفظ المسودة على الجهاز");
        }));
        content.addView(space(8));
        content.addView(primaryButton("إنهاء وإغلاق الكشف", v -> {
            db.saveClinical(visitId, str(complaint), str(exam), str(diagnosis), str(labs), str(treatment), str(followup), true);
            toast("تم إغلاق الزيارة وحفظها في السجل");
            showDoctor();
        }));
    }

    private void showPatients(String query) {
        base("سجل المرضى", "البحث بالكرت أو الاسم أو رقم الهاتف", NAV_PATIENTS);
        LinearLayout searchBox = new LinearLayout(this);
        searchBox.setOrientation(LinearLayout.HORIZONTAL);
        EditText search = field("ابحث...", false);
        search.setSingleLine(true);
        search.setText(query);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, dp(50), 1));
        TextView go = compactButton("بحث", true, v -> showPatients(str(search)));
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(82), dp(50)); gp.setMargins(dp(8),0,0,0);
        searchBox.addView(go, gp);
        content.addView(searchBox);
        content.addView(space(14));

        List<ClinicDb.Patient> list = db.searchPatients(query);
        if (list.isEmpty()) { empty("ما لقينا مريض بالمعلومة دي"); return; }
        for (ClinicDb.Patient p : list) {
            LinearLayout c = card();
            c.addView(tv(p.name, 18, ink, true));
            c.addView(tv("كرت #" + p.cardNo + (safe(p.phone).isEmpty() ? "" : "  •  " + p.phone), 13, muted, false));
            c.setOnClickListener(v -> showPatientDetail(p.id));
            content.addView(c);
        }
    }

    private void showPatientDetail(long patientId) {
        ClinicDb.Patient p = db.getPatient(patientId);
        if (p == null) { showPatients(""); return; }
        base("ملف المريض", "السجل الطبي والزيارات السابقة", NAV_PATIENTS);
        LinearLayout profile = card();
        profile.addView(tv(p.name, 23, ink, true));
        profile.addView(tv("رقم الكرت: #" + p.cardNo, 15, primary, true));
        profile.addView(tv("الهاتف: " + (safe(p.phone).isEmpty() ? "غير مسجل" : p.phone), 14, muted, false));
        profile.addView(tv("النوع: " + (safe(p.gender).isEmpty() ? "غير محدد" : p.gender), 14, muted, false));
        content.addView(profile);

        if (!ROLE_DOCTOR.equals(role)) {
            content.addView(primaryButton("إضافة زيارة جديدة", v -> existingVisitDialog(p, false)));
            content.addView(space(8));
            content.addView(secondaryButton("إضافة نتيجة فحوصات", v -> existingVisitDialog(p, true)));
        }
        content.addView(space(18));
        section("تاريخ الزيارات", "الأحدث أولاً");
        List<ClinicDb.Visit> visits = db.visitsForPatient(patientId);
        if (visits.isEmpty()) empty("لا توجد زيارات سابقة");
        for (ClinicDb.Visit visit : visits) {
            LinearLayout c = card();
            c.addView(tv(typeLabel(visit.type) + "  •  " + visit.createdAt, 14, primary, true));
            c.addView(tv("الحالة: " + statusLabel(visit.status), 13, muted, false));
            if (!safe(visit.diagnosis).isEmpty()) c.addView(tv("التشخيص: " + visit.diagnosis, 15, ink, true));
            if (!safe(visit.labs).isEmpty()) c.addView(tv("الفحوصات: " + visit.labs, 14, ink, false));
            if (!safe(visit.treatment).isEmpty()) c.addView(tv("العلاج: " + visit.treatment, 14, ink, false));
            if (!safe(visit.followup).isEmpty()) c.addView(tv("المتابعة: " + visit.followup, 14, muted, false));
            content.addView(c);
        }
    }

    private void showFinance() {
        if (ROLE_DOCTOR.equals(role)) { showHome(); return; }
        base("حسابات اليوم", "التحصيل والمدفوعات وإغلاق اليوم", NAV_FINANCE);
        ClinicDb.Stats s = db.todayStats();
        content.addView(statRow("المستحق", money(s.totalCharges), "المحصّل", money(s.totalPaid)));
        content.addView(space(10));
        content.addView(statRow("المتبقي", money(s.outstanding), "المعفاة", String.valueOf(s.waivedVisits)));
        content.addView(space(16));
        section("مبالغ غير مكتملة", "يمكن تسجيل دفع كامل أو جزئي");
        List<ClinicDb.Visit> unpaid = db.unpaidToday();
        if (unpaid.isEmpty()) empty("ما في مبالغ معلّقة لليوم");
        for (ClinicDb.Visit visit : unpaid) {
            LinearLayout c = card();
            c.addView(tv(visit.patientName + "  •  #" + visit.cardNo, 17, ink, true));
            c.addView(tv(typeLabel(visit.type), 13, muted, false));
            c.addView(tv("المتبقي: " + money(visit.remaining()), 15, warning, true));
            c.addView(space(6));
            c.addView(primaryButton("تسجيل دفعة", v -> paymentDialog(visit)));
            content.addView(c);
        }
        content.addView(space(16));
        boolean closed = db.isTodayClosed();
        TextView close = closed ? secondaryButton("اليوم مقفول ✓", v -> toast("تم إغلاق اليوم مسبقاً")) : primaryButton("إغلاق حساب اليوم", v -> {
            if (db.closeToday()) { toast("تم حفظ إغلاق اليوم"); showFinance(); }
            else toast("اليوم مقفول مسبقاً");
        });
        content.addView(close);
    }

    private void showSettings() {
        base("إعدادات العيادة", "إعدادات هذا الجهاز", -1);
        EditText clinic = field("اسم العيادة", false); clinic.setText(prefs.getString("clinic_name", "العيادة"));
        EditText fee = field("رسوم الكشف", false); fee.setInputType(InputType.TYPE_CLASS_NUMBER); fee.setText(String.valueOf(prefs.getInt("visit_fee", 10000)));
        EditText resultFee = field("رسوم نتيجة الفحوصات", false); resultFee.setInputType(InputType.TYPE_CLASS_NUMBER); resultFee.setText(String.valueOf(prefs.getInt("result_fee", 0)));
        EditText days = field("مدة المقابلة المجانية بالأيام", false); days.setInputType(InputType.TYPE_CLASS_NUMBER); days.setText(String.valueOf(prefs.getInt("followup_days", 7)));
        content.addView(labeled("اسم العيادة", clinic));
        content.addView(labeled("رسوم الكشف / المقابلة البعيدة", fee));
        content.addView(labeled("رسوم إحضار نتيجة فحوصات", resultFee));
        content.addView(labeled("المقابلة المجانية", days));
        content.addView(primaryButton("حفظ الإعدادات", v -> {
            prefs.edit().putString("clinic_name", str(clinic).trim())
                    .putInt("visit_fee", intValue(fee, 10000))
                    .putInt("result_fee", intValue(resultFee, 0))
                    .putInt("followup_days", Math.max(1, intValue(days, 7))).apply();
            toast("تم حفظ الإعدادات"); showHome();
        }));
        content.addView(space(10));
        content.addView(secondaryButton("تغيير دور الجهاز: " + roleLabel(role), v -> selectRoleDialog(false)));
        content.addView(space(20));
        LinearLayout info = card();
        info.addView(tv("وضع العمل", 16, ink, true));
        info.addView(tv("البيانات محفوظة محلياً على الجهاز وتستمر بعد إغلاق التطبيق. النسخة 1.1", 13, muted, false));
        content.addView(info);
    }

    private void newPatientDialog() {
        LinearLayout box = dialogBox();
        EditText name = field("الاسم الكامل", false);
        EditText phone = field("رقم الهاتف", false); phone.setInputType(InputType.TYPE_CLASS_PHONE);
        Spinner gender = spinner(new String[]{"غير محدد", "أنثى", "ذكر"});
        box.addView(name); box.addView(space(8)); box.addView(phone); box.addView(space(8)); box.addView(gender);
        new AlertDialog.Builder(this)
                .setTitle("تسجيل مريض جديد")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("تسجيل", (d, w) -> {
                    String n = str(name).trim();
                    if (n.length() < 2) { toast("اكتبي اسم المريض"); return; }
                    long patientId = db.createPatient(n, str(phone), String.valueOf(gender.getSelectedItem()));
                    long visitId = db.createVisit(patientId, ClinicDb.NEW, visitFee());
                    ClinicDb.Patient p = db.getPatient(patientId);
                    toast("تم التسجيل • كرت #" + (p == null ? "" : p.cardNo));
                    if (visitId > 0) showQueue(); else showPatientDetail(patientId);
                }).show();
    }

    private void lookupPatientDialog() {
        LinearLayout box = dialogBox();
        EditText q = field("رقم الكرت أو الهاتف أو الاسم", false);
        box.addView(q);
        new AlertDialog.Builder(this)
                .setTitle("البحث عن مريض")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("بحث", (d, w) -> {
                    ClinicDb.Patient p = db.findPatient(str(q));
                    if (p == null) toast("المريض غير موجود"); else showPatientDetail(p.id);
                }).show();
    }

    private void existingVisitDialog(ClinicDb.Patient patient, boolean forceResult) {
        if (db.hasOpenVisit(patient.id)) { toast("عند المريض زيارة مفتوحة بالفعل"); showQueue(); return; }
        int followDays = prefs.getInt("followup_days", 7);
        int since = db.daysSinceLastVisit(patient.id);
        String suggested = since <= followDays ? ClinicDb.FREE_FOLLOWUP : ClinicDb.PAID_FOLLOWUP;
        String[] types = forceResult ? new String[]{ClinicDb.LAB_RESULT} : new String[]{suggested, ClinicDb.LAB_RESULT, ClinicDb.PAID_FOLLOWUP, ClinicDb.FREE_FOLLOWUP};
        String[] labels = new String[types.length];
        for (int i = 0; i < types.length; i++) labels[i] = typeLabel(types[i]);
        Spinner spinner = spinner(labels);
        LinearLayout box = dialogBox();
        box.addView(tv("اقتراح النظام: " + typeLabel(suggested) + (since == 9999 ? "" : " • آخر زيارة قبل " + since + " يوم"), 13, muted, false));
        box.addView(space(8)); box.addView(spinner);
        new AlertDialog.Builder(this)
                .setTitle(patient.name)
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("إضافة للطابور", (d, w) -> {
                    String type = types[spinner.getSelectedItemPosition()];
                    long id = db.createVisit(patient.id, type, feeFor(type));
                    if (id < 0) toast("تعذر الإضافة: توجد زيارة مفتوحة");
                    else { toast("تمت إضافة الزيارة"); showQueue(); }
                }).show();
    }

    private void paymentDialog(ClinicDb.Visit visit) {
        LinearLayout box = dialogBox();
        EditText amount = field("المبلغ", false); amount.setInputType(InputType.TYPE_CLASS_NUMBER); amount.setText(String.valueOf(visit.remaining()));
        Spinner method = spinner(new String[]{"كاش", "تحويل بنكي", "محفظة", "أخرى"});
        box.addView(tv("المتبقي على " + visit.patientName + ": " + money(visit.remaining()), 14, muted, false));
        box.addView(space(8)); box.addView(amount); box.addView(space(8)); box.addView(method);
        new AlertDialog.Builder(this)
                .setTitle("تسجيل دفعة")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", (d, w) -> {
                    int a = intValue(amount, 0);
                    if (db.recordPayment(visit.id, a, String.valueOf(method.getSelectedItem()))) { toast("تم تسجيل الدفعة"); showFinance(); }
                    else toast("المبلغ غير صحيح");
                }).show();
    }

    private void selectRoleDialog(boolean firstTime) {
        String[] labels = {"المسجلة / الاستقبال", "الطبيب", "الإدارة"};
        String[] values = {ROLE_RECEPTION, ROLE_DOCTOR, ROLE_ADMIN};
        new AlertDialog.Builder(this)
                .setTitle(firstTime ? "اختاري استخدام هذا الجهاز" : "تغيير دور الجهاز")
                .setItems(labels, (d, which) -> {
                    role = values[which];
                    prefs.edit().putString("role", role).putBoolean("role_chosen", true).apply();
                    showHome();
                })
                .setCancelable(!firstTime)
                .show();
    }

    private LinearLayout statRow(String l1, String v1, String l2, String v2) {
        LinearLayout row = new LinearLayout(this);
        LinearLayout one = statCard(l1, v1);
        LinearLayout two = statCard(l2, v2);
        row.addView(one, new LinearLayout.LayoutParams(0, dp(104), 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(104), 1); p.setMargins(dp(10),0,0,0);
        row.addView(two, p);
        return row;
    }

    private LinearLayout statCard(String label, String value) {
        LinearLayout c = card();
        c.setPadding(dp(14), dp(14), dp(14), dp(12));
        c.addView(tv(label, 13, muted, false));
        TextView val = tv(value, 21, ink, true); val.setPadding(0, dp(4), 0, 0); c.addView(val);
        return c;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(15), dp(14), dp(15), dp(14));
        c.setBackground(round(surface, 18));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, dp(10));
        c.setLayoutParams(p);
        c.setElevation(dp(1));
        return c;
    }

    private void section(String title, String sub) {
        content.addView(tv(title, 19, ink, true));
        TextView s = tv(sub, 13, muted, false); s.setPadding(0, dp(2), 0, dp(10)); content.addView(s);
    }

    private void empty(String message) {
        LinearLayout c = card();
        TextView t = tv(message, 15, muted, false); t.setGravity(Gravity.CENTER); t.setPadding(0, dp(18), 0, dp(18)); c.addView(t);
        content.addView(c);
    }

    private LinearLayout labeled(String label, View field) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView l = tv(label, 13, ink, true); l.setPadding(dp(3), 0, dp(3), dp(5)); box.addView(l);
        box.addView(field);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); p.setMargins(0,0,0,dp(12)); box.setLayoutParams(p);
        return box;
    }

    private EditText field(String hint, boolean multiline) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(15);
        e.setTextColor(ink);
        e.setHintTextColor(muted);
        e.setPadding(dp(13), dp(11), dp(13), dp(11));
        e.setBackground(strokeBg(surface, line, 14));
        if (multiline) { e.setMinLines(3); e.setGravity(Gravity.TOP | Gravity.RIGHT); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); }
        else e.setSingleLine(true);
        return e;
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items);
        s.setAdapter(adapter);
        s.setPadding(dp(8), dp(4), dp(8), dp(4));
        s.setBackground(strokeBg(surface, line, 14));
        return s;
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        return box;
    }

    private TextView primaryButton(String text, View.OnClickListener click) { return button(text, primary, Color.WHITE, click); }
    private TextView secondaryButton(String text, View.OnClickListener click) { return button(text, Color.rgb(235, 242, 242), primaryDark, click); }

    private TextView compactButton(String text, boolean solid, View.OnClickListener click) {
        TextView t = tv(text, 14, solid ? Color.WHITE : primary, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(strokeBg(solid ? primary : surface, solid ? primary : line, 13));
        t.setOnClickListener(click);
        return t;
    }

    private TextView button(String text, int back, int color, View.OnClickListener click) {
        TextView t = tv(text, 15, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        t.setBackground(round(back, 14));
        t.setOnClickListener(click);
        t.setMinHeight(dp(48));
        return t;
    }

    private TextView link(String text) {
        TextView t = tv(text, 14, primary, true);
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        return t;
    }

    private TextView chip(String text, int back, int color) {
        TextView t = tv(text, 12, color, true);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(back, 99));
        return t;
    }

    private TextView tv(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text == null ? "" : text);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setLineSpacing(0, 1.12f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private View space(int h) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h))); return v; }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private GradientDrawable strokeBg(int color, int stroke, int radiusDp) {
        GradientDrawable g = round(color, radiusDp);
        g.setStroke(dp(1), stroke);
        return g;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
    private String str(EditText e) { return e.getText() == null ? "" : e.getText().toString(); }
    private int intValue(EditText e, int fallback) { try { return Integer.parseInt(str(e).trim()); } catch (Exception ex) { return fallback; } }
    private int visitFee() { return prefs.getInt("visit_fee", 10000); }
    private int feeFor(String type) {
        if (ClinicDb.FREE_FOLLOWUP.equals(type)) return 0;
        if (ClinicDb.LAB_RESULT.equals(type)) return prefs.getInt("result_fee", 0);
        return visitFee();
    }
    private String safe(String s) { return s == null ? "" : s; }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private String money(int amount) {
        return NumberFormat.getIntegerInstance(new Locale("ar")).format(amount) + " ج.س";
    }

    private String roleLabel(String value) {
        if (ROLE_DOCTOR.equals(value)) return "الطبيب";
        if (ROLE_ADMIN.equals(value)) return "الإدارة";
        return "المسجلة";
    }

    private String typeLabel(String value) {
        if (ClinicDb.FREE_FOLLOWUP.equals(value)) return "مقابلة قريبة مجانية";
        if (ClinicDb.PAID_FOLLOWUP.equals(value)) return "مقابلة بعيدة";
        if (ClinicDb.LAB_RESULT.equals(value)) return "نتيجة فحوصات";
        return "مريض جديد";
    }

    private String statusLabel(String value) {
        if (ClinicDb.WAITING.equals(value)) return "بانتظار الطبيب";
        if (ClinicDb.IN_CONSULT.equals(value)) return "داخل الكشف";
        if (ClinicDb.COMPLETED.equals(value)) return "مكتمل";
        return "مسجل";
    }

    private int statusColor(String value) {
        if (ClinicDb.WAITING.equals(value)) return Color.rgb(55, 108, 170);
        if (ClinicDb.IN_CONSULT.equals(value)) return warning;
        if (ClinicDb.COMPLETED.equals(value)) return primary;
        return Color.rgb(100, 110, 115);
    }
}
