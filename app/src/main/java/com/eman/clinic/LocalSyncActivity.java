package com.eman.clinic;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class LocalSyncActivity extends Activity {
    private EditText pairCode;
    private TextView status;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override public void run() {
            if (status != null) updateStatus(LocalSyncManager.status(LocalSyncActivity.this));
            handler.postDelayed(this, 1800);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
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
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(26), dp(20), dp(28));
        root.setBackgroundColor(ClinicUi.BG);
        scroll.addView(root);

        root.addView(ClinicUi.text(this,"ربط أجهزة العيادة",27,ClinicUi.INK,true));
        TextView sub = ClinicUi.text(this,"خلي الأجهزة على نفس Wi‑Fi أو Hotspot. بعد الربط، بيانات الطابور والكشف تتبادل محلياً حتى لو الإنترنت مقطوع.",14,ClinicUi.MUTED,false);
        sub.setPadding(0,dp(5),0,dp(16)); root.addView(sub);

        AuthStore auth = new AuthStore(this);
        LinearLayout info = ClinicUi.card(this);
        info.addView(ClinicUi.text(this,auth.clinicName().isEmpty()?"العيادة":auth.clinicName(),18,ClinicUi.INK,true));
        info.addView(ClinicUi.text(this,"الحساب: "+roleLabel(auth.memberRole()),13,ClinicUi.MUTED,false));
        root.addView(info);

        status = ClinicUi.status(this,LocalSyncManager.status(this),false,false);
        root.addView(status);
        root.addView(ClinicUi.space(this,12));

        LinearLayout codeCard = ClinicUi.card(this);
        codeCard.addView(ClinicUi.text(this,"رمز الربط المحلي",16,ClinicUi.INK,true));
        codeCard.addView(ClinicUi.text(this,"نفس الرمز لازم يكون محفوظ في كل أجهزة العيادة. ما تشاركيه خارج فريق العيادة.",12,ClinicUi.MUTED,false));
        codeCard.addView(ClinicUi.space(this,8));
        pairCode = ClinicUi.field(this,"رمز الربط المحلي");
        pairCode.setText(LocalSyncManager.pairKey(this));
        pairCode.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        codeCard.addView(pairCode);
        codeCard.addView(ClinicUi.space(this,10));

        boolean owner = "owner_doctor".equals(auth.memberRole());
        if (owner) {
            TextView generate = ClinicUi.button(this,"إنشاء رمز جديد للعيادة",true);
            generate.setOnClickListener(v -> {
                String code = LocalSyncManager.generatePairKey();
                pairCode.setText(code);
                LocalSyncManager.savePairKey(this,code);
                copy(code);
                toast("تم إنشاء الرمز وحفظه ونسخه");
            });
            codeCard.addView(generate);
            codeCard.addView(ClinicUi.space(this,8));
        }

        TextView save = ClinicUi.softButton(this,"حفظ الرمز في هذا الجهاز");
        save.setOnClickListener(v -> saveCode());
        codeCard.addView(save);
        codeCard.addView(ClinicUi.space(this,8));

        LinearLayout codeActions = new LinearLayout(this);
        codeActions.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = ClinicUi.button(this,"نسخ",false); copy.setOnClickListener(v -> copy(str(pairCode).trim()));
        TextView share = ClinicUi.button(this,"مشاركة",false); share.setOnClickListener(v -> share(str(pairCode).trim()));
        codeActions.addView(copy,new LinearLayout.LayoutParams(0,dp(48),1));
        LinearLayout.LayoutParams shp = new LinearLayout.LayoutParams(0,dp(48),1); shp.setMargins(dp(8),0,0,0); codeActions.addView(share,shp);
        codeCard.addView(codeActions);
        root.addView(codeCard);

        TextView sync = ClinicUi.button(this,"مزامنة الآن",true);
        sync.setOnClickListener(v -> {
            if (!LocalSyncManager.configured(this)) { toast("احفظي رمز الربط أولاً"); return; }
            LocalSyncManager.kick(this);
            updateStatus("جاري البحث عن أجهزة العيادة القريبة…");
            toast("بدأ البحث عن الأجهزة");
        });
        root.addView(sync);
        root.addView(ClinicUi.space(this,10));

        TextView back = ClinicUi.button(this,"رجوع",false);
        back.setOnClickListener(v -> finish());
        root.addView(back);
        root.addView(ClinicUi.space(this,16));

        LinearLayout how = ClinicUi.card(this);
        how.addView(ClinicUi.text(this,"طريقة الاستخدام",17,ClinicUi.INK,true));
        how.addView(ClinicUi.text(this,"1) الدكتور المالك ينشئ رمز الربط مرة واحدة.\n2) الرمز نفسه يتحفظ في جهاز كل مسجلة أو دكتور بديل.\n3) الأجهزة تكون على نفس Wi‑Fi أو Hotspot.\n4) المزامنة بعد كده تلقائية ومشفّرة.",14,ClinicUi.MUTED,false));
        root.addView(how);

        setContentView(scroll);
    }

    private void saveCode() {
        String code = str(pairCode).trim();
        if (code.length() < 16) { pairCode.setError("الرمز قصير أو غير صحيح"); return; }
        LocalSyncManager.savePairKey(this,code);
        LocalSyncManager.kick(this);
        toast("تم حفظ الرمز وبدأت المزامنة");
    }

    private String roleLabel(String role) {
        if ("owner_doctor".equals(role)) return "الدكتور المالك";
        if ("substitute_doctor".equals(role)) return "دكتور بديل";
        if ("receptionist".equals(role)) return "مسجلة / استقبال";
        return role == null || role.isEmpty() ? "غير مربوط" : role;
    }

    private void updateStatus(String value) {
        if (status == null) return;
        String text = value == null ? "" : value;
        boolean error = text.contains("خطأ") || text.contains("تعذر") || text.contains("غير مضبوط");
        boolean good = text.contains("آخر مزامنة") || text.contains("متصل") || text.contains("جاهز");
        status.setText(text);
        status.setTextColor(error ? ClinicUi.ERROR : (good ? ClinicUi.PRIMARY : ClinicUi.MUTED));
        int back = error ? Color.rgb(252,239,239) : (good ? ClinicUi.SOFT : Color.rgb(240,243,244));
        status.setBackground(ClinicUi.round(this,back,13));
    }

    private void copy(String value) {
        if (value == null || value.isEmpty()) { toast("مافي رمز للنسخ"); return; }
        ClipboardManager clipboard = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Clinic local sync",value));
        toast("تم نسخ الرمز");
    }

    private void share(String value) {
        if (value == null || value.isEmpty()) { toast("مافي رمز للمشاركة"); return; }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT,"رمز ربط أجهزة العيادة:\n"+value+"\n\nاستخدميه داخل شاشة «ربط أجهزة العيادة» فقط.");
        startActivity(Intent.createChooser(i,"مشاركة رمز ربط العيادة"));
    }

    private String str(EditText e) { return e.getText()==null?"":e.getText().toString(); }
    private int dp(int value) { return ClinicUi.dp(this,value); }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_SHORT).show(); }
}
