# shellcheck shell=bash
# Sourced by the gates. A reader of the house manifest style (two-space indent, flow maps allowed), not of all YAML:
# a manifest it cannot read yields no record, and each gate fails on a missing record.

# manifest_records <dir>: one line per workload or HPA in <dir>/**/*.yaml:
#   Deployment|StatefulSet <name> <replicas>   and   HorizontalPodAutoscaler <name> <target> <min> <max> <cpu%>
manifest_records() {
  local files=() f
  while IFS= read -r f; do files+=("$f"); done < <(find "$1" -type f -name '*.yaml' | sort)
  (( ${#files[@]} )) || return 1
  awk '
    function flush() {
      if (kind == "Deployment" || kind == "StatefulSet") print kind, name, (replicas == "" ? "none" : replicas)
      else if (kind == "HorizontalPodAutoscaler") print kind, name, target, min, max, cpu
      kind = name = replicas = target = min = max = cpu = section = sub_ = ""
    }
    function value(line) { sub(/^[^:]*:[[:space:]]*/, "", line); gsub(/["\047[:space:]]/, "", line); return line }
    FNR == 1 { flush() }
    /^---/ { flush(); next }
    /^[A-Za-z]+:/ { section = $1; sub(/:$/, "", section) }
    /^kind:/ { kind = value($0) }
    section == "metadata" && /^  name:/ { name = value($0) }
    section == "spec" && /^  replicas:/ { replicas = value($0) }
    section == "spec" && /^  minReplicas:/ { min = value($0) }
    section == "spec" && /^  maxReplicas:/ { max = value($0) }
    section == "spec" && /^  scaleTargetRef:/ {
      sub_ = "scaleTargetRef"
      if (match($0, /name:[[:space:]]*[A-Za-z0-9-]+/)) { t = substr($0, RSTART, RLENGTH); sub(/name:[[:space:]]*/, "", t); target = t }
      next
    }
    section == "spec" && /^  [A-Za-z]/ { sub_ = "" }
    sub_ == "scaleTargetRef" && /^    name:/ { target = value($0) }
    section == "spec" && /averageUtilization:/ {
      if (match($0, /averageUtilization:[[:space:]]*[0-9]+/)) { c = substr($0, RSTART, RLENGTH); sub(/[^0-9]*/, "", c); cpu = c }
    }
    END { flush() }
  ' "${files[@]}"
}
