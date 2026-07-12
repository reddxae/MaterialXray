package com.material.xray.ui.settings

import android.app.Activity
import android.os.Process
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.R
import com.material.xray.model.LauncherIcon
import com.material.xray.model.NotificationField
import com.material.xray.model.NotificationSettings
import com.material.xray.model.NotificationStyle
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.model.XrayRuntimeSettings
import com.material.xray.ui.components.ScrolledTopAppBar
import com.material.xray.ui.text.descriptionResource
import com.material.xray.ui.text.labelResource
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val tunName by viewModel.tunName.collectAsStateWithLifecycle()
    val dnsServers by viewModel.dnsServers.collectAsStateWithLifecycle()
    val domesticDnsServers by viewModel.domesticDnsServers.collectAsStateWithLifecycle()
    val latencyDnsServers by viewModel.latencyDnsServers.collectAsStateWithLifecycle()
    val autoConnect by viewModel.autoConnect.collectAsStateWithLifecycle()
    val useRootService by viewModel.useRootService.collectAsStateWithLifecycle()
    val rootAvailable by viewModel.rootAvailable.collectAsStateWithLifecycle()
    val bypassLan by viewModel.bypassLan.collectAsStateWithLifecycle()
    val allowIpv6 by viewModel.allowIpv6.collectAsStateWithLifecycle()
    val xrayBufferSizeKiB by viewModel.xrayBufferSizeKiB.collectAsStateWithLifecycle()
    val tunMtu by viewModel.tunMtu.collectAsStateWithLifecycle()
    val xrayMemoryRestartThresholdMiB by viewModel.xrayMemoryRestartThresholdMiB.collectAsStateWithLifecycle()
    val xrayLogLevel by viewModel.xrayLogLevel.collectAsStateWithLifecycle()
    val defaultOutbound by viewModel.defaultOutbound.collectAsStateWithLifecycle()
    val launcherIcon by viewModel.launcherIcon.collectAsStateWithLifecycle()
    val showAdvancedOptions by viewModel.showAdvancedOptions.collectAsStateWithLifecycle()
    val notificationSettings by viewModel.notificationSettings.collectAsStateWithLifecycle()
    val subscriptionSendHardwareId by viewModel.subscriptionSendHardwareId.collectAsStateWithLifecycle()
    val routingPolicyControl by viewModel.routingPolicyControl.collectAsStateWithLifecycle()
    val geoipUrl by viewModel.geoipUrl.collectAsStateWithLifecycle()
    val geositeUrl by viewModel.geositeUrl.collectAsStateWithLifecycle()
    val latencyCheckUrl by viewModel.latencyCheckUrl.collectAsStateWithLifecycle()
    val geoipUpdating by viewModel.geoipUpdating.collectAsStateWithLifecycle()
    val geositeUpdating by viewModel.geositeUpdating.collectAsStateWithLifecycle()
    val xrayCoreVersion by viewModel.xrayCoreVersion.collectAsStateWithLifecycle()
    val databaseResetting by viewModel.databaseResetting.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val scrollState = rememberScrollState()
    var defaultOutboundExpanded by remember { mutableStateOf(false) }
    var logLevelExpanded by remember { mutableStateOf(false) }
    var showRootAccessDeniedDialog by remember { mutableStateOf(false) }
    var showNotificationFieldsDialog by remember { mutableStateOf(false) }
    var showFieldStyleDialog by remember { mutableStateOf(false) }
    var showUpdateFrequencyDialog by remember { mutableStateOf(false) }
    var showResetDatabaseDialog by remember { mutableStateOf(false) }
    var showAdvancedOptionsRowTop by remember { mutableStateOf<Int?>(null) }
    var pendingAdvancedOptionsScrollAnchor by remember { mutableStateOf<AdvancedOptionsScrollAnchor?>(null) }
    val rootServiceAvailable = rootAvailable == true
    val rootServiceActive = useRootService && rootServiceAvailable
    val appVersion = remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
    }

    var editingTunName by remember(tunName) { mutableStateOf(tunName) }
    var editingXrayBufferSizeKiB by remember(xrayBufferSizeKiB) { mutableStateOf(xrayBufferSizeKiB.toString()) }
    var editingTunMtu by remember(tunMtu) { mutableStateOf(tunMtu.toString()) }
    var editingXrayMemoryRestartThresholdMiB by remember(xrayMemoryRestartThresholdMiB) {
        mutableStateOf(xrayMemoryRestartThresholdMiB.toString())
    }
    var editingDns by remember(dnsServers) { mutableStateOf(dnsServers) }
    var editingDomesticDns by remember(domesticDnsServers) { mutableStateOf(domesticDnsServers) }
    var editingLatencyDns by remember(latencyDnsServers) { mutableStateOf(latencyDnsServers) }
    var editingGeoipUrl by remember(geoipUrl) { mutableStateOf(geoipUrl) }
    var editingGeositeUrl by remember(geositeUrl) { mutableStateOf(geositeUrl) }
    var editingLatencyCheckUrl by remember(latencyCheckUrl) { mutableStateOf(latencyCheckUrl) }
    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val hasTunNameChanges by remember(editingTunName, tunName) { derivedStateOf { editingTunName != tunName } }
    val parsedXrayBufferSizeKiB by remember(editingXrayBufferSizeKiB) {
        derivedStateOf { editingXrayBufferSizeKiB.toIntOrNull() }
    }
    val parsedTunMtu by remember(editingTunMtu) { derivedStateOf { editingTunMtu.toIntOrNull() } }
    val parsedXrayMemoryRestartThresholdMiB by remember(editingXrayMemoryRestartThresholdMiB) {
        derivedStateOf { editingXrayMemoryRestartThresholdMiB.toIntOrNull() }
    }
    val isXrayBufferSizeKiBValid by remember(parsedXrayBufferSizeKiB) {
        derivedStateOf { parsedXrayBufferSizeKiB?.let(XrayRuntimeSettings::isValidXrayBufferSizeKiB) == true }
    }
    val isTunMtuValid by remember(parsedTunMtu) {
        derivedStateOf { parsedTunMtu?.let(XrayRuntimeSettings::isValidTunMtu) == true }
    }
    val isXrayMemoryRestartThresholdMiBValid by remember(parsedXrayMemoryRestartThresholdMiB) {
        derivedStateOf {
            parsedXrayMemoryRestartThresholdMiB
                ?.let(XrayRuntimeSettings::isValidXrayMemoryRestartThresholdMiB) == true
        }
    }
    val hasXrayBufferSizeKiBChanges by remember(editingXrayBufferSizeKiB, xrayBufferSizeKiB) {
        derivedStateOf { editingXrayBufferSizeKiB != xrayBufferSizeKiB.toString() }
    }
    val hasTunMtuChanges by remember(editingTunMtu, tunMtu) {
        derivedStateOf { editingTunMtu != tunMtu.toString() }
    }
    val hasXrayMemoryRestartThresholdMiBChanges by remember(
        editingXrayMemoryRestartThresholdMiB,
        xrayMemoryRestartThresholdMiB,
    ) {
        derivedStateOf { editingXrayMemoryRestartThresholdMiB != xrayMemoryRestartThresholdMiB.toString() }
    }
    val hasDnsChanges by remember(editingDns, dnsServers) { derivedStateOf { editingDns != dnsServers } }
    val hasDomesticDnsChanges by remember(editingDomesticDns, domesticDnsServers) {
        derivedStateOf { editingDomesticDns != domesticDnsServers }
    }
    val hasLatencyDnsChanges by remember(editingLatencyDns, latencyDnsServers) {
        derivedStateOf { editingLatencyDns != latencyDnsServers }
    }
    val hasGeoipUrlChanges by remember(editingGeoipUrl, geoipUrl) {
        derivedStateOf { editingGeoipUrl.trim() != geoipUrl }
    }
    val hasGeositeUrlChanges by remember(editingGeositeUrl, geositeUrl) {
        derivedStateOf { editingGeositeUrl.trim() != geositeUrl }
    }
    val hasLatencyCheckUrlChanges by remember(editingLatencyCheckUrl, latencyCheckUrl) {
        derivedStateOf { editingLatencyCheckUrl.trim() != latencyCheckUrl }
    }
    val xrayCoreVersionText = xrayCoreVersionText(xrayCoreVersion)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.importBackup(it) } }

    LaunchedEffect(viewModel, context, resources) {
        viewModel.assetUpdateEvents.collect { message ->
            val text = message.detail?.let { detail ->
                resources.getString(message.messageResId, detail)
            } ?: resources.getString(message.messageResId)
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.rootAccessDeniedEvents.collect {
            showRootAccessDeniedDialog = true
        }
    }

    LaunchedEffect(viewModel, context, resources) {
        viewModel.databaseResetEvents.collect { success ->
            if (success) {
                (context as? Activity)?.finishAndRemoveTask()
                    ?: Process.killProcess(Process.myPid())
            } else {
                Toast.makeText(
                    context,
                    resources.getString(R.string.settings_internal_database_reset_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    AdvancedOptionsScrollAnchorEffect(
        showAdvancedOptions = showAdvancedOptions,
        anchor = pendingAdvancedOptionsScrollAnchor,
        rowTop = showAdvancedOptionsRowTop,
        scrollState = scrollState,
        onConsumed = { pendingAdvancedOptionsScrollAnchor = null },
    )

    Scaffold(
        modifier = Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            ScrolledTopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = topAppBarScrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsServiceSection(
                rootAvailable = rootAvailable,
                rootServiceAvailable = rootServiceAvailable,
                rootServiceActive = rootServiceActive,
                useRootService = useRootService,
                autoConnect = autoConnect,
                onUseRootServiceChange = viewModel::setUseRootService,
                onAutoConnectChange = viewModel::setAutoConnect,
            )

            HorizontalDivider()
            Text(stringResource(R.string.settings_section_routing), style = MaterialTheme.typography.titleMedium)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsNestedSection(title = stringResource(R.string.settings_connectivity_title)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .toggleable(
                                value = bypassLan,
                                role = Role.Switch,
                                onValueChange = { viewModel.setBypassLan(it) },
                            )
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.settings_bypass_lan_title), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.settings_bypass_lan_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = bypassLan, onCheckedChange = null)
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .toggleable(
                                value = allowIpv6,
                                role = Role.Switch,
                                onValueChange = { viewModel.setAllowIpv6(it) },
                            )
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_allow_ipv6_connections),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Switch(checked = allowIpv6, onCheckedChange = null)
                    }
                }

                SettingsNestedSection(title = stringResource(R.string.settings_routing_policy_title)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        RoutingPolicyControl.entries.forEach { policy ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .selectable(
                                        selected = policy == routingPolicyControl,
                                        role = Role.RadioButton,
                                        onClick = { viewModel.setRoutingPolicyControl(policy) },
                                    )
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(policy.labelResource), style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        stringResource(policy.descriptionResource),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                RadioButton(
                                    selected = policy == routingPolicyControl,
                                    onClick = null,
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.settings_section_network), style = MaterialTheme.typography.titleMedium)

            RootTunNameSetting(
                visible = rootServiceActive,
                editingTunName = editingTunName,
                hasTunNameChanges = hasTunNameChanges,
                onEditingTunNameChange = { editingTunName = it },
                onSave = { viewModel.setTunName(editingTunName) },
            )

            if (showAdvancedOptions) {
                AdvancedIntegerSetting(
                    value = editingXrayBufferSizeKiB,
                    onValueChange = { editingXrayBufferSizeKiB = it },
                    label = stringResource(R.string.settings_xray_buffer_size_label),
                    supportingText = stringResource(
                        R.string.settings_xray_buffer_size_supporting_text,
                        XrayRuntimeSettings.MIN_XRAY_BUFFER_SIZE_KIB,
                        XrayRuntimeSettings.MAX_XRAY_BUFFER_SIZE_KIB,
                        XrayRuntimeSettings.DEFAULT_XRAY_BUFFER_SIZE_KIB,
                    ),
                    suffix = stringResource(R.string.settings_kib_abbreviation),
                    isValid = isXrayBufferSizeKiBValid,
                    hasChanges = hasXrayBufferSizeKiBChanges,
                    onSave = { parsedXrayBufferSizeKiB?.let(viewModel::setXrayBufferSizeKiB) },
                )
                AdvancedIntegerSetting(
                    value = editingTunMtu,
                    onValueChange = { editingTunMtu = it },
                    label = stringResource(R.string.settings_tun_mtu_label),
                    supportingText = stringResource(
                        R.string.settings_tun_mtu_supporting_text,
                        XrayRuntimeSettings.MIN_TUN_MTU,
                        XrayRuntimeSettings.MAX_TUN_MTU,
                        XrayRuntimeSettings.DEFAULT_TUN_MTU,
                    ),
                    suffix = stringResource(R.string.settings_bytes_abbreviation),
                    isValid = isTunMtuValid,
                    hasChanges = hasTunMtuChanges,
                    onSave = { parsedTunMtu?.let(viewModel::setTunMtu) },
                )
                AdvancedIntegerSetting(
                    value = editingXrayMemoryRestartThresholdMiB,
                    onValueChange = { editingXrayMemoryRestartThresholdMiB = it },
                    label = stringResource(R.string.settings_xray_memory_restart_threshold_label),
                    supportingText = stringResource(
                        R.string.settings_xray_memory_restart_threshold_supporting_text,
                        XrayRuntimeSettings.MIN_XRAY_MEMORY_RESTART_THRESHOLD_MIB,
                        XrayRuntimeSettings.MAX_XRAY_MEMORY_RESTART_THRESHOLD_MIB,
                        XrayRuntimeSettings.DEFAULT_XRAY_MEMORY_RESTART_THRESHOLD_MIB,
                    ),
                    suffix = stringResource(R.string.settings_mib_abbreviation),
                    isValid = isXrayMemoryRestartThresholdMiBValid,
                    hasChanges = hasXrayMemoryRestartThresholdMiBChanges,
                    onSave = {
                        parsedXrayMemoryRestartThresholdMiB
                            ?.let(viewModel::setXrayMemoryRestartThresholdMiB)
                    },
                )
                ExposedDropdownMenuBox(
                    expanded = defaultOutboundExpanded,
                    onExpandedChange = { defaultOutboundExpanded = it },
                ) {
                    OutlinedTextField(
                        value = stringResource(defaultOutbound.labelResource),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.settings_default_outbound_label)) },
                        supportingText = { Text(stringResource(defaultOutbound.descriptionResource)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = defaultOutboundExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = defaultOutboundExpanded,
                        onDismissRequest = { defaultOutboundExpanded = false },
                    ) {
                        XrayOutbound.entries.forEach { outbound ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(stringResource(outbound.labelResource))
                                        Text(
                                            stringResource(outbound.descriptionResource),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    defaultOutboundExpanded = false
                                    viewModel.setDefaultOutbound(outbound)
                                },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = editingDns,
                onValueChange = { editingDns = it },
                label = { Text(stringResource(R.string.settings_dns_servers_label)) },
                placeholder = { Text(stringResource(R.string.settings_dns_servers_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource(R.string.settings_dns_servers_supporting_text)) },
            )
            if (hasDnsChanges) {
                Button(onClick = { viewModel.setDnsServers(editingDns) }) {
                    Text(stringResource(R.string.settings_save))
                }
            }

            OutlinedTextField(
                value = editingDomesticDns,
                onValueChange = { editingDomesticDns = it },
                label = { Text(stringResource(R.string.settings_domestic_dns_label)) },
                placeholder = { Text(stringResource(R.string.settings_dns_servers_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource(R.string.settings_domestic_dns_supporting_text)) },
            )
            if (hasDomesticDnsChanges) {
                Button(onClick = { viewModel.setDomesticDnsServers(editingDomesticDns) }) {
                    Text(stringResource(R.string.settings_save))
                }
            }

            OutlinedTextField(
                value = editingLatencyDns,
                onValueChange = { editingLatencyDns = it },
                label = { Text(stringResource(R.string.settings_latency_dns_servers_label)) },
                placeholder = { Text(stringResource(R.string.settings_dns_servers_placeholder)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource(R.string.settings_latency_dns_servers_supporting_text)) },
            )
            if (hasLatencyDnsChanges) {
                Button(onClick = { viewModel.setLatencyDnsServers(editingLatencyDns) }) {
                    Text(stringResource(R.string.settings_save))
                }
            }

            if (showAdvancedOptions) {
                ExposedDropdownMenuBox(
                    expanded = logLevelExpanded,
                    onExpandedChange = { logLevelExpanded = it },
                ) {
                    OutlinedTextField(
                        value = stringResource(xrayLogLevel.labelResource),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.settings_xray_log_level_label)) },
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.settings_default_value,
                                    stringResource(XrayLogLevel.default.labelResource),
                                ),
                            )
                        },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = logLevelExpanded) },
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(
                        expanded = logLevelExpanded,
                        onDismissRequest = { logLevelExpanded = false },
                    ) {
                        XrayLogLevel.entries.forEach { level ->
                            DropdownMenuItem(
                                text = { Text(stringResource(level.labelResource)) },
                                onClick = {
                                    logLevelExpanded = false
                                    viewModel.setXrayLogLevel(level)
                                },
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = editingGeoipUrl,
                onValueChange = { editingGeoipUrl = it },
                label = { Text(stringResource(R.string.settings_geoip_url_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(stringResource(R.string.settings_geoip_url_supporting_text))
                },
            )
            if (hasGeoipUrlChanges) {
                Button(onClick = { viewModel.setGeoipUrl(editingGeoipUrl) }) {
                    Text(stringResource(R.string.settings_save))
                }
            }
            OutlinedButton(
                onClick = { viewModel.updateGeoipAsset(editingGeoipUrl) },
                enabled = !geoipUpdating,
            ) {
                Text(
                    stringResource(
                        if (geoipUpdating) R.string.settings_updating else R.string.settings_update,
                    ),
                )
            }

            OutlinedTextField(
                value = editingGeositeUrl,
                onValueChange = { editingGeositeUrl = it },
                label = { Text(stringResource(R.string.settings_geosite_url_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text(stringResource(R.string.settings_geosite_url_supporting_text))
                },
            )
            if (hasGeositeUrlChanges) {
                Button(onClick = { viewModel.setGeositeUrl(editingGeositeUrl) }) {
                    Text(stringResource(R.string.settings_save))
                }
            }
            OutlinedButton(
                onClick = { viewModel.updateGeositeAsset(editingGeositeUrl) },
                enabled = !geositeUpdating,
            ) {
                Text(
                    stringResource(
                        if (geositeUpdating) R.string.settings_updating else R.string.settings_update,
                    ),
                )
            }

            if (showAdvancedOptions) {
                OutlinedTextField(
                    value = editingLatencyCheckUrl,
                    onValueChange = { editingLatencyCheckUrl = it },
                    label = { Text(stringResource(R.string.settings_latency_check_url_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(stringResource(R.string.settings_latency_check_url_supporting_text))
                    },
                )
                if (hasLatencyCheckUrlChanges) {
                    Button(onClick = { viewModel.setLatencyCheckUrl(editingLatencyCheckUrl) }) {
                        Text(stringResource(R.string.settings_save))
                    }
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.settings_section_appearance), style = MaterialTheme.typography.titleMedium)

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsNestedSection(title = stringResource(R.string.settings_notification_title)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .toggleable(
                                value = notificationSettings.enabled,
                                role = Role.Switch,
                                onValueChange = viewModel::setNotificationEnabled,
                            )
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(R.string.settings_customize_service_notification),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Switch(
                            checked = notificationSettings.enabled,
                            onCheckedChange = null,
                        )
                    }

                    if (notificationSettings.enabled) {
                        SettingsActionRow(
                            title = stringResource(R.string.settings_configure_notification_fields),
                            subtitle = notificationFieldSummary(notificationSettings),
                            onClick = { showNotificationFieldsDialog = true },
                        )
                        SettingsActionRow(
                            title = stringResource(R.string.settings_notification_field_style),
                            subtitle = stringResource(notificationSettings.style.labelResource),
                            onClick = { showFieldStyleDialog = true },
                        )
                        SettingsActionRow(
                            title = stringResource(R.string.settings_notification_update_frequency),
                            subtitle = pluralStringResource(
                                R.plurals.settings_notification_update_frequency_summary,
                                notificationSettings.updateIntervalMs,
                                notificationSettings.updateIntervalMs,
                            ),
                            onClick = { showUpdateFrequencyDialog = true },
                        )
                    }
                }

                SettingsNestedSection(title = stringResource(R.string.settings_app_icon_title)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LauncherIcon.entries.forEach { icon ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .selectable(
                                        selected = icon == launcherIcon,
                                        role = Role.RadioButton,
                                        onClick = { viewModel.setLauncherIcon(icon) },
                                    )
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(icon.labelResource), style = MaterialTheme.typography.bodyLarge)
                                }
                                RadioButton(
                                    selected = icon == launcherIcon,
                                    onClick = null,
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(
                            value = subscriptionSendHardwareId,
                            role = Role.Switch,
                            onValueChange = viewModel::setSubscriptionSendHardwareId,
                        )
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_send_hardware_id_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.settings_send_hardware_id_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = subscriptionSendHardwareId,
                        onCheckedChange = null,
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { coordinates ->
                            showAdvancedOptionsRowTop = coordinates.positionInRoot().y.roundToInt()
                        }
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .toggleable(
                            value = showAdvancedOptions,
                            role = Role.Switch,
                            onValueChange = { enabled ->
                                showAdvancedOptionsRowTop?.let { rowTop ->
                                    pendingAdvancedOptionsScrollAnchor = AdvancedOptionsScrollAnchor(
                                        targetEnabled = enabled,
                                        rowTop = rowTop,
                                        scrollValue = scrollState.value,
                                    )
                                }
                                viewModel.setShowAdvancedOptions(enabled)
                            },
                        )
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_show_advanced_options), style = MaterialTheme.typography.bodyLarge)
                    }
                    Switch(
                        checked = showAdvancedOptions,
                        onCheckedChange = null,
                    )
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.settings_section_data), style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("material-xray-backup.json") }) {
                    Text(stringResource(R.string.settings_export))
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                    Text(stringResource(R.string.settings_import))
                }
            }
            if (showAdvancedOptions) {
                SettingsActionRow(
                    title = stringResource(R.string.settings_reset_internal_database),
                    subtitle = stringResource(
                        if (databaseResetting) {
                            R.string.settings_resetting_internal_database
                        } else {
                            R.string.settings_reset_internal_database_description
                        },
                    ),
                    enabled = !databaseResetting,
                    onClick = { showResetDatabaseDialog = true },
                )
            }

            HorizontalDivider()
            Text(stringResource(R.string.settings_section_about), style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (appVersion == null) {
                    stringResource(R.string.settings_app_version_unknown, stringResource(R.string.app_name))
                } else {
                    stringResource(R.string.settings_app_version, stringResource(R.string.app_name), appVersion)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(xrayCoreVersionText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    SettingsDialogs(
        showRootAccessDeniedDialog = showRootAccessDeniedDialog,
        showNotificationFieldsDialog = showNotificationFieldsDialog,
        showUpdateFrequencyDialog = showUpdateFrequencyDialog,
        showFieldStyleDialog = showFieldStyleDialog,
        showResetDatabaseDialog = showResetDatabaseDialog,
        notificationSettings = notificationSettings,
        onDismissRootAccessDenied = { showRootAccessDeniedDialog = false },
        onDismissNotificationFields = { showNotificationFieldsDialog = false },
        onDismissUpdateFrequency = { showUpdateFrequencyDialog = false },
        onDismissFieldStyle = { showFieldStyleDialog = false },
        onDismissResetDatabase = { showResetDatabaseDialog = false },
        onResetDatabase = {
            showResetDatabaseDialog = false
            viewModel.resetInternalDatabase()
        },
        onFieldEnabledChange = viewModel::setNotificationFieldEnabled,
        onReorderFields = viewModel::setNotificationFieldOrder,
        onUpdateFrequency = viewModel::setNotificationUpdateIntervalMs,
        onSelectFieldStyle = viewModel::setNotificationStyle,
    )
}

private data class AdvancedOptionsScrollAnchor(
    val targetEnabled: Boolean,
    val rowTop: Int,
    val scrollValue: Int,
)

@Composable
private fun AdvancedOptionsScrollAnchorEffect(
    showAdvancedOptions: Boolean,
    anchor: AdvancedOptionsScrollAnchor?,
    rowTop: Int?,
    scrollState: ScrollState,
    onConsumed: () -> Unit,
) {
    LaunchedEffect(showAdvancedOptions) {
        anchor ?: return@LaunchedEffect
        if (anchor.targetEnabled != showAdvancedOptions) return@LaunchedEffect

        withFrameNanos { }
        rowTop ?: return@LaunchedEffect
        val scrollDelta = rowTop - anchor.rowTop
        if (scrollDelta != 0) {
            scrollState.scrollTo((anchor.scrollValue + scrollDelta).coerceIn(0, scrollState.maxValue))
        }
        onConsumed()
    }
}

@Composable
private fun SettingsServiceSection(
    rootAvailable: Boolean?,
    rootServiceAvailable: Boolean,
    rootServiceActive: Boolean,
    useRootService: Boolean,
    autoConnect: Boolean,
    onUseRootServiceChange: (Boolean) -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
) {
    Text(stringResource(R.string.settings_section_service), style = MaterialTheme.typography.titleMedium)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .toggleable(
                    value = useRootService && rootAvailable != false,
                    enabled = rootServiceAvailable,
                    role = Role.Switch,
                    onValueChange = onUseRootServiceChange,
                )
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_use_root_service),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (rootAvailable == false) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                if (rootAvailable == false) {
                    Text(
                        stringResource(R.string.settings_unavailable),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = useRootService && rootAvailable != false,
                onCheckedChange = null,
                enabled = rootServiceAvailable,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .toggleable(
                    value = autoConnect,
                    enabled = !useRootService || rootServiceActive,
                    role = Role.Switch,
                    onValueChange = onAutoConnectChange,
                )
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_auto_connect_on_boot),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Switch(
                checked = autoConnect,
                onCheckedChange = null,
                enabled = !useRootService || rootServiceActive,
            )
        }
    }
}

@Composable
private fun SettingsNestedSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SettingsDialogs(
    showRootAccessDeniedDialog: Boolean,
    showNotificationFieldsDialog: Boolean,
    showUpdateFrequencyDialog: Boolean,
    showFieldStyleDialog: Boolean,
    showResetDatabaseDialog: Boolean,
    notificationSettings: NotificationSettings,
    onDismissRootAccessDenied: () -> Unit,
    onDismissNotificationFields: () -> Unit,
    onDismissUpdateFrequency: () -> Unit,
    onDismissFieldStyle: () -> Unit,
    onDismissResetDatabase: () -> Unit,
    onResetDatabase: () -> Unit,
    onFieldEnabledChange: (NotificationField, Boolean) -> Unit,
    onReorderFields: (List<NotificationField>) -> Unit,
    onUpdateFrequency: (Int) -> Unit,
    onSelectFieldStyle: (NotificationStyle) -> Unit,
) {
    if (showRootAccessDeniedDialog) {
        AlertDialog(
            onDismissRequest = onDismissRootAccessDenied,
            text = { Text(stringResource(R.string.settings_root_access_denied)) },
            confirmButton = {
                Button(onClick = onDismissRootAccessDenied) {
                    Text(stringResource(R.string.settings_ok))
                }
            },
        )
    }

    if (showNotificationFieldsDialog) {
        NotificationFieldsDialog(
            settings = notificationSettings,
            onDismiss = onDismissNotificationFields,
            onFieldEnabledChange = onFieldEnabledChange,
            onReorder = onReorderFields,
        )
    }

    if (showUpdateFrequencyDialog) {
        UpdateFrequencyDialog(
            currentValue = notificationSettings.updateIntervalMs,
            onDismiss = onDismissUpdateFrequency,
            onConfirm = {
                onUpdateFrequency(it)
                onDismissUpdateFrequency()
            },
        )
    }

    if (showFieldStyleDialog) {
        FieldStyleDialog(
            selected = notificationSettings.style,
            onDismiss = onDismissFieldStyle,
            onSelect = onSelectFieldStyle,
        )
    }

    if (showResetDatabaseDialog) {
        AlertDialog(
            onDismissRequest = onDismissResetDatabase,
            title = { Text(stringResource(R.string.settings_reset_internal_database_title)) },
            text = { Text(stringResource(R.string.settings_reset_internal_database_confirmation)) },
            confirmButton = {
                TextButton(onClick = onResetDatabase) {
                    Text(
                        text = stringResource(R.string.settings_reset),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissResetDatabase) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

@Composable
private fun xrayCoreVersionText(xrayCoreVersion: String?): String = when (xrayCoreVersion) {
    null -> stringResource(R.string.settings_xray_core_version_detecting)
    "unknown" -> stringResource(R.string.settings_xray_core_version_unknown)
    else -> stringResource(R.string.settings_xray_core_version, xrayCoreVersion)
}

@Composable
private fun RootTunNameSetting(
    visible: Boolean,
    editingTunName: String,
    hasTunNameChanges: Boolean,
    onEditingTunNameChange: (String) -> Unit,
    onSave: () -> Unit,
) {
    if (!visible) return

    OutlinedTextField(
        value = editingTunName,
        onValueChange = onEditingTunNameChange,
        label = { Text(stringResource(R.string.settings_tun_interface_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        supportingText = { Text(stringResource(R.string.settings_tun_interface_name_default, "xray0")) },
    )
    if (hasTunNameChanges) {
        Button(onClick = onSave) { Text(stringResource(R.string.settings_save)) }
    }
}

@Composable
private fun AdvancedIntegerSetting(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supportingText: String,
    suffix: String,
    isValid: Boolean,
    hasChanges: Boolean,
    onSave: () -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { newValue -> onValueChange(newValue.filter(Char::isDigit).take(5)) },
        label = { Text(label) },
        supportingText = { Text(supportingText) },
        suffix = { Text(suffix) },
        isError = value.isNotEmpty() && !isValid,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    if (hasChanges) {
        Button(onClick = onSave, enabled = isValid) {
            Text(stringResource(R.string.settings_save))
        }
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val titleColor = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val subtitleColor = if (enabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = subtitleColor,
            )
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = subtitleColor,
        )
    }
}

@Composable
private fun FieldStyleDialog(
    selected: NotificationStyle,
    onDismiss: () -> Unit,
    onSelect: (NotificationStyle) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_notification_field_style)) },
        text = {
            Column {
                NotificationStyle.entries.forEach { style ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                onSelect(style)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = style == selected,
                            onClick = {
                                onSelect(style)
                                onDismiss()
                            },
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stringResource(style.labelResource), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(style.descriptionResource),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) }
        },
    )
}

@Composable
private fun UpdateFrequencyDialog(
    currentValue: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(currentValue.toString()) }
    val parsed = text.toIntOrNull()
    val isValid = parsed != null &&
        parsed in NotificationSettings.MIN_UPDATE_INTERVAL_MS..NotificationSettings.MAX_UPDATE_INTERVAL_MS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_notification_update_frequency)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value.filter(Char::isDigit).take(4) },
                singleLine = true,
                isError = text.isNotEmpty() && !isValid,
                suffix = { Text(stringResource(R.string.settings_milliseconds_abbreviation)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(
                        stringResource(
                            R.string.settings_update_frequency_range,
                            NotificationSettings.MIN_UPDATE_INTERVAL_MS,
                            NotificationSettings.MAX_UPDATE_INTERVAL_MS,
                            NotificationSettings.DEFAULT_UPDATE_INTERVAL_MS,
                            stringResource(R.string.settings_milliseconds_abbreviation),
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = isValid,
            ) { Text(stringResource(R.string.settings_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) }
        },
    )
}

@Composable
private fun NotificationFieldsDialog(
    settings: NotificationSettings,
    onDismiss: () -> Unit,
    onFieldEnabledChange: (NotificationField, Boolean) -> Unit,
    onReorder: (List<NotificationField>) -> Unit,
) {
    val order = remember { settings.normalizedFieldOrder().toMutableStateList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_notification_fields_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.settings_notification_fields_reorder_instructions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
                ReorderableFieldList(
                    order = order,
                    isEnabled = settings::isFieldEnabled,
                    onToggle = onFieldEnabledChange,
                    onReordered = { onReorder(order.toList()) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_done)) }
        },
    )
}

@Composable
private fun ReorderableFieldList(
    order: SnapshotStateList<NotificationField>,
    isEnabled: (NotificationField) -> Boolean,
    onToggle: (NotificationField, Boolean) -> Unit,
    onReordered: () -> Unit,
) {
    var draggingField by remember { mutableStateOf<NotificationField?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<NotificationField, Int>() }

    Column(modifier = Modifier.fillMaxWidth()) {
        order.forEach { field ->
            key(field) {
                val dragging = field == draggingField
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .onGloballyPositioned { heights[field] = it.size.height }
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = if (dragging) dragOffsetY else 0f }
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (dragging) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                Color.Transparent
                            },
                        )
                        .heightIn(min = 52.dp)
                        .toggleable(
                            value = isEnabled(field),
                            role = Role.Switch,
                            onValueChange = { onToggle(field, it) },
                        )
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.DragIndicator,
                        contentDescription = stringResource(R.string.settings_drag_to_reorder),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .pointerInput(field) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingField = field
                                        dragOffsetY = 0f
                                    },
                                    onDragEnd = {
                                        draggingField = null
                                        dragOffsetY = 0f
                                        onReordered()
                                    },
                                    onDragCancel = {
                                        draggingField = null
                                        dragOffsetY = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragOffsetY += dragAmount.y
                                        val current = order.indexOf(field)
                                        if (dragAmount.y < 0 && current > 0) {
                                            val above = order[current - 1]
                                            val threshold = (heights[above] ?: 0) / 2f
                                            if (-dragOffsetY > threshold) {
                                                order.add(current - 1, order.removeAt(current))
                                                dragOffsetY += (heights[above] ?: 0)
                                            }
                                        } else if (dragAmount.y > 0 && current < order.lastIndex) {
                                            val below = order[current + 1]
                                            val threshold = (heights[below] ?: 0) / 2f
                                            if (dragOffsetY > threshold) {
                                                order.add(current + 1, order.removeAt(current))
                                                dragOffsetY -= (heights[below] ?: 0)
                                            }
                                        }
                                    },
                                )
                            },
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(field.labelResource), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(field.descriptionResource),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = isEnabled(field),
                        onCheckedChange = null,
                    )
                }
            }
        }
    }
}

@Composable
private fun notificationFieldSummary(settings: NotificationSettings): String {
    val enabledFields = settings.normalizedFieldOrder()
        .filter(settings::isFieldEnabled)
        .map { stringResource(it.labelResource) }
    return if (enabledFields.isEmpty()) {
        stringResource(R.string.settings_no_custom_notification_fields)
    } else {
        enabledFields.joinToString(stringResource(R.string.settings_notification_field_separator))
    }
}
