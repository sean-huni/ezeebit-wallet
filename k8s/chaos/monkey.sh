#!/usr/bin/env bash
# Kills pods of the fleet: draws a service uniformly, then one Running pod of it uniformly, once per victim.
# Usage: monkey.sh [--victims N] [--service S] [--namespace ezeebit-app|ezeebit-data]
# Prints `KILL <ns>/<pod> service=<svc>` per kill, then `KILLED <n>`. Exit 1 when n < N, 2 on a refused request.
# The same file runs in the CronJob (service account, no context) and on the host (context kind-ezeebit).
set -uo pipefail

ALLOWED=(ezeebit-app ezeebit-data)
PART_OF=app.kubernetes.io/part-of=ezeebit

refuse() { printf 'monkey: %s\n' "$*" >&2; exit 2; }

# Outside a pod there is no service account, so the host must name the cluster: never the kubeconfig's current one.
[[ -z ${KUBERNETES_SERVICE_HOST:-} ]] && : "${KCTL_CONTEXT:=kind-ezeebit}"
[[ -z ${KCTL_CONTEXT:-} || $KCTL_CONTEXT == kind-ezeebit ]] || refuse "context $KCTL_CONTEXT is not kind-ezeebit"
CTX=()
[[ -n ${KCTL_CONTEXT:-} ]] && CTX=(--context "$KCTL_CONTEXT")
k() { kubectl ${CTX[@]+"${CTX[@]}"} "$@"; }

victims=1 only_service="" namespaces=()
while (( $# )); do
  case $1 in
    --victims) [[ ${2:-} =~ ^[1-9][0-9]*$ ]] || refuse "--victims needs a positive integer"; victims=$2; shift ;;
    --service) [[ -n ${2:-} ]] || refuse "--service needs a name"; only_service=$2; shift ;;
    --namespace)
      [[ " ${ALLOWED[*]} " == *" ${2:-} "* ]] || refuse "namespace '${2:-}' is outside ${ALLOWED[*]}"
      namespaces+=("$2"); shift ;;
    *) refuse "unknown option $1" ;;
  esac
  shift
done
(( ${#namespaces[@]} )) || namespaces=("${ALLOWED[@]}")

# A service is a Deployment or a StatefulSet: ezeebit-data holds both kinds (redis is a Deployment).
SERVICES=()
for ns in "${namespaces[@]}"; do
  names=$(k get deployments,statefulsets -n "$ns" -l "$PART_OF" \
    -o jsonpath='{range .items[*]}{.kind}/{.metadata.name}{"\n"}{end}'); rc=$?
  if (( rc != 0 )); then
    printf 'monkey: listing services in %s exited %s\n' "$ns" "$rc" >&2
    continue
  fi
  while read -r name; do
    [[ -n $name ]] || continue
    [[ -z $only_service || ${name#*/} == "$only_service" ]] && SERVICES+=("$ns/$name")
  done <<< "$names"
done
(( ${#SERVICES[@]} )) || printf 'monkey: no service%s in %s\n' "${only_service:+ $only_service}" "${namespaces[*]}" >&2

killed=0
for (( i = 0; i < victims && ${#SERVICES[@]} > 0; i++ )); do
  pick=${SERVICES[RANDOM % ${#SERVICES[@]}]}
  ns=${pick%%/*} svc=${pick##*/} kind=${pick#*/}; kind=${kind%/*}
  # shellcheck disable=SC2016
  selector=$(k get "$kind" "$svc" -n "$ns" \
    -o go-template='{{range $k, $v := .spec.selector.matchLabels}}{{$k}}={{$v}},{{end}}'); rc=$?
  if (( rc != 0 )) || [[ -z $selector ]]; then
    printf 'monkey: selector of %s exited %s\n' "$pick" "$rc" >&2
    continue
  fi
  # A force-deleted pod can linger as Running with a deletion timestamp; it is already a victim.
  pods=$(k get pods -n "$ns" -l "${selector%,}" --field-selector=status.phase=Running \
    -o go-template='{{range .items}}{{if not .metadata.deletionTimestamp}}{{.metadata.name}}{{"\n"}}{{end}}{{end}}'); rc=$?
  candidates=()
  if (( rc == 0 )); then
    while read -r pod; do [[ -n $pod ]] && candidates+=("$pod"); done <<< "$pods"
  fi
  if (( ${#candidates[@]} == 0 )); then
    printf 'monkey: no Running pod of %s (list exited %s)\n' "$pick" "$rc" >&2
    continue
  fi
  pod=${candidates[RANDOM % ${#candidates[@]}]}
  out=$(k delete pod "$pod" -n "$ns" --grace-period=0 --force --wait=false 2>&1); rc=$?
  if (( rc == 0 )); then
    printf 'KILL %s/%s service=%s\n' "$ns" "$pod" "$svc"
    killed=$((killed + 1))
  else
    printf 'monkey: delete %s/%s exited %s: %s\n' "$ns" "$pod" "$rc" "$out" >&2
  fi
done

printf 'KILLED %s\n' "$killed"
(( killed >= victims )) || exit 1
