package dev.ghostty.android.sample;

import android.app.*;
import android.graphics.Color;
import android.view.*;
import android.widget.*;
import static dev.ghostty.android.sample.AppLanguage.tr;

/** Attachment source sheet; source selection never submits a draft. */
final class AttachmentSourceSheet {
    private AttachmentSourceSheet(){}
    static Dialog show(Activity activity,java.util.function.IntConsumer choose){
        Dialog dialog=new Dialog(activity);LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity,24),dp(activity,12),dp(activity,24),dp(activity,24));panel.setBackground(SampleUi.surface(activity,0xff161719,26));
        View handle=new View(activity);handle.setBackground(SampleUi.surface(activity,0xff42464b,3));LinearLayout.LayoutParams grip=new LinearLayout.LayoutParams(dp(activity,36),dp(activity,4));grip.gravity=Gravity.CENTER_HORIZONTAL;grip.bottomMargin=dp(activity,24);panel.addView(handle,grip);
        TextView title=SampleUi.text(activity,tr(R.string.add_attachment),20,SampleUi.TEXT);UiDesign.type(title,UiDesign.Type.TITLE);title.setPadding(dp(activity,4),0,0,dp(activity,24));panel.addView(title);
        int[] titles={R.string.attachment_camera,R.string.attachment_photos,R.string.attachment_files};
        int[] subtitles={R.string.attachment_camera_hint,R.string.attachment_photos_hint,R.string.attachment_file_hint};String[] icons={"camera","image","file"};
        for(int i=0;i<titles.length;i++){
            final int source=i;LinearLayout row=new LinearLayout(activity);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(activity,16),dp(activity,12),dp(activity,16),dp(activity,12));row.setMinimumHeight(dp(activity,72));row.setBackground(SampleUi.touch(activity,0xff1b1d1f,18));
            ImageView icon=new ImageView(activity);icon.setImageDrawable(new ToolbarIcon(activity,icons[i],dp(activity,20),SampleUi.MUTED));icon.setPadding(dp(activity,10),dp(activity,10),dp(activity,10),dp(activity,10));icon.setBackground(SampleUi.surface(activity,0xff101214,13));row.addView(icon,new LinearLayout.LayoutParams(dp(activity,40),dp(activity,40)));
            LinearLayout labels=new LinearLayout(activity);labels.setOrientation(LinearLayout.VERTICAL);TextView name=SampleUi.text(activity,tr(titles[i]),16,SampleUi.TEXT);labels.addView(name);TextView hint=SampleUi.text(activity,tr(subtitles[i]),14,SampleUi.MUTED);hint.setPadding(0,dp(activity,4),0,0);labels.addView(hint);LinearLayout.LayoutParams text=new LinearLayout.LayoutParams(0,-2,1);text.leftMargin=dp(activity,16);row.addView(labels,text);
            row.setContentDescription(tr(titles[i]));row.setOnClickListener(v->{dialog.dismiss();choose.accept(source);});LinearLayout.LayoutParams r=new LinearLayout.LayoutParams(-1,-2);if(i>0)r.topMargin=dp(activity,8);panel.addView(row,r);
        }
        float[] dragStart={Float.NaN};
        panel.setOnTouchListener((view,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){if(event.getY()>dp(activity,80))return false;dragStart[0]=event.getRawY();view.getParent().requestDisallowInterceptTouchEvent(true);return true;}
            if(Float.isNaN(dragStart[0]))return false;
            float distance=Math.max(0,event.getRawY()-dragStart[0]);
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE){panel.setTranslationY(distance);return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){view.getParent().requestDisallowInterceptTouchEvent(false);if(event.getActionMasked()==MotionEvent.ACTION_UP&&distance>dp(activity,56))dialog.dismiss();else panel.animate().translationY(0).setDuration(150).start();dragStart[0]=Float.NaN;return true;}
            return true;
        });
        dialog.setContentView(AdaptiveLayout.sheetScroll(activity,panel));dialog.setCanceledOnTouchOutside(true);dialog.show();
        Window window=dialog.getWindow();window.setBackgroundDrawableResource(android.R.color.transparent);window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);window.setDimAmount(.55f);
        if(activity.getResources().getConfiguration().smallestScreenWidthDp>=600)AdaptiveLayout.sheetWindow(activity,dialog,560,0);
        UiTheme.window(activity,dialog.getWindow());
        // Phones use the full bottom edge; larger windows keep a centered readable panel.
        if(activity.getResources().getConfiguration().smallestScreenWidthDp<600){
            View owner=activity.getWindow().getDecorView();Runnable resize=()->{android.graphics.Rect bounds=AdaptiveLayout.available(activity);WindowManager.LayoutParams params=dialog.getWindow().getAttributes();if(params.width!=bounds.width()||params.y!=0||params.gravity!=(Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL)){params.width=bounds.width();params.height=WindowManager.LayoutParams.WRAP_CONTENT;params.y=0;params.gravity=Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL;dialog.getWindow().setAttributes(params);}};
            resize.run();View.OnLayoutChangeListener listener=(v,l,t,r,b,ol,ot,or,ob)->resize.run();owner.addOnLayoutChangeListener(listener);dialog.setOnDismissListener(ignored->owner.removeOnLayoutChangeListener(listener));
        }
        return dialog;
    }
    private static int dp(Activity a,int value){return SampleUi.dp(a,value);}
}
