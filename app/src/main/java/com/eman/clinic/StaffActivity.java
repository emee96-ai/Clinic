package com.eman.clinic;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
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
    private final int primary = Color.rgb(14,113,105), ink = Color.rgb(24,35,39), muted = Color.rgb(103,116,121);
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20),dp(28),dp(20),dp(28));
        root.setBackgroundColor(Color.rgb(245,247,248));
        scroll.addView(root);

        root.addView(text("فريق العيادة",27,ink,true));
        TextView sub = text(auth.clinicName()+"\nالدكتور المالك يحدد من يدخل وماذا يستطيع أن يفعل.",14,muted,false);
        sub.setPadding(0,dp(5),0,dp(16)); root.addView(sub);

        LinearLayout actions = new LinearLayout(this); actions.setOrientation(LinearLayout.HORIZONTAL);
        TextView reception = button("+ دعوة مسجلة",true); reception.setOnClickListener(v -> inviteDialog("receptionist"));
        TextView doctor = button("+ دكتور بديل",false); doctor.setOnClickListener(v -> inviteDialog("substitute_doctor"));
        actions.addView(reception,new LinearLayout.LayoutParams(0,dp(52),1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,dp(52),1); p.setMargins(dp(8),0,0,0); actions.addView(doctor,p);
        root.addView(actions);

        status = text("",13,muted,false); status.setPadding(0,dp(12),0,dp(8)); root.addView(status);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); root.addView(content);
        setContentView(scroll);
    }

    private void loadMembers() {
        status.setText("جاري تحميل الفريق…");
        executor.execute(() -> {
            try {
                JSONArray members = new SupabaseApi(this).listMembers();
                runOnUiThread(() -> renderMembers(members));
            } catch (Exception e) { runOnUiThread(() -> status.setText(friendly(e))); }
        });
    }

    private void renderMembers(JSONArray members) {
        content.removeAllViews();
        status.setText("أعضاء الفريق: "+members.length());
        for (int i=0;i<members.length();i++) {
            JSONObject m = members.optJSONObject(i); if (m==null) continue;
            LinearLayout card = card();
            String role = m.optString("role","");
            String name = m.optString("display_name","");
            if (name.isEmpty()) name = roleLabel(role);
            card.addView(text(name,18,ink,true));
            card.addView(text(roleLabel(role)+(m.optBoolean("active",true)?" • نشط":" • موقوف"),13,m.optBoolean("active",true)?primary:Color.rgb(160,60,60),true));
            if (!"owner_doctor".equals(role)) {
                TextView edit = button("الصلاحيات والتفعيل",false);
                final JSONObject member = m;
                edit.setOnClickListener(v -> editMemberDialog(member));
                LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT); ep.setMargins(0,dp(9),0,0); edit.setLayoutParams(ep);
                card.addView(edit);
            } else {
                card.addView(text("مالك العيادة — كل الصلاحيات",12,muted,false));
            }
            content.addView(card);
        }
    }

    private void inviteDialog(String role) {
        LinearLayout box = dialogBox();
        box.addView(text(role.equals("receptionist")?"صلاحيات المسجلة":"صلاحيات الدكتور البديل",16,ink,true));
        JSONObject defaults = defaultPermissions(role);
        LinkedHashMap<String,CheckBox> checks = permissionChecks(defaults);
        for (CheckBox cb:checks.values()) box.addView(cb);
        EditText uses = field("عدد الأشخاص الممكن يستخدموا الرمز"); uses.setInputType(InputType.TYPE_CLASS_NUMBER); uses.setText("1");
        EditText hours = field("صلاحية الرمز بالساعات"); hours.setInputType(InputType.TYPE_CLASS_NUMBER); hours.setText("72");
        box.addView(uses); box.addView(space(7)); box.addView(hours);

        new AlertDialog.Builder(this).setTitle("إنشاء رمز دعوة")
                .setView(box).setNegativeButton("إلغاء",null)
                .setPositiveButton("إنشاء",(d,w)-> {
                    int maxUses = intVal(uses,1), expiry = intVal(hours,72);
                    JSONObject permissions = permissionsFrom(checks);
                    createInvite(role,permissions,expiry,maxUses);
                }).show();
    }

    private void createInvite(String role, JSONObject permissions, int hours, int maxUses) {
        status.setText("جاري إنشاء الرمز…");
        executor.execute(() -> {
            try {
                JSONObject result = new SupabaseApi(this).generateInvite(role,permissions,hours,maxUses);
                String code = result.optString("code","");
                runOnUiThread(() -> showInviteCode(code,role,maxUses));
            } catch (Exception e) { runOnUiThread(() -> status.setText(friendly(e))); }
        });
    }

    private void showInviteCode(String code, String role, int maxUses) {
        status.setText("تم إنشاء رمز الدعوة ✓");
        String message = "رمز الدعوة: "+code+"\nالدور: "+roleLabel(role)+"\nعدد الاستخدامات: "+maxUses+"\n\nالشخص يعمل حسابه في تطبيق العيادة ويكتب الرمز عند الربط.";
        new AlertDialog.Builder(this).setTitle("رمز الدعوة")
                .setMessage(message)
                .setNegativeButton("إغلاق",null)
                .setPositiveButton("مشاركة",(d,w)-> {
                    Intent share = new Intent(Intent.ACTION_SEND); share.setType("text/plain"); share.putExtra(Intent.EXTRA_TEXT,message);
                    startActivity(Intent.createChooser(share,"مشاركة رمز الدعوة"));
                }).show();
    }

    private void editMemberDialog(JSONObject member) {
        JSONObject current = member.optJSONObject("permissions"); if (current==null) current = new JSONObject();
        LinearLayout box = dialogBox();
        CheckBox active = new CheckBox(this); active.setText("الحساب نشط"); active.setChecked(member.optBoolean("active",true)); box.addView(active);
        LinkedHashMap<String,CheckBox> checks = permissionChecks(current);
        for (CheckBox cb:checks.values()) box.addView(cb);
        new AlertDialog.Builder(this).setTitle(member.optString("display_name",roleLabel(member.optString("role",""))))
                .setView(box).setNegativeButton("إلغاء",null)
                .setPositiveButton("حفظ",(d,w)-> saveMember(member.optString("id",""),active.isChecked(),permissionsFrom(checks))).show();
    }

    private void saveMember(String id, boolean active, JSONObject permissions) {
        status.setText("جاري حفظ الصلاحيات…");
        executor.execute(() -> {
            try {
                new SupabaseApi(this).updateMemberAccess(id,active,permissions);
                runOnUiThread(() -> { Toast.makeText(this,"تم حفظ الصلاحيات",Toast.LENGTH_SHORT).show(); loadMembers(); });
            } catch (Exception e) { runOnUiThread(() -> status.setText(friendly(e))); }
        });
    }

    private LinkedHashMap<String,CheckBox> permissionChecks(JSONObject values) {
        LinkedHashMap<String,CheckBox> out = new LinkedHashMap<>();
        for (Map.Entry<String,String> e:PERMISSIONS.entrySet()) {
            CheckBox cb = new CheckBox(this); cb.setText(e.getValue()); cb.setTextColor(ink); cb.setTextSize(14); cb.setChecked(values.optBoolean(e.getKey(),false));
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
        String m=e.getMessage(); if (m==null||m.isEmpty()) return "حدث خطأ";
        if (m.contains("not_allowed")) return "ما عندك صلاحية للعملية دي";
        return m;
    }

    private LinearLayout dialogBox() { LinearLayout b=new LinearLayout(this); b.setOrientation(LinearLayout.VERTICAL); b.setPadding(dp(18),dp(8),dp(18),0); return b; }
    private LinearLayout card() { LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(15),dp(14),dp(15),dp(14)); c.setBackgroundColor(Color.WHITE); LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT); p.setMargins(0,0,0,dp(10)); c.setLayoutParams(p); return c; }
    private EditText field(String hint) { EditText e=new EditText(this); e.setHint(hint); e.setSingleLine(true); e.setTextSize(14); e.setTextColor(ink); e.setHintTextColor(muted); e.setBackgroundColor(Color.WHITE); e.setPadding(dp(12),dp(10),dp(12),dp(10)); return e; }
    private TextView button(String label,boolean solid) { TextView t=text(label,14,solid?Color.WHITE:primary,true); t.setGravity(Gravity.CENTER); t.setPadding(dp(10),dp(12),dp(10),dp(12)); t.setBackgroundColor(solid?primary:Color.WHITE); return t; }
    private TextView text(String s,int sp,int color,boolean bold) { TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color); if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD); return t; }
    private View space(int h) { View v=new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(1,dp(h))); return v; }
    private int intVal(EditText e,int d){ try{return Integer.parseInt(e.getText().toString().trim());}catch(Exception x){return d;} }
    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }
}
