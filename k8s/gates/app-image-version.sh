#!/usr/bin/env bash
# Every ezeebit/<svc> image under k8s/ is tagged with build.gradle's version, the tag kube.sh builds and loads.
# Usage: app-image-version.sh <repository root>
set -uo pipefail
GATE=app-image-version
ROOT=${1:?usage: app-image-version.sh <repository root>}
cd "$ROOT" || { printf 'FAIL %s: cannot enter %s\n' "$GATE" "$ROOT"; exit 1; }

SERVICES=(config-svc eureka-svc ledger-svc conversion-svc gateway-svc)

[[ -f build.gradle && -d k8s ]] || { printf 'FAIL %s: build.gradle or k8s/ missing\n' "$GATE"; exit 1; }
versions=$(sed -nE "s/^[[:space:]]*version = '([^']+)'.*/\1/p" build.gradle); rc=$?
(( rc == 0 )) || { printf 'FAIL %s: sed over build.gradle exited %s\n' "$GATE" "$rc"; exit 1; }
[[ -n $versions && $versions != *$'\n'* ]] || { printf "FAIL %s: want exactly one version = '...' line in build.gradle, got [%s]\n" "$GATE" "$versions"; exit 1; }
version=$versions

files=()
while IFS= read -r f; do files+=("$f"); done < <(find k8s -type f \( -name '*.yaml' -o -name '*.yml' \) | sort)
(( ${#files[@]} )) || { printf 'FAIL %s: no yaml under k8s/\n' "$GATE"; exit 1; }

# file<TAB>ref for every image: line naming ezeebit/, and for every kustomize images: override naming it.
refs=$(awk '
  /^[[:space:]]*(-[[:space:]]+)?(image|name|newName):[[:space:]]*["\047]?ezeebit\// {
    line = $0; sub(/^[^:]*:[[:space:]]*/, "", line); gsub(/["\047[:space:]]/, "", line); print FILENAME "\t" line
  }
  /^[[:space:]]*(-[[:space:]]+)?(newTag|digest):/ { print FILENAME "\tOVERRIDE " $0 }
' "${files[@]}"); rc=$?
(( rc == 0 )) || { printf 'FAIL %s: awk exited %s\n' "$GATE" "$rc"; exit 1; }

bad=() seen=" "
while IFS=$'\t' read -r file ref; do
  [[ -z ${file:-} ]] && continue
  if [[ $ref == OVERRIDE* ]]; then bad+=("$file overrides an image tag (${ref#OVERRIDE }): the version lives in the manifest"); continue; fi
  svc=${ref#ezeebit/}; svc=${svc%%[:@]*}
  [[ " ${SERVICES[*]} " == *" $svc "* ]] || { bad+=("$file names $ref, not one of: ${SERVICES[*]}"); continue; }
  seen+="$svc "
  [[ $ref == "ezeebit/$svc:$version" ]] || bad+=("$file names $ref, build.gradle version is $version (want ezeebit/$svc:$version)")
done <<< "$refs"
for svc in "${SERVICES[@]}"; do
  [[ $seen == *" $svc "* ]] || bad+=("no manifest under k8s/ names ezeebit/$svc")
done

printf 'build.gradle version: %s; ezeebit image refs checked: %s\n' "$version" "$(printf '%s\n' "$refs" | awk 'NF {n++} END {print n + 0}')"
if (( ${#bad[@]} == 0 )); then
  printf 'PASS %s\n' "$GATE"
  exit 0
fi
printf '  %s\n' "${bad[@]}"
printf 'FAIL %s: %s mismatch(es)\n' "$GATE" "${#bad[@]}"
exit 1
