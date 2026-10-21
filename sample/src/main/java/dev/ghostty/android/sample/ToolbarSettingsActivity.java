package dev.ghostty.android.sample;

/** Independent normal-toolbar and Zen-ring configuration. */
public final class ToolbarSettingsActivity extends InputSettingsActivity {
    @Override protected void renderSettings(){
        heading.setText(AppLanguage.tr(R.string.input_toolbar));section(AppLanguage.tr(R.string.input_preview));
        if(controls().flag("zen_mode",false))preview("layout_zen");else{
            android.widget.LinearLayout canvas=column();canvas.setPadding(dp(8),dp(8),dp(8),dp(8));android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,new int[]{0xff063a18,0xff164d48});background.setCornerRadius(dp(12));canvas.setBackground(background);
            android.widget.TextView caption=label(AppLanguage.tr(R.string.input_live_preview),16,SampleUi.MUTED);caption.setGravity(android.view.Gravity.CENTER);canvas.addView(caption,new android.widget.LinearLayout.LayoutParams(-1,0,1));canvas.addView(layoutPreview("layout_toolbar"),new android.widget.LinearLayout.LayoutParams(-1,dp(58)));boolean landscape=getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE;body.addView(canvas,new android.widget.LinearLayout.LayoutParams(-1,dp(landscape?144:276)));
        }
        if(!controls().flag("zen_mode",false))copy(AppLanguage.tr(R.string.input_toolbar_pinned_hint));
        android.widget.RadioGroup modes=new android.widget.RadioGroup(this);modes.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        modes.addView(SampleUi.segment(this,AppLanguage.tr(R.string.input_toolbar),1),new android.widget.LinearLayout.LayoutParams(0,dp(48),1));modes.addView(SampleUi.segment(this,AppLanguage.tr(R.string.input_zen_mode),2),new android.widget.LinearLayout.LayoutParams(0,dp(48),1));modes.check(controls().flag("zen_mode",false)?2:1);modes.setOnCheckedChangeListener((g,id)->{controls().setFlag("zen_mode",id==2);refreshSettings();});body.addView(modes);copy(AppLanguage.tr(R.string.input_zen_mode_double_tap_and_hold_the_terminal_then_drag_to_an_action_on_th));
        inputToggle(AppLanguage.tr(R.string.input_use_icons_for_modifiers),null,"modifier_icons",false);
        section(AppLanguage.tr(R.string.input_layouts));item(AppLanguage.tr(R.string.input_configure_toolbar),null,"toolbox",()->openLayout("layout_toolbar",AppLanguage.tr(R.string.input_configure_toolbar)));
        item(AppLanguage.tr(R.string.input_configure_zen_ring),null,"toolbox",()->openLayout("layout_zen",AppLanguage.tr(R.string.input_configure_zen_ring)));
        item(AppLanguage.tr(R.string.input_configure_input_box),null,"chat",()->openLayout("layout_composer",AppLanguage.tr(R.string.input_configure_input_box)));
    }
}
