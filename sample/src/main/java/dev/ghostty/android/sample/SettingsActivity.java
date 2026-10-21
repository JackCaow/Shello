package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.content.Intent;
import android.os.Bundle;

/** Settings index. Secondary pages are separate entries in the system back stack. */
public final class SettingsActivity extends SettingsPageActivity {
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        // Migrate an older restored settings page or an old internal page intent once.
        String legacy=state==null?getIntent().getStringExtra("page"):state.getString("page");
        getIntent().removeExtra("page");
        if(SettingsNavigation.isPage(legacy)){Intent next=SettingsNavigation.intent(this,legacy);if(state!=null){next.putExtra("initialScroll",state.getInt("scroll"));int y=state.getInt("homeScroll");scroll.post(()->scroll.scrollTo(0,y));}startActivity(next);}
    }
    @Override protected void renderSettings(){
        heading.setText(tr(R.string.settings));section(tr(R.string.terminal));
        navigation(tr(R.string.theme),ThemePreferences.label(ThemePreferences.current(prefs)),"palette","theme");
        navigation(tr(R.string.font_and_size),prefs.getInt("fontSize",14)+" sp","font","font");navigation(tr(R.string.advanced),null,"sliders","advanced");
        section(tr(R.string.agents));navigation("Hook",null,"hook","hook");navigation(tr(R.string.notifications),null,"bell","notifications");navigation(tr(R.string.text_draft),null,"chat","chat");
        section(tr(R.string.input));navigation(AppLanguage.tr(R.string.input_physical_keyboard),null,"keyboard","hardware");navigation(tr(R.string.toolbar),null,"toolbox","toolbar");navigation(tr(R.string.shortcuts),null,"keyboard","shortcuts");navigation(tr(R.string.gestures),null,"pad","gestures");
        section(tr(R.string.app_language));navigation(tr(R.string.app_language),languageName(AppLanguage.choice(this)),"chat","language");
        section(tr(R.string.about));item("Shello",tr(R.string.open_source_android_terminal_library_api_27),"server",()->startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://github.com/JackCaow/Shello"))));
    }
}
