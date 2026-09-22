package com.eman.clinic;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SubscriptionActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private AuthStore auth;
    private TextView status;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        auth = new AuthStore(this);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(30),dp(20),dp(28));
        root.setBackgroundColor(ClinicUi.BG);
        scroll.addView(root);

        root.addView(ClinicUi.text(this,"الاشتراك يحتاج إجراء",27,ClinicUi.INK,true));
        TextView clinic = ClinicUi.text(this,auth.clinicName().isEmpty()?"العيادة":auth.clinicName(),16,ClinicUi.MUTED,true);
        clinic.setPadding(0,dp(5),0,dp(16));
        root.addView(clinic);

        LinearLayout reasonCard = ClinicUi.card(this);
        reasonCard.addView(ClinicUi.text(this,"حالة الاشتراك",14,ClinicUi.MUTED,true));
        status = ClinicUi.text(this,messageFor(auth.subscriptionReason()),17,ClinicUi.INK,true);
        status.setPadding(0,dp(6),0,0);
        reasonCard.addView(status);
        root.addView(reasonCard);

        LinearLayout planCard = ClinicUi.card(this);
        planCard.addView(ClinicUi.text(this,"تفاصيل الخطة",16,ClinicUi.INK,true));
        planCard.addView(ClinicUi.text(this,subscriptionInfo(),14,ClinicUi.MUTED,false));
        root.addView(planCard);

        TextView payment = ClinicUi.button(this,"إرسال بيانات الدفع / التحويل",true);
        payment.setOnClickListener(v -> startActivity(new Intent(this,BillingActivity.class)));
        root.addView(payment);
        root.addView(ClinicUi.space(this,9));

        TextView retry = ClinicUi.button(this,"إعادة التحقق من الاشتراك",false);
        retry.setOnClickListener(v -> recheck(retry));
        root.addView(retry);
        root.addView(ClinicUi.space(this,9));

        TextView logout = ClinicUi.softButton(this,"تسجيل الخروج من الحساب");
        logout.setOnClickListener(v -> {
            auth.clearRemoteSession();
            startActivity(new Intent(this,LoginActivity.class));
            finish();
        });
        root.addView(logout);
        root.addView(ClinicUi.space(this,16));

        LinearLayout note = ClinicUi.card(this);
        note.addView(ClinicUi.text(this,"بيانات العيادة المحلية محفوظة",16,ClinicUi.INK,true));
        note.addView(ClinicUi.text(this,"تعطيل الاشتراك ما بحذف ملفات المرضى المحلية من الجهاز. بعد تفعيل الاشتراك وإعادة التحقق، ترجعي للشغل والمزامنة بصورة طبيعية.",13,ClinicUi.MUTED,false));
        root.addView(note);

        setContentView(scroll);
    }

    private void recheck(TextView button) {
        button.setEnabled(false);
        button.setAlpha(0.55f);
        status.setText("جاري التحقق من الاشتراك…");
        status.setTextColor(ClinicUi.MUTED);
        executor.execute(() -> {
            try {
                boolean allowed = new SupabaseApi(this).refreshEntitlement();
                runOnUiThread(() -> {
                    if (allowed) {
                        startActivity(new Intent(this,MainActivity.class));
                        finish();
                    } else {
                        status.setText(messageFor(auth.subscriptionReason()));
                        status.setTextColor(ClinicUi.INK);
                        button.setEnabled(true);
                        button.setAlpha(1f);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("تعذر التحقق الآن. تأكدي من الشبكة وحاولي مرة تانية.");
                    status.setTextColor(ClinicUi.ERROR);
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
            return until.isEmpty()?"الخطة: تجربة مجانية":"الخطة: تجربة مجانية\nتنتهي: "+until;
        }
        String until = auth.paidUntil();
        return "الخطة: "+planLabel(plan)+(until.isEmpty()?"":"\nمدفوع حتى: "+until);
    }

    private String planLabel(String plan) {
        if ("basic".equals(plan)) return "Basic";
        if ("plus".equals(plan)) return "Plus";
        if ("annual".equals(plan)) return "Annual";
        return plan == null || plan.isEmpty()?"غير محددة":plan;
    }

    private String messageFor(String reason) {
        if ("trial_expired".equals(reason)) return "انتهت فترة التجربة المجانية.";
        if ("subscription_expired".equals(reason)) return "انتهت مدة الاشتراك المدفوع.";
        if ("past_due".equals(reason)) return "الاشتراك عليه مبلغ مستحق ويحتاج تجديد.";
        if ("suspended".equals(reason)) return "تم إيقاف اشتراك العيادة من إدارة النظام.";
        if ("cancelled".equals(reason)) return "تم إلغاء اشتراك العيادة.";
        return "الاشتراك غير نشط حالياً.";
    }

    private int dp(int v){ return ClinicUi.dp(this,v); }

    @Override protected void onDestroy(){ executor.shutdownNow(); super.onDestroy(); }
}
