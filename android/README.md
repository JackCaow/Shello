# Android app and terminal library

This directory owns the Gradle project: `sample/` is the Shello app, `terminal/` is the embeddable terminal library, and `tests/consumer/` checks the locally published AAR.

From the repository root:

```sh
scripts/build-native.sh
./android/gradlew -p android :terminal:assembleRelease :sample:assembleDebug
scripts/check-artifact.sh
```

The app reads the shared hook from `../public/agent/` and the library packages VT notices from `../public/resources/`. App ID, resource names, API 27 minimum and release signing remain unchanged. See [build prerequisites and API usage](../docs/DEVELOPMENT.md).
