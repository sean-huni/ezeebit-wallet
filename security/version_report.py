#!/usr/bin/env python3
"""Reports pinned versions with newer releases and resolved modules with published advisories.

Exit 0: nothing blocks. 2: a source could not be read, so nothing is known about the versions.
3: a shipped module has a HIGH or CRITICAL advisory with a fix. Exit 1 is Python itself crashing.
"""
import argparse
import http.client
import json
import re
import sys
import urllib.request
import xml.etree.ElementTree as ElementTree
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

MAVEN_CENTRAL = 'https://repo1.maven.org/maven2'
PLUGIN_PORTAL = 'https://plugins.gradle.org/m2'
OSV = 'https://api.osv.dev/v1'
TIMEOUT_SECONDS = 30
WORKERS = 8
OSV_BATCH = 500
PLUGIN_PREFIX = 'plugin:'
NEUTRAL_QUALIFIERS = {'final', 'ga', 'release'}
PRE_RELEASE = re.compile(r'(alpha|beta|rc|cr|m\d|milestone|snapshot|preview|ea|pre)([\W_\d]|$)')
SEVERITY_ORDER = ['CRITICAL', 'HIGH', 'MODERATE', 'LOW', 'UNKNOWN']
BLOCKING = {'CRITICAL', 'HIGH', 'UNKNOWN'}
EXIT_CLEAN, EXIT_UNKNOWN, EXIT_BLOCKED = 0, 2, 3

ORANGE, GREEN, RED, DIM, BOLD = '38;5;208', '38;5;82', '38;5;196', '38;5;244', '1'
SEVERITY_STYLE = {
    'CRITICAL': '1;38;5;231;48;5;196',
    'HIGH': '1;38;5;196',
    'MODERATE': '38;5;214',
    'LOW': '38;5;244',
    'UNKNOWN': '38;5;244',
}


class SourceError(Exception):
    """A source did not answer, so absence of a finding would mean nothing."""


@dataclass(frozen=True)
class Pin:
    coordinate: str
    version: str

    @property
    def is_plugin(self):
        return self.coordinate.startswith(PLUGIN_PREFIX)

    @property
    def label(self):
        return self.coordinate.removeprefix(PLUGIN_PREFIX)


@dataclass(frozen=True)
class PinStatus:
    pin: Pin
    latest: str

    @property
    def outdated(self):
        return order(self.latest) > order(self.pin.version)


@dataclass(frozen=True)
class Advisory:
    module: str
    version: str
    ids: tuple
    severity: str
    fix: Optional[str]
    unfixed: int
    shipped: bool
    fixable_blocker: bool

    @property
    def blocking(self):
        return self.shipped and self.fixable_blocker


@dataclass(frozen=True)
class Report:
    pins: list
    advisories: list
    module_count: int


class Http:
    def get(self, url):
        return self._send(urllib.request.Request(url))

    def post(self, url, payload):
        body = json.dumps(payload).encode()
        return self._send(urllib.request.Request(url, body, {'Content-Type': 'application/json'}))

    @staticmethod
    def _send(request):
        try:
            with urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS) as response:
                return response.read()
        except (OSError, http.client.HTTPException) as failure:
            raise SourceError(f'{request.full_url}: {failure}') from failure


def numeric_part(version):
    match = re.match(r'\d+(?:\.\d+)*', version)
    return match.group() if match else ''


def order(version):
    numbers = [int(part) for part in numeric_part(version).split('.') if part]
    return tuple(numbers + [0] * (8 - len(numbers))) + (0 if PRE_RELEASE.match(qualifier(version)) else 1,)


def qualifier(version):
    tail = version[len(numeric_part(version)):].lstrip('.-_').lower()
    return '' if tail in NEUTRAL_QUALIFIERS else tail


def latest_release(available, current):
    same_line = [version for version in available if qualifier(version) == qualifier(current)]
    return max([current, *same_line], key=order)


def tier(current, latest):
    for index, (old, new) in enumerate(zip(order(current), order(latest))):
        if old != new:
            return ('major', 'minor')[index] if index < 2 else 'patch'
    return 'patch'


def metadata_url(pin):
    if pin.is_plugin:
        plugin_id = pin.label
        return f"{PLUGIN_PORTAL}/{plugin_id.replace('.', '/')}/{plugin_id}.gradle.plugin/maven-metadata.xml"
    group, artifact = pin.coordinate.split(':')
    return f"{MAVEN_CENTRAL}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml"


def check_pin(pin, http):
    url = metadata_url(pin)
    try:
        document = ElementTree.fromstring(http.get(url))
    except ElementTree.ParseError as failure:
        raise SourceError(f'{url}: {failure}') from failure
    available = [node.text for node in document.findall('./versioning/versions/version')]
    if not available:
        raise SourceError(f'{url}: lists no versions')
    return PinStatus(pin, latest_release(available, pin.version))


def osv_query(module, version):
    return {'package': {'name': module, 'ecosystem': 'Maven'}, 'version': version}


def read_json(raw, source):
    try:
        return json.loads(raw)
    except ValueError as failure:
        raise SourceError(f'{source}: {failure}') from failure


def remaining_vulnerabilities(query, token, http):
    found = []
    while token:
        answer = read_json(http.post(f'{OSV}/query', {**query, 'page_token': token}), 'osv query')
        found += answer.get('vulns', [])
        token = answer.get('next_page_token')
    return found


def query_advisory_ids(modules, http):
    keys = sorted(modules)
    ids_by_module = {}
    for start in range(0, len(keys), OSV_BATCH):
        chunk = keys[start:start + OSV_BATCH]
        payload = {'queries': [osv_query(*key) for key in chunk]}
        answer = read_json(http.post(f'{OSV}/querybatch', payload), 'osv querybatch')
        results = answer.get('results')
        if results is None or len(results) != len(chunk):
            raise SourceError(f'osv querybatch: {len(chunk)} queries, answer holds {len(results or [])}')
        for key, result in zip(chunk, results):
            token = result.get('next_page_token')
            vulnerabilities = result.get('vulns', []) + remaining_vulnerabilities(osv_query(*key), token, http)
            if vulnerabilities:
                ids_by_module[key] = [entry['id'] for entry in vulnerabilities]
    return ids_by_module


def fix_for(vulnerability, module, version):
    fixes = []
    for affected in vulnerability.get('affected', []):
        if affected.get('package', {}).get('name') != module:
            continue
        for version_range in affected.get('ranges', []):
            fixes += [event['fixed'] for event in version_range.get('events', []) if 'fixed' in event]
    newer = [fix for fix in fixes if order(fix) > order(version)]
    return min(newer, key=order) if newer else None


def display_id(vulnerability):
    cves = [alias for alias in vulnerability.get('aliases', []) if alias.startswith('CVE-')]
    return cves[0] if cves else vulnerability['id']


def severity_of(vulnerability):
    raw = vulnerability.get('database_specific', {}).get('severity', 'UNKNOWN')
    return raw if raw in SEVERITY_ORDER else 'UNKNOWN'


def to_advisory(key, vulnerabilities, shipped):
    module, version = key
    fixes = [fix_for(entry, module, version) for entry in vulnerabilities]
    known = [fix for fix in fixes if fix]
    severities = [severity_of(entry) for entry in vulnerabilities]
    return Advisory(
        module=module,
        version=version,
        ids=tuple(sorted({display_id(entry) for entry in vulnerabilities})),
        severity=min(severities, key=SEVERITY_ORDER.index),
        fix=max(known, key=order) if known else None,
        unfixed=len(fixes) - len(known),
        shipped=shipped,
        fixable_blocker=any(severity in BLOCKING and fix for severity, fix in zip(severities, fixes)),
    )


def build_report(pins, shipped_modules, http):
    with ThreadPoolExecutor(WORKERS) as pool:
        statuses = list(pool.map(lambda pin: check_pin(pin, http), pins))
        pinned = {(pin.coordinate, pin.version) for pin in pins if not pin.is_plugin}
        ids_by_module = query_advisory_ids(shipped_modules | pinned, http)
        unique_ids = sorted({advisory_id for ids in ids_by_module.values() for advisory_id in ids})
        raw = pool.map(lambda advisory_id: http.get(f'{OSV}/vulns/{advisory_id}'), unique_ids)
        details = {advisory_id: read_json(body, advisory_id) for advisory_id, body in zip(unique_ids, raw)}
    advisories = [
        to_advisory(key, [details[advisory_id] for advisory_id in ids], key in shipped_modules)
        for key, ids in ids_by_module.items()
    ]
    advisories.sort(key=lambda entry: (SEVERITY_ORDER.index(entry.severity), entry.module))
    return Report(statuses, advisories, len(shipped_modules))


def exit_code(report):
    return EXIT_BLOCKED if any(entry.blocking for entry in report.advisories) else EXIT_CLEAN


def render(report, color):
    def paint(code, text):
        return f'\033[{code}m{text}\033[0m' if color else text

    outdated = sorted((status for status in report.pins if status.outdated), key=lambda s: s.pin.label)
    current = sorted((status for status in report.pins if not status.outdated), key=lambda s: s.pin.label)
    width = max([len(f'{s.pin.label}:{s.pin.version}') for s in report.pins]
                + [len(f'{a.module}:{a.version}') for a in report.advisories])
    rule = paint(DIM, '─' * (width + 44))
    summary = f'{len(report.pins)} pins, {report.module_count} shipped modules. Maven Central, Gradle Plugin Portal, OSV.'
    lines = ['', f"  {paint(BOLD, 'DEPENDENCY VERSIONS')}  {paint(DIM, summary)}", f'  {rule}']

    def row(tag, code, name, target):
        return f'  {paint(code, tag.ljust(8))}{paint(code, name.ljust(width))}  {target}'

    for status in outdated:
        name = f'{status.pin.label}:{status.pin.version}'
        step = tier(status.pin.version, status.latest)
        lines.append(row('UPGRADE', ORANGE, name, f'-> {paint(GREEN, status.latest)}  {paint(DIM, step)}'))
    for status in current:
        lines.append(row('CURRENT', DIM, f'{status.pin.label}:{status.pin.version}', paint(GREEN, 'up to date')))
    if report.advisories:
        lines.append(f'  {rule}')
    for entry in report.advisories:
        target = paint(GREEN, entry.fix) if entry.fix else paint(RED, 'No Upgrades')
        badge = paint(SEVERITY_STYLE[entry.severity], f' {entry.severity} ')
        partial = paint(RED, f'  {entry.unfixed} without a fix') if entry.fix and entry.unfixed else ''
        scope = '' if entry.shipped else paint(DIM, '  build only')
        ids = paint(DIM, ', '.join(entry.ids))
        lines.append(row('CVE', RED, f'{entry.module}:{entry.version}', f'-> {target}  {badge} {ids}{partial}{scope}'))
    blocking = sum(entry.blocking for entry in report.advisories)
    verdict = paint('1;' + RED, 'FAILED') if blocking else paint('1;' + GREEN, 'PASSED')
    lines += [f'  {rule}',
              f'  {verdict}  {len(outdated)} upgrades, {len(report.advisories)} modules with advisories, {blocking} blocking', '']
    return '\n'.join(lines)


def parse_pin(text):
    coordinate, version = text.split('=', 1)
    return Pin(coordinate, version)


def read_pins(arguments, paths):
    lines = list(arguments)
    for path in paths:
        lines += Path(path).read_text(encoding='utf-8').split()
    return list(dict.fromkeys(parse_pin(line) for line in lines))


def read_modules(paths):
    modules = set()
    for path in paths:
        lines = Path(path).read_text(encoding='utf-8').split()
        if not lines:
            raise SourceError(f'{path}: lists no modules')
        for line in lines:
            group, artifact, version = line.split(':')
            modules.add((f'{group}:{artifact}', version))
    return modules


def main(arguments=None, http=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pin', action='append', default=[], help='coordinate=version')
    parser.add_argument('--pins-file', action='append', default=[], help='file of coordinate=version lines')
    parser.add_argument('--modules', action='append', default=[], help='file of group:artifact:version lines')
    parser.add_argument('--report', help='plain-text copy of the console')
    parser.add_argument('--color', action='store_true')
    options = parser.parse_args(arguments)
    try:
        if not options.pin or not options.modules:
            raise SourceError('no pins or no module lists were given')
        report = build_report(read_pins(options.pin, options.pins_file), read_modules(options.modules), http or Http())
    except SourceError as failure:
        print(f'checkVersions: could not read a source, so nothing is known: {failure}', file=sys.stderr)
        return EXIT_UNKNOWN
    print(render(report, options.color))
    if options.report:
        Path(options.report).parent.mkdir(parents=True, exist_ok=True)
        Path(options.report).write_text(render(report, False) + '\n', encoding='utf-8')
    return exit_code(report)


if __name__ == '__main__':
    sys.exit(main())
