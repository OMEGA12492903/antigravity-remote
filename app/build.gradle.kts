import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseKeystoreProps = Properties().apply {
    val f = file(System.getProperty("user.home") + "/.local/share/noctalia-android-toolchain/keys/antigravity-remote-keystore.properties")
    if (f.exists()) {
        f.inputStream().use { load(it) }
    }
}

// Allow building either lean arm64-v8a (default, ~50 MB) or universal omni (-PgeckoOmni=true)
val useOmniGecko = project.findProperty("geckoOmni")?.toString()?.toBoolean() == true
val geckoViewVersion = "143.0.20251003115653"

android {
    namespace = "com.omegax.antigravityremote"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.omegax.antigravityremote"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.1.3"

        if (!useOmniGecko) {
            ndk {
                abiFilters += "arm64-v8a"
            }
        }

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val sf = releaseKeystoreProps.getProperty("storeFile")
            if (!sf.isNullOrBlank() && file(sf).exists()) {
                storeFile = file(sf)
                storePassword = releaseKeystoreProps.getProperty("storePassword")
                keyAlias = releaseKeystoreProps.getProperty("keyAlias")
                keyPassword = releaseKeystoreProps.getProperty("keyPassword")
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
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
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

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // GeckoView's GeckoLoader requires native .so libraries extracted to disk
            useLegacyPackaging = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Mozilla GeckoView (real Firefox engine -> Google Sign-In works natively!)
    if (useOmniGecko) {
        implementation("org.mozilla.geckoview:geckoview-omni:$geckoViewVersion")
    } else {
        implementation("org.mozilla.geckoview:geckoview-arm64-v8a:$geckoViewVersion")
    }

    // Isolated HTTPS client & coroutines for in-app GitHub Releases updater
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
