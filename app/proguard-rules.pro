# JNI entry points of the Rust adblock engine
-keep class org.ivanivorontsov.tiho.adblock.AdblockNative { *; }
# JavaScript bridge methods
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keepattributes JavascriptInterface
# BouncyCastle: only the lightweight Ed25519/BLAKE2b classes are used directly (no JCA provider registration)
-dontwarn org.bouncycastle.**
