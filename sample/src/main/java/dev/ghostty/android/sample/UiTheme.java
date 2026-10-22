package dev.ghostty.android.sample;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsetsController;

/** Immutable Activity palette. Terminal cells, camera pixels and cached previews are never remapped. */
final class UiTheme {
    private static final UiTheme DARK=new UiTheme(false),LIGHT=new UiTheme(true);
    final boolean light;
    final int canvas,surface,input,text,secondary,accent,onAccent,border,ripple,toggleOff,separator;
    private UiTheme(boolean light){
        this.light=light;canvas=light?0xfff6f7f9:Color.BLACK;surface=light?Color.WHITE:SampleUi.PANEL;
        input=light?0xfff1f3f5:SampleUi.FIELD;text=light?0xff20252b:SampleUi.TEXT;
        secondary=light?0xff626b75:SampleUi.MUTED;accent=light?0xff087442:SampleUi.GREEN;
        onAccent=light?Color.WHITE:0xff062519;border=light?0xffe5e9ee:SampleUi.BORDER;
        separator=light?0xffedf0f3:0xff25282c;
        toggleOff=light?0xff87919c:0xff242628;
        ripple=light?0x180b5730:0x22ffffff;
    }
    static String signature(Context c){return ThemePreferences.current(c.getSharedPreferences("appearance",0)).equals("light")?"light":"dark";}
    static UiTheme forMode(boolean light){return light?LIGHT:DARK;}
    static UiTheme current(Context c){return forMode(signature(c).equals("light"));}
    static UiTheme of(Context c){
        Context owner=c;
        while(owner instanceof ContextWrapper){
            if(owner instanceof LocalizedActivity){UiTheme theme=((LocalizedActivity)owner).uiTheme();if(theme!=null)return theme;}
            Context next=((ContextWrapper)owner).getBaseContext();if(next==owner)break;owner=next;
        }
        return current(c);
    }
    static int foreground(Context c,int color){return of(c).foreground(color);}
    static int background(Context c,int color){return of(c).background(color);}
    int foreground(int color){
        if(!light)return color;
        switch(color){
            case SampleUi.TEXT:case 0xffd4d4d4:return text;
            case SampleUi.MUTED:case 0xff8e9298:return secondary;
            case SampleUi.GREEN:case 0xff00bc65:case 0xff00bc00:case 0xff00ee00:case 0xff00ef00:case 0xff00ed00:case 0xff00d800:return accent;
            case SampleUi.DANGER:case 0xffff4545:return 0xffb42332;
            case 0xffffbd4a:case 0xffbd9036:case 0xffffc33d:return 0xff875400;
            case 0xff75b6ff:case 0xff83b1ff:return 0xff245aab;
            case 0xffffac83:return 0xffa4461c;
            case 0xff78d8a5:return 0xff21734b;
            case 0xffff9398:return 0xffb22c3d;
            case 0xffc2a1ff:return 0xff7545a8;
            case 0xffedcd7c:return 0xff85630b;
            case 0xff7bd7e9:return 0xff176878;
            case 0xffb9c4cf:return secondary;
            case 0xffc6a6e5:return 0xff7b4c98;
            case 0xfff4a5c7:return 0xffa43d6c;
            case 0xff00b99a:return 0xff08715f;
            case 0xffd98b68:return 0xffa04c29;
            default:return color;
        }
    }
    /** Existing UI swatches resolve through one catalog; new components use named palette roles. */
    int background(int color){
        if(!light)return color;
        switch(color){
            case Color.BLACK:case SampleUi.PANEL:case SampleUi.FIELD:case 0xff0b0d0e:case 0xff111113:return surface;
            case 0xff1e1e1e:return 0xffeef1f4;
            case 0xff101214:case 0xff101113:case 0xff171a1b:case 0xff171a1c:case 0xff15181a:return 0xfff0f2f5;
            case 0xff191b1e:case 0xff191b1d:case 0xff1b1d1f:case 0xff1a1d1e:case 0xff222527:case 0xff111713:return 0xfff1f3f5;
            case SampleUi.BORDER:case 0xff222426:case 0xff242628:case 0xff242728:case 0xff282b2d:case 0xff303033:case 0xff303438:return border;
            case 0xff292c30:return 0xffe9edf2;
            case 0xff42464b:case 0xff41464b:case 0xff54585d:case 0xff575b60:case 0xff5a5b60:return 0xffa7afb8;
            case 0xff20372b:case 0xff12332a:return 0xffe5f0e9;
            case 0xff0d2412:case 0xff14271e:case 0xff122c14:case 0xff071b0d:return 0xffeef5f0;
            case 0xff1e3526:return 0xffdfe8e2;
            case 0xff063a18:return 0xffedf4ef;
            case 0xff164d48:return 0xffedf3f2;
            case 0xff041104:case 0xff032003:return 0xffedf4f0;
            case 0xff173247:case 0xff375166:return 0xffdce9f4;
            case 0xff14243d:return 0xffe7effc;
            case 0xff342016:return 0xfff9ebe2;
            case 0xff142d22:return 0xffe2f2e8;
            case 0xff341b20:return 0xfffbe7e9;
            case 0xff271c39:return 0xffefe7f8;
            case 0xff302816:return 0xfff5eed8;
            case 0xff132b32:return 0xffdff1f5;
            case 0xff23282d:return 0xffe8edf0;
            case 0xff2b2033:return 0xfff0e7f7;
            case 0xff331f2b:return 0xfff8e7ee;
            case 0xee111315:return 0xf5ffffff;
            case SampleUi.GREEN:case 0xff00ef00:return accent;
            case 0xff00b99a:return 0xff08715f;
            case 0xffd98b68:return 0xffa04c29;
            default:return color;
        }
    }
    static void window(Context context,Window window){
        if(window==null)return;UiTheme theme=of(context);
        window.setStatusBarColor(theme.canvas);window.setNavigationBarColor(theme.canvas);
        int mask=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        View decor=window.getDecorView();decor.setSystemUiVisibility((decor.getSystemUiVisibility()&~mask)|(theme.light?mask:0));
        if(Build.VERSION.SDK_INT>=30){WindowInsetsController control=window.getInsetsController();if(control!=null){int appearance=WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS|WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;control.setSystemBarsAppearance(theme.light?appearance:0,appearance);}}
    }
}
