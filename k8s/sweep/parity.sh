#!/usr/bin/env bash
# Spec 6.2: what runs is what compose runs; the singletons are single and unscaled; every HPA has its signal;
# and no image came from a registry during the bring-up.
set -uo pipefail
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
CHECK=parity
EXCEPTIONS=(alpine/k8s registry.k8s.io/metrics-server/metrics-server)
HPA_WAIT=180
findings=()

version=$(sed -nE "s/^[[:space:]]*version = '([^']+)'.*/\1/p" build.gradle)
[[ -n $version ]] || findings+=("no version in build.gradle")
compose=$(awk '/^[[:space:]]*image:/ {sub(/^[^:]*image:[[:space:]]*/, ""); gsub(/["\047[:space:]]/, ""); print}' compose.yaml)
[[ -n $compose ]] || findings+=("no image in compose.yaml")

# Spec images of every pod in the four namespaces and kube-system's metrics-server, from the live API.
live=""
for ns in ezeebit-data ezeebit-app ezeebit-obs ezeebit-chaos kube-system; do
  out=$(kctl get pods -n "$ns" -o jsonpath='{range .items[*]}{range .spec.initContainers[*]}{.image}{"\n"}{end}{range .spec.containers[*]}{.image}{"\n"}{end}{end}'); rc=$?
  (( rc == 0 )) || { findings+=("listing pods in $ns exited $rc"); continue; }
  live+=$out$'\n'
done
live_third=() live_own=()
while IFS= read -r img; do
  [[ -z $img ]] && continue
  case $img in
    ezeebit/*) live_own+=("$img") ;;
    registry.k8s.io/kube-*|registry.k8s.io/coredns/*|registry.k8s.io/etcd:*|docker.io/kindest/*|kindest/*) ;;
    *)
      skip=0
      for e in "${EXCEPTIONS[@]}"; do [[ ${img%%[:@]*} == "$e" ]] && skip=1; done
      (( skip )) || live_third+=("$img") ;;
  esac
done < <(printf '%s' "$live" | sort -u)
say "live third-party images: ${#live_third[@]}; compose images: $(printf '%s\n' "$compose" | awk 'NF {n++} END {print n + 0}')"
for img in ${live_third[@]+"${live_third[@]}"}; do
  if [[ $'\n'$compose$'\n' == *$'\n'"$img"$'\n'* ]]; then say "  live = compose: $img"; else findings+=("live $img is not in compose.yaml"); fi
done
while IFS= read -r img; do
  [[ -z $img ]] && continue
  [[ " ${live_third[*]-} " == *" $img "* ]] || findings+=("compose $img runs nowhere in the cluster")
done <<< "$compose"
say "live ezeebit images: ${live_own[*]-none} (want :$version)"
(( ${#live_own[@]} == 5 )) || findings+=("${#live_own[@]} distinct ezeebit images live, want 5")
for img in ${live_own[@]+"${live_own[@]}"}; do [[ $img == *":$version" ]] || findings+=("$img is not :$version"); done

for svc in config-svc eureka-svc; do
  got=$(kctl get deployment "$svc" -n "$APP_NS" -o jsonpath='{.spec.replicas} {.status.readyReplicas}'); rc=$?
  say "$svc spec/ready replicas: $got (want 1 1)"
  [[ $rc == 0 && $got == "1 1" ]] || findings+=("$svc replicas '$got' (exit $rc), want '1 1'")
done
hpas=$(kctl get hpa -A -o jsonpath='{range .items[*]}{.metadata.namespace}/{.metadata.name} {.spec.scaleTargetRef.name}{"\n"}{end}'); rc=$?
(( rc == 0 )) || findings+=("listing HPAs exited $rc")
say "HPAs: $(printf '%s' "$hpas" | tr '\n' ';')"
for svc in config-svc eureka-svc; do
  [[ $'\n'$hpas$'\n' == *" $svc"$'\n'* ]] && findings+=("an HPA targets $svc")
done

# metrics-server needs a scrape or two after the pods start before the HPA can compute.
for svc in gateway-svc ledger-svc conversion-svc; do
  waited=0 state=''
  while (( waited <= HPA_WAIT )); do
    state=$(kctl get hpa "$svc" -n "$APP_NS" -o jsonpath='{.status.conditions[?(@.type=="ScalingActive")].status}')
    [[ $state == True ]] && break
    sleep 5; waited=$((waited + 5))
  done
  say "hpa/$svc ScalingActive=$state after ${waited}s (want True within ${HPA_WAIT}s)"
  [[ $state == True ]] || findings+=("hpa/$svc ScalingActive='$state' after ${HPA_WAIT}s")
done

pulls=$(kctl get events -A --field-selector reason=Pulling -o jsonpath='{range .items[*]}{.involvedObject.namespace}/{.involvedObject.name}: {.message}{"\n"}{end}'); rc=$?
(( rc == 0 )) || findings+=("listing Pulling events exited $rc")
say "kubelet Pulling events: $(printf '%s' "$pulls" | awk 'NF {n++} END {print n + 0}') (want 0)"
[[ -z $pulls ]] || findings+=("the node pulled from a registry: $(printf '%s' "$pulls" | tr '\n' ';')")

verdict "$CHECK" ${findings[@]+"${findings[@]}"}
