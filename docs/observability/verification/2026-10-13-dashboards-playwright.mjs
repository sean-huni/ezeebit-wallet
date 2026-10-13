// Drives real traffic, then drives every Ezeebit dashboard and reads what Grafana DID: the /api/ds/query requests
// the browser sends. A screenshot proves a panel drew; only the request proves a control changed what was asked.
// Run with the fleet and lgtm up: npm install once, then node 2026-10-13-dashboards-playwright.mjs. Captures land in
// out/ (gitignored); the curated ones are copied into docs/observability/evidence/ by hand after being looked at.
import { chromium } from 'playwright';
import { mkdirSync, statSync, writeFileSync } from 'node:fs';
import { drive } from './traffic.mjs';

const GRAFANA = process.env.GRAFANA_URL || 'http://localhost:3000';
const TEMPO = `${GRAFANA}/api/datasources/proxy/uid/tempo`;
const LOKI = `${GRAFANA}/api/datasources/proxy/uid/loki`;
const PROM = `${GRAFANA}/api/datasources/proxy/uid/prometheus`;
const OUT = new URL('./out/', import.meta.url).pathname;
const BOARDS = ['ezeebit-fleet', 'ezeebit-money', 'ezeebit-traces', 'ezeebit-logs', 'ezeebit-alerts'];
const WIDTHS = { desktop: { width: 1440, height: 900 }, mobile: { width: 390, height: 844 } };
// One value per variable, chosen so its substitution is recognisable in a query body.
const VALUES = { service: 'conversion-svc', currency: 'ZAR', pair: 'ZAR/USDT', outcome: 'issued', level: 'warn',
  search: 'quote issued', min_duration: '7ms', interval: '5m', trace_id: null };

mkdirSync(OUT, { recursive: true });
const results = [];
let failed = 0;
const pass = (where, check, detail) => { results.push({ where, check, ok: true, detail }); console.log(`ok   ${where}  ${check}  ${detail}`); };
const fail = (where, check, detail) => { results.push({ where, check, ok: false, detail }); failed++; console.log(`FAIL ${where}  ${check}  ${detail}`); };
const expect = (cond, where, check, detail) => (cond ? pass : fail)(where, check, detail);
const getJson = async url => (await fetch(url)).json();
const sleep = ms => new Promise(r => setTimeout(r, ms));

// 1. Traffic, then long enough for two 10s metric exports and the trace and log batches.
const traffic = await drive();
pass('traffic', 'driven', JSON.stringify(traffic.tally));
await sleep(25000);

// 2. Metric identity: one instance series per JVM, so no two JVMs share a counter.
const identity = await getJson(`${PROM}/api/v1/query?query=${encodeURIComponent('count by (job) (group by (job, instance) (jvm_threads_live))')}`);
const perJob = Object.fromEntries(identity.data.result.map(r => [r.metric.job, Number(r.value[1])]));
expect(Object.keys(perJob).length === 5 && Object.values(perJob).every(n => n === 1), 'prometheus', 'one series per JVM',
  JSON.stringify(perJob));

// 3. The probe conversion is one trace across the three services, found by its merchant id in the gateway span.
// No start/end: with a window Tempo searches only flushed blocks, and these traces are seconds old.
const search = async q => (await getJson(`${TEMPO}/api/search?${new URLSearchParams({ q, limit: '5' })}`)).traces || [];
let probeTraces = [];
for (let i = 0; i < 6 && probeTraces.length === 0; i++) {
  probeTraces = await search(`{resource.service.name="gateway-svc" && span.http.url=~".*${traffic.probe}/conversions"}`);
  if (!probeTraces.length) await sleep(5000);
}
const conversionTrace = probeTraces[0]?.traceID;
const spansOf = async id => {
  const t = await getJson(`${TEMPO}/api/traces/${id}`);
  return (t.batches || []).flatMap(b => {
    const svc = b.resource.attributes.find(a => a.key === 'service.name').value.stringValue;
    return b.scopeSpans.flatMap(s => s.spans.map(sp => ({ svc, name: sp.name, spanId: Buffer.from(sp.spanId, 'base64').toString('hex'), id: sp.spanId })));
  });
};
let conversionSpans = [];
if (!conversionTrace) fail('tempo', 'probe conversion trace found', 'no trace for the probe merchant');
else {
  conversionSpans = await spansOf(conversionTrace);
  const services = [...new Set(conversionSpans.map(s => s.svc))].sort();
  expect(['conversion-svc', 'gateway-svc', 'ledger-svc'].every(s => services.includes(s)), 'tempo', 'one trace spans three services',
    `${conversionTrace}: ${conversionSpans.length} spans from ${services.join(', ')}; ${conversionSpans.map(s => s.name).join(' | ')}`);
  for (const name of ['rate limit', 'conversion accept', 'ledger posting']) {
    expect(conversionSpans.some(s => s.name === name), 'tempo', `business span '${name}' in the trace`, conversionTrace);
  }
}

// 4. Logs carry the request's trace: a random sample of 20 request-path lines, each checked against Tempo.
const now = Date.now();
const lokiLines = await getJson(`${LOKI}/loki/api/v1/query_range?${new URLSearchParams({
  query: '{service_name=~"gateway-svc|conversion-svc|ledger-svc"} | trace_id!=""', limit: '500',
  start: String((now - 600000) * 1e6), end: String(now * 1e6) })}`);
const lines = lokiLines.data.result.flatMap(s => s.values.map(v => ({ svc: s.stream.service_name, trace: s.stream.trace_id,
  span: s.stream.span_id, text: v[1] })));
const sample = lines.sort(() => Math.random() - 0.5).slice(0, 20);
let matched = 0;
const mismatches = [];
for (const l of sample) {
  const spans = await spansOf(l.trace);
  if (spans.some(s => s.svc === l.svc && s.spanId === l.span)) matched++;
  else mismatches.push(`${l.svc} ${l.trace}/${l.span} "${l.text.slice(0, 60)}"`);
}
expect(sample.length === 20 && matched === 20, 'loki+tempo', '20 random request-path log lines resolve to their span in Tempo',
  `${matched} of ${sample.length} (universe: ${lines.length} lines with a trace_id in the last 10 min)${mismatches.length ? ` misses: ${mismatches.join('; ')}` : ''}`);
if (conversionTrace) {
  const own = await getJson(`${LOKI}/loki/api/v1/query_range?${new URLSearchParams({
    query: `{service_name=~".+"} | trace_id="${conversionTrace}"`, start: String((now - 600000) * 1e6), end: String(now * 1e6) })}`);
  const ownLines = own.data.result.flatMap(s => s.values.map(v => `${s.stream.service_name}: ${v[1]}`));
  expect(ownLines.length > 0, 'loki', 'the probe conversion\'s own log lines carry its trace id', ownLines.join(' | '));
}

// 5. Exemplars come back with the latency query, carrying a trace_id that Tempo resolves.
const ex = await getJson(`${PROM}/api/v1/query_exemplars?${new URLSearchParams({
  query: 'http_server_requests_milliseconds_bucket{job="conversion-svc"}', start: String(Math.floor(now / 1000) - 600), end: String(Math.floor(now / 1000)) })}`);
const exTrace = ex.data.flatMap(s => s.exemplars).map(e => e.labels.trace_id).find(Boolean);
expect(Boolean(exTrace) && (await spansOf(exTrace)).length > 0, 'prometheus', 'latency exemplars carry a trace_id Tempo resolves', exTrace || 'none');

// 6. The browser half.
const browser = await chromium.launch({ channel: 'chrome' });
let queries = [];
let annotationRequests = 0;
const watch = page => page.on('request', r => {
  if (r.url().includes('/api/annotations')) annotationRequests++;
  if (!r.url().includes('/api/ds/query')) return;
  try {
    const body = JSON.parse(r.postData() || '{}');
    queries.push({ from: Number(body.from), to: Number(body.to), exprs: (body.queries || []).map(q => q.expr || q.query || '') });
  } catch { queries.push({ from: NaN, to: NaN, exprs: [] }); }
});
const spans = () => queries.filter(q => Number.isFinite(q.from)).map(q => (q.to - q.from) / 1000);
const median = xs => { const s = [...xs].sort((a, b) => a - b); return s.length ? s[Math.floor(s.length / 2)] : NaN; };
const carrying = needle => queries.flatMap(q => q.exprs).filter(e => e.includes(needle)).length;

async function open(page, url) {
  queries = [];
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForSelector('[data-testid^="data-testid Panel header"], [data-testid="dashboard-row-title"], button[aria-expanded]', { timeout: 45000 }).catch(() => {});
  await page.waitForTimeout(6000);
}

// Grafana scrolls a desktop dashboard in its own pane, where window.scrollTo moves nothing; at phone width the pane is
// the document itself (measured: BODY overflow auto, html the scrolling element). The pane is found by walking up from
// a panel header to the first ancestor that clips, and every step must move, so a wrong pick fails rather than shrinks.
const findPane = page => page.evaluateHandle(() => {
  let el = document.querySelector('[data-testid^="data-testid Panel header"]');
  while (el && !(el.scrollHeight > el.clientHeight + 40 && getComputedStyle(el).overflowY !== 'visible')) el = el.parentElement;
  return el && (el === document.body || el === document.documentElement) ? document.scrollingElement : el;
});
const paneName = pane => pane.evaluate(el => (el ? `${el === document.scrollingElement ? 'document' : el.tagName.toLowerCase()} ${el.clientHeight}/${el.scrollHeight}px` : 'none'));
// Panels below the fold never mount, so a variable's substitution is only visible after the whole pane is scrolled.
async function loadAll(page) {
  const pane = await findPane(page);
  for (let i = 0; i < 15; i++) {
    const moved = await pane.evaluate(el => { if (!el) return false; const b = el.scrollTop; el.scrollTop += el.clientHeight * 0.8; return el.scrollTop > b; });
    await page.waitForTimeout(1200);
    if (!moved) break;
  }
  await pane.evaluate(el => { if (el) el.scrollTop = 0; });
  await page.waitForTimeout(1500);
}

async function scrollAndShoot(page, name) {
  const pane = await findPane(page);
  const where = await paneName(pane);
  const info = await pane.evaluate(el => el ? { h: el.scrollHeight, c: el.clientHeight } : null);
  const shots = [];
  let stop = 0;
  // Latest state per panel: a panel first seen half-loaded at the bottom edge is judged where it finished loading.
  const seen = new Map();
  const record = async () => {
    const found = await page.$$eval('[data-testid^="data-testid Panel header"]', els => els.map(e => ({
      title: e.getAttribute('data-testid').replace('data-testid Panel header ', ''),
      empty: /No data|No rows/.test(e.innerText || '') })));
    for (const f of found) seen.set(f.title, f.empty);
  };
  for (;;) {
    await page.waitForTimeout(2500);
    await record();
    const file = `${OUT}${name}-${String(stop).padStart(2, '0')}.png`;
    await page.screenshot({ path: file });
    shots.push(file);
    if (!info) break;
    const before = await pane.evaluate(el => el.scrollTop);
    const after = await pane.evaluate(el => { el.scrollTop += el.clientHeight * 0.8; return el.scrollTop; });
    if (after <= before) break;
    stop++;
    if (stop > 14) break;
  }
  if (info && info.h > info.c + 40 && shots.length < 2) fail(name, 'scrolled inside the Grafana pane', `pane ${where} gave ${shots.length} shot`);
  else pass(name, 'scrolled inside the Grafana pane', `${where}: ${shots.length} viewport captures, each step moved`);
  await pane.evaluate(el => { if (el) el.scrollTop = 0; });
  const emptyTitles = [...seen].filter(([, empty]) => empty).map(([title]) => title);
  return { shots, panels: seen.size, empty: emptyTitles.length, emptyTitles };
}

for (const [label, viewport] of Object.entries(WIDTHS)) {
  const page = await browser.newPage({ viewport, deviceScaleFactor: label === 'mobile' ? 2 : 1 });
  watch(page);
  for (const uid of BOARDS) {
    await open(page, `${GRAFANA}/d/${uid}?from=now-30m&to=now&refresh=`);
    const r = await scrollAndShoot(page, `${uid}-${label}`);
    const sizes = r.shots.map(f => statSync(f).size);
    expect(sizes.every(s => s > 15000), `${uid} ${label}`, 'captures are not blank', `${r.shots.length} shots, smallest ${Math.min(...sizes)} bytes`);
    // Content floor: most panels render data. Panels that are empty by design say so in their description.
    expect(r.panels >= 3 && r.empty <= Math.floor(r.panels / 3), `${uid} ${label}`, 'content floor',
      `${r.panels} panels seen while scrolling, ${r.empty} empty${r.empty ? ` (${r.emptyTitles.join('; ')})` : ''}, ${queries.length} datasource requests`);
  }
  await page.close();
}

const page = await browser.newPage({ viewport: WIDTHS.desktop });
watch(page);

// 7. The time picker drives every panel: 30m to 5m, read off the window each request asks for.
for (const uid of BOARDS) {
  await open(page, `${GRAFANA}/d/${uid}?from=now-30m&to=now&refresh=`);
  const before = median(spans());
  queries = [];
  await page.locator('[data-testid="data-testid TimePicker Open Button"]').first().click();
  await page.waitForTimeout(800);
  await page.getByText('Last 5 minutes', { exact: true }).first().click();
  await page.waitForTimeout(5000);
  const after = median(spans());
  expect(Math.abs(before - 1800) < 60 && Math.abs(after - 300) < 30 && /from=now-5m/.test(page.url()), uid, 'time picker re-queries the new window',
    `median span ${before}s -> ${after}s over ${queries.length} requests`);
}

// 8. Every variable reaches the datasource substituted.
for (const uid of BOARDS) {
  const model = (await getJson(`${GRAFANA}/api/dashboards/uid/${uid}`)).dashboard;
  for (const v of model.templating.list) {
    const value = v.name === 'trace_id' ? conversionTrace : VALUES[v.name];
    if (!value) continue;
    await open(page, `${GRAFANA}/d/${uid}?from=now-30m&to=now&refresh=&var-${v.name}=${encodeURIComponent(value)}`);
    if (v.name === 'trace_id' && uid === 'ezeebit-traces') {
      await page.getByText('Trace by ID', { exact: false }).first().click();
      await page.waitForTimeout(6000);
    }
    await loadAll(page);
    const needle = v.name === 'interval' ? `[${value}]` : value;
    const hits = carrying(needle);
    expect(hits > 0, uid, `variable ${v.name} reaches the datasource`, `${hits} of ${queries.flatMap(q => q.exprs).length} expressions carry ${needle}`);
  }
}

// 9. A tile's data link lands on Logs for that service with the window carried, and Logs then queries that service.
await open(page, `${GRAFANA}/d/ezeebit-fleet?from=now-15m&to=now&refresh=`);
await page.locator('[data-testid="data-testid Panel header Alive"]').getByText('gateway-svc', { exact: true }).first().click();
await page.waitForTimeout(1000);
await page.screenshot({ path: `${OUT}fleet-tile-links.png` });
await page.getByText('Logs for gateway-svc', { exact: true }).first().click();
await page.waitForTimeout(6000);
expect(/\/d\/ezeebit-logs/.test(page.url()) && /var-service=gateway-svc/.test(page.url()) && /from=/.test(page.url()), 'fleet', 'data link lands on Logs for the service, window kept', page.url().replace(GRAFANA, ''));
expect(carrying('gateway-svc') > 0, 'fleet', 'the landed Logs board queries that service', `${carrying('gateway-svc')} expressions carry gateway-svc`);
await page.screenshot({ path: `${OUT}fleet-link-landed-logs.png` });

// 10. Dashboard links: Fleet to Traces keeps the time range and the service.
await open(page, `${GRAFANA}/d/ezeebit-fleet?from=now-15m&to=now&refresh=&var-service=ledger-svc`);
await page.locator('[data-testid="data-testid Dashboard link"]').filter({ hasText: 'Traces' }).first().click();
await page.waitForTimeout(5000);
expect(/\/d\/ezeebit-traces/.test(page.url()) && /from=now-15m/.test(page.url()) && /var-service=ledger-svc/.test(page.url()), 'fleet', 'dashboard link keeps time and service', page.url().replace(GRAFANA, ''));

// 11. A log line's trace_id opens the trace in Tempo.
await open(page, `${GRAFANA}/d/ezeebit-logs?from=now-15m&to=now&refresh=&var-service=conversion-svc&var-search=${encodeURIComponent('conversion accepted')}`);
const logsPanel = page.locator('[data-testid="data-testid Panel header Logs"]');
await logsPanel.getByText('conversion accepted', { exact: false }).first().click();
await page.waitForTimeout(1500);
await page.screenshot({ path: `${OUT}logs-line-expanded.png` });
const traceLink = page.getByRole('link', { name: /Trace: [0-9a-f]{32}/ }).or(page.getByText(/Trace: [0-9a-f]{32}/)).first();
const linkText = (await traceLink.textContent().catch(() => '')) || '';
const linked = linkText.match(/[0-9a-f]{32}/)?.[0];
if (!linked) fail('logs', 'a log line offers its trace', 'no "Trace: <id>" link in the expanded line');
else {
  const [popup] = await Promise.all([page.context().waitForEvent('page', { timeout: 8000 }).catch(() => null), traceLink.click()]);
  const target = popup || page;
  await target.waitForTimeout(7000);
  const url = decodeURIComponent(target.url());
  const text = await target.locator('body').innerText();
  expect(/explore/.test(url) && url.includes(linked) && /tempo/.test(url), 'logs', 'trace_id opens the trace in Tempo', `${linked} -> ${url.replace(GRAFANA, '').slice(0, 160)}`);
  expect(/gateway-svc/.test(text) && /conversion-svc/.test(text), 'logs', 'the opened trace shows its services', 'gateway-svc and conversion-svc rendered');
  await target.screenshot({ path: `${OUT}logs-trace-opened-in-tempo.png` });
  if (popup) await popup.close();
}

// 12. The trace view on the Traces dashboard shows spans from all three services.
if (conversionTrace) {
  await open(page, `${GRAFANA}/d/ezeebit-traces?from=now-30m&to=now&refresh=&var-trace_id=${conversionTrace}`);
  await page.getByText('Trace by ID', { exact: false }).first().click();
  await page.waitForTimeout(8000);
  const text = await page.locator('body').innerText();
  const shown = ['gateway-svc', 'conversion-svc', 'ledger-svc'].filter(s => text.includes(s));
  expect(shown.length === 3, 'traces', 'trace view shows spans from three services', `${conversionTrace}: ${shown.join(', ')}`);
  await page.screenshot({ path: `${OUT}traces-trace-view.png` });
}

// 13. Alert state changes reach the Alerts dashboard as annotations.
annotationRequests = 0;
await open(page, `${GRAFANA}/d/ezeebit-alerts?from=now-3h&to=now&refresh=`);
const stored = await getJson(`${GRAFANA}/api/annotations?type=alert&dashboardUID=ezeebit-alerts&limit=200`);
expect(annotationRequests > 0 && stored.length > 0, 'alerts', 'alert state changes are annotations on the Alerts dashboard',
  `${stored.length} stored, ${annotationRequests} annotation requests from the page; states ${[...new Set(stored.map(a => a.newState))].join(', ')}`);

await browser.close();
writeFileSync(`${OUT}results.json`, JSON.stringify({ at: new Date().toISOString(), traffic, conversionTrace, results }, null, 2));
console.log(`suite: ${results.filter(r => r.ok).length} passed, ${failed} failed`);
process.exit(failed ? 1 : 0);
