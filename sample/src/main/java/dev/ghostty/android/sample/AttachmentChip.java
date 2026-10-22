package dev.ghostty.android.sample;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.InsetDrawable;
import android.view.Gravity;
import android.widget.*;

/** Artwork-only attachment preview with an independently clickable corner remove action. */
final class AttachmentChip extends FrameLayout {
    AttachmentChip(Context context,AttachmentTransfer upload,Runnable preview,Runnable remove){
        super(context);setMinimumWidth(dp(72));setMinimumHeight(dp(72));
        boolean failed=!upload.busy&&upload.path==null;
        String status=upload.busy?AppLanguage.tr(R.string.attachment_pending):failed?AppLanguage.tr(upload.failure):android.text.format.Formatter.formatShortFileSize(context,upload.bytes);
        setContentDescription(upload.name+" · "+status+" · "+AppLanguage.tr(R.string.attachment_preview));setOnClickListener(v->preview.run());
        setBackground(SampleUi.touch(context,Color.TRANSPARENT,12));
        ImageView thumbnail=new ImageView(context);thumbnail.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);thumbnail.setBackground(SampleUi.surface(context,0xff171a1c,12));thumbnail.setClipToOutline(true);
        if(upload.thumbnail!=null){thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);thumbnail.setImageBitmap(upload.thumbnail);}
        else{thumbnail.setScaleType(ImageView.ScaleType.CENTER);thumbnail.setImageDrawable(new ToolbarIcon(upload.mime.startsWith("video/")?"play":upload.mime.startsWith("image/")?"image":"file",dp(24),SampleUi.MUTED));}
        addView(thumbnail,new LayoutParams(dp(56),dp(56),Gravity.LEFT|Gravity.BOTTOM));
        if(upload.busy){ProgressBar progress=new ProgressBar(context);progress.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);LayoutParams p=new LayoutParams(dp(16),dp(16),Gravity.LEFT|Gravity.BOTTOM);p.setMargins(dp(4),0,0,dp(4));addView(progress,p);}
        ImageButton dismiss=new ImageButton(context);dismiss.setImageDrawable(new ToolbarIcon("close",dp(14),SampleUi.TEXT));dismiss.setScaleType(ImageView.ScaleType.CENTER);dismiss.setPadding(0,0,0,0);dismiss.setStateListAnimator(null);
        dismiss.setBackground(new InsetDrawable(SampleUi.touch(context,0xff303438,10),dp(14)));UiDesign.touch(dismiss);dismiss.setContentDescription(AppLanguage.tr(R.string.attachment_remove,upload.name));dismiss.setOnClickListener(v->remove.run());
        // The child consumes its corner target before the preview parent receives a tap.
        addView(dismiss,new LayoutParams(dp(48),dp(48),Gravity.TOP|Gravity.RIGHT));
    }
    private int dp(int value){return SampleUi.dp(getContext(),value);}
}
