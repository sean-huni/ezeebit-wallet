// Recaptures every Logs-board screenshot in EVIDENCE.md on the sectioned board: the triage view, a log line
// expanded to its trace link, and that trace opened in Tempo. Drives one demo conversion first so the line is fresh.
// GRAFANA_URL (default localhost:3000), GATEWAY_URL (8080), LEDGER_URL (8081). Captures: out/.
import { chromium } from 'playwright';
import { mkdirSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import path from 'node:path';
const G = process.env.GRAFANA_URL || 'http://localhost:3000';
const GW = process.env.GATEWAY_URL || 'http://localhost:8080';
const LEDGER = process.env.LEDGER_URL || 'http://localhost:8081';
const OUT = new URL('./out/', import.meta.url).pathname;
mkdirSync(OUT, { recursive: true });
const fails = [];
const check = (ok, msg) => { console.log(`${ok ? 'PASS' : 'FAIL'} ${msg}`); if (!ok) fails.push(msg); };
const post = async (url, body, headers = {}) => {
  const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });
  return { status: r.status, json: await r.json().catch(() => ({})) };
};

// 1. One demo conversion, so "conversion accepted" is in the last 15 minutes.
const m = randomUUID();
const dep = await post(`${LEDGER}/internal/v1/deposits`, { merchantId: m, currency: 'ZAR', amount: '1500.00', reference: 'screenshot run' }, { 'Idempotency-Key': `shot-${m}` });
const quote = await post(`${GW}/api/v1/merchants/${m}/quotes`, { sell: 'ZAR', buy: 'USDT', sellAmount: '1000.00' });
const conv = await post(`${GW}/api/v1/merchants/${m}/conversions`, { quoteId: quote.json?.data?.quoteId }, { 'Idempotency-Key': `convert-${m}` });
check(dep.status === 201 && quote.status === 201 && conv.status === 201 && conv.json?.data?.status === 'COMPLETED',
  `demo conversion: deposit ${dep.status}, quote ${quote.status}, convert ${conv.status} ${conv.json?.data?.status} (merchant ${m})`);
await new Promise(r => setTimeout(r, 8000)); // log export batch and Loki ingest

const browser = await chromium.launch({ headless: true, channel: 'chrome' });
const settle = async (page, landmark) => { await page.getByText(landmark).first().waitFor({ timeout: 30000 }); await page.waitForTimeout(7000); };
try {
  // 2. The triage view, last hour, desktop width.
  const p1 = await browser.newPage({ viewport: { width: 1600, height: 1000 } });
  await p1.goto(`${G}/d/ezeebit-logs/logs?from=now-1h&to=now&refresh=`);
  await settle(p1, '1 · Errors');
  const totals = async title => {
    const lines = (await p1.getByTestId(`data-testid Panel header ${title}`).innerText()).split('\n').map(s => s.trim());
    return lines.flatMap((s, i) => (/^[a-z]+-svc$/.test(s) && /^\d+$/.test(lines[i + 1] || '') ? [`${s} ${lines[i + 1]}`] : []));
  };
  const errTotals = await totals('Errors by service');
  const warnTotals = await totals('Warnings by service');
  console.log('Errors by service legend:', JSON.stringify(errTotals));
  console.log('Warnings by service legend:', JSON.stringify(warnTotals));
  const errText = await p1.getByTestId('data-testid Panel header Errors').innerText();
  check(errTotals.length > 0 || errText.includes('No'), `Errors section rendered (${errTotals.length} services in legend)`);
  check(warnTotals.length > 0, `Warnings section has data (${warnTotals.length} services in legend)`);
  await p1.screenshot({ path: path.join(OUT, '2026-11-06-logs-triage.png') });
  await p1.close();

  // 3. The conversion's log line, expanded in the Info section, offers its trace.
  const p2 = await browser.newPage({ viewport: { width: 1440, height: 900 } });
  await p2.goto(`${G}/d/ezeebit-logs/logs?from=now-15m&to=now&refresh=&var-service=conversion-svc&var-search=${encodeURIComponent('conversion accepted')}`);
  await settle(p2, '3 · Info');
  // Grafana mounts a panel only once it is in view, and scrolls inside its own pane: bring row 3 to the top.
  await p2.getByText('3 · Info').first().evaluate(el => {
    el.scrollIntoView({ block: 'start' });
    let pane = el.parentElement;
    while (pane && !(pane.scrollHeight > pane.clientHeight + 10 && /auto|scroll/.test(getComputedStyle(pane).overflowY))) pane = pane.parentElement;
    if (pane) pane.scrollBy(0, -60); // keep the row title clear of the sticky variable bar
  });
  await p2.waitForTimeout(6000);
  const info = p2.getByTestId('data-testid Panel header Info');
  const line = info.getByText('conversion accepted', { exact: false }).first();
  check(await line.count() > 0, 'Info section shows a "conversion accepted" line for conversion-svc');
  await line.click();
  await p2.waitForTimeout(1500);
  const traceLink = p2.getByRole('link', { name: /Trace: [0-9a-f]{32}/ }).or(p2.getByText(/Trace: [0-9a-f]{32}/)).first();
  const linked = ((await traceLink.textContent().catch(() => '')) || '').match(/[0-9a-f]{32}/)?.[0];
  check(Boolean(linked), `expanded line offers its trace: ${linked}`);
  console.log('expanded line:', JSON.stringify((await line.textContent()).trim()), 'captured', new Date().toISOString());
  await p2.screenshot({ path: path.join(OUT, '2026-11-06-logs-trace-correlated.png') });

  // 4. Following the link opens that trace in Tempo.
  if (linked) {
    const [popup] = await Promise.all([p2.context().waitForEvent('page', { timeout: 8000 }).catch(() => null), traceLink.click()]);
    const target = popup || p2;
    await target.waitForTimeout(8000);
    const url = decodeURIComponent(target.url());
    const text = await target.locator('body').innerText();
    check(/explore/.test(url) && url.includes(linked) && /tempo/.test(url), `trace link opens Explore on Tempo with ${linked}`);
    check(['gateway-svc', 'conversion-svc', 'ledger-svc'].every(s => text.includes(s)), 'opened trace shows gateway-svc, conversion-svc and ledger-svc');
    await target.screenshot({ path: path.join(OUT, '2026-11-06-logs-trace-opened-in-tempo.png') });
  }
  await p2.close();
} finally {
  await browser.close();
}
console.log(`RESULT ${fails.length ? 'FAIL' : 'PASS'} (${fails.length} failed)`);
process.exitCode = fails.length ? 1 : 0;
