import java.util.Properties

plugins {
    id("com.android.application") version "9.5.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10"
}

android {
    namespace = "aj.unifiarr.app"
    compileSdk = 37
    defaultConfig {
        applicationId = "aj.unifiarr.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0"
    }
    // Local: android/keystore.properties; CI: KEYSTORE_FILE/KEYSTORE_PASSWORD/KEY_ALIAS/KEY_PASSWORD env vars.
    val ks = Properties()
    rootProject.file("keystore.properties").takeIf { it.exists() }?.reader()?.use { ks.load(it) }
    fun cfg(prop: String, env: String) = ks.getProperty(prop) ?: System.getenv(env)
    signingConfigs {
        create("release") {
            cfg("storeFile", "KEYSTORE_FILE")?.let { storeFile = rootProject.file(it) }
            storePassword = cfg("storePassword", "KEYSTORE_PASSWORD")
            keyAlias = cfg("keyAlias", "KEY_ALIAS")
            keyPassword = cfg("keyPassword", "KEY_PASSWORD")
        }
    }
    buildTypes { release { isMinifyEnabled = true; isShrinkResources = true; signingConfig = signingConfigs.getByName("release"); proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
    implementation("io.coil-kt.coil3:coil-svg:3.3.0")
}
