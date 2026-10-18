package org.ivanivorontsov.tiho.update

import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    private val key = TestSigner(3)
    private val now = 1_800_000_000L
    private val cert = TrustedKeys.APK_CERT_SHA256
    private val apk = ByteArray(1000) { it.toByte() }
    private val v = AppUpdateVerifier(listOf(key.pub), now = { now })

    private fun json(vc: Int = 3, url: String = "https://github.com/ivanivorontsov-sudo/tiho-updates/releases/download/app-v1.1.0/tiho.apk",
                     pkg: String = "org.ivanivorontsov.tiho", expires: Long = now + 1000, c: String = cert, minVc: Int = 1) =
        """{"package":"$pkg","version_code":$vc,"version_name":"1.1.0","min_version_code":$minVc,"apk_url":"$url","sha256":"${Minisign.sha256Hex(apk)}","size":${apk.size},"expires":$expires,"cert_sha256":"$c","notes":"n"}"""

    private fun check(j: String, installed: Int = 2, tc: String = "tiho-app 3 1.1.0 timestamp:$now") =
        v.verify(j.toByteArray(), key.sign(j.toByteArray(), tc), installed, cert)

    @Test fun available() {
        val r = check(json())
        assertTrue(r is AppUpdateVerifier.Check.Available)
        v.verifyApk((r as AppUpdateVerifier.Check.Available).info, apk)
    }

    @Test fun upToDateAndTooOld() {
        assertTrue(check(json(), installed = 3) is AppUpdateVerifier.Check.UpToDate)
        assertTrue(check(json(minVc = 2), installed = 1) is AppUpdateVerifier.Check.TooOld)
    }

    @Test fun rejections() {
        for (j in listOf(json(url = "http://github.com/x.apk"), json(url = "https://evil.example/x.apk"), json(pkg = "com.evil"),
                json(expires = now - 1), json(c = "00:11"))) {
            assertThrows(j, PackException::class.java) { check(j) }
        }
        assertThrows(PackException::class.java) { check(json(), tc = "tiho-app 9 1.1.0 timestamp:$now") }
        val other = TestSigner(4)
        assertThrows(PackException::class.java) { v.verify(json().toByteArray(), other.sign(json().toByteArray(), "tiho-app 3 x"), 2, cert) }
    }

    @Test fun apkHashChecked() {
        val info = (check(json()) as AppUpdateVerifier.Check.Available).info
        assertThrows(PackException::class.java) { v.verifyApk(info, apk.copyOf().also { it[5] = 99 }) }
        assertThrows(PackException::class.java) { v.verifyApk(info, apk + 1) }
    }

    @Test fun perAbiApks() {
        val other = ByteArray(2000) { (it * 7).toByte() }
        val j = """{"format":2,"package":"org.ivanivorontsov.tiho","version_code":4,"version_name":"2.0.0","min_version_code":1,
            "apk_url":"https://github.com/a/b/releases/download/app-v2.0.0/tiho-arm64.apk","sha256":"${Minisign.sha256Hex(apk)}","size":${apk.size},
            "expires":${now + 1000},"cert_sha256":"$cert","gecko_version":"157.0.20260924084938",
            "apks":{"arm64-v8a":{"url":"https://github.com/a/b/releases/download/app-v2.0.0/tiho-arm64.apk","sha256":"${Minisign.sha256Hex(apk)}","size":${apk.size}},
                    "armeabi-v7a":{"url":"https://github.com/a/b/releases/download/app-v2.0.0/tiho-armv7.apk","sha256":"${Minisign.sha256Hex(other)}","size":${other.size}}}}"""
        val r = check(j, installed = 3, tc = "tiho-app 4 2.0.0 timestamp:$now") as AppUpdateVerifier.Check.Available
        assertEquals("157.0.20260924084938", r.info.geckoVersion)
        val v7 = r.info.forAbis(listOf("armeabi-v7a", "armeabi"))
        assertTrue(v7.apkUrl.endsWith("tiho-armv7.apk"))
        v.verifyApk(v7, other)
        assertThrows(PackException::class.java) { v.verifyApk(v7, apk) }
        assertEquals(r.info.apkUrl, r.info.forAbis(listOf("x86_64")).apkUrl) // unknown ABI → top-level (arm64)
        assertTrue(r.info.forAbis(listOf("arm64-v8a")).apkUrl.endsWith("tiho-arm64.apk"))
        // an ABI entry pointing outside GitHub is rejected
        assertThrows(PackException::class.java) { AppUpdateInfo.parse(j.replace("https://github.com/a/b/releases/download/app-v2.0.0/tiho-armv7.apk", "https://evil.example/x.apk")) }
    }

    @Test fun ciKeyOnlyForAppUpdates() {
        val ci = Minisign.parsePublicKey(TrustedKeys.CI)
        assertTrue(TrustedKeys.appKeys.any { it.keyId.contentEquals(ci.keyId) })
        assertFalse(TrustedKeys.keys.any { it.keyId.contentEquals(ci.keyId) })
        assertEquals(3, TrustedKeys.appKeys.size)
    }
}
