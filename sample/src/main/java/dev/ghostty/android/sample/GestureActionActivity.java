package dev.ghostty.android.sample;

import java.util.*;

/** Action picker remains a real back-stack destination. */
public final class GestureActionActivity extends InputSettingsActivity {
    @Override protected void renderSettings(){String gesture=getIntent().getStringExtra("gesture");if(gesture==null){finish();return;}heading.setText(getIntent().getStringExtra("title"));section(AppLanguage.tr(R.string.input_choose_action));List<String> ids=new ArrayList<>(List.of("none","lock_ctrl","lock_shortcuts","mux_next","mux_previous","pane_next","pane_previous","session_next","session_previous"));ids.addAll(controls().available());String selected=controls().mapping(gesture);for(String id:ids)item((id.equals(selected)?"✓ ":"")+controls().label(id),null,"keyboard",()->{controls().mapping(gesture,id);finish();});}
}
