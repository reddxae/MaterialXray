package com.material.xray.ui.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.material.xray.R
import com.material.xray.core.locale.setAppLocales
import com.material.xray.core.xray.TproxyCompatibility
import com.material.xray.data.repository.BackupSummary
import com.material.xray.data.repository.SettingsSnapshot
import com.material.xray.model.AppUpdateCheckStatus
import com.material.xray.model.LauncherIcon
import com.material.xray.model.NotificationField
import com.material.xray.model.NotificationSettings
import com.material.xray.model.NotificationStyle
import com.material.xray.model.RootConnectionBackend
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.XrayLogLevel
import com.material.xray.model.XrayOutbound
import com.material.xray.model.XrayRuntimeSettings
import com.material.xray.model.isInProgress
import com.material.xray.ui.components.DropdownOption
import com.material.xray.ui.components.ReadOnlyDropdownField
import com.material.xray.ui.components.ScrolledTopAppBar
import com.material.xray.ui.components.SettingsSwitchRow
import com.material.xray.ui.components.rememberSystemState
import com.material.xray.ui.text.descriptionResource
import com.material.xray.ui.text.labelResource
import java.util.Locale
import kotlinx.coroutines.flow.collect
import org.xmlpull.v1.XmlPullParser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val persistedSettings by viewModel.settings.collectAsStateWithLifecycle()
    val startupReady by viewModel.startupReady.collectAsStateWithLifecycle()
    val settings = persistedSettings
    if (settings == null || !startupReady) {
        SettingsLoadingScreen()
    } else {
        SettingsScreenContent(viewModel, settings)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("CyclomaticComplexMethod")
@Composable
private fun SettingsScreenContent(
    viewModel: SettingsViewModel,
    settings: SettingsSnapshot,
) {
    val rootAvailable by viewModel.rootAvailable.collectAsStateWithLifecycle()
    val tproxyCompatibility by viewModel.tproxyCompatibility.collectAsStateWithLifecycle()
    val geoipUpdating by viewModel.geoipUpdating.collectAsStateWithLifecycle()
    val geositeUpdating by viewModel.geositeUpdating.collectAsStateWithLifecycle()
    val xrayCoreVersion by viewModel.xrayCoreVersion.collectAsStateWithLifecycle()
    val databaseResetting by viewModel.databaseResetting.collectAsStateWithLifecycle()
    val backupBusy by viewModel.backupBusy.collectAsStateWithLifecycle()
    val backupImportSummary by viewModel.backupImportSummary.collectAsStateWithLifecycle()
    val appUpdateCheckStatus by viewModel.appUpdateCheckStatus.collectAsStateWithLifecycle()
    val tunName = settings.tunName
    val dnsServers = settings.dnsServers
    val domesticDnsServers = settings.domesticDnsServers
    val autoConnect = settings.autoConnect
    val useRootService = settings.useRootService
    val rootConnectionBackend = settings.rootConnectionBackend
    val bypassLan = settings.bypassLan
    val allowIpv6 = settings.allowIpv6
    val xrayBufferSizeKiB = settings.xrayBufferSizeKiB
    val tunMtu = settings.tunMtu
    val xrayMemoryRestartThresholdMiB = settings.xrayMemoryRestartThresholdMiB
    val passiveHealthMonitoringEnabled = settings.passiveHealthMonitoringEnabled
    val xrayLogLevel = settings.xrayLogLevel
    val defaultOutbound = settings.defaultOutbound
    val launcherIcon = settings.launcherIcon
    val showAdvancedOptions = settings.showAdvancedOptions
    val notificationSettings = settings.notificationSettings
    val subscriptionSendHardwareId = settings.subscriptionSendHardwareId
    val routingPolicyControl = settings.routingPolicyControl
    val geoipUrl = settings.geoipUrl
    val geositeUrl = settings.geositeUrl
    val latencyCheckUrl = settings.latencyCheckUrl
    val sortOutboundsByLatency = settings.sortOutboundsByLatency
    val appUpdateChecksEnabled = settings.appUpdateChecksEnabled
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scrollState = rememberLazyListState()
    var showRootAccessDeniedDialog by rememberSaveable { mutableStateOf(false) }
    var showNotificationFieldsDialog by rememberSaveable { mutableStateOf(false) }
    var showFieldStyleDialog by rememberSaveable { mutableStateOf(false) }
    var showUpdateFrequencyDialog by rememberSaveable { mutableStateOf(false) }
    var showResetDatabaseDialog by rememberSaveable { mutableStateOf(false) }
    var showOpenSourceLicensesDialog by rememberSaveable { mutableStateOf(false) }
    val rootServiceAvailable = rootAvailable != false
    val rootServiceActive = useRootService && rootAvailable == true

    var editingTunName by rememberSaveable(tunName) { mutableStateOf(tunName) }
    var editingXrayBufferSizeKiB by rememberSaveable(xrayBufferSizeKiB) { mutableStateOf(xrayBufferSizeKiB.toString()) }
    var editingTunMtu by rememberSaveable(tunMtu) { mutableStateOf(tunMtu.toString()) }
    var editingXrayMemoryRestartThresholdMiB by rememberSaveable(xrayMemoryRestartThresholdMiB) {
        mutableStateOf(xrayMemoryRestartThresholdMiB.toString())
    }
    var editingDns by rememberSaveable(dnsServers) { mutableStateOf(dnsServers) }
    var editingDomesticDns by rememberSaveable(domesticDnsServers) { mutableStateOf(domesticDnsServers) }
    var editingGeoipUrl by rememberSaveable(geoipUrl) { mutableStateOf(geoipUrl) }
    var editingGeositeUrl by rememberSaveable(geositeUrl) { mutableStateOf(geositeUrl) }
    var editingLatencyCheckUrl by rememberSaveable(latencyCheckUrl) { mutableStateOf(latencyCheckUrl) }
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
    val appUpdateCheckInProgress = appUpdateCheckStatus?.isInProgress == true
    val appUpdateCheckDescription = appUpdateCheckStatus?.let { appUpdateCheckDescription(it) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let { viewModel.exportBackup(it) } }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.prepareBackupImport(it) } }

    LaunchedEffect(viewModel, context, resources) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.assetUpdateEvents.collect { message ->
                val text = message.detail?.let { detail ->
                    resources.getString(message.messageResId, detail)
                } ?: resources.getString(message.messageResId)
                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
            }
        }
    }

    BackupOperationEventEffect(viewModel)

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.rootAccessDeniedEvents.collect {
                showRootAccessDeniedDialog = true
            }
        }
    }

    LaunchedEffect(viewModel, context, resources) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
    }

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
        LazyColumn(
            state = scrollState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "service") {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SettingsServiceSection(
                        rootAvailable = rootAvailable,
                        rootServiceAvailable = rootServiceAvailable,
                        rootServiceActive = rootServiceActive,
                        useRootService = useRootService,
                        rootConnectionBackend = rootConnectionBackend,
                        tproxyCompatibility = tproxyCompatibility,
                        autoConnect = autoConnect,
                        onUseRootServiceChange = viewModel::setUseRootService,
                        onRootConnectionBackendChange = viewModel::setRootConnectionBackend,
                        onRetryTproxyCompatibility = viewModel::retryTproxyCompatibilityCheck,
                        onAutoConnectChange = viewModel::setAutoConnect,
                    )
                }
            }

            item(key = "routing_header") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HorizontalDivider()
                    Text(stringResource(R.string.settings_section_routing), style = MaterialTheme.typography.titleMedium)
                }
            }

            item(key = "routing") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsNestedSection(title = stringResource(R.string.settings_connectivity_title)) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_bypass_lan_title),
                            description = stringResource(R.string.settings_bypass_lan_description),
                            checked = bypassLan,
                            onCheckedChange = { viewModel.setBypassLan(it) },
                        )

                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_allow_ipv6_connections),
                            checked = allowIpv6,
                            onCheckedChange = { viewModel.setAllowIpv6(it) },
                            enabled = isIpv6SelectionEnabled(rootServiceActive, rootConnectionBackend, tproxyCompatibility),
                        )
                    }

                    SettingsNestedSection(title = stringResource(R.string.settings_routing_policy_title)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            RoutingPolicyControl.entries.forEach { policy ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .selectable(
                                            selected = policy == routingPolicyControl,
                                            role = Role.RadioButton,
                                            onClick = { viewModel.setRoutingPolicyControl(policy) },
                                        )
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
            }

            item(key = "network_header") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HorizontalDivider()
                    Text(stringResource(R.string.settings_section_network), style = MaterialTheme.typography.titleMedium)
                }
            }

            if (rootServiceActive && rootConnectionBackend == RootConnectionBackend.Tun) {
                item(key = "tun_name") {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RootTunNameSetting(
                            visible = true,
                            editingTunName = editingTunName,
                            hasTunNameChanges = hasTunNameChanges,
                            onEditingTunNameChange = { editingTunName = it },
                            onSave = { viewModel.setTunName(editingTunName) },
                        )
                    }
                }
            }

            if (showAdvancedOptions) {
                item(key = "xray_buffer") {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
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
                    }
                }
                if (shouldShowTunMtu(rootServiceActive, rootConnectionBackend)) {
                    item(key = "tun_mtu") {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            TunMtuSetting(
                                visible = true,
                                value = editingTunMtu,
                                onValueChange = { editingTunMtu = it },
                                isValid = isTunMtuValid,
                                hasChanges = hasTunMtuChanges,
                                onSave = { parsedTunMtu?.let(viewModel::setTunMtu) },
                            )
                        }
                    }
                }
                item(key = "memory_restart_threshold") {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
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
                    }
                }
                item(key = "passive_health_monitoring") {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_passive_health_monitoring_title),
                        description = stringResource(R.string.settings_passive_health_monitoring_description),
                        checked = passiveHealthMonitoringEnabled,
                        onCheckedChange = viewModel::setPassiveHealthMonitoringEnabled,
                    )
                }
                item(key = "default_outbound") {
                    ReadOnlyDropdownField(
                        label = stringResource(R.string.settings_default_outbound_label),
                        selectedText = stringResource(defaultOutbound.labelResource),
                        supportingText = stringResource(defaultOutbound.descriptionResource),
                        options = XrayOutbound.entries.map { outbound ->
                            DropdownOption(
                                value = outbound,
                                label = stringResource(outbound.labelResource),
                                description = stringResource(outbound.descriptionResource),
                            )
                        },
                        onSelected = viewModel::setDefaultOutbound,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            item(key = "dns") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
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
                        Button(
                            onClick = {
                                editingDns = viewModel.normalizeDnsServers(editingDns)
                                viewModel.setDnsServers(editingDns)
                            },
                        ) {
                            Text(stringResource(R.string.settings_save))
                        }
                    }
                }
            }

            item(key = "domestic_dns") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
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
                        Button(
                            onClick = {
                                editingDomesticDns = viewModel.normalizeDnsServers(editingDomesticDns)
                                viewModel.setDomesticDnsServers(editingDomesticDns)
                            },
                        ) {
                            Text(stringResource(R.string.settings_save))
                        }
                    }
                }
            }

            if (showAdvancedOptions) {
                item(key = "log_level") {
                    ReadOnlyDropdownField(
                        label = stringResource(R.string.settings_xray_log_level_label),
                        selectedText = stringResource(xrayLogLevel.labelResource),
                        supportingText = stringResource(
                            R.string.settings_default_value,
                            stringResource(XrayLogLevel.default.labelResource),
                        ),
                        options = XrayLogLevel.entries.map { level ->
                            DropdownOption(value = level, label = stringResource(level.labelResource))
                        },
                        onSelected = viewModel::setXrayLogLevel,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            item(key = "geoip") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedTextField(
                        value = editingGeoipUrl,
                        onValueChange = { editingGeoipUrl = it },
                        label = { Text(stringResource(R.string.settings_geoip_url_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(stringResource(R.string.settings_geoip_url_supporting_text)) },
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
                        Text(stringResource(if (geoipUpdating) R.string.settings_updating else R.string.settings_update))
                    }
                }
            }

            item(key = "geosite") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedTextField(
                        value = editingGeositeUrl,
                        onValueChange = { editingGeositeUrl = it },
                        label = { Text(stringResource(R.string.settings_geosite_url_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text(stringResource(R.string.settings_geosite_url_supporting_text)) },
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
                        Text(stringResource(if (geositeUpdating) R.string.settings_updating else R.string.settings_update))
                    }
                }
            }

            if (showAdvancedOptions) {
                item(key = "latency_check_url") {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        OutlinedTextField(
                            value = editingLatencyCheckUrl,
                            onValueChange = { editingLatencyCheckUrl = it },
                            label = { Text(stringResource(R.string.settings_latency_check_url_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            supportingText = { Text(stringResource(R.string.settings_latency_check_url_supporting_text)) },
                        )
                        if (hasLatencyCheckUrlChanges) {
                            Button(onClick = { viewModel.setLatencyCheckUrl(editingLatencyCheckUrl) }) {
                                Text(stringResource(R.string.settings_save))
                            }
                        }
                    }
                }
            }

            item(key = "appearance_header") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HorizontalDivider()
                    Text(stringResource(R.string.settings_section_appearance), style = MaterialTheme.typography.titleMedium)
                }
            }
            item(key = "appearance") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppLanguageSetting()

                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_sort_outbounds_by_latency_title),
                        description = stringResource(R.string.settings_sort_outbounds_by_latency_description),
                        checked = sortOutboundsByLatency,
                        onCheckedChange = viewModel::setSortOutboundsByLatency,
                    )

                    NotificationSettingsSection(
                        settings = notificationSettings,
                        onEnabledChange = viewModel::setNotificationEnabled,
                        onConfigureFields = { showNotificationFieldsDialog = true },
                        onConfigureStyle = { showFieldStyleDialog = true },
                        onConfigureFrequency = { showUpdateFrequencyDialog = true },
                    )

                    SettingsNestedSection(title = stringResource(R.string.settings_app_icon_title)) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LauncherIcon.entries.forEach { icon ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                        .selectable(
                                            selected = icon == launcherIcon,
                                            role = Role.RadioButton,
                                            onClick = { viewModel.setLauncherIcon(icon) },
                                        )
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(stringResource(icon.labelResource), style = MaterialTheme.typography.bodyLarge)
                                    }
                                    RadioButton(selected = icon == launcherIcon, onClick = null)
                                }
                            }
                        }
                    }
                }
            }

            item(key = "settings_header") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HorizontalDivider()
                    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleMedium)
                }
            }
            item(key = "app_settings") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_send_hardware_id_title),
                        description = stringResource(R.string.settings_send_hardware_id_description),
                        checked = subscriptionSendHardwareId,
                        onCheckedChange = viewModel::setSubscriptionSendHardwareId,
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_show_advanced_options),
                        checked = showAdvancedOptions,
                        onCheckedChange = viewModel::setShowAdvancedOptions,
                    )
                }
            }

            item(key = "data_header") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HorizontalDivider()
                    Text(stringResource(R.string.settings_section_data), style = MaterialTheme.typography.titleMedium)
                }
            }
            item(key = "backup") {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = !backupBusy,
                        onClick = { exportLauncher.launch("material-xray-backup.json") },
                    ) {
                        Text(stringResource(R.string.settings_export))
                    }
                    OutlinedButton(
                        enabled = !backupBusy,
                        onClick = { importLauncher.launch(arrayOf("application/json")) },
                    ) {
                        Text(stringResource(R.string.settings_import))
                    }
                }
            }
            if (showAdvancedOptions) {
                item(key = "database_reset") {
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
            }

            item(key = "about_header") {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    HorizontalDivider()
                    Text(stringResource(R.string.settings_section_about), style = MaterialTheme.typography.titleMedium)
                }
            }
            item(key = "update_checks") {
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_app_update_checks_title),
                    description = stringResource(R.string.settings_app_update_checks_description),
                    checked = appUpdateChecksEnabled,
                    onCheckedChange = viewModel::setAppUpdateChecksEnabled,
                )
            }
            item(key = "check_for_updates") {
                SettingsActionRow(
                    title = stringResource(R.string.settings_check_for_updates),
                    subtitle = appUpdateCheckDescription,
                    enabled = !appUpdateCheckInProgress,
                    inProgress = appUpdateCheckInProgress,
                    onClick = viewModel::checkForAppUpdate,
                )
            }
            item(key = "licenses") {
                SettingsActionRow(
                    title = stringResource(R.string.settings_open_source_licenses),
                    subtitle = stringResource(R.string.settings_open_source_licenses_description),
                    onClick = { showOpenSourceLicensesDialog = true },
                )
            }
            item(key = "app_version") {
                val appVersion = remember(context) {
                    runCatching {
                        @Suppress("DEPRECATION")
                        context.packageManager.getPackageInfo(context.packageName, 0).versionName
                    }.getOrNull()
                }
                Text(
                    text = if (appVersion == null) {
                        stringResource(R.string.settings_app_version_unknown, stringResource(R.string.app_name))
                    } else {
                        stringResource(R.string.settings_app_version, stringResource(R.string.app_name), appVersion)
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item(key = "xray_version") {
                Text(
                    xrayCoreVersionText,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    SettingsDialogs(
        showRootAccessDeniedDialog = showRootAccessDeniedDialog,
        showNotificationFieldsDialog = showNotificationFieldsDialog,
        showUpdateFrequencyDialog = showUpdateFrequencyDialog,
        showFieldStyleDialog = showFieldStyleDialog,
        showResetDatabaseDialog = showResetDatabaseDialog,
        backupImportSummary = backupImportSummary,
        backupBusy = backupBusy,
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
        onDismissBackupImport = viewModel::dismissBackupImport,
        onConfirmBackupImport = viewModel::confirmBackupImport,
        onFieldEnabledChange = viewModel::setNotificationFieldEnabled,
        onReorderFields = viewModel::setNotificationFieldOrder,
        onUpdateFrequency = viewModel::setNotificationUpdateIntervalMs,
        onSelectFieldStyle = viewModel::setNotificationStyle,
    )
    if (showOpenSourceLicensesDialog) {
        OpenSourceLicensesDialog(onDismiss = { showOpenSourceLicensesDialog = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsLoadingScreen() {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            ScrolledTopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun BackupOperationEventEffect(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, context, resources) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.backupEvents.collect { message ->
                val text = message.detail?.let { detail ->
                    resources.getString(message.messageResId, detail)
                } ?: resources.getString(message.messageResId)
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
private fun SettingsServiceSection(
    rootAvailable: Boolean?,
    rootServiceAvailable: Boolean,
    rootServiceActive: Boolean,
    useRootService: Boolean,
    rootConnectionBackend: RootConnectionBackend,
    tproxyCompatibility: TproxyCompatibility,
    autoConnect: Boolean,
    onUseRootServiceChange: (Boolean) -> Unit,
    onRootConnectionBackendChange: (RootConnectionBackend) -> Unit,
    onRetryTproxyCompatibility: () -> Unit,
    onAutoConnectChange: (Boolean) -> Unit,
) {
    Text(
        text = stringResource(R.string.settings_section_service),
        modifier = Modifier.padding(horizontal = 16.dp),
        style = MaterialTheme.typography.titleMedium,
    )

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_use_root_service),
            description = stringResource(R.string.settings_unavailable).takeIf { rootAvailable == false },
            checked = useRootService && rootAvailable != false,
            onCheckedChange = onUseRootServiceChange,
            enabled = rootServiceAvailable,
            titleColor = if (rootAvailable == false) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )

        if (rootServiceActive) {
            val tproxySelectable = tproxyCompatibility !is TproxyCompatibility.Unsupported
            val supportingText = tproxyCompatibilitySupportingText(tproxyCompatibility)
            SettingsNestedSection(title = stringResource(R.string.settings_root_connection_backend)) {
                RootConnectionBackend.entries.forEach { backend ->
                    val enabled = backend == RootConnectionBackend.Tun || tproxySelectable
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .selectable(
                                selected = backend == rootConnectionBackend,
                                enabled = enabled,
                                role = Role.RadioButton,
                                onClick = { onRootConnectionBackendChange(backend) },
                            )
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(backend.labelResource),
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (enabled) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                stringResource(backend.descriptionResource),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        RadioButton(
                            selected = backend == rootConnectionBackend,
                            onClick = null,
                            enabled = enabled,
                        )
                    }
                }
                supportingText?.let { text ->
                    Text(
                        text,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (tproxyCompatibility is TproxyCompatibility.Unsupported) {
                TextButton(
                    onClick = onRetryTproxyCompatibility,
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.settings_retry_compatibility_check))
                }
            }
        }

        SettingsSwitchRow(
            title = stringResource(R.string.settings_auto_connect_on_boot),
            checked = autoConnect,
            onCheckedChange = onAutoConnectChange,
            enabled = !useRootService || rootServiceActive,
        )
    }
}

@Composable
private fun tproxyCompatibilitySupportingText(compatibility: TproxyCompatibility): String? = when (compatibility) {
    TproxyCompatibility.Unknown,
    TproxyCompatibility.Checking,
    -> null
    is TproxyCompatibility.Supported -> null
    is TproxyCompatibility.Unsupported -> stringResource(
        R.string.settings_tproxy_unsupported,
        stringResource(compatibility.reason.descriptionResource),
    )
}

internal fun shouldShowTunMtu(
    rootServiceActive: Boolean,
    backend: RootConnectionBackend,
): Boolean = !rootServiceActive || backend == RootConnectionBackend.Tun

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
            modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp),
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
private fun NotificationSettingsSection(
    settings: NotificationSettings,
    onEnabledChange: (Boolean) -> Unit,
    onConfigureFields: () -> Unit,
    onConfigureStyle: () -> Unit,
    onConfigureFrequency: () -> Unit,
) {
    val context = LocalContext.current
    var showAccessDialog by remember { mutableStateOf(false) }
    val accessState = rememberSystemState { notificationAccess(it) }
    val access = accessState.value
    val effectiveEnabled = settings.enabled && access == NotificationAccess.Available
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        context.recordNotificationPermissionRequest()
        accessState.refresh()
        if (granted) onEnabledChange(true)
    }

    SettingsNestedSection(title = stringResource(R.string.settings_notification_title)) {
        SettingsSwitchRow(
            title = stringResource(R.string.settings_customize_service_notification),
            checked = effectiveEnabled,
            onCheckedChange = { enabled ->
                when {
                    !enabled -> onEnabledChange(false)
                    access == NotificationAccess.Available -> onEnabledChange(true)
                    else -> showAccessDialog = true
                }
            },
        )

        if (access != NotificationAccess.Available) {
            SettingsActionRow(
                title = stringResource(R.string.settings_notification_permission_unavailable),
                subtitle = stringResource(R.string.settings_notification_permission_unavailable_description),
                onClick = { showAccessDialog = true },
            )
        }

        if (settings.enabled) {
            SettingsActionRow(
                title = stringResource(R.string.settings_configure_notification_fields),
                subtitle = notificationFieldSummary(settings),
                onClick = onConfigureFields,
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_notification_field_style),
                subtitle = stringResource(settings.style.labelResource),
                onClick = onConfigureStyle,
            )
            SettingsActionRow(
                title = stringResource(R.string.settings_notification_update_frequency),
                subtitle = pluralStringResource(
                    R.plurals.settings_notification_update_frequency_summary,
                    settings.updateIntervalMs,
                    settings.updateIntervalMs,
                ),
                onClick = onConfigureFrequency,
            )
        }
    }

    if (showAccessDialog) {
        AlertDialog(
            onDismissRequest = { showAccessDialog = false },
            title = { Text(stringResource(R.string.settings_notification_permission_title)) },
            text = { Text(stringResource(R.string.settings_notification_permission_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showAccessDialog = false
                        when (access) {
                            NotificationAccess.Available -> onEnabledChange(true)
                            NotificationAccess.Requestable,
                            NotificationAccess.Rationale,
                            -> permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)

                            NotificationAccess.SystemSettings -> context.openNotificationSettings()
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (access == NotificationAccess.SystemSettings) {
                                R.string.settings_open_notification_settings
                            } else {
                                R.string.settings_allow_notifications
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccessDialog = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }
}

private fun notificationAccess(context: android.content.Context): NotificationAccess {
    val permissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val permissionGranted = !permissionRequired ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    val activity = context as? Activity
    return resolveNotificationAccess(
        permissionRequired = permissionRequired,
        permissionGranted = permissionGranted,
        notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled(),
        shouldShowRationale = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.POST_NOTIFICATIONS),
        permissionRequested = context.wasNotificationPermissionRequested(),
    )
}

internal fun resolveNotificationAccess(
    permissionRequired: Boolean,
    permissionGranted: Boolean,
    notificationsEnabled: Boolean,
    shouldShowRationale: Boolean,
    permissionRequested: Boolean,
): NotificationAccess = when {
    !permissionRequired || permissionGranted -> {
        if (notificationsEnabled) NotificationAccess.Available else NotificationAccess.SystemSettings
    }
    shouldShowRationale -> NotificationAccess.Rationale
    permissionRequested -> NotificationAccess.SystemSettings
    else -> NotificationAccess.Requestable
}

private fun android.content.Context.openNotificationSettings() {
    startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
    )
}

private fun android.content.Context.recordNotificationPermissionRequest() {
    getSharedPreferences(NOTIFICATION_PERMISSION_PREFS, android.content.Context.MODE_PRIVATE)
        .edit()
        .putBoolean(NOTIFICATION_PERMISSION_REQUESTED, true)
        .apply()
}

private fun android.content.Context.wasNotificationPermissionRequested(): Boolean = getSharedPreferences(
    NOTIFICATION_PERMISSION_PREFS,
    android.content.Context.MODE_PRIVATE,
).getBoolean(NOTIFICATION_PERMISSION_REQUESTED, false)

internal enum class NotificationAccess {
    Available,
    Requestable,
    Rationale,
    SystemSettings,
}

private const val NOTIFICATION_PERMISSION_PREFS = "notification_permission"
private const val NOTIFICATION_PERMISSION_REQUESTED = "requested"

@Composable
private fun SettingsDialogs(
    showRootAccessDeniedDialog: Boolean,
    showNotificationFieldsDialog: Boolean,
    showUpdateFrequencyDialog: Boolean,
    showFieldStyleDialog: Boolean,
    showResetDatabaseDialog: Boolean,
    backupImportSummary: BackupSummary?,
    backupBusy: Boolean,
    notificationSettings: NotificationSettings,
    onDismissRootAccessDenied: () -> Unit,
    onDismissNotificationFields: () -> Unit,
    onDismissUpdateFrequency: () -> Unit,
    onDismissFieldStyle: () -> Unit,
    onDismissResetDatabase: () -> Unit,
    onResetDatabase: () -> Unit,
    onDismissBackupImport: () -> Unit,
    onConfirmBackupImport: () -> Unit,
    onFieldEnabledChange: (NotificationField, Boolean) -> Unit,
    onReorderFields: (List<NotificationField>) -> Unit,
    onUpdateFrequency: (Int) -> Unit,
    onSelectFieldStyle: (NotificationStyle) -> Unit,
) {
    if (backupImportSummary != null) {
        AlertDialog(
            onDismissRequest = onDismissBackupImport,
            title = { Text(stringResource(R.string.settings_backup_import_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_backup_import_confirmation,
                        backupImportSummary.subscriptionCount,
                        backupImportSummary.serverCount,
                        backupImportSummary.appRouteCount,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !backupBusy,
                    onClick = onConfirmBackupImport,
                ) {
                    Text(
                        if (backupBusy) {
                            stringResource(R.string.settings_backup_importing)
                        } else {
                            stringResource(R.string.settings_import)
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !backupBusy,
                    onClick = onDismissBackupImport,
                ) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

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
private fun OpenSourceLicensesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val legalDocuments = legalDocuments()
    var selectedDocumentPath by remember { mutableStateOf(legalDocuments.first().assetPath) }
    val selectedDocument = legalDocuments.first { it.assetPath == selectedDocumentPath }
    val documentText = remember(context, selectedDocument) {
        context.assets.open(selectedDocument.assetPath).bufferedReader().use { it.readText() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_open_source_licenses)) },
        text = {
            Column(
                modifier = Modifier.heightIn(min = 320.dp, max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ReadOnlyDropdownField(
                    label = stringResource(R.string.settings_legal_document),
                    selectedText = selectedDocument.label,
                    options = legalDocuments.map { document ->
                        DropdownOption(value = document.assetPath, label = document.label)
                    },
                    onSelected = { selectedDocumentPath = it },
                )
                key(selectedDocument) {
                    Text(
                        text = documentText,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_done))
            }
        },
    )
}

private data class LegalDocument(
    val label: String,
    val assetPath: String,
)

@Composable
private fun legalDocuments(): List<LegalDocument> = listOf(
    LegalDocument(stringResource(R.string.settings_legal_third_party_notices), "legal/THIRD_PARTY_NOTICES.md"),
    LegalDocument(stringResource(R.string.settings_legal_gpl), "legal/licenses/GPL-3.0-or-later.txt"),
    LegalDocument(stringResource(R.string.settings_legal_mpl), "legal/licenses/MPL-2.0.txt"),
    LegalDocument(stringResource(R.string.settings_legal_apache), "legal/licenses/Apache-2.0.txt"),
    LegalDocument(stringResource(R.string.settings_legal_bsd), "legal/licenses/BSD-3-Clause.txt"),
    LegalDocument(stringResource(R.string.settings_legal_mit), "legal/licenses/MIT.txt"),
    LegalDocument(stringResource(R.string.settings_legal_xray_source), "legal/xray/SOURCE.md"),
    LegalDocument(stringResource(R.string.settings_legal_xray_version), "legal/xray/VERSION"),
    LegalDocument(stringResource(R.string.settings_legal_xray_checksums), "legal/xray/CHECKSUMS.sha256"),
)

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
        supportingText = { Text(stringResource(R.string.settings_tun_interface_name_automatic)) },
    )
    if (hasTunNameChanges) {
        Button(onClick = onSave) { Text(stringResource(R.string.settings_save)) }
    }
}

@Composable
private fun TunMtuSetting(
    visible: Boolean,
    value: String,
    onValueChange: (String) -> Unit,
    isValid: Boolean,
    hasChanges: Boolean,
    onSave: () -> Unit,
) {
    if (!visible) return
    AdvancedIntegerSetting(
        value = value,
        onValueChange = onValueChange,
        label = stringResource(R.string.settings_tun_mtu_label),
        supportingText = stringResource(
            R.string.settings_tun_mtu_supporting_text,
            XrayRuntimeSettings.MIN_TUN_MTU,
            XrayRuntimeSettings.MAX_TUN_MTU,
            XrayRuntimeSettings.DEFAULT_TUN_MTU,
        ),
        suffix = stringResource(R.string.settings_bytes_abbreviation),
        isValid = isValid,
        hasChanges = hasChanges,
        onSave = onSave,
    )
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
    subtitle: String? = null,
    onClick: () -> Unit,
    enabled: Boolean = true,
    inProgress: Boolean = false,
) {
    val contentEnabled = enabled || inProgress
    val titleColor = if (contentEnabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    val subtitleColor = if (contentEnabled) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = titleColor)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = subtitleColor,
                )
            }
        }
        if (inProgress) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        } else {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = subtitleColor,
            )
        }
    }
}

@Composable
private fun appUpdateCheckDescription(status: AppUpdateCheckStatus): String = when (status) {
    AppUpdateCheckStatus.Starting -> stringResource(R.string.settings_update_check_starting)
    is AppUpdateCheckStatus.Fetching -> stringResource(R.string.settings_update_check_fetching, status.url)
    is AppUpdateCheckStatus.RetryingAfterHttpError -> stringResource(
        R.string.settings_update_check_retry_http,
        status.url,
        status.statusCode,
        status.nextUrl,
    )
    is AppUpdateCheckStatus.RetryingAfterConnectionFailure -> stringResource(
        R.string.settings_update_check_retry_connection,
        status.url,
        status.nextUrl,
    )
    is AppUpdateCheckStatus.RetryingAfterInvalidResponse -> stringResource(
        R.string.settings_update_check_retry_invalid_response,
        status.url,
        status.statusCode,
        status.nextUrl,
    )
    is AppUpdateCheckStatus.ReleaseReceived -> stringResource(
        R.string.settings_update_check_comparing,
        status.url,
        status.statusCode,
    )
    AppUpdateCheckStatus.UpToDate -> stringResource(R.string.settings_update_check_up_to_date)
    is AppUpdateCheckStatus.UpdateAvailable -> stringResource(
        R.string.settings_update_check_available,
        status.version,
    )
    AppUpdateCheckStatus.Failed -> stringResource(R.string.settings_update_check_failed)
}

@Composable
private fun AppLanguageSetting() {
    val context = LocalContext.current
    val resources = LocalResources.current
    var showDialog by remember { mutableStateOf(false) }
    val supportedLocales = remember(resources) { resources.loadSupportedAppLocales() }
    val selectedLocale = AppCompatDelegate.getApplicationLocales()[0]
    val selectedLanguageName = selectedLocale?.nativeDisplayName()
        ?: stringResource(R.string.settings_app_language_system_default)

    SettingsActionRow(
        title = stringResource(R.string.settings_app_language_title),
        subtitle = selectedLanguageName,
        onClick = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.startActivity(
                    Intent(
                        Settings.ACTION_APP_LOCALE_SETTINGS,
                        Uri.parse("package:${context.packageName}"),
                    ),
                )
            } else {
                showDialog = true
            }
        },
    )

    if (showDialog) {
        AppLanguageDialog(
            supportedLocales = supportedLocales,
            selectedLocale = selectedLocale,
            onDismiss = { showDialog = false },
            onSelect = { locale ->
                showDialog = false
                setAppLocales(
                    if (locale == null) {
                        LocaleListCompat.getEmptyLocaleList()
                    } else {
                        LocaleListCompat.create(locale)
                    },
                )
            },
        )
    }
}

@Composable
private fun AppLanguageDialog(
    supportedLocales: List<Locale>,
    selectedLocale: Locale?,
    onDismiss: () -> Unit,
    onSelect: (Locale?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_app_language_dialog_title)) },
        text = {
            Column {
                AppLanguageOption(
                    label = stringResource(R.string.settings_app_language_system_default),
                    selected = selectedLocale == null,
                    onClick = { onSelect(null) },
                )
                supportedLocales.forEach { locale ->
                    AppLanguageOption(
                        label = locale.nativeDisplayName(),
                        selected = locale == selectedLocale,
                        onClick = { onSelect(locale) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}

@Composable
private fun AppLanguageOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(
                selected = selected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun Resources.loadSupportedAppLocales(): List<Locale> {
    val parser = getXml(R.xml.locales_config)
    return try {
        buildList {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "locale") {
                    parser.getAttributeValue(ANDROID_RESOURCE_NAMESPACE, "name")
                        ?.let(Locale::forLanguageTag)
                        ?.takeUnless { it.language.isEmpty() }
                        ?.let(::add)
                }
                event = parser.next()
            }
        }
    } finally {
        parser.close()
    }
}

private fun Locale.nativeDisplayName(): String = getDisplayName(this).replaceFirstChar { it.titlecase() }

private const val ANDROID_RESOURCE_NAMESPACE = "http://schemas.android.com/apk/res/android"

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
