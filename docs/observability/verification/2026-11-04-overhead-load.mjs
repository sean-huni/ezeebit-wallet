// Open-loop load: RATE units/s for SECONDS; each unit = quote ZAR/USDT, convert it, read balances, for merchant i % 8.
// Usage: node 2026-11-04-overhead-load.mjs <seconds> <label>. Env GATEWAY_URL, MERCHANTS (from 2026-11-04-overhead-deposit.sh).
import { randomUUID } from 'node:crypto';
const GATEWAY = process.env.GATEWAY_URL ?? 'http://127.0.0.1:8080';
const SECONDS = Number(process.argv[2] ?? 300), LABEL = process.argv[3] ?? 'run', RATE = 10;
const json = { 'Content-Type': 'application/json' };
const merchants = (process.env.MERCHANTS ?? '').split(',').filter(Boolean);
if (merchants.length !== 8) { console.log('MERCHANTS must name 8 funded merchants (2026-11-04-overhead-deposit.sh)'); process.exit(2); }
const lat = { quote: [], convert: [], balances: [] }, codes = {};
const timed = async (op, url, init) => {
  const t = performance.now();
  try { const r = await fetch(url, init); const body = await r.text(); lat[op].push(performance.now() - t);
    codes[`${op} ${r.status}`] = (codes[`${op} ${r.status}`] || 0) + 1; return { status: r.status, body };
  } catch (e) { codes[`${op} ERR`] = (codes[`${op} ERR`] || 0) + 1; return { status: 0 }; }
};
const unit = async i => {
  const m = merchants[i % merchants.length];
  const q = await timed('quote', `${GATEWAY}/api/v1/merchants/${m}/quotes`, { method: 'POST', headers: json,
    body: JSON.stringify({ sell: 'ZAR', buy: 'USDT', sellAmount: '100.00' }) });
  if (q.status === 201) await timed('convert', `${GATEWAY}/api/v1/merchants/${m}/conversions`, { method: 'POST',
    headers: { ...json, 'Idempotency-Key': randomUUID() }, body: JSON.stringify({ quoteId: JSON.parse(q.body).data.quoteId }) });
  await timed('balances', `${GATEWAY}/api/v1/merchants/${m}/balances`, { method: 'GET' });
};
const start = Date.now(), inflight = [];
for (let i = 0; i < SECONDS * RATE; i++) {
  const due = start + (i * 1000) / RATE, wait = due - Date.now();
  if (wait > 0) await new Promise(r => setTimeout(r, wait));
  inflight.push(unit(i));
}
await Promise.all(inflight);
const end = Date.now();
const p = (a, q) => { const s = [...a].sort((x, y) => x - y); return s.length ? s[Math.min(s.length - 1, Math.ceil(q * s.length) - 1)] : NaN; };
const all = Object.values(lat).flat();
console.log(JSON.stringify({ label: LABEL, start: new Date(start).toISOString(), end: new Date(end).toISOString(), startMs: start, endMs: end,
  units: SECONDS * RATE, codes, clientP95ms: Object.fromEntries([...Object.entries(lat), ['all', all]].map(([k, v]) => [k, Number(p(v, 0.95).toFixed(1))])) }));
