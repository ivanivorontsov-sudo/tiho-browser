# GeckoView: Google Play Services (FIDO/WebAuthn, tasks) is excluded on purpose — no gms in a privacy browser.
-dontwarn com.google.android.gms.**
# GeckoView reflection/JNI entry points (the AAR ships consumer rules; keep the public API to be safe).
-keep class org.mozilla.geckoview.** { *; }
-keep class org.mozilla.gecko.** { *; }
-dontwarn org.mozilla.**
# BouncyCastle: only the lightweight Ed25519/BLAKE2b classes are used directly (no JCA provider registration)
-dontwarn org.bouncycastle.**
# GeckoRuntimeSettings.configFilePath is parsed by SnakeYAML (DebugConfig), which needs its real package names.
-keep class org.yaml.snakeyaml.** { *; }
-dontwarn org.yaml.snakeyaml.**
