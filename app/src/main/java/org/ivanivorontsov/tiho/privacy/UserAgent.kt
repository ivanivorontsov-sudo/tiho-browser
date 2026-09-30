package org.ivanivorontsov.tiho.privacy

import android.content.Context
import android.webkit.WebSettings
import androidx.webkit.UserAgentMetadata
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Generic ("reduced") User-Agent, same format as Chrome's UA reduction: no device model, no Android
 * build, no "wv"/"Version/4.0" WebView markers — identical to millions of Chrome-for-Android users.
 */
object UserAgent {
    @Volatile private var major: String? = null

    fun chromeMajor(ctx: Context): String {
        major?.let { return it }
        val v = WebViewCompat.getCurrentWebViewPackage(ctx)?.versionName
            ?: Regex("Chrome/(\\d+)").find(WebSettings.getDefaultUserAgent(ctx))?.groupValues?.get(1) ?: "130"
        return v.substringBefore('.').also { major = it }
    }

    fun mobile(ctx: Context) =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeMajor(ctx)}.0.0.0 Mobile Safari/537.36"

    fun desktop(ctx: Context) =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${chromeMajor(ctx)}.0.0.0 Safari/537.36"

    /** Aligns User-Agent Client Hints (Sec-CH-UA-*, navigator.userAgentData) with the reduced UA. */
    fun applyClientHints(ctx: Context, settings: WebSettings, desktop: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) return
        val m = chromeMajor(ctx)
        val brands = listOf(
            UserAgentMetadata.BrandVersion.Builder().setBrand("Chromium").setMajorVersion(m).setFullVersion("$m.0.0.0").build(),
            UserAgentMetadata.BrandVersion.Builder().setBrand("Not.A/Brand").setMajorVersion("99").setFullVersion("99.0.0.0").build(),
        )
        val md = UserAgentMetadata.Builder()
            .setBrandVersionList(brands)
            .setFullVersion("$m.0.0.0")
            .setPlatform(if (desktop) "Linux" else "Android")
            .setPlatformVersion(if (desktop) "" else "10.0.0")
            .setArchitecture("")
            .setModel("")
            .setMobile(!desktop)
            .setBitness(0)
            .setWow64(false)
            .build()
        try { WebSettingsCompat.setUserAgentMetadata(settings, md) } catch (_: Exception) {}
    }
}
