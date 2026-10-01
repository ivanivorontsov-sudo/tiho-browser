"use strict";
// Тихо built-in extension (privileged, bundled in the APK, loaded with ensureBuiltIn).
// The app pushes the configuration over a native port; we (re)register content scripts with
// excludeMatches = payment domains, so neither userscripts nor our own injections run there.
const port = browser.runtime.connectNative("tiho");
let registered = [];
let cfg = null;

async function unregisterAll() {
  const old = registered; registered = [];
  for (const r of old) { try { await r.unregister(); } catch (e) {} }
}

async function apply(c) {
  cfg = c;
  await unregisterAll();
  const errors = [];
  for (const s of c.scripts || []) {
    try {
      const opts = {
        matches: s.matches && s.matches.length ? s.matches : ["*://*/*"],
        js: [{ code: s.code.replace("/*__TIHO_VALUES__*/{}", JSON.stringify(s.values || {})) }],
        runAt: s.runAt || "document_end",
        allFrames: !!s.allFrames,
      };
      if (!s.trusted && c.paymentExclude && c.paymentExclude.length) opts.excludeMatches = c.paymentExclude;
      registered.push(await browser.contentScripts.register(opts));
    } catch (e) { errors.push(s.id + ": " + e); }
  }
  if (c.payDetect) {
    try {
      registered.push(await browser.contentScripts.register({
        matches: ["https://*/*"], js: [{ file: "paydetect.js" }], runAt: "document_idle", allFrames: true,
        excludeMatches: c.paymentExclude && c.paymentExclude.length ? c.paymentExclude : undefined,
      }));
    } catch (e) { errors.push("paydetect: " + e); }
  }
  port.postMessage({ type: "applied", count: registered.length, errors });
}

port.onMessage.addListener((m) => {
  if (m && m.type === "config") apply(m).catch((e) => port.postMessage({ type: "applied", count: 0, errors: [String(e)] }));
});
port.postMessage({ type: "ready" });
