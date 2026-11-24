plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val gitDescribe = providers.exec {
    commandLine("git", "describe", "--always", "--dirty")
}.standardOutput.asText.get().trim()

// Every commit is a new versionCode, so the in-app updater sees each published build as newer.
val commitCount = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toInt()

// SOUNDBOARD_UPDATE_URL points test builds at a local server; -local (like -dirty) makes publish refuse them.
val defaultUpdateUrl = "https://cdn.rutgerpronk.com/artifacts/soundboard/"
val updateUrlOverride = providers.environmentVariable("SOUNDBOARD_UPDATE_URL").orNull.orEmpty()

android {
    namespace = "com.rutger.soundboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rutger.soundboard"
        minSdk = 26
        targetSdk = 35
        versionCode = commitCount
        versionName = "v$commitCount" +
            (if (gitDescribe.endsWith("-dirty")) "-dirty" else "") +
            (if (updateUrlOverride.isNotEmpty()) "-local" else "")
        buildConfigField("String", "GIT_HASH", "\"$gitDescribe\"")
        buildConfigField("String", "UPDATE_URL", "\"${updateUrlOverride.ifEmpty { defaultUpdateUrl }}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    debugImplementation(libs.androidx.ui.tooling)
}
