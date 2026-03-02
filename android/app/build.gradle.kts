plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

import java.util.Properties

val localProperties = Properties().apply {
    val localPropsFile = rootProject.file("local.properties")
    if (localPropsFile.exists()) {
        localPropsFile.inputStream().use(::load)
    }
}
val workspaceLocalProperties = Properties().apply {
    val localPropsFile = rootProject.projectDir.parentFile?.resolve("local.properties")
    if (localPropsFile != null && localPropsFile.exists()) {
        localPropsFile.inputStream().use(::load)
    }
}

fun readStringProperty(vararg names: String): String? {
    for (name in names) {
        val fromProject = (project.findProperty(name) as String?)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (fromProject != null) return fromProject

        val fromAndroidLocal = localProperties.getProperty(name)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (fromAndroidLocal != null) return fromAndroidLocal

        val fromWorkspaceLocal = workspaceLocalProperties.getProperty(name)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (fromWorkspaceLocal != null) return fromWorkspaceLocal

        val fromEnv = System.getenv(name)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (fromEnv != null) return fromEnv
    }
    return null
}

val variantApiEndpoint: String = readStringProperty("variantApiEndpoint").orEmpty()
val useSharedCoreReducer: Boolean = readStringProperty("useSharedCoreReducer")
    ?.trim()
    ?.lowercase()
    ?.let { it == "true" || it == "1" || it == "yes" || it == "on" }
    ?: false
val volcengineAppId: String = readStringProperty(
    "volcengineAppId",
    "volcengineAppID",
    "volcengineAppid",
    "VOLCENGINE_APP_ID",
    "VOLCENGINE_APPID"
).orEmpty()
val volcengineToken: String = readStringProperty(
    "volcengineToken",
    "volcengineAccessToken",
    "volcengineAppToken",
    "VOLCENGINE_TOKEN",
    "VOLCENGINE_ACCESS_TOKEN",
    "VOLCENGINE_APP_TOKEN"
).orEmpty()
val volcengineAsrCluster: String = readStringProperty(
    "volcengineAsrCluster",
    "volcengineCluster",
    "VOLCENGINE_ASR_CLUSTER",
    "VOLCENGINE_CLUSTER"
).orEmpty()
val volcengineAsrAddress: String =
    readStringProperty(
        "volcengineAsrAddress",
        "volcengineAddress",
        "VOLCENGINE_ASR_ADDRESS",
        "VOLCENGINE_ADDRESS"
    )
        ?: "wss://openspeech.bytedance.com"
val volcengineAsrUri: String =
    readStringProperty(
        "volcengineAsrUri",
        "volcengineUri",
        "VOLCENGINE_ASR_URI",
        "VOLCENGINE_URI"
    )
        ?: "/api/v2/asr"
val volcengineUid: String =
    readStringProperty("volcengineUid", "VOLCENGINE_UID")
        ?: "versementor-android"
val volcengineResourceId: String = readStringProperty(
    "volcengineResourceId",
    "volcengineAsrResourceId",
    "VOLCENGINE_RESOURCE_ID",
    "VOLCENGINE_ASR_RESOURCE_ID"
).orEmpty()
val releaseStoreFilePath: String =
    readStringProperty("releaseStoreFile")
        ?: "${rootDir}/keystore/versementor-release.jks"
val releaseStorePassword: String =
    readStringProperty("releaseStorePassword")
        ?: "android"
val releaseKeyAlias: String =
    readStringProperty("releaseKeyAlias")
        ?: "androiddebugkey"
val releaseKeyPassword: String =
    readStringProperty("releaseKeyPassword")
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
        versionCode = 21
        versionName = "0.4.17"
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
