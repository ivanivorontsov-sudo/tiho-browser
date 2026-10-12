package org.ivanivorontsov.tiho.update

/**
 * Pinned signing keys (minisign / Ed25519). Different from the APK signing key: a leaked update key cannot be used
 * to ship an APK (the APK certificate is checked separately), and the APK key never signs content.
 *
 *  - primary / backup: offline keys; sign content packs (security lists, payment domains, userscript catalog) and the
 *    legacy app/update.json read by Тихо ≤ 1.1.0.
 *  - ci: held only as a GitHub Actions secret; accepted ONLY for app/update-v2.json (APK updates), never for content
 *    packs. CI already holds the APK signing key, so this key adds no new power; it lets the daily GeckoView
 *    update pipeline publish without bringing the offline keys online.
 */
object TrustedKeys {
    const val PRIMARY = "RWR7xUlMF18o7Y7XtCqR6fiKqLRTnJTW1jXWNRGrIRd5YNwvZCIrReL0"
    const val BACKUP = "RWT6jFrDAMGDWtx6ZkxWVfvVeL8rTq0UvrLcN8a79jK2brncdmp/U5Uh"
    const val CI = "RWTYQTo6pzCDMf6JKRNvzdNsFWdTrem3ZAkWcdfQOUoOpwxx6kUQvGQ9"

    /** Keys for content packs. */
    val keys: List<Minisign.PublicKey> by lazy { listOf(Minisign.parsePublicKey(PRIMARY), Minisign.parsePublicKey(BACKUP)) }
    /** Keys for APK update descriptors. */
    val appKeys: List<Minisign.PublicKey> by lazy { keys + Minisign.parsePublicKey(CI) }

    const val BASE_URL = "https://raw.githubusercontent.com/ivanivorontsov-sudo/tiho-updates/main/"
    const val CONTENT_URL = BASE_URL + "content/"
    const val UPDATE_URL = BASE_URL + "app/update-v2.json"
    /** SHA-256 of the APK signing certificate (privbrowser, RSA-4096). */
    const val APK_CERT_SHA256 = "CA:8B:DA:71:17:F3:99:86:2F:C7:E5:1D:F6:B7:BA:25:83:95:DD:CB:86:56:4D:D0:E2:5E:19:16:88:3B:AB:DD"
}
