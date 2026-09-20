package com.eman.clinic;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
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
        root.setPadding(dp(22), dp(34), dp(22), dp(30));
        root.setBackgroundColor(Color.rgb(245, 247, 248));
        scroll.addView(root);

        root.addView(text("إرسال بيانات الدفع", 25, ink, true));
        TextView sub = text("بعد التحويل، ارسلي بيانات العملية. الطلب حيظهر مباشرة في لوحة إدارة الاشتراكات للمراجعة.", 14, muted, false);
        sub.setPadding(0, dp(7), 0, dp(20));
        root.addView(sub);

        amount = field("المبلغ بالجنيه السوداني");
        amount.setInputType(InputType.TYPE_CLASS_NUMBER);
        method = spinner(new String[]{"بنكك", "فوري", "O-Cash", "كاش", "أخرى"});
        reference = field("رقم العملية / المرجع");
        plan = spinner(new String[]{"Basic", "Plus", "Annual"});
        days = field("مدة الاشتراك المطلوبة بالأيام");
        days.setInputType(InputType.TYPE_CLASS_NUMBER);
        days.setText("30");
        notes = field("ملاحظات اختيارية");
        notes.setSingleLine(false);
        notes.setMinLines(2);

        root.addView(labeled("المبلغ", amount));
        root.addView(labeled("وسيلة الدفع", method));
        root.addView(labeled("المرجع", reference));
        root.addView(labeled("الباقة", plan));
        root.addView(labeled("المدة", days));
        root.addView(labeled("ملاحظات", notes));

        plan.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == 2 && (days.getText().toString().isEmpty() || "30".equals(days.getText().toString()))) days.setText("365");
                else if (position != 2 && "365".equals(days.getText().toString())) days.setText("30");
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        status = text("", 13, muted, false);
        root.addView(status);
        submit = button("إرسال طلب المراجعة", true);
        submit.setOnClickListener(v -> submit());
        root.addView(submit);

        TextView back = button("رجوع", false);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.setMargins(0, dp(9), 0, 0);
        back.setLayoutParams(bp);
        back.setOnClickListener(v -> finish());
        root.addView(back);

        setContentView(scroll);
    }

    private void submit() {
        long value = longValue(amount);
        int period = intValue(days);
        String ref = reference.getText().toString().trim();
        if (value <= 0) { toast("اكتبي المبلغ"); return; }
        if (ref.length() < 2) { toast("اكتبي رقم العملية أو المرجع"); return; }
        if (period < 1 || period > 730) { toast("مدة الاشتراك غير صحيحة"); return; }
        String[] plans = {"basic", "plus", "annual"};
        String selectedPlan = plans[plan.getSelectedItemPosition()];
        busy(true, "جاري إرسال الطلب…");
        executor.execute(() -> {
            try {
                new BillingApi(this).submitPayment(
                        value,
                        String.valueOf(method.getSelectedItem()),
                        ref,
                        selectedPlan,
                        period,
                        notes.getText().toString()
                );
                runOnUiThread(() -> {
                    busy(false, "تم إرسال بيانات الدفع للمراجعة ✓");
                    submit.setEnabled(false);
                });
            } catch (Exception e) {
                runOnUiThread(() -> busy(false, friendly(e)));
            }
        });
    }

    private void busy(boolean value, String message) {
        submit.setEnabled(!value);
        submit.setAlpha(value ? 0.55f : 1f);
        status.setText(message);
    }

    private String friendly(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return "تعذر إرسال الطلب";
        if (m.contains("Network") || m.contains("Unable") || m.contains("timed out") || m.contains("HTTP 0")) return "الشبكة غير متاحة الآن";
        return m;
    }

    private LinearLayout labeled(String label, View field) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(label, 13, ink, true);
        l.setPadding(0, 0, 0, dp(5));
        box.addView(l);
        box.addView(field);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, dp(12));
        box.setLayoutParams(p);
        return box;
    }

    private EditText field(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(15);
        e.setTextColor(ink);
        e.setHintTextColor(muted);
        e.setPadding(dp(13), dp(11), dp(13), dp(11));
        e.setBackgroundColor(Color.WHITE);
        return e;
    }

    private Spinner spinner(String[] items) {
        Spinner s = new Spinner(this);
        s.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, items));
        s.setBackgroundColor(Color.WHITE);
        s.setPadding(dp(7), dp(6), dp(7), dp(6));
        return s;
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

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
    private long longValue(EditText e) { try { return Long.parseLong(e.getText().toString().trim()); } catch (Exception x) { return 0; } }
    private int intValue(EditText e) { try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return 0; } }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
