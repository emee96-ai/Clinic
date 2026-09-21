const fs = require('fs');
const path = require('path');
const root = __dirname;
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const js = fs.readFileSync(path.join(root, 'app.js'), 'utf8');

new Function(js);

const ids = new Set([...html.matchAll(/\bid=["']([^"']+)["']/g)].map(m => m[1]));
const refs = new Set([...js.matchAll(/\$\(['"]([^'"]+)['"]\)/g)].map(m => m[1]));
const missing = [...refs].filter(id => !ids.has(id));
if (missing.length) throw new Error(`Missing HTML ids referenced by app.js: ${missing.join(', ')}`);

if (/service[_-]?role|sb_secret_/i.test(js)) throw new Error('Secret/service-role key must never be shipped in admin-web');
for (const required of ['claimAdminBtn','adminSetupCode','paymentHistoryBody','expiryBody']) {
  if (!ids.has(required)) throw new Error(`Required admin control missing: ${required}`);
}
console.log(`Admin web check OK: ${refs.size} DOM references validated.`);
