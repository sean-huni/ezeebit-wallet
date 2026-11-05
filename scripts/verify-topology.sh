#!/usr/bin/env bash
# Release gate: the services the fleet diagram draws are exactly the services the build ships.
# exit 0 conformant, 1 a service deviates, 2 an input could not be read (nothing was learned).
set -uo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
diagram="${TOPOLOGY_DIAGRAM:-$root/docs/fleet/fleet-architecture.html}"
services_dir="${TOPOLOGY_SERVICES_DIR:-$root/services}"
settings="${TOPOLOGY_SETTINGS:-$root/settings.gradle}"

for f in "$diagram" "$settings"; do
	[[ -r "$f" ]] || { echo "unreadable: $f"; exit 2; }
done
[[ -d "$services_dir" ]] || { echo "unreadable: $services_dir"; exit 2; }

drawn=$(grep -oE '\b[a-z]+-svc\b' "$diagram" | sort -u)
built=$(find "$services_dir" -mindepth 2 -maxdepth 2 -name build.gradle -exec dirname {} \; | xargs -n1 basename | sort -u)
included=$(grep -oE "services:[a-z]+-svc" "$settings" | cut -d: -f2 | sort -u)
[[ -n "$drawn" && -n "$built" && -n "$included" ]] || { echo "an input named no services"; exit 2; }

fail=0
report() {
	local label="$1" missing
	missing=$(comm -23 <(printf '%s\n' "$2") <(printf '%s\n' "$3"))
	if [[ -n "$missing" ]]; then
		fail=1
		echo "FAIL $label: $(echo $missing)"
	fi
}
report "drawn but not built" "$drawn" "$built"
report "built but not drawn" "$built" "$drawn"
report "built but not in settings.gradle" "$built" "$included"
report "in settings.gradle but not built" "$included" "$built"

echo "drawn $(wc -w <<< "$drawn"), built $(wc -w <<< "$built"), included $(wc -w <<< "$included"): $(echo $built)"
[[ $fail -eq 0 ]] && echo "PASS topology" || echo "FAIL topology"
exit $fail
