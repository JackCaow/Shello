plugins { id("com.android.application") }
android {
    namespace = "dev.ghostty.android.sample"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.ghostty.android.sample"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha01"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes { getByName("release") { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation(project(":terminal")) }
