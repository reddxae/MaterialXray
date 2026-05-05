package com.material.xray.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.collect
import com.material.xray.model.LauncherIcon
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.ui.components.ScrolledTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val tunName by viewModel.tunName.collectAsStateWithLifecycle()
    val dnsServers by viewModel.dnsServers.collectAsStateWithLifecycle()
    val domesticDnsServers by viewModel.domesticDnsServers.collectAsStateWithLifecycle()
    val latencyDnsServers by viewModel.latencyDnsServers.collectAsStateWithLifecycle()
    val autoConnect by viewModel.autoConnect.collectAsStateWithLifecycle()
    val bypassLan by viewModel.bypassLan.collectAsStateWithLifecycle()
    val xrayLogLevel by viewModel.xrayLogLevel.collectAsStateWithLifecycle()
    val defaultOutbound by viewModel.defaultOutbound.collectAsStateWithLifecycle()
    val launcherIcon by viewModel.launcherIcon.collectAsStateWithLifecycle()
    val showAdvancedOptions by viewModel.showAdvancedOptions.collectAsStateWithLifecycle()
    val geoipUrl by viewModel.geoipUrl.collectAsStateWithLifecycle()
    val geositeUrl by viewModel.geositeUrl.collectAsStateWithLifecycle()
    val geoipUpdating by viewModel.geoipUpdating.collectAsStateWithLifecycle()
    val geositeUpdating by viewModel.geositeUpdating.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var defaultOutboundExpanded by remember { mutableStateOf(false) }
    var logLevelExpanded by remember { mutableStateOf(false) }
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
            Text("Network", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = editingTunName,
                onValueChange = { editingTunName = it },
                label = { Text("TUN Interface Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Default: xray0") },
            )
            if (hasTunNameChanges) {
                Button(onClick = { viewModel.setTunName(editingTunName) }) { Text("Save") }
            }

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
                    Text("Auto-connect on boot", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Reconnect to last server after device restart",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = autoConnect, onCheckedChange = { viewModel.setAutoConnect(it) })
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
            Text("Data", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("material-xray-backup.json") }) { Text("Export") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) { Text("Import") }
            }

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
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("Material Xray v$appVersion", style = MaterialTheme.typography.bodyMedium)
            Text("xray-core v26.3.27", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
