import { createRequire } from 'node:module';
const require = createRequire(new URL('./package.json', import.meta.url));
const { chromium } = require('playwright');
const G = 'http://localhost:3000';
const OUT = new URL('./out/', import.meta.url).pathname;
const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const shot = async (url, name, wait = 7000) => {
  await page.goto(`${G}${url}`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(wait);
  await page.screenshot({ path: `${OUT}${name}` });
  console.log(`${new Date().toTimeString().slice(0, 8)} ${name} ${url}`);
};
await shot(`/alerting/list?view=list&search=${encodeURIComponent('namespace:"Ezeebit alerts"')}`, 'alert-rule-list.png');
await shot('/alerting/grafana/ezeebit-rate-feed-refusing/view?tab=history', 'alert-state-history.png');
for (let i = 0; i < 3; i++) {
  await page.goto(`${G}/d/ezeebit-traces?from=now-30m&to=now&refresh=`, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForTimeout(8000);
  const header = page.locator('[data-testid="data-testid Panel header Service map"]');
  await header.scrollIntoViewIfNeeded();
  await page.waitForTimeout(4000);
  await header.evaluate(el => el.scrollIntoView({ block: 'start' }));
  await page.waitForTimeout(3000);
  await page.screenshot({ path: `${OUT}traces-service-map-${i}.png` });
  console.log(`${new Date().toTimeString().slice(0, 8)} traces-service-map-${i}.png`);
}
await browser.close();
