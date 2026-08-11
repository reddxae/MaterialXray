# Rootful TPROXY Backend Specification

## Status

Implementation specification for adding an optional TPROXY connection backend to rootful mode.

MaterialXray currently uses Xray's TUN inbound in both service modes:

- Rootless mode obtains a TUN file descriptor from Android `VpnService`.
- Rootful mode lets the Linux Xray binary create TUN interfaces and installs root policy routing around them.

This change only gives rootful mode a backend choice. Rootless mode remains unchanged and inherently uses Android `VpnService` plus TUN.

The bundled Xray version is `v26.7.28`, commit `5ca6f4b7d4dc20a881d4330e498892697627ec0c`. That version natively supports transparent TCP/UDP proxying through the `tunnel`/`dokodemo-door` inbound, `followRedirect`, and Linux `IP_TRANSPARENT` socket options. No Xray fork is required.

## Goals

- Let users select `TUN` or `TPROXY` while root service mode is enabled.
- Preserve current rootless behavior without exposing an irrelevant backend setting.
- Preserve every current app route mode in TPROXY mode, including bypass, direct, default outbound, default selected server, and specific server assignments.
- Keep bypassed applications entirely outside Xray and outside MaterialXray packet marks.
- Remove rootful TUN interfaces and per-UID netlink policy rules when TPROXY is selected.
- Detect actual kernel and userspace TPROXY compatibility before allowing selection.
- Fail closed during startup, updates, crashes, and backend transitions.
- Make all installed state recoverable and removable after process death or unreadable persisted state.

## Non-Goals

- TPROXY in rootless mode.
- Replacing Android `VpnService` or renaming rootless runtime concepts.
- Supporting TPROXY through a custom Xray build.
- Hiding state from an application with root or equivalent privileged access.
- Silently degrading or ignoring app-specific server assignments.
- Implementing a TCP-only `REDIRECT` fallback. Unsupported TPROXY falls back to TUN only through an explicit user choice; an automatic mode can be considered later.

## Product Decisions

### Service mode remains a boolean

`useRootService` continues to select between:

- Rootless Android `VpnService` mode when false.
- Root service mode when true.

Do not introduce a three-way service enum such as `VpnServiceTun`, `RootTun`, and `RootTproxy` in the user-facing or persisted settings model.

### Root backend is subordinate to root mode

Add:

```kotlin
enum class RootConnectionBackend(val persistedValue: String) {
    Tun("tun"),
    Tproxy("tproxy"),
}
```

Add `rootConnectionBackend` to `XrayRuntimeSettings`. It is only consulted when `useRootService == true`. Rootless code must ignore it.

The missing/invalid preference default is `Tproxy`. Explicitly persisted TUN selections remain unchanged.

Use the name `RootConnectionBackend`; do not use `routingStrategy`, which would conflict with Xray domain-routing terminology already present in the project.

## User Experience

### Settings layout

Keep the existing `Use root service` switch.

When that switch is enabled, reveal a dropdown field:

```text
Core connection backend
```

Options:

```text
TUN interface
Compatible and proven. Creates virtual network interfaces and routes app traffic through Xray's userspace network stack.

Transparent proxy (TPROXY)
More discreet and usually more efficient. Intercepts TCP and UDP without creating a VPN interface. Requires kernel support.
```

The field is hidden when root service mode is disabled. It must not imply that rootless mode offers a backend choice.

### Compatibility states

The TPROXY option has these UI states:

| State | UI behavior |
| --- | --- |
| Not checked | TPROXY optimistically enabled with no helper text |
| Checking | TPROXY remains enabled with no helper text |
| Supported | TPROXY enabled |
| Supported without socket optimization | TPROXY enabled; no warning required outside details/logs |
| IPv4 only, app IPv6 disabled | TPROXY enabled |
| IPv4 only, app IPv6 enabled | TPROXY remains enabled; disable the app IPv6 setting while root TPROXY is selected |
| Unsupported | TPROXY disabled with a short reason |
| Root unavailable | Backend field follows existing unavailable root-service UI |

Do not request root merely because the application started. Run the compatibility check after root mode is enabled or when the already-root-enabled settings section is opened. `SettingsViewModel` already checks root availability when the settings screen is created; the TPROXY check can follow a successful root check.

Provide a retry action when detection fails for a transient reason.

### Existing TPROXY preference becoming unsupported

Do not silently rewrite the preference. This can happen after restoring a backup onto another device or after a kernel update.

- Display TPROXY as the stored selection with an unsupported warning.
- Block a new root connection with a precise error until the user selects TUN.
- Never silently start a different backend for an explicitly stored selection.

An `Automatic` option may be added later. Only that option may silently fall back.

### Conditional settings

- Show `TUN Interface Name` only when root mode and the TUN backend are both selected.
- Keep `TUN MTU` available because it still applies to rootless mode. Clarify that it applies to Android VPN and rootful TUN, not rootful TPROXY.
- Treat a backend change as a full connection reload, never as a fast app-routing update.

## Settings And Backup

Add a DataStore string key:

```text
root_connection_backend
```

Update `SettingsRepository` with:

- Preference key.
- `Flow<RootConnectionBackend>`.
- Setter.
- Inclusion in `runtimeSettingsSnapshot()`.
- Explicit parsing in `restoreFromMap()`.

Backup export already serializes all DataStore preferences into the settings map. No backup schema-version increase is required, but restore parsing is required.

No Room entity, database migration, or Room schema change is needed.

## Architecture

### Preserve process strategies

`RootXrayRuntimeStrategy` and `VpnServiceXrayRuntimeStrategy` currently answer who launches and owns the Xray process. Keep that responsibility.

Do not create a VpnService backend enum. Add root traffic interception behind the existing root runtime instead.

Introduce a root-only abstraction along these lines:

```kotlin
internal interface RootConnectionBackendController {
    val backend: RootConnectionBackend

    suspend fun prepare(plan: RootTrafficPlan): BackendPrepareResult
    suspend fun activate(plan: RootTrafficPlan): RoutingResult
    suspend fun updateAppRouting(plan: RootTrafficPlan): RoutingResult
    suspend fun verify(state: XrayState): BackendHealth
    suspend fun cleanup(state: XrayState?): Boolean
}
```

Implementations:

- `RootTunBackendController`, delegating current `TunManager` behavior.
- `RootTproxyBackendController`, delegating a new `TproxyManager`.

The exact interface can remain smaller if call sites permit, but backend-specific branches must not spread throughout `XrayService` and `ConnectionManager`.

### Effective behavior resolution

At connection time:

```kotlin
if (!runtimeSettings.useRootService) {
    // Existing VpnService path. Ignore rootConnectionBackend.
} else {
    when (runtimeSettings.rootConnectionBackend) {
        RootConnectionBackend.Tun -> rootTunBackend
        RootConnectionBackend.Tproxy -> rootTproxyBackend
    }
}
```

Always-on Android VPN still forces rootless mode exactly as it does now. The stored root backend does not alter that behavior.

### Transport-neutral app routing plan

Current `AppRoutingPlan` contains TUN names and route tables. Refactor the planner to describe intent before assigning backend resources.

Suggested shape:

```kotlin
internal data class AppTrafficGroup(
    val routeKey: Long,
    val uids: Set<Int>,
    val inboundTag: String,
    val outboundTag: String,
    val server: ServerConfig?,
    val applyRoutingRules: Boolean,
)

internal data class AppRoutingPlan(
    val directUids: Set<Int>,
    val defaultOutboundUids: Set<Int>,
    val selectedServerUids: Set<Int>,
    val trafficGroups: List<AppTrafficGroup>,
    val proxyServerIds: List<Long>,
    val routeProfileIds: Set<Int>,
)
```

The final shape may combine default groups with `trafficGroups`, but it must represent these distinct semantics:

- `Bypass` and `Direct`: do not intercept at all.
- `DefaultOutbound`: intercept through the base inbound and allow normal Xray routing/default-outbound behavior.
- `DefaultSelected`: intercept through an inbound whose final routing rule forces the selected `proxy` outbound after user routing rules.
- `Server`: intercept through an inbound whose early routing rule forces that server-specific outbound.

Backend mapping:

- TUN maps each required group to a TUN name and route table as today.
- TPROXY maps each required group to a packet mark and transparent inbound port.

Do not ship TPROXY as complete while silently mapping unsupported app modes to another route.

## Xray Configuration

### TUN

Preserve the existing TUN inbound behavior.

### TPROXY inbound

Generate one transparent inbound per traffic group. Xray `v26.7.28` supports both protocol names; use the current `tunnel` alias in generated configurations.

Representative inbound:

```json
{
  "tag": "tproxy-in-default",
  "listen": "0.0.0.0",
  "port": 46321,
  "protocol": "tunnel",
  "settings": {
    "allowedNetwork": "tcp,udp",
    "followRedirect": true
  },
  "sniffing": {
    "enabled": true,
    "routeOnly": true,
    "destOverride": ["http", "tls", "quic"]
  },
  "streamSettings": {
    "sockopt": {
      "tproxy": "tproxy",
      "mark": 255
    }
  }
}
```

Validate the final listen-address behavior for dual-stack operation during the prototype. Prefer one TCP/UDP port per group usable by both families. If Xray or an OEM kernel requires separate IPv4 and IPv6 listeners, persist both resources explicitly.

### Listener allocation

- Allocate unpredictable high ports at each new root TPROXY runtime.
- Verify that each selected port is free for both TCP and UDP before writing the config.
- Persist the group-to-port mapping before activating interception.
- Treat an Xray listener bind failure as a connection failure; never activate packet interception first.
- Protect direct loopback access to these ports with owner/firewall rules. Ordinary applications must not be able to connect to or use the listeners.

Port occupancy remains a weaker detection surface than global TUN links and UID policy rules. Random allocation and access firewalling reduce but do not eliminate bind-probe detection.

### Routing rules

Generalize hardcoded `tun-in` DNS and routing rules to accept the generated set of data inbound tags.

Preserve rule ordering:

1. DNS interception rules.
2. Default Xray DNS routing.
3. Server-specific forced inbound rules that currently use `applyRoutingRules == false`.
4. LAN and user routing rules.
5. Default-selected forced inbound rules that currently use `applyRoutingRules == true`.

### Raw configurations

Replace `RawConfigTunInjector` with a transport-aware injector or generalize it. Raw configs must receive TUN inbounds in TUN modes and transparent inbounds in rootful TPROXY mode while preserving current outbound normalization, DNS ownership, API setup, and routing merge order.

## TPROXY Packet Routing

### Required kernel path

For locally generated app traffic:

```text
app socket
  -> mangle OUTPUT app/group selection
  -> interception mark
  -> one masked fwmark policy rule
  -> local route through lo
  -> mangle PREROUTING
  -> TPROXY target chooses the group's Xray port
  -> Xray transparent inbound recovers original destination
```

Xray outbound sockets retain mark `255` and physical-interface binding. They must never be selected by MaterialXray's interception chain.

### Mark namespace

Do not reuse Android's low 16-bit netId as an interception discriminator. Android's fwmark layout also uses explicit-selection, VPN-protection, permission, billing, vendor, and wakeup fields.

Reserve a MaterialXray mark prefix and group bits, for example:

```text
prefix: 0x0a000000
prefix mask: 0x0f000000
group: low 8 bits
```

This is an implementation starting point, not an ABI guarantee. Before activation:

- Inspect existing policy rules for an overlapping mask/value.
- Reject TPROXY compatibility if the namespace conflicts.
- Overwrite the full skb mark only for packets selected for interception. This is intentional after the application/network decision has been made.
- Leave bypassed packets and their Android fwmarks untouched.
- Persist the chosen prefix, mask, route table, and priority in runtime state.

All group marks share one policy rule:

```text
ip rule add fwmark <prefix>/<prefix-mask> lookup <local-table> pref <priority>
```

The local table contains:

```text
local 0.0.0.0/0 dev lo
local ::/0 dev lo
```

when the corresponding address family is enabled and supported.

### UID chain ordering

The OUTPUT selection chain must apply in this order:

1. Return Xray/exempt outbound mark `255`.
2. Return MaterialXray's own application UID.
3. Return loopback destinations.
4. Return multicast and broadcast destinations that must remain local-network traffic.
5. Return every `Bypass` or `Direct` UID.
6. Assign server-specific group marks.
7. Assign explicit `DefaultOutbound` group marks.
8. Assign `DefaultSelected` marks to remaining application UID ranges in each managed profile.

Use ordered exceptions followed by whole profile application ranges instead of generating fragmented include ranges. This keeps the netfilter rule count bounded while moving UID details out of unprivileged rtnetlink visibility.

### Protocol handling

- TCP and UDP are marked and sent to TPROXY.
- ICMP echo from intercepted UIDs must not be sent into Xray and must not leak directly. Drop echo requests for those UIDs by default.
- ICMP from bypassed UIDs remains untouched and uses the physical network normally.
- Other unsupported IP protocols from intercepted UIDs fail closed rather than bypassing unexpectedly.
- Necessary kernel-generated ICMP/ICMPv6 control traffic must not be globally blocked. Restrict app protocol drops to selected OUTPUT UIDs and validate IPv6 PMTU behavior.

Do not add artificial ICMP latency. Xray TUN's local echo implementation answers nonexistent destinations and is intrinsically detectable.

### Optional DIVERT optimization

If `-m socket` is supported, install the conventional DIVERT chain for established transparent TCP sockets. Treat this as an optimization, not a compatibility requirement.

## Atomicity And Fail-Closed Behavior

`TproxyManager` must own all netfilter and policy-routing mutations.

Use versioned/two-slot chains, analogous to `XrayApiFirewall`:

- Build a complete inactive replacement chain.
- Validate all commands.
- Swap a single jump to activate the replacement.
- Remove the previous chain only after activation succeeds.

Startup order:

1. Clean stale owned state.
2. Build app routing and resource plan.
3. Install a temporary fail-closed guard for UIDs that will be intercepted.
4. Write and validate Xray config.
5. Start Xray.
6. Verify process, API, and transparent listeners.
7. Install local routes, policy rule, PREROUTING chains, and inactive OUTPUT chain.
8. Atomically activate OUTPUT interception.
9. Remove the temporary guard.
10. Persist final active state.

Failure at any step must leave selected traffic blocked until the attempted backend state is removed. It must never fall through to the physical default route during a connection transition.

Disconnect order:

1. Install/update guard if a seamless reconnect is in progress.
2. Deactivate OUTPUT interception.
3. Stop Xray.
4. Remove PREROUTING chains, policy rules, local routes, listener firewall rules, and guards.
5. Delete state only after cleanup verifies success.

Normal user-requested disconnect may restore direct networking after cleanup. Reconnect/backend-switch paths remain guarded throughout transition.

## Compatibility Detector

### Result model

Suggested model:

```kotlin
sealed interface TproxyCompatibility {
    data object Unknown : TproxyCompatibility
    data object Checking : TproxyCompatibility
    data class Supported(
        val ipv6: Boolean,
        val socketMatchOptimization: Boolean,
    ) : TproxyCompatibility
    data class Unsupported(val reason: Reason, val details: String? = null) : TproxyCompatibility
}
```

Reasons should distinguish at least:

- Root unavailable.
- Init network namespace unavailable.
- iptables mangle unavailable.
- Owner match unavailable.
- MARK target unavailable.
- TPROXY IPv4 unavailable.
- Policy routing/local route unavailable.
- IPv6 TPROXY unavailable.
- Mark namespace conflict.
- Probe cleanup failed.
- Command timed out.

### Probe requirements

Do not rely solely on kernel config files, `/proc/net/ip_tables_targets`, or `iptables -j TPROXY -h`. Those can produce false negatives or prove userspace parsing without proving kernel support.

Run an inert transactional probe in Android's init network namespace:

1. Open root through the existing `RootShell` init namespace requirement.
2. Create a uniquely named, unhooked mangle chain.
3. Add an `owner --uid-owner` match to prove owner support.
4. Add a `MARK --set-xmark` rule to prove masked marking support.
5. Add TCP and UDP TPROXY rules to prove the target and revision are accepted.
6. Add a temporary local route table and masked fwmark policy rule.
7. use `ip route get <benchmark-address> mark <probe-mark>` and verify the local route/table.
8. Repeat target and route checks with `ip6tables`/`ip -6` when IPv6 is enabled.
9. Probe `-m socket` separately and record it as optional.
10. Remove every probe object in a shell trap.
11. Reinspect all probe names/rules/tables and report unsupported if cleanup was incomplete.

Prefer an isolated temporary network namespace if `unshare -n` works under the active root implementation. Fall back to inert, unhooked chains and unused rules in the init namespace. Never send an external compatibility probe.

### Connection-time verification

Cached compatibility is advisory. Re-run the lightweight mandatory probe before starting an explicit TPROXY connection if the previous result is from another boot/kernel/iptables backend or is otherwise stale.

Starting the real Xray listeners is the final `IP_TRANSPARENT` test. Activate no app interception until the real process has successfully bound every listener and its local API responds.

Cache keys should include:

- Boot ID.
- Kernel release.
- `iptables --version` and backend.
- Xray version.
- Whether IPv6 is requested.

An in-memory cache is sufficient initially.

## Persisted Runtime State

Extend `XrayState` with a backward-compatible backend discriminator:

```kotlin
val rootConnectionBackend: RootConnectionBackend = RootConnectionBackend.Tun
```

Old state files therefore remain root TUN states.

Persist TPROXY cleanup identity, either as nested state or explicit fields:

```kotlin
@Serializable
data class TproxyRuntimeState(
    val markPrefix: Int,
    val markMask: Int,
    val routeTable: Int,
    val rulePriority: Int,
    val outputChainSlot: String,
    val groups: List<TproxyGroupState>,
    val ipv6Enabled: Boolean,
)

@Serializable
data class TproxyGroupState(
    val routeKey: Long,
    val mark: Int,
    val port: Int,
    val inboundTag: String,
)
```

Write provisional state before activation so process death during setup remains recoverable. Update it atomically after activation.

## Cleanup And Ownership

Add a dedicated `TproxyManager`; do not repurpose the legacy cleanup-only `NftablesManager`.

Owned chain names must be deterministic from the application UID and a fixed prefix, with two slots for atomic replacement. Respect iptables chain-name length limits.

Cleanup must:

- Stop only MaterialXray-owned Xray processes using existing config-path ownership checks.
- Remove only exact MaterialXray chain names/jumps.
- Remove only persisted or reserved MaterialXray policy-rule priorities, marks, and route tables.
- Verify absence after removal.
- Defensively clean both root TUN and root TPROXY artifacts when persisted state is absent or unreadable, without touching unrelated VPN/proxy state.
- Preserve the state file when cleanup is incomplete.

The repository contains cleanup for an old `table inet xray`; that table is not the new implementation and remains defensive legacy cleanup.

## Restore, Health, And Network Changes

### Restore

Root TUN restore continues to require the persisted interface.

Root TPROXY restore requires:

- Owned Xray process alive.
- Xray API reachable.
- Active OUTPUT and PREROUTING jumps present.
- Expected policy rule present.
- Expected local route table present for enabled families.
- Listener firewall state present.

If state is partially present, clean and reconnect instead of reporting connected.

### Watchdog

Replace the assumption that every root runtime has `tunName` availability with a backend-specific health probe.

TPROXY health remains passive and checks only local process/API/routing state. It sends no external probe.

### Network changes

TPROXY does not need a physical bypass route table because the system default route remains physical. Xray outbound sockets are exempt from interception and remain bound to the selected physical interface.

- Reconnect when the physical interface changes so generated Xray sockopts bind the new interface.
- A gateway/address change on the same interface generally requires state refresh but no TPROXY route replacement.
- Preserve existing settle/retry behavior around Android network callbacks.

## Fast App-Routing Updates

When the set and order of traffic groups is unchanged, update UID membership by building and atomically swapping the inactive OUTPUT chain. No Xray restart is required.

When group topology changes, including adding/removing a server-specific route, ports/inbounds/outbounds change and a full Xray restart is required. This matches the current `proxyServerIds` topology check.

The Apps UI must continue to expose all root-only route modes for both root backends once feature parity is implemented.

## Diagnostics And Logging

Startup diagnostics should log:

- Service mode: root/rootless.
- Root backend when applicable.
- Compatibility result and optional capabilities.
- Allocated TPROXY group count, without logging package names unless current diagnostics already do so.
- Mark prefix/mask, route table, and policy priority.
- IPv4/IPv6 activation.

On setup failure, collect bounded output for:

- Owned mangle chains only.
- Relevant policy rule only.
- Relevant local route table only.
- Xray listener/process status.
- Root namespace identity.

Do not dump the device's complete firewall or unrelated application policy into normal logs.

Update notification text so root TPROXY does not claim a TUN is pinned. Suggested connected detail:

```text
Root transparent proxy active via <physical-interface>
```

## Security And Privacy Requirements

- Bypassed UID packets retain their original Android fwmark and physical route.
- MaterialXray and Xray control traffic cannot enter transparent interception.
- TPROXY listener ports reject direct app access.
- No listener is active without matching persisted provisional state.
- No interception chain is active without verified Xray listeners.
- No selected UID can leak directly during startup, backend switching, app-routing updates, or automatic recovery.
- Unsupported protocols from intercepted UIDs fail closed.
- Compatibility checks are local and do not contact external endpoints.
- Command construction shell-quotes all dynamic values and validates numeric ranges before root execution.

## Tests

### Unit tests

Add or update tests for:

- `RootConnectionBackend` parsing/defaulting.
- Settings snapshot, setter, backup export, and backup restore.
- Settings visibility and selection-state logic where practical.
- TUN config remains byte/structure compatible where expected.
- Transparent inbound generation for base, default-selected, and server groups.
- Raw config transparent-inbound injection.
- Routing rule ordering across all inbound types.
- App routing plan backend neutrality.
- UID exception/group ordering.
- Mark allocation and collision detection.
- IPv4 and IPv6 command generation.
- Inert compatibility probe parsing and cleanup verification.
- Two-slot chain activation/update/removal.
- Partial setup rollback at every stage.
- Persisted TPROXY state backward compatibility.
- Restore and watchdog decisions for missing process, listener, chain, rule, or route.
- Fast update versus full restart topology decisions.
- Cleanup with missing, valid, and unreadable state.

Likely existing test anchors include:

- `ConfigGeneratorTest`
- `RawConfigTunInjectorTest`
- `ConnectionManagerTest`
- `ActiveRoutingUpdaterTest`
- `TunManagerTest`
- `XrayServiceModeTest`
- `StartupDiagnosticsLoggerTest`
- `ConnectionDiagnosticsTest`
- Settings repository and backup tests

### Device test matrix

At minimum validate:

- Multiple Android releases and at least one non-GKI/older vendor kernel if available.
- Magisk, KernelSU, and APatch root where available.
- Wi-Fi, cellular, and network switching.
- IPv4-only, dual stack, and IPv6 disabled/enabled settings.
- TCP, UDP, QUIC, DNS, and Private DNS behavior.
- Bypass app public IP, DNS, routes, socket marks, ICMP, and network capabilities.
- Default outbound, default selected, direct, bypass, and multiple server-specific app groups.
- Shared UIDs and work profiles.
- Xray crash, app process death, reboot, failed cleanup, and backend switching.
- Doze and screen-off operation.
- Port conflicts and direct listener access attempts.
- The third-party VPN detector scan in both root backends.

Specifically verify standard Android resolver behavior. DNS packets may be emitted by a resolver process rather than the requesting app UID; TPROXY must not globally capture resolver traffic in a way that changes DNS for bypassed apps. Existing root TUN behavior should be measured as the baseline.

## Rollout Plan

### Phase 1: Prototype

- Generate one base transparent inbound.
- Manually install minimal IPv4 OUTPUT/route/PREROUTING state on a development device.
- Validate Xray TCP and UDP operation, original destination recovery, loop prevention, and cleanup.
- Validate IPv6 separately.
- Measure detector output, CPU, memory, throughput, TCP behavior, and DNS.

Do not expose the setting in release builds during this phase.

### Phase 2: Backend and lifecycle

- Add setting/model/UI behind an experimental build flag.
- Add compatibility detector.
- Add `TproxyManager`, persisted provisional/final state, atomic activation, cleanup, restore, watchdog, diagnostics, and network retarget behavior.
- Support base/default route semantics and fail-closed protocol handling.

### Phase 3: Split-routing parity

- Refactor app routing into transport-neutral traffic groups.
- Add default-selected and server-specific transparent inbounds.
- Add fast UID-chain updates.
- Complete all route-mode tests.

Do not call TPROXY complete or remove its experimental label before this phase passes.

### Phase 4: Device qualification

- Run the device matrix.
- Compare TUN and TPROXY scans.
- Resolve OEM-specific failures or classify them through compatibility reasons.
- Remove the experimental label after crash recovery, split routing, IPv6, and bypass invisibility are demonstrated.

### Future phase: Automatic backend

After forced TPROXY is stable, consider:

```text
Automatic (prefer TPROXY, fall back to TUN)
```

Automatic fallback must happen only before interception activates or after complete guarded rollback.

## Acceptance Criteria

- Rootless behavior and UI are unchanged.
- Enabling root mode reveals `Core connection backend`.
- Missing backend preferences default to TPROXY; explicit selections are preserved.
- Unsupported TPROXY is disabled with a useful reason.
- TPROXY mode creates no Xray TUN interfaces.
- TPROXY mode does not install per-UID `ip rule uidrange` entries.
- All app route modes behave identically at the product level across TUN and TPROXY.
- Bypassed apps use normal physical IPv4, IPv6, DNS, TCP, UDP, and ICMP behavior.
- Proxied apps do not receive Xray TUN's synthetic ICMP replies.
- Xray outbound traffic cannot loop into TPROXY.
- Listener ports cannot be used directly by ordinary apps.
- Startup, reload, app-routing update, backend switch, crash, reboot, and disconnect leave no unintended direct-leak window.
- Persisted or stale MaterialXray TPROXY state is safely restored or removed without touching unrelated firewall/routing state.
- `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug` passes.
- `prek run --all-files` passes.

## Principal Files Expected To Change

- `app/src/main/kotlin/com/material/xray/model/XrayRuntimeSettings.kt`
- New `RootConnectionBackend` model file
- `app/src/main/kotlin/com/material/xray/data/repository/SettingsRepository.kt`
- `app/src/main/kotlin/com/material/xray/ui/settings/SettingsViewModel.kt`
- `app/src/main/kotlin/com/material/xray/ui/settings/SettingsScreen.kt`
- Settings strings in English and Russian
- `app/src/main/kotlin/com/material/xray/service/ConnectionManager.kt`
- `app/src/main/kotlin/com/material/xray/service/AppRoutingPlanner.kt`
- `app/src/main/kotlin/com/material/xray/service/ActiveRoutingUpdater.kt`
- `app/src/main/kotlin/com/material/xray/service/XrayService.kt`
- `app/src/main/kotlin/com/material/xray/service/XrayHealthWatchdog.kt`
- `app/src/main/kotlin/com/material/xray/service/ConnectionRuntimeManager.kt`
- `app/src/main/kotlin/com/material/xray/service/ConnectionDiagnostics.kt`
- `app/src/main/kotlin/com/material/xray/core/xray/ConfigGenerator.kt`
- Transport-aware replacement/generalization of `RawConfigTunInjector.kt`
- `app/src/main/kotlin/com/material/xray/core/xray/XrayConfigRouting.kt`
- `app/src/main/kotlin/com/material/xray/core/xray/StateFile.kt`
- `app/src/main/kotlin/com/material/xray/core/xray/CleanupManager.kt`
- New `TproxyManager` and `TproxyCompatibilityDetector` files
- Corresponding unit tests

## Implementation Warning

The dropdown itself is small. The correctness boundary is the root networking transaction. Do not implement this as scattered `if (backend == Tproxy)` branches around the existing TUN lifecycle. The backend must own config resources, activation, verification, update, and cleanup as one coherent unit, or crash recovery and leak prevention will be unreliable.
