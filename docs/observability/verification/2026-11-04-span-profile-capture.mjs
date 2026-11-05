import { createRequire } from 'node:module';
const require = createRequire(new URL('./package.json', import.meta.url));
const { chromium } = require('playwright');
// Usage: node 2026-11-04-span-profile-capture.mjs <traceId> <server span name>. Opens the trace in Explore,
// clicks the service's SERVER span, follows "Profiles for this span" and photographs the flame graph.
const G = process.env.GRAFANA_URL ?? 'http://127.0.0.1:3000';
const OUT = new URL('./out/', import.meta.url).pathname;
const [trace, spanName] = process.argv.slice(2);
const pane = { datasource: 'tempo', queries: [{ refId: 'A', datasource: { type: 'tempo', uid: 'tempo' }, queryType: 'traceql', query: trace }], range: { from: 'now-1h', to: 'now' } };
const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1440, height: 1600 } });
const profileQueries = [];
page.on('request', r => { const b = r.postData() ?? ''; if (r.url().includes('/api/ds/query') && b.includes('grafana-pyroscope-datasource')) profileQueries.push(b); });
let failed = 0;
await page.goto(`${G}/explore?schemaVersion=1&orgId=1&panes=${encodeURIComponent(JSON.stringify({ a: pane }))}`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(8000);
const span = page.getByText(spanName, { exact: true });
if (await span.count() !== 1) failed++;
await span.first().click();
await page.waitForTimeout(3000);
// With several span links Grafana groups them under the span detail's "Links" menu.
await page.getByRole('button', { name: 'Links' }).first().click();
await page.waitForTimeout(1500);
const link = page.getByText('Profiles for this span');
const links = await link.count();
if (links !== 1) failed++;
console.log(`span "${spanName}": "Profiles for this span" links=${links}`);
await page.screenshot({ path: `${OUT}span-profile-link.png` });
await link.first().click();
await page.waitForTimeout(10000);
const canvas = page.locator('canvas[data-testid="flameGraph"]');
const box = (await canvas.count()) ? await canvas.first().boundingBox() : null;
if (!box || box.height < 20) failed++;
console.log(`flame graph canvas ${box ? `${Math.round(box.width)}x${Math.round(box.height)}` : 'absent'}`);
const sel = profileQueries.map(b => JSON.parse(b).queries.map(q => `${q.labelSelector} spanSelector=${JSON.stringify(q.spanSelector)} range=${JSON.parse(b).from}..${JSON.parse(b).to}`)).flat();
console.log(`pyroscope queries: ${JSON.stringify(sel)}`);
await page.screenshot({ path: `${OUT}span-profile-flamegraph.png` });
// A single-chain stack is drawn as one collapsed group, e.g. "(11) total"; expand it to name the frames.
const expand = page.getByRole('button', { name: /expand all groups/i });
console.log(`expand-all buttons=${await expand.count()}`);
if (await expand.count()) {
  await expand.first().click();
  await page.waitForTimeout(2000);
  await page.screenshot({ path: `${OUT}span-profile-flamegraph-expanded.png` });
}
await page.getByTestId('data-testid radio-button-option topTable').check({ force: true });
await page.waitForTimeout(1500);
await page.screenshot({ path: `${OUT}span-profile-top-table.png` });
await browser.close();
console.log(`span profile capture ${failed === 0 ? 'PASS' : 'FAIL'} (${failed} mismatches)`);
process.exit(failed === 0 ? 0 : 1);
