package com.eman.clinic;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BillingActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText amount, reference, days, notes;
    private Spinner method, plan;
    private TextView submit, status;
    private AuthStore auth;

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
        root.setPadding(dp(20), dp(26), dp(20), dp(28));
        root.setBackgroundColor(ClinicUi.BG);
        scroll.addView(root);

        root.addView(ClinicUi.text(this, "الاشتراك والدفع", 27, ClinicUi.INK, true));
        TextView sub = ClinicUi.text(this, "بعد التحويل، أرسلي بيانات العملية للمراجعة. التفعيل يتم بعد تأكيد الدفعة من إدارة المنصة.", 14, ClinicUi.MUTED, false);
        sub.setPadding(0, dp(5), 0, dp(16));
        root.addView(sub);

        LinearLayout clinicCard = ClinicUi.card(this);
        clinicCard.addView(ClinicUi.text(this, auth.clinicName().isEmpty() ? "العيادة" : auth.clinicName(), 18, ClinicUi.INK, true));
        clinicCard.addView(ClinicUi.text(this, "الباقة الحالية: " + planLabel(auth.subscriptionPlan()) + "  •  الحالة: " + statusLabel(auth.subscriptionStatus()), 13, ClinicUi.MUTED, false));
        if (!auth.paidUntil().isEmpty()) clinicCard.addView(ClinicUi.text(this, "مدفوع حتى: " + auth.paidUntil(), 12, ClinicUi.MUTED, false));
        root.addView(clinicCard);

        LinearLayout form = ClinicUi.card(this);
        amount = ClinicUi.field(this, "المبلغ بالجنيه السوداني");
        amount.setInputType(InputType.TYPE_CLASS_NUMBER);
        method = spinner(new String[]{"بنكك", "فوري", "O-Cash", "كاش", "أخرى"});
        reference = ClinicUi.field(this, "رقم العملية / المرجع");
        plan = spinner(new String[]{"Basic", "Plus", "Annual"});
        days = ClinicUi.field(this, "مدة الاشتراك بالأيام");
        days.setInputType(InputType.TYPE_CLASS_NUMBER);
        days.setText("30");
        notes = ClinicUi.field(this, "ملاحظات اختيارية");
        notes.setSingleLine(false);
        notes.setMinLines(2);

        form.addView(ClinicUi.labeled(this, "المبلغ", amount));
        form.addView(ClinicUi.labeled(this, "وسيلة الدفع", method));
        form.addView(ClinicUi.labeled(this, "رقم العملية", reference));
        form.addView(ClinicUi.labeled(this, "الباقة المطلوبة", plan));
        form.addView(ClinicUi.labeled(this, "المدة", days));
        form.addView(ClinicUi.labeled(this, "ملاحظات", notes));

        plan.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == 2 && (str(days).isEmpty() || "30".equals(str(days)))) days.setText("365");
                else if (position != 2 && "365".equals(str(days))) days.setText("30");
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        status = ClinicUi.status(this, "", false, false);
        status.setVisibility(View.GONE);
        form.addView(status);
        form.addView(ClinicUi.space(this, 10));

        submit = ClinicUi.button(this, "إرسال طلب المراجعة", true);
        submit.setOnClickListener(v -> submit());
        form.addView(submit);
        TextView back = ClinicUi.button(this, "رجوع", false);
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, -2);
        bp.setMargins(0, dp(9), 0, 0);
        back.setLayoutParams(bp);
        form.addView(back);
        root.addView(form);

        LinearLayout note = ClinicUi.card(this);
        note.addView(ClinicUi.text(this, "قبل الإرسال", 16, ClinicUi.INK, true));
        note.addView(ClinicUi.text(this, "راجعي رقم العملية والمبلغ كويس. الطلب ما بيفعّل الاشتراك تلقائياً إلا بعد المراجعة والتأكيد.", 13, ClinicUi.MUTED, false));
        root.addView(note);

        setContentView(scroll);
    }

    private void submit() {
        long value = longValue(amount);
        int period = intValue(days);
        String ref = str(reference).trim();
        if (value <= 0) { amount.setError("اكتبي المبلغ"); return; }
        if (ref.length() < 2) { reference.setError("اكتبي رقم العملية"); return; }
        if (period < 1 || period > 730) { days.setError("من يوم إلى 730 يوم"); return; }
        String[] plans = {"basic", "plus", "annual"};
        String selectedPlan = plans[plan.getSelectedItemPosition()];
        busy(true, "جاري إرسال الطلب…", false, false);
        executor.execute(() -> {
            try {
                new BillingApi(this).submitPayment(value, String.valueOf(method.getSelectedItem()), ref,
                        selectedPlan, period, str(notes));
                runOnUiThread(() -> {
                    busy(false, "تم إرسال بيانات الدفع للمراجعة ✓", true, false);
                    submit.setEnabled(false);
                    submit.setAlpha(0.55f);
                });
            } catch (Exception e) {
                runOnUiThread(() -> busy(false, friendly(e), false, true));
            }
        });
    }

    private void busy(boolean value, String message, boolean good, boolean bad) {
        submit.setEnabled(!value);
        if (!good) submit.setAlpha(value ? 0.55f : 1f);
        status.setText(message == null ? "" : message);
        status.setTextColor(bad ? ClinicUi.ERROR : (good ? ClinicUi.PRIMARY : ClinicUi.MUTED));
        int back = bad ? Color.rgb(252,239,239) : (good ? ClinicUi.SOFT : Color.rgb(240,243,244));
        status.setBackground(ClinicUi.round(this, back, 13));
        status.setVisibility(message == null || message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private String friendly(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("not_signed_in")) return "سجلي الدخول للحساب أولاً";
        if (m.contains("invalid_amount")) return "المبلغ غير صحيح";
        if (m.contains("invalid_period")) return "مدة الاشتراك غير صحيحة";
        if (m.contains("Network") || m.contains("Unable") || m.contains("timed out") || m.contains("HTTP 0") || m.contains("Failed to connect")) return "الشبكة غير متاحة الآن. حاولي الإرسال لما ترجع.";
        return "تعذر إرسال الطلب. راجعي البيانات وحاولي مرة تانية.";
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items));
        s.setPadding(dp(10), dp(7), dp(10), dp(7));
        s.setMinHeight(dp(52));
        s.setBackground(ClinicUi.stroke(this, ClinicUi.SURFACE, ClinicUi.LINE, 14));
        return s;
    }

    private String planLabel(String p) {
        if ("plus".equalsIgnoreCase(p)) return "Plus";
        if ("annual".equalsIgnoreCase(p)) return "Annual";
        if ("basic".equalsIgnoreCase(p)) return "Basic";
        return "تجربة";
    }

    private String statusLabel(String s) {
        if ("active".equals(s)) return "نشط";
        if ("trialing".equals(s)) return "تجربة";
        if ("suspended".equals(s)) return "موقوف";
        if ("past_due".equals(s)) return "متأخر";
        if ("cancelled".equals(s)) return "ملغي";
        return s == null || s.isEmpty() ? "غير محدد" : s;
    }

    private String str(EditText e) { return e.getText() == null ? "" : e.getText().toString(); }
    private long longValue(EditText e) { try { return Long.parseLong(str(e).trim()); } catch (Exception x) { return 0; } }
    private int intValue(EditText e) { try { return Integer.parseInt(str(e).trim()); } catch (Exception x) { return 0; } }
    private int dp(int value) { return ClinicUi.dp(this, value); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
