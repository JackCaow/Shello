package dev.ghostty.android.sample;

import android.content.*;
import org.json.*;
import java.util.*;

/** Input preferences isolated from connections and agent state; defaults retain legacy button visibility. */
final class InputPreferences {
    static final String FILE="input_controls";
    final SharedPreferences prefs;
    private final SharedPreferences appearance;
    InputPreferences(Context context){prefs=context.getSharedPreferences(FILE,0);appearance=context.getSharedPreferences("appearance",0);}
    List<String> defaults(String key){
        if(key.equals("layout_toolbar")){List<String> result=new ArrayList<>(List.of("ctrl","key_ESCAPE","key_TAB","pad","panel","paste","history","chat","keyboard"));result.removeIf(id->List.of("pad","paste","history","chat").contains(id)&&!appearance.getBoolean("tool_"+id,true));return result;}
        if(key.equals("layout_composer"))return new ArrayList<>(List.of("key_ESCAPE","pad","history","shortcuts"));
        if(key.equals("layout_zen"))return new ArrayList<>(List.of("ctrl","key_ESCAPE","key_TAB","pad","paste","shortcuts","keyboard","minimize"));
        if(key.equals("groups"))return new ArrayList<>(List.of("Favorites","Tmux","Claude","Ctrl","Codex","Herdr"));
        if(key.startsWith("group_")){String group=key.substring(6);List<String> all=InputActions.GROUPS.getOrDefault(group,List.of());int limit=List.of("Tmux","Herdr","Zellij").contains(group)?4:8;return new ArrayList<>(all.subList(0,Math.min(limit,all.size())));}
        return new ArrayList<>();
    }
    List<String> list(String key){
        String raw=prefs.getString(key,null);if(raw==null)return defaults(key);
        try{JSONArray array=new JSONArray(raw);List<String> result=new ArrayList<>();for(int i=0;i<array.length();i++){String id=array.getString(i);if(!result.contains(id)&&(key.equals("groups")?InputActions.GROUPS.containsKey(id):action(id)!=null))result.add(id);}return result;}
        catch(JSONException e){return defaults(key);}
    }
    List<String> editable(String key){List<String> ids=list(key);if(key.equals("layout_toolbar"))ids.removeAll(List.of("chat","keyboard"));return ids;}
    List<String> toolbar(){List<String> ids=editable("layout_toolbar");ids.add("chat");ids.add("keyboard");return ids;}
    void save(String key,List<String> ids){LinkedHashSet<String> unique=new LinkedHashSet<>(ids);if(key.equals("layout_toolbar"))unique.add("ctrl");prefs.edit().putString(key,new JSONArray(unique).toString()).apply();}
    void reset(String key){prefs.edit().remove(key).apply();}
    InputActions.Action action(String id){
        try{JSONObject a=customs().optJSONObject(id);if(a!=null)return new InputActions.Action(id,a.getString("label"),"keyboard",a.getString("binding"),a.optBoolean("enter"));}
        catch(JSONException ignored){}InputActions.Action action=InputActions.CATALOG.get(id);
        if(action!=null&&(id.startsWith("preset_Tmux_")||id.startsWith("preset_Herdr_"))){String group=id.startsWith("preset_Tmux_")?"Tmux":"Herdr";return new InputActions.Action(action.id(),action.label(),action.icon(),prefix(group)+action.binding().substring(3),action.enter());}return action;
    }
    JSONObject customs(){try{return new JSONObject(prefs.getString("customs","{}"));}catch(JSONException e){return new JSONObject();}}
    String put(String id,String label,String binding,boolean enter){InputActions.parse(binding);if(label==null||label.isBlank())throw new IllegalArgumentException(AppLanguage.tr(R.string.input_enter_a_button_name));if(id==null)id="custom_"+UUID.randomUUID();JSONObject all=customs();try{JSONObject a=new JSONObject();a.put("label",label);a.put("binding",binding);a.put("enter",enter);all.put(id,a);}catch(JSONException e){throw new IllegalArgumentException(e);}prefs.edit().putString("customs",all.toString()).apply();return id;}
    void removeCustom(String id){JSONObject all=customs();all.remove(id);SharedPreferences.Editor edit=prefs.edit().putString("customs",all.toString());for(String key:prefs.getAll().keySet())if(key.startsWith("layout_")||key.startsWith("group_")){List<String> ids=list(key);ids.remove(id);edit.putString(key,new JSONArray(ids).toString());}edit.apply();}
    String prefix(String group){return prefs.getString("prefix_"+group,"C-b");}
    String mapping(String key){return prefs.getString("gesture_"+key,switch(key){case "double_tap"->"paste";case "pull_down","toolbar_down"->"hide_keyboard";case "toolbar_up"->"minimize";case "keyboard_tap"->"keyboard";case "keyboard_double"->"key_ENTER";case "keyboard_long"->"dictation";case "ctrl_double"->"lock_ctrl";case "ctrl_long"->"shortcuts";case "shortcut_double"->"lock_shortcuts";case "swipe_left"->"mux_next";case "swipe_right"->"mux_previous";case "two_left"->"pane_next";case "two_right"->"pane_previous";case "two_up"->"session_next";case "two_down"->"session_previous";case "corner_ul"->"key_BACKSPACE";case "corner_ur"->"ctrl_c";default->"none";});}
    void mapping(String key,String action){prefs.edit().putString("gesture_"+key,action).apply();}
    boolean flag(String key,boolean fallback){return prefs.getBoolean(key,fallback);}
    void setFlag(String key,boolean value){prefs.edit().putBoolean(key,value).apply();}
    List<String> available(){List<String> out=new ArrayList<>(InputActions.CATALOG.keySet());Iterator<String> ids=customs().keys();while(ids.hasNext()){String id=ids.next();if(!out.contains(id))out.add(id);}return out;}
    String label(String id){InputActions.Action a=action(id);if(a!=null)return a.label();return switch(id){case "lock_ctrl"->AppLanguage.tr(R.string.input_lock_ctrl);case "lock_shortcuts"->AppLanguage.tr(R.string.input_lock_shortcut_panel);case "mux_next"->AppLanguage.tr(R.string.input_next_tmux_window);case "mux_previous"->AppLanguage.tr(R.string.input_previous_tmux_window);case "pane_next"->AppLanguage.tr(R.string.input_next_tmux_pane);case "pane_previous"->AppLanguage.tr(R.string.input_previous_tmux_pane);case "session_next"->AppLanguage.tr(R.string.input_next_session);case "session_previous"->AppLanguage.tr(R.string.input_previous_session);default->AppLanguage.tr(R.string.input_none);};}
}
