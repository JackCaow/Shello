package dev.ghostty.android;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Test APK only: inspect the real Android renderer without modifying an SSH session. */
public final class CursorPreviewActivity extends Activity {
    private final List<TerminalSession> sessions=new ArrayList<>();
    private final List<TerminalView> views=new ArrayList<>();
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(Color.BLACK);getWindow().setNavigationBarColor(Color.BLACK);
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff1e1e1e);root.setPadding(24,32,24,24);setContentView(root);
        int[] shapes={1,5,3};
        String[] labels={"方块 · 输入后重新亮起","竖线 · 远端指定形状","下划线 · 远端指定形状"};
        for(int i=0;i<3;i++){
            TextView label=new TextView(this);label.setText(labels[i]);label.setTextColor(0xffdddddd);label.setTextSize(18);label.setPadding(8,16,8,16);root.addView(label);
            TerminalView view=new TerminalView(this);root.addView(view,new LinearLayout.LayoutParams(-1,0,1));views.add(view);
            TerminalSession session=new TerminalSession(40,5);sessions.add(session);view.setSession(session);
            session.write(("\033]10;#dddddd\007\033]11;#1e1e1e\007\033]12;#dddddd\007"
                    +"$ claude\r\n中文输入 · emoji 👩🏽‍🚀\r\n光标下的文字保持可读\r\n> Hello 中文 world\033[4;9H\033["+shapes[i]+" q").getBytes(StandardCharsets.UTF_8));
            view.setListener(new TerminalView.Listener(){public void onInput(byte[] bytes){session.write(bytes);}public void onResize(int c,int r){}});
        }
        Button reset=new Button(this);reset.setText("模拟输入 · 重置闪烁");root.addView(reset);reset.setOnClickListener(v->{for(TerminalView view:views)view.sendText("");});
    }
    @Override protected void onDestroy(){for(TerminalView view:views)view.setSession(null);for(TerminalSession session:sessions)session.close();super.onDestroy();}
}
