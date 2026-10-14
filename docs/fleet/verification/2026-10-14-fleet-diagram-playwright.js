// Verifies docs/fleet/fleet-architecture.html and its detail fleet-ledger-grpc.html in installed Chrome.
// Run: npm install, then node <this file>. The repo is served on 127.0.0.1; TARGET_FILE (a path under docs/fleet)
// replaces the overview to red-proof a broken copy, TARGET_URL replaces it outright, PW_OUT sets the screenshot dir.
const { chromium } = require('playwright');
const { spawn } = require('node:child_process');
const net = require('node:net');
const path = require('node:path');

const REPO = path.resolve(__dirname, '../..', '..');
const OUT = process.env.PW_OUT || __dirname;
const VIEWBOX_W = 1360;
const WIDTHS = [360, 390, 430, 768, 1280, 1440, 1920];
const results = [];

function check(name, ok, detail) {
  results.push({ name, ok });
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail === undefined ? '' : `  [${detail}]`}`);
}

function freePort() {
  return new Promise((resolve, reject) => {
    const srv = net.createServer();
    srv.listen(0, '127.0.0.1', () => { const { port } = srv.address(); srv.close(() => resolve(port)); });
    srv.on('error', reject);
  });
}

async function serve() {
  const port = await freePort();
  const proc = spawn('python3', ['-m', 'http.server', String(port), '--bind', '127.0.0.1', '--directory', REPO],
    { stdio: 'ignore' });
  const base = `http://127.0.0.1:${port}/docs/fleet/`;
  for (let i = 0; i < 50; i++) {
    try { if ((await fetch(`${base}fleet-architecture.html`)).status === 200) return { proc, base }; } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 100));
  }
  proc.kill();
  throw new Error(`static server never answered 200 on ${base}`);
}

// Max colour score inside a +-4px window around an SVG point, read from a real screenshot of the svg.
async function probeConnectors(page, probes) {
  const svg = page.locator('svg[role="img"]');
  const png = await svg.screenshot();
  return page.evaluate(async ({ b64, probes, vbw }) => {
    const img = new Image();
    img.src = `data:image/png;base64,${b64}`;
    await img.decode();
    const c = document.createElement('canvas');
    c.width = img.width; c.height = img.height;
    const ctx = c.getContext('2d');
    ctx.drawImage(img, 0, 0);
    const k = img.width / vbw;
    const scores = {
      cyan: (r, g, b) => Math.min(g, b) - r,
      green: (r, g, b) => g - Math.max(r, b),
      magenta: (r, g, b) => Math.min(r, b) - g,
      grey: (r, g, b) => Math.min(r, g, b),
      dark: (r, g, b) => -(r + g + b) / 3,
    };
    return probes.map(({ name, x, y, hue }) => {
      let best = -999;
      let px = null;
      for (let dy = -4; dy <= 4; dy++) {
        for (let dx = -4; dx <= 4; dx++) {
          const [r, g, b] = ctx.getImageData(Math.round(x * k) + dx, Math.round(y * k) + dy, 1, 1).data;
          const score = scores[hue](r, g, b);
          if (score > best) { best = score; px = [r, g, b]; }
        }
      }
      return { name, hue, best, px };
    });
  }, { b64: png.toString('base64'), probes, vbw: VIEWBOX_W });
}

async function geometry(page) {
  return page.evaluate(() => {
    const svg = document.querySelector('svg[role="img"]');
    const box = (el) => { const b = el.getBBox(); return { x: b.x, y: b.y, w: b.width, h: b.height }; };
    const inside = (a, b, m = 0) => a.x >= b.x + m && a.y >= b.y - 0.5 && a.x + a.w <= b.x + b.w - m && a.y + a.h <= b.y + b.h + 0.5;
    const hit = (a, b) => a.x < b.x + b.w && b.x < a.x + a.w && a.y < b.y + b.h && b.y < a.y + a.h;
    const all = [...svg.querySelectorAll('*')];
    const texts = [...svg.querySelectorAll('text')];
    const nodes = [...svg.querySelectorAll('rect[height="76"], rect[height="44"]')];
    const masks = [...svg.querySelectorAll('rect[height="16"][fill="url(#wall)"]')];
    const labelMisfit = [];
    for (const m of masks) {
      const t = m.nextElementSibling;
      if (!t || t.tagName !== 'text' || !inside(box(t), box(m), 2)) labelMisfit.push(t ? t.textContent : '(no text)');
    }
    const maskUnderLaterNode = [];
    for (const m of masks) {
      const mi = all.indexOf(m);
      for (const n of nodes) if (all.indexOf(n) > mi && hit(box(m), box(n))) maskUnderLaterNode.push(m.nextElementSibling.textContent);
    }
    const nodeTextOut = [];
    for (const t of texts) {
      const tb = box(t);
      const owner = nodes.find((n) => hit(tb, box(n)));
      if (owner && !inside(tb, box(owner), 4)) nodeTextOut.push(t.textContent);
    }
    const overlaps = [];
    for (let i = 0; i < texts.length; i++) {
      for (let j = i + 1; j < texts.length; j++) {
        if (hit(box(texts[i]), box(texts[j]))) overlaps.push(`${texts[i].textContent} / ${texts[j].textContent}`);
      }
    }
    const outOfView = texts.filter((t) => { const b = box(t); return b.x < 0 || b.y < 0 || b.x + b.w > 1360 || b.y + b.h > 800; })
      .map((t) => t.textContent);
    const sizes = texts.map((t) => parseFloat(getComputedStyle(t).fontSize));
    const families = [...new Set(texts.map((t) => getComputedStyle(t).fontFamily))];
    // Connectors are orthogonal: a line is horizontal or vertical, a path uses only M, H, V and Q corners.
    const diagonal = [];
    for (const l of svg.querySelectorAll('line')) {
      const [x1, y1, x2, y2] = ['x1', 'y1', 'x2', 'y2'].map((a) => l.getAttribute(a));
      if (x1 !== x2 && y1 !== y2) diagonal.push(`line ${x1},${y1} ${x2},${y2}`);
    }
    for (const p of svg.querySelectorAll('path')) {
      const d = p.getAttribute('d');
      if (/[^MHVQ0-9.,\s-]/.test(d)) diagonal.push(`path ${d}`);
    }
    // Each node's texts, so a check can ask whether a named component is drawn at all.
    const nodeTexts = nodes.filter((n) => n.getAttribute('stroke')).map((n) => texts
      .filter((t) => inside(box(t), box(n), 0)).map((t) => t.textContent).join(' | '));
    const labels = masks.map((m) => m.nextElementSibling && m.nextElementSibling.textContent);
    return { textCount: texts.length, maskCount: masks.length, nodeCount: nodes.length, labelMisfit, maskUnderLaterNode,
      nodeTextOut, overlaps, outOfView, minSvgFont: Math.min(...sizes), families, diagonal, nodeTexts, labels,
      allText: texts.map((t) => t.textContent) };
  });
}

const PAGES = [
  {
    file: 'fleet-architecture.html',
    prefix: '',
    shot: 'fleet',
    link: 'fleet-ledger-grpc.html',
    probes: [
      { name: 'merchant ingress (green tube)', x: 60, y: 406, hue: 'green' },
      { name: 'gateway tube border', x: 232, y: 368, hue: 'green' },
      { name: 'balances', x: 590, y: 334, hue: 'cyan' },
      { name: 'quote+convert', x: 590, y: 478, hue: 'cyan' },
      { name: 'grpc conversion to ledger (vertical line)', x: 720, y: 430, hue: 'magenta' },
      { name: 'rates (horizontal line)', x: 1020, y: 434, hue: 'cyan' },
      { name: 'kafka dead-letter edge (dashed)', x: 1020, y: 534, hue: 'grey' },
      { name: 'kafka node border', x: 1152, y: 496, hue: 'grey' },
      { name: 'slack alerts (vertical line)', x: 1248, y: 100, hue: 'cyan' },
      { name: 'legend http line', x: 302, y: 782, hue: 'cyan' },
      { name: 'legend grpc line', x: 78, y: 782, hue: 'magenta' },
      { name: 'slack endpoint border', x: 1200, y: 52, hue: 'cyan' },
      { name: 'CONTROL empty core-zone floor', x: 700, y: 600, hue: 'dark' },
      { name: 'CONTROL gap between kafka and redis', x: 1152, y: 584, hue: 'dark' },
    ],
    nodes: [['Kafka', ':9092'], ['Ledger', 'gRPC :9081'], ['WireMock', ':8089'], ['Redis'], ['Postgres'], ['LGTM']],
    labels: ['GRPC :9081', 'RATES', 'DEAD LETTER'],
    absentLabels: ['POSTINGS'],
  },
  {
    file: 'fleet-ledger-grpc.html',
    prefix: 'detail: ',
    shot: 'fleet-ledger-grpc',
    link: 'fleet-architecture.html',
    probes: [
      { name: 'ledger client tube border', x: 456, y: 320, hue: 'green' },
      { name: 'grpc client to ledger (horizontal line)', x: 640, y: 358, hue: 'magenta' },
      { name: 'resolve via eureka (dashed)', x: 600, y: 166, hue: 'grey' },
      { name: 'dead letter to kafka (dashed)', x: 1000, y: 426, hue: 'grey' },
      { name: 'counter to alert rules (dashed)', x: 1000, y: 546, hue: 'grey' },
      { name: 'page to slack (vertical line)', x: 1152, y: 604, hue: 'cyan' },
      { name: 'legend grpc line', x: 78, y: 782, hue: 'magenta' },
      { name: 'slack endpoint border', x: 1100, y: 672, hue: 'cyan' },
      { name: 'CONTROL empty conversion-zone floor', x: 150, y: 380, hue: 'dark' },
    ],
    nodes: [['Dead-letter topic', ':9092'], ['Ledger gRPC', ':9081'], ['Ledger client'], ['Service registry'], ['Alert rules'], ['Slack']],
    labels: ['GRPC :9081', 'DEAD LETTER', 'RESOLVE', 'GRPCPORT', 'COUNTER', 'PAGE'],
    absentLabels: [],
    texts: ['ezeebit.conversion.ledger.deadletter'],
  },
];

async function verifyPage(browser, url, spec) {
  const P = spec.prefix;
  console.log(`target ${url}`);
  const ctx = await browser.newContext({ viewport: { width: 1440, height: 1100 }, deviceScaleFactor: 2 });
  const page = await ctx.newPage();
  const consoleErrors = [];
  const failed = [];
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('requestfailed', (r) => failed.push(r.url()));
  page.on('response', (r) => { if (r.status() >= 400) consoleErrors.push(`${r.status()} ${r.url()}`); });
  await page.goto(url);
  await page.evaluate(() => document.fonts.ready);

  check(`${P}no console errors`, consoleErrors.length === 0, consoleErrors.join(' | ') || '0');
  check(`${P}no failed requests`, failed.length === 0, failed.join(' | ') || '0');

  const fonts = await page.evaluate(() => [...document.fonts]
    .filter((f) => f.family.includes('IBM Plex') && f.status === 'loaded').map((f) => `${f.family}|${f.style}|${f.weight}`));
  const uniq = [...new Set(fonts)];
  check(`${P}IBM Plex faces loaded (sans 600, sans italic, mono)`,
    uniq.some((f) => /Sans\|normal\|600/.test(f)) && uniq.some((f) => /Sans\|italic/.test(f)) && uniq.some((f) => /Mono/.test(f)),
    uniq.join(', ') || 'none loaded');
  const a11y = await page.evaluate(() => {
    const svg = document.querySelector('svg[role="img"]');
    const ids = svg.getAttribute('aria-labelledby').split(' ');
    return { first: svg.firstElementChild.tagName, filled: ids.map((id) => (document.getElementById(id) || {}).textContent || '') };
  });
  check(`${P}svg title first child, title and desc filled`, a11y.first === 'title' && a11y.filled.every((t) => t.length > 0),
    a11y.filled.map((t) => t.slice(0, 30)).join(' | '));

  const g = await geometry(page);
  check(`${P}every svg text uses IBM Plex`, g.families.every((f) => f.includes('IBM Plex')), g.families.join(' | '));
  check(`${P}smallest svg text >= 11px (viewBox units)`, g.minSvgFont >= 11, `${g.minSvgFont}px over ${g.textCount} texts`);
  check(`${P}every arrow label fits its mask (2px side margin)`, g.labelMisfit.length === 0, `${g.maskCount} masks; ${g.labelMisfit.join(', ') || 'none out'}`);
  check(`${P}no label mask under a node drawn after it`, g.maskUnderLaterNode.length === 0, g.maskUnderLaterNode.join(', ') || `0 of ${g.maskCount}`);
  check(`${P}node text inside its node (4px margin)`, g.nodeTextOut.length === 0, g.nodeTextOut.join(', ') || `${g.nodeCount} nodes`);
  check(`${P}no two texts overlap`, g.overlaps.length === 0, g.overlaps.join(' ; ') || `0 pairs of ${g.textCount}`);
  check(`${P}all text inside the viewBox`, g.outOfView.length === 0, g.outOfView.join(', ') || 'all in');
  check(`${P}every connector is horizontal or vertical`, g.diagonal.length === 0, g.diagonal.join(' ; ') || 'none diagonal');

  for (const want of spec.nodes) {
    const found = g.nodeTexts.find((t) => want.every((w) => t.includes(w)));
    check(`${P}node drawn: ${want.join(' + ')}`, found !== undefined, found || `none of ${g.nodeTexts.length} nodes`);
  }
  for (const l of spec.labels) check(`${P}edge label present: ${l}`, g.labels.includes(l), g.labels.join(', '));
  for (const l of spec.absentLabels) check(`${P}retired edge label absent: ${l}`, !g.labels.includes(l), g.labels.join(', '));
  for (const t of spec.texts || []) check(`${P}text present: ${t}`, g.allText.includes(t), `${g.textCount} texts`);

  const doc = await page.evaluate(() => ({
    html: document.documentElement.outerHTML,
    links: [...document.querySelectorAll('.detail-link a')].map((a) => ({ href: a.getAttribute('href'), abs: a.href })),
  }));
  check(`${P}no em dash in the page`, !doc.html.includes('\u2014') && !doc.html.includes('&mdash;'), 'U+2014 and entity');
  const link = doc.links.find((l) => l.href === spec.link);
  const linkStatus = link ? await page.evaluate(async (u) => (await fetch(u)).status, link.abs) : 0;
  check(`${P}links to ${spec.link} and it answers 200`, linkStatus === 200, link ? `${link.abs} -> ${linkStatus}` : 'no link');

  const minHtmlFont = await page.evaluate(() => Math.min(...[...document.querySelectorAll('.frame > *:not(.diagram-container)')]
    .map((e) => parseFloat(getComputedStyle(e).fontSize))));
  check(`${P}page text >= 11px`, minHtmlFont >= 11, `${minHtmlFont}px`);

  for (const p of await probeConnectors(page, spec.probes)) {
    const ok = p.hue === 'dark' ? Math.max(...p.px) < 60 : p.best > 60;
    check(`${P}pixel: ${p.name} renders ${p.hue}`, ok, `score ${p.best} rgb(${p.px})`);
  }

  const hover = await page.evaluate(() => {
    const svg = document.querySelector('svg[role="img"]');
    const r = svg.getBoundingClientRect();
    const at = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
    const h1 = document.querySelector('h1').getBoundingClientRect();
    const ctl = document.elementFromPoint(h1.left + 10, h1.top + h1.height / 2);
    return { svgHit: at instanceof SVGElement, at: at && at.tagName, control: ctl && ctl.tagName };
  });
  check(`${P}hover never targets the svg (no <title> tooltip)`, !hover.svgHit && hover.control === 'H1',
    `center hits ${hover.at}; control hits ${hover.control}`);

  // Each width gets a fresh context at that size: resizing a live page made Chrome tile its fullPage captures.
  for (const w of WIDTHS) {
    const sweepCtx = await browser.newContext({ viewport: { width: w, height: 900 }, deviceScaleFactor: 2 });
    const sweep = await sweepCtx.newPage();
    await sweep.goto(url);
    await sweep.evaluate(() => document.fonts.ready);
    const m = await sweep.evaluate(() => {
      window.scrollTo(9999, 0);
      const pageScrollX = window.scrollX;
      window.scrollTo(0, 0);
      const c = document.querySelector('.diagram-container');
      const svgW = document.querySelector('svg[role="img"]').getBoundingClientRect().width;
      c.scrollLeft = 99999;
      const moved = c.scrollLeft;
      c.scrollLeft = 0;
      return { iw: window.innerWidth, pageScrollX, svgW, overflow: c.scrollWidth > c.clientWidth, moved, cw: c.clientWidth };
    });
    const scale = m.svgW / VIEWBOX_W;
    const needsScroll = m.cw < VIEWBOX_W;
    check(`${P}${w}px: layout viewport == device width`, m.iw === w, `innerWidth ${m.iw}`);
    check(`${P}${w}px: page never scrolls sideways`, m.pageScrollX === 0, `scrollX ${m.pageScrollX}`);
    check(`${P}${w}px: diagram scale >= 1 (text never below 11px)`, scale >= 1, `scale ${scale.toFixed(3)}, svg ${m.svgW.toFixed(0)}px`);
    check(`${P}${w}px: diagram scrolls in its own container exactly when narrower than 1360`,
      needsScroll ? m.overflow && m.moved > 0 : !m.overflow, `container ${m.cw}px, scrolled ${m.moved}px`);
    await sweep.screenshot({ path: path.join(OUT, `2026-10-14-${spec.shot}-${w}.png`), fullPage: true });
    if (needsScroll) {
      await sweep.evaluate(() => { document.querySelector('.diagram-container').scrollLeft = 99999; });
      await sweep.screenshot({ path: path.join(OUT, `2026-10-14-${spec.shot}-${w}-scrolled.png`), fullPage: true });
    }
    await sweepCtx.close();
  }
  await ctx.close();
}

(async () => {
  const server = await serve();
  try {
    for (const spec of PAGES) {
      let url = `${server.base}${spec.file}`;
      if (spec.prefix === '' && process.env.TARGET_FILE) url = `${server.base}${process.env.TARGET_FILE}`;
      if (spec.prefix === '' && process.env.TARGET_URL) url = process.env.TARGET_URL;
      const browser = await chromium.launch({ channel: 'chrome', headless: true });
      try { await verifyPage(browser, url, spec); } finally { await browser.close(); }
    }
  } finally {
    server.proc.kill();
  }
  const bad = results.filter((r) => !r.ok).length;
  console.log(`${results.length} checks, ${bad} failed`);
  process.exitCode = bad === 0 ? 0 : 1;
})().catch((e) => { console.error(e); process.exitCode = 2; });
