"use strict";
// Built-in ТихоЩит: blocks requests to known mobile-ad / tracker hosts (no VPN, no proxy).
const port = browser.runtime.connectNative("tihoshield");
const loaded = {};
let merged = new Set();
let owner = new Map();
let enabled = true;
let listOn = {};
let blocked = 0;
let last = [];
let flushTimer = null;

function hostOf(url) {
  try { return new URL(url).hostname.toLowerCase().replace(/\.$/, ""); } catch (e) { return ""; }
}

function matchHost(host) {
  let h = host;
  while (h) {
    if (merged.has(h)) return { rule: h, list: owner.get(h) || "" };
    const i = h.indexOf(".");
    if (i < 0) break;
    h = h.slice(i + 1);
  }
  return null;
}

function rebuild() {
  merged = new Set();
  owner = new Map();
  for (const [id, set] of Object.entries(loaded)) {
    if (listOn[id] === false) continue;
    for (const d of set) {
      if (!merged.has(d)) { merged.add(d); owner.set(d, id); }
    }
  }
}

function flush() {
  flushTimer = null;
  try { port.postMessage({ type: "stats", blocked: blocked, last: last.slice(0, 30) }); } catch (e) {}
}

function scheduleFlush() {
  if (flushTimer) return;
  flushTimer = setTimeout(flush, 400);
}

try {
  browser.webRequest.onBeforeRequest.addListener((d) => {
    if (!enabled || !merged.size) return {};
    const host = hostOf(d.url);
    if (!host) return {};
    const hit = matchHost(host);
    if (!hit) return {};
    blocked++;
    last.unshift({ host: host, rule: hit.rule, list: hit.list, t: Date.now() });
    if (last.length > 40) last.length = 40;
    scheduleFlush();
    return { cancel: true };
  }, { urls: ["<all_urls>"] }, ["blocking"]);
} catch (e) {
  port.postMessage({ type: "error", message: String(e) });
}

async function loadList(id) {
  if (loaded[id]) return;
  try {
    const text = await (await fetch(browser.runtime.getURL("lists/" + id + ".txt"))).text();
    const s = new Set();
    for (const raw of text.split("\n")) {
      const line = raw.trim().toLowerCase();
      if (!line || line.charAt(0) === "#" || line.charAt(0) === "!" || line.charAt(0) === "[") continue;
      if (line.indexOf(".") < 0) continue;
      s.add(line);
    }
    loaded[id] = s;
  } catch (e) {
    loaded[id] = new Set();
  }
}

port.onMessage.addListener((m) => {
  if (!m) return;
  if (m.type === "config") {
    enabled = m.enabled !== false;
    listOn = m.lists || {};
    const ids = Object.keys(listOn);
    Promise.all(ids.map(loadList)).then(() => {
      rebuild();
      const sizes = {};
      for (const id of ids) sizes[id] = (loaded[id] && loaded[id].size) || 0;
      port.postMessage({ type: "ready", sizes: sizes, merged: merged.size, blocked: blocked, last: last.slice(0, 30) });
    });
  } else if (m.type === "reset") {
    blocked = 0; last = []; flush();
  }
});
port.postMessage({ type: "hello" });
