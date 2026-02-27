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
        versionCode = 5
        versionName = "0.4.1"
        buildConfigField("String", "VARIANT_API_ENDPOINT", quoteForBuildConfig(variantApiEndpoint))
        buildConfigField("boolean", "USE_SHARED_CORE_REDUCER", useSharedCoreReducer.toString())
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
    debugImplementation("androidx.compose.ui:ui-tooling")
}
