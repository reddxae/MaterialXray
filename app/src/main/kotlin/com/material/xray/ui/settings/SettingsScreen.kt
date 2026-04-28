package com.material.xray.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.material.xray.model.XrayLogLevel
import com.material.xray.ui.components.ScrolledTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val tunName by viewModel.tunName.collectAsStateWithLifecycle()
    val dnsServers by viewModel.dnsServers.collectAsStateWithLifecycle()
    val autoConnect by viewModel.autoConnect.collectAsStateWithLifecycle()
    val xrayLogLevel by viewModel.xrayLogLevel.collectAsStateWithLifecycle()
    val geoipUrl by viewModel.geoipUrl.collectAsStateWithLifecycle()
    val geositeUrl by viewModel.geositeUrl.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    var logLevelExpanded by remember { mutableStateOf(false) }
    val appVersion = remember(context) {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        }.getOrDefault("unknown")
    }

    var editingTunName by remember(tunName) { mutableStateOf(tunName) }
    var editingDns by remember(dnsServers) { mutableStateOf(dnsServers) }
    var editingGeoipUrl by remember(geoipUrl) { mutableStateOf(geoipUrl) }
    var editingGeositeUrl by remember(geositeUrl) { mutableStateOf(geositeUrl) }
    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val hasTunNameChanges by remember(editingTunName, tunName) { derivedStateOf { editingTunName != tunName } }
    val hasDnsChanges by remember(editingDns, dnsServers) { derivedStateOf { editingDns != dnsServers } }
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

    Scaffold(
        modifier = Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
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
                TextButton(onClick = { viewModel.setTunName(editingTunName) }) { Text("Save") }
            }

            OutlinedTextField(
                value = editingDns,
                onValueChange = { editingDns = it },
                label = { Text("DNS Servers") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Comma-separated, e.g. 1.1.1.1,8.8.8.8") },
            )
            if (hasDnsChanges) {
                TextButton(onClick = { viewModel.setDnsServers(editingDns) }) { Text("Save") }
            }

            ExposedDropdownMenuBox(
                expanded = logLevelExpanded,
                onExpandedChange = { logLevelExpanded = it },
            ) {
                OutlinedTextField(
                    value = xrayLogLevel.label,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Xray Log Level") },
                    supportingText = { Text("Default: Error") },
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
                TextButton(onClick = { viewModel.setGeoipUrl(editingGeoipUrl) }) { Text("Save") }
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
                TextButton(onClick = { viewModel.setGeositeUrl(editingGeositeUrl) }) { Text("Save") }
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
            Text("Data", style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("material-xray-backup.json") }) { Text("Export") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) { Text("Import") }
            }

            HorizontalDivider()
            Text("About", style = MaterialTheme.typography.titleMedium)
            Text("Material Xray v$appVersion", style = MaterialTheme.typography.bodyMedium)
            Text("xray-core v26.3.27", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
