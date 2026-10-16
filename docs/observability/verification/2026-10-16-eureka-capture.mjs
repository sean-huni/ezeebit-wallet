import { createRequire } from 'node:module';
const require = createRequire(new URL('./package.json', import.meta.url));
const { chromium } = require('playwright');
// eureka-svc binds its pod IP, so the caller bridges it to localhost first (EVIDENCE.md, Service discovery).
const E = process.env.EUREKA_URL ?? 'http://localhost:18761';
const OUT = new URL('./out/', import.meta.url).pathname;
// config-svc serves the Eureka settings and is not a client, so it never appears.
const WANT = { 'GATEWAY-SVC': 2, 'LEDGER-SVC': 2, 'CONVERSION-SVC': 2 };
const browser = await chromium.launch({ channel: 'chrome' });
let failed = 0;
// Desktop only: Eureka's own page is not responsive, and at 390 px it crops the Status column.
for (const [name, width, height] of [['eureka-dashboard.png', 1440, 900]]) {
  const page = await browser.newPage({ viewport: { width, height } });
  await page.goto(`${E}/`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(7000);
  // The page gives three tables id="instances"; the registry is the one with an Availability Zones column.
  const registry = page.locator('table#instances').filter({ hasText: 'Availability Zones' });
  const rows = await registry.locator('tbody tr').allInnerTexts();
  if (rows.length !== Object.keys(WANT).length) failed++;
  console.log(`${width}px registered applications: ${rows.length} (want ${Object.keys(WANT).length})`);
  for (const [app, n] of Object.entries(WANT)) {
    const row = rows.find(r => r.startsWith(app));
    const up = row?.match(/UP \((\d+)\)/)?.[1];
    const ok = Number(up) === n;
    if (!ok) failed++;
    console.log(`${width}px ${app} UP=${up ?? 'absent'} want ${n} ${ok ? 'PASS' : 'FAIL'}`);
  }
  await registry.scrollIntoViewIfNeeded();
  await page.screenshot({ path: `${OUT}${name}` });
  await page.screenshot({ path: `${OUT}${name.replace('.png', '-full.png')}`, fullPage: true });
  console.log(`${new Date().toTimeString().slice(0, 8)} ${name}`);
  await page.close();
}
await browser.close();
console.log(`eureka capture ${failed === 0 ? 'PASS' : 'FAIL'} (${failed} mismatches)`);
process.exit(failed === 0 ? 0 : 1);
