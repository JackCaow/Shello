package dev.ghostty.android.sample;

import android.graphics.Typeface;
import android.text.Layout;
import android.util.TypedValue;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

/** App-owned design roles. Terminal cell typography is intentionally configured separately. */
final class UiDesign {
    static final int PAGE=16, SECTION=24, GAP=8, TEXT_GAP=4, CARD=16,
            TOUCH=48, ROW=56, FIELD=56, NAVIGATION=56, SHEET=24, CONTENT_MAX=640;
    enum Type {
        TITLE(20,28,true), SECTION(13,20,true), BODY(16,24,false),
        SECONDARY(14,20,false), CAPTION(12,16,false), CONTROL(12,18,false), CODE(14,20,false);
        final int size,line;final boolean medium;
        Type(int size,int line,boolean medium){this.size=size;this.line=line;this.medium=medium;}
    }
    private UiDesign(){}
    static Type role(int size){return size>=20?Type.TITLE:size>=16?Type.BODY:size>=13?Type.SECONDARY:Type.CAPTION;}
    static void type(TextView view,Type role){
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP,role.size);
        view.setIncludeFontPadding(false);
        view.setTypeface(role==Type.CODE||role==Type.CONTROL?Typeface.MONOSPACE:Typeface.create(role.medium?"sans-serif-medium":"sans-serif",Typeface.NORMAL));
        float line=TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,role.line,view.getResources().getDisplayMetrics());
        view.setLineSpacing(Math.max(0,line-view.getPaint().getFontMetricsInt(null)),1);
        if(!(view instanceof EditText)){if(role==Type.CODE)view.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);else view.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED);view.setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);}
        view.setTag(R.id.ui_text_role,role);
    }
    static void touch(View view){view.setMinimumWidth(SampleUi.dp(view.getContext(),TOUCH));view.setMinimumHeight(SampleUi.dp(view.getContext(),TOUCH));}
    static void paragraph(TextView view){type(view,Type.SECONDARY);}
    static void heading(TextView view){type(view,Type.SECTION);if(android.os.Build.VERSION.SDK_INT>=28)view.setAccessibilityHeading(true);}
}
