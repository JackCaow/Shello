# Third-party notices and provenance

The Android adapter, rendering and selection code was extracted from the purpose-built Terminal Lab prototype in 2026. The originating copyright holder is retained in the MIT LICENSE. No account, RCP, product UI, credentials, network service or proprietary dependencies are included. This library's MIT grant is scoped to this repository, not the surrounding source product.

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
