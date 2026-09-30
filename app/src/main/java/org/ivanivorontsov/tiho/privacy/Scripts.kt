package org.ivanivorontsov.tiho.privacy

import android.content.Context
import org.ivanivorontsov.tiho.ext.UserScript
import org.json.JSONArray
import org.json.JSONObject

/** Assembles the document-start scripts injected into every page. */
object Scripts {
    private var privacy = ""
    private var content = ""

    fun init(ctx: Context) {
        privacy = ctx.assets.open("js/privacy.js").bufferedReader().use { it.readText() }
        content = ctx.assets.open("js/content.js").bufferedReader().use { it.readText() }
    }

    /** Main script: privacy shims first (before any page script), then the content script. */
    fun generic(config: JSONObject): String =
        "(function(){'use strict';const C=$config;\n" +
            "try{\n$privacy\ntihoPrivacy(window,C);}catch(e){}\n" +
            "try{\n$content\ntihoContent(window,C);}catch(e){}\n})();"

    /** Scriptlets for one origin (registered only for that origin, runs before page scripts). */
    fun scriptlets(js: String) = "(function(){try{\n$js\n}catch(e){}})();"

    /** Greasemonkey-compatible wrapper for one userscript. */
    fun userscript(s: UserScript, bridge: String, token: String): String {
        val inc = JSONArray(s.includeRegexes).toString()
        val exc = JSONArray(s.excludeRegexes).toString()
        val info = JSONObject().put("script", JSONObject().put("name", s.name).put("version", s.version)
            .put("namespace", s.namespace).put("description", s.description).put("author", s.author))
            .put("scriptHandler", "Тихо").put("version", "1.0").toString()
        return """
(function(){
const url=location.href;
if(!$inc.some(r=>new RegExp(r).test(url))||$exc.some(r=>new RegExp(r).test(url)))return;
if(${s.noframes}&&window!==window.top)return;
const B=window[${JSONObject.quote(bridge)}],T=${JSONObject.quote(token)},ID=${JSONObject.quote(s.id)};
const GM_info=$info;
const GM_getValue=(k,d)=>{try{const v=B.gmGet(T,ID,String(k));return v==null?d:JSON.parse(v);}catch(e){return d;}};
const GM_setValue=(k,v)=>{try{B.gmSet(T,ID,String(k),JSON.stringify(v));}catch(e){}};
const GM_deleteValue=(k)=>{try{B.gmSet(T,ID,String(k),null);}catch(e){}};
const GM_listValues=()=>{try{return JSON.parse(B.gmKeys(T,ID));}catch(e){return [];}};
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
