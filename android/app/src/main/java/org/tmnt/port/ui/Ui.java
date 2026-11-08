package org.tmnt.port.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.TextView;

public final class Ui {
    public static final int BG = 0xFF101713, CARD = 0xFF1B2821, TEXT = 0xFFF1F5ED;
    public static final int MUTED = 0xFFAABBAE, GREEN = 0xFF8FE36A, DARK = 0xFF102016;
    private Ui() {}
    public static int dp(Context c, float v) { return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics())); }
    public static GradientDrawable round(int color, int radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(c,radiusDp)); return d;
    }
    public static TextView text(Context c, String value, float sp, int color) {
        TextView t = new TextView(c); t.setText(value); t.setTextSize(sp); t.setTextColor(color);
        t.setLineSpacing(dp(c,3),1); return t;
    }
    public static TextView title(Context c, int resource, float sp) { return text(c,c.getString(resource),sp,TEXT); }
    public static Button button(Context c, int resource, boolean primary) {
        Button b = new Button(c); b.setText(resource); b.setAllCaps(false); b.setTextSize(16);
        b.setTextColor(primary ? DARK : TEXT); b.setBackground(round(primary ? GREEN : CARD,14,c));
        b.setMinHeight(dp(c,52)); b.setPadding(dp(c,18),dp(c,10),dp(c,18),dp(c,10));
        b.setStateListAnimator(null); return b;
    }
    public static android.widget.LinearLayout.LayoutParams match() {
        return new android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT);
    }
    public static void gap(android.widget.LinearLayout box, Context c, int dp) { box.addView(new View(c),new android.widget.LinearLayout.LayoutParams(1,dp(c,dp))); }
    public static void immersive(Activity a) {
        if (Build.VERSION.SDK_INT >= 30) {
            a.getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = a.getWindow().getInsetsController();
            if (c != null) { c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); }
        } else {
            a.getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        a.getWindow().setStatusBarColor(Color.TRANSPARENT); a.getWindow().setNavigationBarColor(Color.TRANSPARENT);
    }
    /** Predictive-back safe on API 33+; older versions use the activity's key/back overrides. */
    public static void back(Activity a, Runnable action) {
        if (Build.VERSION.SDK_INT >= 33) {
            a.getWindow().getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, action::run);
        }
    }
    public static void safePadding(View v) {
        v.setOnApplyWindowInsetsListener((view,insets) -> {
            int l,t,r,b;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                l=i.left; t=i.top; r=i.right; b=i.bottom;
            } else {
                l=insets.getSystemWindowInsetLeft(); t=insets.getSystemWindowInsetTop();
                r=insets.getSystemWindowInsetRight(); b=insets.getSystemWindowInsetBottom();
                if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                    l=Math.max(l,insets.getDisplayCutout().getSafeInsetLeft()); t=Math.max(t,insets.getDisplayCutout().getSafeInsetTop());
                    r=Math.max(r,insets.getDisplayCutout().getSafeInsetRight()); b=Math.max(b,insets.getDisplayCutout().getSafeInsetBottom());
                }
            }
            view.setPadding(l,t,r,b); return insets;
        });
        v.requestApplyInsets();
    }
}
