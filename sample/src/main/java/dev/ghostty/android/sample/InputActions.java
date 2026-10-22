package dev.ghostty.android.sample;

import dev.ghostty.android.TerminalSession;
import java.util.*;

/** Shared catalog and validated, transport-independent shortcut grammar. */
final class InputActions {
    record Action(String id,String label,String icon,String binding,boolean enter){ @Override public String label(){return id.startsWith("custom_")?label:InputText.localize(label);} }
    record Stroke(TerminalSession.Key key,int codePoint,String text,int modifiers,boolean paste){}
    static final LinkedHashMap<String,Action> CATALOG=new LinkedHashMap<>();
    static final LinkedHashMap<String,List<String>> GROUPS=new LinkedHashMap<>();
    static {
        for(String[] a:new String[][]{{"ctrl","Ctrl","keyboard"},{"alt","Opt","keyboard"},{"shift","Shift","keyboard"},{"super","Cmd","keyboard"},{"pad","方向键浮板","pad"},{"panel","侧边栏","panel"},{"paste","粘贴","paste"},{"history","输入历史","history"},{"chat","文本输入","chat"},{"keyboard","键盘","keyboard"},{"shortcuts","快捷键面板","shortcuts"},{"minimize","最小化","down"},{"hide_keyboard","隐藏键盘","keyboard"},{"zen","Zen 环","toolbox"},{"select","文本选择","pad"},{"mouse","鼠标选择","pad"},{"right_click","右键","pad"},{"drag_arrows","拖动方向键","pad"},{"changes","代码变更","edit"},{"preview","网页预览","server"},{"dictation","语音输入","mic"},{"none","无","close"}})add(a[0],a[1],a[2],"action:"+a[0],false);
        for(TerminalSession.Key key:TerminalSession.Key.values())add("key_"+key.name(),keyLabel(key),"keyboard",key.name(),false);
        for(String c:new String[]{"|","/","\\","~","-","_"})add("char_"+(int)c.charAt(0),c,"keyboard","text:"+c,false);
        for(char c:new char[]{'c','d','z','l','a','e','u','w'})add("ctrl_"+c,"Ctrl+"+Character.toUpperCase(c),"keyboard","C-"+c,false);
        group("Favorites");group("Tmux","new win:C-b, c","next:C-b, n","prev:C-b, p","detach:C-b, d","windows:C-b, w","zoom:C-b, z","kill:C-b, x","last:C-b, l");
        group("Claude","/clear","Shift+Tab:S-TAB","/compact","/resume","/model","/permissions","/diff","/usage");
        group("Ctrl","Ctrl+C:C-c","Ctrl+D:C-d","Ctrl+Z:C-z","Ctrl+L:C-l","Ctrl+A:C-a","Ctrl+E:C-e","Ctrl+U:C-u","Ctrl+W:C-w");
        group("Codex","/new","/compact","/model","/permissions","/plan","/resume","/diff","/status");
        group("Herdr","new tab:C-b, c","next:C-b, n","prev:C-b, p","detach:C-b, q","spaces:C-b, w","goto:C-b, g","zoom:C-b, z","kill:C-b, x");
        group("Copilot","/clear","/model","/agent","/delegate","/share","/session","/mcp","/help","/add-dir","/list-dirs","/cwd","/theme","/terminal-setup","/reset-allowed-tools","/feedback","/login","/logout","/user");
        group("Cursor","/new-chat","/plan","/ask","/compress","/resume","/model","/auto-run","/usage","/max-mode","/sandbox","/vim","/about","/help","/copy-request-id","/copy-conversation-id","/setup-terminal","/mcp list","/mcp enable","/mcp disable","/rules","/commands","/feedback","/logout","/quit");
        group("OpenCode","/new","/sessions","/models","/init","/review","/skills","/status","/themes");
        group("Zellij","new tab:C-t, n","close tab:C-t, x","rename:C-t, r","next:C-t, l","prev:C-t, h","sync:C-t, s","break:C-t, b","toggle:C-t, TAB");
    }
    private static void add(String id,String label,String icon,String binding,boolean enter){CATALOG.put(id,new Action(id,label,icon,binding,enter));}
    private static void group(String name,String... bindings){List<String> ids=new ArrayList<>();for(int i=0;i<bindings.length;i++){String value=bindings[i];int at=value.indexOf(':');String label=at<0?value:value.substring(0,at),binding=at<0?"text:"+value:value.substring(at+1);String id="preset_"+name+"_"+i;add(id,label,"keyboard",binding,at<0);ids.add(id);}GROUPS.put(name,Collections.unmodifiableList(ids));}
    static String groupSymbol(String name){return switch(name){case "Favorites"->"☆";case "Upload"->"↑";case "Claude"->"✳";case "Codex"->"⌘";case "Tmux"->"▦";case "Ctrl"->"⌃";default->name.isEmpty()?"":name.substring(0,1);};}
    static String keyLabel(TerminalSession.Key key){return switch(key){case UP->"↑";case DOWN->"↓";case LEFT->"←";case RIGHT->"→";case PAGE_UP->"PgUp";case PAGE_DOWN->"PgDn";case ESCAPE->"Esc";case BACKSPACE->"⌫";default->key.name().substring(0,1)+key.name().substring(1).toLowerCase(Locale.ROOT);};}
    static List<Stroke> parse(String binding){
        if(binding==null||binding.isBlank())throw new IllegalArgumentException(AppLanguage.tr(R.string.input_enter_a_key_or_text_binding));
        if(binding.startsWith("action:")){if(!CATALOG.containsKey(binding.substring(7)))throw new IllegalArgumentException(AppLanguage.tr(R.string.input_unknown_action));return List.of();}
        if(binding.startsWith("text:"))return List.of(new Stroke(null,0,binding.substring(5),0,true));
        List<Stroke> result=new ArrayList<>();
        for(String step:binding.trim().replaceAll("\\s*,\\s*",",").split(",|\\s+",-1)){
            String value=step.trim();int mods=0;boolean prefix=true;
            java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("(?i)^(ctrl|control|alt|opt|option|shift|cmd|super|meta|[cmasd])[-+]").matcher(value);
            while(matcher.find()){String modifier=matcher.group(1).toLowerCase(Locale.ROOT);mods|=switch(modifier){case "c","ctrl","control"->2;case "s","shift"->1;case "d","cmd","super"->8;default->4;};value=value.substring(matcher.end());matcher.reset(value);}
            if(value.isEmpty())throw new IllegalArgumentException(AppLanguage.tr(R.string.input_the_key_sequence_contains_an_empty_step));
            String name=value.toUpperCase(Locale.ROOT).replace("PAGEUP","PAGE_UP").replace("PAGEDOWN","PAGE_DOWN");
            name=switch(name){case "ESC"->"ESCAPE";case "RETURN"->"ENTER";case "SPACE"->" ";case "DASH"->"-";case "PLUS"->"+";case "COMMA"->",";case "PGUP"->"PAGE_UP";case "PGDN"->"PAGE_DOWN";default->name;};
            TerminalSession.Key key=null;try{key=TerminalSession.Key.valueOf(name);}catch(IllegalArgumentException ignored){}
            if(key!=null)result.add(new Stroke(key,0,"",mods,false));
            else if(name.equals(" "))result.add(new Stroke(null,32," ",mods,false));
            else if(name.equals("-")||name.equals("+")||name.equals(",")){result.add(new Stroke(null,name.charAt(0),name,mods,false));}
            else if(value.codePointCount(0,value.length())==1){int cp=value.codePointAt(0);String output=(mods&1)!=0?value.toUpperCase(Locale.ROOT):value;result.add(new Stroke(null,cp,output,mods,false));}
            else throw new IllegalArgumentException(AppLanguage.tr(R.string.input_unrecognized_key)+value+AppLanguage.tr(R.string.input_use_the_text_prefix_for_text));
        }
        if(result.size()>64)throw new IllegalArgumentException(AppLanguage.tr(R.string.input_up_to_64_keystrokes_are_supported));return result;
    }
}
