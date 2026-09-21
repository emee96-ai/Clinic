package com.eman.clinic;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class LocalSyncActivity extends Activity {
    private final int primary = Color.rgb(14, 113, 105);
    private final int ink = Color.rgb(24, 35, 39);
    private final int muted = Color.rgb(103, 116, 121);
    private EditText pairCode;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            if (status != null) status.setText(LocalSyncManager.status(LocalSyncActivity.this));
            handler.postDelayed(this, 1800);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        buildUi();
        LocalSyncManager.start(this);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresher);
        super.onPause();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(28), dp(22), dp(28));
        root.setBackgroundColor(Color.rgb(245, 247, 248));
        scroll.addView(root);

        root.addView(tv("ربط أجهزة العيادة", 27, ink, true));
        TextView sub = tv("وصّلي تلفون الدكتور وتلفون المسجلة بنفس Wi‑Fi أو Hotspot. بعد الربط، الطابور والكشف يتزامنوا حتى بدون إنترنت.", 14, muted, false);
        sub.setPadding(0, dp(7), 0, dp(18));
        root.addView(sub);

        AuthStore auth = new AuthStore(this);
        LinearLayout info = card();
        info.addView(tv(auth.clinicName().isEmpty() ? "العيادة" : auth.clinicName(), 18, ink, true));
        info.addView(tv("الحساب: " + roleLabel(auth.memberRole()), 13, muted, false));
        root.addView(info);
        root.addView(space(14));

        status = tv(LocalSyncManager.status(this), 14, primary, true);
        status.setPadding(dp(14), dp(13), dp(14), dp(13));
        status.setBackground(round(Color.rgb(232, 246, 244), 14));
        root.addView(status);
        root.addView(space(18));

        pairCode = new EditText(this);
        pairCode.setHint("رمز الربط المحلي");
        pairCode.setText(LocalSyncManager.pairKey(this));
        pairCode.setSingleLine(true);
        pairCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        pairCode.setPadding(dp(14), 0, dp(14), 0);
        pairCode.setBackground(round(Color.WHITE, 12));
        root.addView(pairCode, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));
        root.addView(space(10));

        boolean owner = "owner_doctor".equals(auth.memberRole());
        if (owner) {
            root.addView(primaryButton("إنشاء رمز ربط للعيادة", v -> {
                String code = LocalSyncManager.generatePairKey();
                pairCode.setText(code);
                LocalSyncManager.savePairKey(this, code);
                copy(code);
                toast("تم إنشاء الرمز ونسخه. أدخليه مرة واحدة في أجهزة الفريق.");
            }));
            root.addView(space(8));
        }

        root.addView(secondaryButton("حفظ رمز الربط في هذا الجهاز", v -> {
            String code = pairCode.getText().toString().trim();
            if (code.length() < 16) {
                toast("رمز الربط قصير أو غير صحيح");
                return;
            }
            LocalSyncManager.savePairKey(this, code);
            toast("تم حفظ الرمز");
        }));
        root.addView(space(8));
        root.addView(secondaryButton("نسخ الرمز", v -> copy(pairCode.getText().toString().trim())));
        root.addView(space(8));
        root.addView(secondaryButton("مزامنة الآن", v -> {
            LocalSyncManager.kick(this);
            toast("جاري البحث عن أجهزة العيادة القريبة");
        }));

        root.addView(space(20));
        LinearLayout how = card();
        how.addView(tv("طريقة الاستخدام", 18, ink, true));
        how.addView(tv("1) الدكتور المالك ينشئ الرمز.\n2) نفس الرمز يتحفظ في جهاز كل مسجلة أو دكتور بديل.\n3) الأجهزة تكون على نفس Wi‑Fi أو Hotspot.\n4) المزامنة بعد كده تلقائية ومشفّرة.", 14, muted, false));
        root.addView(how);

        setContentView(scroll);
    }

    private String roleLabel(String role) {
        if ("owner_doctor".equals(role)) return "الدكتور المالك";
        if ("substitute_doctor".equals(role)) return "دكتور بديل";
        return "المسجلة / موظف العيادة";
    }

    private void copy(String value) {
        if (value == null || value.isEmpty()) { toast("مافي رمز للنسخ"); return; }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Clinic local sync", value));
        toast("تم نسخ الرمز");
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(15), dp(16), dp(15));
        c.setBackground(round(Color.WHITE, 14));
        return c;
    }

    private TextView primaryButton(String label, View.OnClickListener listener) {
        TextView t = tv(label, 15, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(primary, 12));
        t.setOnClickListener(listener);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)));
        return t;
    }

    private TextView secondaryButton(String label, View.OnClickListener listener) {
        TextView t = tv(label, 15, primary, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(round(Color.WHITE, 12));
        t.setOnClickListener(listener);
        t.setPadding(dp(12), 0, dp(12), 0);
        t.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(50)));
        return t;
    }

    private TextView tv(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private View space(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
}
