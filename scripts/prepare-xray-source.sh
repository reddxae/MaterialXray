#!/bin/bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

VERSION_FILE="third_party/xray/VERSION"
COMMIT_FILE="third_party/xray/COMMIT"
VERSION="${1:-$(<"${VERSION_FILE}")}"
OUTPUT="${2:-Xray-core-${VERSION}-source.tar.gz}"
COMMIT_OVERRIDE="${3:-}"
if [[ ! "${VERSION}" =~ ^v[0-9]+([.][0-9]+)*$ ]]; then
  echo "Invalid Xray version: ${VERSION}" >&2
  exit 1
fi
COMMIT="${COMMIT_OVERRIDE:-$(<"${COMMIT_FILE}")}"
if [[ ! "${COMMIT}" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Invalid Xray commit: ${COMMIT}" >&2
  exit 1
fi

WORK_DIR="$(mktemp -d)"
cleanup() {
  rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

SOURCE_ARCHIVE="${WORK_DIR}/Xray-core-${COMMIT}.tar.gz"
SOURCE_DIR="${WORK_DIR}/Xray-core-${COMMIT}"
curl -fL "https://github.com/XTLS/Xray-core/archive/${COMMIT}.tar.gz" -o "${SOURCE_ARCHIVE}"
tar -xzf "${SOURCE_ARCHIVE}" -C "${WORK_DIR}"

if [[ ! -d "${SOURCE_DIR}" ]]; then
  echo "Expected source directory was not present in the Xray archive" >&2
  exit 1
fi

(
  cd "${SOURCE_DIR}"
  go mod vendor
)

mkdir -p "$(dirname "${OUTPUT}")"
tar -czf "${OUTPUT}" -C "${WORK_DIR}" "$(basename "${SOURCE_DIR}")"
echo "Created ${OUTPUT}"
