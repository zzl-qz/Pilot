#!/usr/bin/env bash
# Build an isolated Pilot server JAR from any git commit SHA using 100% read-only `git archive`.
# Never modifies the repository working tree, branches, index, or worktrees.
#
# Usage:
#   bash scripts/build-pilot-jar.sh <git-ref-or-sha> <output-tag>
set -euo pipefail

REF="${1:?Usage: build-pilot-jar.sh <git-ref> <tag>}"
TAG="${2:?Usage: build-pilot-jar.sh <git-ref> <tag>}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="${PILOT_MSB_ROOT:-$(cd "${SCRIPT_DIR}/.." && pwd)}"
REPO="${PILOT_REPO:-$(cd "${ROOT}/../.." && pwd)}"
JARS_DIR="${ROOT}/jars"
SRC_DIR="${ROOT}/build-src/${TAG}-$$"

sha256_cmd() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$@"
  else
    shasum -a 256 "$@"
  fi
}

mkdir -p "${JARS_DIR}" "${SRC_DIR}"
SHA="$(git -C "${REPO}" rev-parse "${REF}")"
SHORT_SHA="$(git -C "${REPO}" rev-parse --short "${SHA}")"
echo "[build] Exporting ${REF} (${SHORT_SHA}) via read-only git archive into ${SRC_DIR}..."

cleanup() {
  rm -rf "${SRC_DIR}"
}
trap cleanup EXIT

git -C "${REPO}" archive --format=tar "${SHA}" | tar -xf - -C "${SRC_DIR}"

echo "[build] Building pilot-server JAR with JDK 25 (skipping tests & frontend)..."
(
  cd "${SRC_DIR}"
  sh mvnw -B -ntp -pl pilot-server -am package -DskipTests -DskipFrontend=true
)

SRC_JAR="$(ls -1 "${SRC_DIR}"/pilot-server/target/pilot-server-*.jar | grep -v '\.original$' | head -n 1)"
DEST_JAR="${JARS_DIR}/${TAG}.jar"
cp "${SRC_JAR}" "${DEST_JAR}"

PROMPT_SHA="$(find "${SRC_DIR}/pilot-core/src/main/resources/prompts" -type f 2>/dev/null | LC_ALL=C sort | while read -r f; do sha256_cmd "$f"; done | sha256_cmd | awk '{print $1}')"
JAR_SHA="$(sha256_cmd "${DEST_JAR}" | awk '{print $1}')"

cat > "${DEST_JAR}.meta.json" <<METAEOF
{
  "tag": "${TAG}",
  "requestedRef": "${REF}",
  "gitSha": "${SHA}",
  "shortSha": "${SHORT_SHA}",
  "jarSha256": "${JAR_SHA}",
  "promptSha256": "${PROMPT_SHA}",
  "builtAt": "$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
}
METAEOF

echo "[build] Done: ${DEST_JAR} (sha=${SHORT_SHA}, jarSha256=${JAR_SHA:0:12})"
