plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "ru.ainur.feldshernotes.verification"
    compileSdk = 36
    defaultConfig { applicationId = "ru.ainur.feldshernotes.test"; minSdk = 26; targetSdk = 36; versionCode = 2; versionName = "1.1.0-test" }
    buildTypes { release { isMinifyEnabled = false; signingConfig = null } }
    sourceSets.getByName("main").java.srcDir("../app/src/androidTest/java")
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
dependencies {
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
}