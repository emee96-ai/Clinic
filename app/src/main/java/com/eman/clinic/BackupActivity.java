package com.eman.clinic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

    private TextView status;
    private char[] pendingBackupPassword;
    private Uri pendingRestoreUri;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        AuthStore auth = new AuthStore(this);
        if (auth.hasRemoteIdentity() && !"owner_doctor".equals(auth.memberRole())) {
            Toast.makeText(this,"النسخ الاحتياطي متاح لمالك العيادة فقط",Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(26),dp(20),dp(28));
        root.setBackgroundColor(ClinicUi.BG);
        scroll.addView(root);

        root.addView(ClinicUi.text(this,"النسخ الاحتياطي",27,ClinicUi.INK,true));
        TextView sub = ClinicUi.text(this,"احفظي نسخة مشفّرة من بيانات العيادة في مكان تختاريه، أو استرجعي نسخة سابقة على هذا الجهاز.",14,ClinicUi.MUTED,false);
        sub.setPadding(0,dp(5),0,dp(16)); root.addView(sub);

        LinearLayout safety = ClinicUi.card(this);
        safety.addView(ClinicUi.text(this,"النسخة محمية بكلمة مرور",17,ClinicUi.INK,true));
        safety.addView(ClinicUi.text(this,"الملف لا يحتوي كلمة مرور الحساب أو توكنات الدخول أو سر ربط الأجهزة. كلمة مرور النسخة نفسها لا يمكن استرجاعها إذا نُسيت.",13,ClinicUi.MUTED,false));
        root.addView(safety);

        TextView backup = ClinicUi.button(this,"إنشاء نسخة احتياطية مشفّرة",true);
        backup.setOnClickListener(v -> askBackupPassword());
        root.addView(backup);
        root.addView(ClinicUi.space(this,9));

        TextView restore = ClinicUi.button(this,"استرجاع نسخة احتياطية",false);
        restore.setOnClickListener(v -> chooseRestoreFile());
        root.addView(restore);
        root.addView(ClinicUi.space(this,9));

        TextView back = ClinicUi.softButton(this,"رجوع");
        back.setOnClickListener(v -> finish());
        root.addView(back);
        root.addView(ClinicUi.space(this,14));

        status = ClinicUi.status(this,"",false,false);
        status.setVisibility(View.GONE);
        root.addView(status);
        root.addView(ClinicUi.space(this,16));

        LinearLayout restoreNote = ClinicUi.card(this);
        restoreNote.addView(ClinicUi.text(this,"قبل الاسترجاع",16,ClinicUi.INK,true));
        restoreNote.addView(ClinicUi.text(this,"الاسترجاع يستبدل البيانات المحلية الموجودة على هذا الجهاز. بيانات Supabase لا تُحذف، وبعد الاسترجاع ترجع المزامنة تكمل تلقائياً.",13,ClinicUi.MUTED,false));
        root.addView(restoreNote);

        setContentView(scroll);
    }

    private void askBackupPassword() {
        LinearLayout box = dialogBox();
        EditText p1 = passwordField("كلمة مرور النسخة — 8 أحرف على الأقل");
        EditText p2 = passwordField("أعيدي كلمة المرور");
        box.addView(ClinicUi.labeled(this,"كلمة المرور",p1));
        box.addView(ClinicUi.labeled(this,"التأكيد",p2));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("حماية النسخة")
                .setView(box)
                .setNegativeButton("إلغاء",null)
                .setPositiveButton("اختيار مكان الحفظ",null)
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
            intent.putExtra(Intent.EXTRA_TITLE,backupFileName());
            startActivityForResult(intent,CREATE_BACKUP);
        }));
        dialog.show();
    }

    private void chooseRestoreFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent,OPEN_BACKUP);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
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
        showStatus("جاري إنشاء النسخة…",false,false);
        new Thread(() -> {
            try {
                String encrypted = new ClinicBackupManager(this).createEncryptedBackup(password);
                try (OutputStream out = getContentResolver().openOutputStream(uri,"w")) {
                    if (out == null) throw new IllegalStateException("cannot_open_backup_file");
                    out.write(encrypted.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                runOnUiThread(() -> showStatus("تم حفظ النسخة الاحتياطية بنجاح ✓",true,false));
            } catch (Exception e) {
                runOnUiThread(() -> showStatus("تعذر إنشاء النسخة: "+friendly(e),false,true));
            } finally { Arrays.fill(password,'\0'); }
        },"clinic-backup-write").start();
    }

    private void askRestorePassword() {
        if (pendingRestoreUri == null) return;
        EditText password = passwordField("كلمة مرور النسخة");
        LinearLayout box = dialogBox();
        box.addView(ClinicUi.text(this,"سيتم التحقق من سلامة الملف والعيادة قبل لمس البيانات المحلية.",13,ClinicUi.MUTED,false));
        box.addView(ClinicUi.space(this,8));
        box.addView(password);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("تأكيد الاسترجاع")
                .setView(box)
                .setNegativeButton("إلغاء",(d,w)->pendingRestoreUri=null)
                .setPositiveButton("استرجاع",null)
                .create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String p = password.getText().toString();
            if (p.length() < 8) { password.setError("أدخلي كلمة مرور النسخة"); return; }
            Uri uri = pendingRestoreUri;
            pendingRestoreUri = null;
            char[] chars = p.toCharArray();
            password.setText("");
            dialog.dismiss();
            restore(uri,chars);
        }));
        dialog.show();
    }

    private void restore(Uri uri, char[] password) {
        showStatus("جاري التحقق من النسخة واسترجاعها…",false,false);
        new Thread(() -> {
            try {
                String envelope = readLimited(uri);
                ClinicBackupManager.RestoreSummary s = new ClinicBackupManager(this).restoreEncryptedBackup(envelope,password);
                SyncCoordinator.kick(this);
                String message = "تم الاسترجاع ✓\nالمرضى: "+s.patients+" • الزيارات: "+s.visits+" • الدفعات: "+s.payments+
                        (s.pendingSync > 0?"\nبانتظار المزامنة: "+s.pendingSync:"");
                runOnUiThread(() -> showStatus(message,true,false));
            } catch (Exception e) {
                runOnUiThread(() -> showStatus("تعذر الاسترجاع: "+friendly(e),false,true));
            } finally { Arrays.fill(password,'\0'); }
        },"clinic-backup-restore").start();
    }

    private String readLimited(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("cannot_open_backup_file");
            byte[] buffer = new byte[8192];
            int total=0,n;
            while ((n=in.read(buffer))!=-1) {
                total += n;
                if (total > MAX_BACKUP_BYTES) throw new IllegalStateException("backup_too_large");
                out.write(buffer,0,n);
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
        if (m.contains("cannot_open_backup_file")) return "تعذر فتح الملف المختار";
        return "حدث خطأ أثناء العملية";
    }

    private void showStatus(String value, boolean good, boolean bad) {
        status.setText(value == null?"":value);
        status.setTextColor(bad?ClinicUi.ERROR:(good?ClinicUi.PRIMARY:ClinicUi.MUTED));
        int back = bad?Color.rgb(252,239,239):(good?ClinicUi.SOFT:Color.rgb(240,243,244));
        status.setBackground(ClinicUi.round(this,back,13));
        status.setVisibility(value == null || value.isEmpty()?View.GONE:View.VISIBLE);
    }

    private EditText passwordField(String hint) {
        EditText e = ClinicUi.field(this,hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private LinearLayout dialogBox() {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(18),dp(8),dp(18),0);
        return b;
    }

    private String backupFileName() {
        String when = new SimpleDateFormat("yyyyMMdd-HHmm",Locale.US).format(new Date());
        return "clinic-backup-"+when+".clinicbak";
    }

    private void clearPendingPassword() {
        if (pendingBackupPassword != null) Arrays.fill(pendingBackupPassword,'\0');
        pendingBackupPassword = null;
    }

    @Override protected void onDestroy() {
        clearPendingPassword();
        super.onDestroy();
    }

    private int dp(int v){ return ClinicUi.dp(this,v); }
}
