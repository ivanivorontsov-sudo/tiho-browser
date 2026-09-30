import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing: env vars (CI secrets) or keystore.properties (local, git-ignored). One key for every build.
val ksProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: ksProps.getProperty(prop)
val ksFile = rootProject.file(secret("TIHO_KEYSTORE", "storeFile") ?: "keystore/privbrowser-release.p12")
val ksPass = secret("TIHO_KEYSTORE_PASSWORD", "storePassword")
val keyAliasName = secret("TIHO_KEY_ALIAS", "keyAlias") ?: "privbrowser"
val keyPass = secret("TIHO_KEY_PASSWORD", "keyPassword") ?: ksPass
val canSign = ksFile.exists() && ksPass != null

android {
    namespace = "org.ivanivorontsov.tiho"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.ivanivorontsov.tiho"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
        resourceConfigurations += listOf("ru")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            // -PwithX86 adds x86_64 for the emulator build only; release APK stays ARM-only.
            if (project.hasProperty("withX86")) abiFilters += "x86_64"
        }
    }

    signingConfigs {
        create("tiho") {
            if (canSign) {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = keyAliasName
                keyPassword = keyPass
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (canSign) signingConfig = signingConfigs.getByName("tiho")
        }
        debug {
            if (canSign) signingConfig = signingConfigs.getByName("tiho")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "kotlin/**", "DebugProbesKt.bin") }
        jniLibs { useLegacyPackaging = true } // compress the Rust .so inside the APK (smaller download)
    }
    dependenciesInfo {
        // No Google-signed dependency metadata blob in the APK.
        includeInApk = false
        includeInBundle = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // JVM unit tests load the same JNI engine compiled for the host (cargo build --release).
            val lib = rootProject.file("rust/tihoadblock/target/release/libtihoadblock.so")
            it.systemProperty("tiho.nativeLib", lib.absolutePath)
            it.systemProperty("tiho.assets", project.file("src/main/assets").absolutePath)
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
