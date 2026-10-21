package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;

import android.content.Intent;
import android.widget.TextView;

/** Independent hook settings destination. */
public final class HookSettingsActivity extends SettingsPageActivity {
    @Override protected void renderSettings(){
        heading.setText("Hook");section(tr(R.string.install));
        TextView intro=copy(tr(R.string.install_claude_code_hook_on_an_ssh_host_to_receive_approval_reque));intro.setTextColor(SampleUi.TEXT);intro.setTextSize(16);
        section(tr(R.string.ssh_hosts));AgentHub hub=AgentHub.get(this);java.util.List<SshProfileStore.Entry> profiles=new SshProfileStore(this).list();
        if(profiles.isEmpty())item(tr(R.string.add_an_ssh_connection_first),null,"plus",()->startActivity(new Intent(this,SshDiscoveryActivity.class)));
        for(SshProfileStore.Entry p:profiles){boolean enabled=hub.enabled(p.id);item(p.name,enabled?tr(hub.states.getOrDefault(p.id,R.string.enabled)):tr(R.string.not_installed),"server",()->{
            if(working)return;SampleUi.confirm(this,enabled?tr(R.string.uninstall_hook):tr(R.string.install_hook_387),p.user+"@"+p.host+"\n"+(enabled?tr(R.string.only_the_claude_code_hook_registered_by_this_app_will_be_removed):tr(R.string.existing_settings_are_kept_start_a_new_claude_code_session_after)),null,tr(R.string.cancel),enabled?tr(R.string.uninstall):tr(R.string.install),false,null,()->{
                working=true;toast(tr(R.string.working));hub.configure(p.id,!enabled,(ok,message)->{working=false;if(isDestroyed())return;toast(message);refreshSettings();});
            });
        });}
        copy(tr(R.string.installation_and_sync_use_ssh_no_extra_ports_are_needed_installat));
        boolean updates=false;for(SshProfileStore.Entry p:profiles)if(hub.enabled(p.id)){
            if(!updates){section(tr(R.string.update));updates=true;}
            item(p.name,tr(R.string.update_host_helper_and_recent_session_support),"hook",()->{if(working)return;working=true;toast(tr(R.string.updating));hub.configure(p.id,true,(ok,message)->{working=false;if(isDestroyed())return;toast(message);refreshSettings();});});
        }
        toggle(getResources().getConfiguration().getLocales().get(0).getLanguage().equals("zh")?"智能体页面保持常亮":"Keep Agents screen awake",null,null,"agent","keepScreenOn",false,null);
        section(tr(R.string.hook_status));toggle(tr(R.string.show_hook_status),tr(R.string.show_status_dots_on_saved_connections),null,"appearance","hookStatus",true,null);
    }
}
