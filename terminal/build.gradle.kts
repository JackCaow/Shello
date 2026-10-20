plugins { id("com.android.library"); id("maven-publish") }
group = "dev.ghostty.android"
version = "0.1.0-alpha01"
android {
    namespace = "dev.ghostty.android"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        minSdk = 31
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    publishing { singleVariant("release") { withSourcesJar() } }
}
tasks.register("verifyNativeLibrary") {
    doLast { check(file("src/main/jniLibs/arm64-v8a/libghostty_android.so").isFile) { "Run scripts/build-native.sh before building the AAR." } }
}
tasks.named("preBuild") { dependsOn("verifyNativeLibrary") }
afterEvaluate {
    publishing { publications {
        create<MavenPublication>("release") {
            from(components["release"])
            artifactId = "terminal"
            pom {
                name = "Ghostty Android (unofficial)"
                description = "Experimental Android native terminal view using libghostty-vt"
                licenses { license { name = "MIT License"; url = "https://opensource.org/license/mit" } }
            }
        }
    } }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
