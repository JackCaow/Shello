package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.content.Intent;
import android.os.Build;
import android.provider.Settings;

/** Independent notifications settings destination. */
public final class NotificationsSettingsActivity extends SettingsPageActivity {
    @Override protected void renderSettings(){
        heading.setText(tr(R.string.notifications));
        toggle(tr(R.string.agent_event_notifications),null,"bell","agent","notifications",true,null);
        copy(tr(R.string.receive_approval_requests_questions_and_completion_notifications));
        section(tr(R.string.permissions));item(tr(R.string.system_notification_settings),tr(R.string.review_notification_permissions_and_channels),"bell",()->startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,getPackageName())));
        if(Build.VERSION.SDK_INT>=33)item(tr(R.string.request_notification_permission),null,"bell",()->requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},91));
        section(tr(R.string.agent_updates));item(tr(R.string.install_hook),tr(R.string.install_on_an_ssh_host_to_receive_agent_events),"hook",()->SettingsNavigation.open(this,"hook"));
        copy(tr(R.string.active_ssh_sessions_keep_fetching_events_in_the_background_if_no));
    }
}
