package com.material.xray.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.model.LauncherIcon
import com.material.xray.model.NotificationField
import com.material.xray.model.NotificationSettings
import com.material.xray.model.NotificationStyle
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.ui.components.ScrolledTopAppBar
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
    val xrayLogLevel by viewModel.xrayLogLevel.collectAsStateWithLifecycle()
    val defaultOutbound by viewModel.defaultOutbound.collectAsStateWithLifecycle()
    val launcherIcon by viewModel.launcherIcon.collectAsStateWithLifecycle()
    val showAdvancedOptions by viewModel.showAdvancedOptions.collectAsStateWithLifecycle()
    val notificationSettings by viewModel.notificationSettings.collectAsStateWithLifecycle()
    val subscriptionSendHardwareId by viewModel.subscriptionSendHardwareId.collectAsStateWithLifecycle()
    val geoipUrl by viewModel.geoipUrl.collectAsStateWithLifecycle()
    val geositeUrl by viewModel.geositeUrl.collectAsStateWithLifecycle()
    val latencyCheckUrl by viewModel.latencyCheckUrl.collectAsStateWithLifecycle()
    val geoipUpdating by viewModel.geoipUpdating.collectAsStateWithLifecycle()
    val geositeUpdating by viewModel.geositeUpdating.collectAsStateWithLifecycle()
    val xrayCoreVersion by viewModel.xrayCoreVersion.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var defaultOutboundExpanded by remember { mutableStateOf(false) }
    var logLevelExpanded by remember { mutableStateOf(false) }
    var showRootAccessDeniedDialog by remember { mutableStateOf(false) }
    var showNotificationFieldsDialog by remember { mutableStateOf(false) }
    var showFieldStyleDialog by remember { mutableStateOf(false) }
    var showUpdateFrequencyDialog by remember { mutableStateOf(false) }
    val rootServiceAvailable = rootAvailable == true
    val rootServiceActive = useRootService && rootServiceAvailable
    val appVersion = remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    var editingTunName by remember(tunName) { mutableStateOf(tunName) }
    var editingDns by remember(dnsServers) { mutableStateOf(dnsServers) }
    var editingDomesticDns by remember(domesticDnsServers) { mutableStateOf(domesticDnsServers) }
    var editingLatencyDns by remember(latencyDnsServers) { mutableStateOf(latencyDnsServers) }
    var editingGeoipUrl by remember(geoipUrl) { mutableStateOf(geoipUrl) }
    var editingGeositeUrl by remember(geositeUrl) { mutableStateOf(geositeUrl) }
    var editingLatencyCheckUrl by remember(latencyCheckUrl) { mutableStateOf(latencyCheckUrl) }
    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val hasTunNameChanges by remember(editingTunName, tunName) { derivedStateOf { editingTunName != tunName } }
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

    LaunchedEffect(viewModel, context) {
        viewModel.assetUpdateEvents.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.rootAccessDeniedEvents.collect {
            showRootAccessDeniedDialog = true
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            ScrolledTopAppBar(
                title = "Settings",
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
            Text("Service", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Use root service",
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (rootAvailable == false) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        text = when (rootAvailable) {
                            null -> "Checking root access..."
                            true -> "Root access available"
                            false -> "Root unavailable on this device"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = useRootService && rootAvailable != false,
                    onCheckedChange = { viewModel.setUseRootService(it) },
                    enabled = rootServiceAvailable,
                )
            }

            HorizontalDivider()
            Text("Settings", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Show advanced options", style = MaterialTheme.typography.bodyLarge)
                }
                Switch(
                    checked = showAdvancedOptions,
                    onCheckedChange = { viewModel.setShowAdvancedOptions(it) },
                )
            }

            HorizontalDivider()
            Text("Notification", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Customize persistent notification", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (notificationSettings.enabled) "Custom fields enabled" else "Use the default service status text",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = notificationSettings.enabled,
                    onCheckedChange = viewModel::setNotificationEnabled,
                )
            }

            SettingsActionRow(
                title = "Configure fields",
                subtitle = notificationFieldSummary(notificationSettings),
                enabled = notificationSettings.enabled,
                onClick = { showNotificationFieldsDialog = true },
            )
            SettingsActionRow(
                title = "Field style",
                subtitle = notificationSettings.style.label,
                enabled = notificationSettings.enabled,
                onClick = { showFieldStyleDialog = true },
            )
            SettingsActionRow(
                title = "Update frequency",
                subtitle = "Every ${notificationSettings.updateIntervalMs} ms",
                enabled = notificationSettings.enabled,
                onClick = { showUpdateFrequencyDialog = true },
            )

            HorizontalDivider()
            Text("Appearance", style = MaterialTheme.typography.titleMedium)

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LauncherIcon.entries.forEach { icon ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(icon.label, style = MaterialTheme.typography.bodyLarge)
                        }
                        RadioButton(
                            selected = icon == launcherIcon,
                            onClick = { viewModel.setLauncherIcon(icon) },
                        )
                    }
                }
            }

            HorizontalDivider()
            Text("Startup", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Auto-connect on boot",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Switch(
                    checked = autoConnect,
                    onCheckedChange = { viewModel.setAutoConnect(it) },
                    enabled = !useRootService || rootServiceActive,
                )
            }

            HorizontalDivider()
            Text("Routing", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Bypass LAN", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Route private IPs and LAN domains directly",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = bypassLan, onCheckedChange = { viewModel.setBypassLan(it) })
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Allow IPv6 connections", style = MaterialTheme.typography.bodyLarge)
                }
                Switch(checked = allowIpv6, onCheckedChange = { viewModel.setAllowIpv6(it) })
            }

            HorizontalDivider()
            Text("Network", style = MaterialTheme.typography.titleMedium)

            RootTunNameSetting(
                visible = rootServiceActive,
                editingTunName = editingTunName,
                hasTunNameChanges = hasTunNameChanges,
                onEditingTunNameChange = { editingTunName = it },
                onSave = { viewModel.setTunName(editingTunName) },
            )

            if (showAdvancedOptions) {
                ExposedDropdownMenuBox(
                    expanded = defaultOutboundExpanded,
                    onExpandedChange = { defaultOutboundExpanded = it },
                ) {
                    OutlinedTextField(
                        value = defaultOutbound.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Default Outbound") },
                        supportingText = { Text(defaultOutbound.description) },
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
                                        Text(outbound.label)
                                        Text(
                                            outbound.description,
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
                label = { Text("DNS Servers") },
                placeholder = { Text("Leave empty to use system DNS") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Comma-separated, e.g. 1.1.1.1,1.0.0.1") },
            )
            if (hasDnsChanges) {
                Button(onClick = { viewModel.setDnsServers(editingDns) }) { Text("Save") }
            }

            OutlinedTextField(
                value = editingDomesticDns,
                onValueChange = { editingDomesticDns = it },
                label = { Text("Domestic DNS") },
                placeholder = { Text("Leave empty to use system DNS") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Used for direct domestic domains, e.g. 77.88.8.8,77.88.8.1") },
            )
            if (hasDomesticDnsChanges) {
                Button(onClick = { viewModel.setDomesticDnsServers(editingDomesticDns) }) { Text("Save") }
            }

            OutlinedTextField(
                value = editingLatencyDns,
                onValueChange = { editingLatencyDns = it },
                label = { Text("Latency DNS Servers") },
                placeholder = { Text("Leave empty to use system DNS") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Used only for node latency checks, e.g. 77.88.8.8,77.88.8.1") },
            )
            if (hasLatencyDnsChanges) {
                Button(onClick = { viewModel.setLatencyDnsServers(editingLatencyDns) }) { Text("Save") }
            }

            if (showAdvancedOptions) {
                ExposedDropdownMenuBox(
                    expanded = logLevelExpanded,
                    onExpandedChange = { logLevelExpanded = it },
                ) {
                    OutlinedTextField(
                        value = xrayLogLevel.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Xray Log Level") },
                        supportingText = { Text("Default: error") },
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
                                text = { Text(level.label) },
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
                label = { Text("GeoIP URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text("Direct URL for the geoip.dat download")
                },
            )
            if (hasGeoipUrlChanges) {
                Button(onClick = { viewModel.setGeoipUrl(editingGeoipUrl) }) { Text("Save") }
            }
            OutlinedButton(
                onClick = { viewModel.updateGeoipAsset(editingGeoipUrl) },
                enabled = !geoipUpdating,
            ) {
                Text(if (geoipUpdating) "Updating..." else "Update")
            }

            OutlinedTextField(
                value = editingGeositeUrl,
                onValueChange = { editingGeositeUrl = it },
                label = { Text("GeoSite URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = {
                    Text("Direct URL for the geosite.dat download")
                },
            )
            if (hasGeositeUrlChanges) {
                Button(onClick = { viewModel.setGeositeUrl(editingGeositeUrl) }) { Text("Save") }
            }
            OutlinedButton(
                onClick = { viewModel.updateGeositeAsset(editingGeositeUrl) },
                enabled = !geositeUpdating,
            ) {
                Text(if (geositeUpdating) "Updating..." else "Update")
            }

            if (showAdvancedOptions) {
                OutlinedTextField(
                    value = editingLatencyCheckUrl,
                    onValueChange = { editingLatencyCheckUrl = it },
                    label = { Text("Latency Check URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("HTTP endpoint used for node latency checks")
                    },
                )
                if (hasLatencyCheckUrlChanges) {
                    Button(onClick = { viewModel.setLatencyCheckUrl(editingLatencyCheckUrl) }) { Text("Save") }
                }
            }

            HorizontalDivider()
            Text("Subscriptions", style = MaterialTheme.typography.titleMedium)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Send hardware ID (HWID)", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Include a stable x-hwid header so providers can recognise this device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = subscriptionSendHardwareId,
                    onCheckedChange = viewModel::setSubscriptionSendHardwareId,
                )
            }

            HorizontalDivider()
            Text("Data", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("material-xray-backup.json") }) { Text("Export") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) { Text("Import") }
            }

            HorizontalDivider()
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("Material Xray v$appVersion", style = MaterialTheme.typography.bodyMedium)
            Text(xrayCoreVersionText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    SettingsDialogs(
        showRootAccessDeniedDialog = showRootAccessDeniedDialog,
        showNotificationFieldsDialog = showNotificationFieldsDialog,
        showUpdateFrequencyDialog = showUpdateFrequencyDialog,
        showFieldStyleDialog = showFieldStyleDialog,
        notificationSettings = notificationSettings,
        onDismissRootAccessDenied = { showRootAccessDeniedDialog = false },
        onDismissNotificationFields = { showNotificationFieldsDialog = false },
        onDismissUpdateFrequency = { showUpdateFrequencyDialog = false },
        onDismissFieldStyle = { showFieldStyleDialog = false },
        onFieldEnabledChange = viewModel::setNotificationFieldEnabled,
        onReorderFields = viewModel::setNotificationFieldOrder,
        onUpdateFrequency = viewModel::setNotificationUpdateIntervalMs,
        onSelectFieldStyle = viewModel::setNotificationStyle,
    )
}

@Composable
private fun SettingsDialogs(
    showRootAccessDeniedDialog: Boolean,
    showNotificationFieldsDialog: Boolean,
    showUpdateFrequencyDialog: Boolean,
    showFieldStyleDialog: Boolean,
    notificationSettings: NotificationSettings,
    onDismissRootAccessDenied: () -> Unit,
    onDismissNotificationFields: () -> Unit,
    onDismissUpdateFrequency: () -> Unit,
    onDismissFieldStyle: () -> Unit,
    onFieldEnabledChange: (NotificationField, Boolean) -> Unit,
    onReorderFields: (List<NotificationField>) -> Unit,
    onUpdateFrequency: (Int) -> Unit,
    onSelectFieldStyle: (NotificationStyle) -> Unit,
) {
    if (showRootAccessDeniedDialog) {
        AlertDialog(
            onDismissRequest = onDismissRootAccessDenied,
            text = { Text("Unable to access root on device") },
            confirmButton = {
                Button(onClick = onDismissRootAccessDenied) {
                    Text("OK")
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
}

private fun xrayCoreVersionText(xrayCoreVersion: String?): String = when (xrayCoreVersion) {
    null -> "xray-core version detecting..."
    "unknown" -> "xray-core version unknown"
    else -> "xray-core v$xrayCoreVersion"
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
        label = { Text("TUN Interface Name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        supportingText = { Text("Default: xray0") },
    )
    if (hasTunNameChanges) {
        Button(onClick = onSave) { Text("Save") }
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
        title = { Text("Field style") },
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
                            Text(style.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                style.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
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
        title = { Text("Update frequency") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value.filter(Char::isDigit).take(4) },
                singleLine = true,
                isError = text.isNotEmpty() && !isValid,
                suffix = { Text("ms") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                supportingText = {
                    Text(
                        "Range: ${NotificationSettings.MIN_UPDATE_INTERVAL_MS}-" +
                            "${NotificationSettings.MAX_UPDATE_INTERVAL_MS} ms · Default: " +
                            "${NotificationSettings.DEFAULT_UPDATE_INTERVAL_MS} ms",
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { parsed?.let(onConfirm) },
                enabled = isValid,
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
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
        title = { Text("Notification fields") },
        text = {
            Column {
                Text(
                    "Drag the handle to reorder how fields appear in the notification.",
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
            TextButton(onClick = onDismiss) { Text("Done") }
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
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (dragging) {
                                MaterialTheme.colorScheme.surfaceVariant
                            } else {
                                Color.Transparent
                            },
                        )
                        .heightIn(min = 52.dp)
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.DragIndicator,
                        contentDescription = "Drag to reorder",
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
                        Text(field.label, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            field.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = isEnabled(field),
                        onCheckedChange = { onToggle(field, it) },
                    )
                }
            }
        }
    }
}

private fun notificationFieldSummary(settings: NotificationSettings): String {
    val enabledFields = settings.normalizedFieldOrder()
        .filter(settings::isFieldEnabled)
        .map(NotificationField::label)
    return if (enabledFields.isEmpty()) "No custom fields selected" else enabledFields.joinToString(" • ")
}
