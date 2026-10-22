package dev.shello.releaseprobe;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.json.*;

/** Test-only runner. Uses public Android APIs, with no dependency on Shello internals or R8 names. */
public final class Probe extends Instrumentation {
    private Bundle arguments;
    private Activity active;
    @Override public void callActivityOnResume(Activity activity){active=activity;super.callActivityOnResume(activity);}
    @Override public void onCreate(Bundle args){arguments=args;super.onCreate(args);start();}
    @Override public void onStart(){
        try(ServerSocket server=new ServerSocket(Integer.parseInt(arguments.getString("port","38765")),1,InetAddress.getByName("127.0.0.1"))){
            while(true)try(Socket socket=server.accept()){
                socket.setSoTimeout(30000);
                BufferedReader in=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                String line=in.readLine();if(line==null||!line.startsWith("{"))continue;JSONObject request=new JSONObject(line);JSONObject response;
                try{response=execute(request);response.put("ok",true);}catch(Exception e){response=new JSONObject().put("ok",false).put("error",e.toString());}
                socket.getOutputStream().write((response.toString()+"\n").getBytes(StandardCharsets.UTF_8));
                if("finish".equals(request.optString("op")))break;
            }
            finish(Activity.RESULT_OK,new Bundle());
        }catch(Exception e){Bundle b=new Bundle();b.putString("error",e.toString());finish(Activity.RESULT_CANCELED,b);}
    }
    private JSONObject execute(JSONObject r)throws Exception{
        String op=r.getString("op");JSONObject out=new JSONObject();
        if(op.equals("tree")){JSONArray nodes=new JSONArray();collect(getUiAutomation().getRootInActiveWindow(),nodes);return out.put("nodes",nodes);}
        if(op.equals("text")){AccessibilityNodeInfo n=find(getUiAutomation().getRootInActiveWindow(),r.getString("match"));if(n==null)throw new IOException("No field: "+r.getString("match"));Bundle b=new Bundle();b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,r.getString("value"));if(!n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,b))throw new IOException("Set text rejected");return out;}
        if(op.equals("launch")){startActivitySync(new Intent().setComponent(new ComponentName("dev.ghostty.android.sample","dev.ghostty.android.sample.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return out;}
        if(op.equals("snapshot")){JSONObject files=new JSONObject();Context c=getTargetContext();for(File dir:new File[]{new File(c.getApplicationInfo().dataDir,"shared_prefs"),c.getFilesDir(),c.getNoBackupFilesDir()})hash(dir,c.getApplicationInfo().dataDir+"/",files);return out.put("files",files);}
        if(op.equals("memory")){Debug.MemoryInfo m=new Debug.MemoryInfo();Debug.getMemoryInfo(m);return out.put("pssKb",m.getTotalPss()).put("nativeAllocated",Debug.getNativeHeapAllocatedSize()).put("javaUsed",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory());}
        if(op.equals("clipboard")){runOnMainSync(()->((ClipboardManager)getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Acceptance input",r.optString("value"))));return out;}
        if(op.equals("ime")){runOnMainSync(()->{try{android.view.View v=active.getCurrentFocus();if(!(v instanceof android.widget.EditText))throw new IllegalStateException("No focused editor");android.widget.EditText e=(android.widget.EditText)v;if(r.has("inputType")){e.setInputType(r.optInt("inputType"));((android.view.inputmethod.InputMethodManager)getTargetContext().getSystemService(Context.INPUT_METHOD_SERVICE)).restartInput(e);}out.put("viewClass",v.getClass().getName()).put("inputType",e.getInputType()).put("text",e.getText().toString()).put("composingStart",android.view.inputmethod.BaseInputConnection.getComposingSpanStart(e.getText())).put("composingEnd",android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(e.getText()));if(r.has("value")){android.view.inputmethod.InputConnection c=v.onCreateInputConnection(new android.view.inputmethod.EditorInfo());c.commitText(r.optString("value"),1);out.put("after",e.getText().toString());}}catch(Exception ex){try{out.put("diagnosticError",ex.toString());}catch(Exception ignored){}}});return out;}
        if(op.equals("finish"))return out;
        throw new IOException("Unknown operation");
    }
    private void collect(AccessibilityNodeInfo n,JSONArray out)throws Exception{
        if(n==null)return;android.graphics.Rect b=new android.graphics.Rect();n.getBoundsInScreen(b);
        out.put(new JSONObject().put("text",String.valueOf(n.getText()==null?"":n.getText())).put("desc",String.valueOf(n.getContentDescription()==null?"":n.getContentDescription())).put("class",String.valueOf(n.getClassName())).put("bounds",new JSONArray(new int[]{b.left,b.top,b.right,b.bottom})).put("clickable",n.isClickable()).put("focused",n.isFocused()).put("enabled",n.isEnabled()));
        for(int i=0;i<n.getChildCount();i++)collect(n.getChild(i),out);
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo n,String match){
        if(n==null)return null;if(n.isEditable()&&(match.equals(String.valueOf(n.getText()))||match.equals(String.valueOf(n.getContentDescription()))||match.equals("focused")&&n.isFocused()))return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo found=find(n.getChild(i),match);if(found!=null)return found;}return null;
    }
    private void hash(File f,String root,JSONObject result)throws Exception{
        if(f.isDirectory()){File[] children=f.listFiles();if(children!=null)for(File child:children)hash(child,root,result);return;}
        if(!f.isFile())return;MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(f)){byte[] b=new byte[16384];int n;while((n=in.read(b))!=-1)digest.update(b,0,n);}
        StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format("%02x",b&255));result.put(f.getPath().substring(root.length()),hex.toString());
    }
}
