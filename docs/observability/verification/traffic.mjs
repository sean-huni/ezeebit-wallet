// Real merchant traffic through the gateway, so every dashboard has something true to show:
// deposits, quotes, conversions, a refused quote of each kind, and a burst the limiter refuses.
// `node traffic.mjs` runs it once. The probe merchant makes exactly one quote, one conversion and one stale-rate
// quote, so the suite can find those requests' traces by the merchant id in the gateway span's http.url.
import { randomUUID } from 'node:crypto';

const GATEWAY = process.env.GATEWAY_URL || 'http://localhost:8080';
const LEDGER = process.env.LEDGER_URL || 'http://localhost:8081';
const RATES = process.env.RATES_URL || 'http://localhost:8089';

const json = { 'Content-Type': 'application/json' };

async function call(method, url, body, headers = {}) {
  const res = await fetch(url, { method, headers: { ...json, ...headers }, body: body && JSON.stringify(body) });
  const text = await res.text();
  let data;
  try { data = JSON.parse(text); } catch { data = text; }
  return { status: res.status, data };
}

const deposit = (merchant, currency, amount, key) =>
  call('POST', `${LEDGER}/internal/v1/deposits`, { merchantId: merchant, currency, amount, reference: `rail ${key}` },
    { 'Idempotency-Key': key });

const quote = (merchant, sell, buy, sellAmount) =>
  call('POST', `${GATEWAY}/api/v1/merchants/${merchant}/quotes`, { sell, buy, sellAmount });

const convert = (merchant, quoteId) =>
  call('POST', `${GATEWAY}/api/v1/merchants/${merchant}/conversions`, { quoteId }, { 'Idempotency-Key': randomUUID() });

export async function drive({ rounds = 3 } = {}) {
  const tally = {};
  const count = (what, status) => { const k = `${what} ${status}`; tally[k] = (tally[k] || 0) + 1; };
  const merchants = [randomUUID(), randomUUID(), randomUUID()];
  const [zar, ngn, kes] = merchants;
  const probe = randomUUID();

  for (const [m, cur, amt] of [[zar, 'ZAR', '25000.00'], [ngn, 'NGN', '900000.00'], [kes, 'KES', '60000.00'],
    [probe, 'ZAR', '5000.00']]) {
    const key = randomUUID();
    count('deposit', (await deposit(m, cur, amt, key)).status);
    count('deposit replay', (await deposit(m, cur, amt, key)).status);
  }
  count('deposit refused', (await deposit(zar, 'XYZ', '1.00', randomUUID())).status);

  const pairs = [[zar, 'ZAR', 'USDT', '1000.00'], [zar, 'ZAR', 'USDC', '800.00'], [ngn, 'NGN', 'USDT', '150000.00'],
    [kes, 'KES', 'USDC', '9000.00']];
  for (let r = 0; r < rounds; r++) {
    for (const [m, sell, buy, amount] of pairs) {
      const q = await quote(m, sell, buy, amount);
      count(`quote ${sell}/${buy}`, q.status);
      if (q.status !== 201) continue;
      const c = await convert(m, q.data.data.quoteId);
      count(`conversion ${sell}/${buy}`, `${c.status} ${c.data?.data?.status || c.data?.code || ''}`.trim());
    }
  }

  const pq = await quote(probe, 'ZAR', 'USDT', '1000.00');
  count('probe quote', pq.status);
  if (pq.status === 201) count('probe conversion', (await convert(probe, pq.data.data.quoteId)).status);

  // Refusals: an unsupported pair, a conversion the ledger rejects for funds, and a stale rate.
  count('quote ZAR/NGN unsupported', (await quote(zar, 'ZAR', 'NGN', '100.00')).status);
  const big = await quote(kes, 'KES', 'USDT', '5000000.00');
  if (big.status === 201) {
    const c = await convert(kes, big.data.data.quoteId);
    count('conversion insufficient funds', `${c.status} ${c.data?.code || ''}`.trim());
  }
  const stale = await call('POST', `${RATES}/__admin/mappings`, {
    priority: 1, request: { method: 'GET', urlPath: '/rates', queryParameters: { base: { equalTo: 'USDT' } } },
    response: { status: 200, headers: json, body: JSON.stringify({ base: 'USDT', quote: 'ZAR', rate: '18.4321',
      timestamp: '2020-01-01T00:00:00Z' }) } });
  try {
    count('probe quote USDT/ZAR stale rate', (await quote(probe, 'USDT', 'ZAR', '10.000000')).status);
  } finally {
    await call('DELETE', `${RATES}/__admin/mappings/${stale.data.id}`);
  }

  // A burst past the 100-token bucket, five at a time so the bucket drains rather than contends: about 100 admitted
  // (plus what refills), the rest 429 RATE_LIMITED, every one counted by the limiter.
  const queue = Array.from({ length: 180 }, () => `${GATEWAY}/api/v1/merchants/${ngn}/balances`);
  const worker = async () => { while (queue.length) count('balances burst', (await fetch(queue.pop())).status); };
  await Promise.all(Array.from({ length: 5 }, worker));
  return { tally, probe, merchants };
}

if (import.meta.url === `file://${process.argv[1]}`) {
  const out = await drive();
  console.log(JSON.stringify(out, null, 2));
}
