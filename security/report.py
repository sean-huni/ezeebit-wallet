#!/usr/bin/env python3
"""Reads the files security/scan.sh wrote, prints the supply-chain console and decides the verdict.

It reads FILES, never a scanner's say-so: a stage whose exit code or report is missing or empty FAILS,
because a scanner that never ran must not read as a scanner that found nothing.
"""
import argparse
import json
import os
import re
import subprocess
import sys

SEC = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(SEC)
SEVERITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN"]
GATING = {"CRITICAL", "HIGH"}
RULE = "=" * 78


def stages(services):
    return {
        "secrets": (["gitleaks-history", "gitleaks-tree", "trivy-secrets"],
                    ["gitleaks-history.json", "gitleaks-tree.json", "trivy-secrets.json", "tree-files.txt"]),
        "sbom": (["sbom-" + s for s in services], ["sbom/%s.cdx.json" % s for s in services]),
        "images": (["image-" + s for s in services],
                   ["images/shared-layers.txt"] + ["images/%s.json" % s for s in services]),
        "config": (["misconfig"], ["misconfig.json", "config-files.txt"]),
    }


# gitleaks exits 1 when it finds a leak; that is a finding, read from its report, not a failed scan.
def allowed_exits(name):
    return {"0", "1"} if name.startswith("gitleaks-") else {"0"}


def load(out, rel):
    path = os.path.join(out, rel)
    if not os.path.isfile(path) or os.path.getsize(path) == 0:
        return None
    with open(path) as handle:
        return json.load(handle) if rel.endswith(".json") else handle.read()


def statuses(out):
    seen = {}
    path = os.path.join(out, "status.txt")
    if os.path.isfile(path):
        for line in open(path):
            if " exit=" in line:
                name, code = line.strip().rsplit(" exit=", 1)
                seen[name] = code
    return seen


def lock(key):
    for line in open(os.path.join(SEC, "scanners.lock")):
        if line.startswith(key + "="):
            return line.split("=", 1)[1].strip().split("@")[0].rsplit(":", 1)[1]
    return "?"


def git_count(*extra):
    try:
        return int(subprocess.run(["git", "-C", ROOT, "rev-list", "--all", "--count"] + list(extra),
                                  capture_output=True, text=True, check=True).stdout.strip())
    except (subprocess.CalledProcessError, ValueError, OSError):
        return None


def ignore_problems():
    """Every vulnerability suppression must carry an expiry and a reason, or it is a hidden finding."""
    entries = []
    for raw in open(os.path.join(SEC, "trivy-ignore.yaml")):
        line = raw.split("#", 1)[0].rstrip()
        if re.match(r"^\s*-\s*id:", line):
            entries.append({"id": line.split("id:", 1)[1].strip()})
        elif entries and ":" in line:
            entries[-1][line.strip().split(":", 1)[0]] = True
    return len(entries), [e["id"] for e in entries if not (e.get("expired_at") and e.get("statement"))]


def check_evidence(out, recorded, required, spec, failures):
    print("\nSTAGES REQUIRED BY THIS RUN  [%s]" % ",".join(required))
    if not os.path.isfile(os.path.join(out, "status.txt")):
        failures.append("no status.txt: no scanner recorded an exit code, so nothing can read as clean")
    for stage in required:
        scanners, files = spec[stage]
        absent = [n for n in scanners if n not in recorded]
        broken = [n for n in scanners if n in recorded and recorded[n] not in allowed_exits(n)]
        missing = [f for f in files if not os.path.isfile(os.path.join(out, f))
                   or os.path.getsize(os.path.join(out, f)) == 0]
        ok = not (absent or broken or missing)
        print("  %-8s %d/%d scanner(s) completed   %d/%d report file(s) present   %s"
              % (stage, len(scanners) - len(absent) - len(broken), len(scanners),
                 len(files) - len(missing), len(files), "OK" if ok else "MISSING"))
        if absent:
            failures.append("stage %s: no exit code recorded for %s" % (stage, ", ".join(absent)))
        if broken:
            failures.append("stage %s: scanner did not complete: %s" % (
                stage, ", ".join("%s exit=%s" % (n, recorded[n]) for n in broken)))
        if missing:
            failures.append("stage %s: report absent or empty: %s" % (stage, ", ".join(missing)))


def secrets(out, failures):
    history = load(out, "gitleaks-history.json") or []
    tree = load(out, "gitleaks-tree.json") or []
    trivy = [(r.get("Target"), s.get("RuleID"), s.get("StartLine"))
             for r in (load(out, "trivy-secrets.json") or {}).get("Results") or []
             for s in r.get("Secrets") or []]
    files = (load(out, "tree-files.txt") or "?").strip()
    found = re.search(r"(\d+) commits scanned", load(out, "gitleaks-history.log") or "")
    scanned = int(found.group(1)) if found else None
    expected, total = git_count("--no-merges"), git_count()
    print("\nSECRETS  [any finding fails; values are redacted and never printed]")
    print("  gitleaks history : %d finding(s)  [%s of %s non-merge commits, all refs; %s with merges]"
          % (len(history), scanned, expected, total))
    print("  gitleaks tree    : %d finding(s)  [%s files: tracked plus untracked, .gitignore honoured]"
          % (len(tree), files))
    print("  trivy secret     : %d finding(s)  [the same %s files, second instrument]" % (len(trivy), files))
    for f in history:
        print("    HISTORY %s %s:%s commit %s" % (f.get("RuleID"), f.get("File"), f.get("StartLine"),
                                                   (f.get("Commit") or "")[:8]))
    for f in tree:
        print("    TREE    %s %s:%s" % (f.get("RuleID"), (f.get("File") or "").replace("/out/stage-tree/", ""), f.get("StartLine")))
    for target, rule, line in trivy:
        print("    TRIVY   %s %s:%s" % (rule, target, line))
    if scanned is None or expected is None or scanned != expected:
        failures.append("gitleaks history covered %s commits where git counts %s non-merge commits"
                        % (scanned, expected))
    if history or tree or trivy:
        failures.append("%d secret finding(s)" % (len(history) + len(tree) + len(trivy)))


def image_rows(report, shared):
    rows = set()
    for result in report.get("Results") or []:
        for v in result.get("Vulnerabilities") or []:
            layer = (v.get("Layer") or {}).get("DiffID", "")
            rows.add(("SHARED" if layer in shared else "SERVICE", v.get("Severity", "UNKNOWN"),
                      v.get("VulnerabilityID", ""), v.get("PkgName", ""), v.get("InstalledVersion", ""),
                      v.get("FixedVersion") or ""))
    return rows


def images(out, services, failures):
    shared = set((load(out, "images/shared-layers.txt") or "").split())
    per = {}
    for s in services:
        report = load(out, "images/%s.json" % s)
        if report is None:
            return
        per[s] = image_rows(report, shared)
    db = ((load(out, "trivy-version.json") or {}).get("VulnerabilityDB") or {}).get("UpdatedAt", "unknown")
    print("\nIMAGES  [%d images, %d layers carried by all %d; trivy DB updated %s]"
          % (len(services), len(shared), len(services), db))
    print("  %-16s %7s %7s %9s   %s" % ("service", "SERVICE", "SHARED", "distinct", "gating"))
    for s in services:
        rows = per[s]
        print("  %-16s %7d %7d %9d   %d" % (
            s, sum(r[0] == "SERVICE" for r in rows), sum(r[0] == "SHARED" for r in rows), len(rows),
            sum(r[0] == "SERVICE" and r[1] in GATING and bool(r[5]) for r in rows)))
    every = set().union(*per.values())
    for scope, title in (("SERVICE", "layers this repository builds, ours to fix"),
                         ("SHARED", "builder and run-image layers, fixed by moving the builder pin")):
        rows = sorted((r for r in every if r[0] == scope), key=lambda r: (SEVERITIES.index(r[1]), r[2]))
        print("\n  %s SCOPE, %s (%d distinct)" % (scope, title, len(rows)))
        for r in rows:
            carriers = sum(r in per[s] for s in services)
            print("    %-8s %-20s %-40s fix=%-14s [%d/%d images]"
                  % (r[1], r[2], r[3] + " " + r[4], r[5] or "NONE", carriers, len(services)))
    gating = sorted({r[2] for r in every if r[0] == "SERVICE" and r[1] in GATING and r[5]})
    if gating:
        failures.append("%d finding(s) HIGH or CRITICAL AND with a published fix AND in a SERVICE layer: %s"
                        % (len(gating), ", ".join(gating)))


def sbom(out, services):
    print("\nSBOM  [CycloneDX written by trivy from each image, build/supply-chain/sbom/]")
    for s in services:
        doc = load(out, "sbom/%s.cdx.json" % s) or {}
        print("  %-16s %4d components  CycloneDX %s" % (s, len(doc.get("components") or []),
                                                       doc.get("specVersion", "?")))


def config(out):
    report = load(out, "misconfig.json") or {}
    staged = (load(out, "config-files.txt") or "?").strip()
    detected = re.search(r"Detected config files\s+num=(\d+)", load(out, "misconfig.log") or "")
    counts = {}
    for r in report.get("Results") or []:
        for m in r.get("Misconfigurations") or []:
            if m.get("Status") == "FAIL":
                counts[m.get("Severity", "UNKNOWN")] = counts.get(m.get("Severity", "UNKNOWN"), 0) + 1
    print("\nMISCONFIGURATION  [reported, NOT gating; its own universe, never add it to image totals]")
    print("  %s file(s) staged (compose.yaml, docker/, Dockerfile*); trivy recognised %s as a config format"
          % (staged, detected.group(1) if detected else "?"))
    print("  failing checks: " + "  ".join("%s=%d" % (s, counts.get(s, 0)) for s in SEVERITIES))


def main(args):
    services = args.services.split()
    spec = stages(services)
    required = [s for s in args.require.split(",") if s]
    unknown = [s for s in required if s not in spec]
    if not services or not required or unknown:
        raise SystemExit("report.py: need services and known stages (%s); got %s" % (", ".join(spec), unknown))

    failures = []
    print(RULE)
    print("EZEEBIT SUPPLY CHAIN   trivy %s, gitleaks %s, pinned in security/scanners.lock"
          % (lock("TRIVY_IMAGE"), lock("GITLEAKS_IMAGE")))
    print(RULE)
    check_evidence(args.out, statuses(args.out), required, spec, failures)
    if "secrets" in required:
        secrets(args.out, failures)
    if "images" in required:
        images(args.out, services, failures)
    if "sbom" in required:
        sbom(args.out, services)
    if "config" in required:
        config(args.out)
    entries, bad = ignore_problems()
    print("\nSUPPRESSIONS  security/trivy-ignore.yaml holds %d vulnerability entr%s"
          % (entries, "y" if entries == 1 else "ies"))
    if bad:
        failures.append("suppression(s) without expired_at and statement: " + ", ".join(bad))

    print("\n" + RULE)
    for failure in failures:
        print("FAIL  " + failure)
    if failures:
        print("SUPPLY CHAIN GATE: FAILED")
    elif "images" in required:
        print("SUPPLY CHAIN GATE: PASSED  (no finding is HIGH or CRITICAL AND fixable AND in a SERVICE layer)")
    else:
        print("SUPPLY CHAIN GATE: PASSED  (stages %s only; no image verdict is claimed)" % ",".join(required))
    return 1 if failures else 0


class Tee:
    def __init__(self, *streams):
        self.streams = streams

    def write(self, text):
        for stream in self.streams:
            stream.write(text)

    def flush(self):
        for stream in self.streams:
            stream.flush()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    for flag in ("--out", "--services", "--require", "--console"):
        parser.add_argument(flag, required=True)
    parsed = parser.parse_args()
    os.makedirs(os.path.dirname(os.path.abspath(parsed.console)), exist_ok=True)
    with open(parsed.console, "w") as saved:
        sys.stdout = Tee(sys.__stdout__, saved)
        try:
            code = main(parsed)
        finally:
            sys.stdout = sys.__stdout__
    sys.exit(code)
