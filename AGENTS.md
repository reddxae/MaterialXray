# AGENTS.md

## Commands
- Run Gradle from the repo root with the wrapper: `./gradlew :app:assembleDebug`.
- Install the prek-managed Git hook with `prek install`; run all hook checks explicitly with `prek run --all-files`.
- Unit tests: `./gradlew :app:testDebugUnitTest`.
- Single test class: `./gradlew :app:testDebugUnitTest --tests com.material.xray.core.xray.ConfigGeneratorTest`.
- Single Kotlin backtick-named test: `./gradlew :app:testDebugUnitTest --tests "com.material.xray.core.xray.ConfigGeneratorTest.generates TUN inbound with correct name and MTU"`.
- Lint and broader local verification: `./gradlew :app:lintDebug` and `./gradlew :app:check`.
- Device-only flows need a connected device/emulator: `./gradlew :app:installDebug` and `./gradlew :app:connectedDebugAndroidTest`.
- Release signing is read from env vars, Gradle properties, then `local.properties`: `RELEASE_KEYSTORE_PATH`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`, `RELEASE_STORE_PASSWORD`.

## Project Shape
- This is a single-module Android app; `settings.gradle.kts` includes only `:app` and the namespace/application id is `com.material.xray`.
- App startup is `MaterialXrayApp` for Hilt and scheduled subscription refresh, then `MainActivity` -> `MaterialXrayTheme` -> `MainNavigation` for Compose tabs.
- The runtime service is `service/XrayService.kt`, a `VpnService` that handles both root-managed service mode and rootless Android `VpnService` mode.
- Main boundaries: `core/xray` builds Xray config/TUN/routing, `core/root` wraps root shell execution, `data` holds Room/repositories/subscription parsing, and `ui` holds Compose screens.

## Native Assets
- Only arm64 is wired: `abiFilters += "arm64-v8a"`, `app/src/main/assets/xray_arm64` for root service mode, and `app/src/main/jniLibs/arm64-v8a/libxray.so` for rootless mode.
- Update bundled Xray binaries with `./scripts/download-xray.sh v26.3.27` or another Xray tag; the script writes both arm64 destinations and fixes permissions.
- Rootless mode starts Xray through `app/src/main/cpp/xray_launcher.c` via CMake and `System.loadLibrary("xray_launcher")`; native changes need an Android build, not only JVM tests.

## Data And Generated Code
- Room schema version is in `AppDatabase`; when changing entities, add a migration there and register it in `DatabaseModule` alongside the existing `MIGRATION_1_2` through `MIGRATION_7_8`.
- Hilt and Room use KSP from `app/build.gradle.kts`; prefer Gradle tasks for verification so generated code is produced.
- `local.properties`, Gradle outputs, `.cxx`, and most local IDE state are gitignored; do not depend on local-only values except SDK path or local signing credentials.

## CI
- `.github/workflows/ci.yml` only builds and uploads the debug APK on pushes and pull requests.
- `.github/workflows/release.yml` manually builds, signs, uploads, and publishes a release APK.

## QA
- Do not call a repository change truly 'done' until `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug` succeeds. These build-dependent checks intentionally run locally rather than in CI or prek.
- After the Gradle QA command, run `prek run --all-files` so ktlint and detekt validate the final worktree.
- If a required check cannot run, report that explicitly.

## Agent Workflow
- After implementing a change, you should check whether a device is attached over ADB. If it is, you should install the *release build* of the app on the device to let the User verify the change.
- If a `review` subagent or similar is available to you and the change you made is significant/big, run it with the instruction to review your diff. If unsure about the significance - ask the User whether you should run the review.
- When asked to commit your changes, follow conventional commits and try to keep your commits atomic - one commit is one 'thing done'.
