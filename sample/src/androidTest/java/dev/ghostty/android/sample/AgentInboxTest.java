package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.os.ParcelFileDescriptor;
import android.view.*;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.*;
import org.junit.Test;
import java.util.*;
import java.io.InputStream;
import static org.junit.Assert.*;

public class AgentInboxTest {
    final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    final Context context=inst.getTargetContext();
    View find(View view,String text){
        if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=find(((ViewGroup)view).getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
    @Test public void codexSourceLifecycleAndApprovalDetails()throws Exception{
        AgentHub hub=AgentHub.get(context);List<AgentHub.Event> old=new ArrayList<>();
        // Hold refresh while displaying only isolated UI fixtures; no host calls.
        long until=System.currentTimeMillis()+15000;boolean[] busy={true};
        do{inst.runOnMainSync(()->busy[0]=hub.busy);if(busy[0])Thread.sleep(50);}while(busy[0]&&System.currentTimeMillis()<until);
        assertFalse(busy[0]);
        String[] kinds={"SessionStart","UserPromptSubmit","Stop","Interrupt","SessionEnd","PermissionRequest"};
        int[] titles={R.string.agent_session_started,R.string.agent_task_started,R.string.task_completed,R.string.agent_task_interrupted,R.string.agent_session_ended};
        List<AgentHub.Event> fixtures=new ArrayList<>();
        for(String kind:kinds)fixtures.add(new AgentHub.Event("inbox-test","Test host",new JSONObject().put("id",UUID.randomUUID().toString()).put("provider","codex").put("hook_event_name",kind).put("status",kind.equals("PermissionRequest")?"pending":"info").put("tool_name","apply_patch").put("tool_input",new JSONObject().put("command","*** Begin Patch\n*** End Patch"))));
        inst.runOnMainSync(()->{old.addAll(hub.events);hub.busy=true;hub.events.clear();hub.events.addAll(fixtures);});
        AgentActivity activity=null;
        try{
            // Foreground the app before an internal Activity launch on Android 10+.
            try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -W -n dev.ghostty.android.sample/.MainActivity");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
            activity=(AgentActivity)inst.startActivitySync(new Intent(context,AgentActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));inst.waitForIdleSync();final AgentActivity page=activity;
            inst.runOnMainSync(()->{
                for(int title:titles)assertNotNull(find(page.body,"● "+AppLanguage.tr(title)));
                assertNotNull(find(page.body,"Codex · Test host · "+AppLanguage.tr(R.string.received)));
                View text=find(page.body,"● "+AppLanguage.tr(R.string.approval_request_tool,"apply_patch"));assertNotNull(text);
                ((View)text.getParent().getParent()).performClick();
                assertNotNull(find(page.body,"*** Begin Patch\n*** End Patch"));
                assertNotNull(find(page.body,AppLanguage.tr(R.string.allow_once)));assertNotNull(find(page.body,AppLanguage.tr(R.string.deny)));
            });
        }finally{
            final AgentActivity page=activity;
            inst.runOnMainSync(()->{if(page!=null)page.finish();hub.events.clear();hub.events.addAll(old);hub.busy=false;for(AgentHub.Event e:fixtures)hub.preferences.edit().remove("read_"+e.id()).commit();});
        }
    }
}
