package org.ivanivorontsov.tiho.security

import org.ivanivorontsov.tiho.Prefs
import org.json.JSONArray

/** Effective payment-domain matcher: built-in ∪ signed packs (add-only) ∪ user-confirmed domains. */
class Payments(private val prefs: Prefs) {
    @Volatile var matcher = PaymentDomains.Matcher(PaymentDomains.BUILTIN)
        private set

    fun refresh() {
        matcher = PaymentDomains.Matcher(PaymentDomains.BUILTIN + prefs.paymentKnown + prefs.paymentUserDomains)
    }

    fun isPayment(host: String?) = matcher.isPayment(host)

    fun remember(host: String) {
        val h = PaymentDomains.normalizeHost(host) ?: return
        prefs.paymentUserDomains = prefs.paymentUserDomains + h
        refresh()
    }

    /** JS array literal of all payment domains, embedded in injected scripts for the synchronous frame check. */
    fun domainsJson(): String = JSONArray(matcher.domains.sorted()).toString()

    /**
     * Synchronous JS guard (no bridge, no async): true if this frame or ANY ancestor frame is a payment page.
     * Uses location.ancestorOrigins (Chromium) so a 3-DS iframe inside a merchant page is also detected.
     */
    fun guardJs(): String = guardJs(domainsJson())

    companion object {
    fun guardJs(domainsJson: String): String = """
const __tihoPayM=(function(){const D=new Set($domainsJson);
const acs=/(^|[.-])(acs|3ds|3dsecure|3dsec|secure3d|securecode|idcheck|verifiedbyvisa|safekey|mirconnect|threeds)\d*([.-])/;
return(h)=>{h=String(h||'').toLowerCase().replace(/\.$/,'').replace(/^www\./,'');if(!h)return false;const p=h.split('.');
if(p.length>=3&&acs.test(p.slice(0,-2).join('.')+'.'))return true;for(let i=0;i<p.length-1;i++){if(D.has(p.slice(i).join('.')))return true;}return false;};})();
const __tihoPay=(function(){try{if(__tihoPayM(location.hostname))return true;const ao=location.ancestorOrigins;
if(ao)for(let i=0;i<ao.length;i++){try{if(__tihoPayM(new URL(ao[i]).hostname))return true;}catch(e){}}}catch(e){}
try{if(window.top!==window&&__tihoPayM(window.top.location.hostname))return true;}catch(e){}return false;})();
""".trim()
    }
}
