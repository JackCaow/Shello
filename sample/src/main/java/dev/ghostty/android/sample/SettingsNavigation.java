package dev.ghostty.android.sample;

import android.app.Activity;
import android.content.*;

/** One mapping for settings shortcuts, index rows and legacy page migration. */
final class SettingsNavigation {
    private SettingsNavigation(){}
    static Intent intent(Context context,String page){return new Intent(context,type(page));}
    static void open(Activity from,String page){from.startActivity(intent(from,page));}
    static boolean isPage(String page){return page!=null&&!page.isEmpty()&&type(page)!=SettingsActivity.class;}
    static Class<? extends SettingsPageActivity> type(String page){
        switch(page==null?"":page){
            case "language":return LanguageSettingsActivity.class;
            case "hook":return HookSettingsActivity.class;
            case "notifications":return NotificationsSettingsActivity.class;
            case "font":return FontSettingsActivity.class;
            case "theme":return ThemeSettingsActivity.class;
            case "advanced":return AdvancedSettingsActivity.class;
            case "hardware":return HardwareKeyboardSettingsActivity.class;
            case "toolbar":return ToolbarSettingsActivity.class;
            case "gestures":return GesturesSettingsActivity.class;
            case "chat":return DraftSettingsActivity.class;
            case "shortcuts":return ShortcutsSettingsActivity.class;
            default:return SettingsActivity.class;
        }
    }
}
