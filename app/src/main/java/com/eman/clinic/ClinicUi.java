package com.eman.clinic;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small dependency-free design system shared by the Clinic Android screens. */
public final class ClinicUi {
    public static final int PRIMARY = Color.rgb(14, 113, 105);
    public static final int PRIMARY_DARK = Color.rgb(8, 78, 73);
    public static final int BG = Color.rgb(245, 247, 248);
    public static final int SURFACE = Color.WHITE;
    public static final int INK = Color.rgb(24, 35, 39);
    public static final int MUTED = Color.rgb(103, 116, 121);
    public static final int LINE = Color.rgb(226, 231, 233);
    public static final int SOFT = Color.rgb(232, 246, 244);
    public static final int WARNING = Color.rgb(175, 105, 16);
    public static final int ERROR = Color.rgb(160, 55, 55);

    private ClinicUi() {}

    public static TextView text(Context c, String value, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(value == null ? "" : value);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        t.setLineSpacing(0, 1.14f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    public static EditText field(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setTextSize(15);
        e.setTextColor(INK);
        e.setHintTextColor(MUTED);
        e.setPadding(dp(c, 14), dp(c, 11), dp(c, 14), dp(c, 11));
        e.setMinHeight(dp(c, 52));
        e.setBackground(stroke(c, SURFACE, LINE, 14));
        return e;
    }

    public static LinearLayout labeled(Context c, String label, View field) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView l = text(c, label, 13, INK, true);
        l.setPadding(dp(c, 2), 0, dp(c, 2), dp(c, 5));
        box.addView(l);
        box.addView(field);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, dp(c, 12));
        box.setLayoutParams(p);
        return box;
    }

    public static LinearLayout card(Context c) {
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(c, 16), dp(c, 15), dp(c, 16), dp(c, 15));
        box.setBackground(round(c, SURFACE, 18));
        box.setElevation(dp(c, 1));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(0, 0, 0, dp(c, 12));
        box.setLayoutParams(p);
        return box;
    }

    public static TextView button(Context c, String label, boolean solid) {
        TextView t = text(c, label, 15, solid ? Color.WHITE : PRIMARY_DARK, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        t.setMinHeight(dp(c, 50));
        t.setBackground(solid ? round(c, PRIMARY, 14) : stroke(c, SURFACE, LINE, 14));
        return t;
    }

    public static TextView softButton(Context c, String label) {
        TextView t = text(c, label, 14, PRIMARY_DARK, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(c, 10), dp(c, 10), dp(c, 10), dp(c, 10));
        t.setMinHeight(dp(c, 46));
        t.setBackground(round(c, SOFT, 13));
        return t;
    }

    public static TextView status(Context c, String value, boolean good, boolean bad) {
        int color = bad ? ERROR : (good ? PRIMARY : MUTED);
        int back = bad ? Color.rgb(252, 239, 239) : (good ? SOFT : Color.rgb(240, 243, 244));
        TextView t = text(c, value, 13, color, good || bad);
        t.setPadding(dp(c, 13), dp(c, 11), dp(c, 13), dp(c, 11));
        t.setBackground(round(c, back, 13));
        return t;
    }

    public static GradientDrawable round(Context c, int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(c, radiusDp));
        return g;
    }

    public static GradientDrawable stroke(Context c, int color, int stroke, int radiusDp) {
        GradientDrawable g = round(c, color, radiusDp);
        g.setStroke(dp(c, 1), stroke);
        return g;
    }

    public static View space(Context c, int h) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(c, h)));
        return v;
    }

    public static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
}
