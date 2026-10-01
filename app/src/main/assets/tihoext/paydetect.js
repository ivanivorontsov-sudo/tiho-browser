"use strict";
// Payment-form heuristic (runs only outside known payment domains). Reports signals to the app, which decides
// whether to offer «Похоже на страницу оплаты — открыть в защищённой вкладке?». Reads no field values.
(function () {
  if (window.__tihoPD) return; window.__tihoPD = 1;
  function scan() {
    const s = new Set();
    const els = document.querySelectorAll("input,select,iframe,[autocomplete]");
    for (let i = 0; i < els.length && i < 3000; i++) {
      const el = els[i];
      const ac = (el.getAttribute("autocomplete") || "").toLowerCase();
      if (ac.includes("cc-number")) s.add("cc-number");
      if (ac.includes("cc-csc")) s.add("cc-csc");
      if (ac.includes("cc-exp")) s.add("cc-exp");
      if (ac.includes("cc-name")) s.add("cc-name");
      const n = ((el.name || "") + " " + (el.id || "") + " " + (el.getAttribute("placeholder") || "") + " " +
        (el.getAttribute("aria-label") || "")).toLowerCase();
      if (/card.?num|cardnumber|pan\b|номер карты/.test(n)) s.add("card-number-name");
      if (/\b(cvc|cvv|cvn|csc|cvc2|cvv2)\b/.test(n)) s.add("card-cvc");
      if (/(exp(iry|iration)?|срок)/.test(n) && /(card|cc|карт)/.test(n)) s.add("card-exp-name");
      if (/(holder|владел)/.test(n)) s.add("card-holder");
      if (el.tagName === "IFRAME") {
        const src = (el.getAttribute("src") || "").toLowerCase();
        if (/(js\.stripe\.com|braintreegateway|adyen|checkout\.com|cloudpayments|yoomoney|yookassa|tinkoff|payment|securepay|3ds)/.test(src)) s.add("payment-iframe");
      }
    }
    return Array.from(s).sort();
  }
  let sent = "";
  function check() {
    const sig = scan(); const k = sig.join(",");
    if (sig.length && k !== sent) {
      sent = k;
      browser.runtime.sendNativeMessage("tiho", { type: "pay", url: location.href, top: window === window.top, signals: sig }).catch(() => {});
    }
  }
  setTimeout(check, 800); setTimeout(check, 3000);
  let t = 0;
  new MutationObserver(() => { clearTimeout(t); t = setTimeout(check, 1200); })
    .observe(document.documentElement, { childList: true, subtree: true });
})();
