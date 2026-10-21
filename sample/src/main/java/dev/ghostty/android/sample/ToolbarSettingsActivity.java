package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

/** Independent toolbar settings destination. */
public final class ToolbarSettingsActivity extends SettingsPageActivity {
    private LinearLayout toolbarPreview;
    @Override protected void renderSettings(){
        heading.setText(tr(R.string.toolbar));section(tr(R.string.preview));
        LinearLayout canvas=column();canvas.setPadding(dp(8),dp(8),dp(8),dp(8));
        GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff063a18,0xff164d48});gradient.setCornerRadius(dp(12));canvas.setBackground(gradient);
        TextView caption=label(tr(R.string.live_toolbar_preview),16,SampleUi.MUTED);caption.setGravity(Gravity.CENTER);canvas.addView(caption,new LinearLayout.LayoutParams(-1,0,1));
        toolbarPreview=row();toolbarPreview.setPadding(dp(6),dp(6),dp(6),dp(6));toolbarPreview.setBackground(SampleUi.surface(this,Color.BLACK,32));toolbarPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        canvas.addView(toolbarPreview,new LinearLayout.LayoutParams(-1,dp(52)));body.addView(canvas,new LinearLayout.LayoutParams(-1,dp(landscape?132:276)));refreshToolbar();
        section(tr(R.string.visible_buttons));
        for(String[] tool:new String[][]{{"pad",tr(R.string.direction_pad)},{"history",tr(R.string.input_history)},{"paste",tr(R.string.paste)},{"chat",tr(R.string.text_draft)}})
            toggle(tool[1],null,null,"appearance","tool_"+tool[0],true,this::refreshToolbar);
        copy(tr(R.string.ctrl_esc_tab_and_the_keyboard_button_are_always_shown_changes_app));
    }
    private void refreshToolbar(){
        if(toolbarPreview==null)return;toolbarPreview.removeAllViews();
        for(String tool:new String[]{"Ctrl","Esc","Tab","pad","history","paste","chat","keyboard"}){
            boolean fixed=tool.equals("Ctrl")||tool.equals("Esc")||tool.equals("Tab")||tool.equals("keyboard");
            if(!fixed&&!prefs.getBoolean("tool_"+tool,true))continue;
            View view;
            if(tool.length()>1&&Character.isUpperCase(tool.charAt(0))){TextView key=label(tool,13,SampleUi.TEXT);key.setGravity(Gravity.CENTER);key.setTypeface(Typeface.MONOSPACE);key.setSingleLine(true);key.setAutoSizeTextTypeUniformWithConfiguration(9,13,1,android.util.TypedValue.COMPLEX_UNIT_DIP);view=key;}
            else{ImageView icon=SampleUi.icon(this,tool.equals("history")?"panel":tool,SampleUi.TEXT);icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);icon.setPadding(dp(8),dp(8),dp(8),dp(8));view=icon;}
            view.setBackground(SampleUi.surface(this,CARD,24));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(36),1);p.leftMargin=dp(2);p.rightMargin=dp(2);toolbarPreview.addView(view,p);
        }
    }
}
