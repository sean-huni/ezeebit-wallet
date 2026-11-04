import { createRequire } from 'node:module';
import { randomUUID } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { existsSync } from 'node:fs';
const require = createRequire(new URL('./package.json', import.meta.url));
const { chromium } = require('playwright');
// Usage: node 2026-11-04-span-to-profile.mjs. Delays ZAR/USDT rates in WireMock (RATES_ADMIN, a port-forward to
// svc/wiremock), drives quotes, opens a slow trace, follows the conversion-svc SERVER span's "Profiles for this
// span", then opens the dashboard profiling row over the trace +/- PAD_MS. Asserts from the datasource traffic.
const G = process.env.GRAFANA_URL ?? 'http://127.0.0.1:3000';
const GATEWAY = process.env.GATEWAY_URL ?? 'http://127.0.0.1:8080';
const RATES_ADMIN = process.env.RATES_ADMIN ?? 'http://127.0.0.1:18089';
const OUT = new URL('./out/', import.meta.url).pathname;
const SERVICE = 'conversion-svc', FRAME = 'RateCall.get', DELAY_MS = 800, PAD_MS = 15000;
// Screenshots are keyed by date, commit and time (RUN_ID overrides), and never overwrite a file or a tracked path.
const RUN = process.env.RUN_ID ?? `${new Date().toISOString().slice(0, 10)}-${execFileSync('git', ['rev-parse', '--short', 'HEAD']).toString().trim()}-${new Date().toISOString().slice(11, 19).replaceAll(':', '')}`;
const shot = async (page, name) => {
  const path = `${OUT}t7-${RUN}-${name}.png`;
  let tracked = true;
  try { execFileSync('git', ['ls-files', '--error-unmatch', path], { stdio: 'ignore' }); } catch { tracked = false; }
  if (tracked || existsSync(path)) throw new Error(`refusing to overwrite ${path}`);
  await page.screenshot({ path });
  console.log(`screenshot ${path}`);
};
const sleep = ms => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log(...a);
let failed = 0;
const check = (ok, what) => { log(`${ok ? 'PASS' : 'FAIL'} ${what}`); if (!ok) failed++; };
const getJson = async (url, init) => { const r = await fetch(url, init); return { status: r.status, body: await r.json().catch(() => null) }; };
const hex = id => /^[0-9a-f]{16}$/.test(id) ? id : Buffer.from(id, 'base64').toString('hex');
const attr = (attrs, key) => attrs?.find(a => a.key === key)?.value?.stringValue;

const mapping = await getJson(`${RATES_ADMIN}/__admin/mappings`, { method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ name: 'task7: slow ZAR/USDT', priority: 1,
    request: { method: 'GET', urlPath: '/rates', queryParameters: { base: { equalTo: 'ZAR' }, quote: { equalTo: 'USDT' } } },
    response: { status: 200, fixedDelayMilliseconds: DELAY_MS, headers: { 'Content-Type': 'application/json' },
      body: '{"base":"ZAR","quote":"USDT","rate":"0.054254","timestamp":"{{now}}"}', transformers: ['response-template'] } }) });
log(`delay mapping POST ${mapping.status} id=${mapping.body?.id}`);
check(mapping.status === 201, 'WireMock delay mapping created');
const driveStart = Date.now();
const codes = {};
try {
  const worker = async () => {
    for (let i = 0; i < 12; i++) {
      const t = Date.now();
      const r = await fetch(`${GATEWAY}/api/v1/merchants/${randomUUID()}/quotes`, { method: 'POST',
        headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sell: 'ZAR', buy: 'USDT', sellAmount: '1000.00' }) });
      await r.text();
      const k = `${r.status} ${Date.now() - t >= DELAY_MS ? 'slow' : 'fast'}`; codes[k] = (codes[k] || 0) + 1;
    }
  };
  await Promise.all(Array.from({ length: 4 }, worker));
} finally {
  const del = await fetch(`${RATES_ADMIN}/__admin/mappings/${mapping.body?.id}`, { method: 'DELETE' });
  const left = await getJson(`${RATES_ADMIN}/__admin/mappings`);
  const delayed = left.body.mappings.filter(m => m.response?.fixedDelayMilliseconds).length;
  log(`delay mapping DELETE ${del.status}; mappings now ${left.body.meta.total}, with fixedDelayMilliseconds ${delayed}`);
  check(del.status === 200 && delayed === 0, 'delay removed, 0 delayed mappings remain');
}
const driveEnd = Date.now();
log(`drive ${new Date(driveStart).toISOString()}..${new Date(driveEnd).toISOString()} quotes ${JSON.stringify(codes)}`);
await sleep(30000);

const q = encodeURIComponent(`{resource.service.name="${SERVICE}" && name="http get" && duration>${DELAY_MS - 100}ms}`);
const found = await getJson(`${G}/api/datasources/proxy/uid/tempo/api/search?q=${q}&start=${Math.floor(driveStart / 1000) - 5}&end=${Math.ceil(driveEnd / 1000) + 5}&limit=100`);
const traces = (found.body?.traces ?? []).sort((a, b) => Number(b.durationMs ?? 0) - Number(a.durationMs ?? 0));
log(`tempo search HTTP ${found.status}: ${traces.length} slow traces`);
check(traces.length > 0, 'a slow trace was found');
const traceId = traces[0]?.traceID;
log(`slowest of ${traces.length}: ${traceId} ${traces[0]?.durationMs} ms`);
const trace = await getJson(`${G}/api/datasources/proxy/uid/tempo/api/v2/traces/${traceId}`);
let server;
for (const rs of trace.body?.trace?.resourceSpans ?? []) {
  if (attr(rs.resource.attributes, 'service.name') !== SERVICE) continue;
  for (const ss of rs.scopeSpans) for (const s of ss.spans) if (s.kind === 'SPAN_KIND_SERVER') server = s;
}
const profileId = attr(server?.attributes, 'pyroscope.profile.id');
const spanStart = Number(BigInt(server.startTimeUnixNano) / 1000000n), spanEnd = Number(BigInt(server.endTimeUnixNano) / 1000000n);
log(`trace ${traceId}: ${SERVICE} SERVER span ${hex(server.spanId)} "${server.name}" ${spanEnd - spanStart} ms, pyroscope.profile.id=${profileId}`);
check(profileId === hex(server.spanId), 'the SERVER span carries its own id as pyroscope.profile.id');

const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1440, height: 1600 } });
const profileCalls = [];
page.on('response', async r => {
  const b = r.request().postData() ?? '';
  if (r.url().includes('/api/ds/query') && b.includes('grafana-pyroscope-datasource')) profileCalls.push({ req: JSON.parse(b), res: await r.text().catch(() => '') });
});
const pane = { datasource: 'tempo', queries: [{ refId: 'A', datasource: { type: 'tempo', uid: 'tempo' }, queryType: 'traceql', query: traceId }], range: { from: String(spanStart - 600000), to: String(spanEnd + 600000) } };
await page.goto(`${G}/explore?schemaVersion=1&orgId=1&panes=${encodeURIComponent(JSON.stringify({ a: pane }))}`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(8000);
await shot(page, 'trace');
const span = page.getByText(server.name, { exact: true });
log(`span rows named "${server.name}": ${await span.count()}`);
check(await span.count() >= 1, 'the SERVER span is shown in the trace view');
// Several spans can share the name (gateway's client span is "http post"); the SERVER name is service-specific.
await span.first().click();
await page.waitForTimeout(3000);
await page.getByRole('button', { name: 'Links' }).first().click();
await page.waitForTimeout(1500);
const link = page.getByText('Profiles for this span');
check(await link.count() === 1, '"Profiles for this span" offered once');
await shot(page, 'span-links');
profileCalls.length = 0;
await link.first().click();
await page.waitForTimeout(10000);
const linkQueries = profileCalls.flatMap(c => c.req.queries.map(x => ({ sel: x.labelSelector, span: x.spanSelector, type: x.profileTypeId, from: c.req.from, to: c.req.to, res: c.res })));
for (const x of linkQueries) log(`link query: ${x.type} ${x.sel} spanSelector=${JSON.stringify(x.span)} range=${x.from}..${x.to}`);
check(linkQueries.some(x => x.sel?.includes(`service_name="${SERVICE}"`) && JSON.stringify(x.span) === JSON.stringify([profileId])),
  `(a) the link's datasource request names ${SERVICE} and spanSelector [${profileId}]`);
const rows = r => { try { return Object.values(JSON.parse(r).results).flatMap(v => v.frames ?? []).map(f => f.data?.values?.[0]?.length ?? 0).reduce((a, b) => a + b, 0); } catch { return 0; } };
const linkRows = Math.max(0, ...linkQueries.map(x => rows(x.res)));
// Root of the flame graph: the 'value' field's first row, in the profile's unit (nanoseconds for wall).
const total = r => { try { const f = Object.values(JSON.parse(r).results).flatMap(v => v.frames ?? [])[0];
  const i = f.schema.fields.findIndex(x => x.name === 'value'); return Number(f.data.values[i][0]) / 1e6; } catch { return 0; } };
const linkTotalMs = Math.max(0, ...linkQueries.map(x => total(x.res)));
const canvas = page.locator('canvas[data-testid="flameGraph"]');
const box = (await canvas.count()) ? await canvas.first().boundingBox() : null;
log(`link flame graph: ${linkRows} rows, root ${linkTotalMs} ms in the response, canvas ${box ? `${Math.round(box.width)}x${Math.round(box.height)}` : 'absent'}`);
check(linkRows > 1 && box && box.height >= 20, '(b) the span flame graph renders non-empty');
log(`(info) ${FRAME} in the span-filtered response: ${linkQueries.some(x => x.res.includes(FRAME))}`);
await shot(page, 'span-flamegraph');

profileCalls.length = 0;
const from = spanStart - PAD_MS, to = spanEnd + PAD_MS;
await page.goto(`${G}/d/ezeebit-fleet/fleet-overview?orgId=1&from=${from}&to=${to}&var-service=${SERVICE}&refresh=`, { waitUntil: 'domcontentloaded' });
await page.waitForTimeout(6000);
const row = page.getByText('Profiling (Pyroscope)', { exact: false }).first();
await row.scrollIntoViewIfNeeded();
await row.click();
await page.waitForTimeout(3000);
const header = page.locator('[data-testid^="data-testid Panel header Wall clock"]').first();
await header.scrollIntoViewIfNeeded();
await page.waitForTimeout(8000);
const rowQueries = profileCalls.flatMap(c => c.req.queries.map(x => ({ sel: x.labelSelector, span: x.spanSelector, from: c.req.from, to: c.req.to, res: c.res })));
for (const x of rowQueries) log(`row query: ${x.sel} spanSelector=${JSON.stringify(x.span ?? null)} range=${x.from}..${x.to} rows=${rows(x.res)} has ${FRAME}=${x.res.includes(FRAME)}`);
check(rowQueries.some(x => x.sel === `{service_name=~"${SERVICE}"}` && Number(x.from) <= from && Number(x.to) >= to && x.res.includes(FRAME)),
  `(c) the service-window view over the trace +/- ${PAD_MS / 1000}s holds ${FRAME}`);
const search = page.getByPlaceholder(/search/i).last();
if (await search.count()) { await search.fill(FRAME); await page.waitForTimeout(1500); }
await header.evaluate(el => el.scrollIntoView({ block: 'start' }));
await page.waitForTimeout(1500);
await shot(page, 'service-window');
await browser.close();
log(`span to profile ${failed === 0 ? 'PASS' : 'FAIL'} (${failed} failed checks)`);
process.exit(failed === 0 ? 0 : 1);
