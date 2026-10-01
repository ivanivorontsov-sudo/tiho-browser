plugins {
    id("com.android.application") version "9.4.1" apply false
    // AGP 9 has built-in Kotlin; this only pins the Kotlin Gradle plugin version (GeckoView needs Kotlin 2.4 metadata).
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
