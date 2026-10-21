package dev.ghostty.android.sample;

import java.util.*;

/** Independent destination with immediately persisted add/remove/drag configuration. */
public final class InputLayoutActivity extends InputSettingsActivity {
    @Override protected void renderSettings(){
        String key=getIntent().getStringExtra("key");if(key==null||!(key.startsWith("layout_")||key.startsWith("group_"))){finish();return;}
        heading.setText(getIntent().getStringExtra("title"));
        if(key.equals("group_Tmux")||key.equals("group_Herdr")){
            String group=key.substring(6);section(AppLanguage.tr(R.string.input_prefix_key));android.widget.EditText prefix=new android.widget.EditText(this);SampleUi.field(prefix);prefix.setSingleLine(true);prefix.setText(controls().prefix(group));body.addView(prefix);
            item(AppLanguage.tr(R.string.input_save_prefix),null,"keyboard",()->{try{String binding=prefix.getText().toString().trim();java.util.List<InputActions.Stroke> strokes=InputActions.parse(binding);if(strokes.size()!=1||strokes.get(0).paste())throw new IllegalArgumentException(AppLanguage.tr(R.string.input_prefix_single_key));controls().prefs.edit().putString("prefix_"+group,binding).apply();refreshSettings();}catch(IllegalArgumentException e){prefix.setError(e.getMessage());}});
            if(group.equals("Tmux")){inputToggle(AppLanguage.tr(R.string.input_window_one_based),null,"tmux_one_based",true);inputToggle(AppLanguage.tr(R.string.input_hide_window_picker),null,"tmux_hide_windows",false);}
        }
        section(AppLanguage.tr(R.string.input_preview));preview(key);
        section(AppLanguage.tr(R.string.input_selected_buttons_drag_handles_to_reorder));List<String> selected=controls().list(key);ordered(key,selected,false);
        section(AppLanguage.tr(R.string.input_add));item(AppLanguage.tr(R.string.input_custom_shortcut),AppLanguage.tr(R.string.input_key_combinations_text_and_sequences),"plus",()->editShortcut(null,key));
        LinkedHashSet<String> all=new LinkedHashSet<>();
        if(key.startsWith("group_"))all.addAll(InputActions.GROUPS.getOrDefault(key.substring(6),List.of()));
        for(String id:controls().available())if(id.startsWith("custom_")||!key.startsWith("group_")&&!id.startsWith("preset_"))all.add(id);
        for(String id:all)if(!selected.contains(id)){InputActions.Action action=controls().action(id);if(action!=null)item("＋ "+action.label(),action.binding(),action.icon(),()->{List<String> next=controls().list(key);next.add(id);controls().save(key,next);refreshSettings();});}
        section(AppLanguage.tr(R.string.input_reset));item(AppLanguage.tr(R.string.input_restore_defaults),null,"history",()->{controls().reset(key);refreshSettings();});
        if(key.equals("layout_toolbar"))copy(AppLanguage.tr(R.string.input_ctrl_is_always_retained_changes_apply_immediately_to_the_terminal));
    }
}
