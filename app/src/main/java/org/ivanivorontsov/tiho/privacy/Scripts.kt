package org.ivanivorontsov.tiho.privacy

import org.ivanivorontsov.tiho.TihoApp
import org.ivanivorontsov.tiho.ext.UserScript
import org.json.JSONArray
import org.json.JSONObject

/** Assembles the document-start scripts injected into pages. */
object Scripts {
    @Volatile private var privacy = ""
    @Volatile private var content = ""
    /** "pack v<N>" when shim JS comes from a signed content pack, "встроенные" otherwise. */
    @Volatile var source = "встроенные"
        private set

    /** Unit tests: inject shim sources without an Application. */
    internal fun setSources(privacyJs: String, contentJs: String) { privacy = privacyJs; content = contentJs }

    /** Loads shim JS: from the signed content pack when allowed («только списки, без кода» off), else from the APK. */
    fun init(app: TihoApp) {
        fun read(name: String): Pair<String, Boolean> {
            app.contentPacks.codeFile("js/$name")?.let { f -> try { return f.readText() to true } catch (_: Exception) {} }
            return app.assets.open("js/$name").bufferedReader().use { it.readText() } to false
        }
        val (p, pp) = read("privacy.js")
        val (c, cp) = read("content.js")
        privacy = p; content = c
        source = if (pp || cp) "пакет v${app.contentPacks.version}" else "встроенные"
    }

    /**
     * Async message channel to the app (WebViewCompat.addWebMessageListener; addJavascriptInterface fallback on old
     * WebView). The object reference and postMessage are captured before any page script runs; every message carries
     * the session token. Replies are matched by id.
     */
    fun rpc(bridge: String, token: String): String = """
const TB=(function(){const o=window[${JSONObject.quote(bridge)}];if(!o)return null;const T=${JSONObject.quote(token)};
const legacy=typeof o.post==='function';const pm=legacy?null:o.postMessage.bind(o);let n=0;const P=new Map();
if(!legacy)o.addEventListener('message',(e)=>{try{const m=JSON.parse(e.data);const f=P.get(m.id);if(f){P.delete(m.id);f(m.r);}}catch(x){}});
const raw=(m)=>{m.t=T;const s=JSON.stringify(m);if(legacy){const r=o.post(s);return r?JSON.parse(r).r:null;}pm(s);return undefined;};
return{send(k,d){try{raw(Object.assign({k:k},d||{}));}catch(e){}},
call(k,d){return new Promise((res)=>{const id=(++n)+'.'+Math.random().toString(36).slice(2);
if(legacy){try{res(raw(Object.assign({k:k,id:id},d||{})));}catch(e){res(null);}return;}
P.set(id,res);try{raw(Object.assign({k:k,id:id},d||{}));}catch(e){P.delete(id);res(null);return;}
setTimeout(()=>{if(P.has(id)){P.delete(id);res(null);}},4000);});}};})();
""".trim()

    /** Main script for normal tabs: payment-frame guard first, then privacy shims, then the content script. */
    fun generic(config: JSONObject, guard: String, bridge: String, token: String): String =
        "(function(){'use strict';const C=$config;\n$guard\n" +
            // Payment page (this frame or any ancestor): none of our shims/cosmetics/bridge, WebRTC relay-only stays.
            "if(__tihoPay){try{\n$privacy\ntihoPrivacy(window,Object.assign({},C,{payMin:true}),null);}catch(e){}return;}\n" +
            "${rpc(bridge, token)}\n" +
            "try{\n$privacy\ntihoPrivacy(window,C,TB);}catch(e){}\n" +
            "try{\n$content\ntihoContent(window,C,TB,__tihoPayM);}catch(e){}\n})();"

    /** «Безопасный режим оплаты» tab: only the WebRTC relay-only shim; no noise, no UA/tz spoofing, no bridge. */
    fun paymentShim(config: JSONObject): String =
        "(function(){'use strict';const C=$config;try{\n$privacy\ntihoPrivacy(window,Object.assign({},C,{payMin:true}),null);}catch(e){}})();"

    /** Scriptlets for one origin (registered only for that origin, runs before page scripts). */
    fun scriptlets(js: String, guard: String) = "(function(){$guard\nif(__tihoPay)return;try{\n$js\n}catch(e){}})();"

    /**
     * Greasemonkey-compatible wrapper for one userscript. The payment guard runs synchronously before anything else:
     * on bank/payment pages (or inside them) the script does not run unless it is trusted for payments with a matching
     * hash (then it is only installed in payment tabs without any bridge).
     */
    fun userscript(s: UserScript, guard: String, bridge: String?, token: String, values: Map<String, String>, inPaymentTab: Boolean): String {
        val inc = JSONArray(s.includeRegexes).toString()
        val exc = JSONArray(s.excludeRegexes).toString()
        val info = JSONObject().put("script", JSONObject().put("name", s.name).put("version", s.version)
            .put("namespace", s.namespace).put("description", s.description).put("author", s.author))
            .put("scriptHandler", "Тихо").put("version", "1.1").put("sha256", s.sha256).toString()
        val tb = if (bridge != null) rpc(bridge, token) else "const TB=null;"
        return """
(function(){
$guard
if(__tihoPay&&!${inPaymentTab && s.runsOnPayment})return;
const url=location.href;
if(!$inc.some(r=>new RegExp(r).test(url))||$exc.some(r=>new RegExp(r).test(url)))return;
if(${s.noframes}&&window!==window.top)return;
$tb
const ID=${JSONObject.quote(s.id)};const V=${JSONObject(values)};
const GM_info=$info;
const GM_getValue=(k,d)=>{try{k=String(k);return Object.prototype.hasOwnProperty.call(V,k)?JSON.parse(V[k]):d;}catch(e){return d;}};
const GM_setValue=(k,v)=>{try{k=String(k);V[k]=JSON.stringify(v);TB&&TB.send('gms',{s:ID,key:k,v:V[k]});}catch(e){}};
const GM_deleteValue=(k)=>{try{k=String(k);delete V[k];TB&&TB.send('gms',{s:ID,key:k,v:null});}catch(e){}};
const GM_listValues=()=>Object.keys(V);
const GM_addStyle=(css)=>{const s=document.createElement('style');s.textContent=css;(document.head||document.documentElement).appendChild(s);return s;};
const GM_openInTab=(u)=>window.open(u,'_blank');
const GM_setClipboard=(t)=>{try{navigator.clipboard.writeText(String(t));}catch(e){}};
const GM_notification=(t)=>{try{console.info('[GM_notification]',typeof t==='object'?t.text:t);}catch(e){}};
const GM_log=console.log.bind(console);
const GM_xmlhttpRequest=(d)=>{const ac=new AbortController();fetch(d.url,{method:d.method||'GET',headers:d.headers,body:d.data,credentials:d.anonymous?'omit':'include',signal:ac.signal}).then(async r=>{const text=await r.text();let json=null;if(d.responseType==='json'){try{json=JSON.parse(text);}catch(e){}}const resp={status:r.status,statusText:r.statusText,responseText:text,response:d.responseType==='json'?json:text,finalUrl:r.url,responseHeaders:[...r.headers].map(h=>h.join(': ')).join('\r\n'),readyState:4};d.onload&&d.onload(resp);d.onloadend&&d.onloadend(resp);}).catch(e=>{d.onerror&&d.onerror(e);d.onloadend&&d.onloadend(e);});return {abort(){ac.abort();}};};
const unsafeWindow=window;
const GM={info:GM_info,getValue:async(k,d)=>GM_getValue(k,d),setValue:async(k,v)=>GM_setValue(k,v),deleteValue:async(k)=>GM_deleteValue(k),listValues:async()=>GM_listValues(),addStyle:GM_addStyle,openInTab:GM_openInTab,setClipboard:GM_setClipboard,notification:GM_notification,xmlHttpRequest:GM_xmlhttpRequest};
const run=()=>{try{
${s.requireCode}
;
${s.code}
}catch(e){console.error('[userscript] '+GM_info.script.name,e);}};
const at=${JSONObject.quote(s.runAt)};
if(at==='document-start')run();
else if(at==='document-end'){if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',run,{once:true});else run();}
else{if(document.readyState==='complete')setTimeout(run,0);else window.addEventListener('load',()=>setTimeout(run,0),{once:true});}
})();"""
    }
}
