package dev.ghostty.android.sample;

import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

/** Radial controls, falling back to a scrollable grid when the window is short. */
final class ZenRing extends HorizontalScrollView {
    private final List<String> ids;private final Consumer<String> run;private final List<View> buttons=new ArrayList<>();
    private int active=-1,availableWidth;
    private final FrameLayout canvas;
    ZenRing(Context context,InputPreferences config,Consumer<String> run){
        super(context);this.run=run;ids=config.list("layout_zen");setHorizontalScrollBarEnabled(false);setFillViewport(true);setBackground(SampleUi.surface(context,0xee111315,128));
        canvas=new FrameLayout(context){
            @Override protected void onMeasure(int ws,int hs){
                int width=availableWidth,height=MeasureSpec.getSize(hs),count=buttons.size();
                int rows=Math.max(1,(height-dp(8))/dp(48));
                int gridWidth=((count+rows-1)/rows)*dp(48)+dp(8);
                if(compact(width,height,count))width=Math.max(width,gridWidth);
                super.onMeasure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),hs);
            }
            @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
                int count=buttons.size();if(count==0)return;
                if(compact(ZenRing.this.getWidth(),getHeight(),count)){
                    int rows=Math.max(1,(getHeight()-dp(8))/dp(48)),columns=(count+rows-1)/rows;
                    int cell=Math.max(dp(48),(getWidth()-dp(8))/Math.max(1,columns));
                    for(int i=0;i<count;i++){int x=dp(4)+(i/rows)*cell+(cell-dp(48))/2,y=dp(4)+(i%rows)*dp(48);buttons.get(i).layout(x,y,x+dp(48),y+dp(48));}return;
                }
                float radius=Math.max(0,Math.min(getWidth(),getHeight())/2f-dp(28));
                for(int i=0;i<count;i++){double angle=-Math.PI/2+2*Math.PI*i/count;int x=Math.round(getWidth()/2f+radius*(float)Math.cos(angle)-dp(24)),y=Math.round(getHeight()/2f+radius*(float)Math.sin(angle)-dp(24));buttons.get(i).layout(x,y,x+dp(48),y+dp(48));}
            }
        };
        addView(canvas,new HorizontalScrollView.LayoutParams(-1,-1));
        for(String id:ids){InputButton button=new InputButton(context,config.action(id),config.flag("modifier_icons",false));button.setOnClickListener(v->run.accept(id));buttons.add(button);canvas.addView(button,new FrameLayout.LayoutParams(dp(48),dp(48)));}
    }
    @Override protected void onMeasure(int width,int height){availableWidth=MeasureSpec.getSize(width);super.onMeasure(width,height);}
    private boolean compact(int width,int height,int count){float radius=Math.min(width,height)/2f-dp(28);return height<dp(180)||count>1&&2*radius*Math.sin(Math.PI/count)<dp(52);}
    private int dp(int n){return SampleUi.dp(getContext(),n);}
    List<View> buttons(){return Collections.unmodifiableList(buttons);}
    void track(float x,float y){active=-1;double best=dp(40);for(int i=0;i<buttons.size();i++){View v=buttons.get(i);double distance=Math.hypot(x+getScrollX()-v.getX()-v.getWidth()/2f,y-v.getY()-v.getHeight()/2f);if(distance<best){best=distance;active=i;}}for(int i=0;i<buttons.size();i++)buttons.get(i).setActivated(i==active);}
    void release(){if(active>=0)run.accept(ids.get(active));active=-1;}
}
