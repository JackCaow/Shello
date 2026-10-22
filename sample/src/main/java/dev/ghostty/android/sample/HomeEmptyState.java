package dev.ghostty.android.sample;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Empty cards are invitations to connect, never fake terminal sessions. */
final class HomeEmptyState {
    static View card(Context c,boolean session,Runnable add){
        LinearLayout card=new LinearLayout(c);card.setOrientation(session?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);card.setGravity(Gravity.CENTER_VERTICAL|(session?Gravity.CENTER_HORIZONTAL:Gravity.LEFT));
        int padding=SampleUi.dp(c,16);card.setPadding(padding,padding,padding,padding);
        GradientDrawable surface=SampleUi.surface(c,0xff0b0d0e,session?16:20);surface.setStroke(Math.max(1,SampleUi.dp(c,1)),0xff1e3526,SampleUi.dp(c,3),SampleUi.dp(c,3));
        card.setBackground(new RippleDrawable(ColorStateList.valueOf(0x2200ee00),surface,SampleUi.surface(c,Color.WHITE,session?16:20)));
        card.setFocusable(true);card.setContentDescription(AppLanguage.tr(session?R.string.no_active_sessions:R.string.no_saved_connections)+". "+AppLanguage.tr(R.string.new_ssh_connection));card.setOnClickListener(v->add.run());
        ImageView icon=SampleUi.icon(c,session?"terminal":"server",0xff00d800);icon.setPadding(SampleUi.dp(c,15),SampleUi.dp(c,15),SampleUi.dp(c,15),SampleUi.dp(c,15));icon.setBackground(SampleUi.surface(c,0xff071b0d,16));icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams glyph=new LinearLayout.LayoutParams(SampleUi.dp(c,56),SampleUi.dp(c,56));if(session)glyph.bottomMargin=SampleUi.dp(c,16);else glyph.rightMargin=SampleUi.dp(c,16);card.addView(icon,glyph);
        LinearLayout labels=new LinearLayout(c);labels.setOrientation(LinearLayout.VERTICAL);labels.setGravity(session?Gravity.CENTER:Gravity.LEFT);
        TextView title=SampleUi.text(c,AppLanguage.tr(session?R.string.no_active_sessions:R.string.no_saved_connections),16,SampleUi.TEXT);title.setIncludeFontPadding(false);title.setGravity(session?Gravity.CENTER:Gravity.LEFT);labels.addView(title);
        LinearLayout hint=new LinearLayout(c);hint.setGravity(Gravity.CENTER_VERTICAL);TextView copy=SampleUi.text(c,AppLanguage.tr(R.string.tap_plus_to_add_connection),14,SampleUi.MUTED);copy.setIncludeFontPadding(false);hint.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
        TextView arrow=SampleUi.text(c,"↘",14,0xff00ed00);arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-2,-2);ap.leftMargin=SampleUi.dp(c,8);hint.addView(arrow,ap);
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2);hp.topMargin=SampleUi.dp(c,8);labels.addView(hint,hp);
        card.addView(labels,new LinearLayout.LayoutParams(session?-1:0,-2,session?0:1));card.setMinimumHeight(SampleUi.dp(c,session?160:92));return card;
    }
}
