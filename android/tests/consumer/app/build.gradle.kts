plugins { id("com.android.application") }
android {
    namespace = "dev.ghostty.android.consumer"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig { applicationId = "dev.ghostty.android.consumer"; minSdk = 27; targetSdk = 35; ndk { abiFilters += "arm64-v8a" } }
    buildTypes { getByName("release") { isMinifyEnabled = true; signingConfig = signingConfigs.getByName("debug"); proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation("dev.ghostty.android:terminal:0.1.0-alpha01") }
