package dev.ghostty.android;

import org.json.*;
import java.nio.charset.StandardCharsets;

/** Protocol widths are owned by VT, never inferred from the font's pixel advance. */
final class UnicodeChecks {
    static final String[] NAMES={"family","skin-tone","flag","keycap","emoji-VS16","text-VS15","rainbow","couple","combining","CJK","ambiguous","hash-keycap","star-keycap"};
    static final String[] TEXT={"👨‍👩‍👧‍👦","👍🏽","🇨🇳","1️⃣","☀️","☀︎","🏳️‍🌈","👩‍❤️‍👩","é","中","·","#️⃣","*️⃣"};
    static final int[] WIDTH={2,2,2,2,2,1,2,2,1,2,1,2,2};
    static final int[] LEGACY_WIDTH={8,4,4,1,1,1,3,5,1,2,1,1,1};
    static void write(long h,String s){NativeBridge.write(h,s.getBytes(StandardCharsets.UTF_8));}
    static void add(JSONArray out,String name,boolean pass,Object actual)throws JSONException{out.put(new JSONObject().put("name",name).put("pass",pass).put("actual",actual));}
    static JSONArray run()throws Exception{
        JSONArray out=new JSONArray();
        for(boolean clustered:new boolean[]{false,true})for(int i=0;i<TEXT.length;i++){
            long h=NativeBridge.create(40,4),restored=0;
            try{
                String init="\033[?2027"+(clustered?"h":"l");write(h,init);
                byte[] bytes=TEXT[i].getBytes(StandardCharsets.UTF_8);
                // Snapshot in the middle of a sequence, including inside a multibyte UTF-8 character.
                int cut=Math.max(1,bytes.length/2);NativeBridge.write(h,java.util.Arrays.copyOfRange(bytes,0,cut));restored=NativeBridge.restore(NativeBridge.snapshot(h));
                byte[] tail=java.util.Arrays.copyOfRange(bytes,cut,bytes.length);NativeBridge.write(h,tail);NativeBridge.write(restored,tail);
                JSONObject f=new JSONObject(NativeBridge.frame(h));int width=f.getInt("x");
                add(out,(clustered?"cluster:":"legacy:")+NAMES[i]+":snapshot",NativeBridge.frame(h).equals(NativeBridge.frame(restored)),width);
                String copy=new String(NativeBridge.selection(h,0,Math.max(0,width-1)),StandardCharsets.UTF_8);
                add(out,(clustered?"cluster:":"legacy:")+NAMES[i]+":copy",copy.equals(TEXT[i]),copy);
                add(out,(clustered?"cluster:":"legacy:")+NAMES[i]+":width",width==(clustered?WIDTH[i]:LEGACY_WIDTH[i]),width);
            }finally{NativeBridge.free(h);NativeBridge.free(restored);}
        }
        long h=NativeBridge.create(8,4);try{
            write(h,"\033[?2027$p");add(out,"default-legacy-query",new String(NativeBridge.replies(h),StandardCharsets.UTF_8).contains("?2027;2$y"),"DECRQM");
            write(h,"\033[?2027h\033[?2027$p");add(out,"enabled-cluster-query",new String(NativeBridge.replies(h),StandardCharsets.UTF_8).contains("?2027;1$y"),"DECRQM");
            write(h,"1234567"+TEXT[0]+"X");JSONObject f=new JSONObject(NativeBridge.frame(h));add(out,"wide-right-margin",f.getInt("x")==3&&f.getInt("y")==1,f);
            String copied=new String(NativeBridge.selection(h,0,10),StandardCharsets.UTF_8);add(out,"wide-wrap-copy",copied.equals("1234567"+TEXT[0]+"X"),copied);
            NativeBridge.resize(h,12,4);copied=new String(NativeBridge.selection(h,0,9),StandardCharsets.UTF_8);add(out,"wide-reflow-copy",copied.equals("1234567"+TEXT[0]+"X"),copied);
            write(h,"\033c\033[?2027$p");add(out,"reset-restores-legacy",new String(NativeBridge.replies(h),StandardCharsets.UTF_8).contains("?2027;2$y"),"RIS");
            write(h,"x\ufe0f");String invalid=new String(NativeBridge.selection(h,0,0),StandardCharsets.UTF_8);add(out,"invalid-selector-still-ignored",invalid.equals("x"),invalid);
        }finally{NativeBridge.free(h);}return out;
    }
    static String screen(){StringBuilder s=new StringBuilder("\033cLegacy widths (shape only; columns preserved)\r\n");for(String text:TEXT)s.append('|').append(text);s.append("|\r\n\033[?2027hCluster mode 2027 (application opt-in)\r\n");for(int i=0;i<TEXT.length;i++)s.append(NAMES[i]).append(" | ").append(TEXT[i]).append(" |X\r\n");return s.append("\033[?2027l").toString();}
}
