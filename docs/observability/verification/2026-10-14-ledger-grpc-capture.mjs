import { createRequire } from 'node:module';
const require = createRequire(new URL('./package.json', import.meta.url));
const { chromium } = require('playwright');
const G = 'http://localhost:3000';
const OUT = new URL('../evidence/', import.meta.url).pathname;
const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const stamp = name => console.log(`${new Date().toTimeString().slice(0, 8)} ${name}`);
const ROW = 'Ledger gRPC (conversion-svc to ledger-svc): calls, errors, breakers, dead letters';

await page.goto(`${G}/d/ezeebit-fleet?from=now-15m&to=now&refresh=`, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForTimeout(8000);
// The row ships collapsed; its panels mount only once it is opened and scrolled into Grafana's own pane.
const row = page.getByText(ROW, { exact: true });
await row.scrollIntoViewIfNeeded();
await row.click();
await page.waitForTimeout(3000);
await row.evaluate(el => el.scrollIntoView({ block: 'start' }));
await page.waitForTimeout(6000);
await page.screenshot({ path: `${OUT}2026-10-14-fleet-overview-ledger-grpc-row.png` });
stamp('2026-10-14-fleet-overview-ledger-grpc-row.png');

const dead = page.locator('[data-testid="data-testid Panel header Dead letters"]');
await dead.evaluate(el => el.scrollIntoView({ block: 'center' }));
await page.waitForTimeout(4000);
const box = await dead.evaluate(el => {
	const r = (el.closest('.react-grid-item') ?? el.closest('[data-viz-panel-key]') ?? el.parentElement.parentElement).getBoundingClientRect();
	return { x: r.x, y: r.y, width: r.width, height: r.height };
});
await page.screenshot({ path: `${OUT}2026-10-14-fleet-overview-dead-letters.png`, clip: box });
stamp('2026-10-14-fleet-overview-dead-letters.png');

await page.goto(`${G}/d/ezeebit-alerts?from=now-15m&to=now&refresh=`, { waitUntil: 'domcontentloaded', timeout: 60000 });
await page.waitForTimeout(8000);
await page.screenshot({ path: `${OUT}2026-10-14-alerts-dead-letter-firing.png` });
stamp('2026-10-14-alerts-dead-letter-firing.png');
const input = page.locator('[data-testid="data-testid Panel header ConversionLedgerDeadLettered"]');
await input.evaluate(el => el.scrollIntoView({ block: 'center' }));
await page.waitForTimeout(5000);
await page.screenshot({ path: `${OUT}2026-10-14-alerts-dead-letter-input.png` });
stamp('2026-10-14-alerts-dead-letter-input.png');
await browser.close();
