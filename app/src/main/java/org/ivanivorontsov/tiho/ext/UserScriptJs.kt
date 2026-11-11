package org.ivanivorontsov.tiho.ext

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the content-script registration for the built-in WebExtension. Userscripts run in the extension's
 * isolated content-script sandbox (Xray: the page cannot tamper with GM_* functions); unsafeWindow is the page window.
 * Pure JVM (unit-tested; generated code is syntax-checked with node in tests when available).
 */
object UserScriptJs {
    private val MATCH_RE = Regex("^(\\*|https?|wss?|ftp|file)://(\\*|\\*\\.[^/*]+|[^/*]+)/.*$")

    /** True if every @match is a valid WebExtension match pattern (then Gecko can pre-filter); else we use the catch-all pattern. */
    fun nativeMatches(s: UserScript): List<String>? {
        if (s.includes.isNotEmpty() || s.matches.isEmpty()) return null
        val m = s.matches.map { if (it == "<all_urls>") "*://*/*" else it }
        return if (m.all { MATCH_RE.matches(it) }) m else null
    }

    /**
     * Runs before any user code in the shared content-script sandbox (every wrapper starts with it, so the very first
     * code executed is always this): pins the genuine sendNativeMessage so a later script cannot hook it to steal tokens.
     */
    val PRELUDE = """var __tihoSnm=(function(){try{if(typeof __tihoSnm==="function")return __tihoSnm;}catch(e){}
const r=browser.runtime;const f=r.sendNativeMessage.bind(r);
try{Object.defineProperty(globalThis,"__tihoSnm",{value:f,writable:false,configurable:false});}catch(e){}
try{Object.defineProperty(r,"sendNativeMessage",{value:r.sendNativeMessage,writable:false,configurable:false});}catch(e){}
try{Object.defineProperty(globalThis,"browser",{value:browser,writable:false,configurable:false});}catch(e){}
return f;})();""".trimIndent()

    fun runAt(r: String) = when (r) { "document-start" -> "document_start"; "document-idle" -> "document_idle"; else -> "document_end" }

    /** Exclude patterns covering each payment base domain and all its subdomains. */
    fun paymentExcludes(domains: Collection<String>): List<String> = domains.sorted().map { "*://*.$it/*" }

    fun wrapper(s: UserScript, guardJs: String, trusted: Boolean): String {
        val inc = JSONArray(s.includeRegexes).toString()
        val exc = JSONArray(s.excludeRegexes).toString()
        val info = JSONObject().put("script", JSONObject().put("name", s.name).put("version", s.version).put("namespace", s.namespace)
            .put("description", s.description)).put("scriptHandler", "Тихо").put("version", "2.0").toString()
        return """
$PRELUDE
(function(){"use strict";
$guardJs
if(!${trusted}&&__tihoPay)return;
const __u=location.href;const __inc=$inc,__exc=$exc;
if(!__inc.some(r=>new RegExp(r).test(__u))||__exc.some(r=>new RegExp(r).test(__u)))return;
${if (s.noframes) "if(window.top!==window)return;" else ""}
const __id=${JSONObject.quote(s.id)};const __vals=/*__TIHO_VALUES__*/{};
const __tok=${JSONObject.quote(ScriptTokens.tokenFor(s.id))};const __snm=__tihoSnm;
const __send=(m)=>{try{m.tok=__tok;__snm("tiho",m).catch(()=>{});}catch(e){}};
const GM_info=$info;
function GM_getValue(k,d){return Object.prototype.hasOwnProperty.call(__vals,k)?JSON.parse(__vals[k]):d;}
function GM_setValue(k,v){const s=JSON.stringify(v);__vals[k]=s;__send({type:"gm",id:__id,key:String(k),value:s});}
function GM_deleteValue(k){delete __vals[k];__send({type:"gm",id:__id,key:String(k),value:null});}
function GM_listValues(){return Object.keys(__vals);}
function GM_addStyle(css){const e=document.createElement("style");e.textContent=css;(document.head||document.documentElement).appendChild(e);return e;}
function GM_log(){console.log.apply(console,arguments);}
const unsafeWindow=window.wrappedJSObject||window;
const GM={info:GM_info,getValue:(k,d)=>Promise.resolve(GM_getValue(k,d)),setValue:(k,v)=>Promise.resolve(GM_setValue(k,v)),
deleteValue:(k)=>Promise.resolve(GM_deleteValue(k)),listValues:()=>Promise.resolve(GM_listValues()),addStyle:(c)=>Promise.resolve(GM_addStyle(c))};
try{
${s.requireCode}
;
${s.code}
}catch(e){console.error("[\u0422ихо userscript "+GM_info.script.name+"]",e);}
})();
""".trim()
    }

    fun styleWrapper(s: UserStyle, guardJs: String, trusted: Boolean): String {
        val inc = JSONArray(s.includeRegexes).toString()
        val exc = JSONArray(s.excludeRegexes).toString()
        return """
$PRELUDE
(function(){"use strict";
$guardJs
if(!${trusted}&&__tihoPay)return;
const __u=location.href;const __inc=$inc,__exc=$exc;
if((__inc.length&&!__inc.some(r=>new RegExp(r).test(__u)))||__exc.some(r=>new RegExp(r).test(__u)))return;
try{
const e=document.createElement("style");
e.setAttribute("data-tiho-style",${JSONObject.quote(s.id)});
e.textContent=${JSONObject.quote(s.css)};
(document.documentElement||document.head).appendChild(e);
}catch(ex){console.error("[Тихо style]",ex);}
})();
""".trim()
    }

    private fun styleMatches(s: UserStyle): List<String> {
        val m = s.matches.map { if (it == "<all_urls>") "*://*/*" else it }
        return if (m.isNotEmpty() && m.all { MATCH_RE.matches(it) } && s.includes.isEmpty() && s.extraRegexes.isEmpty()) m else listOf("*://*/*")
    }

    /** Config message for the built-in extension's native port. */
    fun config(scripts: List<UserScript>, values: (String) -> Map<String, String>, paymentDomains: Collection<String>, guardJs: String, payDetect: Boolean, styles: List<UserStyle> = emptyList()): JSONObject {
        val arr = JSONArray()
        for (s in scripts) {
            if (!s.enabled || s.tampered || !UserScriptPin.intact(s)) continue
            val trusted = s.runsOnPayment
            arr.put(JSONObject().put("id", s.id).put("code", wrapper(s, guardJs, trusted))
                .put("matches", JSONArray(nativeMatches(s) ?: listOf("*://*/*"))).put("runAt", runAt(s.runAt))
                .put("allFrames", !s.noframes).put("trusted", trusted).put("values", JSONObject(values(s.id) as Map<*, *>)))
        }
        for (s in styles) {
            if (!s.enabled || s.tampered) continue
            if (s.sha256.isNotEmpty() && s.sha256 != UserScriptPin.sha256(s.code)) continue
            val trusted = s.runsOnPayment
            arr.put(JSONObject().put("id", s.id).put("code", styleWrapper(s, guardJs, trusted))
                .put("matches", JSONArray(styleMatches(s))).put("runAt", "document_start")
                .put("allFrames", true).put("trusted", trusted).put("values", JSONObject()))
        }
        return JSONObject().put("type", "config").put("scripts", arr)
            .put("paymentExclude", JSONArray(paymentExcludes(paymentDomains))).put("payDetect", payDetect)
            .put("prelude", PRELUDE).put("payToken", ScriptTokens.tokenFor(ScriptTokens.PAY_ID))
    }
}
