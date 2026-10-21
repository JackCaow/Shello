# Third-party notices and provenance

The Android adapter, rendering and selection code was extracted from the purpose-built Terminal Lab prototype in 2026. The originating copyright holder is retained in the MIT LICENSE. No account, RCP, credentials or proprietary dependencies from the originating product are included. The optional sample SSH fixture was added independently. This library's MIT grant is scoped to this repository, not the surrounding source product.

The native build pins Ghostty `b58ef1d678be2747c830bc1b1e6c0a086afecf9a` (MIT; Mitchell Hashimoto and contributors) and applies the documented keycap-selector patch. Ghostty's `build.zig.zon` pins uucode to `9d55524551411b493cca41ca06363625d90aff1e` (MIT; Jacob Sandlund), including Unicode data and the Björn Höhrmann decoder notices. Zig 0.16.0 is used to compile the core and its standard-library/runtime code (MIT). SIMD, Oniguruma and kitty graphics are disabled in the VT build; no desktop renderer or fonts are distributed.

These notices are packaged under `META-INF/ghostty-android/` in the library's classes JAR (inside the AAR):

- Project MIT license
- Ghostty MIT license
- uucode MIT license
- Unicode data license
- Björn Höhrmann notice
- Zig MIT license

Android platform fonts, ICU and libc are provided by the operating system. Build-only Gradle wrapper code is Apache-2.0; its distribution license is retained at `licenses/Gradle-LICENSE`. SDK/NDK, Gradle, Zig compiler and upstream source archives are not checked into this repository.

Upstream source locations:
- https://github.com/ghostty-org/ghostty
- https://github.com/jacobsandlund/uucode
- https://github.com/ziglang/zig
- https://github.com/gradle/gradle

The sample application (not the terminal AAR) uses [mwiede/JSch 2.28.8](https://github.com/mwiede/jsch) for SSH. Its BSD-style JSch/JZlib and jBCrypt notices are retained in `sample/src/main/assets/third_party/jsch/` and packaged in the sample APK. No Moshi source code or artwork is included.

The sample also includes [Bouncy Castle bcprov-jdk18on 1.86](https://www.bouncycastle.org/download/bouncy-castle-java/) (MIT) for JSch Ed25519 support on Android, including API 27. Its upstream notice is packaged at `assets/third_party/bouncycastle/LICENSE.md`. This dependency is not included in the terminal AAR.

The sample QR scanner uses [ZXing Android Embedded 4.3.0](https://github.com/journeyapps/zxing-android-embedded) and [ZXing Core](https://github.com/zxing/zxing), Apache-2.0. The optional host pairing script uses the externally installed `qrcode` Python package (BSD); it is not bundled in the AAR.

The sample's Claude/OpenAI identification icons come from [Lobe Icons](https://github.com/lobehub/lobe-icons), MIT, copyright 2023 LobeHub. SVG path commands are expanded for Android VectorDrawable compatibility; its license is packaged under `assets/third_party/lobe-icons/LICENSE`. Brand marks identify the corresponding third-party CLI and do not imply affiliation. No Moshi assets are used.
