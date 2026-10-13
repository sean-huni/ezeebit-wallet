// Captures the static application security testing (SAST) and software composition analysis (SCA)
// evidence images from the reports that `./gradlew clean build` and `./gradlew checkSupplyChain` write.
// Run from docs/observability/verification (it resolves the installed playwright package from there),
// with the repository root served over HTTP: `python3 -m http.server 8765 --bind 127.0.0.1` in the root.
//   node <repo>/docs/evidence/security/2026-10-13-capture.mjs <repo root> <base URL> [service]
import { createRequire } from 'node:module';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const { chromium } = createRequire(path.join(process.cwd(), 'package.json'))('playwright');
const [root, base, svc = 'ledger-svc'] = process.argv.slice(2);
if (!root || !base) throw new Error('usage: node capture.mjs <repo root> <base URL> [service]');
const SERVICES = ['config-svc', 'conversion-svc', 'eureka-svc', 'gateway-svc', 'ledger-svc'];
const SEV = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'];
const out = path.join(root, 'docs/evidence/security');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');
const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const commit = execFileSync('git', ['-C', root, 'rev-parse', '--short', 'HEAD']).toString().trim();
const dirty = execFileSync('git', ['-C', root, 'status', '--porcelain']).toString().trim() ? ' (working tree has uncommitted changes)' : '';
const stamp = new Date().toISOString();
const page0 = (title, body) => `<!doctype html><meta charset="utf-8"><title>${esc(title)}</title>
<style>body{margin:24px;background:#fff;color:#111;font:14px/1.45 -apple-system,Helvetica,Arial,sans-serif}
h1{font-size:18px;margin:0 0 4px}p.meta{color:#444;margin:0 0 16px}pre{font:13px/1.4 Menlo,Consolas,monospace;margin:0;white-space:pre}
table{border-collapse:collapse}th,td{border:1px solid #bbb;padding:4px 10px;text-align:right}th:first-child,td:first-child{text-align:left}
th{background:#eee}p.note{max-width:1100px;color:#333}</style>${body}`;

function testCounts(service) {
	const tiers = {};
	const dir = path.join(root, 'services', service, 'build/test-results');
	for (const tier of fs.existsSync(dir) ? fs.readdirSync(dir) : []) {
		for (const f of fs.readdirSync(path.join(dir, tier)).filter((n) => n.endsWith('.xml'))) {
			const head = fs.readFileSync(path.join(dir, tier, f), 'utf8').match(/<testsuite [^>]*>/)[0];
			const num = (k) => Number(head.match(new RegExp(` ${k}="(\\d+)"`))[1]);
			const t = (tiers[tier] ??= { tests: 0, failed: 0 });
			t.tests += num('tests');
			t.failed += num('failures') + num('errors');
		}
	}
	return tiers;
}

function sastRow(service) {
	const sb = read(`services/${service}/build/reports/spotbugs/main.xml`);
	const cs = read(`services/${service}/build/reports/checkstyle/main.xml`);
	const tiers = testCounts(service);
	return {
		service,
		tiers: Object.entries(tiers).map(([k, v]) => `${k} ${v.tests}`).join(', '),
		tests: Object.values(tiers).reduce((a, v) => a + v.tests, 0),
		failed: Object.values(tiers).reduce((a, v) => a + v.failed, 0),
		classes: (sb.match(/<Jar>/g) || []).length,
		fsb: /<Plugin id="com\.h3xstream\.findsecbugs"[^>]*enabled="true"/.test(sb) ? 'enabled' : 'NOT enabled',
		errors: sb.match(/<Errors errors="(\d+)" missingClasses="(\d+)"/).slice(1).join(' / '),
		bugs: (sb.match(/<BugInstance /g) || []).length,
		csFiles: (cs.match(/<file /g) || []).length,
		csViolations: (cs.match(/<error /g) || []).length,
	};
}

function vulnRow(service) {
	const report = JSON.parse(read(`build/supply-chain/images/${service}.json`));
	const distinct = new Map();
	let raw = 0;
	for (const r of report.Results ?? []) {
		for (const v of r.Vulnerabilities ?? []) {
			raw++;
			distinct.set(`${v.VulnerabilityID}|${v.PkgName}|${v.InstalledVersion}`, { sev: v.Severity ?? 'UNKNOWN', fix: !!v.FixedVersion });
		}
	}
	const vals = [...distinct.values()];
	return {
		service, raw, distinct: distinct.size,
		bySev: SEV.map((s) => [vals.filter((v) => v.sev === s).length, vals.filter((v) => v.sev === s && v.fix).length]),
		fixable: vals.filter((v) => v.fix).length,
	};
}

const browser = await chromium.launch({ channel: 'chrome' });
const page = await browser.newPage({ viewport: { width: 1440, height: 900 } });
const shoot = async (name, opts = {}) => {
	await page.screenshot({ path: path.join(out, name), ...opts });
	console.log('wrote', name);
};
const from = async (sel) => {
	const a = await page.locator(sel).first().boundingBox();
	const end = await page.evaluate(() => document.documentElement.scrollHeight);
	return { x: 0, y: Math.max(0, a.y - 12), width: 1440, height: end - a.y + 12 };
};

// SAST: SpotBugs with Find Security Bugs, one service.
await page.goto(`${base}/services/${svc}/build/reports/spotbugs/main.html`);
await shoot(`sast-spotbugs-${svc}.png`);
await shoot(`sast-spotbugs-${svc}-summary.png`, { fullPage: true, clip: await from('h2:has-text("Metrics")') });

// SAST: Checkstyle main source set, one service.
await page.goto(`${base}/services/${svc}/build/reports/checkstyle/main.html`);
await shoot(`sast-checkstyle-${svc}.png`, { fullPage: true });

// Architecture gate (a design rule run inside `test`): the test index and the two architecture classes.
await page.goto(`${base}/services/${svc}/build/reports/tests/test/index.html`);
await shoot(`sast-architecture-tests-${svc}.png`, { fullPage: true });
for (const cls of ['DependencyBudgetTest', 'InjectionCounterTest']) {
	await page.goto(`${base}/services/${svc}/build/reports/tests/test/io.ezeebit.architecture.${cls}/index.html`);
	await shoot(`sast-architecture-${cls}-${svc}.png`, { fullPage: true });
}

// Coverage (not SAST): JaCoCo index.
await page.goto(`${base}/services/${svc}/build/reports/jacoco/test/html/index.html`);
await shoot(`coverage-jacoco-${svc}.png`, { fullPage: true });

// SAST summary over all five services, read from the same XML the gate reads.
const rows = SERVICES.map(sastRow);
await page.setContent(page0('SAST summary', `<h1>SAST and test summary, all five services</h1>
<p class="meta">commit ${commit}${esc(dirty)}, captured ${stamp}, from services/&lt;svc&gt;/build/{test-results,reports/spotbugs/main.xml,reports/checkstyle/main.xml}</p>
<table><tr><th>service</th><th>tests per tier</th><th>tests</th><th>failed</th><th>SpotBugs class files</th><th>Find Security Bugs</th><th>analysis errors / missing classes</th><th>SpotBugs findings</th><th>Checkstyle main files</th><th>Checkstyle main violations</th></tr>
${rows.map((r) => `<tr><td>${r.service}</td><td>${r.tiers}</td><td>${r.tests}</td><td>${r.failed}</td><td>${r.classes}</td><td>${r.fsb}</td><td>${r.errors}</td><td>${r.bugs}</td><td>${r.csFiles}</td><td>${r.csViolations}</td></tr>`).join('')}</table>
<p class="note">Class files are the &lt;Jar&gt; entries handed to SpotBugs (its FindBugsSummary total_classes reads 0 on 4.10.5). Findings are &lt;BugInstance&gt; elements; violations are &lt;error&gt; elements. Failed is failures plus errors.</p>`));
await shoot('sast-all-services-summary.png', { fullPage: true });

// SCA: the supply-chain console, verbatim.
const consoleText = read('build/reports/supply-chain/console.txt');
const header = (t) => `<h1>${t}</h1><p class="meta">build/reports/supply-chain/console.txt, commit ${commit}${esc(dirty)}, captured ${stamp}</p>`;
await page.setContent(page0('Supply-chain console', `${header('Supply-chain console, full')}<pre>${esc(consoleText)}</pre>`));
await shoot('sca-supply-chain-console.png', { fullPage: true });
const lines = consoleText.split('\n');
const shared = lines.findIndex((l) => l.includes('SHARED SCOPE'));
const verdict = lines.findIndex((l) => l.startsWith('SUPPLY CHAIN GATE'));
if (shared < 0 || verdict < 0) throw new Error('console lacks the SHARED SCOPE list or the verdict line');
const omitted = `[... console lines ${shared + 1} to ${verdict - 1} omitted here (SHARED scope list, SBOM, misconfiguration, suppressions); see sca-supply-chain-console.png ...]`;
const crop = [...lines.slice(0, shared), omitted, ...lines.slice(verdict - 1, verdict + 1)].join('\n');
await page.setContent(page0('Supply-chain verdict', `${header('Supply-chain console, verdict and per-image summary (elided)')}<pre>${esc(crop)}</pre>`));
await shoot('sca-supply-chain-verdict.png', { fullPage: true });

// SCA: per-image vulnerability counts from the trivy JSON reports.
const v = SERVICES.map(vulnRow);
await page.setContent(page0('Image vulnerabilities', `<h1>Image vulnerabilities per service (trivy JSON)</h1>
<p class="meta">build/supply-chain/images/&lt;svc&gt;.json, commit ${commit}${esc(dirty)}, captured ${stamp}</p>
<table><tr><th>image</th>${SEV.map((s) => `<th>${s}<br>(with fix)</th>`).join('')}<th>distinct</th><th>with a fix</th><th>raw entries</th></tr>
${v.map((r) => `<tr><td>ezeebit/${r.service}:0.1.0</td>${r.bySev.map(([n, f]) => `<td>${n} (${f})</td>`).join('')}<td>${r.distinct}</td><td>${r.fixable}</td><td>${r.raw}</td></tr>`).join('')}</table>
<p class="note">Distinct = unique (VulnerabilityID, PkgName, InstalledVersion) over every Result of the report, the same key as the console's "distinct" column; "with a fix" = FixedVersion non-empty. Raw entries count every occurrence, so a package found at several paths counts more than once. These totals cover SERVICE and SHARED layers together; the gate fails only on HIGH or CRITICAL with a fix in a SERVICE layer (see the console).</p>`));
await shoot('sca-image-vulnerabilities.png', { fullPage: true });

await browser.close();
