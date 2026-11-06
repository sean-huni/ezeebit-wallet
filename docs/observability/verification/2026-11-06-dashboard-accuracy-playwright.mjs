// Accuracy fixes on Fleet Overview, Money, Alerts and Traces, read from the queries the browser sends and the panels it
// renders. Run against any Grafana serving these boards: GRAFANA_URL (default localhost:3000). Captures land in out/.
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import path from 'node:path';

const G = process.env.GRAFANA_URL || 'http://localhost:3000';
const OUT = new URL('./out/', import.meta.url).pathname;
mkdirSync(OUT, { recursive: true });
const fails = [];
const check = (ok, msg) => { console.log(`${ok ? 'PASS' : 'FAIL'} ${msg}`); if (!ok) fails.push(msg); };

function track(page) {
  const exprs = [];
  const errs = [];
  page.on('request', r => {
    if (!/\/api\/ds\/query/.test(r.url())) return;
    try { for (const q of JSON.parse(r.postData() || '{}').queries || []) exprs.push(q.expr || q.query || ''); } catch { /* not JSON */ }
  });
  page.on('response', async r => {
    if (!/\/api\/ds\/query/.test(r.url())) return;
    try { for (const v of Object.values((await r.json()).results || {})) if (v.error) errs.push(v.error); }
    catch { if (r.status() >= 400) errs.push(`HTTP ${r.status()}`); }
  });
  return { exprs, errs };
}

async function open(page, uid, range = 'now-6h') {
  await page.goto(`${G}/d/${uid}?from=${range}&to=now&refresh=`);
  await page.locator('[data-testid^="data-testid Panel header "]').first().waitFor({ timeout: 30000 });
  await page.waitForTimeout(4000);
}

// Grafana mounts panels only when scrolled into its own pane; bring each one in view before reading it.
async function panel(page, title) {
  const p = page.getByTestId(`data-testid Panel header ${title}`);
  await p.scrollIntoViewIfNeeded();
  await page.waitForTimeout(3000);
  return p;
}

const browser = await chromium.launch({ headless: true, channel: 'chrome' });
try {
  const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });

  // Fleet Overview
  let t = track(page);
  await open(page, 'ezeebit-fleet');
  for (const title of ['Outbound calls by host and status', 'Outbound p95 by host', 'Connection acquire mean and timeouts']) {
    const p = await panel(page, title);
    check(await p.count() === 1, `Fleet: panel '${title}' is on the board`);
  }
  const acquire = await panel(page, 'Connection acquire mean and timeouts');
  const acquireText = await acquire.innerText();
  const means = [...acquireText.replace(/\s+/g, ' ').matchAll(/(\S+-svc) acquire mean ([\d.]+) ms/g)].map(m => `${m[1]} ${m[2]} ms`);
  check(means.length > 0 && !/NaN/.test(acquireText), `Fleet: acquire mean renders a millisecond value (${means.join(', ') || 'none'})`);
  await acquire.screenshot({ path: path.join(OUT, 'accuracy-fleet-acquire.png') });
  const fivexx = await panel(page, '5xx ratio by service');
  check(t.exprs.some(e => e.includes('status=~"5.."') && e.includes(' unless ')), "Fleet: 5xx ratio counts a series' first event (counted, not rate)");
  await fivexx.screenshot({ path: path.join(OUT, 'accuracy-fleet-5xx.png') });
  await page.getByText('Ledger gRPC (conversion-svc to ledger-svc)', { exact: false }).first().click();
  await page.waitForTimeout(5000);
  const grpc = await panel(page, 'Calls by method and status');
  check(t.exprs.some(e => e.includes('grpc_client_milliseconds_count') && e.includes(' unless ')), 'Fleet: gRPC calls and error ratio are counted');
  check(t.exprs.some(e => e.includes('state="forced_open"}[30s]) * 5')), 'Fleet: breaker codes ranked, FORCED_OPEN highest (5)');
  await grpc.screenshot({ path: path.join(OUT, 'accuracy-fleet-grpc.png') });
  check(t.errs.length === 0, `Fleet: no datasource errors (${t.errs.length}) ${t.errs.slice(0, 2).join(' | ')}`);

  // Alerts
  t = track(page);
  await open(page, 'ezeebit-alerts', 'now-3h');
  const slo = await panel(page, 'LatencySLOBurn');
  check(t.exprs.some(e => e.includes('le=~"500(\\\\.0)?"')), 'Alerts: LatencySLOBurn selects the 500 ms bucket in either form');
  check(!t.exprs.some(e => e.includes('le="500.0"')), 'Alerts: no query selects the exact le="500.0" that matches nothing');
  await slo.screenshot({ path: path.join(OUT, 'accuracy-alerts-slo.png') });
  const board = await (await page.request.get(`${G}/api/dashboards/uid/ezeebit-alerts`)).json();
  const templated = board.dashboard.panels.filter(p => (p.description || '').includes('{{')).map(p => p.title);
  check(templated.length === 0, `Alerts: no panel description shows raw alert templates (${templated.join(', ') || 'none'})`);
  check(t.errs.length === 0, `Alerts: no datasource errors (${t.errs.length})`);

  // Money
  t = track(page);
  await open(page, 'ezeebit-money');
  const quotes = await panel(page, 'Quotes by pair and outcome');
  check((await quotes.count()) === 1 && t.errs.length === 0, `Money: renders with no datasource errors (${t.errs.length})`);
  const money = await (await page.request.get(`${G}/api/dashboards/uid/ezeebit-money`)).json();
  const desc = Object.fromEntries(money.dashboard.panels.map(p => [p.title, p.description || '']));
  check(/not counted/.test(desc['Quotes by pair and outcome']) && /not counted/.test(desc['Conversion requests by pair and outcome']),
    'Money: quote and conversion counters say validation refusals are not counted');

  // Traces
  t = track(page);
  await open(page, 'ezeebit-traces');
  const spans = await panel(page, 'Span rate by service and status');
  await panel(page, 'Slow merchant requests');
  check(t.exprs.some(e => e.includes('span_kind="SPAN_KIND_SERVER"')), 'Traces: span rate counts server spans only');
  check(t.exprs.some(e => e.includes('trace:duration >')), 'Traces: slow merchant requests test the whole trace duration');
  await spans.screenshot({ path: path.join(OUT, 'accuracy-traces-span-rate.png') });
  check(t.errs.length === 0, `Traces: no datasource errors (${t.errs.length}) ${t.errs.slice(0, 2).join(' | ')}`);
} finally {
  await browser.close();
}
console.log(`RESULT ${fails.length ? 'FAIL' : 'PASS'} (${fails.length} failed)`);
process.exitCode = fails.length ? 1 : 0;
