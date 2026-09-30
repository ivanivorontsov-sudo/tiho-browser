// Generates app/src/main/assets/adblock/resources.json (adblock-rust Resource format) from
// uBlock Origin's scriptlets + redirect resources (GPLv3, https://github.com/gorhill/uBlock)
// and Brave's adblock-resources (MPL-2.0).
// Usage: node tools/gen-resources.mjs <path-to-uBlock-src-checkout> <brave-resources.json> <out.json>
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

const [uboDir, braveJson, out] = process.argv.slice(2);
const res = [];
const seen = new Set();
const b64 = s => Buffer.from(s).toString('base64');

const { builtinScriptlets } = await import(pathToFileURL(path.join(uboDir, 'src/js/resources/scriptlets.js')));
for (const s of builtinScriptlets) {
  if (s.world === 'ISOLATED') continue; // WebView document-start scripts run in the page (MAIN) world only
  if (seen.has(s.name)) continue;
  seen.add(s.name);
  res.push({
    name: s.name,
    aliases: (s.aliases || []).filter(a => !seen.has(a)),
    // top-level scriptlets must be application/javascript (function-style), helpers are fn/javascript
    kind: { mime: s.name.endsWith('.fn') ? 'fn/javascript' : 'application/javascript' },
    content: b64(s.fn.toString()),
    dependencies: s.dependencies || [],
    permission: s.requiresTrust ? 1 : 0,
  });
}

const { default: redirects } = await import(pathToFileURL(path.join(uboDir, 'src/js/redirect-resources.js')));
const mimeOf = n => {
  const ext = n.split('.').pop();
  return ({ js: 'application/javascript', gif: 'image/gif', png: 'image/png', css: 'text/css', html: 'text/html',
    txt: 'text/plain', json: 'application/json', xml: 'text/xml', mp3: 'audio/mp3', mp4: 'video/mp4' })[ext] || 'text/plain';
};
for (const [name, details] of redirects) {
  const file = path.join(uboDir, 'src/web_accessible_resources', name);
  if (!fs.existsSync(file) || seen.has(name)) continue;
  seen.add(name);
  const aliases = [];
  const a = details.alias;
  if (Array.isArray(a)) aliases.push(...a); else if (a) aliases.push(a);
  // uBO short aliases used in filters: noopjs, noop.txt, etc.
  if (name === 'noop.js') aliases.push('noopjs');
  if (name === 'noop.txt') aliases.push('nooptext');
  res.push({ name, aliases, kind: { mime: mimeOf(name) }, content: fs.readFileSync(file).toString('base64'), dependencies: [], permission: 0 });
}

if (braveJson && fs.existsSync(braveJson)) {
  for (const r of JSON.parse(fs.readFileSync(braveJson, 'utf8'))) {
    if (seen.has(r.name)) continue;
    seen.add(r.name);
    res.push({ dependencies: [], permission: 0, ...r });
  }
}
fs.writeFileSync(out, JSON.stringify(res));
console.log(`resources: ${res.length} -> ${out}`);
