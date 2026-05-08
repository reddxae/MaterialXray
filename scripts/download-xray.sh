#!/bin/bash
set -euo pipefail

VERSION="${1:-v26.3.27}"
BASE_URL="https://github.com/XTLS/Xray-core/releases/download/${VERSION}"
WORK_DIR="$(mktemp -d)"

cleanup() {
  rm -rf "${WORK_DIR}"
}
trap cleanup EXIT

download_xray() {
  local archive_name="$1"
  local destination="$2"
  local unpack_dir="${WORK_DIR}/${archive_name%.zip}"

  echo "Downloading ${archive_name}..."
  curl -fL "${BASE_URL}/${archive_name}" -o "${WORK_DIR}/${archive_name}"
  mkdir -p "${unpack_dir}"
  unzip -qo "${WORK_DIR}/${archive_name}" xray -d "${unpack_dir}"
  mkdir -p "$(dirname "${destination}")"
  cp "${unpack_dir}/xray" "${destination}"
  chmod 755 "${destination}"
}

echo "Downloading xray-core ${VERSION}..."

# Root service mode needs the Linux binary: it creates and configures TUN from a root shell.
download_xray "Xray-linux-arm64-v8a.zip" "app/src/main/assets/xray_arm64"
chmod 644 "app/src/main/assets/xray_arm64"

# Rootless VpnService mode needs the Android binary: it consumes VpnService's tun fd via xray.tun.fd.
download_xray "Xray-android-arm64-v8a.zip" "app/src/main/jniLibs/arm64-v8a/libxray.so"

echo "Done."
file app/src/main/assets/xray_arm64 app/src/main/jniLibs/arm64-v8a/libxray.so
ls -lh app/src/main/assets/xray_arm64 app/src/main/jniLibs/arm64-v8a/libxray.so
