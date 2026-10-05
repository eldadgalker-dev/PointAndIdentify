// Copyright (c) 1986-2026 Eldad Galker, eldad@galker.com, https://www.galker.com/software/
// This software is released under the BSD 3-Clause License.
// See the LICENSE.txt file in the project root for full license information.
// Version 1.2
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// ===== Parameters =====
// Version values are injected by CI (-PversionCode / -PversionName); local builds use the defaults.
val appVersionCode: Int = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val appVersionName: String = (project.findProperty("versionName") as String?) ?: "1.0.0-local"

val githubOwner: String = project.property("point.githubOwner") as String
val githubRepo: String = project.property("point.githubRepo") as String
val githubBranch: String = project.property("point.githubBranch") as String
val rawHost: String = project.property("point.rawHost") as String
val webHost: String = project.property("point.webHost") as String

// Release signing is read from environment variables (set as CI secrets); absent values leave release unsigned.
val signingStoreFile: String? = System.getenv("POINT_KEYSTORE_FILE")
val signingStorePassword: String? = System.getenv("POINT_KEYSTORE_PASSWORD")
val signingKeyAlias: String? = System.getenv("POINT_KEY_ALIAS")
val signingKeyPassword: String? = System.getenv("POINT_KEY_PASSWORD")
val hasReleaseSigning = listOf(signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword)
    .all { !it.isNullOrBlank() }

android {
    namespace = "com.galker.pointandidentify"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.galker.pointandidentify"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Data and update endpoints are composed from gradle.properties, not hardcoded in sources.
        buildConfigField("String", "DATA_BASE_URL", "\"$rawHost/$githubOwner/$githubRepo/$githubBranch/data\"")
        buildConfigField("String", "RELEASE_LATEST_URL", "\"$webHost/$githubOwner/$githubRepo/releases/latest/download\"")
        buildConfigField("String", "RELEASE_TAG_URL", "\"$webHost/$githubOwner/$githubRepo/releases/download\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.play.services.location)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
}
