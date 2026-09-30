/* Тихо — content script: cosmetic filtering (adblock-rust), procedural filters, cookie-banner
 * auto-reject, YouTube ad skipping, force-dark, ping stripping. Runs at document-start. */
const tihoContent = function (W, C) {
  const D = W.document;
  const B = W[C.bridge];
  const host = W.location.hostname;
  let info = {};
  if (B && /^https?:$/.test(W.location.protocol)) {
    try { info = JSON.parse(B.cosmetic(C.token, W.location.href) || '{}'); } catch (e) { info = {}; }
  }
  const shieldsOn = !info.off;
  const addCss = (css) => {
    if (!css) return;
    try { const s = new W.CSSStyleSheet(); s.replaceSync(css); D.adoptedStyleSheets = [...D.adoptedStyleSheets, s]; } catch (e) {}
    const put = () => { try { const st = D.createElement('style'); st.textContent = css; (D.head || D.documentElement).appendChild(st); } catch (e) {} };
    if (D.documentElement) put(); else D.addEventListener('DOMContentLoaded', put, { once: true });
  };
  const onReady = (f) => { if (D.readyState === 'loading') D.addEventListener('DOMContentLoaded', f, { once: true }); else f(); };
  const throttle = (f, ms) => { let t = 0; return () => { if (t) return; t = setTimeout(() => { t = 0; f(); }, ms); }; };

  // ---------------------------------------------------------------- scriptlets fallback (frames / first load)
  if (shieldsOn && info.js) { try { (0, eval)(info.js); } catch (e) {} }

  // ---------------------------------------------------------------- cosmetic filtering
  if (shieldsOn && C.adblock) {
    addCss(info.css);
    // Generic class/id rules: collect classes & ids and ask the engine which are ads (Brave's approach).
    if (!info.gh && B) {
      const seenC = new Set(), seenI = new Set();
      let newC = [], newI = [];
      const collect = (el) => {
        if (el.nodeType !== 1) return;
        if (el.id && !seenI.has(el.id)) { seenI.add(el.id); newI.push(el.id); }
        const cl = el.classList;
        if (cl) for (let i = 0; i < cl.length; i++) { const c = cl[i]; if (!seenC.has(c)) { seenC.add(c); newC.push(c); } }
      };
      const flush = () => {
        if (!newC.length && !newI.length) return;
        const c = newC, i = newI; newC = []; newI = [];
        try {
          const r = B.hidden(C.token, W.location.href, c.join(' '), i.join(' '));
          if (r) addCss(r.split('\n').filter(Boolean).map((s) => s + '{display:none!important}').join('\n'));
        } catch (e) {}
      };
      const flushSoon = throttle(flush, 60);
      const scan = (root) => { collect(root); const all = root.querySelectorAll ? root.querySelectorAll('[class],[id]') : []; for (let k = 0; k < all.length; k++) collect(all[k]); };
      const mo = new W.MutationObserver((ms) => {
        for (const m of ms) {
          if (m.type === 'attributes') collect(m.target);
          else for (const n of m.addedNodes) if (n.nodeType === 1) scan(n);
        }
        flushSoon();
      });
      const start = () => { scan(D.documentElement); flush(); mo.observe(D.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] }); };
      if (D.documentElement) start(); else D.addEventListener('readystatechange', start, { once: true });
    }
    // Procedural / action filters (uBO syntax: :has-text, :upward, :xpath, :remove(), :style() …)
    let proc = [];
    try { proc = (info.proc || []).map((s) => JSON.parse(s)); } catch (e) { proc = []; }
    if (proc.length) {
      const textMatch = (arg) => {
        const m = /^\/(.*)\/([imsu]*)$/.exec(arg);
        if (m) { const re = new RegExp(m[1], m[2]); return (s) => re.test(s); }
        return (s) => s.includes(arg);
      };
      const run = (ops) => {
        let nodes = null;
        for (const op of ops) {
          const a = op.arg;
          switch (op.type) {
            case 'css-selector':
              if (nodes === null) nodes = Array.from(D.querySelectorAll(a));
              else nodes = nodes.flatMap((n) => Array.from(n.querySelectorAll(/^\s*[>+~]/.test(a) ? ':scope ' + a : a)));
              break;
            case 'has-text': { const t = textMatch(a); nodes = (nodes || Array.from(D.querySelectorAll('*'))).filter((n) => t(n.textContent || '')); break; }
            case 'min-text-length': nodes = (nodes || []).filter((n) => (n.textContent || '').length >= parseInt(a, 10)); break;
            case 'matches-path': { const t = textMatch(a); if (!t(W.location.pathname + W.location.search)) nodes = []; break; }
            case 'matches-css': case 'matches-css-before': case 'matches-css-after': {
              const pseudo = op.type === 'matches-css' ? null : (op.type.endsWith('before') ? '::before' : '::after');
              const i = a.indexOf(':'); const prop = a.slice(0, i).trim(); const t = textMatch(a.slice(i + 1).trim());
              nodes = (nodes || []).filter((n) => t(W.getComputedStyle(n, pseudo).getPropertyValue(prop)));
              break;
            }
            case 'matches-attr': {
              const m = /^"?([^"=]+)"?(?:="?(.*?)"?)?$/.exec(a) || [];
              const nt = textMatch(m[1] || ''); const vt = m[2] !== undefined ? textMatch(m[2]) : null;
              nodes = (nodes || []).filter((n) => Array.from(n.attributes).some((at) => nt(at.name) && (!vt || vt(at.value))));
              break;
            }
            case 'upward': {
              const k = parseInt(a, 10);
              nodes = (nodes || []).map((n) => { if (!isNaN(k)) { let p = n; for (let j = 0; j < k && p; j++) p = p.parentElement; return p; } return n.parentElement && n.parentElement.closest(a); }).filter(Boolean);
              break;
            }
            case 'xpath': {
              const out = [];
              for (const ctx of (nodes || [D])) {
                const r = D.evaluate(a, ctx, null, 7, null);
                for (let j = 0; j < r.snapshotLength; j++) out.push(r.snapshotItem(j));
              }
              nodes = out; break;
            }
            default: return [];
          }
          if (!nodes.length) return [];
        }
        return nodes || [];
      };
      const applyAll = () => {
        for (const f of proc) {
          let els = [];
          try { els = run(f.selector); } catch (e) { continue; }
          for (const el of els) {
            const act = f.action;
            try {
              if (!act) el.style.setProperty('display', 'none', 'important');
              else if (act.type === 'remove') el.remove();
              else if (act.type === 'style') el.style.cssText += ';' + act.arg;
              else if (act.type === 'remove-attr') act.arg.split('|').forEach((x) => el.removeAttribute(x.trim()));
              else if (act.type === 'remove-class') act.arg.split('|').forEach((x) => el.classList.remove(x.trim()));
            } catch (e) {}
          }
        }
      };
      onReady(() => {
        applyAll();
        const again = throttle(applyAll, 300);
        new W.MutationObserver(again).observe(D.documentElement, { childList: true, subtree: true });
      });
    }
  }

  // ---------------------------------------------------------------- hyperlink auditing (<a ping>)
  if (C.pings) {
    D.addEventListener('click', (e) => { const a = e.target && e.target.closest && e.target.closest('a[ping]'); if (a) a.removeAttribute('ping'); }, true);
  }

  if (W !== W.top && !C.youtube) return;

  // ---------------------------------------------------------------- cookie banners: auto-reject
  if (shieldsOn && C.cookies && W === W.top) {
    const REJECT = [
      '#onetrust-reject-all-handler', '.ot-pc-refuse-all-handler', '#CybotCookiebotDialogBodyButtonDecline',
      '#CybotCookiebotDialogBodyLevelButtonLevelOptinDeclineAll', '#didomi-notice-disagree-button', '.didomi-continue-without-agreeing',
      '.qc-cmp2-summary-buttons button[mode="secondary"]', '#truste-consent-required', '.cc-deny', '.cky-btn-reject', '.cmplz-deny',
      '.iubenda-cs-reject-btn', '#tarteaucitronAllDenied2', '.fc-cta-do-not-consent', '[data-cookiefirst-action="reject"]',
      '.js-cookie-consent-reject', 'button[data-cookiebanner="reject_button"]', '#cookiescript_reject', '.osano-cm-denyAll',
      '[data-testid="uc-deny-all-button"]', 'button.sp_choice_type_REJECT_ALL', '#klaro .cn-decline', '.cm-btn-danger',
      '.moove-gdpr-infobar-reject-btn', '#wt-cli-reject-btn', '.termly-styles-decline', '#ccc-reject-settings', '#gdpr-reject-all',
    ];
    const clicked = new WeakSet();
    const tryReject = () => {
      for (const sel of REJECT) {
        let el = null;
        try { el = D.querySelector(sel); } catch (e) {}
        if (!el) { const uc = D.querySelector('#usercentrics-root'); if (uc && uc.shadowRoot) el = uc.shadowRoot.querySelector(sel); }
        if (el && !clicked.has(el) && el.offsetParent !== null) { clicked.add(el); try { el.click(); } catch (e) {} }
      }
    };
    onReady(() => {
      tryReject();
      const mo = new W.MutationObserver(throttle(tryReject, 400));
      mo.observe(D.documentElement, { childList: true, subtree: true });
      setTimeout(() => mo.disconnect(), 15000);
    });
  }

  // ---------------------------------------------------------------- YouTube
  if (shieldsOn && C.youtube && /(^|\.)youtube(-nocookie)?\.com$/.test(host)) {
    addCss(['ytd-ad-slot-renderer', 'ytd-in-feed-ad-layout-renderer', 'ytd-promoted-sparkles-web-renderer', 'ytm-promoted-sparkles-web-renderer',
      'ytm-companion-ad-renderer', 'ad-slot-renderer', 'ytm-ad-slot-renderer', '#player-ads', '.ytp-ad-overlay-container', 'ytd-banner-promo-renderer',
      'ytm-promoted-video-renderer', 'ytd-display-ad-renderer', 'ytd-statement-banner-renderer', 'ytd-merch-shelf-renderer',
      'ytm-paid-content-overlay-renderer', 'ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"]',
      '.ytp-suggested-action', 'ytm-mealbar-promo-renderer', 'ytd-mealbar-promo-renderer', 'tp-yt-paper-dialog:has(#mealbar-promo-renderer)']
      .join(',') + '{display:none!important}');
    const SKIP = '.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-overlay-close-button,button[id^="skip-button"],.ytm-skip-ad-button';
    const tick = () => {
      const p = D.querySelector('.html5-video-player, #movie_player');
      const s = D.querySelector(SKIP);
      if (s) try { s.click(); } catch (e) {}
      if (p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting'))) {
        const v = p.querySelector('video');
        if (v) { v.muted = true; try { v.playbackRate = 16; } catch (e) {} if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration; }
      }
    };
    onReady(() => { new W.MutationObserver(throttle(tick, 150)).observe(D.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['class'] }); setInterval(tick, 800); });
  }

  // ---------------------------------------------------------------- force dark ("Dark Reader lite")
  if (C.dark && W === W.top) {
    const lum = (c) => { const m = /rgba?\((\d+),\s*(\d+),\s*(\d+)(?:,\s*([\d.]+))?/.exec(c); if (!m || m[4] === '0') return null; return (0.2126 * m[1] + 0.7152 * m[2] + 0.0722 * m[3]) / 255; };
    onReady(() => {
      const bg = lum(W.getComputedStyle(D.body || D.documentElement).backgroundColor) ?? lum(W.getComputedStyle(D.documentElement).backgroundColor) ?? 1;
      if (bg < 0.4) return; // already dark
      addCss('html{filter:invert(0.9) hue-rotate(180deg)!important;background:#fff!important}' +
        'img,video,picture,canvas,iframe,embed,object,svg image,[style*="background-image"]{filter:invert(1) hue-rotate(180deg)!important}');
    });
  }
};
