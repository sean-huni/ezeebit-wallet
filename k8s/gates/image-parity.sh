#!/usr/bin/env bash
# Third-party images in k8s/base and compose.yaml are the same strings, both ways. Usage: image-parity.sh <repository root>
set -uo pipefail
GATE='image-parity'
ROOT=${1:?usage: image-parity.sh <repository root>}
cd "$ROOT" || { printf 'FAIL %s: cannot enter %s\n' "$GATE" "$ROOT"; exit 1; }

# k8s-only by design: the chaos actor, the HPA signal and the kind node have no compose twin.
EXCEPTIONS=(alpine/k8s registry.k8s.io/metrics-server/metrics-server kindest/node)

# Prints one image ref per line, unquoted.
images_in() {
  awk '/^[[:space:]]*(-[[:space:]]+)?image:/ {
    sub(/^[^:]*image:[[:space:]]*/, ""); gsub(/["\047[:space:]]/, ""); if ($0 != "") print
  }' "$@"
}

repo_of() { local r=${1%@*}; printf '%s' "${r%:*}"; }

excepted() {
  local repo e
  repo=$(repo_of "$1")
  for e in "${EXCEPTIONS[@]}"; do [[ $repo == "$e" ]] && return 0; done
  return 1
}

[[ -d k8s/base && -f compose.yaml ]] || { printf 'FAIL %s: k8s/base or compose.yaml missing\n' "$GATE"; exit 1; }
k8s_files=()
while IFS= read -r f; do k8s_files+=("$f"); done < <(find k8s/base -type f -name '*.yaml' | sort)
k8s_all=$(images_in "${k8s_files[@]}"); rc1=$?
compose_all=$(images_in compose.yaml); rc2=$?
(( rc1 == 0 && rc2 == 0 )) || { printf 'FAIL %s: awk exited %s/%s\n' "$GATE" "$rc1" "$rc2"; exit 1; }

k8s_third=() compose_imgs=() bad=()
while IFS= read -r i; do
  [[ -z $i || $i == ezeebit/* ]] && continue
  excepted "$i" && { printf 'excepted: %s\n' "$i"; continue; }
  k8s_third+=("$i")
done < <(printf '%s\n' "$k8s_all" | sort -u)
while IFS= read -r i; do [[ -n $i ]] && compose_imgs+=("$i"); done < <(printf '%s\n' "$compose_all" | sort -u)
(( ${#k8s_third[@]} && ${#compose_imgs[@]} )) || { printf 'FAIL %s: k8s third-party=%s compose=%s, an empty side compares nothing\n' "$GATE" "${#k8s_third[@]}" "${#compose_imgs[@]}"; exit 1; }

for i in "${k8s_third[@]}"; do
  [[ $'\n'$compose_all$'\n' == *$'\n'"$i"$'\n'* ]] || bad+=("k8s/base names $i, compose.yaml does not")
done
for i in "${compose_imgs[@]}"; do
  [[ $'\n'$k8s_all$'\n' == *$'\n'"$i"$'\n'* ]] || bad+=("compose.yaml names $i, k8s/base does not")
done

printf 'compared: %s k8s third-party images, %s compose images\n' "${#k8s_third[@]}" "${#compose_imgs[@]}"
if (( ${#bad[@]} == 0 )); then
  printf 'PASS %s\n' "$GATE"
  exit 0
fi
printf '  %s\n' "${bad[@]}"
printf 'FAIL %s: %s mismatch(es)\n' "$GATE" "${#bad[@]}"
exit 1
