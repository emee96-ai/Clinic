package com.eman.clinic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owner-doctor screen for receptionist/substitute-doctor invitations and permissions. */
public class StaffActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private TextView status;
    private AuthStore auth;

    private static final LinkedHashMap<String,String> PERMISSIONS = new LinkedHashMap<>();
    static {
        PERMISSIONS.put("view_patients","عرض المرضى");
        PERMISSIONS.put("edit_patients","إضافة وتعديل بيانات المرضى");
        PERMISSIONS.put("register_visits","تسجيل زيارات جديدة");
        PERMISSIONS.put("manage_queue","إدارة الطابور وحالة الزيارة");
        PERMISSIONS.put("view_clinical","مشاهدة السجل السريري");
        PERMISSIONS.put("edit_clinical","كتابة التشخيص والعلاج والفحوصات");
        PERMISSIONS.put("view_finance","مشاهدة الحسابات");
        PERMISSIONS.put("record_payments","تسجيل المدفوعات");
        PERMISSIONS.put("close_day","إغلاق حساب اليوم");
        PERMISSIONS.put("manage_staff","إدارة أعضاء الفريق");
        PERMISSIONS.put("manage_invites","إنشاء رموز الدعوة");
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        getWindow().setStatusBarColor(Color.WHITE);
        getWindow().setNavigationBarColor(Color.WHITE);
        auth = new AuthStore(this);
        if (!auth.can("manage_staff")) {
            Toast.makeText(this,"ما عندك صلاحية إدارة الفريق",Toast.LENGTH_LONG).show();
            finish(); return;
        }
        buildUi();
        loadMembers();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(26),dp(20),dp(28));
        root.setBackgroundColor(ClinicUi.BG);
        scroll.addView(root);

        root.addView(ClinicUi.text(this,"فريق العيادة",27,ClinicUi.INK,true));
        TextView sub = ClinicUi.text(this,(auth.clinicName().isEmpty()?"العيادة":auth.clinicName())+"\nالدكتور المالك يحدد صلاحيات كل عضو ويمكنه إيقاف الحساب فوراً.",14,ClinicUi.MUTED,false);
        sub.setPadding(0,dp(5),0,dp(16)); root.addView(sub);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView reception = ClinicUi.button(this,"+ دعوة مسجلة",true);
        reception.setOnClickListener(v -> inviteDialog("receptionist"));
        TextView doctor = ClinicUi.button(this,"+ دكتور بديل",false);
        doctor.setOnClickListener(v -> inviteDialog("substitute_doctor"));
        actions.addView(reception,new LinearLayout.LayoutParams(0,dp(52),1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,dp(52),1); p.setMargins(dp(8),0,0,0); actions.addView(doctor,p);
        root.addView(actions);
        root.addView(ClinicUi.space(this,10));

        TextView refresh = ClinicUi.softButton(this,"تحديث قائمة الفريق");
        refresh.setOnClickListener(v -> loadMembers());
        root.addView(refresh);
        root.addView(ClinicUi.space(this,10));

        status = ClinicUi.status(this,"",false,false);
        status.setVisibility(View.GONE);
        root.addView(status);
        root.addView(ClinicUi.space(this,10));

        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); root.addView(content);
        setContentView(scroll);
    }

    private void loadMembers() {
        showStatus("جاري تحميل الفريق…",false,false);
        executor.execute(() -> {
            try {
                JSONArray members = new SupabaseApi(this).listMembers();
                runOnUiThread(() -> renderMembers(members));
            } catch (Exception e) { runOnUiThread(() -> showStatus(friendly(e),false,true)); }
        });
    }

    private void renderMembers(JSONArray members) {
        content.removeAllViews();
        showStatus("أعضاء الفريق: "+members.length(),true,false);
        for (int i=0;i<members.length();i++) {
            JSONObject m = members.optJSONObject(i); if (m==null) continue;
            LinearLayout card = ClinicUi.card(this);
            String role = m.optString("role","");
            String name = m.optString("display_name","");
            if (name.isEmpty()) name = roleLabel(role);
            card.addView(ClinicUi.text(this,name,18,ClinicUi.INK,true));
            boolean active = m.optBoolean("active",true);
            card.addView(ClinicUi.text(this,roleLabel(role)+(active?" • نشط":" • موقوف"),13,active?ClinicUi.PRIMARY:ClinicUi.ERROR,true));
            if (!"owner_doctor".equals(role)) {
                TextView edit = ClinicUi.softButton(this,"الصلاحيات والتفعيل");
                final JSONObject member = m;
                edit.setOnClickListener(v -> editMemberDialog(member));
                LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT); ep.setMargins(0,dp(9),0,0); edit.setLayoutParams(ep);
                card.addView(edit);
            } else {
                card.addView(ClinicUi.text(this,"مالك العيادة — كل الصلاحيات",12,ClinicUi.MUTED,false));
            }
            content.addView(card);
        }
    }

    private void inviteDialog(String role) {
        LinearLayout box = dialogBox();
        box.addView(ClinicUi.text(this,role.equals("receptionist")?"صلاحيات المسجلة":"صلاحيات الدكتور البديل",16,ClinicUi.INK,true));
        box.addView(ClinicUi.text(this,"راجعي الصلاحيات قبل إنشاء الرمز. يمكن تعديلها لاحقاً لكل عضو.",12,ClinicUi.MUTED,false));
        box.addView(ClinicUi.space(this,6));
        JSONObject defaults = defaultPermissions(role);
        LinkedHashMap<String,CheckBox> checks = permissionChecks(defaults);
        for (CheckBox cb:checks.values()) box.addView(cb);
        EditText uses = ClinicUi.field(this,"عدد استخدامات الرمز"); uses.setInputType(InputType.TYPE_CLASS_NUMBER); uses.setText("1");
        EditText hours = ClinicUi.field(this,"صلاحية الرمز بالساعات"); hours.setInputType(InputType.TYPE_CLASS_NUMBER); hours.setText("72");
        box.addView(ClinicUi.space(this,8)); box.addView(ClinicUi.labeled(this,"عدد الاستخدامات (1–20)",uses));
        box.addView(ClinicUi.labeled(this,"الصلاحية بالساعات (1–168)",hours));

        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("إنشاء رمز دعوة")
                .setView(box).setNegativeButton("إلغاء",null).setPositiveButton("إنشاء",null).create();
        dialog.setOnShowListener(x -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int maxUses = intVal(uses,1), expiry = intVal(hours,72);
            if (maxUses < 1 || maxUses > 20) { uses.setError("من 1 إلى 20"); return; }
            if (expiry < 1 || expiry > 168) { hours.setError("من ساعة إلى 168 ساعة"); return; }
            JSONObject permissions = permissionsFrom(checks);
            dialog.dismiss();
            createInvite(role,permissions,expiry,maxUses);
        }));
        dialog.show();
    }

    private void createInvite(String role, JSONObject permissions, int hours, int maxUses) {
        showStatus("جاري إنشاء رمز الدعوة…",false,false);
        executor.execute(() -> {
            try {
                JSONObject result = new SupabaseApi(this).generateInvite(role,permissions,hours,maxUses);
                String code = result.optString("code","");
                runOnUiThread(() -> showInviteCode(code,role,maxUses));
            } catch (Exception e) { runOnUiThread(() -> showStatus(friendly(e),false,true)); }
        });
    }

    private void showInviteCode(String code, String role, int maxUses) {
        showStatus("تم إنشاء رمز الدعوة ✓",true,false);
        String message = "رمز الدعوة: "+code+"\nالدور: "+roleLabel(role)+"\nعدد الاستخدامات: "+maxUses+"\n\nالشخص يفتح تطبيق العيادة، يختار «رمز دعوة»، ويكتب الرمز عند الربط.";
        new AlertDialog.Builder(this).setTitle("رمز الدعوة")
                .setMessage(message)
                .setNegativeButton("إغلاق",null)
                .setNeutralButton("نسخ الرمز",(d,w)->copy(code))
                .setPositiveButton("مشاركة",(d,w)-> {
                    Intent share = new Intent(Intent.ACTION_SEND); share.setType("text/plain"); share.putExtra(Intent.EXTRA_TEXT,message);
                    startActivity(Intent.createChooser(share,"مشاركة رمز الدعوة"));
                }).show();
    }

    private void editMemberDialog(JSONObject member) {
        JSONObject current = member.optJSONObject("permissions"); if (current==null) current = new JSONObject();
        LinearLayout box = dialogBox();
        CheckBox active = new CheckBox(this); active.setText("الحساب نشط"); active.setTextColor(ClinicUi.INK); active.setChecked(member.optBoolean("active",true)); box.addView(active);
        LinkedHashMap<String,CheckBox> checks = permissionChecks(current);
        for (CheckBox cb:checks.values()) box.addView(cb);
        new AlertDialog.Builder(this).setTitle(member.optString("display_name",roleLabel(member.optString("role",""))))
                .setView(box).setNegativeButton("إلغاء",null)
                .setPositiveButton("حفظ",(d,w)-> saveMember(member.optString("id",""),active.isChecked(),permissionsFrom(checks))).show();
    }

    private void saveMember(String id, boolean active, JSONObject permissions) {
        showStatus("جاري حفظ الصلاحيات…",false,false);
        executor.execute(() -> {
            try {
                new SupabaseApi(this).updateMemberAccess(id,active,permissions);
                runOnUiThread(() -> { Toast.makeText(this,"تم حفظ الصلاحيات",Toast.LENGTH_SHORT).show(); loadMembers(); });
            } catch (Exception e) { runOnUiThread(() -> showStatus(friendly(e),false,true)); }
        });
    }

    private LinkedHashMap<String,CheckBox> permissionChecks(JSONObject values) {
        LinkedHashMap<String,CheckBox> out = new LinkedHashMap<>();
        for (Map.Entry<String,String> e:PERMISSIONS.entrySet()) {
            CheckBox cb = new CheckBox(this); cb.setText(e.getValue()); cb.setTextColor(ClinicUi.INK); cb.setTextSize(14); cb.setChecked(values.optBoolean(e.getKey(),false));
            out.put(e.getKey(),cb);
        }
        return out;
    }

    private JSONObject permissionsFrom(LinkedHashMap<String,CheckBox> checks) {
        JSONObject out = new JSONObject();
        for (Map.Entry<String,CheckBox> e:checks.entrySet()) try { out.put(e.getKey(),e.getValue().isChecked()); } catch (Exception ignored) {}
        return out;
    }

    private JSONObject defaultPermissions(String role) {
        JSONObject p = new JSONObject();
        try {
            if ("receptionist".equals(role)) {
                p.put("view_patients",true); p.put("edit_patients",true); p.put("register_visits",true); p.put("manage_queue",true);
                p.put("view_clinical",false); p.put("edit_clinical",false); p.put("view_finance",true); p.put("record_payments",true); p.put("close_day",true);
            } else {
                p.put("view_patients",true); p.put("edit_patients",false); p.put("register_visits",false); p.put("manage_queue",true);
                p.put("view_clinical",true); p.put("edit_clinical",true); p.put("view_finance",false); p.put("record_payments",false); p.put("close_day",false);
            }
            p.put("manage_staff",false); p.put("manage_invites",false);
        } catch (Exception ignored) {}
        return p;
    }

    private String roleLabel(String role) {
        if ("owner_doctor".equals(role)) return "الدكتور المالك";
        if ("substitute_doctor".equals(role)) return "دكتور بديل";
        if ("receptionist".equals(role)) return "مسجلة / استقبال";
        return role;
    }

    private String friendly(Exception e) {
        String m=String.valueOf(e.getMessage());
        if (m.contains("not_allowed") || m.contains("permission")) return "ما عندك صلاحية للعملية دي";
        if (m.contains("Network") || m.contains("Unable") || m.contains("timed out") || m.contains("Failed to connect")) return "الشبكة غير متاحة الآن";
        return "تعذر إكمال العملية. حاولي مرة تانية.";
    }

    private void showStatus(String message, boolean good, boolean bad) {
        status.setText(message == null ? "" : message);
        status.setTextColor(bad ? ClinicUi.ERROR : (good ? ClinicUi.PRIMARY : ClinicUi.MUTED));
        int back = bad ? Color.rgb(252,239,239) : (good ? ClinicUi.SOFT : Color.rgb(240,243,244));
        status.setBackground(ClinicUi.round(this,back,13));
        status.setVisibility(message == null || message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private LinearLayout dialogBox() { LinearLayout b=new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(dp(18),dp(8),dp(18),0); return b; }
    private void copy(String value) { ClipboardManager cb=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE); cb.setPrimaryClip(ClipData.newPlainText("Clinic invite",value)); Toast.makeText(this,"تم نسخ الرمز",Toast.LENGTH_SHORT).show(); }
    private int intVal(EditText e,int d){ try{return Integer.parseInt(e.getText().toString().trim());}catch(Exception x){return d;} }
    private int dp(int v){ return ClinicUi.dp(this,v); }

    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
