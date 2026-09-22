package com.eman.clinic;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class LoginActivity extends Activity {
    private static final int MODE_LOGIN = 0;
    private static final int MODE_OWNER = 1;
    private static final int MODE_JOIN = 2;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText email, password, displayName, clinicName, inviteCode;
    private TextView status, primaryAction, secondaryAction, modeLogin, modeOwner, modeJoin, helper;
    private LinearLayout nameBox, clinicBox, inviteBox;
    private int mode = MODE_LOGIN;
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        buildUi();
        setMode(MODE_LOGIN);
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(28));
        root.setBackgroundColor(ClinicUi.BG);
        scroll.addView(root);

        TextView brand = ClinicUi.text(this, "العيادة", 15, ClinicUi.PRIMARY, true);
        root.addView(brand);
        root.addView(ClinicUi.text(this, "ابدئي حسب نوع حسابك", 28, ClinicUi.INK, true));
        helper = ClinicUi.text(this, "", 14, ClinicUi.MUTED, false);
        helper.setPadding(0, dp(5), 0, dp(16));
        root.addView(helper);

        LinearLayout modes = new LinearLayout(this);
        modes.setOrientation(LinearLayout.HORIZONTAL);
        modeLogin = modeButton("دخول", MODE_LOGIN);
        modeOwner = modeButton("عيادة جديدة", MODE_OWNER);
        modeJoin = modeButton("رمز دعوة", MODE_JOIN);
        modes.addView(modeLogin, modeParams(true));
        modes.addView(modeOwner, modeParams(false));
        modes.addView(modeJoin, modeParams(false));
        root.addView(modes);
        root.addView(ClinicUi.space(this, 14));

        LinearLayout form = ClinicUi.card(this);
        displayName = ClinicUi.field(this, "الاسم الظاهر — مثال: د. أحمد / سارة الاستقبال");
        email = ClinicUi.field(this, "البريد الإلكتروني");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password = ClinicUi.field(this, "كلمة المرور");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        clinicName = ClinicUi.field(this, "اسم العيادة");
        inviteCode = ClinicUi.field(this, "رمز الدعوة من الدكتور");
        inviteCode.setAllCaps(true);

        nameBox = ClinicUi.labeled(this, "الاسم", displayName);
        clinicBox = ClinicUi.labeled(this, "اسم العيادة", clinicName);
        inviteBox = ClinicUi.labeled(this, "رمز الدعوة", inviteCode);
        form.addView(nameBox);
        form.addView(clinicBox);
        form.addView(inviteBox);
        form.addView(ClinicUi.labeled(this, "البريد الإلكتروني", email));
        form.addView(ClinicUi.labeled(this, "كلمة المرور", password));

        status = ClinicUi.status(this, "", false, false);
        status.setVisibility(View.GONE);
        form.addView(status);
        form.addView(ClinicUi.space(this, 10));

        primaryAction = ClinicUi.button(this, "تسجيل الدخول", true);
        primaryAction.setOnClickListener(v -> runPrimaryAction());
        form.addView(primaryAction);

        secondaryAction = ClinicUi.softButton(this, "");
        secondaryAction.setOnClickListener(v -> runSecondaryAction());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.setMargins(0, dp(9), 0, 0);
        secondaryAction.setLayoutParams(sp);
        form.addView(secondaryAction);
        root.addView(form);

        LinearLayout note = ClinicUi.card(this);
        note.addView(ClinicUi.text(this, "الشغل ما بوقف مع ضعف الشبكة", 16, ClinicUi.INK, true));
        note.addView(ClinicUi.text(this, "بعد أول ربط بالحساب، بيانات العيادة تظل محفوظة على الجهاز والمزامنة تكمل تلقائياً عند توفر الشبكة.", 13, ClinicUi.MUTED, false));
        root.addView(note);

        setContentView(scroll);
    }

    private TextView modeButton(String label, int target) {
        TextView t = ClinicUi.text(this, label, 13, ClinicUi.MUTED, true);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(46));
        t.setOnClickListener(v -> { if (!busy) setMode(target); });
        return t;
    }

    private LinearLayout.LayoutParams modeParams(boolean first) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
        if (!first) p.setMargins(dp(6), 0, 0, 0);
        return p;
    }

    private void setMode(int next) {
        mode = next;
        clearStatus();
        boolean owner = mode == MODE_OWNER;
        boolean join = mode == MODE_JOIN;
        nameBox.setVisibility(owner || join ? View.VISIBLE : View.GONE);
        clinicBox.setVisibility(owner ? View.VISIBLE : View.GONE);
        inviteBox.setVisibility(join ? View.VISIBLE : View.GONE);
        secondaryAction.setVisibility(mode == MODE_LOGIN ? View.GONE : View.VISIBLE);

        if (mode == MODE_LOGIN) {
            helper.setText("لو حسابك مربوط بعيادة من قبل، دخلي بالبريد وكلمة المرور فقط.");
            primaryAction.setText("تسجيل الدخول");
        } else if (mode == MODE_OWNER) {
            helper.setText("الدكتور المالك ينشئ حسابه وعيادته، وبعدها يقدر يدعو المسجلة والدكتور البديل.");
            primaryAction.setText("إنشاء حساب وعيادة جديدة");
            secondaryAction.setText("الحساب موجود — إكمال إنشاء العيادة");
        } else {
            helper.setText("للمسجلة أو الدكتور البديل: استخدمي رمز الدعوة الصادر من الدكتور المالك.");
            primaryAction.setText("إنشاء حساب والانضمام للعيادة");
            secondaryAction.setText("الحساب موجود — ربطه برمز الدعوة");
        }
        styleModes();
    }

    private void styleModes() {
        TextView[] views = {modeLogin, modeOwner, modeJoin};
        for (int i = 0; i < views.length; i++) {
            boolean active = i == mode;
            views[i].setTextColor(active ? Color.WHITE : ClinicUi.PRIMARY_DARK);
            views[i].setBackground(active
                    ? ClinicUi.round(this, ClinicUi.PRIMARY, 13)
                    : ClinicUi.stroke(this, ClinicUi.SURFACE, ClinicUi.LINE, 13));
        }
    }

    private void runPrimaryAction() {
        if (mode == MODE_LOGIN) signIn();
        else if (mode == MODE_OWNER) signUpDoctor();
        else signUpInvitee();
    }

    private void runSecondaryAction() {
        if (mode == MODE_OWNER) linkExistingOwner();
        else if (mode == MODE_JOIN) linkExistingInvitee();
    }

    private void signIn() {
        String e = str(email).trim(), p = str(password);
        if (!valid(e, p)) return;
        setBusy(true, "جاري تسجيل الدخول…");
        executor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(this);
                api.signIn(e, p);
                if (!api.resolveMembership()) throw new Exception("account_not_linked");
                success();
            } catch (Exception ex) { fail(ex); }
        });
    }

    private void signUpDoctor() {
        String e = str(email).trim(), p = str(password), c = str(clinicName).trim(), name = str(displayName).trim();
        if (!valid(e, p)) return;
        if (name.length() < 2) { displayName.setError("اكتبي اسم الدكتور"); return; }
        if (c.length() < 2) { clinicName.setError("اكتبي اسم العيادة"); return; }
        setBusy(true, "جاري إنشاء حساب الدكتور…");
        executor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(this);
                api.auth().setPendingClinicName(c);
                boolean session = api.signUp(e, p);
                if (session) {
                    if (!api.createDoctorClinic(c, name)) throw new Exception("clinic_create_failed");
                    success();
                } else {
                    runOnUiThread(() -> {
                        setBusy(false, "");
                        showStatus("تم إنشاء الحساب. أكدي البريد، وبعدها ارجعي لنفس قسم «عيادة جديدة» واضغطي إكمال إنشاء العيادة.", true, false);
                    });
                }
            } catch (Exception ex) { fail(ex); }
        });
    }

    private void linkExistingOwner() {
        String e = str(email).trim(), p = str(password), c = str(clinicName).trim(), name = str(displayName).trim();
        if (!valid(e, p)) return;
        if (name.length() < 2) { displayName.setError("اكتبي اسم الدكتور"); return; }
        if (c.length() < 2) { clinicName.setError("اكتبي اسم العيادة"); return; }
        setBusy(true, "جاري تسجيل الدخول وإنشاء العيادة…");
        executor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(this);
                api.signIn(e, p);
                if (!api.resolveMembership() && !api.createDoctorClinic(c, name)) throw new Exception("clinic_create_failed");
                success();
            } catch (Exception ex) { fail(ex); }
        });
    }

    private void signUpInvitee() {
        String e = str(email).trim(), p = str(password), code = str(inviteCode).trim(), name = str(displayName).trim();
        if (!valid(e, p)) return;
        if (name.length() < 2) { displayName.setError("اكتبي اسمك"); return; }
        if (code.length() < 8) { inviteCode.setError("اكتبي رمز الدعوة"); return; }
        setBusy(true, "جاري إنشاء الحساب…");
        executor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(this);
                boolean session = api.signUp(e, p);
                if (session) {
                    if (!api.acceptInvite(code, name)) throw new Exception("invite_invalid_or_expired");
                    success();
                } else {
                    runOnUiThread(() -> {
                        setBusy(false, "");
                        showStatus("تم إنشاء الحساب. أكدي البريد، وبعدها ارجعي لقسم «رمز دعوة» واضغطي ربط الحساب بالرمز.", true, false);
                    });
                }
            } catch (Exception ex) { fail(ex); }
        });
    }

    private void linkExistingInvitee() {
        String e = str(email).trim(), p = str(password), code = str(inviteCode).trim(), name = str(displayName).trim();
        if (!valid(e, p)) return;
        if (name.length() < 2) { displayName.setError("اكتبي اسمك"); return; }
        if (code.length() < 8) { inviteCode.setError("اكتبي رمز الدعوة"); return; }
        setBusy(true, "جاري ربط الحساب بالعيادة…");
        executor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(this);
                api.signIn(e, p);
                if (!api.resolveMembership() && !api.acceptInvite(code, name)) throw new Exception("invite_invalid_or_expired");
                success();
            } catch (Exception ex) { fail(ex); }
        });
    }

    private void success() {
        runOnUiThread(() -> {
            setBusy(false, "");
            showStatus("تم الربط بالعيادة ✓", true, false);
            SyncCoordinator.kick(this);
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
    }

    private void fail(Exception ex) {
        runOnUiThread(() -> {
            setBusy(false, "");
            showStatus(friendly(ex), false, true);
        });
    }

    private boolean valid(String e, String p) {
        if (!e.contains("@") || e.startsWith("@") || e.endsWith("@")) { email.setError("اكتبي بريد إلكتروني صحيح"); return false; }
        if (p.length() < 6) { password.setError("6 أحرف أو أكثر"); return false; }
        return true;
    }

    private void setBusy(boolean value, String message) {
        busy = value;
        primaryAction.setEnabled(!value);
        secondaryAction.setEnabled(!value);
        modeLogin.setEnabled(!value); modeOwner.setEnabled(!value); modeJoin.setEnabled(!value);
        float alpha = value ? 0.55f : 1f;
        primaryAction.setAlpha(alpha); secondaryAction.setAlpha(alpha);
        if (value) showStatus(message, false, false);
    }

    private void clearStatus() {
        if (status != null) { status.setText(""); status.setVisibility(View.GONE); }
    }

    private void showStatus(String message, boolean good, boolean bad) {
        status.setText(message == null ? "" : message);
        status.setTextColor(bad ? ClinicUi.ERROR : (good ? ClinicUi.PRIMARY : ClinicUi.MUTED));
        int back = bad ? Color.rgb(252,239,239) : (good ? ClinicUi.SOFT : Color.rgb(240,243,244));
        status.setBackground(ClinicUi.round(this, back, 13));
        status.setVisibility(View.VISIBLE);
    }

    private String friendly(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("Invalid login credentials")) return "البريد أو كلمة المرور غير صحيحة";
        if (m.contains("Email not confirmed")) return "أكدي البريد الإلكتروني أولاً ثم حاولي مرة تانية";
        if (m.contains("invite_invalid_or_expired") || m.contains("invalid_invite")) return "رمز الدعوة غير صحيح أو انتهت صلاحيته أو استُخدم بالكامل";
        if (m.contains("already registered") || m.contains("User already registered") || m.contains("duplicate")) return "الحساب موجود مسبقاً؛ استخدمي زر ربط الحساب الموجود";
        if (m.contains("account_not_linked")) return "الحساب صحيح لكنه غير مربوط بعيادة. اختاري «عيادة جديدة» أو «رمز دعوة» حسب حسابك.";
        if (m.contains("clinic_create_failed")) return "تعذر إنشاء العيادة. تأكدي إن الحساب ما مربوط بعيادة أخرى.";
        if (m.contains("Network") || m.contains("Unable") || m.contains("timed out") || m.contains("HTTP 0") || m.contains("Failed to connect")) return "الشبكة غير متاحة حالياً. البيانات المحلية ما بتتأثر.";
        if (m == null || m.trim().isEmpty() || "null".equals(m)) return "تعذر إكمال العملية";
        return m;
    }

    private String str(EditText e) { return e.getText() == null ? "" : e.getText().toString(); }
    private int dp(int v) { return ClinicUi.dp(this, v); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
