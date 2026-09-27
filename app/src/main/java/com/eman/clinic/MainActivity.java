package com.eman.clinic;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.lifecycle.ViewModelProvider;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends ComponentActivity {
    private static final int NAV_HOME = 0, NAV_QUEUE = 1, NAV_PATIENTS = 2, NAV_FINANCE = 3;
    private static final int SCREEN_HOME=0, SCREEN_QUEUE=1, SCREEN_DOCTOR=2, SCREEN_DOCTOR_VISIT=3,
            SCREEN_PATIENTS=4, SCREEN_PATIENT_DETAIL=5, SCREEN_FINANCE=6, SCREEN_SETTINGS=7;
    private static final String ROLE_RECEPTION = "RECEPTION";
    private static final String ROLE_DOCTOR = "DOCTOR";

    private final int primary = Color.rgb(14, 113, 105);
    private final int primaryDark = Color.rgb(8, 78, 73);
    private final int surface = Color.WHITE;
    private final int bg = Color.rgb(245, 247, 248);
    private final int ink = Color.rgb(24, 35, 39);
    private final int muted = Color.rgb(103, 116, 121);
    private final int line = Color.rgb(226, 231, 233);
    private final int warning = Color.rgb(175, 105, 16);

    private ClinicDb db;
    private ClinicViewModel viewModel;
    private AuthStore auth;
    private SharedPreferences prefs;
    private LinearLayout content;
    private String role;
    private int currentScreen = SCREEN_HOME;
    private long currentPatientId = -1;
    private long patientDetailReturnVisitId = -1;

    private long activeVisitId = -1;
    private EditText activeComplaint, activeExam, activeDiagnosis, activeLabs, activeTreatment, activeFollowup;
    private EditText activeTemperature, activeBloodPressure, activePulse, activeWeight, activeOxygen, activeMedications;
    private final Handler draftHandler = new Handler(Looper.getMainLooper());
    private final Runnable draftSaver = this::saveActiveDraft;

    @Override public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        Window window = getWindow();
        window.setStatusBarColor(surface);
        window.setNavigationBarColor(surface);
        auth = new AuthStore(this);
        if (!auth.hasRemoteIdentity() || !auth.isMembershipActive()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }
        viewModel = new ViewModelProvider(this).get(ClinicViewModel.class);
        db = viewModel.database();
        prefs = getSharedPreferences("clinic_settings", MODE_PRIVATE);
        role = uiRole(auth.memberRole());
        long restoredVisit=bundle==null?-1:bundle.getLong("active_visit_id",-1);
        int restoredScreen=bundle==null?SCREEN_HOME:bundle.getInt("screen",SCREEN_HOME);
        currentPatientId=bundle==null?-1:bundle.getLong("patient_id",-1);
        patientDetailReturnVisitId=bundle==null?-1:bundle.getLong("patient_return_visit_id",-1);
        if(restoredVisit>0 && auth.can("view_clinical")) showDoctorVisit(restoredVisit);
        else if(restoredScreen==SCREEN_PATIENT_DETAIL && currentPatientId>0) showPatientDetail(currentPatientId,patientDetailReturnVisitId);
        else if(restoredScreen==SCREEN_QUEUE) showQueue();
        else if(restoredScreen==SCREEN_DOCTOR) showDoctor();
        else if(restoredScreen==SCREEN_PATIENTS) showPatients("");
        else if(restoredScreen==SCREEN_FINANCE) showFinance();
        else if(restoredScreen==SCREEN_SETTINGS) showSettings();
        else showHome();
    }

    @Override protected void onResume() {
        super.onResume();
        if (auth != null) role = uiRole(auth.memberRole());
    }

    @Override protected void onPause() {
        saveActiveDraft();
        super.onPause();
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        saveActiveDraft();
        outState.putInt("screen",currentScreen);
        outState.putLong("patient_id",currentPatientId);
        outState.putLong("patient_return_visit_id",patientDetailReturnVisitId);
        outState.putLong("active_visit_id",activeVisitId);
        super.onSaveInstanceState(outState);
    }

    @Override public void onBackPressed() {
        if(currentScreen==SCREEN_HOME){super.onBackPressed();return;}
        if(currentScreen==SCREEN_DOCTOR_VISIT){saveActiveDraft();showDoctor();return;}
        if(currentScreen==SCREEN_PATIENT_DETAIL){
            long returnVisit=patientDetailReturnVisitId;
            patientDetailReturnVisitId=-1;
            if(returnVisit>0) showDoctorVisit(returnVisit); else showPatients("");
            return;
        }
        showHome();
    }

    private void base(String title, String subtitle, int selectedNav) {
        saveActiveDraft();
        clearActiveDraftBinding();
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
        roleChip.setOnClickListener(v -> toast("الدور مربوط بحسابك وصلاحيات الدكتور"));
        top.addView(roleChip);
        header.addView(top);
        header.addView(tv(title, 26, ink, true));
        TextView sub = tv(subtitle, 13, muted, false);
        sub.setPadding(0, dp(3), 0, 0);
        header.addView(sub);
        if(auth.hasRemoteIdentity()) {
            ClinicSyncUiState syncUiState=viewModel.syncState();
            int waiting=syncUiState.getPending();
            String last=syncUiState.getLastCloudSync();
            String connectivity=isOnline()?"متصل":"بدون إنترنت — العمل محفوظ على الجهاز";
            String syncLine=connectivity+" • معلّق: "+waiting+(last.isEmpty()?" • لم تتم مزامنة سحابية بعد":" • آخر مزامنة: "+last);
            TextView syncState=tv(syncLine,11,waiting>0||!isOnline()?warning:primary,false);
            syncState.setContentDescription(syncLine);
            syncState.setPadding(0,dp(5),0,0);
            header.addView(syncState);
        }
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
        if(ClinicAccessPolicy.canOpenQueue(auth.can("manage_queue"),auth.can("view_clinical"),auth.can("register_visits")))
            bar.addView(navItem("الطابور", selected == NAV_QUEUE, v -> showQueue()), navParams());
        if(ClinicAccessPolicy.canOpenPatients(auth.can("view_patients")))
            bar.addView(navItem("المرضى", selected == NAV_PATIENTS, v -> showPatients("")), navParams());
        if (ROLE_DOCTOR.equals(role) && ClinicAccessPolicy.canOpenDoctor(auth.can("view_clinical")) && !isOwnerDoctor())
            bar.addView(navItem("الطبيب", currentScreen==SCREEN_DOCTOR||currentScreen==SCREEN_DOCTOR_VISIT, v -> showDoctor()), navParams());
        else if(ClinicAccessPolicy.canOpenFinance(auth.can("view_finance"),auth.can("record_payments"),auth.can("close_day")))
            bar.addView(navItem(auth.can("view_finance") ? "الحسابات" : "التحصيل", selected == NAV_FINANCE, v -> showFinance()), navParams());
        return bar;
    }

    private LinearLayout.LayoutParams navParams() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1); }

    private TextView navItem(String label, boolean selected, View.OnClickListener listener) {
        TextView t = tv(label, 13, selected ? primary : muted, selected);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(selected ? Color.rgb(232, 246, 244) : Color.TRANSPARENT, 14));
        t.setOnClickListener(listener);
        t.setContentDescription(label+(selected?"، الصفحة الحالية":""));
        t.setFocusable(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        p.setMargins(dp(3), dp(4), dp(3), dp(4));
        t.setLayoutParams(p);
        return t;
    }

    private void showHome() {
        currentScreen=SCREEN_HOME; currentPatientId=-1;
        base("لوحة اليوم", "إدارة المرضى والطابور والكشف والحسابات بدون إنترنت", NAV_HOME);
        ClinicDb.Stats s = viewModel.todayStats();
        content.addView(statRow("زيارات اليوم", String.valueOf(s.totalVisits), "في الطابور", String.valueOf(s.openQueue)));
        content.addView(space(10));
        if (auth.can("view_finance") || auth.can("close_day")) {
            content.addView(statRow("المحصّل اليوم", money(s.totalPaid), "متبقي زيارات اليوم", money(s.outstanding)));
            content.addView(space(12));
        }

        if (auth.hasRemoteIdentity()) {
            LinearLayout sync = card();
            ClinicSyncUiState state=viewModel.syncState();
            int pending=state.getPending(), failed=state.getFailed(), conflicts=state.getConflicts();
            boolean syncHealthy = pending == 0 && failed == 0 && conflicts == 0;
            String syncTitle = syncHealthy ? "المزامنة محدثة ✓"
                    : "انتظار " + pending + " • فشل " + failed + " • تعارض " + conflicts;
            sync.addView(tv(syncTitle, 15, syncHealthy ? primary : warning, true));
            sync.addView(tv(LocalSyncManager.status(this), 12, muted, false));
            sync.setOnClickListener(v -> startActivity(new Intent(this, SyncIssuesActivity.class)));
            content.addView(sync);
        }

        if (db.isOperationalDayClosed()) {
            LinearLayout locked = card();
            locked.addView(tv("تم إغلاق حساب اليوم ✓", 16, primary, true));
            locked.addView(tv("لا يمكن إضافة زيارات أو دفعات جديدة بعد الإغلاق.", 13, muted, false));
            content.addView(locked);
        }
        content.addView(space(4));

        if (ROLE_RECEPTION.equals(role)) {
            section("شغل المسجلة", "سجلي المريض ثم أرسليه للطبيب");
            if(auth.can("register_visits")) {
                content.addView(primaryButton("تسجيل مريض جديد", v -> newPatientDialog()));
                content.addView(space(8));
                content.addView(secondaryButton("مريض مسجل / مقابلة / نتيجة فحوصات", v -> lookupPatientDialog()));
            }
            if (auth.can("record_payments")) {
                content.addView(space(8));
                content.addView(secondaryButton("تحصيل المبالغ المعلّقة", v -> showFinance()));
            }
        } else if (ROLE_DOCTOR.equals(role)) {
            section("شغل الطبيب", "الحالات المرسلة من المسجلة تظهر هنا");
            if(auth.can("view_clinical")) content.addView(primaryButton("فتح طابور الطبيب", v -> showDoctor()));
            if (isOwnerDoctor()) {
                content.addView(space(8));
                content.addView(secondaryButton("الحسابات وإغلاق اليوم", v -> showFinance()));
            }
        } else {
            section("الإدارة", "متابعة التشغيل والتحصيل وإعدادات العيادة");
            content.addView(primaryButton("الحسابات وإغلاق اليوم", v -> showFinance()));
            content.addView(space(8));
            content.addView(secondaryButton("إعدادات وأدوات العيادة", v -> showSettings()));
        }

        content.addView(space(18));
        section("آخر حركة في الطابور", "الحالات المفتوحة الآن");
        List<ClinicDb.Visit> q = (auth.can("manage_queue")||auth.can("view_clinical"))?viewModel.openQueue():new java.util.ArrayList<>();
        if (q.isEmpty()) empty("الطابور فاضي حالياً");
        else {
            int max = Math.min(4, q.size());
            for (int i = 0; i < max; i++) content.addView(queueCard(q.get(i), false));
        }
        content.addView(space(8));
        TextView settings = link("الإعدادات والأدوات");
        settings.setOnClickListener(v -> showSettings());
        content.addView(settings);
    }

    private void showQueue() {
        if(!ClinicAccessPolicy.canOpenQueue(auth.can("manage_queue"),auth.can("view_clinical"),auth.can("register_visits"))){showHome();return;}
        currentScreen=SCREEN_QUEUE; currentPatientId=-1;
        base("طابور العيادة", "من التسجيل إلى دخول الطبيب", NAV_QUEUE);
        if (!ROLE_DOCTOR.equals(role) && !db.isOperationalDayClosed()) {
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
        List<ClinicDb.Visit> visits = viewModel.openQueue();
        if (visits.isEmpty()) { empty("لا توجد حالات مفتوحة"); return; }
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
        card.addView(tv(cardLabel(visit.cardNo) + "  •  " + typeLabel(visit.type), 14, muted, false));
        if (ClinicDb.WAITING.equals(visit.status)) card.addView(tv("مدة الانتظار: " + waitingTime(visit.createdAt), 13, warning, true));
        if (visit.fee == 0) card.addView(tv("معفاة من الرسوم", 13, primary, true));
        else card.addView(tv("الرسوم: " + money(visit.fee) + "  |  المدفوع: " + money(visit.paidAmount), 13, muted, false));

        if (actions) {
            card.addView(space(8));
            if (ClinicDb.REGISTERED.equals(visit.status) && !ROLE_DOCTOR.equals(role)) {
                card.addView(primaryButton("إرسال للطبيب", v -> {
                    if (db.sendToDoctor(visit.id)) { toast("تم الإرسال للطبيب"); showQueue(); }
                    else { toast("تعذر الإرسال؛ حالة الزيارة تغيرت"); showQueue(); }
                }));
                card.addView(space(6));
                card.addView(secondaryButton("تعديل الزيارة", v -> editVisitDialog(visit)));
            } else if (ClinicDb.WAITING.equals(visit.status) && ROLE_DOCTOR.equals(role)) {
                card.addView(primaryButton("بدء الكشف", v -> {
                    if (db.startVisit(visit.id)) showDoctorVisit(visit.id);
                    else { toast("تعذر بدء الكشف؛ حدّثي الطابور"); showDoctor(); }
                }));
            } else if (ClinicDb.IN_CONSULT.equals(visit.status) && ROLE_DOCTOR.equals(role)) {
                card.addView(primaryButton("متابعة الكشف", v -> showDoctorVisit(visit.id)));
                card.addView(space(6));
                card.addView(secondaryButton("تحويل لدكتور آخر", v -> {
                    if (db.transferVisit(visit.id)) { toast("عادت الحالة للانتظار"); showDoctor(); }
                    else toast("تعذر تحويل الحالة");
                }));
            }
            if ((ClinicDb.REGISTERED.equals(visit.status) || ClinicDb.WAITING.equals(visit.status))
                    && auth.can("manage_queue")) {
                card.addView(space(6));
                card.addView(secondaryButton("إلغاء الزيارة", v -> cancelVisitDialog(visit)));
            }
        }
        return card;
    }

    private void showDoctor() {
        if(!ClinicAccessPolicy.canOpenDoctor(auth.can("view_clinical"))){showHome();return;}
        currentScreen=SCREEN_DOCTOR; currentPatientId=-1;
        base("شاشة الطبيب", "المرضى المنتظرون والكشف الجاري", -1);
        List<ClinicDb.Visit> list = viewModel.doctorQueue();
        if (list.isEmpty()) { empty("لا يوجد مريض بانتظار الطبيب حالياً"); return; }
        for (ClinicDb.Visit visit : list) content.addView(queueCard(visit, true));
    }

    private void showDoctorVisit(long visitId) {
        if(!ClinicAccessPolicy.canOpenDoctor(auth.can("view_clinical"))){showHome();return;}
        ClinicDb.Visit visit = viewModel.visit(visitId);
        if (visit == null || !ClinicDb.IN_CONSULT.equals(visit.status)) { showDoctor(); return; }
        ClinicDb.Patient patient = viewModel.patient(visit.patientId);
        currentScreen=SCREEN_DOCTOR_VISIT; currentPatientId=visit.patientId;
        base("كشف المريض", cardLabel(visit.cardNo) + " • " + visit.patientName + " • " + typeLabel(visit.type), -1);

        LinearLayout identity = card();
        identity.addView(tv(visit.patientName, 21, ink, true));
        String identityLine = (patient == null ? "" : safe(patient.gender));
        if (patient != null && !safe(patient.ageText).isEmpty()) identityLine += "  •  " + patient.ageText;
        if (patient != null && !safe(patient.phone).isEmpty()) identityLine += "  •  " + patient.phone;
        identity.addView(tv(identityLine, 13, muted, false));
        identity.addView(tv("الزيارة: " + typeLabel(visit.type), 14, primary, true));
        content.addView(identity);

        if (patient != null && auth.can("view_clinical")) {
            LinearLayout medical = card();
            medical.addView(tv("معلومات طبية مهمة", 16, ink, true));
            boolean any = false;
            if (!safe(patient.allergies).isEmpty()) {
                medical.addView(tv("⚠ الحساسية: " + patient.allergies, 14, warning, true));
                any = true;
            }
            if (!safe(patient.chronicConditions).isEmpty()) {
                medical.addView(tv("أمراض مزمنة: " + patient.chronicConditions, 14, ink, false));
                any = true;
            }
            if (!safe(patient.currentMedications).isEmpty()) {
                medical.addView(tv("أدوية حالية: " + patient.currentMedications, 14, ink, false));
                any = true;
            }
            if (!any) medical.addView(tv("لم تُسجل حساسية أو أمراض مزمنة أو أدوية حالية بعد.", 13, muted, false));
            if (canEditMedicalProfile()) {
                TextView editMedical = link("تحديث المعلومات الطبية ←");
                editMedical.setOnClickListener(v -> patientMedicalDialog(patient, () -> showDoctorVisit(visitId)));
                medical.addView(editMedical);
            }
            content.addView(medical);
        }

        List<ClinicDb.Visit> history = viewModel.visitsForPatient(visit.patientId);
        int previous = Math.max(0, history.size() - 1);
        ClinicDb.Visit last = null;
        for (ClinicDb.Visit h : history) {
            if (h.id != visit.id && ClinicDb.COMPLETED.equals(h.status)) { last = h; break; }
        }
        if (last != null) {
            LinearLayout previousCard = card();
            previousCard.addView(tv("آخر زيارة • " + last.createdAt, 14, primary, true));
            if (!safe(last.diagnosis).isEmpty()) previousCard.addView(tv("التشخيص: " + last.diagnosis, 14, ink, true));
            if (!safe(last.labs).isEmpty()) previousCard.addView(tv("الفحوصات: " + last.labs, 13, ink, false));
            if (!safe(last.treatment).isEmpty()) previousCard.addView(tv("العلاج: " + last.treatment, 13, ink, false));
            if (!safe(last.followup).isEmpty()) previousCard.addView(tv("المتابعة: " + last.followup, 13, muted, false));
            TextView allHistory = link("عرض كل السجل (" + previous + ") ←");
            allHistory.setOnClickListener(v -> showPatientDetail(visit.patientId,visit.id));
            previousCard.addView(allHistory);
            content.addView(previousCard);
        } else {
            content.addView(tv("لا توجد زيارة طبية سابقة", 13, muted, false));
            content.addView(space(8));
        }

        EditText complaint = field("الشكوى الرئيسية والأعراض", true); complaint.setText(visit.complaint);
        EditText exam = field("الفحص السريري", true); exam.setText(visit.exam);
        EditText diagnosis = field("التشخيص", true); diagnosis.setText(visit.diagnosis);
        EditText labs = field("الفحوصات المطلوبة / نتائج الفحوصات", true); labs.setText(visit.labs);
        EditText treatment = field("العلاج والروشتة", true); treatment.setText(visit.treatment);
        EditText followup = field("خطة المتابعة والتعليمات", true); followup.setText(visit.followup);
        EditText temperature = field("الحرارة °C", false); temperature.setText(visit.temperature);
        EditText bloodPressure = field("الضغط — مثال 120/80", false); bloodPressure.setText(visit.bloodPressure);
        EditText pulse = field("النبض / دقيقة", false); pulse.setText(visit.pulse);
        EditText weight = field("الوزن كجم", false); weight.setText(visit.weight);
        EditText oxygen = field("الأكسجين %", false); oxygen.setText(visit.oxygen);
        EditText medications = field("الأدوية: الاسم | الجرعة | التكرار | المدة", true); medications.setText(visit.medications);

        section(ClinicDb.LAB_RESULT.equals(visit.type)?"نتيجة الفحوصات":"الشكوى والفحص",
                ClinicDb.LAB_RESULT.equals(visit.type)?"راجعي النتيجة ثم حدّثي التشخيص والعلاج":"ابدئي بسبب الحضور ثم اكتبي نتيجة الفحص السريري");
        if(ClinicDb.LAB_RESULT.equals(visit.type)) content.addView(labeled("نتيجة الفحوصات",labs));
        else {
            content.addView(labeled("الشكوى الرئيسية",complaint));
            content.addView(labeled("الفحص السريري",exam));
        }

        section("التشخيص والعلاج", "المعلومات الأساسية المطلوبة لإغلاق الكشف");
        content.addView(labeled(ClinicDb.LAB_RESULT.equals(visit.type)?"التشخيص بعد النتيجة":"التشخيص",diagnosis));
        content.addView(labeled("العلاج والروشتة",treatment));
        if(!ClinicDb.LAB_RESULT.equals(visit.type)) content.addView(labeled("الفحوصات المطلوبة / النتائج",labs));
        content.addView(labeled("الأدوية والجرعات",medications));
        content.addView(labeled("خطة المتابعة والتعليمات",followup));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.addView(labeled("الحرارة °C",temperature));
        details.addView(labeled("ضغط الدم",bloodPressure));
        details.addView(labeled("النبض / دقيقة",pulse));
        details.addView(labeled("الوزن كجم",weight));
        details.addView(labeled("الأكسجين %",oxygen));
        details.setVisibility(View.GONE);
        TextView detailsToggle = secondaryButton("إظهار العلامات الحيوية", null);
        detailsToggle.setOnClickListener(v -> {
            boolean opening=details.getVisibility()!=View.VISIBLE;
            details.setVisibility(opening?View.VISIBLE:View.GONE);
            detailsToggle.setText(opening?"إخفاء العلامات الحيوية":"إظهار العلامات الحيوية");
            detailsToggle.setContentDescription(detailsToggle.getText());
        });
        content.addView(detailsToggle); content.addView(details);
        content.addView(tv("الحفظ تلقائي عند الانتقال أو إغلاق الشاشة", 12, muted, false));
        content.addView(space(8));

        bindActiveVisit(visitId, complaint, exam, diagnosis, labs, treatment, followup,
                temperature, bloodPressure, pulse, weight, oxygen, medications);

        content.addView(secondaryButton("حفظ الآن", v -> {
            boolean ok = saveClinicalForm(visitId, complaint, exam, diagnosis, labs, treatment,
                    followup, temperature, bloodPressure, pulse, weight, oxygen, medications, false);
            toast(ok ? "تم الحفظ" : "لم يتم الحفظ؛ حالة الزيارة تغيرت");
        }));
        content.addView(space(8));
        content.addView(primaryButton("إنهاء الكشف وفتح المريض التالي", v -> {
            if (!ClinicWorkflowRules.canCompleteClinical(visit.type, str(complaint), str(diagnosis), str(labs))) {
                toast(ClinicDb.LAB_RESULT.equals(visit.type)
                        ? "أدخلي نتيجة الفحوصات قبل الإنهاء"
                        : "الشكوى والتشخيص مطلوبان قبل إنهاء الكشف");
                return;
            }
            new AlertDialog.Builder(this).setTitle("إنهاء كشف "+visit.patientName+"؟")
                    .setMessage("سيتم حفظ البيانات وإغلاق الزيارة ثم فتح المريض التالي إن وجد.")
                    .setNegativeButton("مراجعة البيانات",null)
                    .setPositiveButton("حفظ وإنهاء",(d,w)->{
                        boolean ok=saveClinicalForm(visitId,complaint,exam,diagnosis,labs,treatment,
                                followup,temperature,bloodPressure,pulse,weight,oxygen,medications,true);
                        if(ok){clearActiveDraftBinding();toast("تم حفظ وإغلاق الزيارة");openNextDoctorPatient();}
                        else {toast("لم يتم الإغلاق؛ حالة الزيارة تغيرت");showDoctor();}
                    }).show();
        }));
    }

    private void bindActiveVisit(long visitId, EditText complaint, EditText exam, EditText diagnosis,
            EditText labs, EditText treatment, EditText followup, EditText temperature,
            EditText bloodPressure, EditText pulse, EditText weight, EditText oxygen,
            EditText medications) {
        activeVisitId = visitId;
        activeComplaint = complaint;
        activeExam = exam;
        activeDiagnosis = diagnosis;
        activeLabs = labs;
        activeTreatment = treatment;
        activeFollowup = followup;
        activeTemperature = temperature; activeBloodPressure = bloodPressure;
        activePulse = pulse; activeWeight = weight; activeOxygen = oxygen;
        activeMedications = medications;
        TextWatcher watcher = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                draftHandler.removeCallbacks(draftSaver);
                draftHandler.postDelayed(draftSaver, 1200);
            }
            public void afterTextChanged(Editable s) {}
        };
        for (EditText field : new EditText[]{complaint, exam, diagnosis, labs, treatment, followup,
                temperature, bloodPressure, pulse, weight, oxygen, medications}) field.addTextChangedListener(watcher);
    }

    private void saveActiveDraft() {
        if (activeVisitId <= 0 || db == null || activeComplaint == null) return;
        saveClinicalForm(activeVisitId, activeComplaint, activeExam, activeDiagnosis, activeLabs,
                activeTreatment, activeFollowup, activeTemperature, activeBloodPressure,
                activePulse, activeWeight, activeOxygen, activeMedications, false);
    }

    private void clearActiveDraftBinding() {
        draftHandler.removeCallbacks(draftSaver);
        activeVisitId=-1;
        activeComplaint=activeExam=activeDiagnosis=activeLabs=activeTreatment=activeFollowup=null;
        activeTemperature=activeBloodPressure=activePulse=activeWeight=activeOxygen=activeMedications=null;
    }

    private boolean saveClinicalForm(long visitId, EditText complaint, EditText exam,
            EditText diagnosis, EditText labs, EditText treatment, EditText followup,
            EditText temperature, EditText bloodPressure, EditText pulse, EditText weight,
            EditText oxygen, EditText medications, boolean complete) {
        return db.saveClinical(visitId, str(complaint), str(exam), str(diagnosis), str(labs),
                str(treatment), str(followup), str(temperature), str(bloodPressure), str(pulse),
                str(weight), str(oxygen), str(medications), complete);
    }

    private void openNextDoctorPatient() {
        List<ClinicDb.Visit> list = viewModel.doctorQueue();
        for (ClinicDb.Visit next : list) {
            if (ClinicDb.IN_CONSULT.equals(next.status)) { showDoctorVisit(next.id); return; }
            if (ClinicDb.WAITING.equals(next.status) && db.startVisit(next.id)) { showDoctorVisit(next.id); return; }
        }
        showDoctor();
    }

    private void showPatients(String query) {
        if(!ClinicAccessPolicy.canOpenPatients(auth.can("view_patients"))){showHome();return;}
        currentScreen=SCREEN_PATIENTS; currentPatientId=-1;
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

        List<ClinicDb.Patient> list = viewModel.searchPatients(query);
        if (list.isEmpty()) { empty("ما لقينا مريض بالمعلومة دي"); return; }
        for (ClinicDb.Patient p : list) {
            LinearLayout c = card();
            c.addView(tv(p.name, 18, ink, true));
            String sub = cardLabel(p.cardNo);
            if (auth.can("view_clinical") && !safe(p.ageText).isEmpty()) sub += "  •  " + p.ageText;
            if (!safe(p.phone).isEmpty()) sub += "  •  " + p.phone;
            c.addView(tv(sub, 13, muted, false));
            c.setOnClickListener(v -> showPatientDetail(p.id));
            content.addView(c);
        }
    }

    private void showPatientDetail(long patientId) {
        showPatientDetail(patientId,-1);
    }

    private void showPatientDetail(long patientId,long returnVisitId) {
        ClinicDb.Patient p = viewModel.patient(patientId);
        if (p == null) { showPatients(""); return; }
        patientDetailReturnVisitId=returnVisitId;
        currentScreen=SCREEN_PATIENT_DETAIL; currentPatientId=patientId;
        base("ملف المريض", auth.can("view_clinical") ? "السجل الطبي والزيارات السابقة" : "بيانات المريض والزيارات", NAV_PATIENTS);
        LinearLayout profile = card();
        profile.addView(tv(p.name, 23, ink, true));
        profile.addView(tv(cardLabel(p.cardNo), 15, primary, true));
        if (auth.can("view_clinical"))
            profile.addView(tv("العمر: " + (safe(p.ageText).isEmpty() ? "غير مسجل" : p.ageText), 14, muted, false));
        profile.addView(tv("الهاتف: " + (safe(p.phone).isEmpty() ? "غير مسجل" : p.phone), 14, muted, false));
        profile.addView(tv("النوع: " + (safe(p.gender).isEmpty() ? "غير محدد" : p.gender), 14, muted, false));
        content.addView(profile);

        if (auth.can("view_clinical")) {
            LinearLayout medical = card();
            medical.addView(tv("المعلومات الطبية الأساسية", 16, ink, true));
            medical.addView(tv("الحساسية: " + (safe(p.allergies).isEmpty() ? "غير مسجلة" : p.allergies), 14, safe(p.allergies).isEmpty() ? muted : warning, !safe(p.allergies).isEmpty()));
            medical.addView(tv("الأمراض المزمنة: " + (safe(p.chronicConditions).isEmpty() ? "غير مسجلة" : p.chronicConditions), 14, muted, false));
            medical.addView(tv("الأدوية الحالية: " + (safe(p.currentMedications).isEmpty() ? "غير مسجلة" : p.currentMedications), 14, muted, false));
            if (canEditMedicalProfile()) {
                TextView editMedical = link("تحديث المعلومات الطبية ←");
                editMedical.setOnClickListener(v -> patientMedicalDialog(p, () -> showPatientDetail(patientId)));
                medical.addView(editMedical);
            }
            content.addView(medical);
        }

        if (!ROLE_DOCTOR.equals(role) && !db.isOperationalDayClosed()) {
            content.addView(primaryButton("إضافة زيارة جديدة", v -> existingVisitDialog(p, false)));
            content.addView(space(8));
            content.addView(secondaryButton("إضافة نتيجة فحوصات", v -> existingVisitDialog(p, true)));
        }
        content.addView(space(18));
        section("تاريخ الزيارات", "الأحدث أولاً");
        List<ClinicDb.Visit> visits = viewModel.visitsForPatient(patientId);
        if (visits.isEmpty()) empty("لا توجد زيارات سابقة");
        for (ClinicDb.Visit visit : visits) {
            LinearLayout c = card();
            c.addView(tv(typeLabel(visit.type) + "  •  " + visit.createdAt, 14, primary, true));
            c.addView(tv("الحالة: " + statusLabel(visit.status), 13, muted, false));
            if (ClinicDb.CANCELLED.equals(visit.status)) {
                if (!safe(visit.cancellationReason).isEmpty())
                    c.addView(tv("سبب الإلغاء: " + visit.cancellationReason, 13, warning, false));
                if (auth.can("manage_queue")) {
                    TextView reopen = link("إعادة فتح الزيارة ←");
                    reopen.setOnClickListener(v -> {
                        if (db.reopenVisit(visit.id)) { toast("أعيدت الزيارة للطابور"); showPatientDetail(patientId); }
                        else toast("تعذر إعادة الفتح");
                    });
                    c.addView(reopen);
                }
            }
            if (!safe(visit.diagnosis).isEmpty()) c.addView(tv("التشخيص: " + visit.diagnosis, 15, ink, true));
            if (!safe(visit.labs).isEmpty()) c.addView(tv("الفحوصات: " + visit.labs, 14, ink, false));
            if (!safe(visit.treatment).isEmpty()) c.addView(tv("العلاج: " + visit.treatment, 14, ink, false));
            if (!safe(visit.followup).isEmpty()) c.addView(tv("المتابعة: " + visit.followup, 14, muted, false));
            content.addView(c);
        }
    }

    private void showFinance() {
        boolean canViewFinance = auth.can("view_finance") || auth.can("close_day");
        boolean canCollect = auth.can("record_payments");
        if (!canViewFinance && !canCollect) { showHome(); return; }
        currentScreen=SCREEN_FINANCE; currentPatientId=-1;
        base(canViewFinance ? "حسابات اليوم" : "التحصيل",
                canViewFinance ? "التحصيل والمدفوعات وإغلاق اليوم" : "تسجيل الدفعات للزيارات المعلّقة",
                NAV_FINANCE);
        ClinicDb.Stats s = viewModel.todayStats();
        if (canViewFinance) {
            content.addView(statRow("رسوم زيارات اليوم", money(s.totalCharges), "المقبوض اليوم", money(s.totalPaid)));
            content.addView(space(10));
            content.addView(statRow("متبقي زيارات اليوم", money(s.outstanding), "المعفاة", String.valueOf(s.waivedVisits)));
            content.addView(space(10));
            content.addView(statRow("المبالغ المعكوسة/المستردة", money(s.refunded), "الزيارات الملغاة", String.valueOf(s.cancelledVisits)));
            LinearLayout methods = card();
            methods.addView(tv("طرق الدفع (صافي اليوم)", 15, ink, true));
            methods.addView(tv("كاش: " + money(s.cash) + "  •  تحويل: " + money(s.bank) + "\nمحفظة: " + money(s.wallet) + "  •  أخرى: " + money(s.other), 13, muted, false));
            content.addView(methods);
            content.addView(space(16));
        }
        section("مبالغ غير مكتملة", canViewFinance
                ? "يمكن تسجيل دفع كامل أو جزئي قبل إغلاق اليوم"
                : "يمكنك تسجيل الدفع فقط؛ ملخص اليوم متاح للإدارة");
        List<ClinicDb.Visit> unpaid = viewModel.unpaidToday();
        if (unpaid.isEmpty()) empty("ما في مبالغ معلّقة لليوم");
        for (ClinicDb.Visit visit : unpaid) {
            LinearLayout c = card();
            c.addView(tv(visit.patientName + "  •  " + cardLabel(visit.cardNo), 17, ink, true));
            c.addView(tv(typeLabel(visit.type), 13, muted, false));
            c.addView(tv("المتبقي: " + money(visit.remaining()), 15, warning, true));
            if (canCollect && !db.isOperationalDayClosed()) {
                c.addView(space(6));
                c.addView(primaryButton("تسجيل دفعة", v -> paymentDialog(visit)));
            }
            content.addView(c);
        }
        section("ديون الأيام السابقة", "تظل ظاهرة حتى اكتمال التحصيل ولا تختفي بإغلاق اليوم");
        List<ClinicDb.Debt> debts=viewModel.olderDebts();
        if (debts.isEmpty()) empty("ما في ديون سابقة");
        for (ClinicDb.Debt debt:debts) {
            LinearLayout c=card(); c.addView(tv(debt.patientName+"  •  "+cardLabel(debt.cardNo),16,ink,true));
            c.addView(tv("المتبقي: "+money(debt.remaining())+"  •  "+debt.createdAt,13,warning,true));
            if (canCollect && !db.isOperationalDayClosed()) c.addView(primaryButton("تحصيل من الدين",v->{ ClinicDb.Visit visit=viewModel.visit(debt.visitId); if(visit!=null) paymentDialog(visit); }));
            content.addView(c);
        }
        if (canViewFinance) {
            section("حركة الدفع اليوم", "الدفعات والتصحيحات محفوظة باسم المنفّذ والسبب");
            List<ClinicDb.Payment> payments=viewModel.recentPaymentsToday();
            if (payments.isEmpty()) empty("ما في حركة دفع اليوم");
            for (ClinicDb.Payment payment:payments) {
                LinearLayout c=card();
                String sign=payment.amount<0?"−":"+";
                c.addView(tv(payment.patientName+"  •  "+sign+money(Math.abs(payment.amount)),15,ink,true));
                c.addView(tv(payment.method+"  •  "+payment.eventType+(payment.actorName.isEmpty()?"":"  •  "+payment.actorName),12,muted,false));
                if (!payment.reason.isEmpty()) c.addView(tv("السبب: "+payment.reason,12,warning,false));
                if (isOwnerDoctor() && payment.amount>0 && !payment.reversed && !db.isOperationalDayClosed())
                    c.addView(secondaryButton("إلغاء / استرداد / تصحيح",v->paymentAdjustmentDialog(payment)));
                content.addView(c);
            }
        }
        if (!auth.can("close_day")) return;
        content.addView(space(16));
        boolean closed = db.isTodayClosed();
        if (!closed && s.openQueue > 0) {
            LinearLayout warn = card();
            warn.addView(tv("ما ممكن نقفل اليوم الآن", 16, warning, true));
            warn.addView(tv("في " + s.openQueue + " حالة لسه مفتوحة في الطابور. أكمليها أولاً.", 13, muted, false));
            content.addView(warn);
        }
        TextView close = closed ? secondaryButton(isOwnerDoctor()?"اليوم مقفول ✓ — إعادة فتح إدارية":"اليوم مقفول ✓", v -> {
            if (isOwnerDoctor()) reopenDayDialog(); else toast("تم إغلاق اليوم؛ إعادة الفتح متاحة للإدارة فقط");
        }) : primaryButton("إغلاق حساب اليوم", v -> {
            ClinicDb.Stats latest = viewModel.todayStats();
            if (latest.openQueue > 0) { toast("أكملي كل الحالات المفتوحة قبل إغلاق اليوم"); return; }
            new AlertDialog.Builder(this)
                    .setTitle("إغلاق حساب اليوم؟")
                    .setMessage("المقبوض اليوم: " + money(latest.totalPaid) + "\nمتبقي زيارات اليوم: " + money(latest.outstanding) + "\n\nبعد الإغلاق ما حتقدري تضيفي زيارة أو دفعة جديدة لليوم.")
                    .setNegativeButton("رجوع", null)
                    .setPositiveButton("إغلاق اليوم", (d,w) -> {
                        if (db.closeToday()) { toast("تم حفظ إغلاق اليوم"); showFinance(); }
                        else toast("تعذر الإغلاق أو اليوم مقفول مسبقاً");
                    }).show();
        });
        content.addView(close);
    }

    private void reopenDayDialog() {
        EditText reason=field("سبب إعادة الفتح",false);
        LinearLayout box=dialogBox();
        box.addView(tv("ستعود العمليات المالية للعمل، وسيُحفظ اسم المنفّذ والسبب في سجل المراجعة.",13,muted,false));
        box.addView(space(8)); box.addView(reason);
        validatedDialog("إعادة فتح اليوم؟",box,"إعادة فتح",()->{
            if(str(reason).trim().length()<3){reason.setError("اكتبي سبباً واضحاً");return false;}
            if(!db.reopenToday(str(reason))){toast("تعذرت إعادة الفتح");return false;}
            toast("تمت إعادة فتح اليوم إدارياً");showFinance();return true;
        });
    }

    private void paymentAdjustmentDialog(ClinicDb.Payment payment) {
        LinearLayout box=dialogBox();
        Spinner type=spinner(new String[]{"إلغاء كامل","استرداد كامل","تصحيح المبلغ"});
        EditText amount=field("المبلغ الصحيح (للتصحيح فقط)",false); amount.setInputType(InputType.TYPE_CLASS_NUMBER); amount.setText(String.valueOf(payment.amount));
        EditText reason=field("سبب العملية",false);
        box.addView(type); box.addView(space(8)); box.addView(amount); box.addView(space(8)); box.addView(reason);
        validatedDialog("مراجعة دفعة "+money(payment.amount),box,"حفظ القيد العكسي",()->{
                    String selected=String.valueOf(type.getSelectedItem());
                    String event=selected.startsWith("إلغاء")?"VOID":selected.startsWith("استرداد")?"REFUND":"CORRECTION";
                    int replacement="CORRECTION".equals(event)?intValue(amount,0):0;
                    if(str(reason).trim().length()<3){reason.setError("اكتبي سبباً واضحاً");return false;}
                    if("CORRECTION".equals(event)&&replacement<=0){amount.setError("اكتبي المبلغ الصحيح");return false;}
                    if(!db.reversePayment(payment.id,event,replacement,str(reason))){toast("تعذرت العملية أو اليوم مقفول");return false;}
                    toast("تم حفظ القيد العكسي بدون حذف الدفعة الأصلية");showFinance();return true;
                });
    }

    private void showSettings() {
        currentScreen=SCREEN_SETTINGS; currentPatientId=-1;
        base("إعدادات وأدوات العيادة", "الحساب، المزامنة وأدوات الإدارة", -1);
        if (isOwnerDoctor()) {
            EditText clinic = field("اسم العيادة", false); clinic.setText(prefs.getString("clinic_name", "العيادة"));
            EditText fee = field("رسوم الكشف", false); fee.setInputType(InputType.TYPE_CLASS_NUMBER); fee.setText(String.valueOf(prefs.getInt("visit_fee", 10000)));
            EditText resultFee = field("رسوم نتيجة الفحوصات", false); resultFee.setInputType(InputType.TYPE_CLASS_NUMBER); resultFee.setText(String.valueOf(prefs.getInt("result_fee", 0)));
            EditText days = field("مدة المقابلة المجانية بالأيام", false); days.setInputType(InputType.TYPE_CLASS_NUMBER); days.setText(String.valueOf(prefs.getInt("followup_days", 7)));
            content.addView(labeled("اسم العيادة", clinic));
            content.addView(labeled("رسوم الكشف / المقابلة البعيدة", fee));
            content.addView(labeled("رسوم إحضار نتيجة فحوصات", resultFee));
            content.addView(labeled("المقابلة المجانية", days));
            content.addView(primaryButton("حفظ الإعدادات", v -> {
                String clinicValue = str(clinic).trim();
                int feeValue = intValue(fee, 10000);
                int resultValue = intValue(resultFee, 0);
                int followValue = intValue(days, 7);
                if (clinicValue.length() < 2) { clinic.setError("اكتبي اسم العيادة"); return; }
                if (feeValue < 0) { fee.setError("الرسوم ما ممكن تكون سالبة"); return; }
                if (resultValue < 0) { resultFee.setError("الرسوم ما ممكن تكون سالبة"); return; }
                if (followValue < 1 || followValue > 90) { days.setError("من يوم إلى 90 يوم"); return; }
                prefs.edit().putString("clinic_name", clinicValue)
                        .putInt("visit_fee", feeValue)
                        .putInt("result_fee", resultValue)
                        .putInt("followup_days", followValue).apply();
                db.markFinanceSettingsDirty();
                SyncCoordinator.kick(this);
                toast("تم حفظ الإعدادات"); showHome();
            }));
            content.addView(space(10));
        }
        content.addView(secondaryButton("الدور مربوط بالحساب: " + roleLabel(role), v -> toast("تغيير الدور والصلاحيات يتم من حساب الدكتور")));

        content.addView(space(20));
        section("أدوات العيادة", "كل أدوات الإدارة من مكان واحد");
        if (auth.hasRemoteIdentity()) {
            content.addView(toolCard("ربط ومزامنة الأجهزة", LocalSyncManager.status(this), v -> startActivity(new Intent(this, LocalSyncActivity.class))));
            if (auth.can("manage_staff")) {
                content.addView(toolCard("فريق العيادة والصلاحيات", "دعوة المسجلة والدكتور البديل وتحديد صلاحياتهم", v -> startActivity(new Intent(this, StaffActivity.class))));
            }
            if ("owner_doctor".equals(auth.memberRole())) {
                content.addView(toolCard("النسخ الاحتياطي والاسترجاع", "إنشاء نسخة مشفّرة أو استرجاع نسخة سابقة", v -> startActivity(new Intent(this, BackupActivity.class))));
                content.addView(toolCard("الاشتراك والدفع", "إرسال بيانات الدفع ومتابعة حالة الاشتراك", v -> startActivity(new Intent(this, BillingActivity.class))));
            }
            content.addView(secondaryButton("مزامنة الآن", v -> {
                SyncCoordinator.kick(this);
                LocalSyncManager.kick(this);
                toast("بدأت المزامنة");
            }));
        }

        content.addView(space(10));
        content.addView(secondaryButton("تسجيل الخروج من الحساب", v -> new AlertDialog.Builder(this)
                .setTitle("تسجيل الخروج؟")
                .setMessage("ستظل بيانات العيادة مشفّرة على هذا الجهاز، ولن تظهر إلا بعد الدخول لنفس العيادة.")
                .setNegativeButton("رجوع", null)
                .setPositiveButton("خروج", (d, w) -> {
                    auth.clearRemoteSession();
                    startActivity(new Intent(this, LoginActivity.class));
                    finish();
                }).show()));

        content.addView(space(20));
        LinearLayout info = card();
        info.addView(tv("وضع العمل", 16, ink, true));
        info.addView(tv("البيانات محفوظة محلياً على الجهاز وتستمر بعد إغلاق التطبيق.", 13, muted, false));
        info.addView(tv("الإصدار " + BuildConfig.VERSION_NAME, 12, muted, false));
        content.addView(info);
    }

    private View toolCard(String title, String subtitle, View.OnClickListener click) {
        LinearLayout c = card();
        c.addView(tv(title, 16, ink, true));
        c.addView(tv(subtitle, 12, muted, false));
        TextView open = link("فتح ←");
        open.setOnClickListener(click);
        c.setOnClickListener(click);
        c.addView(open);
        return c;
    }

    private void newPatientDialog() {
        if (db.isOperationalDayClosed()) { toast("اليوم مقفول؛ افتحي يوم عمل جديد غداً لإضافة زيارة"); return; }
        LinearLayout box = dialogBox();
        EditText name = field("الاسم الكامل", false);
        EditText phone = field("رقم الهاتف", false); phone.setInputType(InputType.TYPE_CLASS_PHONE);
        EditText age = field("العمر — مثال: 32 سنة / 8 شهور", false);
        Spinner gender = spinner(new String[]{"غير محدد", "أنثى", "ذكر"});
        box.addView(name); box.addView(space(8)); box.addView(phone); box.addView(space(8));
        if (auth.can("edit_clinical")) { box.addView(age); box.addView(space(8)); }
        box.addView(gender);
        validatedDialog("تسجيل مريض جديد",box,"تسجيل",()->{
                    String n = str(name).trim();
                    if (n.length() < 2) { name.setError("اكتبي اسم المريض"); return false; }
                    ClinicDb.RegistrationResult result = db.registerPatientWithVisit(n, str(phone),
                            String.valueOf(gender.getSelectedItem()),
                            auth.can("edit_clinical") ? str(age) : "", ClinicDb.NEW, visitFee());
                    if (result.code == -3) {
                        toast("رقم الهاتف مسجل لمريض موجود؛ تم فتح ملفه");
                        showPatientDetail(result.patientId);
                        return true;
                    }
                    if (result.code == -2) { toast("حساب اليوم مقفول؛ لم يتم إنشاء سجل ناقص"); return false; }
                    if (!result.success()) { toast("ما عندك صلاحية أو البيانات غير صحيحة"); return false; }
                    long patientId = result.patientId;
                    ClinicDb.Patient p = viewModel.patient(patientId);
                    toast("تم التسجيل • " + (p == null ? "" : cardLabel(p.cardNo)));
                    showQueue();
                    return true;
                });
    }

    private void cancelVisitDialog(ClinicDb.Visit visit) {
        EditText reason = field("سبب الإلغاء", true);
        validatedDialog("إلغاء زيارة "+visit.patientName,reason,"إلغاء الزيارة",()->{
            if(str(reason).trim().length()<3){reason.setError("اكتبي سبباً واضحاً");return false;}
            if(!db.cancelVisit(visit.id,str(reason))){toast("حالة الزيارة تغيرت؛ حدّثي الطابور");return false;}
            toast("تم إلغاء الزيارة");showQueue();return true;
        });
    }

    private void editVisitDialog(ClinicDb.Visit visit) {
        String[] types = {ClinicDb.NEW, ClinicDb.FREE_FOLLOWUP, ClinicDb.PAID_FOLLOWUP, ClinicDb.LAB_RESULT};
        String[] labels = new String[types.length];
        int selected = 0;
        for (int i = 0; i < types.length; i++) {
            labels[i] = typeLabel(types[i]);
            if (types[i].equals(visit.type)) selected = i;
        }
        Spinner type = spinner(labels); type.setSelection(selected);
        EditText fee = field("الرسوم", false); fee.setInputType(InputType.TYPE_CLASS_NUMBER);
        fee.setText(String.valueOf(visit.fee));
        LinearLayout box = dialogBox(); box.addView(type); box.addView(space(8)); box.addView(fee);
        validatedDialog("تعديل الزيارة",box,"حفظ",()->{
            int value=intValue(fee,-1);
            if(value<0){fee.setError("اكتبي رسوماً صحيحة");return false;}
            if(!db.updateVisitRegistration(visit.id,types[type.getSelectedItemPosition()],value)){
                toast("تعذر التعديل؛ ربما تغيرت حالة الزيارة");return false;
            }
            toast("تم تعديل الزيارة");showQueue();return true;
        });
    }

    private void patientMedicalDialog(ClinicDb.Patient patient, Runnable afterSave) {
        if (patient == null) return;
        LinearLayout box = dialogBox();
        EditText age = field("العمر — مثال: 32 سنة / 8 شهور", false); age.setText(patient.ageText);
        EditText allergies = field("الحساسية — أدوية / أطعمة / أخرى", true); allergies.setText(patient.allergies);
        EditText chronic = field("الأمراض المزمنة", true); chronic.setText(patient.chronicConditions);
        EditText medications = field("الأدوية الحالية", true); medications.setText(patient.currentMedications);
        box.addView(labeled("العمر", age));
        box.addView(labeled("الحساسية", allergies));
        box.addView(labeled("الأمراض المزمنة", chronic));
        box.addView(labeled("الأدوية الحالية", medications));
        validatedDialog("المعلومات الطبية الأساسية",box,"حفظ",()->{
                    boolean ok = db.updatePatientMedical(patient.id, str(age), str(allergies), str(chronic), str(medications));
                    if (ok) {
                        SyncCoordinator.kick(this);
                        LocalSyncManager.kick(this);
                        toast("تم حفظ المعلومات الطبية");
                        if (afterSave != null) afterSave.run();
                        return true;
                    }
                    toast("تعذر الحفظ أو لا توجد صلاحية");return false;
                });
    }

    private void lookupPatientDialog() {
        LinearLayout box = dialogBox();
        EditText q = field("رقم الكرت أو الهاتف أو الاسم", false);
        box.addView(q);
        validatedDialog("البحث عن مريض",box,"بحث",()->{
                    if(str(q).trim().isEmpty()){q.setError("اكتبي رقم الكرت أو الهاتف أو الاسم");return false;}
                    ClinicDb.Patient p = db.findPatient(str(q));
                    if(p==null){q.setError("المريض غير موجود");return false;}
                    showPatientDetail(p.id);return true;
                });
    }

    private void existingVisitDialog(ClinicDb.Patient patient, boolean forceResult) {
        if (db.isOperationalDayClosed()) { toast("اليوم مقفول؛ لا يمكن إضافة زيارة جديدة"); return; }
        if (db.hasOpenVisit(patient.id)) { toast("عند المريض زيارة مفتوحة بالفعل"); showQueue(); return; }
        int followDays = prefs.getInt("followup_days", 7);
        int since = db.daysSinceLastVisit(patient.id);
        boolean free = ClinicWorkflowRules.isFreeFollowup(since, followDays);
        String suggested = free ? ClinicDb.FREE_FOLLOWUP : ClinicDb.PAID_FOLLOWUP;
        String automaticType = forceResult ? ClinicDb.LAB_RESULT : suggested;
        LinearLayout box = dialogBox();
        box.addView(tv("نوع الزيارة محسوب تلقائياً: " + typeLabel(automaticType)
                + (since == 9999 ? " • لا توجد زيارة مكتملة سابقة" : " • آخر زيارة مكتملة قبل " + since + " يوم"), 13, muted, false));
        new AlertDialog.Builder(this)
                .setTitle(patient.name)
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("إضافة للطابور", (d, w) -> {
                    long id = db.createVisit(patient.id, automaticType, feeFor(automaticType));
                    if (id == -2) toast("اليوم مقفول؛ لا يمكن إضافة زيارة");
                    else if (id < 0) toast("تعذر الإضافة: توجد زيارة مفتوحة أو لا توجد صلاحية");
                    else { toast("تمت إضافة الزيارة"); showQueue(); }
                }).show();
    }

    private void paymentDialog(ClinicDb.Visit visit) {
        if (db.isOperationalDayClosed()) { toast("اليوم مقفول؛ لا يمكن تسجيل دفعة جديدة"); return; }
        LinearLayout box = dialogBox();
        EditText amount = field("المبلغ", false); amount.setInputType(InputType.TYPE_CLASS_NUMBER); amount.setText(String.valueOf(visit.remaining()));
        Spinner method = spinner(new String[]{"كاش", "تحويل بنكي", "محفظة", "أخرى"});
        box.addView(tv("المتبقي على " + visit.patientName + ": " + money(visit.remaining()), 14, muted, false));
        box.addView(space(8)); box.addView(amount); box.addView(space(8)); box.addView(method);
        validatedDialog("تسجيل دفعة",box,"حفظ",()->{
                    int a = intValue(amount, 0);
                    if(a<=0){amount.setError("اكتبي مبلغاً صحيحاً");return false;}
                    if(a>visit.remaining()){amount.setError("المبلغ أكبر من المتبقي");return false;}
                    if(!db.recordPayment(visit.id,a,String.valueOf(method.getSelectedItem()))){toast("المبلغ غير صحيح أو اليوم مقفول");return false;}
                    toast("تم تسجيل الدفعة");showFinance();return true;
                });
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
        e.setPadding(dp(13), dp(10), dp(13), dp(10));
        e.setBackground(strokeBg(surface, line, 14));
        e.setContentDescription(hint);
        if (multiline) { e.setMinLines(2); e.setGravity(Gravity.TOP | Gravity.RIGHT); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); }
        else e.setSingleLine(true);
        return e;
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items);
        s.setAdapter(adapter);
        s.setPadding(dp(8), dp(4), dp(8), dp(4));
        s.setBackground(strokeBg(surface, line, 14));
        s.setContentDescription("اختيار من القائمة");
        s.setMinimumHeight(dp(48));
        return s;
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), dp(4));
        return box;
    }

    private interface DialogAction { boolean run(); }

    private void validatedDialog(String title, View view, String positive, DialogAction action) {
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(title).setView(view)
                .setNegativeButton("رجوع",null).setPositiveButton(positive,null).create();
        dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(action.run()) dialog.dismiss();
        }));
        dialog.show();
    }

    private TextView primaryButton(String text, View.OnClickListener click) { return button(text, primary, Color.WHITE, click); }
    private TextView secondaryButton(String text, View.OnClickListener click) { return button(text, Color.rgb(235, 242, 242), primaryDark, click); }

    private TextView compactButton(String text, boolean solid, View.OnClickListener click) {
        TextView t = tv(text, 14, solid ? Color.WHITE : primary, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(strokeBg(solid ? primary : surface, solid ? primary : line, 13));
        t.setOnClickListener(click);
        t.setContentDescription(text); t.setFocusable(true); t.setMinHeight(dp(48));
        return t;
    }

    private TextView button(String text, int back, int color, View.OnClickListener click) {
        TextView t = tv(text, 15, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        t.setBackground(round(back, 14));
        t.setOnClickListener(click);
        t.setMinHeight(dp(48));
        t.setContentDescription(text);
        t.setFocusable(true);
        return t;
    }

    private TextView link(String text) {
        TextView t = tv(text, 14, primary, true);
        t.setPadding(dp(4), dp(8), dp(4), dp(8));
        t.setContentDescription(text); t.setFocusable(true); t.setMinHeight(dp(48));
        return t;
    }

    private TextView chip(String text, int back, int color) {
        TextView t = tv(text, 12, color, true);
        t.setPadding(dp(10), dp(6), dp(10), dp(6));
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(back, 99));
        t.setContentDescription(text);
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
    private boolean isOnline() {
        ConnectivityManager manager=(ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
        if(manager==null) return false;
        Network network=manager.getActiveNetwork();
        NetworkCapabilities capabilities=network==null?null:manager.getNetworkCapabilities(network);
        return capabilities!=null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    private String str(EditText e) { return e == null || e.getText() == null ? "" : e.getText().toString(); }
    private int intValue(EditText e, int fallback) { try { return Integer.parseInt(str(e).trim()); } catch (Exception ex) { return fallback; } }
    private int visitFee() { return prefs.getInt("visit_fee", 10000); }
    private int feeFor(String type) {
        if (ClinicDb.FREE_FOLLOWUP.equals(type)) return 0;
        if (ClinicDb.LAB_RESULT.equals(type)) return prefs.getInt("result_fee", 0);
        return visitFee();
    }
    private String safe(String s) { return s == null ? "" : s; }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private boolean isOwnerDoctor() { return auth != null && "owner_doctor".equals(auth.memberRole()); }
    private boolean canEditMedicalProfile() {
        return auth != null && auth.can("edit_clinical");
    }

    private String uiRole(String memberRole) {
        if ("owner_doctor".equals(memberRole) || "substitute_doctor".equals(memberRole)) return ROLE_DOCTOR;
        return ROLE_RECEPTION;
    }

    private String waitingTime(String createdAt) {
        try {
            Date started = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(createdAt);
            if (started == null) return "غير معروف";
            long minutes = Math.max(0, (System.currentTimeMillis() - started.getTime()) / 60000L);
            if (minutes < 60) return minutes + " دقيقة";
            long hours = minutes / 60;
            long rest = minutes % 60;
            return hours + " س " + rest + " د";
        } catch (Exception e) { return "غير معروف"; }
    }

    private String money(int amount) {
        return NumberFormat.getIntegerInstance(new Locale("ar")).format(amount) + " ج.س";
    }

    private String cardLabel(int cardNo) {
        return cardNo > 0 ? "كرت #" + cardNo : "كرت مؤقت — يُثبّت عند المزامنة";
    }

    private String roleLabel(String value) {
        if (ROLE_DOCTOR.equals(value) && isOwnerDoctor()) return "الطبيب المالك";
        if (ROLE_DOCTOR.equals(value)) return "الطبيب";
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
        if (ClinicDb.CANCELLED.equals(value)) return "ملغي";
        return "مسجل";
    }

    private int statusColor(String value) {
        if (ClinicDb.WAITING.equals(value)) return Color.rgb(55, 108, 170);
        if (ClinicDb.IN_CONSULT.equals(value)) return warning;
        if (ClinicDb.COMPLETED.equals(value)) return primary;
        if (ClinicDb.CANCELLED.equals(value)) return Color.rgb(155, 55, 55);
        return Color.rgb(100, 110, 115);
    }
}
