import { createRequire } from 'node:module';
const require = createRequire(new URL('./package.json', import.meta.url));
const { chromium } = require('playwright');
// Usage: node 2026-11-04-profiling-row-capture.mjs. Opens Fleet Overview, expands the profiling row, and asserts
// one wall-clock flame graph per service from the Pyroscope requests the browser sent, then photographs the row.
const G = process.env.GRAFANA_URL ?? 'http://127.0.0.1:3000';
const OUT = new URL('./out/', import.meta.url).pathname;
const SERVICES = ['config-svc', 'conversion-svc', 'eureka-svc', 'gateway-svc', 'ledger-svc'];
const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1440, height: 2400 } });
const selectors = [];
page.on('request', r => {
  const b = r.postData() ?? '';
  if (r.url().includes('/api/ds/query') && b.includes('grafana-pyroscope-datasource')) {
    JSON.parse(b).queries.forEach(q => selectors.push(`${q.profileTypeId} ${q.labelSelector}`));
  }
});
let failed = 0;
await page.goto(`${G}/d/ezeebit-fleet/fleet-overview?orgId=1&from=now-30m&to=now&refresh=`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(6000);
const row = page.getByText('Profiling (Pyroscope)', { exact: false }).first();
await row.scrollIntoViewIfNeeded();
await row.click();
await page.waitForTimeout(3000);
// Grafana mounts a panel only once it is in its own scroll pane's view, so bring each flame graph there.
const headers = page.locator('[data-testid^="data-testid Panel header Wall clock"]');
for (let i = 0; i < await headers.count(); i++) {
  await headers.nth(i).scrollIntoViewIfNeeded();
  await page.waitForTimeout(4000);
}
const canvases = page.locator('canvas[data-testid="flameGraph"]');
const count = await canvases.count();
if (count !== SERVICES.length) failed++;
for (let i = 0; i < count; i++) {
  const box = await canvases.nth(i).boundingBox();
  if (!box || box.height < 20) failed++;
}
console.log(`flame graph canvases=${count}`);
const distinct = [...new Set(selectors)].sort();
console.log(`pyroscope queries: ${JSON.stringify(distinct)}`);
for (const s of SERVICES) {
  if (!distinct.includes(`wall:wall:nanoseconds:wall:nanoseconds {service_name=~"${s}"}`)) { failed++; console.log(`missing query for ${s}`); }
}
const titles = await page.locator('[data-testid^="data-testid Panel header Wall clock"]').allInnerTexts();
console.log(`panel headers: ${JSON.stringify(titles.map(t => t.split('\n')[0]))}`);
await row.evaluate(el => el.scrollIntoView({ block: 'start' }));
await page.waitForTimeout(2000);
await page.screenshot({ path: `${OUT}fleet-profiling-row.png` });
await browser.close();
console.log(`profiling row capture ${failed === 0 ? 'PASS' : 'FAIL'} (${failed} mismatches)`);
process.exit(failed === 0 ? 0 : 1);
