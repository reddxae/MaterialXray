# Third-Party Notices

Copyright (C) 2026 Material Xray contributors.

Material Xray's original repository material, including its source code, documentation, and artwork, is licensed under the GNU General Public License, version 3 or later. Material Xray is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. The complete license is in `LICENSE`.

The notices below identify separately licensed material distributed with, compiled into, or downloaded by the application.

## Corresponding Source

The preferred source form for each Material Xray release is available from:

- https://github.com/reddxae/MaterialXray
- https://github.com/reddxae/MaterialXray/releases

The release workflow publishes Material Xray's source archive and an `Xray-core-<version>-source.tar.gz` archive containing the corresponding Xray-core source and vendored Go modules used by the distributed executables.

## Xray-core

- Component: Xray-core, Linux and Android arm64 executables; `third_party/xray/VERSION` identifies the version
- Project: https://github.com/XTLS/Xray-core
- Source tags: https://github.com/XTLS/Xray-core/tags
- License: Mozilla Public License 2.0; see `third_party/xray/LICENSE`

Material Xray distributes unmodified official Xray-core release executables as separate child processes. Xray-core is licensed under MPL-2.0; licenses for its transitive Go modules are preserved in the corresponding-source archive.

The corresponding-source archive contains the vendored Go modules and preserves the original license files shipped with those modules.

The protocol definitions under `app/src/main/proto/` are reduced, Java-targeted versions of Xray-core protocol definitions and remain governed by MPL-2.0.

## Apache-2.0 Components

The following component families are licensed under the Apache License 2.0. Their direct dependency declarations are recorded in `gradle/libs.versions.toml`, and Gradle resolves their transitive dependencies for each build. The complete license is in `third_party/licenses/Apache-2.0.txt`.

- AndroidX libraries, including Activity, AppCompat, Compose, Core, DataStore, Hilt integrations, Lifecycle, Navigation, Room, SQLite, WorkManager, and their transitive AndroidX modules; Copyright The Android Open Source Project.
- Jetpack Compose Material, Material 3, and Material icon libraries; Copyright The Android Open Source Project.
- Material Symbols vector artwork under `app/src/main/res/drawable/`; Copyright Google LLC.
- Kotlin standard library, kotlinx.coroutines, and kotlinx.serialization; Copyright JetBrains and Kotlin contributors.
- Dagger and Hilt; Copyright Google LLC.
- OkHttp and Okio; Copyright Square, Inc. and contributors.
- gRPC-Java and PerfMark; Copyright The gRPC Authors and Google LLC.
- ZXing core; Copyright ZXing authors.
- Guava, Gson, Error Prone annotations, J2ObjC annotations, and Google Android annotations; Copyright Google LLC and contributors.
- JSpecify; Copyright the JSpecify Authors.
- `javax.inject` and Jakarta Inject APIs; Copyright their respective contributors.
- JSR-305 annotations; Copyright the JSR-305 authors.
- JetBrains Java annotations; Copyright JetBrains s.r.o.
- Gradle Wrapper; Copyright Gradle, Inc.

## BSD-3-Clause Components

Protocol Buffers Java Lite and generated support code are licensed under the BSD 3-Clause license. Copyright 2008 Google Inc. The complete notice is in `third_party/licenses/BSD-3-Clause.txt`.

## MIT Components

Animal Sniffer annotations use the MIT License. Copyright (c) 2009 codehaus.org. The complete notice is in `third_party/licenses/MIT.txt`.

## Runtime-Downloaded Routing Data

These datasets are not included in the Material Xray source tree or APK. The application downloads them only when requested or required at runtime.

- `v2fly/geoip`: "GeoIP files for V2Ray", V2Fly Community, licensed under CC-BY-SA-4.0. Source: https://github.com/v2fly/geoip. License: https://creativecommons.org/licenses/by-sa/4.0/
- `v2fly/domain-list-community`: V2Fly Community, licensed under the MIT License. Source: https://github.com/v2fly/domain-list-community

Material Xray does not modify these downloaded datasets.

## No Endorsement

Project and product names are used only to identify upstream components. Their inclusion does not imply sponsorship or endorsement of Material Xray by the respective copyright holders.
