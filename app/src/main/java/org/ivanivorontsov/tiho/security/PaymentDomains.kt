package org.ivanivorontsov.tiho.security

import org.json.JSONArray
import org.json.JSONObject

/**
 * «Безопасный режим оплаты»: which sites are banks / payment pages.
 * Effective list = built-in base ∪ signed content pack ∪ domains the user confirmed. Updates can only ADD domains:
 * the built-in list is compiled into the APK and a pack cannot remove entries from it. Pure JVM (unit-tested).
 */
object PaymentDomains {
    /** Base list: Russian banks & payment systems, global gateways and 3-D Secure / anti-fraud infrastructure. */
    val BUILTIN: Set<String> = setOf(
        // RU banks
        "sberbank.ru", "sber.ru", "sberbank.com", "sberbusiness.ru", "tinkoff.ru", "tbank.ru", "tinkoffbank.ru",
        "vtb.ru", "vtb24.ru", "alfabank.ru", "alfa-bank.ru", "alfa.me", "gazprombank.ru", "gpb.ru", "raiffeisen.ru",
        "rshb.ru", "psbank.ru", "open.ru", "sovcombank.ru", "halvacard.ru", "mkb.ru", "pochtabank.ru", "rosbank.ru",
        "uralsib.ru", "akbars.ru", "bspb.ru", "homecredit.ru", "otpbank.ru", "rencredit.ru", "unicreditbank.ru",
        "mtsbank.ru", "bank.domrf.ru", "finance.ozon.ru", "bank.ozon.ru", "wb-bank.ru", "absolutbank.ru", "rgsbank.ru",
        "zenit.ru", "novikom.ru", "lockobank.ru", "ubrr.ru", "skbbank.ru", "sinara.ru", "mspbank.ru",
        // RU payment systems / gateways / SBP
        "nspk.ru", "sbp.nspk.ru", "qr.nspk.ru", "mironline.ru", "mirconnect.ru", "yoomoney.ru", "yookassa.ru", "yoomoney.com",
        "money.yandex.ru", "pay.yandex.ru", "qiwi.com", "cloudpayments.ru", "cloudpayments.kz", "cloudpayments.eu",
        "robokassa.ru", "robokassa.com", "payanyway.ru", "paymaster.ru", "unitpay.ru", "payselection.net", "platron.ru",
        "best2pay.net", "assist.ru", "uniteller.ru", "payler.com", "pay.mts.ru", "money.mail.ru", "payonline.ru",
        "rbk.money", "paykeeper.ru", "tinkoff-pay.ru", "sberpay.ru", "pay.vk.com", "vkpay.io", "dengi.mail.ru",
        // CIS
        "kaspi.kz", "halykbank.kz", "homebank.kz", "belarusbank.by", "priorbank.by",
        // Global gateways / wallets
        "paypal.com", "paypal.me", "stripe.com", "stripe.network", "stripe.dev", "checkout.stripe.com", "pay.google.com",
        "payments.google.com", "adyen.com", "adyenpayments.com", "braintreegateway.com", "braintree-api.com", "klarna.com",
        "afterpay.com", "worldpay.com", "checkout.com", "squareup.com", "square.link", "pay.amazon.com", "payments.amazon.com",
        "alipay.com", "revolut.com", "wise.com", "payu.com", "payu.ru", "2checkout.com", "authorize.net", "mollie.com",
        "paddle.com", "fastspring.com", "skrill.com", "neteller.com", "paysafe.com", "airwallex.com", "razorpay.com",
        // 3-D Secure / card schemes / ACS providers
        "cardinalcommerce.com", "arcot.com", "securecode.com", "3dsecure.io", "wlp-acs.com", "modirum.com", "netcetera.com",
        "3ds.mastercard.com", "idcheck.mastercard.com", "verified.visa.com", "secure.visa.com", "americanexpress.com",
        "unionpaysecure.com", "safekey.americanexpress.com", "rsa3dsauth.com", "securesuite.co.uk", "gpayments.com",
    )

    /**
     * Anti-fraud / 3-DS services that ad/privacy lists often block but banks require
     * (ThreatMetrix, Cardinal, Arcot, iovation, Kount, Forter, Riskified, Sift, Stripe Radar…).
     * Requests to them are never blocked on payment pages (built-in «payment-unbreak»).
     */
    val ANTI_FRAUD: Set<String> = setOf(
        "online-metrix.net", "threatmetrix.com", "cardinalcommerce.com", "arcot.com", "3dsecure.io", "wlp-acs.com",
        "iesnare.com", "iovation.com", "kount.net", "kount.com", "kaptcha.com", "forter.com", "signifyd.com",
        "riskified.com", "sift.com", "siftscience.com", "stripe.network", "m.stripe.com", "seon.io", "nethone.io",
        "fingerprint.com", "fpjs.io", "geo.ddc.paypal.com", "c.paypal.com", "b.stats.paypal.com", "t.paypal.com",
        "acs.sbrf.ru", "mpi.sberbank.ru", "ds.nspk.ru",
    )

    private val ACS_LABEL = Regex("(^|-)(acs|3ds|3dsecure|3dsec|secure3d|securecode|idcheck|verifiedbyvisa|safekey|mirconnect|threeds)\\d*(-|$)")

    fun normalizeHost(host: String?): String? {
        val h = host?.trim()?.trimEnd('.')?.lowercase() ?: return null
        if (h.isEmpty() || h.contains('/') || h.contains(' ')) return null
        return h.removePrefix("www.")
    }

    fun suffixMatch(host: String, domains: Set<String>): Boolean {
        var h = host
        while (true) {
            if (h in domains) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    /** 3-D Secure ACS host heuristic: a host label like acs, acs2, 3ds, 3dsecure, securecode, idcheck… */
    fun looksLikeAcs(host: String): Boolean {
        val labels = host.split('.')
        if (labels.size < 3) return false // "acs.ru"-like registrable domains are not ACS servers by themselves
        return labels.dropLast(2).any { ACS_LABEL.containsMatchIn(it) }
    }

    fun isAntiFraud(host: String?): Boolean {
        val h = normalizeHost(host) ?: return false
        return suffixMatch(h, ANTI_FRAUD) || looksLikeAcs(h)
    }

    /** Parses payment-domains.json from a content pack: {"version":N,"domains":[…],"anti_fraud":[…]}. Invalid entries are dropped. */
    fun parsePack(json: String): Set<String> {
        val o = JSONObject(json)
        val arr = o.optJSONArray("domains") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { normalizeHost(arr.optString(it)) }
            .filter { it.contains('.') && it.length <= 253 && Regex("^[a-z0-9.-]+$").matches(it) }
            .toSet()
    }

    /** Add-only merge: whatever an update contains, every built-in and previously known domain stays. */
    fun merge(builtin: Set<String>, previous: Set<String>, update: Set<String>): Set<String> = builtin + previous + update

    class Matcher(val domains: Set<String>) {
        fun isPayment(host: String?): Boolean {
            val h = normalizeHost(host) ?: return false
            return suffixMatch(h, domains) || looksLikeAcs(h)
        }
    }

    /**
     * Content heuristic (signals reported by the page scanner): card-number autocomplete, CVC field, card iframes,
     * PaymentRequest. One strong signal or two weak ones → «Похоже на оплату».
     */
    val STRONG_SIGNALS = setOf("cc-number", "cc-csc", "cc-exp", "payment-request", "payment-iframe", "card-cvc")
    val WEAK_SIGNALS = setOf("card-number-name", "cc-name", "card-holder", "pay-button", "card-exp-name")

    fun isLikelyPayment(signals: Collection<String>): Boolean {
        val s = signals.toSet()
        return s.any { it in STRONG_SIGNALS } || s.count { it in WEAK_SIGNALS } >= 2
    }
}
