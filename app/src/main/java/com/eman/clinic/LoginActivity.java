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
    private EditText email, password, clinicName;
    private TextView status, login, signup;
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
        root.setPadding(dp(24), dp(42), dp(24), dp(30));
        root.setBackgroundColor(Color.rgb(245, 247, 248));
        scroll.addView(root);

        TextView title = text("ربط الجهاز بالعيادة", 26, ink, true);
        root.addView(title);
        TextView sub = text("سجلي الدخول مرة واحدة، وبعدها التطبيق يواصل شغله حتى لو الشبكة قطعت.", 14, muted, false);
        sub.setPadding(0, dp(6), 0, dp(22));
        root.addView(sub);

        email = field("البريد الإلكتروني");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password = field("كلمة المرور");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        clinicName = field("اسم العيادة — مطلوب للحساب الجديد فقط");
        root.addView(email); root.addView(space(10));
        root.addView(password); root.addView(space(10));
        root.addView(clinicName); root.addView(space(14));

        status = text("", 13, muted, false);
        root.addView(status); root.addView(space(8));

        login = button("دخول وربط الجهاز", true);
        login.setOnClickListener(v -> signIn());
        root.addView(login); root.addView(space(10));

        signup = button("إنشاء حساب عيادة جديدة", false);
        signup.setOnClickListener(v -> signUp());
        root.addView(signup); root.addView(space(18));

        TextView local = text("الدخول للتطبيق بدون مزامنة الآن", 14, primary, true);
        local.setGravity(Gravity.CENTER);
        local.setPadding(dp(12), dp(12), dp(12), dp(12));
        local.setOnClickListener(v -> openClinic());
        root.addView(local);

        setContentView(scroll);
    }

    private void signIn() {
        String e = email.getText().toString().trim();
        String p = password.getText().toString();
        if (!valid(e, p)) return;
        busy(true, "جاري تسجيل الدخول…");
        executor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(this);
                api.signIn(e, p);
                String requested = clinicName.getText().toString().trim();
                if (requested.isEmpty()) requested = api.auth().pendingClinicName();
                boolean ok = api.resolveOrCreateClinic(requested);
                if (!ok) throw new Exception("اكتبي اسم العيادة لإكمال الربط");
                runOnUiThread(() -> {
                    busy(false, "تم الربط ✓");
                    SyncCoordinator.kick(this);
                    openClinic();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> busy(false, friendly(ex)));
            }
        });
    }

    private void signUp() {
        String e = email.getText().toString().trim();
        String p = password.getText().toString();
        String c = clinicName.getText().toString().trim();
        if (!valid(e, p)) return;
        if (c.length() < 2) { toast("اكتبي اسم العيادة"); return; }
        busy(true, "جاري إنشاء الحساب…");
        executor.execute(() -> {
            try {
                SupabaseApi api = new SupabaseApi(this);
                api.auth().setPendingClinicName(c);
                boolean hasSession = api.signUp(e, p);
                if (hasSession) {
                    api.resolveOrCreateClinic(c);
                    runOnUiThread(() -> {
                        busy(false, "تم إنشاء وربط العيادة ✓");
                        SyncCoordinator.kick(this);
                        openClinic();
                    });
                } else {
                    runOnUiThread(() -> busy(false, "تم إنشاء الحساب. أكدي البريد الإلكتروني ثم ارجعي وسجلي الدخول."));
                }
            } catch (Exception ex) {
                runOnUiThread(() -> busy(false, friendly(ex)));
            }
        });
    }

    private boolean valid(String e, String p) {
        if (!e.contains("@")) { toast("اكتبي بريد إلكتروني صحيح"); return false; }
        if (p.length() < 6) { toast("كلمة المرور لازم تكون 6 أحرف أو أكثر"); return false; }
        return true;
    }

    private void busy(boolean value, String message) {
        login.setEnabled(!value);
        signup.setEnabled(!value);
        login.setAlpha(value ? 0.55f : 1f);
        signup.setAlpha(value ? 0.55f : 1f);
        status.setText(message);
    }

    private void openClinic() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private String friendly(Exception e) {
        String m = e.getMessage();
        if (m == null || m.trim().isEmpty()) return "تعذر الاتصال. التطبيق المحلي ما زال شغال.";
        if (m.contains("Invalid login credentials")) return "البريد أو كلمة المرور غير صحيحة";
        if (m.contains("Email not confirmed")) return "أكدي البريد الإلكتروني أولاً ثم سجلي الدخول";
        if (m.contains("Network") || m.contains("Unable") || m.contains("timed out")) return "الشبكة غير متاحة حالياً. ممكن تواصلي محلياً.";
        return m;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(15);
        e.setTextColor(ink);
        e.setHintTextColor(muted);
        e.setSingleLine(true);
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        e.setBackgroundColor(Color.WHITE);
        e.setMinHeight(dp(52));
        return e;
    }

    private TextView button(String label, boolean solid) {
        TextView t = text(label, 15, solid ? Color.WHITE : primary, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(14), dp(12), dp(14));
        t.setBackgroundColor(solid ? primary : Color.WHITE);
        t.setMinHeight(dp(52));
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
