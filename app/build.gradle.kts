plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseSigningEnvironment = listOf(
    "WAVE_ANDROID_KEYSTORE",
    "WAVE_ANDROID_KEY_ALIAS",
    "WAVE_ANDROID_KEYSTORE_PASSWORD",
    "WAVE_ANDROID_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val hasReleaseSigning = releaseSigningEnvironment.values.any { it != null }
if (hasReleaseSigning) {
    require(!gradle.startParameter.isConfigurationCacheRequested) {
        "Release signing requires --no-configuration-cache to keep credentials out of cached configuration"
    }
    require(releaseSigningEnvironment.values.all { !it.isNullOrBlank() }) {
        "Release signing requires all four WAVE_ANDROID signing environment variables"
    }
}

android {
    namespace = "me.vwave.vpn"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.vwave.vpn"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "0.1.4"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseSigningEnvironment.getValue("WAVE_ANDROID_KEYSTORE")!!)
                storeType = "PKCS12"
                keyAlias = releaseSigningEnvironment.getValue("WAVE_ANDROID_KEY_ALIAS")
                storePassword = releaseSigningEnvironment.getValue("WAVE_ANDROID_KEYSTORE_PASSWORD")
                keyPassword = releaseSigningEnvironment.getValue("WAVE_ANDROID_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "+debug"
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    testImplementation(libs.junit)
    implementation(project(":core"))
    implementation(libs.wireguard.tunnel)
    implementation(libs.coroutines.android)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
