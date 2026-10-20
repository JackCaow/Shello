plugins { id("com.android.application") }
android {
    buildFeatures { buildConfig = true }
    namespace = "dev.ghostty.android.sample"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = "dev.ghostty.android.sample"
        minSdk = 27
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-alpha01"
        ndk { abiFilters += "arm64-v8a" }
    }
    buildTypes { getByName("release") { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":terminal"))
    implementation("com.github.mwiede:jsch:2.28.8")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}
