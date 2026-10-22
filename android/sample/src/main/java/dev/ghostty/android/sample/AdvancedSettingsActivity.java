package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.content.Intent;
import android.provider.Settings;

/** Independent advanced settings destination. */
public final class AdvancedSettingsActivity extends SettingsPageActivity {
    @Override protected void renderSettings(){
        heading.setText(tr(R.string.advanced));section(tr(R.string.home));
        toggle(tr(R.string.show_connection_hook_status),tr(R.string.show_status_dots_on_saved_connections),null,"appearance","hookStatus",true,null);
        toggle(tr(R.string.show_feature_guide),tr(R.string.show_feature_tips_on_the_home_screen),null,"appearance","guide",true,null);
        section(tr(R.string.background_activity));item(tr(R.string.battery_and_background_activity),tr(R.string.review_device_battery_restrictions),"settings",()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:"+getPackageName()))));
        copy(tr(R.string.ssh_stays_connected_in_the_background_tmux_sessions_stay_on_the_s));
    }
}
