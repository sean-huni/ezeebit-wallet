#!/usr/bin/env python3
"""Offline proof of version_report.py: every source is a fake, so the build needs no network."""
import contextlib
import http.client
import io
import json
import sys
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import version_report as vr  # noqa: E402

BOOT = 'org.springframework.boot:spring-boot'
LIB = 'com.acme:lib'


def metadata(*versions):
    rows = ''.join(f'<version>{version}</version>' for version in versions)
    return f'<metadata><versioning><versions>{rows}</versions></versioning></metadata>'.encode()


def vulnerability(identifier, module, fixed=None, severity='HIGH', cve=None):
    events = [{'introduced': '0'}] + ([{'fixed': fixed}] if fixed else [{'last_affected': '9.9.9'}])
    return {
        'id': identifier,
        'aliases': [cve] if cve else [],
        'database_specific': {'severity': severity},
        'affected': [{'package': {'name': module}, 'ranges': [{'type': 'ECOSYSTEM', 'events': events}]}],
    }


class FakeHttp:
    def __init__(self, versions, vulnerabilities=None, failing=None):
        self.versions = versions
        self.vulnerabilities = vulnerabilities or {}
        self.failing = failing

    def _raise_if_failing(self, url):
        if self.failing and self.failing in url:
            raise vr.SourceError(f'{url}: refused')

    def get(self, url):
        self._raise_if_failing(url)
        if '/vulns/' in url:
            wanted = url.rsplit('/', 1)[1]
            entries = [entry for found in self.vulnerabilities.values() for entry in found]
            return json.dumps(next(entry for entry in entries if entry['id'] == wanted)).encode()
        for path, versions in self.versions.items():
            if path in url:
                return metadata(*versions)
        raise AssertionError(f'unexpected url {url}')

    def post(self, url, payload):
        self._raise_if_failing(url)
        results = []
        for query in payload['queries']:
            found = self.vulnerabilities.get(query['package']['name'], [])
            results.append({'vulns': [{'id': entry['id']} for entry in found]} if found else {})
        return json.dumps({'results': results}).encode()


class VersionRules(unittest.TestCase):
    def test_pre_releases_are_not_offered_to_a_release_pin(self):
        self.assertEqual('4.1.2', vr.latest_release(['4.1.2', '4.2.0-RC1', '4.2.0-M1'], '4.1.0'))

    def test_an_alpha_pin_follows_its_own_line(self):
        self.assertEqual('2.29.0-alpha', vr.latest_release(['2.29.0-alpha', '2.29.0'], '2.28.1-alpha'))

    def test_a_jre_pin_is_never_offered_android(self):
        self.assertEqual('33.8.0-jre', vr.latest_release(['33.8.0-jre', '33.9.0-android'], '33.7.2-jre'))

    def test_numeric_order_is_not_string_order(self):
        self.assertEqual('1.10.0', vr.latest_release(['1.9.0', '1.10.0'], '1.2.0'))

    def test_a_newest_pin_stays_current(self):
        self.assertEqual('5.0.0', vr.latest_release(['4.0.0'], '5.0.0'))

    def test_tiers(self):
        self.assertEqual('major', vr.tier('1.2.3', '2.0.0'))
        self.assertEqual('minor', vr.tier('1.2.3', '1.3.0'))
        self.assertEqual('patch', vr.tier('1.2.3', '1.2.4'))


class FixSelection(unittest.TestCase):
    def test_the_smallest_fix_above_the_version_wins(self):
        entry = vulnerability('A', LIB, fixed='1.5.0')
        entry['affected'][0]['ranges'].append({'events': [{'introduced': '2.0.0'}, {'fixed': '2.3.0'}]})
        self.assertEqual('1.5.0', vr.fix_for(entry, LIB, '1.2.0'))

    def test_a_release_fixes_its_own_pre_release(self):
        self.assertEqual('2.28.1', vr.fix_for(vulnerability('A', LIB, fixed='2.28.1'), LIB, '2.28.1-alpha'))

    def test_a_fix_for_an_older_line_is_no_fix(self):
        entry = vulnerability('A', LIB, fixed='1.5.0')
        self.assertIsNone(vr.fix_for(entry, LIB, '3.0.0'))

    def test_a_fix_for_another_package_is_ignored(self):
        self.assertIsNone(vr.fix_for(vulnerability('A', 'org.other:thing', fixed='9.0.0'), LIB, '1.0.0'))


class Reports(unittest.TestCase):
    PIN = vr.Pin(BOOT, '4.1.0')
    VERSIONS = {'spring-boot': ['4.1.0', '4.1.2', '4.2.0-RC1']}

    def report(self, vulnerabilities, shipped=frozenset({(LIB, '1.0.0')})):
        return vr.build_report([self.PIN], set(shipped), FakeHttp(self.VERSIONS, vulnerabilities))

    def test_the_upgrade_row_names_both_versions_with_orange_then_green(self):
        output = vr.render(self.report({}), color=True)
        self.assertRegex(output, r'\033\[38;5;208mUPGRADE.*\033\[38;5;208m' + BOOT + r':4\.1\.0.*-> \033\[38;5;82m4\.1\.2')

    def test_a_cve_with_a_fix_is_red_then_green(self):
        found = {LIB: [vulnerability('GHSA-1', LIB, fixed='1.2.0', cve='CVE-2026-1')]}
        output = vr.render(self.report(found), color=True)
        self.assertRegex(output, r'\033\[38;5;196m' + LIB + r':1\.0\.0.*-> \033\[38;5;82m1\.2\.0')
        self.assertIn('CVE-2026-1', output)

    def test_a_cve_without_a_fix_says_no_upgrades_in_red(self):
        output = vr.render(self.report({LIB: [vulnerability('GHSA-1', LIB)]}), color=True)
        self.assertIn('-> \033[38;5;196mNo Upgrades', output)

    def test_plain_output_holds_no_escape(self):
        self.assertNotIn('\033', vr.render(self.report({LIB: [vulnerability('GHSA-1', LIB)]}), color=False))

    def test_every_advisory_of_a_module_must_clear_so_the_highest_fix_wins(self):
        found = {LIB: [vulnerability('A', LIB, fixed='1.2.0'), vulnerability('B', LIB, fixed='1.4.0', severity='LOW')]}
        advisory = self.report(found).advisories[0]
        self.assertEqual('1.4.0', advisory.fix)
        self.assertEqual('HIGH', advisory.severity)


class Verdicts(unittest.TestCase):
    VERSIONS = {'spring-boot': ['4.1.0']}

    def code(self, vulnerabilities, shipped=frozenset({(LIB, '1.0.0')})):
        http = FakeHttp(self.VERSIONS, vulnerabilities)
        return vr.exit_code(vr.build_report([vr.Pin(BOOT, '4.1.0')], set(shipped), http))

    def test_high_with_a_fix_on_a_shipped_module_blocks(self):
        self.assertEqual(vr.EXIT_BLOCKED, self.code({LIB: [vulnerability('A', LIB, fixed='1.2.0')]}))

    def test_critical_with_a_fix_blocks(self):
        self.assertEqual(vr.EXIT_BLOCKED, self.code({LIB: [vulnerability('A', LIB, fixed='1.2.0', severity='CRITICAL')]}))

    def test_moderate_does_not_block(self):
        self.assertEqual(0, self.code({LIB: [vulnerability('A', LIB, fixed='1.2.0', severity='MODERATE')]}))

    def test_high_without_a_fix_does_not_block(self):
        self.assertEqual(0, self.code({LIB: [vulnerability('A', LIB)]}))

    def test_a_critical_without_a_fix_beside_a_low_with_one_does_not_block(self):
        found = {LIB: [vulnerability('A', LIB, severity='CRITICAL'), vulnerability('B', LIB, fixed='1.2.0', severity='LOW')]}
        self.assertEqual(vr.EXIT_CLEAN, self.code(found))

    def test_an_advisory_with_no_severity_and_a_fix_blocks(self):
        self.assertEqual(vr.EXIT_BLOCKED, self.code({LIB: [vulnerability('A', LIB, fixed='1.2.0', severity='')]}))

    def test_a_second_page_of_advisories_is_read(self):
        class Paged(FakeHttp):
            def post(self, url, payload):
                if url.endswith('/querybatch'):
                    results = [{'next_page_token': 'more'} if query['package']['name'] == LIB else {} for query in payload['queries']]
                    return json.dumps({'results': results}).encode()
                return json.dumps({'vulns': [{'id': 'A'}]}).encode()
        http = Paged(self.VERSIONS, {LIB: [vulnerability('A', LIB, fixed='1.2.0')]})
        report = vr.build_report([vr.Pin(BOOT, '4.1.0')], {(LIB, '1.0.0')}, http)
        self.assertEqual(vr.EXIT_BLOCKED, vr.exit_code(report))

    def test_a_build_only_pin_does_not_block(self):
        found = {BOOT: [vulnerability('A', BOOT, fixed='4.1.2')]}
        self.assertEqual(0, self.code(found, shipped=frozenset({(LIB, '1.0.0')})))


class Transport(unittest.TestCase):
    def test_a_dropped_connection_is_a_source_error(self):
        def dropped(*_args, **_kwargs):
            raise http.client.RemoteDisconnected('closed')
        with mock.patch('urllib.request.urlopen', dropped), self.assertRaises(vr.SourceError):
            vr.Http().get('https://registry.invalid/x')


class FailsClosed(unittest.TestCase):
    def run_main(self, http, tmp_modules):
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return vr.main(['--pin', f'{BOOT}=4.1.0', '--modules', str(tmp_modules)], http)

    def setUp(self):
        self.modules = Path(__file__).resolve().parent / 'fixtures-modules.txt'
        self.modules.write_text(f'{LIB}:1.0.0\n', encoding='utf-8')
        self.addCleanup(self.modules.unlink)

    def test_an_unreachable_registry_exits_2(self):
        self.assertEqual(2, self.run_main(FakeHttp({'spring-boot': ['4.1.0']}, failing='maven-metadata'), self.modules))

    def test_an_unreachable_advisory_source_exits_2(self):
        self.assertEqual(2, self.run_main(FakeHttp({'spring-boot': ['4.1.0']}, failing='querybatch'), self.modules))

    def test_an_unreadable_advisory_record_exits_2(self):
        http = FakeHttp({'spring-boot': ['4.1.0']}, {LIB: [vulnerability('A', LIB)]}, failing='/vulns/')
        self.assertEqual(2, self.run_main(http, self.modules))

    def test_a_registry_listing_no_versions_exits_2(self):
        self.assertEqual(2, self.run_main(FakeHttp({'spring-boot': []}), self.modules))

    def test_an_empty_module_list_exits_2(self):
        self.modules.write_text('', encoding='utf-8')
        self.assertEqual(2, self.run_main(FakeHttp({'spring-boot': ['4.1.0']}), self.modules))

    def test_a_truncated_batch_answer_exits_2(self):
        class Truncated(FakeHttp):
            def post(self, url, payload):
                return json.dumps({'results': []}).encode()
        self.assertEqual(2, self.run_main(Truncated({'spring-boot': ['4.1.0']}), self.modules))

    def test_pins_from_files_merge_with_arguments_and_repeat_once(self):
        self.modules.write_text(f'{LIB}=1.0.0\n{LIB}=1.0.0\n', encoding='utf-8')
        pins = vr.read_pins([f'{BOOT}=4.1.0', f'{LIB}=1.0.0'], [str(self.modules)])
        self.assertEqual([vr.Pin(BOOT, '4.1.0'), vr.Pin(LIB, '1.0.0')], pins)

    def test_a_clean_run_exits_0(self):
        self.assertEqual(0, self.run_main(FakeHttp({'spring-boot': ['4.1.0']}), self.modules))


if __name__ == '__main__':
    unittest.main()
