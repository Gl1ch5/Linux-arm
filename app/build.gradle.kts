import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val gitCommit: String = providers.exec {
    commandLine("git", "rev-parse", "--short=10", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "nogit" } }.getOrElse("nogit")

// CI passes the version through the environment (tag name or run number).
val appVersionName = System.getenv("APP_VERSION_NAME") ?: "0.1.0-dev"
val appVersionCode = (System.getenv("APP_VERSION_CODE") ?: "1").toInt()

android {
    namespace = "io.github.gl1ch5.linuxarm"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "io.github.gl1ch5.linuxarm"
        minSdk = 29
        // targetSdk >= 29 forbids execve() from writable app storage. proot and its
        // loader are therefore shipped as lib*.so in jniLibs and executed from
        // nativeLibraryDir (see README, "Why jniLibs").
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "GITHUB_REPO", "\"Gl1ch5/Linux-arm\"")
    }

    signingConfigs {
        // Committed, publicly known key so that debug APKs from different CI runs
        // can be installed over each other. Never use it for releases.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "linuxarm"
            keyAlias = "debug"
            keyPassword = "linuxarm"
        }
        create("release") {
            val ks = System.getenv("KEYSTORE_FILE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            // No applicationIdSuffix: Termux:X11 bakes the host applicationId into its
            // BuildConfig and addresses its broadcasts to exactly that package.
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (System.getenv("KEYSTORE_FILE") != null)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    packaging {
        // Native libraries must be extracted to nativeLibraryDir: proot and its
        // loader are executables and have to exist as real files there.
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)
                ?.outputFileName?.set("linux-arm-${variant.buildType}.apk")
        }
    }
}

dependencies {
    implementation(project(":lorie"))

    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")
    implementation("androidx.core:core-ktx:1.19.1")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:5.5.1")
    implementation("io.coil-kt.coil3:coil-compose:3.6.3")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")
}
