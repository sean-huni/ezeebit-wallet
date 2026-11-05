#!/usr/bin/env bash
# Release gate: dynamic scans (DAST) of the running edge, through the gateway as a caller sees it. ZAP actively attacks
# every operation in the public OpenAPI specs, Schemathesis fuzzes them for server errors, Nuclei probes for known
# Spring exposures. `control` runs the same scans against security/dast-fixture.py, where each must find its plant.
# usage: security/dast.sh [fleet|control]   exit 0 clean, 1 a finding (or a missed plant), 2 a scanner could not run
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SEC="$ROOT/security"
LOCK="$SEC/scanners.lock"
MODE="${1:-fleet}"
OUT="${DAST_OUT:-$ROOT/build/dast}/$MODE"
SPECS=(conversion-svc ledger-svc)

die() { printf 'dast: %s\n' "$*" >&2; exit 2; }

lock_value() { awk -F= -v k="$1" '$1 == k {sub(/^[^=]*=/, ""); print; exit}' "$LOCK"; }

pinned_image() {
  local ref
  ref="$(lock_value "$1")"
  [[ "$ref" =~ ^[^@]+:[^@]+@sha256:[0-9a-f]{64}$ ]] || die "$1=$ref in $LOCK is not pinned as tag@sha256:<digest>."
  printf '%s\n' "$ref"
}

ZAP_IMAGE="$(pinned_image ZAP_IMAGE)" || exit 2
ST_IMAGE="$(pinned_image SCHEMATHESIS_IMAGE)" || exit 2
NUCLEI_IMAGE="$(pinned_image NUCLEI_IMAGE)" || exit 2
TEMPLATES="$(lock_value NUCLEI_TEMPLATES_VOLUME)"
[ -n "$TEMPLATES" ] || die "$LOCK declares no NUCLEI_TEMPLATES_VOLUME."

# One IPv4 name for the host in every container. The scanners attack whatever this resolves to.
RUN=(docker run --rm --add-host dast-target:host-gateway)
FIXTURE_PID=""
trap '[ -n "$FIXTURE_PID" ] && kill "$FIXTURE_PID" 2> /dev/null' EXIT

target() {
  case "$MODE" in
    fleet)
      local url="${DAST_TARGET:-http://localhost:8080}"
      # These scanners send attack payloads and write rows. They run against a local fleet, never a shared one.
      [[ "$url" =~ ^http://(localhost|127\.0\.0\.1):([0-9]+)$ ]] || die "DAST_TARGET=$url is not a loopback URL; refusing to attack it."
      PORT="${BASH_REMATCH[2]}" ;;
    control)
      python3 -I "$SEC/dast-fixture.py" 0 > "$OUT/fixture.log" 2>&1 &
      FIXTURE_PID=$!
      for _ in $(seq 50); do grep -q 'listening' "$OUT/fixture.log" && break; sleep 0.1; done
      PORT="$(sed -n 's/.*127\.0\.0\.1:\([0-9]*\).*/\1/p' "$OUT/fixture.log")"
      [ -n "$PORT" ] || die "the control fixture did not start: $(cat "$OUT/fixture.log")" ;;
    *) die "unknown mode $MODE (fleet or control)." ;;
  esac
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/actuator/health")"
  [ "$code" = 200 ] || die "the target on port $PORT answered $code to /actuator/health; nothing was scanned."
  TARGET="http://dast-target:$PORT"
}

# Throttled under the gateway's 20/s per-merchant refill: ZAP reuses one merchant id, and its SQL-injection rule read
# the 429s of an unthrottled run as High (measured 2026-11-05).
zap() {
  local spec rc
  for spec in "${SPECS[@]}"; do
    cp "$ROOT/docs/openapi/$spec.json" "$OUT/zap/"
    "${RUN[@]}" -v "$OUT/zap:/zap/wrk:rw" "$ZAP_IMAGE" zap-api-scan.py -t "/zap/wrk/$spec.json" -f openapi -O "$TARGET" \
      -J "$spec-report.json" -r "$spec-report.html" -I -z '-config scanner.threadPerHost=1 -config scanner.delayInMs=100' \
      > "$OUT/zap/$spec.log" 2>&1
    rc=$?
    [ "$rc" -le 2 ] && [ -s "$OUT/zap/$spec-report.json" ] || { echo "ERROR zap $spec: exit $rc, see $OUT/zap/$spec.log"; return 2; }
    grep -Eq 'Number of Imported URLs: [1-9]' "$OUT/zap/$spec.log" || { echo "ERROR zap $spec: imported no operations"; return 2; }
  done
  python3 -I - "$OUT/zap" "$MODE" <<'PY'
import glob, json, sys
out, mode = sys.argv[1], sys.argv[2]
high, version = [], "?"
for report in sorted(glob.glob(f"{out}/*-report.json")):
    spec = report.rsplit("/", 1)[1].removesuffix("-report.json")
    data = json.load(open(report))
    version = data["@version"]
    for site in data["site"]:
        for alert in site["alerts"]:
            if alert["riskcode"] == "3":
                high.append((spec, alert["pluginid"], alert["name"], [i["uri"] for i in alert["instances"]][:3]))
print(f"zap {version}: {len(high)} High alert(s)")
for spec, plugin, name, uris in high:
    print(f"  HIGH {spec} [{plugin}] {name}: {', '.join(uris)}")
if mode == "control":
    caught = any(plugin == "40018" for _, plugin, _, _ in high)
    print(f"{'PASS' if caught else 'FAIL'} zap control: planted SQL injection [40018] {'found' if caught else 'MISSED'}")
    sys.exit(0 if caught else 1)
print(f"{'FAIL' if high else 'PASS'} zap")
sys.exit(1 if high else 0)
PY
}

schemathesis() {
  local spec rc worst=0
  for spec in "${SPECS[@]}"; do
    mkdir -p "$OUT/schemathesis/$spec" && chmod 777 "$OUT/schemathesis/$spec"
    "${RUN[@]}" -v "$ROOT/docs/openapi:/specs:ro" -v "$OUT/schemathesis/$spec:/out" "$ST_IMAGE" run "/specs/$spec.json" \
      --url "$TARGET/api" --checks not_a_server_error --max-examples 50 --rate-limit 15/s --seed 20261105 \
      --report junit --report-dir /out > "$OUT/schemathesis/$spec.log" 2>&1
    rc=$?
    [ "$rc" -le 1 ] || { echo "ERROR schemathesis $spec: exit $rc, see $OUT/schemathesis/$spec.log"; return 2; }
    grep -Eq 'Tested: [1-9]' "$OUT/schemathesis/$spec.log" || { echo "ERROR schemathesis $spec: tested no operations"; return 2; }
    printf 'schemathesis %s: %s\n' "$spec" "$(grep -E '^ *[0-9]+ generated' "$OUT/schemathesis/$spec.log" | sed 's/^ *//')"
    grep -E '^- ST[0-9]+|^\[[0-9]{3}\]' "$OUT/schemathesis/$spec.log" | sort | uniq -c | sed 's/^/  /'
    [ "$rc" -eq 1 ] && worst=1
  done
  if [ "$MODE" = control ]; then
    if grep -q 'ST101' "$OUT/schemathesis/ledger-svc.log"; then echo "PASS schemathesis control: planted server error found"; return 0; fi
    echo "FAIL schemathesis control: planted server error MISSED"
    return 1
  fi
  [ "$worst" -eq 0 ] && echo "PASS schemathesis" || echo "FAIL schemathesis"
  return "$worst"
}

nuclei() {
  local rc
  chmod 777 "$OUT/nuclei"
  "${RUN[@]}" -v "$TEMPLATES:/root/nuclei-templates" -v "$OUT/nuclei:/out" "$NUCLEI_IMAGE" -u "$TARGET" \
    -tags springboot,spring,actuator,exposure,misconfig -severity low,medium,high,critical -rl 50 -nmhe -duc -nc \
    -jsonl -o /out/findings.jsonl > "$OUT/nuclei/nuclei.log" 2>&1
  rc=$?
  [ "$rc" -eq 0 ] || { echo "ERROR nuclei: exit $rc, see $OUT/nuclei/nuclei.log"; return 2; }
  grep -Eq 'Templates loaded for current scan: [1-9]' "$OUT/nuclei/nuclei.log" || { echo "ERROR nuclei: loaded no templates"; return 2; }
  # -nmhe keeps Nuclei on a target that times out on a malformed probe, so a target that died must be caught here.
  [ "$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:$PORT/actuator/health")" = 200 ] \
    || { echo "ERROR nuclei: the target stopped answering during the scan"; return 2; }
  touch "$OUT/nuclei/findings.jsonl"
  python3 -I - "$OUT/nuclei" "$MODE" <<'PY'
import json, re, sys
out, mode = sys.argv[1], sys.argv[2]
loaded = re.search(r"Templates loaded for current scan: (\d+)", open(f"{out}/nuclei.log").read()).group(1)
found = [json.loads(line) for line in open(f"{out}/findings.jsonl") if line.strip()]
print(f"nuclei: {loaded} templates, {len(found)} finding(s) at low or above")
for f in found:
    print(f"  {f['info']['severity'].upper()} {f['template-id']}: {f.get('matched-at')}")
if mode == "control":
    caught = any(f["template-id"] == "springboot-env" for f in found)
    print(f"{'PASS' if caught else 'FAIL'} nuclei control: planted /actuator/env {'found' if caught else 'MISSED'}")
    sys.exit(0 if caught else 1)
print(f"{'FAIL' if found else 'PASS'} nuclei")
sys.exit(1 if found else 0)
PY
}

docker info > /dev/null 2>&1 || die "Docker unavailable: every scanner runs as a container, so nothing was scanned."
rm -rf "$OUT" && mkdir -p "$OUT/zap" "$OUT/schemathesis" "$OUT/nuclei" && chmod 777 "$OUT/zap" || die "cannot prepare $OUT."
target
echo "dast $MODE: target $TARGET (host port $PORT), $(date -u +%FT%TZ)"
verdict=0
for scanner in zap schemathesis nuclei; do
  "$scanner"
  rc=$?
  echo "$scanner exit=$rc" >> "$OUT/status.txt"
  [ "$rc" -gt "$verdict" ] && verdict=$rc
done
echo "dast $MODE exit=$verdict"
exit "$verdict"
