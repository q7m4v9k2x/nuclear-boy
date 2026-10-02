import java.util.Properties

plugins {
    id("com.android.application")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.chaquopy)
}

fun String?.trimToNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

val androidTestSecretProperties = Properties().apply {
    val defaultSecretFiles = listOf(
        rootProject.file("android-test-secrets.properties"),
        project.file("android-test-secrets.properties"),
    )
    val overrideSecretFile = providers.gradleProperty("nbAndroidTestSecretsFile")
        .orNull
        ?.trimToNull()
        ?.let { rootProject.file(it) }
    (defaultSecretFiles + listOfNotNull(overrideSecretFile))
        .distinctBy { it.absolutePath }
        .filter { it.isFile }
        .forEach { file -> file.inputStream().use(::load) }
}

fun secretPropertyOrEnv(propertyName: String, vararg envNames: String): String? =
    envNames.asSequence()
        .mapNotNull { providers.environmentVariable(it).orNull.trimToNull() }
        .firstOrNull()
        ?: androidTestSecretProperties.getProperty(propertyName).trimToNull()

val localAndroidTestRunnerArgs = mapOf(
    "nbBaseUrl" to secretPropertyOrEnv("nbBaseUrl", "NB_TEST_BASE_URL"),
    "nbModel" to secretPropertyOrEnv("nbModel", "NB_TEST_MODEL", "NB_TEST_MODEL_NAME"),
    "nbApiKey" to secretPropertyOrEnv("nbApiKey", "NB_TEST_API_KEY"),
    "nbEndpointMode" to secretPropertyOrEnv("nbEndpointMode", "NB_TEST_ENDPOINT_MODE"),
).mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

android {
    namespace = "com.nuclearboy.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nuclearboy.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 178
        versionName = "1.1.68"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments.putAll(localAndroidTestRunnerArgs)

        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    // Chaquopy block at android level, NOT inside defaultConfig
    chaquopy {
        defaultConfig {
            version = "3.11"
            pip {
                install("python-docx")
                install("openpyxl")
                install("Pillow")
                install("chardet")
                install("python-pptx")
                install("requests")
                install("beautifulsoup4")
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
            // 用内置 debug 签名给正式包签名，便于直接安装/分发（个人/镜像项目，非 Play 上架）
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
}

dependencies {
    // Project modules
    implementation(project(":common"))
    implementation(project(":api-deepseek"))
    implementation(project(":agent-core"))
    implementation(project(":python-bridge"))
    implementation(project(":memory"))
    implementation(project(":remote-pc"))
    implementation(project(":skills"))
    implementation(project(":tools-docgen"))
    implementation(project(":ui-chat"))
    implementation(project(":ui-workspace"))

    // AndroidX Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)
    debugImplementation(libs.compose.ui.tooling)

    // Networking
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    // KotlinX
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Markdown
    implementation(libs.markwon.core)

    // Image Loading
    implementation(libs.coil.compose)

    // Logging
    implementation(libs.timber)

    // 配对二维码扫描（远程电脑设置）
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext)
    androidTestImplementation(libs.androidx.test.espresso)
    androidTestImplementation(libs.androidx.test.uiautomator)
}
