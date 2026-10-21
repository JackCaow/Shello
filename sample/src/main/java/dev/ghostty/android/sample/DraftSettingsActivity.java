package dev.ghostty.android.sample;

import static dev.ghostty.android.sample.AppLanguage.tr;


/** Independent chat settings destination. */
public final class DraftSettingsActivity extends SettingsPageActivity {
    @Override protected void renderSettings(){
        heading.setText(tr(R.string.text_draft));section(tr(R.string.input_mode));toggle(tr(R.string.start_with_a_text_draft),tr(R.string.edit_before_sending_when_a_terminal_opens),"chat","appearance","draftDefault",false,null);
        copy(tr(R.string.multiline_input_is_supported_respond_to_agent_questions_and_appro));
        section(tr(R.string.send));item(tr(R.string.tap_send),tr(R.string.send_text_and_press_enter),"send",null);item(tr(R.string.long_press_send),tr(R.string.send_text_without_enter),"edit",null);
    }
}
