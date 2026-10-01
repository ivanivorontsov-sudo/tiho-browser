package org.ivanivorontsov.tiho.update

/**
 * Pinned content-signing keys (minisign / Ed25519). Different from the APK signing key: a leaked update key cannot
 * be used to ship an APK, and the APK key never touches the content pipeline. Primary signs; backup is kept offline.
 */
object TrustedKeys {
    const val PRIMARY = "RWR7xUlMF18o7Y7XtCqR6fiKqLRTnJTW1jXWNRGrIRd5YNwvZCIrReL0"
    const val BACKUP = "RWT6jFrDAMGDWtx6ZkxWVfvVeL8rTq0UvrLcN8a79jK2brncdmp/U5Uh"

    val keys: List<Minisign.PublicKey> by lazy { listOf(Minisign.parsePublicKey(PRIMARY), Minisign.parsePublicKey(BACKUP)) }

    const val BASE_URL = "https://raw.githubusercontent.com/ivanivorontsov-sudo/tiho-updates/main/"
    const val CONTENT_URL = BASE_URL + "content/"
    const val UPDATE_URL = BASE_URL + "app/update.json"
    /** SHA-256 of the APK signing certificate (privbrowser, RSA-4096). */
    const val APK_CERT_SHA256 = "F1:72:85:19:60:CF:08:63:34:B7:14:91:FB:DC:48:89:86:1E:1E:EB:60:7E:4F:2D:40:6C:BD:86:FF:90:D6:D0"
}
