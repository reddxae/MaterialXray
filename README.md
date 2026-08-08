# Material Xray

Material Xray is an Android proxy client. It runs xray-core with a native TUN inbound, manages the process lifecycle, and routes device traffic through the selected outbound server using root-managed policy routing or Android `VpnService`.

This project is AI-assisted.

## Features

- Material Design 3 Jetpack Compose UI.
- Subscription import and refresh for common share-link formats.
- Server cards grouped by subscription.
- Per-server latency testing.
- Per-app bypass list.
- Foreground Xray service with live app and Xray logs.
- Root-managed Xray TUN interface.
- Per-app routing with custom proxy server selection.
- Policy routing for captured app traffic.
- Xray outbound binding to the active physical interface.
- Network-change retargeting for Wi-Fi and cellular switches.
- Pre-start hostname resolution with randomized A/AAAA selection.

## Requirements

- Android 8 or newer (actually tested on Android 14+). 
- Root access with `su` for root service mode.
- Android SDK and a JDK compatible with the Gradle wrapper.
- A connected Android device or emulator for install/debug flows.

The app includes:

- `app/src/main/assets/xray_arm64` for root service mode.
- `app/src/main/jniLibs/arm64-v8a/libxray.so` for rootless `VpnService` mode.

Only arm64 is wired up at the moment.

## Build

```sh
./gradlew assembleDebug
```

Build a signed release locally:

```sh
RELEASE_KEYSTORE_PATH=/path/to/release.keystore \
RELEASE_KEY_ALIAS=your_alias \
RELEASE_KEY_PASSWORD=your_key_password \
RELEASE_STORE_PASSWORD=your_store_password \
./gradlew assembleRelease
```

Run unit tests:

```sh
./gradlew testDebugUnitTest
```

Install the repository hooks with [prek](https://prek.j178.dev/):

```sh
prek install
```

Install the debug build to a connected device:

```sh
./gradlew installDebug
```

## CI

GitHub Actions builds `app:assembleDebug` on pushes and pull requests and uploads `app-debug.apk` as a workflow artifact.

The separate, manually dispatched release workflow builds `app:assembleRelease`, signs the APK from CI secrets, and publishes a GitHub Release with the signed APK attached.

## CI Verification

Our GitHub Actions release builds since `v0.5.0` are attested with build provenance. You can verify that a given APK was built by our release workflow - and not modified since - with:
```sh
gh attestation verify <filename.apk> --repo reddxae/MaterialXray
```
*Note: Releases before v0.5.0 are not attested and will not verify.*

## Runtime Notes

Material Xray downloads `geoip.dat` and `geosite.dat` into the runtime directory when needed, writes an Xray config, starts the correct Xray build for the selected service mode, and then either applies Android policy routing in root service mode or passes the Android `VpnService` TUN fd to Xray in rootless mode.

Xray's own outbound traffic is marked and bound to the detected physical interface so it does not get captured by the TUN route. When Android switches between Wi-Fi and cellular, the service debounces network callbacks, checks the live physical route, and reconnects Xray if the outbound interface changed.

The default TUN interface name is configurable in the app settings. Existing local test devices may have it saved as `wlan2`; the default setting is `xray0`.
The routing data download URLs are configurable in settings as direct file URLs and default to Loyalsoldier's `v2ray-rules-dat` release downloads.

## Project Layout

```text
app/src/main/kotlin/com/material/xray/
  core/root/      Root shell execution
  core/xray/      Xray binary resolution, config, TUN and routing
  data/           Room database, repositories, subscription parsing
  model/          Server and connection state models
  service/        Foreground Xray service, logs, boot receiver
  ui/             Compose screens and navigation
```

## Updating Xray Assets

The repository includes a helper script for downloading Xray releases:

```sh
./scripts/download-xray.sh
```

The script downloads two arm64 builds:

- `Xray-linux-arm64-v8a.zip` -> `app/src/main/assets/xray_arm64` for root service mode.
- `Xray-android-arm64-v8a.zip` -> `app/src/main/jniLibs/arm64-v8a/libxray.so` for rootless `VpnService` mode.

The Android binary is stored under `jniLibs` so Android extracts it into the executable native library directory at install time.

The downloader verifies each release archive against Xray's published SHA-256 digest, preserves Xray's license, and records the archive and executable hashes under `third_party/xray/`. Release builds also publish a corresponding-source archive containing Xray-core and its vendored Go modules.

## Status

This is an early implementation. Expect device-specific behavior around Android policy routing, root shell behavior, `VpnService`, and network namespace handling.

## License

Copyright (C) 2026 Material Xray contributors.

Material Xray's original repository material, including its source code, documentation, and artwork, is free software licensed under the [GNU General Public License, version 3 or later](LICENSE). You may redistribute and modify it under those terms. Material Xray is distributed without any warranty; see the license for details.

Third-party components and files derived from other projects remain under their respective licenses. See [Third-Party Notices](THIRD_PARTY_NOTICES.md) for attribution, license details, and corresponding-source information.
