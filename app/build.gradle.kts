@file:Suppress("UnstableApiUsage")

import java.util.Properties

plugins {
    alias(libs.plugins.agp.app)
    alias(libs.plugins.compose.compiler)
    id("kotlin-parcelize")
}

val androidCompileSdkVersion: Int by rootProject.extra
val androidCompileSdkVersionMinor: Int by rootProject.extra
val androidBuildToolsVersion: String by rootProject.extra
val androidMinSdkVersion: Int by rootProject.extra
val androidTargetSdkVersion: Int by rootProject.extra
val androidSourceCompatibility: JavaVersion by rootProject.extra
val androidTargetCompatibility: JavaVersion by rootProject.extra
val appVersionCode: Int by rootProject.extra
val appVersionName: String by rootProject.extra
val signingPropertiesFile = rootProject.file("sign.properties")
val signingProperties = Properties().apply {
    if (signingPropertiesFile.exists()) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}
val requiredSigningProperties = listOf(
    "KEYSTORE_FILE",
    "KEYSTORE_PASSWORD",
    "KEY_ALIAS",
    "KEY_PASSWORD",
)
val releaseKeystoreFile = signingProperties.getProperty("KEYSTORE_FILE")
    ?.takeIf(String::isNotBlank)
    ?.let(rootProject::file)
val hasCompleteReleaseSigning = signingPropertiesFile.exists() &&
    requiredSigningProperties.all { !signingProperties.getProperty(it).isNullOrBlank() } &&
    releaseKeystoreFile?.isFile == true
val releaseArtifactRequested = gradle.startParameter.taskNames.any { requestedTask ->
    val taskName = requestedTask.substringAfterLast(':').lowercase()
    taskName in setOf("assemble", "build", "bundle", "install", "package", "publish") ||
        (taskName.contains("release") &&
            listOf("assemble", "bundle", "install", "package", "publish", "sign")
                .any(taskName::startsWith))
}

if (releaseArtifactRequested && !hasCompleteReleaseSigning) {
    throw GradleException(
        "A signed release was requested, but sign.properties is missing or incomplete. " +
            "Required keys: ${requiredSigningProperties.joinToString()}"
    )
}

android {
    namespace = "com.glassbar.ssh"

    signingConfigs {
        if (hasCompleteReleaseSigning) {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = signingProperties.getProperty("KEYSTORE_PASSWORD")
                keyAlias = signingProperties.getProperty("KEY_ALIAS")
                keyPassword = signingProperties.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // No native build in debug
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo.include = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidResources {
        generateLocaleConfig = true
    }
    compileSdk {
        version =
            release(androidCompileSdkVersion) {
                minorApiLevel = androidCompileSdkVersionMinor
            }
    }
    buildToolsVersion = androidBuildToolsVersion

    defaultConfig {
        minSdk = androidMinSdkVersion
        targetSdk = androidTargetSdkVersion
        versionCode = appVersionCode
        versionName = appVersionName
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    compileOptions {
        sourceCompatibility = androidSourceCompatibility
        targetCompatibility = androidTargetCompatibility
    }
}

base {
    archivesName.set(
        "GlassBarSSH_${appVersionName}_${appVersionCode}"
    )
}

dependencies {
    testImplementation(libs.junit4)

    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigationevent.compose)

    implementation(libs.kotlinx.coroutines.core)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.blur)

    implementation(libs.material.kolor)

    implementation(libs.jsch)
}
