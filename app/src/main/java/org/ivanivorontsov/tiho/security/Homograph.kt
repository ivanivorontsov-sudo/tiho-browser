package org.ivanivorontsov.tiho.security

import java.net.IDN

/**
 * IDN / punycode homograph detection for the address bar (Chromium IDN spoof-checker, simplified).
 * Pure JVM (unit-tested).
 */
object Homograph {
    data class Verdict(val suspicious: Boolean, val unicode: String, val ascii: String, val reason: String = "")

    /** Cyrillic / Greek letters that look like Latin ones. */
    private const val CONFUSABLE = "аеорсухіјѕԁһԛԝкмнтвпьгАВЕКМНОРСТУХІЈЅԜοαν" + "ӏɡ"
    private val CYR_TLDS = setOf("рф", "рус", "москва", "дети", "сайт", "онлайн", "орг", "укр", "бел", "қаз", "срб", "мкд", "бг")

    private fun script(c: Char): String = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.LATIN -> "Latn"
        Character.UnicodeScript.CYRILLIC -> "Cyrl"
        Character.UnicodeScript.GREEK -> "Grek"
        Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> ""
        else -> Character.UnicodeScript.of(c.code).name
    }

    fun check(host: String?): Verdict {
        val h = host?.trimEnd('.')?.lowercase() ?: return Verdict(false, "", "")
        val ascii = try { IDN.toASCII(h, IDN.ALLOW_UNASSIGNED) } catch (_: Exception) { return Verdict(true, h, h, "Некорректное имя сайта") }
        val unicode = try { IDN.toUnicode(ascii, IDN.ALLOW_UNASSIGNED) } catch (_: Exception) { h }
        if (!ascii.split('.').any { it.startsWith("xn--") }) return Verdict(false, unicode, ascii)
        val labels = unicode.split('.')
        val tld = labels.last()
        for (label in labels) {
            val scripts = label.map(::script).filter { it.isNotEmpty() }.toSet()
            if (scripts.size > 1) {
                // Cyrillic+Latin in one label is the classic spoof (раypal.com). Japanese-style mixes are rare in RU/EN use.
                return Verdict(true, unicode, ascii, "В адресе смешаны алфавиты (${scripts.joinToString("+")}): возможна подделка")
            }
            if (scripts.any { it !in setOf("Latn", "Cyrl", "Grek") } && label.any { it in CONFUSABLE }) {
                return Verdict(true, unicode, ascii, "Подозрительные символы в адресе")
            }
            // Whole-script confusable: a fully Cyrillic label made only of Latin look-alikes outside Cyrillic TLDs (е.g. «аррӏе.com»).
            if (scripts == setOf("Cyrl") && tld !in CYR_TLDS && label.all { it in CONFUSABLE || it == '-' || it.isDigit() }) {
                return Verdict(true, unicode, ascii, "Кириллический адрес, похожий на латинский")
            }
            if (scripts == setOf("Grek") && label.all { it in CONFUSABLE || it == '-' || it.isDigit() }) {
                return Verdict(true, unicode, ascii, "Греческий адрес, похожий на латинский")
            }
        }
        if (label0Invisible(unicode)) return Verdict(true, unicode, ascii, "Невидимые символы в адресе")
        return Verdict(false, unicode, ascii)
    }

    private fun label0Invisible(s: String) = s.any { it in "\u200b\u200c\u200d\u2060\u00ad\ufeff" }

    /** What the address bar shows: punycode for suspicious hosts, Unicode otherwise. */
    fun display(host: String?): String { val v = check(host); return if (v.suspicious) v.ascii else v.unicode.ifEmpty { host ?: "" } }
}
