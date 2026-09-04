<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="app/src/main/res/drawable-xxxhdpi/ic_launcher_material_foreground.png">
    <img src="app/src/main/res/drawable-xxxhdpi/ic_launcher_default_foreground.png" alt="Material Xray logo" width="144" height="144">
  </picture>
</p>

<h1 align="center">Material Xray</h1>

<p align="center">An Android proxy client powered by Xray-core, with a Material Design 3 interface.</p>

<p align="center">
  <a href="https://github.com/AetherMagee/MaterialXray/releases/latest">Download APK</a> &middot;
  <a href="https://github.com/AetherMagee/MaterialXray/releases">Release notes</a> &middot;
  <a href="https://github.com/AetherMagee/MaterialXray/issues">Report an issue</a>
</p>

## Get started

You'll need Android 9 or newer on an arm64 device, plus a proxy server or subscription of your own. Material Xray is a client, not a service that provides servers.

1. Download the APK from the [latest release](https://github.com/AetherMagee/MaterialXray/releases/latest) and install it. Android may ask you to allow installation from your browser or file manager.
2. Open the app and choose **Add new server or subscription**. Paste a link, scan a QR code, or enter it manually.
3. Select a server and tap **Start**. 

## What you can do

- Keep servers grouped by subscription and test their latency before connecting.
- Import VLESS, VMess, Trojan, Shadowsocks, and Hysteria2 links. HTTP, SOCKS, WireGuard, and raw Xray JSON configurations are also supported.
- Choose which apps use the proxy, bypass it, or connect through a specific server.
- Add custom routing rules or apply routing supplied by your subscription provider.
- See live upload, download, session traffic, and ping on the home screen.
- Configure DNS, IPv6, and local-network bypass.

Advanced options expose live app and Xray logs, a configuration viewer and editor, and additional connection settings.

## Root or rootless?

| Category | Rootless | Rootful |
| --- | --- | --- |
| Detection points | ⚠️ Establishes an Android VPN, which apps can detect through the system's network APIs. | ✅ Configures routing tables to make the tunnel hidden from the apps that bypass it. |
| Ease of setup | ✅ Approve Android's VPN permission, just like any other VPN app. | ⚠️ Requires superuser access through `su`. KernelSU is preferred. |
| Android VPN state | ⚠️ Occupies Android's VPN slot, easily detected by other apps. | ✅ Uses root-managed routing instead, allowing it to hide itself and even coexist with other VPNs like Tailscale. |
| Per-app control | ✅ Choose which apps use the proxy and which bypass it. | ✅ Choose which apps use the proxy and which bypass it + assign different proxy servers to individual apps. |
| Hotspot and tethering | ⚠️ Does not tunnel tethered clients. | ✅ Can tunnel tethered clients through the proxy. |
| Always-on VPN | ✅ Supports Android's always-on VPN. | ⚠️ Enabling Android's always-on VPN switches the app to rootless mode. |
| Auto-connect after reboot | ✅ Supported | ✅ Supported |
| If Android kills the app process | ⚠️ The proxy process stops too. Always-on VPN can restart the service. | ✅ The proxy process can keep running independently of the app. |
| Stability | ✅ Traffic is routed by Android, standard and battle-tested. | ⚠️ Rigorously tested but may have rough edges. |

**TL;DR**: Use rootful mode when avoiding VPN detection by other apps is the priority. Use rootless when root is unavailable or you prefer Android's standard VPN integration.

Rootful does not mean undetectable. Apps may use other signals, such as root detection, bad routing policies and weird networking edge-cases. It does not hide another VPN you run alongside it, and coexistence still depends on routing compatibility. If the app falls back to rootless mode, or Android's always-on VPN forces rootless mode, the connection becomes an Android VPN again.

The app is still under active development. Device-specific behavior is possible, especially with root routing and network changes. If something goes wrong, [open an issue](https://github.com/AetherMagee/MaterialXray/issues) with your Android version, device model, service mode, and steps to reproduce it. Remove credentials, subscription URLs, and other private information from any logs or configurations you share.

## Development

This project is AI-assisted.

Material Xray is a single-module Kotlin Android app using Jetpack Compose, Hilt, Room, DataStore, and WorkManager. Xray-core handles proxy connections; the app manages subscriptions, configuration, routing, and the service lifecycle.

### Build and install

Use JDK 21 and the Android SDK. The current build uses Android platform 37.0 and CMake 3.31.6, matching [CI](.github/workflows/ci.yml). Set your SDK path through `ANDROID_HOME` or `sdk.dir` in `local.properties`.

Run from the repository root:

```sh
./gradlew :app:assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. To install it on a connected arm64 device or emulator:

```sh
./gradlew :app:installDebug
```

### Checks

Install the Git hook with [prek](https://prek.j178.dev/):

```sh
prek install
```

Run tests and assemble the app, then lint and static analysis:

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
./gradlew :app:lintDebug
prek run --all-files
```

Formatting is checked by the hook, not applied by a build. Use `./gradlew :app:ktlintFormat` to fix Kotlin formatting. Device tests require a connected device or emulator and run with `./gradlew :app:connectedDebugAndroidTest`.

### Signed releases

To build a signed release locally, provide your own keystore:

```sh
RELEASE_KEYSTORE_PATH=/path/to/release.keystore \
RELEASE_KEY_ALIAS=your_alias \
RELEASE_KEY_PASSWORD=your_key_password \
RELEASE_STORE_PASSWORD=your_store_password \
./gradlew :app:assembleRelease
```

[CI](.github/workflows/ci.yml) builds a debug APK on pushes and pull requests. The manually triggered [release workflow](.github/workflows/release.yml) signs and publishes a release APK, an Xray corresponding-source archive, and build-provenance attestations.

To verify a release artifact with the GitHub CLI:

```sh
gh attestation verify <filename.apk> --repo AetherMagee/MaterialXray
```

Releases before `v0.5.0` do not have attestations.

### Runtime and native assets

Only `arm64-v8a` is currently packaged. Root mode uses `app/src/main/assets/xray_arm64`. Rootless mode uses `app/src/main/jniLibs/arm64-v8a/libxray.so`, launched through the JNI shim in `app/src/main/cpp/xray_launcher.c`.

The service downloads `geoip.dat` and `geosite.dat` when needed, generates an Xray configuration, and starts the appropriate binary. Routing data defaults to `v2fly/geoip` and `v2fly/domain-list-community` releases; the download URLs are configurable in Settings.

In rootful TUN mode, the service manages the tunnel interface and routing. Rootful mode binds outbound connections to the physical network interface to avoid routing loops, watches Wi-Fi and cellular changes, and retargets the connection when needed. Rootless mode passes Android's VPN TUN file descriptor to Xray and excludes Material Xray itself from the VPN to prevent routing loops, relying on Android's network routing rather than the rootful retargeting logic.

Update the bundled Xray binaries with:

```sh
./scripts/download-xray.sh
```

The script uses the version recorded in `third_party/xray/VERSION`, or accepts an Xray release tag as an argument. It downloads both arm64 builds, verifies the published SHA-256 digests, preserves Xray's license, and records hashes under `third_party/xray/`.

### Project layout

```text
app/src/main/kotlin/com/material/xray/
  core/root/      Root shell execution
  core/xray/      Xray binaries, configuration, TUN, and routing
  data/           Database, repositories, and subscription parsing
  model/          Server and connection state models
  service/        Connection service, logs, and boot receiver
  ui/             Compose screens and navigation
```

## License

Copyright (C) 2026 Material Xray contributors.

Material Xray's original source code, documentation, and artwork are licensed under the [GNU General Public License, version 3 or later](LICENSE), without any warranty.

Third-party components and derived files retain their respective licenses. See [Third-Party Notices](THIRD_PARTY_NOTICES.md) for attribution, license details, and corresponding-source information.
