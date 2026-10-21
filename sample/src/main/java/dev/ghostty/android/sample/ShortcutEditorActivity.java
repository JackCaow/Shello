package dev.ghostty.android.sample;

import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Explicit Save/Cancel, validated bindings, no input is sent while editing. */
public final class ShortcutEditorActivity extends InputSettingsActivity {
    private EditText name,binding;private CheckBox enter;private boolean rendered;
    private LinearLayout footer;private int editModifiers;
    private final List<TextView> modifierButtons=new ArrayList<>();
    @Override protected void renderSettings(){
        heading.setText(AppLanguage.tr(R.string.input_edit_shortcut));InputActions.Action action=controls().action(getIntent().getStringExtra("id"));
        section(AppLanguage.tr(R.string.input_button_name));name=new EditText(this);name.setSingleLine(true);SampleUi.field(name);body.addView(name);name.setText(action==null?"":action.label());
        section(AppLanguage.tr(R.string.input_key_or_text_binding));binding=new EditText(this);SampleUi.field(binding);binding.setMinLines(2);binding.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);body.addView(binding);binding.setText(action==null?"":action.binding());
        section(AppLanguage.tr(R.string.input_insert_key));modifierButtons.clear();LinearLayout modifiers=row();String[] labels={"Ctrl","Opt","Shift","Cmd"};int[] bits={2,4,1,8};
        for(int i=0;i<bits.length;i++){int bit=bits[i];TextView b=chip(labels[i],()->{editModifiers^=bit;applyModifiers();});b.setTag(bit);modifierButtons.add(b);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1);p.setMargins(dp(2),dp(2),dp(2),dp(2));modifiers.addView(b,p);}body.addView(modifiers);
        GridLayout keys=new GridLayout(this);keys.setColumnCount(4);
        for(String[] key:new String[][]{{"Esc","ESCAPE"},{"Tab","TAB"},{"Enter","ENTER"},{"⌫","BACKSPACE"},{"↑","UP"},{"↓","DOWN"},{"←","LEFT"},{"→","RIGHT"},{"Home","HOME"},{"End","END"},{"PgUp","PAGE_UP"},{"PgDn","PAGE_DOWN"}}){TextView b=chip(key[0],()->{String prefix=((editModifiers&2)!=0?"C-":"")+((editModifiers&4)!=0?"M-":"")+((editModifiers&1)!=0?"S-":"")+((editModifiers&8)!=0?"D-":"");binding.setText(prefix+key[1]);if(name.length()==0)name.setText(prefix+key[0]);});GridLayout.LayoutParams p=new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),GridLayout.spec(GridLayout.UNDEFINED,1f));p.width=0;p.height=dp(44);p.setMargins(dp(2),dp(2),dp(2),dp(2));keys.addView(b,p);}body.addView(keys,new LinearLayout.LayoutParams(-1,-2));
        item(AppLanguage.tr(R.string.input_custom_text),null,"edit",()->{if(!binding.getText().toString().startsWith("text:"))binding.setText("text:");binding.requestFocus();binding.setSelection(binding.length());getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(binding,0);});
        enter=new SampleUi.Toggle(this);enter.setText(AppLanguage.tr(R.string.input_send_enter_automatically));enter.setChecked(action!=null&&action.enter());body.addView(enter);
        section(AppLanguage.tr(R.string.input_advanced_bindings));copy(AppLanguage.tr(R.string.input_c_b_s_t_send_key_combinations_in_sequence_c_ctrl_a_m_opt_s_shift_d_cmd));
        if(action!=null){item(AppLanguage.tr(R.string.input_add_to_favorites),null,"plus",()->{List<String> ids=controls().list("group_Favorites");if(!ids.contains(action.id()))ids.add(action.id());controls().save("group_Favorites",ids);toast(AppLanguage.tr(R.string.input_added_to_favorites));});if(action.id().startsWith("custom_"))item(AppLanguage.tr(R.string.input_delete_shortcut),null,"close",()->{controls().removeCustom(action.id());finish();});}
        binding.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){readModifiers();}public void afterTextChanged(android.text.Editable text){}});readModifiers();
        if(footer!=null)root.removeView(footer);footer=row();footer.setPadding(dp(16),dp(8),dp(16),dp(8));
        for(boolean save:new boolean[]{false,true}){TextView button=chip(AppLanguage.tr(save?R.string.input_save:R.string.input_cancel),save?this::save:this::finish);button.setTextColor(save?SampleUi.GREEN:SampleUi.TEXT);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(48),1);p.setMargins(dp(4),0,dp(4),0);footer.addView(button,p);}root.addView(footer,new LinearLayout.LayoutParams(-1,-2));rendered=true;
    }
    private TextView chip(String text,Runnable action){TextView b=label(text,15,SampleUi.TEXT);b.setContentDescription(text);b.setGravity(Gravity.CENTER);b.setBackground(SampleUi.touch(this,CARD,12));b.setOnClickListener(v->action.run());return b;}
    private void applyModifiers(){int modifiers=editModifiers;try{List<InputActions.Stroke> parsed=InputActions.parse(binding.getText().toString());if(parsed.size()==1&&!parsed.get(0).paste()){InputActions.Stroke stroke=parsed.get(0);String key=stroke.key()==null?new String(Character.toChars(stroke.codePoint())):stroke.key().name();binding.setText(((modifiers&2)!=0?"C-":"")+((modifiers&4)!=0?"M-":"")+((modifiers&1)!=0?"S-":"")+((modifiers&8)!=0?"D-":"")+key);}}catch(IllegalArgumentException ignored){}editModifiers=modifiers;updateModifierButtons();}
    private void readModifiers(){try{List<InputActions.Stroke> parsed=InputActions.parse(binding.getText().toString());editModifiers=parsed.size()==1?parsed.get(0).modifiers():0;}catch(IllegalArgumentException ignored){editModifiers=0;}updateModifierButtons();}
    private void updateModifierButtons(){for(TextView b:modifierButtons){b.setEnabled(!binding.getText().toString().startsWith("text:"));boolean selected=(editModifiers&(Integer)b.getTag())!=0;b.setSelected(selected);b.setTextColor(selected?SampleUi.GREEN:SampleUi.MUTED);}}
    private void save(){try{
        String id=getIntent().getStringExtra("id");if(id==null||!id.startsWith("custom_"))id=null;String saved=controls().put(id,name.getText().toString(),binding.getText().toString(),enter.isChecked());String list=getIntent().getStringExtra("list");if(list==null)list="group_Favorites";List<String> ids=controls().list(list);String original=getIntent().getStringExtra("id");int index=ids.indexOf(original);if(index>=0)ids.set(index,saved);else if(!ids.contains(saved))ids.add(saved);controls().save(list,ids);finish();
    }catch(IllegalArgumentException e){binding.setError(e.getMessage());binding.requestFocus();}}
    // Local edits survive recreation and returning from a child without reaching the remote terminal.
    @Override protected void onResume(){if(!rendered)super.onResume();else{String n=name.getText().toString(),b=binding.getText().toString();boolean e=enter.isChecked();super.onResume();name.setText(n);binding.setText(b);enter.setChecked(e);}}
    @Override public void onSaveInstanceState(Bundle state){state.putString("edit_name",name.getText().toString());state.putString("edit_binding",binding.getText().toString());state.putBoolean("edit_enter",enter.isChecked());super.onSaveInstanceState(state);}
    @Override public void onCreate(Bundle state){super.onCreate(state);if(state!=null){name.setText(state.getString("edit_name",""));binding.setText(state.getString("edit_binding",""));enter.setChecked(state.getBoolean("edit_enter"));}}
}
