import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
}

val releaseKeystorePath = providers.environmentVariable("RELEASE_KEYSTORE_PATH")
    .orElse(providers.gradleProperty("releaseKeystorePath"))
    .orNull
val releaseKeyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS")
    .orElse(providers.gradleProperty("releaseKeyAlias"))
    .orNull
val releaseKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD")
    .orElse(providers.gradleProperty("releaseKeyPassword"))
    .orNull
val releaseStorePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD")
    .orElse(providers.gradleProperty("releaseStorePassword"))
    .orNull
val hasReleaseSigning = listOf(
    releaseKeystorePath,
    releaseKeyAlias,
    releaseKeyPassword,
    releaseStorePassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.materialxray"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.materialxray"
        minSdk = 31
        targetSdk = 36
        versionCode = 3
        versionName = "1.2.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = File(requireNotNull(releaseKeystorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.core.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)

    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
