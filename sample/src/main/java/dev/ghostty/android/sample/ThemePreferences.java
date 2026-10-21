package dev.ghostty.android.sample;

import android.content.SharedPreferences;

final class ThemePreferences {
    static String current(SharedPreferences prefs){
        String theme=prefs.getString("theme","Shello");
        // Upgrade the previous label without changing the user's colors.
        if("Moshi".equals(theme)){theme="Shello";prefs.edit().putString("theme",theme).apply();}
        return theme;
    }
}
