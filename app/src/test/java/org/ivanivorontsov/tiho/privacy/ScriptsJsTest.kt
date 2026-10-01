package org.ivanivorontsov.tiho.privacy

import org.ivanivorontsov.tiho.ext.UserScript
import org.ivanivorontsov.tiho.ext.UserScriptPin
import org.ivanivorontsov.tiho.security.PaymentDomains
import org.ivanivorontsov.tiho.security.Payments
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Generated page scripts must be valid JS, and the JS payment guard must agree with the Kotlin matcher. Needs node. */
class ScriptsJsTest {
    private val assets = File(System.getProperty("tiho.assets") ?: "src/main/assets")
    private val node: String? = listOf("/usr/bin/node", "/usr/local/bin/node").firstOrNull { File(it).canExecute() }
        ?: try { if (ProcessBuilder("node", "--version").start().waitFor() == 0) "node" else null } catch (_: Exception) { null }

    private fun run(vararg args: String): Pair<Int, String> {
        val p = ProcessBuilder(node!!, *args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(60, TimeUnit.SECONDS))
        return p.exitValue() to out
    }

    private fun checkSyntax(name: String, js: String) {
        val f = File.createTempFile("tiho-$name", ".js").apply { writeText(js); deleteOnExit() }
        val (rc, out) = run("--check", f.path)
        assertEquals("$name: $out", 0, rc)
    }

    private val domains = JSONArray(PaymentDomains.BUILTIN.sorted()).toString()
    private val guard = Payments.guardJs(domains)

    @Test fun generatedScriptsParse() {
        assumeTrue(node != null)
        Scripts.setSources(File(assets, "js/privacy.js").readText(), File(assets, "js/content.js").readText())
        val c = JSONObject().put("webrtc", 1).put("noise", true)
        checkSyntax("generic", Scripts.generic(c, guard, "tihoBridge", "tok\"en'"))
        checkSyntax("payment", Scripts.paymentShim(c))
        checkSyntax("scriptlets", Scripts.scriptlets("console.log(1)", guard))
        val us = UserScriptPin.pin(UserScript.parse("// ==UserScript==\n// @name x\n// @match https://*/*\n// @require https://a.b/l.js\n// ==/UserScript==\nGM_setValue('a',1);", "https://a.b/x.user.js"),
            mapOf("https://a.b/l.js" to "var L=1;"))
        checkSyntax("userscript", Scripts.userscript(us, guard, "tihoBridge", "t", mapOf("a" to "1"), false))
        checkSyntax("userscript-pay", Scripts.userscript(us, guard, null, "t", emptyMap(), true))
        checkSyntax("rpc", Scripts.rpc("b", "t"))
    }

    @Test fun jsGuardMatchesKotlin() {
        assumeTrue(node != null)
        val m = PaymentDomains.Matcher(PaymentDomains.BUILTIN)
        val hosts = listOf("online.sberbank.ru", "www.sberbank.ru", "checkout.stripe.dev", "acs2.bank.example", "3ds.pay.example.com",
            "example.com", "sberbank.ru.evil.com", "access.example.com", "mail.acs.com", "acs.example", "yoomoney.ru", "pay.google.com", "news.ru")
        val js = guard.substringBefore("const __tihoPay=") +
            "\nconst H=${JSONArray(hosts)};console.log(JSON.stringify(H.map(h=>__tihoPayM(h))));"
        val f = File.createTempFile("tiho-guard", ".js").apply { writeText(js); deleteOnExit() }
        val (rc, out) = run(f.path)
        assertEquals(out, 0, rc)
        val got = JSONArray(out.trim().lines().last())
        hosts.forEachIndexed { i, h -> assertEquals(h, m.isPayment(h), got.getBoolean(i)) }
    }

    @Test fun guardChecksAncestors() {
        assumeTrue(node != null)
        val js = "const location={hostname:'merchant.example',ancestorOrigins:['https://checkout.stripe.com']};const window={};window.top=window;\n" +
            guard + "\nconsole.log(__tihoPay);"
        val f = File.createTempFile("tiho-guard2", ".js").apply { writeText(js); deleteOnExit() }
        assertEquals("true", run(f.path).second.trim())
    }
}
