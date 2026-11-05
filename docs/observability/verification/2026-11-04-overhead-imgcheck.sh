#!/usr/bin/env bash
# Kind image identity: host manifest == node manifest for ezeebit/<svc>:<version>, and that manifest's config digest
# == every running app container's image. Prints one row per service and exit=0 only when all match.
VERSION=$(sed -nE "s/^[[:space:]]*version = '([^']+)'.*/\1/p" "$(dirname "$0")/../../../build.gradle")
N=ezeebit-control-plane; bad=0
running=$(docker exec $N crictl ps -o json | jq -r '.containers[] | select(.labels["io.kubernetes.pod.namespace"]=="ezeebit-app") | "\(.labels["io.kubernetes.pod.name"]) \(.imageRef)"')
for s in config-svc eureka-svc ledger-svc conversion-svc gateway-svc; do
  ref=ezeebit/$s:$VERSION
  host=$(docker image inspect --format '{{.Descriptor.digest}}' "$ref")
  node=$(docker exec $N ctr -n k8s.io images ls "name==docker.io/$ref" | awk -v r="docker.io/$ref" '$1==r{print $3}')
  cfg=$(docker exec $N ctr -n k8s.io content get "$node" | jq -r .config.digest)
  pods=$(awk -v s="$s-" 'index($1,s)==1{print $2}' <<< "$running")
  n=$(awk 'NF{c++} END{print c+0}' <<< "$pods")
  ok=1; [[ -n $host && $host == "$node" && $cfg == sha256:* && $n -ge 1 ]] || ok=0
  for p in $pods; do [[ $p == "$cfg" ]] || ok=0; done
  [[ $ok == 1 ]] || bad=1
  echo "$s host-manifest=${host:7:12} node-manifest=${node:7:12} config=${cfg:7:12} running=$n match=$ok"
done
echo "imgcheck exit=$bad"; exit $bad
