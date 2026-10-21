# Localization

Shello supports English and Simplified Chinese, with English as the fallback.
Choose **Settings → App language → Follow system / English / 简体中文**.
On Android 13+, this preference also appears in Android's per-app language settings.
On Android 8.1–12, Shello stores the preference locally and recreates its screens.
SSH connections and remote tmux processes belong to the service and are not restarted
when a screen changes language.

## Resource layout

- `sample/src/main/res/values/strings.xml`: default English app strings and plurals.
- `sample/src/main/res/values-zh-rCN/strings.xml`: Simplified Chinese app translations.
- `terminal/src/main/res/values*/strings.xml`: embeddable terminal input and accessibility strings.
- `sample/src/main/res/xml/locales_config.xml`: Android 13+ supported app languages.

Use `AppLanguage.tr(R.string.key, args...)` for app text, including service messages;
use `AppLanguage.quantity(R.plurals.key, count, args...)` for counts and relative times.
The terminal library uses its own context resources and does not depend on the app.
Keep full sentences together, use positional format arguments, and provide English
`one` / `other` plural variants. Do not translate stored preference keys, theme IDs,
protocol identifiers, terminal escape sequences, or remote output. Multilingual text
in font/Unicode demos is intentional. Connection names and agent messages are user
or remote content and remain unchanged.

Theme preferences migrate old display labels to `shello`, `black`, or `light`.
Connection and Hook state are kept as resource IDs so already-running sessions can
render their status in the newly selected language.

## Validation

```sh
python3 scripts/check-localization.py
./gradlew :sample:assembleDebug :sample:assembleDebugAndroidTest :sample:lintRelease
adb shell am instrument -w \
  -e class dev.ghostty.android.sample.LocalizationTest,dev.ghostty.android.sample.SettingsActivityTest \
  dev.ghostty.android.sample.test/androidx.test.runner.AndroidJUnitRunner
```

The APK and test APK must be installed first. Run on API 27 and Android 13+ to cover
both language-switching paths. Check longer English text on narrow screens and at
larger system font sizes. A language change does not translate existing terminal
scrollback or re-run remote commands.
