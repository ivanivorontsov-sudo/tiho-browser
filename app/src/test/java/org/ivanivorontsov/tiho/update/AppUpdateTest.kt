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
}
