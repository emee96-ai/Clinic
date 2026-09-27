package com.eman.clinic;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Human-readable queue, failure and conflict state for weak-network clinics. */
public final class SyncIssuesActivity extends Activity {
    private LinearLayout content;
    private SyncStore store;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        store = new SyncStore(this);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(26), dp(20), dp(28));
        root.setBackgroundColor(ClinicUi.BG);
        scroll.addView(root);

        root.addView(ClinicUi.text(this, "حالة المزامنة", 27, ClinicUi.INK, true));
        root.addView(ClinicUi.text(this,
                "الحفظ على الجهاز يتم فوراً. العناصر هنا ستُرسل تلقائياً عند رجوع الشبكة.",
                14, ClinicUi.MUTED, false));
        root.addView(ClinicUi.space(this, 12));

        TextView retry = ClinicUi.button(this, "إعادة محاولة الكل الآن", true);
        retry.setOnClickListener(v -> {
            store.retryAll();
            SyncCoordinator.kick(this);
            toast("بدأت إعادة المحاولة عند توفر الإنترنت");
            render();
        });
        root.addView(retry);
        root.addView(ClinicUi.space(this, 12));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content);

        TextView back = ClinicUi.button(this, "رجوع", false);
        back.setOnClickListener(v -> finish());
        root.addView(back);
        setContentView(scroll);
        render();
    }

    private void render() {
        content.removeAllViews();
        List<SyncStore.SyncIssue> issues = store.issues();
        String last = store.meta("last_cloud_sync_at");
        LinearLayout summary = ClinicUi.card(this);
        summary.addView(ClinicUi.text(this,
                issues.isEmpty() ? "كل البيانات متزامنة ✓" : "عناصر تحتاج متابعة: " + issues.size(),
                17, issues.isEmpty() ? ClinicUi.PRIMARY : ClinicUi.ERROR, true));
        summary.addView(ClinicUi.text(this,
                last.isEmpty() ? "لم تكتمل مزامنة سحابية بعد" : "آخر مزامنة ناجحة: " + last,
                12, ClinicUi.MUTED, false));
        content.addView(summary);

        for (SyncStore.SyncIssue issue : issues) {
            LinearLayout card = ClinicUi.card(this);
            card.addView(ClinicUi.text(this, label(issue.entityType) + " • " + status(issue),
                    16, issue.conflict || "failed".equals(issue.status) ? ClinicUi.ERROR : ClinicUi.INK, true));
            if (!issue.message.isEmpty())
                card.addView(ClinicUi.text(this, friendly(issue.message), 13, ClinicUi.MUTED, false));
            card.addView(ClinicUi.text(this, issue.at, 11, ClinicUi.MUTED, false));
            if (issue.conflict) {
                TextView reviewed = ClinicUi.softButton(this, "راجعت التعارض — إخفاء التنبيه");
                reviewed.setOnClickListener(v -> { store.acknowledgeConflict(issue.id); render(); });
                card.addView(reviewed);
            }
            content.addView(card);
        }
        content.addView(ClinicUi.space(this, 8));
    }

    private String status(SyncStore.SyncIssue issue) {
        if (issue.conflict) return "تعارض محفوظ";
        if ("failed".equals(issue.status)) return "فشلت مؤقتاً • محاولة " + issue.attempts;
        if ("syncing".equals(issue.status)) return "جاري الإرسال";
        return "في الانتظار";
    }

    private String label(String type) {
        if ("patient".equals(type)) return "مريض";
        if ("visit".equals(type)) return "زيارة / كشف";
        if ("payment".equals(type)) return "دفعة";
        if ("day_closure".equals(type)) return "إغلاق اليوم";
        return "سجل";
    }

    private String friendly(String error) {
        if (error.contains("Unable") || error.contains("timed out") || error.contains("Network"))
            return "الشبكة غير متاحة؛ ستتم المحاولة تلقائياً";
        if (error.contains("permission") || error.contains("not_allowed"))
            return "الصلاحية تغيّرت؛ راجعي الدكتور المالك";
        return error;
    }

    private int dp(int value) { return ClinicUi.dp(this, value); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
}
