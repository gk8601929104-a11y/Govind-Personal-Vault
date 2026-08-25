package com.govind.personalvault;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Minimal secure fallback for synchronous launcher failures. */
final class StartupGuard {
    private StartupGuard() {}

    static void show(Activity activity, Throwable failure) {
        try {
            LinearLayout page=new LinearLayout(activity); page.setOrientation(LinearLayout.VERTICAL); page.setGravity(Gravity.CENTER);
            int pad=dp(activity,24); page.setPadding(pad,pad,pad,pad); page.setBackgroundColor(Color.rgb(8,10,12));
            TextView title=text(activity,"Aegis",24,Color.rgb(196,165,116)); title.setTypeface(Typeface.DEFAULT,Typeface.BOLD); page.addView(title);
            String code=failure==null?"UNKNOWN":failure.getClass().getSimpleName();
            TextView info=text(activity,"Secure startup could not finish. No vault data was deleted.\n\nDiagnostic: "+code,15,Color.rgb(142,154,148));
            info.setGravity(Gravity.CENTER); info.setPadding(0,dp(activity,14),0,dp(activity,20)); page.addView(info);
            Button close=new Button(activity); close.setText("Close app"); close.setAllCaps(false); close.setOnClickListener(v->activity.finishAndRemoveTask());
            page.addView(close,new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,dp(activity,54)));
            activity.setContentView(page);
        } catch(Throwable ignored){ activity.finish(); }
    }

    private static TextView text(Activity a,String value,float size,int color){TextView t=new TextView(a);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    private static int dp(Activity a,int value){return Math.round(value*a.getResources().getDisplayMetrics().density);}
}
