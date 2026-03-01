plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val variantApiEndpoint: String = (project.findProperty("variantApiEndpoint") as String?)?.trim().orEmpty()
val useSharedCoreReducer: Boolean = (project.findProperty("useSharedCoreReducer") as String?)
    ?.trim()
    ?.lowercase()
    ?.let { it == "true" || it == "1" || it == "yes" || it == "on" }
    ?: false
val volcengineAppId: String = (project.findProperty("volcengineAppId") as String?)?.trim().orEmpty()
val volcengineToken: String = (project.findProperty("volcengineToken") as String?)?.trim().orEmpty()
val volcengineAsrCluster: String = (project.findProperty("volcengineAsrCluster") as String?)?.trim().orEmpty()
val volcengineAsrAddress: String =
    (project.findProperty("volcengineAsrAddress") as String?)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "wss://openspeech.bytedance.com"
val volcengineAsrUri: String =
    (project.findProperty("volcengineAsrUri") as String?)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "/api/v2/asr"
val volcengineUid: String =
    (project.findProperty("volcengineUid") as String?)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "versementor-android"
val volcengineResourceId: String = (project.findProperty("volcengineResourceId") as String?)?.trim().orEmpty()
val releaseStoreFilePath: String =
    (project.findProperty("releaseStoreFile") as String?)?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "${rootDir}/keystore/versementor-release.jks"
val releaseStorePassword: String =
    (project.findProperty("releaseStorePassword") as String?)?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "android"
val releaseKeyAlias: String =
    (project.findProperty("releaseKeyAlias") as String?)?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "androiddebugkey"
val releaseKeyPassword: String =
    (project.findProperty("releaseKeyPassword") as String?)?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: "android"

fun quoteForBuildConfig(value: String): String {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.versementor.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.versementor.android"
        minSdk = 24
        targetSdk = 34
        versionCode = 17
        versionName = "0.4.13"
        buildConfigField("String", "VARIANT_API_ENDPOINT", quoteForBuildConfig(variantApiEndpoint))
        buildConfigField("boolean", "USE_SHARED_CORE_REDUCER", useSharedCoreReducer.toString())
        buildConfigField("String", "VOLCENGINE_APP_ID", quoteForBuildConfig(volcengineAppId))
        buildConfigField("String", "VOLCENGINE_TOKEN", quoteForBuildConfig(volcengineToken))
        buildConfigField("String", "VOLCENGINE_ASR_CLUSTER", quoteForBuildConfig(volcengineAsrCluster))
        buildConfigField("String", "VOLCENGINE_ASR_ADDRESS", quoteForBuildConfig(volcengineAsrAddress))
        buildConfigField("String", "VOLCENGINE_ASR_URI", quoteForBuildConfig(volcengineAsrUri))
        buildConfigField("String", "VOLCENGINE_UID", quoteForBuildConfig(volcengineUid))
        buildConfigField("String", "VOLCENGINE_RESOURCE_ID", quoteForBuildConfig(volcengineResourceId))
    }

    signingConfigs {
        create("release") {
            storeFile = file(releaseStoreFilePath)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    applicationVariants.all {
        val currentVersionName = versionName ?: "0.0.0"
        outputs.all {
            val apkOutput = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            apkOutput.outputFileName = "VerseMentor-v${currentVersionName}-${buildType.name}.apk"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.bytedance.speechengine:speechengine_asr_tob:1.1.7") {
        exclude(group = "com.android.support")
    }
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
