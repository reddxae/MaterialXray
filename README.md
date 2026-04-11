# MaterialXray

MaterialXray is an Android proxy client for rooted devices. It runs xray-core with a native TUN inbound, manages the root-side process lifecycle, and routes device traffic through the selected outbound server using Linux policy routing.

The current app mode is native/root mode. A rootless `VpnService` mode is planned, but is not implemented yet.

## Features

- Material Design 3 Jetpack Compose UI.
- Subscription import and refresh for common share-link formats.
- Server cards grouped by subscription.
- Per-server latency testing.
- Per-app bypass list.
- Foreground Xray service with live app and Xray logs.
- Root-managed Xray TUN interface.
- Policy routing for captured app traffic.
- Xray outbound binding to the active physical interface.
- Network-change retargeting for Wi-Fi and cellular switches.
- Pre-start hostname resolution with randomized A/AAAA selection.

## Requirements

- Android 12 or newer.
- Root access with `su`.
- Android SDK and a JDK compatible with the Gradle wrapper.
- A connected Android device or emulator for install/debug flows.

The app currently bundles:

- `app/src/main/assets/xray_arm64`
- `app/src/main/assets/geoip.dat`
- `app/src/main/assets/geosite.dat`

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

Install the debug build to a connected device:

```sh
./gradlew installDebug
```

## CI

GitHub Actions builds `app:assembleDebug` on every pushed commit and uploads `app-debug.apk` as a workflow artifact.

When the pushed ref is a tag that starts with `v` such as `v1.3.2`, the workflow switches to `app:assembleRelease`, signs the APK from CI secrets, and publishes a GitHub Release with the signed APK attached.

## Runtime Notes

MaterialXray starts a root shell, extracts Xray and geo data into the app files directory, writes an Xray config, starts the Xray process, waits for the TUN interface, and then applies Android policy routing.

Xray's own outbound traffic is marked and bound to the detected physical interface so it does not get captured by the TUN route. When Android switches between Wi-Fi and cellular, the service debounces network callbacks, checks the live physical route, and reconnects Xray if the outbound interface changed.

The default TUN interface name is configurable in the app settings. Existing local test devices may have it saved as `wlan2`; the default setting is `xray0`.

## Project Layout

```text
app/src/main/kotlin/com/materialxray/
  core/root/      Root shell execution
  core/xray/      Xray asset extraction, config, TUN and routing
  data/           Room database, repositories, subscription parsing
  model/          Server and connection state models
  service/        Foreground Xray service, logs, boot receiver
  ui/             Compose screens and navigation
```

## Updating Xray Assets

The repository includes a helper script for downloading Xray releases:

```sh
./scripts/download-xray.sh v26.3.27
```

That script currently targets the older `jniLibs` layout, while the app uses `app/src/main/assets`. If you use it, copy or adapt the downloaded binary into the assets layout expected by `XrayBinary`.

## Status

This is an early root-only implementation. Expect device-specific behavior around Android policy routing, root shell behavior, and network namespace handling.
