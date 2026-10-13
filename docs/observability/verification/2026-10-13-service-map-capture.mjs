import { createRequire } from 'node:module';
const require = createRequire(new URL('./package.json', import.meta.url));
const { chromium } = require('playwright');
const OUT = new URL('./out/', import.meta.url).pathname;
const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
await page.goto('http://localhost:3000/d/ezeebit-traces?from=now-30m&to=now&refresh=', { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForTimeout(8000);
const header = page.locator('[data-testid="data-testid Panel header Service map"]');
await header.scrollIntoViewIfNeeded();
await page.waitForTimeout(4000);
await header.evaluate(el => el.scrollIntoView({ block: 'start' }));
await page.waitForTimeout(3000);
const box = await header.boundingBox();

await page.mouse.click(1343, 564); await page.waitForTimeout(800);

await page.waitForTimeout(1500);
await page.mouse.move(box.x + 1200, box.y + 60);
await page.waitForTimeout(500);
await page.screenshot({ path: `${OUT}traces-service-map-zoomed.png` });
console.log(new Date().toTimeString().slice(0, 8), 'traces-service-map-zoomed.png');
await browser.close();
