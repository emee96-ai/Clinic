package com.eman.clinic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/** Owner-only encrypted local backup and restore UI. */
public final class BackupActivity extends Activity {
    private static final int CREATE_BACKUP = 8101;
    private static final int OPEN_BACKUP = 8102;
    private static final int MAX_BACKUP_BYTES = 64 * 1024 * 1024;

    private final int primary = Color.rgb(14, 113, 105);
    private final int ink = Color.rgb(24, 35, 39);
    private final int muted = Color.rgb(103, 116, 121);
    private TextView status;
    private char[] pendingBackupPassword;
    private Uri pendingRestoreUri;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        AuthStore auth = new AuthStore(this);
        if (auth.hasRemoteIdentity() && !"owner_doctor".equals(auth.memberRole())) {
            Toast.makeText(this, "النسخ الاحتياطي متاح لمالك العيادة فقط", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        render();
    }

    private void render() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22), dp(24), dp(22), dp(24));
        root.setBackgroundColor(Color.rgb(245, 247, 248));

        root.addView(text("النسخ الاحتياطي والاسترجاع", 26, ink, true));
        TextView sub = text("انسخي بيانات العيادة في ملف مشفّر بكلمة مرور، أو استرجعي نسخة سابقة على هذا الجهاز.", 14, muted, false);
        sub.setPadding(0, dp(6), 0, dp(20));
        root.addView(sub);

        LinearLayout warning = card();
        warning.addView(text("مهم", 17, ink, true));
        warning.addView(text("كلمة مرور النسخة لا يمكن استرجاعها إذا نُسيت. الملف لا يحتوي كلمات مرور الحساب أو توكنات الدخول أو أسرار ربط الأجهزة.", 14, muted, false));
        root.addView(warning);

        TextView backup = button("إنشاء نسخة احتياطية مشفّرة");
        backup.setOnClickListener(v -> askBackupPassword());
        root.addView(backup);

        TextView restore = secondaryButton("استرجاع نسخة احتياطية");
        restore.setOnClickListener(v -> chooseRestoreFile());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(52));
        rp.setMargins(0, dp(10), 0, 0);
        restore.setLayoutParams(rp);
        root.addView(restore);

        status = text("", 14, muted, false);
        status.setPadding(0, dp(18), 0, 0);
        root.addView(status);

        setContentView(root);
    }

    private void askBackupPassword() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), 0, dp(20), 0);
        EditText p1 = passwordField("كلمة مرور النسخة — 8 أحرف على الأقل");
        EditText p2 = passwordField("أعيدي كلمة المرور");
        box.addView(p1);
        box.addView(p2);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("حماية النسخة")
                .setView(box)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("اختيار مكان الحفظ", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String one = p1.getText().toString();
            String two = p2.getText().toString();
            if (one.length() < 8) { p1.setError("8 أحرف على الأقل"); return; }
            if (!one.equals(two)) { p2.setError("كلمتا المرور غير متطابقتين"); return; }
            clearPendingPassword();
            pendingBackupPassword = one.toCharArray();
            p1.setText(""); p2.setText("");
            dialog.dismiss();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/octet-stream");
            intent.putExtra(Intent.EXTRA_TITLE, backupFileName());
            startActivityForResult(intent, CREATE_BACKUP);
        }));
        dialog.show();
    }

    private void chooseRestoreFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, OPEN_BACKUP);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            if (requestCode == CREATE_BACKUP) clearPendingPassword();
            return;
        }
        if (requestCode == CREATE_BACKUP) writeBackup(data.getData());
        else if (requestCode == OPEN_BACKUP) {
            pendingRestoreUri = data.getData();
            askRestorePassword();
        }
    }

    private void writeBackup(Uri uri) {
        final char[] password = pendingBackupPassword;
        pendingBackupPassword = null;
        if (password == null) return;
        setStatus("جاري إنشاء النسخة…");
        new Thread(() -> {
            try {
                String encrypted = new ClinicBackupManager(this).createEncryptedBackup(password);
                try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                    if (out == null) throw new IllegalStateException("cannot_open_backup_file");
                    out.write(encrypted.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                runOnUiThread(() -> setStatus("تم حفظ النسخة الاحتياطية بنجاح ✓"));
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("تعذر إنشاء النسخة: " + friendly(e)));
            } finally {
                Arrays.fill(password, '\0');
            }
        }, "clinic-backup-write").start();
    }

    private void askRestorePassword() {
        if (pendingRestoreUri == null) return;
        EditText password = passwordField("كلمة مرور النسخة");
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), 0, dp(20), 0);
        box.addView(text("الاسترجاع سيستبدل البيانات المحلية الموجودة على هذا الجهاز. بيانات Supabase لا تُحذف.", 14, muted, false));
        box.addView(password);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("تأكيد الاسترجاع")
                .setView(box)
                .setNegativeButton("إلغاء", (d, w) -> pendingRestoreUri = null)
                .setPositiveButton("استرجاع", null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String p = password.getText().toString();
            if (p.length() < 8) { password.setError("أدخلي كلمة مرور النسخة"); return; }
            Uri uri = pendingRestoreUri;
            pendingRestoreUri = null;
            char[] chars = p.toCharArray();
            password.setText("");
            dialog.dismiss();
            restore(uri, chars);
        }));
        dialog.show();
    }

    private void restore(Uri uri, char[] password) {
        setStatus("جاري التحقق من النسخة واسترجاعها…");
        new Thread(() -> {
            try {
                String envelope = readLimited(uri);
                ClinicBackupManager.RestoreSummary s = new ClinicBackupManager(this).restoreEncryptedBackup(envelope, password);
                SyncCoordinator.kick(this);
                String message = "تم الاسترجاع ✓  المرضى: " + s.patients + " • الزيارات: " + s.visits +
                        " • الدفعات: " + s.payments + (s.pendingSync > 0 ? " • بانتظار المزامنة: " + s.pendingSync : "");
                runOnUiThread(() -> setStatus(message));
            } catch (Exception e) {
                runOnUiThread(() -> setStatus("تعذر الاسترجاع: " + friendly(e)));
            } finally {
                Arrays.fill(password, '\0');
            }
        }, "clinic-backup-restore").start();
    }

    private String readLimited(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("cannot_open_backup_file");
            byte[] buffer = new byte[8192];
            int total = 0, n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > MAX_BACKUP_BYTES) throw new IllegalStateException("backup_too_large");
                out.write(buffer, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String friendly(Exception e) {
        String m = String.valueOf(e.getMessage());
        if (m.contains("wrong_password_or_corrupt_backup")) return "كلمة المرور غير صحيحة أو الملف تالف";
        if (m.contains("backup_belongs_to_another_clinic")) return "النسخة تخص عيادة أخرى";
        if (m.contains("unsupported_backup_format") || m.contains("invalid_backup")) return "الملف ليس نسخة Clinic صالحة";
        if (m.contains("backup_too_large")) return "حجم ملف النسخة أكبر من الحد المسموح";
        return "حدث خطأ أثناء العملية";
    }

    private void setStatus(String value) { if (status != null) status.setText(value == null ? "" : value); }

    private EditText passwordField(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setPadding(dp(12), dp(11), dp(12), dp(11));
        return e;
    }

    private TextView button(String value) {
        TextView t = text(value, 15, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(primary);
        t.setMinHeight(dp(52));
        return t;
    }

    private TextView secondaryButton(String value) {
        TextView t = text(value, 15, primary, true);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundColor(Color.rgb(232, 246, 244));
        t.setMinHeight(dp(52));
        return t;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        c.setBackgroundColor(Color.WHITE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.setMargins(0, 0, 0, dp(18));
        c.setLayoutParams(p);
        return c;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return t;
    }

    private String backupFileName() {
        String when = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        return "clinic-backup-" + when + ".clinicbak";
    }

    private void clearPendingPassword() {
        if (pendingBackupPassword != null) Arrays.fill(pendingBackupPassword, '\0');
        pendingBackupPassword = null;
    }

    @Override protected void onDestroy() {
        clearPendingPassword();
        super.onDestroy();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}