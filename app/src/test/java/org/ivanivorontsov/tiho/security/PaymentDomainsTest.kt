package org.ivanivorontsov.tiho.security

import org.junit.Assert.*
import org.junit.Test

class PaymentDomainsTest {
    private val m = PaymentDomains.Matcher(PaymentDomains.BUILTIN)

    @Test fun builtinBanksAndProviders() {
        for (h in listOf("online.sberbank.ru", "www.tbank.ru", "online.vtb.ru", "click.alfabank.ru", "qr.nspk.ru", "yoomoney.ru",
                "checkout.stripe.com", "checkout.stripe.dev", "www.paypal.com", "pay.google.com", "widget.cloudpayments.ru", "auth.robokassa.ru"))
            assertTrue(h, m.isPayment(h))
    }

    @Test fun notPayment() {
        for (h in listOf("example.com", "news.ru", "sberbank.ru.evil.com", "notpaypal.com", "stripe.com.example.org", null, ""))
            assertFalse("$h", m.isPayment(h))
    }

    @Test fun acsHeuristic() {
        assertTrue(m.isPayment("acs2.somebank.example"))
        assertTrue(m.isPayment("3ds.processing.example"))
        assertTrue(m.isPayment("idcheck-acs.issuer.example"))
        assertFalse(m.isPayment("acs.example")) // registrable domain only
        assertFalse(m.isPayment("access.example.com"))
        assertFalse(m.isPayment("mail.acs.com"))
    }

    @Test fun antiFraud() {
        assertTrue(PaymentDomains.isAntiFraud("h.online-metrix.net"))
        assertTrue(PaymentDomains.isAntiFraud("centinelapi.cardinalcommerce.com"))
        assertFalse(PaymentDomains.isAntiFraud("google-analytics.com"))
    }

    @Test fun packParsingDropsJunk() {
        val s = PaymentDomains.parsePack("""{"version":3,"domains":["NewBank.Example","www.pay.test.","bad host","x","a/b","ok-pay.example"]}""")
        assertEquals(setOf("newbank.example", "pay.test", "ok-pay.example"), s)
    }

    @Test fun mergeIsAddOnly() {
        val prev = setOf("old.example")
        val merged = PaymentDomains.merge(PaymentDomains.BUILTIN, prev, setOf("new.example"))
        assertTrue(merged.containsAll(PaymentDomains.BUILTIN))
        assertTrue("old.example" in merged && "new.example" in merged)
        // An empty / hostile update can't remove anything.
        assertTrue(PaymentDomains.merge(PaymentDomains.BUILTIN, merged, emptySet()).containsAll(merged))
    }

    @Test fun heuristicSignals() {
        assertTrue(PaymentDomains.isLikelyPayment(listOf("cc-number")))
        assertTrue(PaymentDomains.isLikelyPayment(listOf("cc-name", "pay-button")))
        assertFalse(PaymentDomains.isLikelyPayment(listOf("pay-button")))
        assertFalse(PaymentDomains.isLikelyPayment(listOf("pay-button", "pay-button")))
        assertFalse(PaymentDomains.isLikelyPayment(emptyList()))
    }
}
