package dev.ghostty.android.sample;

import android.app.Activity;
import android.content.ClipData;
import android.graphics.*;
import android.os.Bundle;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.*;

/** Session thumbnails, contextual preview and drag ordering. No connection-profile mutations. */
final class SessionOverview {
    interface Actions { void open(String id); void close(String id); void closeAll(); void move(String id,int index); }
    private final Activity activity;
    private final FrameLayout stage;
    private final View background;
    private final Actions actions;
    private final LinearLayout root,header,cards;
    private final HorizontalScrollView scroll;
    private final TextView hint,done;
    private final ArrayList<SshSessionService.Tab> tabs=new ArrayList<>();
    private FrameLayout overlay;
    private boolean ordering;
    private String dragging;
    private int scrollDirection;
    private final Runnable autoScroll=new Runnable(){public void run(){if(dragging!=null&&scrollDirection!=0){scroll.smoothScrollBy(dp(18)*scrollDirection,0);scroll.postDelayed(this,40);}}};
    SessionOverview(Activity activity,FrameLayout stage,View background,Actions actions){
        this.activity=activity;this.stage=stage;this.background=background;this.actions=actions;
        root=column();header=row();root.addView(header,new LinearLayout.LayoutParams(-1,dp(44)));
        TextView title=SampleUi.text(activity,"会话",15,SampleUi.MUTED);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        done=SampleUi.text(activity,"完成",16,SampleUi.GREEN);done.setGravity(Gravity.CENTER);done.setContentDescription("完成会话排序");done.setFocusable(true);
        done.setBackground(SampleUi.touch(activity,0xff14271e,18));done.setOnClickListener(v->setOrdering(false));header.addView(done,new LinearLayout.LayoutParams(dp(64),dp(36)));done.setVisibility(View.GONE);
        scroll=new HorizontalScrollView(activity);scroll.setHorizontalScrollBarEnabled(false);scroll.setClipToPadding(false);
        cards=row();cards.setGravity(Gravity.TOP);scroll.addView(cards,new HorizontalScrollView.LayoutParams(-2,-2));root.addView(scroll);
        hint=SampleUi.text(activity,"",13,SampleUi.MUTED);hint.setPadding(0,dp(12),0,0);root.addView(hint);
        scroll.setOnDragListener((v,event)->{
            if(!(event.getLocalState() instanceof String)||!ordering)return false;
            switch(event.getAction()){
                case DragEvent.ACTION_DRAG_STARTED:dragging=(String)event.getLocalState();return true;
                case DragEvent.ACTION_DRAG_LOCATION:
                    scrollDirection=event.getX()<dp(42)?-1:event.getX()>scroll.getWidth()-dp(42)?1:0;
                    scroll.removeCallbacks(autoScroll);if(scrollDirection!=0)scroll.post(autoScroll);return true;
                case DragEvent.ACTION_DROP:
                    int index=Math.max(0,Math.min(tabs.size()-1,(int)((event.getX()+scroll.getScrollX())/(cardWidth()+dp(12)))));
                    actions.move((String)event.getLocalState(),index);return true;
                case DragEvent.ACTION_DRAG_ENDED:stopDrag();return true;
                default:return true;
            }
        });
    }
    View view(){return root;}
    void refresh(List<SshSessionService.Tab> values){
        tabs.clear();tabs.addAll(values);
        // Do not replace drag source Views while Android owns their drag session.
        if(dragging!=null)return;
        cards.removeAllViews();
        for(SshSessionService.Tab tab:tabs){
            LinearLayout card=column();card.setContentDescription("恢复会话 "+tab.name());card.setFocusable(true);
            card.setOnClickListener(v->{if(!ordering)actions.open(tab.id);});
            FrameLayout thumb=thumbnail(tab);card.addView(thumb,new LinearLayout.LayoutParams(cardWidth(),cardWidth()));
            TextView name=SampleUi.text(activity,tab.name(),15,SampleUi.TEXT);name.setSingleLine();name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setPadding(0,dp(8),0,0);card.addView(name);
            TextView type=SampleUi.text(activity,tab.remote()?"›_ SSH":"›_ local",13,SampleUi.GREEN);type.setTypeface(Typeface.MONOSPACE);card.addView(type);
            card.setOnLongClickListener(v->{if(ordering)beginDrag(card,tab.id);else showContext(card,thumb,tab);return true;});
            // Directional accessibility actions let keyboard/TalkBack users reorder without dragging.
            card.setAccessibilityDelegate(new View.AccessibilityDelegate(){
                public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(host,info);
                    if(ordering){info.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01020001,"向前移动"));info.addAction(new AccessibilityNodeInfo.AccessibilityAction(0x01020002,"向后移动"));}}
                public boolean performAccessibilityAction(View host,int action,Bundle args){
                    if(ordering&&(action==0x01020001||action==0x01020002)){actions.move(tab.id,tabs.indexOf(tab)+(action==0x01020001?-1:1));return true;}
                    return super.performAccessibilityAction(host,action,args);
                }
            });
            if(ordering){
                LinearLayout controls=row();
                TextView left=SampleUi.header(activity,"left","向前移动 "+tab.name(),SampleUi.TEXT,()->actions.move(tab.id,tabs.indexOf(tab)-1));
                TextView right=SampleUi.header(activity,"right","向后移动 "+tab.name(),SampleUi.TEXT,()->actions.move(tab.id,tabs.indexOf(tab)+1));
                left.setEnabled(tabs.indexOf(tab)>0);right.setEnabled(tabs.indexOf(tab)<tabs.size()-1);left.setAlpha(left.isEnabled()?1:.3f);right.setAlpha(right.isEnabled()?1:.3f);
                controls.addView(left,new LinearLayout.LayoutParams(0,dp(44),1));controls.addView(right,new LinearLayout.LayoutParams(0,dp(44),1));card.addView(controls);
            }
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(cardWidth(),-2);cp.rightMargin=dp(12);cards.addView(card,cp);
        }
        if(tabs.isEmpty()){hint.setText("暂无打开的会话，点击已保存的连接或 + 新建连接");setOrdering(false);}
        else hint.setText(ordering?(tabs.size()==1?"只有一个会话；打开更多连接后可拖动排序":"长按并拖动会话调整顺序"):"长按会话可排序或关闭");
    }
    private FrameLayout thumbnail(SshSessionService.Tab tab){
        FrameLayout thumb=new FrameLayout(activity);thumb.setBackground(SampleUi.surface(activity,0xff1e1e1e,16));thumb.setClipToOutline(true);
        ImageView image=new ImageView(activity);image.setScaleType(ImageView.ScaleType.FIT_START);image.setImageBitmap(tab.preview);image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);thumb.addView(image,new FrameLayout.LayoutParams(-1,-1));
        TextView badge=SampleUi.text(activity,tab.remote()?"SSH":"LOCAL",12,0xffe6efff);badge.setPadding(dp(8),dp(4),dp(8),dp(4));badge.setBackground(SampleUi.surface(activity,tab.remote()?0xff286de0:0xff375166,10));
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-2,-2,Gravity.TOP|Gravity.RIGHT);bp.setMargins(dp(8),dp(8),dp(8),0);thumb.addView(badge,bp);
        if(tab.preview==null){TextView title=SampleUi.text(activity,tab.name(),15,SampleUi.TEXT);title.setPadding(dp(12),dp(48),dp(12),dp(12));thumb.addView(title);}
        return thumb;
    }
    private void beginDrag(View card,String id){
        card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        card.startDragAndDrop(ClipData.newPlainText("session",id),new View.DragShadowBuilder(card),id,0);
    }
    private void stopDrag(){dragging=null;scrollDirection=0;scroll.removeCallbacks(autoScroll);refresh(new ArrayList<>(tabs));}
    private void setOrdering(boolean enabled){
        boolean changed=ordering!=enabled;ordering=enabled;done.setVisibility(enabled?View.VISIBLE:View.GONE);
        if(changed){stopDrag();if(enabled)done.requestFocus();}
    }
    private void showContext(View card,View thumb,SshSessionService.Tab tab){
        dismiss();card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        int w=stage.getWidth(),h=stage.getHeight();if(w==0||h==0)return;
        Bitmap backdrop=blurred(stage);Bitmap sharp=Bitmap.createBitmap(thumb.getWidth(),thumb.getHeight(),Bitmap.Config.ARGB_8888);thumb.draw(new Canvas(sharp));
        overlay=new FrameLayout(activity);overlay.setContentDescription("会话操作 "+tab.name());overlay.setClickable(true);overlay.setFocusableInTouchMode(true);overlay.setOnClickListener(v->dismiss());
        ImageView blurred=new ImageView(activity);blurred.setScaleType(ImageView.ScaleType.FIT_XY);blurred.setImageBitmap(backdrop);overlay.addView(blurred,new FrameLayout.LayoutParams(-1,-1));
        View dim=new View(activity);dim.setBackgroundColor(0x66000000);overlay.addView(dim,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout menu=column();menu.setBackground(SampleUi.surface(activity,SampleUi.PANEL,22));menu.setClipToOutline(true);
        menu.addView(action("重新排序","reorder",false,()->setOrdering(true)));
        View line=new View(activity);line.setBackgroundColor(0xff303033);menu.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
        menu.addView(action("关闭","close",true,()->actions.close(tab.id)));
        View gap=new View(activity);gap.setBackgroundColor(0xff101113);menu.addView(gap,new LinearLayout.LayoutParams(-1,dp(8)));
        menu.addView(action("全部关闭","close",true,actions::closeAll));
        int mw=Math.min(dp(250),w-dp(32));menu.measure(View.MeasureSpec.makeMeasureSpec(mw,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        int[] a=new int[2],b=new int[2];thumb.getLocationOnScreen(a);stage.getLocationOnScreen(b);
        int size=Math.min(thumb.getWidth(),Math.max(dp(80),h-menu.getMeasuredHeight()-dp(48)));
        int x=Math.max(dp(16),Math.min(a[0]-b[0],w-Math.max(size,mw)-dp(16)));
        int y=Math.max(dp(16),Math.min(a[1]-b[1],h-size-menu.getMeasuredHeight()-dp(32)));
        ImageView selected=new ImageView(activity);selected.setImageBitmap(sharp);selected.setScaleType(ImageView.ScaleType.FIT_XY);selected.setBackground(SampleUi.surface(activity,0xff1e1e1e,16));selected.setClipToOutline(true);
        selected.setContentDescription("选中会话 "+tab.name());selected.setOnClickListener(v->dismiss());
        FrameLayout.LayoutParams sp=new FrameLayout.LayoutParams(size,size);sp.leftMargin=x;sp.topMargin=y;overlay.addView(selected,sp);
        FrameLayout.LayoutParams mp=new FrameLayout.LayoutParams(mw,-2);mp.leftMargin=x;mp.topMargin=y+size+dp(12);overlay.addView(menu,mp);
        background.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        stage.addView(overlay,new FrameLayout.LayoutParams(-1,-1));overlay.requestFocus();
    }
    private View action(String title,String icon,boolean danger,Runnable run){
        LinearLayout row=row();row.setPadding(dp(16),dp(12),dp(16),dp(12));row.setMinimumHeight(dp(48));row.setContentDescription(title);row.setFocusable(true);
        row.setBackground(SampleUi.touch(activity,Color.TRANSPARENT,0));row.addView(SampleUi.text(activity,title,17,danger?0xffff4545:SampleUi.TEXT),new LinearLayout.LayoutParams(0,-2,1));
        row.addView(SampleUi.icon(activity,icon,danger?0xffff4545:SampleUi.TEXT),new LinearLayout.LayoutParams(dp(24),dp(24)));
        row.setOnClickListener(v->{dismiss();run.run();});return row;
    }
    boolean back(){if(overlay!=null){dismiss();return true;}if(ordering){setOrdering(false);return true;}return false;}
    void dismiss(){if(overlay!=null){stage.removeView(overlay);overlay=null;background.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);}scroll.removeCallbacks(autoScroll);}
    /** A small, separable box blur also works on API 27; captured only when the menu opens. */
    private Bitmap blurred(View source){
        int w=128,h=Math.max(1,Math.round(source.getHeight()*128f/source.getWidth()));
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(bitmap);c.scale(w/(float)source.getWidth(),h/(float)source.getHeight());source.draw(c);
        int[] pixels=new int[w*h],out=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);
        for(int pass=0;pass<4;pass++){
            boolean horizontal=pass%2==0;
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int r=0,g=0,b=0;for(int k=-3;k<=3;k++){int xx=horizontal?Math.max(0,Math.min(w-1,x+k)):x,yy=horizontal?y:Math.max(0,Math.min(h-1,y+k));int color=pixels[yy*w+xx];r+=(color>>16)&255;g+=(color>>8)&255;b+=color&255;}
                out[y*w+x]=0xff000000|(r/7)<<16|(g/7)<<8|b/7;
            }
            int[] swap=pixels;pixels=out;out=swap;
        }
        bitmap.setPixels(pixels,0,w,0,0,w,h);return bitmap;
    }
    private int cardWidth(){return (activity.getResources().getDisplayMetrics().widthPixels-dp(44))/2;}
    private int dp(int n){return SampleUi.dp(activity,n);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(activity);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private LinearLayout row(){LinearLayout l=new LinearLayout(activity);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
}
