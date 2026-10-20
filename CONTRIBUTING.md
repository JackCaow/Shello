# Contributing

Use JDK 17, the pinned Android/NDK/Zig versions, and the Gradle wrapper. Keep the core transport-independent. Changes to native code, JNI names or dependency versions must rebuild the AAR and run device instrumentation tests. Changes to rendering/input/selection also need a real-device interaction check.

Document the device/OS/IME and distinguish unit, instrumentation, emulator, physical-device, and historical results. Do not commit binaries, SDKs, private credentials, transport endpoints or screenshots containing personal data. Public API changes need an example and contract test.

Before a package release, choose final Maven coordinates, verify notices in the AAR, check CI results and record the validated device matrix. Source is hosted at https://github.com/JackCaow/ghostty-android; no remote Maven publishing repository or credentials are configured.
