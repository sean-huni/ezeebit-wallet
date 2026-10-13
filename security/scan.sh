#!/usr/bin/env bash
# Runs the pinned scanners as containers and writes one report file plus one exit code per scanner.
# security/report.py reads those files and alone decides the verdict. Run it through Gradle
# (checkSecrets, checkSupplyChain), which supplies SERVICES, IMAGE_VERSION, OUT_DIR and CONSOLE_FILE.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SEC="$ROOT/security"
LOCK="$SEC/scanners.lock"

die() { printf 'scan: %s\n' "$*" >&2; exit 2; }

: "${SERVICES:?run through ./gradlew checkSecrets or checkSupplyChain, which set SERVICES}"
: "${IMAGE_VERSION:?IMAGE_VERSION is unset}"
: "${OUT_DIR:?OUT_DIR is unset}"
: "${CONSOLE_FILE:?CONSOLE_FILE is unset}"
OUT="$OUT_DIR"
read -r -a SVC <<< "$SERVICES"

lock_value() {
  local v
  v="$(awk -F= -v k="$1" '$1 == k {sub(/^[^=]*=/, ""); print; exit}' "$LOCK")"
  [ -n "$v" ] || die "$LOCK declares no $1."
  printf '%s\n' "$v"
}

pinned_image() {
  local ref
  ref="$(lock_value "$1")" || exit 2
  [[ "$ref" =~ ^[^@]+:[^@]+@sha256:[0-9a-f]{64}$ ]] || die "$1=$ref is not pinned as tag@sha256:<digest>."
  printf '%s\n' "$ref"
}

TRIVY_IMAGE="$(pinned_image TRIVY_IMAGE)" || exit 2
GITLEAKS_IMAGE="$(pinned_image GITLEAKS_IMAGE)" || exit 2
CACHE_VOLUME="$(lock_value TRIVY_CACHE_VOLUME)" || exit 2
DB_REPO="$(lock_value TRIVY_DB_REPOSITORY)" || exit 2
JAVA_DB_REPO="$(lock_value TRIVY_JAVA_DB_REPOSITORY)" || exit 2

# Containers run as the caller: as root they leave files under build/ that a Linux user cannot
# replace, and Docker Desktop's file sharing hides that on a Mac. HOME is for git inside gitleaks.
AS_CALLER=(--user "$(id -u):$(id -g)" -e HOME=/tmp)

record() { printf '%s exit=%s\n' "$1" "$2" >> "$OUT/status.txt"; }

cleanup() { rm -rf "$OUT/stage-tree" "$OUT/stage-config"; rm -f "$OUT"/images/*.tar; }

preflight() {
  docker info > /dev/null 2>&1 || die "Docker unavailable: every scanner runs as a container, so nothing was scanned."
  # The cache volume was created by root; the one root container hands it to the caller.
  docker run --rm --user 0:0 --entrypoint chown -v "$CACHE_VOLUME:/cache" "$TRIVY_IMAGE" -R "$(id -u):$(id -g)" /cache \
    || die "could not hand the trivy cache volume $CACHE_VOLUME to uid $(id -u)."
}

# Scanners see only /out (reports and staged inputs) and /sec (policy), never the repository root,
# so a gitignored file such as .env cannot reach a tree scan by any path.
trivy() {
  local sub="$1" db=()
  shift
  [ "$sub" = config ] || db=(--db-repository "$DB_REPO" --java-db-repository "$JAVA_DB_REPO")
  docker run --rm "${AS_CALLER[@]}" -v "$CACHE_VOLUME:/cache" -v "$OUT:/out" -v "$SEC:/sec:ro" "$TRIVY_IMAGE" "$sub" \
    --cache-dir /cache ${db[@]+"${db[@]}"} --skip-version-check "$@"
}

# Copies the files a NUL-separated git listing names into $1 and prints how many arrived; the
# count must equal the listing's, so a copy that silently dropped files cannot pass as a clean tree.
stage_files() {
  local dest="$1" list="$1.list" listed staged
  shift
  mkdir -p "$dest"
  ( cd "$ROOT" && "$@" | while IFS= read -r -d '' f; do if [ -f "$f" ]; then printf '%s\0' "$f"; fi; done ) > "$list"
  listed="$(tr -cd '\0' < "$list" | wc -c | tr -d ' ')"
  ( cd "$ROOT" && tar --null -T - -cf - < "$list" ) | tar -xf - -C "$dest" || die "staging into $dest failed."
  rm -f "$list"
  staged="$(find "$dest" -type f | awk 'END {print NR}')"
  [ "$listed" -gt 0 ] || die "the listing for $dest named 0 files."
  [ "$staged" = "$listed" ] || die "staged $staged of $listed listed files into $dest."
  printf '%s\n' "$staged"
}

cmd_secrets() {
  local rc n
  rm -f "$OUT"/gitleaks-*.json "$OUT"/gitleaks-*.log "$OUT"/trivy-secrets.* "$OUT/tree-files.txt"
  docker run --rm "${AS_CALLER[@]}" -v "$ROOT/.git:/repo/.git:ro" -v "$OUT:/out" \
    -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0='*' \
    "$GITLEAKS_IMAGE" git /repo "--log-opts=--all --diff-merges=first-parent" --redact --no-banner \
    --report-format json --report-path /out/gitleaks-history.json > "$OUT/gitleaks-history.log" 2>&1
  rc=$?; record gitleaks-history "$rc"

  rm -rf "$OUT/stage-tree"
  n="$(stage_files "$OUT/stage-tree" git ls-files -z --cached --others --exclude-standard)" || exit 2
  printf '%s\n' "$n" > "$OUT/tree-files.txt"

  docker run --rm "${AS_CALLER[@]}" -v "$OUT:/out" "$GITLEAKS_IMAGE" dir /out/stage-tree --redact --no-banner \
    --report-format json --report-path /out/gitleaks-tree.json > "$OUT/gitleaks-tree.log" 2>&1
  rc=$?; record gitleaks-tree "$rc"

  trivy fs --scanners secret --format json --quiet -o /out/trivy-secrets.json /out/stage-tree \
    > "$OUT/trivy-secrets.log" 2>&1
  rc=$?; record trivy-secrets "$rc"
  rm -rf "$OUT/stage-tree"
}

shared_layers() {
  local s
  for s in "${SVC[@]}"; do
    docker image inspect "ezeebit/$s:$IMAGE_VERSION" --format '{{range .RootFS.Layers}}{{println .}}{{end}}' \
      | awk 'NF' | sort -u
  done | sort | uniq -c | awk -v n="${#SVC[@]}" '$1 == n {print $2}'
}

cmd_images() {
  local s rc tar
  rm -rf "$OUT/images" "$OUT/sbom"
  mkdir -p "$OUT/images" "$OUT/sbom"
  for s in "${SVC[@]}"; do
    docker image inspect "ezeebit/$s:$IMAGE_VERSION" > /dev/null 2>&1 \
      || die "no image ezeebit/$s:$IMAGE_VERSION; run ./gradlew bootBuildImageAll."
  done
  shared_layers > "$OUT/images/shared-layers.txt"
  for s in "${SVC[@]}"; do
    docker image inspect "ezeebit/$s:$IMAGE_VERSION" > "$OUT/images/$s.inspect.json"
  done
  for s in "${SVC[@]}"; do
    tar="$OUT/images/$s.tar"
    docker save -o "$tar" "ezeebit/$s:$IMAGE_VERSION" || die "docker save of ezeebit/$s failed."
    # layers/sbom holds Paketo's SBOM documents, which restate packages scanned in their real layers;
    # read as a second source they put buildpack binaries into a per-image layer and misattribute them.
    trivy image --input "/out/images/$s.tar" --scanners vuln --format json --quiet --skip-dirs layers/sbom \
      --ignorefile /sec/trivy-ignore.yaml -o "/out/images/$s.json" > "$OUT/images/$s.log" 2>&1
    rc=$?; record "image-$s" "$rc"
    trivy image --input "/out/images/$s.tar" --format cyclonedx --quiet --skip-dirs layers/sbom \
      -o "/out/sbom/$s.cdx.json" > "$OUT/sbom/$s.log" 2>&1
    rc=$?; record "sbom-$s" "$rc"
    rm -f "$tar"
  done
  docker run --rm "${AS_CALLER[@]}" -v "$CACHE_VOLUME:/cache" "$TRIVY_IMAGE" version --cache-dir /cache --format json \
    > "$OUT/trivy-version.json" 2> "$OUT/trivy-version.log"
}

cmd_config() {
  local rc n
  rm -rf "$OUT/stage-config"
  rm -f "$OUT/misconfig.json" "$OUT/misconfig.log"
  n="$(stage_files "$OUT/stage-config" git ls-files -z -- compose.yaml docker ':(glob)**/Dockerfile*')" || exit 2
  printf '%s\n' "$n" > "$OUT/config-files.txt"
  trivy config --format json -o /out/misconfig.json /out/stage-config > "$OUT/misconfig.log" 2>&1
  rc=$?; record misconfig "$rc"
  rm -rf "$OUT/stage-config"
}

gate() {
  python3 "$SEC/report.py" --out "$OUT" --services "$SERVICES" --require "$1" --console "$CONSOLE_FILE"
}

start() { preflight; mkdir -p "$OUT"; : > "$OUT/status.txt"; trap cleanup EXIT; }

case "${1:-}" in
  secretgate) start; cmd_secrets; gate secrets ;;
  all)        start; cmd_secrets; cmd_images; cmd_config; gate secrets,sbom,images,config ;;
  gate)       gate "${2:?gate needs the stages, e.g. secrets,sbom,images,config}" ;;
  *)          printf 'usage: scan.sh secretgate|all|gate <stages>\n' >&2; exit 2 ;;
esac
