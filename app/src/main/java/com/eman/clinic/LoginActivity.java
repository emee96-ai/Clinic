package com.eman.clinic;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
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
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText email, password, displayName, clinicName, inviteCode;
    private TextView status, login, doctorSignup, joinSignup;
    private final int primary = Color.rgb(14, 113, 105);
    private final int ink = Color.rgb(24, 35, 39);
    private final int muted = Color.rgb(103, 116, 121);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(34), dp(24), dp(30));
        root.setBackgroundColor(Color.rgb(245, 247, 248));
        scroll.addView(root);

        root.addView(text("الدخول للعيادة", 27, ink, true));
        TextView sub = text("الدكتور ينشئ العيادة بحسابه. المسجلة أو الدكتور البديل يعملوا حساباتهم ثم يربطهم الدكتور برمز دعوة.", 14, muted, false);
        sub.setPadding(0, dp(6), 0, dp(20));
        root.addView(sub);

        displayName = field("الاسم الظاهر — مثال: د. أحمد / سارة الاستقبال");
        email = field("البريد الإلكتروني");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password = field("كلمة المرور");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        clinicName = field("اسم العيادة — للدكتور المالك فقط");
        inviteCode = field("رمز الدعوة — للمسجلة أو الدكتور البديل");
        inviteCode.setAllCaps(true);

        root.addView(displayName); root.addView(space(9));
        root.addView(email); root.addView(space(9));
        root.addView(password); root.addView(space(9));
        root.addView(clinicName); root.addView(space(9));
        root.addView(inviteCode); root.addView(space(12));

        status = text("", 13, muted, false);
        root.addView(status); root.addView(space(8));

        login = button("تسجيل الدخول", true);
        login.setOnClickListener(v -> signIn());
        root.addView(login); root.addView(space(10));

        doctorSignup = button("إنشاء حساب الدكتور وعيادة جديدة", false);
        doctorSignup.setOnClickListener(v -> signUpDoctor());
        root.addView(doctorSignup); root.addView(space(10));

        joinSignup = button("إنشاء حساب والانضمام برمز الدعوة", false);
        joinSignup.setOnClickListener(v -> signUpInvitee());
        root.addView(joinSignup);

        TextView note = text("بعد أول ربط، بيانات العيادة تظل محفوظة على الجهاز ويستمر الشغل عند ضعف الشبكة.", 12, muted, false);
        note.setPadding(0, dp(18), 0, 0);
        root.addView(note);
        setContentView(scroll);
    }

    private void signIn() {
        String e = email.getText().toString().trim();
        String p = password.getText().toString();
        if (!valid(e, p)) return;
        busy(true, "جاري تسجيل الدخول والربط…");
        executor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(this);
                api.signIn(e, p);
                boolean linked = api.resolveMembership();
                if (!linked && !str(inviteCode).trim().isEmpty()) linked = api.acceptInvite(str(inviteCode), str(displayName));
                if (!linked && !str(clinicName).trim().isEmpty()) linked = api.createDoctorClinic(str(clinicName), str(displayName));
                if (!linked) throw new Exception("الحساب غير مربوط بعيادة. للدكتور: اكتبي اسم العيادة. للموظف: اكتبي رمز الدعوة.");
                success();
            } catch (Exception ex) {
                runOnUiThread(() -> busy(false, friendly(ex)));
            }
        });
    }

    private void signUpDoctor() {
        String e = email.getText().toString().trim();
        String p = password.getText().toString();
        String c = str(clinicName).trim();
        if (!valid(e, p)) return;
        if (c.length() < 2) { toast("اكتبي اسم العيادة"); return; }
        busy(true, "جاري إنشاء حساب الدكتور…");
        executor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(this);
                api.auth().setPendingClinicName(c);
                boolean session = api.signUp(e, p);
                if (session) {
                    if (!api.createDoctorClinic(c, str(displayName))) throw new Exception("تعذر إنشاء العيادة");
                    success();
                } else {
                    runOnUiThread(() -> busy(false, "تم إنشاء الحساب. أكدي البريد، وبعدها سجلي الدخول واكتبي اسم العيادة مرة واحدة."));
                }
            } catch (Exception ex) { runOnUiThread(() -> busy(false, friendly(ex))); }
        });
    }

    private void signUpInvitee() {
        String e = email.getText().toString().trim();
        String p = password.getText().toString();
        String code = str(inviteCode).trim();
        if (!valid(e, p)) return;
        if (code.length() < 8) { toast("اكتبي رمز الدعوة من الدكتور"); return; }
        busy(true, "جاري إنشاء الحساب وربطه بالعيادة…");
        executor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(this);
                boolean session = api.signUp(e, p);
                if (session) {
                    if (!api.acceptInvite(code, str(displayName))) throw new Exception("تعذر قبول الدعوة");
                    success();
                } else {
                    runOnUiThread(() -> busy(false, "تم إنشاء الحساب. أكدي البريد، ثم سجلي الدخول بنفس رمز الدعوة."));
                }
            } catch (Exception ex) { runOnUiThread(() -> busy(false, friendly(ex))); }
        });
    }

    private void success() {
        runOnUiThread(() -> {
            busy(false, "تم الربط ✓");
            SyncCoordinator.kick(this);
            startActivity(new Intent(this, MainActivity.class));
            finish();
        });
    }

    private boolean valid(String e, String p) {
        if (!e.contains("@")) { toast("اكتبي بريد إلكتروني صحيح"); return false; }
        if (p.length() < 6) { toast("كلمة المرور لازم تكون 6 أحرف أو أكثر"); return false; }
        return true;
    }

    private void busy(boolean value, String message) {
        login.setEnabled(!value); doctorSignup.setEnabled(!value); joinSignup.setEnabled(!value);
        float a = value ? 0.55f : 1f;
        login.setAlpha(a); doctorSignup.setAlpha(a); joinSignup.setAlpha(a);
        status.setText(message);
    }

    private String friendly(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return "تعذر الاتصال بالشبكة";
        if (m.contains("Invalid login credentials")) return "البريد أو كلمة المرور غير صحيحة";
        if (m.contains("Email not confirmed")) return "أكدي البريد الإلكتروني أولاً ثم سجلي الدخول";
        if (m.contains("invite_invalid_or_expired") || m.contains("invalid_invite")) return "رمز الدعوة غير صحيح أو انتهت صلاحيته";
        if (m.contains("duplicate") || m.contains("already registered")) return "الحساب موجود مسبقاً؛ استخدمي تسجيل الدخول";
        if (m.contains("Network") || m.contains("Unable") || m.contains("timed out") || m.contains("HTTP 0")) return "الشبكة غير متاحة حالياً";
        return m;
    }

    private String str(EditText e) { return e.getText().toString(); }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setTextSize(15); e.setTextColor(ink); e.setHintTextColor(muted);
        e.setSingleLine(true); e.setPadding(dp(14), dp(12), dp(14), dp(12)); e.setBackgroundColor(Color.WHITE); e.setMinHeight(dp(52));
        return e;
    }

    private TextView button(String label, boolean solid) {
        TextView t = text(label, 15, solid ? Color.WHITE : primary, true);
        t.setGravity(Gravity.CENTER); t.setPadding(dp(12), dp(14), dp(12), dp(14));
        t.setBackgroundColor(solid ? primary : Color.WHITE); t.setMinHeight(dp(52));
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return t;
    }

    private View space(int h) { View v = new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h))); return v; }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
