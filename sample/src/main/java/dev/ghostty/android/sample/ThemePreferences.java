package dev.ghostty.android.sample;

import android.content.SharedPreferences;

final class ThemePreferences {
    static String current(SharedPreferences prefs){
        String stored=prefs.getString("theme","shello"),theme;
        switch(stored){
            // Legacy versions persisted display labels; migrate without changing colors.
            case "黑色": case "Black": case "black": theme="black";break;
            case "浅色": case "Light": case "light": theme="light";break;
            default: theme="shello";
        }
        if(!theme.equals(stored))prefs.edit().putString("theme",theme).apply();
        return theme;
    }
    static String label(String theme){
        return theme.equals("light")?AppLanguage.tr(R.string.light):theme.equals("black")?AppLanguage.tr(R.string.black):"Shello";
    }
}
