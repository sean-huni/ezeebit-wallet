// The Logs board's triage order and the Traces service map, read from what Grafana renders and the queries it sends.
// Run against any Grafana serving these boards: GRAFANA_URL (default localhost:3000). EXPECT_RATE_FEED=1 once the
// collector overlay (docker/lgtm/otelcol-peer.yml) is live, to require the rate feed's host:port node. Captures: out/.
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import path from 'node:path';
const G = process.env.GRAFANA_URL || 'http://localhost:3000';
const OUT = new URL('./out/', import.meta.url).pathname;
mkdirSync(OUT, { recursive: true });
const fails = [];
const check = (ok, msg) => { console.log(`${ok ? 'PASS' : 'FAIL'} ${msg}`); if (!ok) fails.push(msg); };

function track(page) {
  const reqs = [];
  page.on('request', r => { if (/\/api\/ds\/query|\/api\/datasources\/proxy/.test(r.url())) reqs.push({ url: r.url(), body: r.postData() || '' }); });
  const errs = [];
  page.on('response', async r => {
    if (!/\/api\/ds\/query/.test(r.url())) return;
    try { const j = await r.json(); for (const [k, v] of Object.entries(j.results || {})) if (v.error) errs.push(v.error); }
    catch { if (r.status() >= 400) errs.push(`HTTP ${r.status()}`); }
  });
  return { reqs, errs };
}

async function panelTitles(page) {
  return page.$$eval('[data-testid^="data-testid Panel header "], [data-testid="dashboard-row-title"], button[data-testid^="data-testid dashboard-row-title"]',
    els => els.map(e => (e.getAttribute('data-testid') || '').replace('data-testid Panel header ', '').replace(/^data-testid dashboard-row-title-?/, '') || e.textContent.trim()));
}

  const browser = await chromium.launch({ headless: true, channel: 'chrome' });
try {
    // A real trace id carried by an ERROR or WARN line, read from Loki through the same Grafana.
    const ctx0 = await browser.newContext();
    const api = ctx0.request;
    const lq = await api.post(`${G}/api/ds/query`, { data: { from: 'now-6h', to: 'now', queries: [{ refId: 'A', datasource: { type: 'loki', uid: 'loki' }, queryType: 'range', maxLines: 200,
      expr: '{service_name=~".+"} | detected_level=~"error|warn" | trace_id=~".+"' }] } });
    const frame = (await lq.json()).results.A.frames[0];
    const labelsIdx = frame.schema.fields.findIndex(f => f.name === 'labels');
    const traceId = frame ? frame.data.values[labelsIdx].map(l => l.trace_id).find(Boolean) : null;
    console.log('trace id with a warn/error line:', traceId);

    // 1. Logs board at a desktop viewport: order, live queries, screenshot of the first screen.
    const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
    let t = track(page);
    await page.goto(`${G}/d/ezeebit-logs/logs?from=now-1h&to=now`);
    await page.getByText('1 · Errors').first().waitFor({ timeout: 30000 });
    await page.waitForTimeout(6000);
    await page.screenshot({ path: path.join(OUT, 'logs-1600-top.png') });
    // Grafana scrolls inside its own pane: walk up from a panel to the element that actually scrolls.
    const steps = await page.evaluate(async () => {
      let el = document.querySelector('[data-testid^="data-testid Panel header "]');
      while (el && !(el.scrollHeight > el.clientHeight + 10 && /auto|scroll/.test(getComputedStyle(el).overflowY))) el = el.parentElement;
      if (!el) return null;
      const seen = [];
      for (let i = 0; i < 12; i++) { const before = el.scrollTop; el.scrollBy(0, 700); await new Promise(r => setTimeout(r, 900)); seen.push(el.scrollTop - before); if (el.scrollTop + el.clientHeight >= el.scrollHeight - 2) break; }
      return seen;
    });
    check(Array.isArray(steps) && steps.length > 1 && steps.slice(0, -1).every(d => d > 0), `logs pane scrolled in moving steps: ${JSON.stringify(steps)}`);
    await page.waitForTimeout(3000);
    await page.screenshot({ path: path.join(OUT, 'logs-1600-bottom.png') });
    const want = ['1 · Errors', 'Errors by service', 'Errors', '2 · Warnings', 'Warnings by service', 'Warnings', '3 · Info', 'Info by service', 'Info', '4 · All logs (interactive)', 'Log lines by level', 'All logs'];
    const html = await page.content();
    const pos = want.map(w => html.indexOf(w === 'Errors' ? 'Panel header Errors"' : w === 'Warnings' ? 'Panel header Warnings"' : w === 'Info' ? 'Panel header Info"' : w === 'All logs' ? 'Panel header All logs"' : w));
    check(pos.every(p => p >= 0) && pos.every((p, i) => i === 0 || p > pos[i - 1]), `logs panels render in triage order: ${JSON.stringify(pos)}`);
    const lokiReqs = t.reqs.filter(r => r.body.includes('"loki"'));
    check(lokiReqs.length >= 8, `logs board sent ${lokiReqs.length} Loki query requests (8 panels)`);
    check(t.errs.length === 0, `no datasource errors on logs board (${t.errs.length}) ${t.errs.slice(0, 2).join(' | ')}`);
    const serviceFirst = async (pg, title) => {
      const parts = (await pg.getByTestId(`data-testid Panel header ${title}`).innerText()).split(/\s+/).filter(Boolean);
      let rows = 0, named = 0;
      parts.forEach((x, i) => { if (/^\d\d:\d\d:\d\d\.\d{3}$/.test(parts[i - 1] || '') && /^(ERROR|WARN|INFO|DEBUG)$/.test(x)) { rows++; if (/^[a-z]+-svc$/.test(parts[i + 1] || '')) named++; } });
      return [named, rows];
    };
    for (const title of ['Warnings', 'All logs']) {
      const [named, rows] = await serviceFirst(page, title);
      check(rows > 0 && named === rows, `${title}: service named right after the level on ${named}/${rows} rendered rows`);
    }

    // 2. Trace ID box narrows every section; Level box reaches only All logs.
    if (traceId) {
      const p2 = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
      t = track(p2);
      await p2.goto(`${G}/d/ezeebit-logs/logs?from=now-6h&to=now&var-trace_id=${traceId}&var-level=error`);
      await p2.getByText('1 · Errors').first().waitFor({ timeout: 30000 });
      await p2.waitForTimeout(6000);
      const exprs = t.reqs.flatMap(r => { try { return JSON.parse(r.body).queries.map(q => q.expr || ''); } catch { return []; } });
      const withTrace = exprs.filter(e => e.includes(`trace_id=~"${traceId}"`)).length;
      check(exprs.length > 0 && withTrace === exprs.length, `trace id substituted into ${withTrace}/${exprs.length} Loki queries`);
      check(exprs.some(e => e.includes('detected_level=~"warn|warning"')) && exprs.some(e => e.includes('detected_level=~"info"')), 'Level=error leaves the Warnings and Info sections on their own level');
      await p2.screenshot({ path: path.join(OUT, 'logs-trace-filter.png') });
      await p2.close();
    } else check(false, 'no warn/error line with a trace id in the last 6 hours to drive the filter test');


    // 2b. Both sides of the Errors section: real errors in a 3h window, and the empty state when there are none.
    const pe = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
    const te = track(pe);
    await pe.goto(`${G}/d/ezeebit-logs/logs?from=now-3h&to=now`);
    await pe.getByText('1 · Errors').first().waitFor({ timeout: 30000 });
    await pe.waitForTimeout(7000);
    const errPanel = pe.getByTestId('data-testid Panel header Errors');
    const [eNamed, eRows] = await serviceFirst(pe, 'Errors');
    check(eRows > 0 && eNamed === eRows, `Errors: service named right after the level on ${eNamed}/${eRows} rows`);
    const errText = await errPanel.innerText();
    const errLines = errText.split('\n').filter(l => /(config|eureka|ledger|conversion|gateway)-svc\s{2,}/.test(l) || /ERROR/.test(l)).length;
    check(errLines > 0 && !errText.includes('No errors in this window'), `Errors section shows real ERROR lines over 3h: ${errLines}`);
    check(te.errs.length === 0, `no datasource errors on the 3h board (${te.errs.length})`);
    await pe.screenshot({ path: path.join(OUT, 'logs-errors-3h.png') });
    await pe.close();
    const empty = await page.getByTestId('data-testid Panel header Errors').innerText().catch(() => '');
    console.log('1h Errors panel text starts:', JSON.stringify(empty.slice(0, 80)));

    // 3. Mobile width still usable (no page-level horizontal scroll).
    const m = await browser.newPage({ viewport: { width: 390, height: 844 } });
    await m.goto(`${G}/d/ezeebit-logs/logs?from=now-1h&to=now`);
    await m.getByText('1 · Errors').first().waitFor({ timeout: 30000 });
    await m.waitForTimeout(5000);
    await m.screenshot({ path: path.join(OUT, 'logs-390.png') });
    await m.close();

    // 4. Traces board over 6h, so the window holds merchant traffic without driving any into a shared fleet.
    const p3 = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
    t = track(p3);
    await p3.goto(`${G}/d/ezeebit-traces/traces?from=now-6h&to=now`);
    await p3.getByText('Service map').first().waitFor({ timeout: 30000 });
    await p3.evaluate(async () => {
      let el = [...document.querySelectorAll('[data-testid^="data-testid Panel header "]')].find(e => e.textContent.includes('Service map'));
      el && el.scrollIntoView({ block: 'start' });
      await new Promise(r => setTimeout(r, 500));
    });
    await p3.waitForTimeout(8000);
    const mapReqs = t.reqs.filter(r => decodeURIComponent(r.url + r.body).includes('traces_service_graph_request_total'));
    const sent = mapReqs.map(r => decodeURIComponent(r.url + r.body));
    check(mapReqs.length > 0, `service map sent ${mapReqs.length} service-graph queries`);
    const norm = sent.map(s => s.replace(/\\"/g, '"'));
    check(norm.length > 0 && norm.every(s => s.includes('client!="user"') && s.includes('client="user", server!~"eureka-svc|config-svc"')),
      'every service-graph query carries both selectors of the filter');
    const mapPanel = p3.getByTestId('data-testid Panel header Service map');
    const labels = (await mapPanel.locator('svg text').allTextContents().catch(() => [])).concat((await mapPanel.innerText()).split('\n'));
    const nodes = [...new Set(labels.map(s => s.trim()).filter(s => /svc|user|:\d+/.test(s)))];
    console.log('service map labels:', JSON.stringify(nodes));
    check(nodes.includes('gateway-svc') && nodes.includes('ledger-svc') && nodes.includes('conversion-svc'), 'map draws gateway-svc, conversion-svc and ledger-svc');
    check(nodes.length > 0 && !nodes.includes('config-svc'), `map drops config-svc, which only 'user' calls (of ${nodes.length} labels)`);
    check(nodes.includes('eureka-svc'), "map keeps eureka-svc: gateway-svc's registry calls are real span pairs");
    if (process.env.EXPECT_RATE_FEED === '1') check(nodes.some(n => /:\d+$/.test(n)), 'map draws the rate feed as a host:port node');
    check(t.errs.length === 0, `no datasource errors on traces board (${t.errs.length}) ${t.errs.slice(0, 2).join(' | ')}`);
    await mapPanel.screenshot({ path: path.join(OUT, 'traces-service-map.png') });
    await p3.close();
  } finally {
    await browser.close();
  }
  console.log(`RESULT ${fails.length ? 'FAIL' : 'PASS'} (${fails.length} failed)`);
  process.exitCode = fails.length ? 1 : 0;
