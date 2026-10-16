import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
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

// Engine + app version (CI bumps these when a new GeckoView stable is published).
val geckoProps = Properties().apply { rootProject.file("gecko.properties").inputStream().use { load(it) } }
val geckoVersion: String = geckoProps.getProperty("geckoVersion")
val appVersionCode = geckoProps.getProperty("versionCode").toInt()
val appVersionName: String = geckoProps.getProperty("versionName")

android {
    namespace = "org.ivanivorontsov.tiho"
    // GeckoView 157 AAR metadata requires compiling against API 37.1
    compileSdk { version = release(37) { minorApiLevel = 2 } }

    defaultConfig {
        applicationId = "org.ivanivorontsov.tiho"
        minSdk = 26
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "GECKO_VERSION", "\"$geckoVersion\"")
        androidResources.localeFilters += listOf("ru")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One APK per ABI (GeckoView is ~45-60 MB per ABI). x86_64 only with -PwithX86 (emulator tests).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            if (project.hasProperty("withX86")) include("x86_64")
            isUniversalApk = false
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
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "kotlin/**", "DebugProbesKt.bin", "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/versions/**/OSGI-INF/**", "org/bouncycastle/**/*.properties") }
        // GeckoView loads libxul from the APK; keep the libraries compressed for a smaller download.
        jniLibs { useLegacyPackaging = true }
    }
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.systemProperty("tiho.assets", project.file("src/main/assets").absolutePath)
        }
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("org.mozilla.geckoview:geckoview:$geckoVersion") {
        // No Google Play Services in a privacy browser (only used by GeckoView for WebAuthn/FIDO, disabled via prefs).
        exclude(group = "com.google.android.gms")
    }
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // Ed25519 + BLAKE2b for minisign verification of content packs / update.json
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")

    // Instrumented navigation tests (emulator / device): popups, target=_blank, window.open, tabs.create.
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Release APKs are only ever signed with the privbrowser release key (keystore/privbrowser-release.p12 locally,
// TIHO_* secrets in CI). No fallback to the debug key and no unsigned release artifacts.
tasks.matching { it.name == "packageRelease" || it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        if (!canSign) throw GradleException("Release build requires the privbrowser release key (keystore.properties or TIHO_* env); refusing to produce an unsigned/debug-signed APK")
    }
}
