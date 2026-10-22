package dev.ghostty.android.sample;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

/** Real measured views: run at font scales 1, 1.3 and 2 on portrait, landscape and tablet. */
public class UiDesignAuditTest {
    private final Instrumentation inst=InstrumentationRegistry.getInstrumentation();
    private final Context context=inst.getTargetContext();
    private final List<Activity> opened=new ArrayList<>();
    private final List<String> problems=new ArrayList<>();
    private String previousLanguage;private volatile Activity foreground;
    private final Application.ActivityLifecycleCallbacks observer=new Application.ActivityLifecycleCallbacks(){
        public void onActivityCreated(Activity a,Bundle b){opened.add(a);}public void onActivityStarted(Activity a){}public void onActivityResumed(Activity a){foreground=a;}
        public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}public void onActivityDestroyed(Activity a){}
    };
    @Before public void setup()throws Exception{
        ((Application)context.getApplicationContext()).registerActivityLifecycleCallbacks(observer);
        previousLanguage=AppLanguage.choice(context);String language=InstrumentationRegistry.getArguments().getString("designLanguage");if(language!=null)inst.runOnMainSync(()->AppLanguage.select(context,language));
        try(ParcelFileDescriptor fd=inst.getUiAutomation().executeShellCommand("am start -W -n dev.ghostty.android.sample/.MainActivity");InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){while(in.read()!=-1){}}
    }
    @After public void cleanup(){inst.runOnMainSync(()->{for(int i=opened.size()-1;i>=0;i--)if(!opened.get(i).isDestroyed())opened.get(i).finish();if(previousLanguage!=null&&!previousLanguage.equals(AppLanguage.choice(context)))AppLanguage.select(context,previousLanguage);});((Application)context.getApplicationContext()).unregisterActivityLifecycleCallbacks(observer);}
    private void check(String page,View view){
        if(view.getVisibility()!=View.VISIBLE||view.getWidth()==0||view.getHeight()==0)return;
        String name=view.getContentDescription()==null?view.getClass().getSimpleName():view.getContentDescription().toString();
        float dp=view.getResources().getDisplayMetrics().density;
        if(view.isClickable()&&view.isEnabled()&&(view.getWidth()<48*dp-1||view.getHeight()<48*dp-1))problems.add(page+": target < 48dp: "+name+" "+Math.round(view.getWidth()/dp)+"x"+Math.round(view.getHeight()/dp));
        if(view instanceof TextView){TextView text=(TextView)view;android.text.Layout layout=text.getLayout();String content=text.getText().toString();
            if(layout!=null&&!content.isEmpty()){
                int height=text.getHeight()-text.getCompoundPaddingTop()-text.getCompoundPaddingBottom();
                if(layout.getHeight()>height+1)problems.add(page+": vertical clipping: "+content);
                if(text.getEllipsize()==null&&!(text instanceof EditText))for(int i=0;i<layout.getLineCount();i++)if(layout.getLineMax(i)>text.getWidth()-text.getCompoundPaddingLeft()-text.getCompoundPaddingRight()+2){problems.add(page+": horizontal clipping: "+content);break;}
                if(!(text instanceof EditText)&&text.getTextSize()/text.getResources().getDisplayMetrics().scaledDensity<12-.01)problems.add(page+": tiny text: "+content);
            }
        }
        if(view instanceof ViewGroup){ViewGroup parent=(ViewGroup)view;for(int i=0;i<parent.getChildCount();i++){View child=parent.getChildAt(i);if(parent instanceof LinearLayout&&child.getVisibility()==View.VISIBLE&&(child.getTop()<-1||child.getBottom()>parent.getHeight()+1||child.getLeft()<-1||child.getRight()>parent.getWidth()+1))problems.add(page+": child outside "+parent.getClass().getSimpleName()+": "+child.getClass().getSimpleName()+" "+(child instanceof TextView?((TextView)child).getText():child.getContentDescription())+" ["+child.getTop()+","+child.getBottom()+"] / "+parent.getHeight());check(page,child);}}
    }
    private Activity open(Intent intent){
        inst.runOnMainSync(()->context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        long until=SystemClock.uptimeMillis()+10000;boolean[] ready={false};
        do{inst.runOnMainSync(()->ready[0]=foreground!=null&&foreground.getComponentName().equals(intent.getComponent())&&!foreground.isDestroyed()&&foreground.hasWindowFocus());if(!ready[0])SystemClock.sleep(50);}while(!ready[0]&&SystemClock.uptimeMillis()<until);
        assertTrue("Destination did not resume",ready[0]);inst.waitForIdleSync();SystemClock.sleep(250);return foreground;
    }
    private void audit(String name,Activity page)throws Exception{
        inst.runOnMainSync(()->check(name,page.getWindow().getDecorView()));
        if("true".equals(InstrumentationRegistry.getArguments().getString("designScreenshots")))capture(name);
    }
    private void capture(String name)throws Exception{SystemClock.sleep(600);Bitmap image=inst.getUiAutomation().takeScreenshot();assertNotNull(image);File dir=new File(context.getCacheDir(),"design-review");assertTrue(dir.isDirectory()||dir.mkdirs());try(FileOutputStream out=new FileOutputStream(new File(dir,name+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}}
    private void done(){assertTrue(String.join("\n",problems),problems.isEmpty());}
    @Test public void settingsAndFormsKeepReadableTextAndTouchTargets()throws Exception{
        for(String name:new String[]{"","language","font","theme","advanced","hook","notifications","toolbar","gestures","chat","shortcuts","hardware"}){
            Activity page=open(SettingsNavigation.intent(context,name));audit(name.isEmpty()?"settings":name,page);inst.runOnMainSync(page::finish);
        }
        for(Class<? extends Activity> type:List.of(SshDiscoveryActivity.class,PairingActivity.class,ShortcutEditorActivity.class)){
            Activity page=open(new Intent(context,type));audit(type.getSimpleName(),page);
            if(page instanceof SshDiscoveryActivity){Dialog[] picker={null};inst.runOnMainSync(()->{try{java.lang.reflect.Method show=SshDiscoveryActivity.class.getDeclaredMethod("showPicker");show.setAccessible(true);show.invoke(page);java.lang.reflect.Field field=SshDiscoveryActivity.class.getDeclaredField("picker");field.setAccessible(true);picker[0]=(Dialog)field.get(page);}catch(Exception e){throw new AssertionError(e);}});inst.waitForIdleSync();inst.runOnMainSync(()->check("ssh-discovery",picker[0].getWindow().getDecorView()));capture("ssh-discovery");inst.runOnMainSync(()->picker[0].dismiss());}
            inst.runOnMainSync(page::finish);
        }done();
    }
    @Test public void homeAndInboxRespectTheSameGeometry()throws Exception{
        MainActivity home=(MainActivity)open(new Intent(context,MainActivity.class));
        long deadline=SystemClock.uptimeMillis()+10000;boolean[] ready={false};
        do{inst.runOnMainSync(()->{try{java.lang.reflect.Field field=MainActivity.class.getDeclaredField("sshService");field.setAccessible(true);ready[0]=field.get(home)!=null;}catch(Exception e){throw new AssertionError(e);}});if(!ready[0])SystemClock.sleep(50);}while(!ready[0]&&SystemClock.uptimeMillis()<deadline);
        assertTrue(ready[0]);inst.runOnMainSync(()->{try{java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod("minimize");method.setAccessible(true);method.invoke(home);}catch(Exception e){throw new AssertionError(e);}});inst.waitForIdleSync();audit("home",home);
        Activity inbox=open(new Intent(context,AgentActivity.class));audit("inbox",inbox);done();
    }
    private double contrast(int text,int surface){double a=android.graphics.Color.luminance(text),b=android.graphics.Color.luminance(surface);return (Math.max(a,b)+.05)/(Math.min(a,b)+.05);}
    @Test public void textPaletteHasSufficientContrast(){
        for(int text:new int[]{SampleUi.TEXT,SampleUi.MUTED,SampleUi.GREEN,SampleUi.DANGER})for(int surface:new int[]{android.graphics.Color.BLACK,SampleUi.FIELD,SampleUi.PANEL})assertTrue("Normal UI text contrast < 4.5:1",contrast(text,surface)>=4.5);
        for(int surface:new int[]{0xff063a18,0xff164d48})assertTrue("Preview caption contrast < 4.5:1",contrast(SampleUi.TEXT,surface)>=4.5);
    }
    @Test public void sheetsAndEmptyCardsGrowWithText()throws Exception{
        Activity page=open(SettingsNavigation.intent(context,""));Dialog[] dialog={null};
        inst.runOnMainSync(()->dialog[0]=AttachmentSourceSheet.show(page,source->fail("Audit must not choose a source")));inst.waitForIdleSync();
        inst.runOnMainSync(()->check("attachments",dialog[0].getWindow().getDecorView()));capture("attachments");inst.runOnMainSync(()->dialog[0].dismiss());
        inst.runOnMainSync(()->{
            dialog[0]=new Dialog(page);ScrollView scroll=new ScrollView(page);LinearLayout cards=new LinearLayout(page);cards.setOrientation(LinearLayout.VERTICAL);cards.setPadding(SampleUi.dp(page,16),SampleUi.dp(page,16),SampleUi.dp(page,16),SampleUi.dp(page,16));scroll.addView(cards);
            View session=HomeEmptyState.card(page,true,()->{});session.setMinimumHeight(SampleUi.dp(page,168));cards.addView(session,new LinearLayout.LayoutParams(SampleUi.dp(page,168),-2));
            LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=SampleUi.dp(page,24);cards.addView(HomeEmptyState.card(page,false,()->{}),p);
            dialog[0].setContentView(scroll);dialog[0].show();dialog[0].getWindow().setLayout(Math.min(SampleUi.dp(page,360),AdaptiveLayout.available(page).width()),-2);
        });inst.waitForIdleSync();inst.runOnMainSync(()->check("empty-cards",dialog[0].getWindow().getDecorView()));capture("empty-cards");inst.runOnMainSync(()->dialog[0].dismiss());
        inst.runOnMainSync(()->{dialog[0]=new Dialog(page);TmuxPicker picker=new TmuxPicker(page,"ui-fixture",id->fail("No connection from UI audit"),()->{});dialog[0].setContentView(picker.buildPanel());
            try{org.json.JSONArray entries=new org.json.JSONArray();for(String provider:new String[]{"claude","codex"})entries.put(new org.json.JSONObject().put("provider",provider).put("id","12345678-1234-1234-1234-123456789012").put("cwd","/home/ui/project").put("project","中文项目 · Long project name").put("updated",System.currentTimeMillis()/1000d));picker.recentRows(new org.json.JSONObject().put("home","/home/ui").put("sessions",entries));}catch(org.json.JSONException e){throw new AssertionError(e);}dialog[0].show();AdaptiveLayout.sheetWindow(page,dialog[0],640,540);});inst.waitForIdleSync();inst.runOnMainSync(()->check("tmux-header",dialog[0].getWindow().getDecorView()));capture("tmux-header");inst.runOnMainSync(()->dialog[0].dismiss());done();
    }
}
