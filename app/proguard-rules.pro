# JNI entry points of the Rust adblock engine
-keep class org.ivanivorontsov.tiho.adblock.AdblockNative { *; }
# JavaScript bridge methods
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
-keepattributes JavascriptInterface
