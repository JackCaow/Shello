package dev.ghostty.android.sample;

import android.content.Context;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

/** Radial controls shared by the live ring and its preview. */
final class ZenRing extends FrameLayout {
    private final List<String> ids;private final Consumer<String> run;private final List<View> buttons=new ArrayList<>();
    private int active=-1;
    ZenRing(Context context,InputPreferences config,Consumer<String> run){super(context);this.run=run;ids=config.list("layout_zen");setBackground(SampleUi.surface(context,0xee111315,128));
        for(String id:ids){InputButton b=new InputButton(context,config.action(id),config.flag("modifier_icons",false));b.setOnClickListener(v->run.accept(id));buttons.add(b);addView(b,new FrameLayout.LayoutParams(dp(44),dp(44)));}
    }
    private int dp(int n){return SampleUi.dp(getContext(),n);}
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b){
        int count=buttons.size();if(count==0)return;float radius=Math.max(0,Math.min(getWidth(),getHeight())/2f-dp(26));
        boolean compact=getHeight()<dp(180)||2*Math.PI*radius/count<dp(48);
        if(compact){int columns=Math.max(1,Math.min(count,getWidth()/dp(48))),rows=(count+columns-1)/columns;float cellW=getWidth()/(float)columns,cellH=getHeight()/(float)rows;int size=Math.max(1,Math.min(dp(44),(int)Math.min(cellW,cellH)-dp(4)));for(int i=0;i<count;i++){int x=Math.round((i%columns+.5f)*cellW-size/2f),y=Math.round((i/columns+.5f)*cellH-size/2f);buttons.get(i).layout(x,y,x+size,y+size);}return;}
        for(int i=0;i<count;i++){double angle=-Math.PI/2+2*Math.PI*i/count;int x=Math.round(getWidth()/2f+radius*(float)Math.cos(angle)-dp(22)),y=Math.round(getHeight()/2f+radius*(float)Math.sin(angle)-dp(22));buttons.get(i).layout(x,y,x+dp(44),y+dp(44));}
    }
    void track(float x,float y){active=-1;double best=dp(40);for(int i=0;i<buttons.size();i++){View v=buttons.get(i);double distance=Math.hypot(x-v.getX()-v.getWidth()/2f,y-v.getY()-v.getHeight()/2f);if(distance<best){best=distance;active=i;}}for(int i=0;i<buttons.size();i++)buttons.get(i).setActivated(i==active);}
    void release(){if(active>=0)run.accept(ids.get(active));active=-1;}
}
