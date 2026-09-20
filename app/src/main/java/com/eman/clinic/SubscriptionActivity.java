package com.eman.clinic;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SubscriptionActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AuthStore auth;
    private TextView status;
    private final int primary = Color.rgb(14, 113, 105);
    private final int ink = Color.rgb(24, 35, 39);
    private final int muted = Color.rgb(103, 116, 121);

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        auth = new AuthStore(this);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(48), dp(24), dp(32));
        root.setBackgroundColor(Color.rgb(245, 247, 248));

        root.addView(text("الاشتراك غير نشط", 27, ink, true));
        TextView clinic = text(auth.clinicName(), 17, muted, true);
        clinic.setPadding(0, dp(8), 0, dp(18));
        root.addView(clinic);

        status = text(messageFor(auth.subscriptionReason()), 16, ink, false);
        status.setPadding(dp(16), dp(16), dp(16), dp(16));
        status.setBackgroundColor(Color.WHITE);
        root.addView(status);

        TextView info = text(subscriptionInfo(), 14, muted, false);
        info.setPadding(0, dp(14), 0, dp(20));
        root.addView(info);

        TextView retry = button("إعادة التحقق من الاشتراك", true);
        retry.setOnClickListener(v -> recheck(retry));
        root.addView(retry);

        TextView logout = button("تسجيل الخروج", false);
        logout.setOnClickListener(v -> {
            auth.clearRemoteSession();
            startActivity(new Intent(this, LoginActivity.class));
            finish();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(10), 0, 0);
        logout.setLayoutParams(lp);
        root.addView(logout);

        setContentView(root);
    }

    private void recheck(TextView button) {
        button.setEnabled(false);
        button.setAlpha(0.6f);
        status.setText("جاري التحقق…");
        executor.execute(() -> {
            try {
                boolean allowed = new SupabaseApi(this).refreshEntitlement();
                runOnUiThread(() -> {
                    if (allowed) {
                        startActivity(new Intent(this, MainActivity.class));
                        finish();
                    } else {
                        status.setText(messageFor(auth.subscriptionReason()));
                        button.setEnabled(true);
                        button.setAlpha(1f);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("تعذر التحقق الآن. تأكدي من الشبكة وحاولي مرة أخرى.");
                    button.setEnabled(true);
                    button.setAlpha(1f);
                });
            }
        });
    }

    private String subscriptionInfo() {
        String plan = auth.subscriptionPlan();
        if ("trial".equals(plan)) {
            String until = auth.trialEndsAt();
            return until.isEmpty() ? "الخطة: تجربة مجانية" : "الخطة: تجربة مجانية\nتنتهي: " + until;
        }
        String until = auth.paidUntil();
        return "الخطة: " + plan + (until.isEmpty() ? "" : "\nمدفوع حتى: " + until);
    }

    private String messageFor(String reason) {
        if ("trial_expired".equals(reason)) return "انتهت فترة التجربة المجانية للعيادة.";
        if ("subscription_expired".equals(reason)) return "انتهت مدة الاشتراك المدفوع.";
        if ("past_due".equals(reason)) return "الاشتراك عليه مبلغ مستحق ويحتاج تجديد.";
        if ("suspended".equals(reason)) return "تم إيقاف اشتراك العيادة من إدارة النظام.";
        if ("cancelled".equals(reason)) return "تم إلغاء اشتراك العيادة.";
        return "الاشتراك غير متاح حاليًا.";
    }

    private TextView button(String label, boolean solid) {
        TextView t = text(label, 15, solid ? Color.WHITE : primary, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(14), dp(12), dp(14));
        t.setBackgroundColor(solid ? primary : Color.WHITE);
        t.setMinHeight(dp(52));
        return t;
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value == null ? "" : value);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
