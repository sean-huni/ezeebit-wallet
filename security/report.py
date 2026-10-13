#!/usr/bin/env python3
"""Reads the files security/scan.sh wrote, prints the supply-chain console and decides the verdict.

It reads FILES, never a scanner's say-so: a stage whose exit code or report is missing, empty or
unparseable FAILS, because a scanner that never ran must not read as a scanner that found nothing.
"""
import argparse
import datetime
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
LIFECYCLE_LABEL = "io.buildpacks.lifecycle.metadata"


def stages(services):
    return {
        "secrets": (["gitleaks-history", "gitleaks-tree", "trivy-secrets"],
                    ["gitleaks-history.json", "gitleaks-tree.json", "trivy-secrets.json", "tree-files.txt"]),
        "sbom": (["sbom-" + s for s in services], ["sbom/%s.cdx.json" % s for s in services]),
        "images": (["image-" + s for s in services],
                   ["images/shared-layers.txt"] + ["images/%s.json" % s for s in services]
                   + ["images/%s.inspect.json" % s for s in services]),
        "config": (["misconfig"], ["misconfig.json", "config-files.txt"]),
    }


# gitleaks exits 1 when it finds a leak; that is a finding, read from its report, not a failed scan.
def allowed_exits(name):
    return {"0", "1"} if name.startswith("gitleaks-") else {"0"}


def problem(out, rel):
    """Returns why a report cannot serve as evidence, or None when it can."""
    path = os.path.join(out, rel)
    if not os.path.isfile(path):
        return "absent"
    if os.path.getsize(path) == 0:
        return "empty"
    if rel.endswith(".json"):
        try:
            with open(path) as handle:
                json.load(handle)
        except ValueError as error:
            return "unparseable JSON (%s)" % error
    return None


def load(out, rel):
    if problem(out, rel):
        return None
    with open(os.path.join(out, rel)) as handle:
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


def git(*args):
    try:
        return subprocess.run(["git", "-C", ROOT] + list(args), capture_output=True, text=True, check=True).stdout
    except (subprocess.CalledProcessError, OSError):
        return None


# The same commits gitleaks reads with --diff-merges=first-parent: those whose diff touches a file.
def commits_with_changes():
    listing = git("log", "--all", "--diff-merges=first-parent", "--format=@%H", "--name-only")
    if listing is None:
        return None
    count, changed = 0, False
    for line in listing.splitlines():
        if line.startswith("@"):
            count, changed = count + changed, False
        elif line.strip():
            changed = True
    return count + changed


ENTRY_KEYS = {"id", "statement", "expired_at"}


def parse_ignore(text):
    """A strict block-form subset of trivy's ignore YAML. Flow style and unknown keys are refused,
    because trivy honours forms this reader could otherwise miss and count as zero entries."""
    entries, errors, header = [], [], None
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.split(" #", 1)[0].rstrip() if not raw.lstrip().startswith("#") else ""
        if not line:
            continue
        if header is None:
            if line in ("vulnerabilities:", "vulnerabilities: []"):
                header = line
                continue
            errors.append("line %d: expected 'vulnerabilities:' or 'vulnerabilities: []'" % number)
            break
        match = re.match(r"^  (- |  )([a-z_]+): (.+)$", line)
        if header.endswith("[]") or not match or any(c in match.group(3) for c in "[]{}"):
            errors.append("line %d: not block-form '- id:' / 'statement:' / 'expired_at:'" % number)
            continue
        lead, key, value = match.groups()
        if lead == "- ":
            entries.append({})
        if not entries or key not in ENTRY_KEYS or key in entries[-1] or (lead == "- ") != (key == "id"):
            errors.append("line %d: unexpected key '%s'" % (number, key))
            continue
        entries[-1][key] = value.strip().strip('"').strip("'").strip()
    if header is None and not errors:
        errors.append("no 'vulnerabilities' key")
    return entries, errors


def ignore_problems():
    entries, errors = parse_ignore(open(os.path.join(SEC, "trivy-ignore.yaml")).read())
    today = datetime.date.today()
    for entry in entries:
        name = entry.get("id", "?")
        if not entry.get("statement"):
            errors.append("%s has no statement" % name)
        try:
            if datetime.date.fromisoformat(entry.get("expired_at", "")) < today:
                errors.append("%s expired on %s: renew it with a reason, or fix the finding" % (name, entry["expired_at"]))
        except ValueError:
            errors.append("%s has no ISO expired_at date" % name)
    return len(entries), errors


def check_evidence(out, recorded, required, spec, failures):
    print("\nSTAGES REQUIRED BY THIS RUN  [%s]" % ",".join(required))
    if not os.path.isfile(os.path.join(out, "status.txt")):
        failures.append("no status.txt: no scanner recorded an exit code, so nothing can read as clean")
    for stage in required:
        scanners, files = spec[stage]
        absent = [n for n in scanners if n not in recorded]
        broken = [n for n in scanners if n in recorded and recorded[n] not in allowed_exits(n)]
        unusable = [(f, problem(out, f)) for f in files if problem(out, f)]
        ok = not (absent or broken or unusable)
        print("  %-8s %d/%d scanner(s) completed   %d/%d report file(s) usable   %s"
              % (stage, len(scanners) - len(absent) - len(broken), len(scanners),
                 len(files) - len(unusable), len(files), "OK" if ok else "MISSING"))
        if absent:
            failures.append("stage %s: no exit code recorded for %s" % (stage, ", ".join(absent)))
        if broken:
            failures.append("stage %s: scanner did not complete: %s" % (
                stage, ", ".join("%s exit=%s" % (n, recorded[n]) for n in broken)))
        for rel, why in unusable:
            failures.append("stage %s: %s is %s" % (stage, rel, why))


def secrets(out, failures):
    history = load(out, "gitleaks-history.json") or []
    tree = load(out, "gitleaks-tree.json") or []
    trivy = [(r.get("Target"), s.get("RuleID"), s.get("StartLine"))
             for r in (load(out, "trivy-secrets.json") or {}).get("Results") or []
             for s in r.get("Secrets") or []]
    files = (load(out, "tree-files.txt") or "?").strip()
    found = re.search(r"(\d+) commits scanned", load(out, "gitleaks-history.log") or "")
    scanned = int(found.group(1)) if found else None
    expected = commits_with_changes()
    total = (git("rev-list", "--all", "--count") or "?").strip()
    print("\nSECRETS  [any finding fails; values are redacted and never printed]")
    print("  gitleaks history : %d finding(s)  [%s of %s commits with changes, all refs, merges by first parent; %s in all]"
          % (len(history), scanned, expected, total))
    print("  gitleaks tree    : %d finding(s)  [%s files: tracked plus untracked, .gitignore honoured]"
          % (len(tree), files))
    print("  trivy secret     : %d finding(s)  [the same %s files, second instrument]" % (len(trivy), files))
    for f in history:
        print("    HISTORY %s %s:%s commit %s" % (f.get("RuleID"), f.get("File"), f.get("StartLine"),
                                                   (f.get("Commit") or "")[:8]))
    for f in tree:
        print("    TREE    %s %s:%s" % (f.get("RuleID"), (f.get("File") or "").replace("/out/stage-tree/", ""),
                                         f.get("StartLine")))
    for target, rule, line in trivy:
        print("    TRIVY   %s %s:%s" % (rule, target, line))
    if scanned is None or expected is None or scanned != expected:
        failures.append("gitleaks history covered %s commits where git counts %s with changes" % (scanned, expected))
    if history or tree or trivy:
        failures.append("%d secret finding(s)" % (len(history) + len(tree) + len(trivy)))


def builder_layers(inspect):
    """Returns (app layers, layers the builder or run image supplied) from the buildpacks label."""
    image = inspect[0]
    meta = json.loads((image.get("Config") or {}).get("Labels", {}).get(LIFECYCLE_LABEL) or "{}")
    app = {a["sha"] for a in meta.get("app") or []}
    supplied = {(meta.get("launcher") or {}).get("sha")}
    for buildpack in meta.get("buildpacks") or []:
        supplied |= {layer.get("sha") for layer in (buildpack.get("layers") or {}).values()}
    layers = (image.get("RootFS") or {}).get("Layers") or []
    top = (meta.get("runImage") or {}).get("topLayer")
    if top in layers:
        supplied |= set(layers[:layers.index(top) + 1])
    return app, supplied - app - {None}


# An app layer is ours even when every image carries it; a layer in neither set is ours too (fails safe).
def image_rows(report, supplied):
    rows = set()
    for result in report.get("Results") or []:
        for v in result.get("Vulnerabilities") or []:
            layer = (v.get("Layer") or {}).get("DiffID", "")
            rows.add(("SHARED" if layer in supplied else "SERVICE", v.get("Severity", "UNKNOWN"),
                      v.get("VulnerabilityID", ""), v.get("PkgName", ""), v.get("InstalledVersion", ""),
                      v.get("FixedVersion") or ""))
    return rows


def images(out, services, failures):
    common = set((load(out, "images/shared-layers.txt") or "").split())
    per = {}
    for s in services:
        report, inspect = load(out, "images/%s.json" % s), load(out, "images/%s.inspect.json" % s)
        if report is None or not inspect:
            return
        app, supplied = builder_layers(inspect)
        if not app:
            failures.append("%s carries no %s app layers, so no layer can be attributed" % (s, LIFECYCLE_LABEL))
        per[s] = image_rows(report, supplied)
    db = ((load(out, "trivy-version.json") or {}).get("VulnerabilityDB") or {}).get("UpdatedAt", "unknown")
    print("\nIMAGES  [%d images, %d layers carried by all %d; trivy DB updated %s]"
          % (len(services), len(common), len(services), db))
    print("  %-16s %7s %7s %9s   %s" % ("service", "SERVICE", "SHARED", "distinct", "gating"))
    for s in services:
        rows = per[s]
        print("  %-16s %7d %7d %9d   %d" % (
            s, sum(r[0] == "SERVICE" for r in rows), sum(r[0] == "SHARED" for r in rows), len(rows),
            sum(r[0] == "SERVICE" and r[1] in GATING and bool(r[5]) for r in rows)))
    every = set().union(*per.values())
    for scope, title in (("SERVICE", "app layers and unattributed layers, ours to fix"),
                         ("SHARED", "builder and run-image layers, fixed by moving those pins")):
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


def sbom(out, services, failures):
    print("\nSBOM  [CycloneDX written by trivy from each image, build/supply-chain/sbom/]")
    for s in services:
        doc = load(out, "sbom/%s.cdx.json" % s) or {}
        count = len(doc.get("components") or [])
        print("  %-16s %4d components  CycloneDX %s" % (s, count, doc.get("specVersion", "?")))
        if count == 0:
            failures.append("sbom/%s.cdx.json lists 0 components, which no image built here can have" % s)


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
        sbom(args.out, services, failures)
    if "config" in required:
        config(args.out)
    entries, bad = ignore_problems()
    print("\nSUPPRESSIONS  security/trivy-ignore.yaml holds %d vulnerability entr%s"
          % (entries, "y" if entries == 1 else "ies"))
    for reason in bad:
        failures.append("trivy-ignore.yaml: " + reason)

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
