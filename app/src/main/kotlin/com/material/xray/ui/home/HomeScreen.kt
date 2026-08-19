package com.material.xray.ui.home

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateBounds
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LookaheadScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.material.xray.R
import com.material.xray.data.db.entity.ServerEntity
import com.material.xray.data.db.entity.SubscriptionEntity
import com.material.xray.data.repository.toSubscriptionAppRouting
import com.material.xray.data.repository.toSubscriptionRouting
import com.material.xray.model.AppUpdate
import com.material.xray.model.ConnectionProgress
import com.material.xray.model.ConnectionState
import com.material.xray.model.PingMethod
import com.material.xray.model.RoutingPolicyControl
import com.material.xray.model.ServerConfig
import com.material.xray.model.SubscriptionUserAgentMode
import com.material.xray.service.AppUpdateInstallProgress
import com.material.xray.service.AppUpdateInstallStage
import com.material.xray.service.ConnectionEvent
import com.material.xray.ui.components.DropdownOption
import com.material.xray.ui.components.ReadOnlyDropdownField
import com.material.xray.ui.components.ScrolledTopAppBar
import com.material.xray.ui.components.SelectableOptionRow
import com.material.xray.ui.components.rememberSystemState
import com.material.xray.ui.text.descriptionResource
import com.material.xray.ui.text.labelResource
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(viewModel: HomeViewModel = hiltViewModel()) {
    val uiState = collectHomeUiState(viewModel)
    val connectionUiState = buildConnectionUiState(
        connectionState = uiState.connectionState,
        selectedServer = uiState.selectedServer,
        alwaysOnVpn = uiState.alwaysOnVpn,
    )

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var keepQrScannerDialog by remember { mutableStateOf(false) }
    var editingSubscriptionId by rememberSaveable { mutableStateOf<Long?>(null) }
    val editingSubscription = uiState.subscriptions?.find { it.id == editingSubscriptionId }
    // Drop a parked edit id once the loaded list no longer contains it, so a later subscription
    // that happens to reuse the row id does not spontaneously reopen the edit dialog. A null list
    // means the data has not loaded yet and cannot say anything about the id.
    LaunchedEffect(uiState.subscriptions, editingSubscriptionId) {
        val id = editingSubscriptionId ?: return@LaunchedEffect
        val subscriptions = uiState.subscriptions ?: return@LaunchedEffect
        if (subscriptions.none { it.id == id }) {
            editingSubscriptionId = null
        }
    }
    var removeSubscriptionRequest by remember { mutableStateOf<Pair<SubscriptionEntity, Int>?>(null) }
    var showRootFallbackDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val unableToFetchLinkText = stringResource(R.string.home_unable_to_fetch_link)
    val clipboardConfigLabel = stringResource(R.string.home_clipboard_xray_config_label)
    val lifecycleOwner = LocalLifecycleOwner.current
    val topAppBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior(rememberTopAppBarState())
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.connect()
        }
    }
    val openQrScanner = QrScannerPermissionGate { showQrScanner = true }
    val startRootlessConnection = {
        val vpnPermissionIntent = VpnService.prepare(context)
        if (vpnPermissionIntent != null) {
            vpnPermissionLauncher.launch(vpnPermissionIntent)
        } else {
            viewModel.connect()
        }
    }
    val pasteFromClipboard = {
        val link = context.clipboardText()
        if (link == null) {
            Toast.makeText(context, unableToFetchLinkText, Toast.LENGTH_SHORT).show()
        } else {
            viewModel.addLink(link)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.refreshTunnelInterfaceState()
        viewModel.checkForAppUpdateIfDue()
    }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.connectionEvents.collect { event ->
                when (event) {
                    ConnectionEvent.RootUnavailableFallback -> showRootFallbackDialog = true
                }
            }
        }
    }

    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.uiEvents.collect { event ->
                when (event) {
                    is HomeUiEvent.Toast -> Toast.makeText(context, event.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    LaunchedEffect(showQrScanner) {
        if (showQrScanner) {
            keepQrScannerDialog = true
        } else {
            delay(QR_SCANNER_TRANSITION_MS.toLong())
            keepQrScannerDialog = false
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.refreshTunnelInterfaceState()
                    viewModel.resumePendingAppUpdateInstall()
                }
                Lifecycle.Event.ON_STOP -> {
                    showQrScanner = false
                    keepQrScannerDialog = false
                    viewModel.onHidden()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onHidden()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection),
        contentWindowInsets = WindowInsets(0.dp),
        topBar = {
            ScrolledTopAppBar(
                title = stringResource(R.string.app_name),
                scrollBehavior = topAppBarScrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                ConnectionPanel(
                    connectionState = uiState.connectionState,
                    connectionProgress = uiState.connectionProgress,
                    showProgressDetails = uiState.showAdvancedOptions,
                    selectedServerName = connectionUiState.displayServerName,
                    activeBalancerServer = uiState.activeBalancerServer,
                    buttonColor = connectionUiState.buttonColor,
                    isConnected = connectionUiState.isConnected,
                    isRestartRequired = connectionUiState.isRestartRequired,
                    isInterfaceBusy = connectionUiState.isInterfaceBusy,
                    isTransitioning = connectionUiState.isTransitioning,
                    isAlwaysOnVpn = connectionUiState.isAlwaysOnVpn,
                    canStart = uiState.selectedServer != null,
                    onClick = {
                        connectionUiState.handleClick(
                            context = context,
                            useRootService = uiState.useRootService,
                            disconnect = viewModel::disconnect,
                            connectRoot = viewModel::connect,
                            connectVpn = startRootlessConnection,
                        )
                    },
                    onViewConfig = { viewModel.showRunningConfig() },
                )
            }

            uiState.availableUpdate?.let { update ->
                item(contentType = "appUpdate") {
                    AppUpdateBanner(
                        update = update,
                        installProgress = uiState.appUpdateInstallProgress,
                        onInstall = { viewModel.installAppUpdate(update) },
                    )
                }
            }

            if (uiState.isRefreshing) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            val errorState = uiState.connectionState as? ConnectionState.Error
            if (errorState != null) {
                item {
                    ErrorCard(message = errorState.message)
                }
            }

            val subscriptions = uiState.subscriptions
            when {
                // Not loaded yet. The splash screen normally covers this state on cold start; if
                // loading is unusually slow, a blank list beats a misleading empty-state card.
                subscriptions == null -> Unit
                subscriptions.isEmpty() -> item {
                    EmptySubscriptionsCard(
                        onPasteFromClipboard = pasteFromClipboard,
                        onScanQrCode = openQrScanner,
                        onAddManually = { showAddDialog = true },
                    )
                }
                else -> {
                    items(
                        items = subscriptions,
                        key = { it.id },
                        contentType = { "subscription" },
                    ) { subscription ->
                        val servers = uiState.serversBySubscription[subscription.id].orEmpty()
                        SubscriptionCard(
                            subscription = subscription,
                            servers = servers,
                            selectedServerId = uiState.selectedServerId,
                            defaultPingMethod = uiState.defaultPingMethod,
                            canApplyRouting = uiState.routingPolicyControl == RoutingPolicyControl.User &&
                                (subscription.toSubscriptionAppRouting() != null || subscription.toSubscriptionRouting() != null),
                            onDelete = {
                                if (servers.isEmpty()) {
                                    viewModel.deleteSubscription(subscription)
                                } else {
                                    removeSubscriptionRequest = subscription to servers.size
                                }
                            },
                            onEdit = { editingSubscriptionId = subscription.id },
                            onRefresh = { viewModel.refreshSubscription(subscription) },
                            onTestAll = { viewModel.testSubscriptionLatencies(subscription) },
                            onDefaultPingMethodSelected = { viewModel.setDefaultPingMethod(it) },
                            onApplyRouting = { viewModel.requestApplySubscriptionRouting(subscription) },
                            onDescriptionHiddenChange = { hidden ->
                                viewModel.setSubscriptionDescriptionHidden(subscription.id, hidden)
                            },
                            onServerSelected = { viewModel.selectServer(it) },
                            onTestLatency = { viewModel.testLatency(it) },
                        )
                    }
                    item(contentType = "addSubscription") {
                        AddSubscriptionActionButton(
                            modifier = Modifier.fillMaxWidth(),
                            onPasteFromClipboard = pasteFromClipboard,
                            onScanQrCode = openQrScanner,
                            onAddManually = { showAddDialog = true },
                        )
                    }
                }
            }
        }
    }

    AddSubscriptionDialogHost(
        visible = showAddDialog,
        onDismiss = { showAddDialog = false },
        onConfirm = { name, url, preferJson, userAgentMode, customUserAgent, customHeaders ->
            viewModel.addSubscription(name, url, preferJson, userAgentMode, customUserAgent, customHeaders)
            showAddDialog = false
        },
    )
    QrScannerDialogHost(
        keepDialog = keepQrScannerDialog,
        visible = showQrScanner,
        onVisibleChange = { showQrScanner = it },
        onLinkScanned = { link ->
            val trimmed = link.trim()
            if (trimmed.isEmpty()) {
                Toast.makeText(context, unableToFetchLinkText, Toast.LENGTH_SHORT).show()
            } else {
                viewModel.addLink(trimmed)
            }
        },
    )
    ApplySubscriptionRoutingDialogHost(
        visible = uiState.pendingSubscriptionRouting != null,
        onDismiss = viewModel::dismissPendingSubscriptionRouting,
        onConfirm = viewModel::applyPendingSubscriptionRouting,
    )
    RootFallbackDialogHost(
        visible = showRootFallbackDialog,
        onDismiss = { showRootFallbackDialog = false },
        onConfirm = {
            showRootFallbackDialog = false
            startRootlessConnection()
        },
    )
    RemoveSubscriptionDialogHost(
        request = removeSubscriptionRequest,
        onDismiss = { removeSubscriptionRequest = null },
        onConfirm = { subscription ->
            viewModel.deleteSubscription(subscription)
            removeSubscriptionRequest = null
        },
    )
    EditSubscriptionDialogHost(
        subscription = editingSubscription,
        onDismiss = { editingSubscriptionId = null },
        onConfirm = { subscription, name, url, preferJson, autoUpdateIntervalHours, userAgentMode, customUserAgent, customHeaders ->
            viewModel.updateSubscription(
                subscription,
                name,
                url,
                preferJson,
                autoUpdateIntervalHours,
                userAgentMode,
                customUserAgent,
                customHeaders,
            )
            editingSubscriptionId = null
        },
    )
    RawConfigDialogHost(
        config = uiState.runningConfig,
        onDismiss = viewModel::dismissRunningConfig,
        onCopy = {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard?.setPrimaryClip(ClipData.newPlainText(clipboardConfigLabel, uiState.runningConfig.orEmpty()))
        },
    )
    InstallPermissionRationaleDialogHost(
        visible = uiState.showInstallPermissionRationale,
        onDismiss = viewModel::dismissInstallPermissionRationale,
        onConfirm = viewModel::confirmInstallPermissionRationale,
    )
}

@Composable
private fun AddSubscriptionDialogHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean, SubscriptionUserAgentMode, String, String) -> Unit,
) {
    if (!visible) return

    AddSubscriptionDialog(
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
private fun QrScannerDialogHost(
    keepDialog: Boolean,
    visible: Boolean,
    onVisibleChange: (Boolean) -> Unit,
    onLinkScanned: (String) -> Unit,
) {
    if (!keepDialog) return

    Dialog(
        onDismissRequest = { onVisibleChange(false) },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(durationMillis = QR_SCANNER_TRANSITION_MS)),
            exit = fadeOut(animationSpec = tween(durationMillis = QR_SCANNER_TRANSITION_MS)),
        ) {
            QrScannerOverlay(
                onQrCodeScanned = { link ->
                    onVisibleChange(false)
                    onLinkScanned(link)
                },
                onClose = { onVisibleChange(false) },
            )
        }
    }
}

@Composable
private fun QrScannerPermissionGate(onGranted: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var promptAccess by remember { mutableStateOf<CameraPermissionAccess?>(null) }
    val accessState = rememberSystemState { cameraPermissionAccess(it) }
    val access = accessState.value
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        context.recordCameraPermissionRequest()
        accessState.refresh()
        if (granted) {
            promptAccess = null
            onGranted()
        } else {
            promptAccess = cameraPermissionAccess(context)
        }
    }

    promptAccess?.let { requestedAccess ->
        AlertDialog(
            onDismissRequest = { promptAccess = null },
            title = { Text(stringResource(R.string.home_camera_permission_title)) },
            text = { Text(stringResource(R.string.home_camera_permission_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        promptAccess = null
                        if (requestedAccess == CameraPermissionAccess.SystemSettings) {
                            context.openAppSettings()
                        } else {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                ) {
                    Text(
                        stringResource(
                            if (requestedAccess == CameraPermissionAccess.SystemSettings) {
                                R.string.home_open_app_settings
                            } else {
                                R.string.home_allow_camera
                            },
                        ),
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { promptAccess = null }) {
                    Text(stringResource(R.string.home_action_cancel))
                }
            },
        )
    }

    return {
        when (access) {
            CameraPermissionAccess.Granted -> onGranted()
            else -> promptAccess = access
        }
    }
}

private fun cameraPermissionAccess(context: Context): CameraPermissionAccess {
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    val activity = context as? android.app.Activity
    return resolveCameraPermissionAccess(
        granted = granted,
        shouldShowRationale = activity != null &&
            ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA),
        permissionRequested = context.wasCameraPermissionRequested(),
    )
}

internal fun resolveCameraPermissionAccess(
    granted: Boolean,
    shouldShowRationale: Boolean,
    permissionRequested: Boolean,
): CameraPermissionAccess = when {
    granted -> CameraPermissionAccess.Granted
    shouldShowRationale -> CameraPermissionAccess.Rationale
    permissionRequested -> CameraPermissionAccess.SystemSettings
    else -> CameraPermissionAccess.Requestable
}

private fun Context.openAppSettings() {
    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
}

private fun Context.recordCameraPermissionRequest() {
    getSharedPreferences(CAMERA_PERMISSION_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(CAMERA_PERMISSION_REQUESTED, true)
        .apply()
}

private fun Context.wasCameraPermissionRequested(): Boolean = getSharedPreferences(
    CAMERA_PERMISSION_PREFS,
    Context.MODE_PRIVATE,
).getBoolean(CAMERA_PERMISSION_REQUESTED, false)

internal enum class CameraPermissionAccess {
    Granted,
    Requestable,
    Rationale,
    SystemSettings,
}

@Composable
private fun ApplySubscriptionRoutingDialogHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    ApplySubscriptionRoutingDialog(
        onDismiss = onDismiss,
        onConfirm = onConfirm,
    )
}

@Composable
private fun RootFallbackDialogHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(stringResource(R.string.home_root_fallback_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.home_action_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_action_cancel))
            }
        },
    )
}

@Composable
private fun RemoveSubscriptionDialogHost(
    request: Pair<SubscriptionEntity, Int>?,
    onDismiss: () -> Unit,
    onConfirm: (SubscriptionEntity) -> Unit,
) {
    val (subscription, serverCount) = request ?: return

    RemoveSubscriptionDialog(
        serverCount = serverCount,
        onDismiss = onDismiss,
        onConfirm = { onConfirm(subscription) },
    )
}

@Composable
private fun EditSubscriptionDialogHost(
    subscription: SubscriptionEntity?,
    onDismiss: () -> Unit,
    onConfirm: (SubscriptionEntity, String, String, Boolean, Int, SubscriptionUserAgentMode, String, String) -> Unit,
) {
    subscription ?: return

    EditSubscriptionDialog(
        subscription = subscription,
        onDismiss = onDismiss,
        onConfirm = { name, url, preferJson, autoUpdateIntervalHours, userAgentMode, customUserAgent, customHeaders ->
            onConfirm(subscription, name, url, preferJson, autoUpdateIntervalHours, userAgentMode, customUserAgent, customHeaders)
        },
    )
}

@Composable
private fun RawConfigDialogHost(
    config: String?,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    if (config == null) return

    RawConfigDialog(
        config = config,
        onDismiss = onDismiss,
        onCopy = onCopy,
    )
}

@Composable
private fun InstallPermissionRationaleDialogHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!visible) return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_app_update_permission_title)) },
        text = { Text(stringResource(R.string.home_app_update_permission_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.home_app_update_permission_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_app_update_permission_not_now))
            }
        },
    )
}

@Composable
private fun collectHomeUiState(viewModel: HomeViewModel): HomeUiState {
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    val connectionProgress by viewModel.connectionProgress.collectAsStateWithLifecycle()
    val alwaysOnVpn by viewModel.alwaysOnVpn.collectAsStateWithLifecycle()
    val selectedServer by viewModel.selectedServer.collectAsStateWithLifecycle()
    val activeBalancerServer by viewModel.activeBalancerServer.collectAsStateWithLifecycle()
    val selectedServerId by viewModel.selectedServerId.collectAsStateWithLifecycle()
    val useRootService by viewModel.useRootService.collectAsStateWithLifecycle()
    val showAdvancedOptions by viewModel.showAdvancedOptions.collectAsStateWithLifecycle()
    val subscriptions by viewModel.subscriptions.collectAsStateWithLifecycle()
    val serversBySubscription by viewModel.serversBySubscription.collectAsStateWithLifecycle()
    val isRefreshing by viewModel.isRefreshing.collectAsStateWithLifecycle()
    val runningConfig by viewModel.runningConfig.collectAsStateWithLifecycle()
    val defaultPingMethod by viewModel.defaultPingMethod.collectAsStateWithLifecycle()
    val routingPolicyControl by viewModel.routingPolicyControl.collectAsStateWithLifecycle()
    val pendingSubscriptionRouting by viewModel.pendingSubscriptionRouting.collectAsStateWithLifecycle()
    val availableUpdate by viewModel.availableUpdate.collectAsStateWithLifecycle()
    val appUpdateInstallProgress by viewModel.appUpdateInstallProgress.collectAsStateWithLifecycle()
    val showInstallPermissionRationale by viewModel.showInstallPermissionRationale.collectAsStateWithLifecycle()

    return HomeUiState(
        connectionState = connectionState,
        connectionProgress = connectionProgress,
        alwaysOnVpn = alwaysOnVpn,
        selectedServer = selectedServer,
        activeBalancerServer = activeBalancerServer,
        selectedServerId = selectedServerId,
        useRootService = useRootService,
        showAdvancedOptions = showAdvancedOptions,
        subscriptions = subscriptions,
        serversBySubscription = serversBySubscription,
        isRefreshing = isRefreshing,
        runningConfig = runningConfig,
        defaultPingMethod = defaultPingMethod,
        routingPolicyControl = routingPolicyControl,
        pendingSubscriptionRouting = pendingSubscriptionRouting,
        availableUpdate = availableUpdate,
        appUpdateInstallProgress = appUpdateInstallProgress,
        showInstallPermissionRationale = showInstallPermissionRationale,
    )
}

@Composable
private fun buildConnectionUiState(
    connectionState: ConnectionState,
    selectedServer: ServerConfig?,
    alwaysOnVpn: Boolean,
): ConnectionUiState {
    val isConnected = connectionState is ConnectionState.Connected
    val isRestartRequired = connectionState is ConnectionState.RestartRequired
    val isInterfaceBusy = connectionState is ConnectionState.InterfaceBusy
    val isTransitioning = connectionState is ConnectionState.Connecting ||
        connectionState is ConnectionState.ApplyingRoutingChanges ||
        connectionState is ConnectionState.UpdatingRoutingData ||
        connectionState is ConnectionState.Disconnecting
    val selectedServerName = selectedServer?.name ?: stringResource(R.string.home_no_server_selected)

    return ConnectionUiState(
        isConnected = isConnected,
        isRestartRequired = isRestartRequired,
        isInterfaceBusy = isInterfaceBusy,
        isTransitioning = isTransitioning,
        isAlwaysOnVpn = alwaysOnVpn,
        buttonColor = when {
            isConnected && !alwaysOnVpn || isRestartRequired || isInterfaceBusy -> MaterialTheme.colorScheme.error
            isTransitioning -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        displayServerName = (connectionState as? ConnectionState.Connected)?.serverName ?: selectedServerName,
    )
}

private data class HomeUiState(
    val connectionState: ConnectionState,
    val connectionProgress: ConnectionProgress?,
    val alwaysOnVpn: Boolean,
    val selectedServer: ServerConfig?,
    val activeBalancerServer: ActiveBalancerServerState?,
    val selectedServerId: Long,
    val useRootService: Boolean,
    val showAdvancedOptions: Boolean,
    /** `null` until the home data snapshot has loaded; distinct from a loaded empty list. */
    val subscriptions: List<SubscriptionEntity>?,
    val serversBySubscription: Map<Long, List<ServerListItem>>,
    val isRefreshing: Boolean,
    val runningConfig: String?,
    val defaultPingMethod: PingMethod,
    val routingPolicyControl: RoutingPolicyControl,
    val pendingSubscriptionRouting: SubscriptionRoutingData?,
    val availableUpdate: AppUpdate?,
    val appUpdateInstallProgress: AppUpdateInstallProgress?,
    val showInstallPermissionRationale: Boolean,
)

private data class ConnectionUiState(
    val isConnected: Boolean,
    val isRestartRequired: Boolean,
    val isInterfaceBusy: Boolean,
    val isTransitioning: Boolean,
    val isAlwaysOnVpn: Boolean,
    val buttonColor: Color,
    val displayServerName: String,
)

@Composable
private fun ConnectionPanel(
    connectionState: ConnectionState,
    connectionProgress: ConnectionProgress?,
    showProgressDetails: Boolean,
    selectedServerName: String,
    activeBalancerServer: ActiveBalancerServerState?,
    buttonColor: Color,
    isConnected: Boolean,
    isRestartRequired: Boolean,
    isInterfaceBusy: Boolean,
    isTransitioning: Boolean,
    isAlwaysOnVpn: Boolean,
    canStart: Boolean,
    onClick: () -> Unit,
    onViewConfig: () -> Unit,
) {
    val buttonEnabled = (canStart || isConnected || isRestartRequired || isInterfaceBusy) && !isTransitioning
    val activeBalancerLabel = activeBalancerServer?.let { balancerServerLabel(it) }
    val containerColor = if (buttonEnabled) {
        buttonColor.copy(alpha = 0.15f)
    } else {
        buttonColor.copy(alpha = 0.10f)
    }
    val contentColor = if (buttonEnabled) {
        buttonColor
    } else {
        buttonColor.copy(alpha = 0.75f)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = when (connectionState) {
                is ConnectionState.Connected -> stringResource(R.string.home_connection_connected)
                is ConnectionState.Connecting -> stringResource(R.string.home_connection_connecting)
                ConnectionState.ApplyingRoutingChanges -> stringResource(R.string.home_connection_applying_routing)
                ConnectionState.UpdatingRoutingData -> stringResource(R.string.home_connection_updating_routing)
                is ConnectionState.RestartRequired -> stringResource(R.string.home_connection_restart_required)
                is ConnectionState.InterfaceBusy -> stringResource(R.string.home_connection_interface_busy)
                is ConnectionState.Disconnecting -> stringResource(R.string.home_connection_disconnecting)
                is ConnectionState.Error -> stringResource(R.string.home_connection_error)
                ConnectionState.Disconnected -> stringResource(R.string.home_connection_disconnected)
            },
            style = MaterialTheme.typography.titleLarge,
            color = when {
                isConnected -> MaterialTheme.colorScheme.primary
                isRestartRequired || isInterfaceBusy -> MaterialTheme.colorScheme.error
                connectionState is ConnectionState.Error -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurface
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = when {
                isInterfaceBusy -> stringResource(R.string.home_connection_interface_busy_detail)
                isRestartRequired -> stringResource(R.string.home_connection_restart_required_detail)
                else -> selectedServerName
            },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = if (isRestartRequired || isInterfaceBusy) 4 else 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Box(
            modifier = Modifier.height(with(LocalDensity.current) { MaterialTheme.typography.bodySmall.lineHeight.toDp() }),
            contentAlignment = Alignment.Center,
        ) {
            when {
                showProgressDetails && connectionProgress != null -> Text(
                    text = connectionProgressText(connectionProgress),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                connectionState is ConnectionState.Connected -> CoreUptime(startTime = connectionState.startTime)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Surface(
            color = containerColor,
            contentColor = contentColor,
            shape = CircleShape,
            modifier = Modifier
                .size(124.dp)
                .clip(CircleShape)
                .combinedClickable(
                    enabled = buttonEnabled,
                    onClick = onClick,
                    onLongClick = {
                        if (isConnected) {
                            onViewConfig()
                        }
                    },
                ),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                if (isTransitioning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(54.dp),
                        strokeWidth = 4.dp,
                        color = buttonColor,
                    )
                } else {
                    Text(
                        text = stringResource(
                            connectionActionLabel(
                                isConnected = isConnected,
                                isAlwaysOnVpn = isAlwaysOnVpn,
                                isRestartRequired = isRestartRequired,
                                isInterfaceBusy = isInterfaceBusy,
                            ),
                        ),
                        modifier = Modifier.padding(horizontal = 8.dp),
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        color = contentColor,
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 10.sp,
                            maxFontSize = MaterialTheme.typography.titleLarge.fontSize,
                            stepSize = 1.sp,
                        ),
                    )
                }
            }
        }

        AnimatedVisibility(visible = isConnected && activeBalancerLabel != null) {
            Text(
                text = activeBalancerLabel.orEmpty(),
                modifier = Modifier.padding(top = 12.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
    }
}

@Composable
private fun connectionProgressText(progress: ConnectionProgress): String = when (progress) {
    ConnectionProgress.PreparingRuntime -> stringResource(R.string.home_connection_progress_preparing_runtime)
    ConnectionProgress.PreparingCore -> stringResource(R.string.home_connection_progress_preparing_core)
    ConnectionProgress.UpdatingRoutingData -> stringResource(R.string.home_connection_progress_updating_routing_data)
    ConnectionProgress.ResolvingEntryServer -> stringResource(R.string.home_connection_progress_resolving_entry_server)
    ConnectionProgress.GeneratingConfiguration -> stringResource(R.string.home_connection_progress_generating_configuration)
    ConnectionProgress.StartingCore -> stringResource(R.string.home_connection_progress_starting_core)
    ConnectionProgress.ConfiguringTunnel -> stringResource(R.string.home_connection_progress_configuring_tunnel)
    ConnectionProgress.ConfiguringRouting -> stringResource(R.string.home_connection_progress_configuring_routing)
    ConnectionProgress.WaitingForCore -> stringResource(R.string.home_connection_progress_waiting_for_core)
    ConnectionProgress.StoppingCore -> stringResource(R.string.home_connection_progress_stopping_core)
    ConnectionProgress.CleaningRuntime -> stringResource(R.string.home_connection_progress_cleaning_runtime)
    ConnectionProgress.InspectingSavedRuntime -> stringResource(R.string.home_connection_progress_inspecting_saved_runtime)
    ConnectionProgress.VerifyingRuntime -> stringResource(R.string.home_connection_progress_verifying_runtime)
    ConnectionProgress.RestoringControlApi -> stringResource(R.string.home_connection_progress_restoring_control_api)
    ConnectionProgress.UpdatingNetworkRoute -> stringResource(R.string.home_connection_progress_updating_network_route)
    ConnectionProgress.UpdatingAppRouting -> stringResource(R.string.home_connection_progress_updating_app_routing)
}

@Composable
private fun CoreUptime(startTime: Long) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var currentTime by remember(startTime) { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(startTime, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                currentTime = System.currentTimeMillis()
                delay(CORE_UPTIME_REFRESH_INTERVAL_MS)
            }
        }
    }

    Text(
        text = formatCoreUptime(currentTime - startTime),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

internal fun formatCoreUptime(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis.coerceAtLeast(0L) / 1_000L
    val days = totalSeconds / 86_400L
    val hours = totalSeconds % 86_400L / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return when {
        days > 0L -> String.format(Locale.ROOT, "%02d:%02d:%02d:%02d", days, hours, minutes, seconds)
        hours > 0L -> String.format(Locale.ROOT, "%02d:%02d:%02d", hours, minutes, seconds)
        else -> String.format(Locale.ROOT, "%02d:%02d", minutes, seconds)
    }
}

private fun ConnectionUiState.handleClick(
    context: Context,
    useRootService: Boolean,
    disconnect: () -> Unit,
    connectRoot: () -> Unit,
    connectVpn: () -> Unit,
) {
    when {
        isConnected && isAlwaysOnVpn -> context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
        isConnected -> disconnect()
        !isTransitioning && useRootService -> connectRoot()
        !isTransitioning -> connectVpn()
    }
}

@StringRes
private fun connectionActionLabel(
    isConnected: Boolean,
    isAlwaysOnVpn: Boolean,
    isRestartRequired: Boolean,
    isInterfaceBusy: Boolean,
): Int = when {
    isConnected && isAlwaysOnVpn -> R.string.home_action_always_on
    isConnected -> R.string.home_action_stop
    isRestartRequired || isInterfaceBusy -> R.string.home_action_restart
    else -> R.string.home_action_start
}

@Composable
private fun balancerServerLabel(server: ActiveBalancerServerState): String = if (server.latencyMs == null) {
    stringResource(R.string.home_balancer_active_server, server.title)
} else {
    stringResource(R.string.home_balancer_active_server_with_latency, server.title, server.latencyMs)
}

@Composable
private fun RawConfigDialog(
    config: String,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onCopy) {
                Text(stringResource(R.string.home_action_copy))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_action_close))
            }
        },
        title = { Text(stringResource(R.string.home_active_xray_config_title)) },
        text = {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.small,
            ) {
                SelectionContainer {
                    Text(
                        text = config,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 160.dp, max = 420.dp)
                            .verticalScroll(scrollState)
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}

@Composable
private fun ErrorCard(message: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun AppUpdateBanner(
    update: AppUpdate,
    installProgress: AppUpdateInstallProgress?,
    onInstall: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = installProgress == null, onClick = onInstall),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.SystemUpdate, contentDescription = null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_app_update_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.home_app_update_message, update.tagName),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (installProgress != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = appUpdateInstallProgressText(installProgress),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val fraction = installProgress.fraction
                    if (fraction == null) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(
                            progress = { fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun appUpdateInstallProgressText(progress: AppUpdateInstallProgress): String = when (progress.stage) {
    AppUpdateInstallStage.ResolvingRelease -> stringResource(R.string.home_app_update_progress_resolving)
    AppUpdateInstallStage.Connecting -> stringResource(R.string.home_app_update_progress_connecting)
    AppUpdateInstallStage.Downloading -> progress.fraction?.let { fraction ->
        stringResource(R.string.home_app_update_progress_downloading_percent, (fraction * 100).roundToInt())
    } ?: stringResource(R.string.home_app_update_progress_downloading)
    AppUpdateInstallStage.Verifying -> stringResource(R.string.home_app_update_progress_verifying)
    AppUpdateInstallStage.PreparingInstallation -> stringResource(R.string.home_app_update_progress_preparing)
    AppUpdateInstallStage.OpeningInstaller -> stringResource(R.string.home_app_update_progress_opening_installer)
    AppUpdateInstallStage.InstallingWithRoot -> stringResource(R.string.home_app_update_progress_installing_root)
}

@Composable
private fun EmptySubscriptionsCard(
    onPasteFromClipboard: () -> Unit,
    onScanQrCode: () -> Unit,
    onAddManually: () -> Unit,
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.home_no_subscriptions_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(R.string.home_no_subscriptions_message),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            AddSubscriptionActionButton(
                onPasteFromClipboard = onPasteFromClipboard,
                onScanQrCode = onScanQrCode,
                onAddManually = onAddManually,
            )
        }
    }
}

@Composable
private fun AddSubscriptionActionButton(
    modifier: Modifier = Modifier,
    onPasteFromClipboard: () -> Unit,
    onScanQrCode: () -> Unit,
    onAddManually: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.home_add_server_or_subscription))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_paste_from_clipboard)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_content_paste_24),
                        contentDescription = null,
                    )
                },
                onClick = {
                    expanded = false
                    onPasteFromClipboard()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_scan_qr_code)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_qr_code_scanner_24),
                        contentDescription = null,
                    )
                },
                onClick = {
                    expanded = false
                    onScanQrCode()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.home_add_manually)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_24),
                        contentDescription = null,
                    )
                },
                onClick = {
                    expanded = false
                    onAddManually()
                },
            )
        }
    }
}

@Composable
private fun SubscriptionCard(
    subscription: SubscriptionEntity,
    servers: List<ServerListItem>,
    selectedServerId: Long,
    defaultPingMethod: PingMethod,
    canApplyRouting: Boolean,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onRefresh: () -> Unit,
    onTestAll: () -> Unit,
    onDefaultPingMethodSelected: (PingMethod) -> Unit,
    onApplyRouting: () -> Unit,
    onDescriptionHiddenChange: (Boolean) -> Unit,
    onServerSelected: (Long) -> Unit,
    onTestLatency: (ServerEntity) -> Unit,
) {
    val resources = LocalResources.current
    val locale = resources.configuration.locales[0]
    val metadata = remember(
        subscription.announce,
        subscription.subscriptionUploadBytes,
        subscription.subscriptionDownloadBytes,
        subscription.subscriptionTotalBytes,
        subscription.subscriptionExpireAt,
        subscription.autoUpdateIntervalHours,
        locale,
    ) {
        buildSubscriptionMetadataUiState(subscription, resources)
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SubscriptionHeader(
                subscription = subscription,
                metadata = metadata,
                defaultPingMethod = defaultPingMethod,
                onRefresh = onRefresh,
                onTestAll = onTestAll,
                onDefaultPingMethodSelected = onDefaultPingMethodSelected,
                onDelete = onDelete,
                onEdit = onEdit,
                canApplyRouting = canApplyRouting,
                onApplyRouting = onApplyRouting,
                onDescriptionHiddenChange = onDescriptionHiddenChange,
            )
            if (metadata.hasVisibleSubscriptionSection()) {
                Spacer(modifier = Modifier.height(SubscriptionBlockGap))
            }
            SubscriptionMetadataSection(
                subscription = subscription,
                metadata = metadata,
            )

            if (servers.isEmpty()) {
                Text(
                    stringResource(R.string.home_no_servers_in_subscription),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LookaheadScope {
                    Column {
                        servers.forEachIndexed { index, server ->
                            key(server.entity.id) {
                                Column(modifier = Modifier.animateBounds(this@LookaheadScope)) {
                                    if (index > 0) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                                        )
                                    }
                                    ServerRow(
                                        server = server,
                                        isSelected = server.entity.id == selectedServerId,
                                        onClick = { onServerSelected(server.entity.id) },
                                        onTestLatency = { onTestLatency(server.entity) },
                                        contentPadding = ServerRowDefaults.contentPadding,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionMetadataSection(
    subscription: SubscriptionEntity,
    metadata: SubscriptionMetadataUiState,
) {
    val limitedTraffic = metadata.traffic?.takeUnless { it.quotaText == null }

    val hasVisibleMetadata = metadata.announcement.isNotEmpty() ||
        limitedTraffic != null
    if (!hasVisibleMetadata) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = tween(durationMillis = 180))
            .padding(start = 16.dp, top = 0.dp, end = 16.dp, bottom = SubscriptionMetadataGap),
        verticalArrangement = Arrangement.spacedBy(SubscriptionMetadataGap),
    ) {
        AnimatedVisibility(
            visible = metadata.announcement.isNotEmpty() && !subscription.descriptionHidden,
            enter = fadeIn(animationSpec = tween(durationMillis = 120)),
            exit = fadeOut(animationSpec = tween(durationMillis = 90)),
        ) {
            SubscriptionDescriptionText(description = metadata.announcement)
        }

        if (limitedTraffic != null) {
            SubscriptionTrafficUsage(
                state = limitedTraffic,
                expiry = metadata.expiry,
            )
        }
    }
}

private fun SubscriptionMetadataUiState.hasVisibleSubscriptionSection(): Boolean {
    val limitedTraffic = traffic?.takeUnless { it.quotaText == null }
    return announcement.isNotEmpty() ||
        limitedTraffic != null
}

@Composable
private fun SubscriptionTrafficUsage(
    state: SubscriptionTrafficUiState,
    expiry: SubscriptionExpiryUiState?,
) {
    val expiredStatusText = stringResource(R.string.home_subscription_expired_inline)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = state.summary,
            modifier = Modifier.fillMaxWidth(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
        )
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth(),
        )
        if (expiry != null) {
            Text(
                text = remember(expiry.standaloneText, expiredStatusText) {
                    expiry.standaloneText.withMetadataEmphasis(expiredStatusText)
                },
                modifier = Modifier.align(Alignment.End),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SubscriptionHeader(
    subscription: SubscriptionEntity,
    metadata: SubscriptionMetadataUiState,
    defaultPingMethod: PingMethod,
    onRefresh: () -> Unit,
    onTestAll: () -> Unit,
    onDefaultPingMethodSelected: (PingMethod) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    canApplyRouting: Boolean,
    onApplyRouting: () -> Unit,
    onDescriptionHiddenChange: (Boolean) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    var showPingMethodDialog by remember { mutableStateOf(false) }
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    val supportUrl = subscription.supportUrl?.trim().orEmpty()
    val hasDescription = subscription.announce?.trim()?.isNotEmpty() == true
    val headerDetailText = metadata.headerDetailText(resources)
    val expiredStatusText = stringResource(R.string.home_subscription_expired_inline)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = subscription.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (!headerDetailText.isNullOrBlank()) {
                Text(
                    text = remember(headerDetailText, expiredStatusText) {
                        headerDetailText.withMetadataEmphasis(expiredStatusText)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onRefresh) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = stringResource(
                    R.string.home_subscription_refresh_content_description,
                    subscription.name,
                ),
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .combinedClickable(
                    role = Role.Button,
                    onClick = onTestAll,
                    onLongClick = { showPingMethodDialog = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Speed,
                contentDescription = stringResource(
                    R.string.home_subscription_test_content_description,
                    subscription.name,
                    defaultPingMethod.value,
                ),
            )
        }
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.home_subscription_menu_content_description),
                )
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_action_edit)) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.edit_24px), contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        onEdit()
                    },
                )
                if (supportUrl.isNotEmpty()) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_action_support)) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.support_24px), contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            uriHandler.openUri(supportUrl)
                        },
                    )
                }
                if (hasDescription) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (subscription.descriptionHidden) {
                                        R.string.home_subscription_show_description
                                    } else {
                                        R.string.home_subscription_hide_description
                                    },
                                ),
                            )
                        },
                        leadingIcon = {
                            Icon(
                                painterResource(
                                    if (subscription.descriptionHidden) {
                                        R.drawable.visibility_24px
                                    } else {
                                        R.drawable.visibility_off_24px
                                    },
                                ),
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDescriptionHiddenChange(!subscription.descriptionHidden)
                        },
                    )
                }
                if (canApplyRouting) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.home_subscription_apply_routing)) },
                        leadingIcon = {
                            Icon(painterResource(R.drawable.cloud_download_24px), contentDescription = null)
                        },
                        onClick = {
                            showMenu = false
                            onApplyRouting()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.home_action_remove)) },
                    leadingIcon = {
                        Icon(painterResource(R.drawable.delete_forever_24px), contentDescription = null)
                    },
                    onClick = {
                        showMenu = false
                        onDelete()
                    },
                )
            }
        }
    }

    if (showPingMethodDialog) {
        PingMethodDialog(
            selectedMethod = defaultPingMethod,
            onDismiss = { showPingMethodDialog = false },
            onSelected = { method ->
                onDefaultPingMethodSelected(method)
                showPingMethodDialog = false
            },
        )
    }
}

@Composable
private fun PingMethodDialog(
    selectedMethod: PingMethod,
    onDismiss: () -> Unit,
    onSelected: (PingMethod) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_action_close))
            }
        },
        title = { Text(stringResource(R.string.home_choose_ping_method_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PingMethod.entries.forEach { method ->
                    SelectableOptionRow(
                        title = stringResource(method.labelResource),
                        description = stringResource(method.descriptionResource),
                        selected = method == selectedMethod,
                        onSelected = { onSelected(method) },
                    )
                }
            }
        },
    )
}

@Composable
private fun SubscriptionDescriptionText(description: String) {
    val linkColor = MaterialTheme.colorScheme.primary
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val uriHandler = LocalUriHandler.current
    var pendingUrl by remember(description) { mutableStateOf<String?>(null) }
    val annotatedDescription = remember(description, linkColor) {
        description.withUrlLinks(linkColor) { url ->
            pendingUrl = url
        }
    }

    SelectionContainer {
        Text(
            text = annotatedDescription,
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
        )
    }

    pendingUrl?.let { url ->
        AlertDialog(
            onDismissRequest = { pendingUrl = null },
            title = { Text(stringResource(R.string.home_open_link_title)) },
            text = {
                SelectionContainer {
                    Text(
                        text = url,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingUrl = null
                        uriHandler.openUri(url)
                    },
                ) {
                    Text(stringResource(R.string.home_action_open))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingUrl = null }) {
                    Text(stringResource(R.string.home_action_cancel))
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ServerRow(
    server: ServerListItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onTestLatency: () -> Unit,
    contentPadding: PaddingValues = ServerRowDefaults.contentPadding,
) {
    val latency = server.latency
    val latencyColor = if (latency?.let(::latencyShowsError) == true) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onTestLatency),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactSelectionDot(isSelected = isSelected)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = server.entity.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = server.endpointSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (latency != null) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    LatencyBadgeContent(
                        latency = latency,
                        color = latencyColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun LatencyBadgeContent(
    latency: ServerLatencyState,
    color: Color,
) {
    val tcpingLatencyMs = latency.tcpingLatencyMs
    val httpingLatencyMs = latency.httpingLatencyMs
    if (latency.latencyMs == LATENCY_TESTING) {
        Text(
            text = stringResource(R.string.home_latency_testing),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = color,
            style = MaterialTheme.typography.labelMedium,
        )
    } else if (tcpingLatencyMs != null && httpingLatencyMs != null) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LatencyValue(tcpingLatencyMs, PingMethod.Tcping, Icons.Outlined.NetworkPing, color)
            Text(
                text = ",",
                color = color,
                style = MaterialTheme.typography.labelMedium.copy(letterSpacing = (-0.25).sp),
            )
            LatencyValue(httpingLatencyMs, PingMethod.Httping, Icons.Outlined.Dns, color)
        }
    } else {
        Text(
            text = if (latency.latencyMs < 0) {
                stringResource(R.string.home_latency_not_available)
            } else {
                stringResource(R.string.home_latency_milliseconds, latency.latencyMs)
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            color = color,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun LatencyValue(
    latencyMs: Int,
    method: PingMethod?,
    icon: ImageVector,
    color: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (latencyMs < 0) {
                stringResource(R.string.home_latency_not_available)
            } else {
                stringResource(R.string.home_latency_milliseconds_compact, latencyMs)
            },
            color = color,
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = (-0.25).sp),
        )
        Icon(
            imageVector = icon,
            contentDescription = method?.let { stringResource(it.labelResource) },
            modifier = Modifier.size(13.dp),
            tint = color,
        )
    }
}

internal fun latencyShowsError(latency: ServerLatencyState): Boolean {
    val httpingLatencyMs = latency.httpingLatencyMs
    if (latency.latencyMs == LATENCY_TESTING) return false
    return if (latency.tcpingLatencyMs != null && httpingLatencyMs != null) {
        httpingLatencyMs < 0
    } else {
        latency.latencyMs < 0
    }
}

private object ServerRowDefaults {
    val contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
}

@Composable
private fun CompactSelectionDot(isSelected: Boolean) {
    Surface(
        modifier = Modifier.size(18.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 2.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            },
        ),
    ) {
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private fun String.withMetadataEmphasis(expiredStatusText: String) = buildAnnotatedString {
    metadataTextSegments(this@withMetadataEmphasis, expiredStatusText).forEach { segment ->
        if (segment.emphasized) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                append(segment.value)
            }
        } else {
            append(segment.value)
        }
    }
}

private fun String.withUrlLinks(
    linkColor: androidx.compose.ui.graphics.Color,
    onUrlClick: (String) -> Unit,
): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = linkColor,
            textDecoration = TextDecoration.Underline,
        ),
    )

    subscriptionUrlRegex.findAll(this@withUrlLinks).forEach { match ->
        val start = match.range.first
        val end = this@withUrlLinks.trimmedUrlEnd(match)
        if (end <= start) return@forEach

        if (cursor < start) {
            append(this@withUrlLinks.substring(cursor, start))
        }

        val url = this@withUrlLinks.substring(start, end)
        val linkStart = length
        append(url)
        addLink(
            LinkAnnotation.Clickable(
                tag = url.normalizedSubscriptionUrl(),
                styles = linkStyles,
                linkInteractionListener = LinkInteractionListener { link ->
                    (link as? LinkAnnotation.Clickable)?.tag?.let(onUrlClick)
                },
            ),
            start = linkStart,
            end = length,
        )
        cursor = end
    }

    if (cursor < this@withUrlLinks.length) {
        append(this@withUrlLinks.substring(cursor))
    }
}

private fun String.trimmedUrlEnd(match: MatchResult): Int {
    var end = match.range.last + 1
    while (end > match.range.first && this[end - 1] in trailingUrlPunctuation) {
        end--
    }
    return end
}

private fun String.normalizedSubscriptionUrl(): String = if (startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)) {
    this
} else {
    "https://$this"
}

private val subscriptionUrlRegex = Regex(
    pattern = """(?i)(?<![@\w])(?:https?://[^\s<>"']+|(?:www\.|(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,})(?:/[^\s<>"']*)?)""",
)
private val trailingUrlPunctuation = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')
private val SubscriptionBlockGap = 6.dp
private val SubscriptionMetadataGap = 10.dp
private const val QR_SCANNER_TRANSITION_MS = 180
private const val CORE_UPTIME_REFRESH_INTERVAL_MS = 1_000L
private const val CAMERA_PERMISSION_PREFS = "camera_permission"
private const val CAMERA_PERMISSION_REQUESTED = "requested"

private fun Context.clipboardText(): String? {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return null
    val clip = clipboard.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    return clip.getItemAt(0)
        ?.coerceToText(this)
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

private data class AutoUpdateIntervalOption(
    val intervalHours: Int,
)

private val autoUpdateIntervalOptions = listOf(
    AutoUpdateIntervalOption(1),
    AutoUpdateIntervalOption(3),
    AutoUpdateIntervalOption(6),
    AutoUpdateIntervalOption(24),
    AutoUpdateIntervalOption(72),
    AutoUpdateIntervalOption(0),
)

@Composable
private fun autoUpdateIntervalLabel(intervalHours: Int): String = when (intervalHours) {
    0 -> stringResource(R.string.home_duration_manual)
    24, 72 -> {
        val days = intervalHours / 24
        pluralStringResource(R.plurals.home_duration_days, days, days)
    }
    else -> pluralStringResource(R.plurals.home_duration_hours, intervalHours, intervalHours)
}

@Composable
private fun EditSubscriptionDialog(
    subscription: SubscriptionEntity,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean, Int, SubscriptionUserAgentMode, String, String) -> Unit,
) {
    var name by rememberSaveable(subscription.id) { mutableStateOf(subscription.name) }
    var url by rememberSaveable(subscription.id) { mutableStateOf(subscription.url) }
    var preferJson by rememberSaveable(subscription.id) { mutableStateOf(subscription.preferJson ?: true) }
    var autoUpdateIntervalHours by rememberSaveable(subscription.id) {
        mutableStateOf(subscription.autoUpdateIntervalHours)
    }
    var userAgentMode by rememberSaveable(subscription.id) {
        mutableStateOf(SubscriptionUserAgentMode.fromValue(subscription.userAgentMode))
    }
    var customUserAgent by rememberSaveable(subscription.id) {
        mutableStateOf(subscription.customUserAgent.orEmpty())
    }
    var customHeaders by rememberSaveable(subscription.id) {
        mutableStateOf(subscription.customHeaders.orEmpty())
    }
    val hasChanges = name.trim() != subscription.name ||
        url.trim() != subscription.url ||
        preferJson != (subscription.preferJson ?: true) ||
        autoUpdateIntervalHours != subscription.autoUpdateIntervalHours ||
        userAgentMode != SubscriptionUserAgentMode.fromValue(subscription.userAgentMode) ||
        customUserAgent.trim().ifBlank { null } != subscription.customUserAgent ||
        customHeaders.trim().ifBlank { null } != subscription.customHeaders
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_edit_subscription_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.home_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(R.string.home_name_from_provider_hint)) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.home_field_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                SubscriptionFetchTypeDropdown(
                    preferJson = preferJson,
                    onPreferJsonChange = { preferJson = it },
                )
                Spacer(modifier = Modifier.height(16.dp))
                key(subscription.id) {
                    ReadOnlyDropdownField(
                        label = stringResource(R.string.home_auto_update_label),
                        selectedText = autoUpdateIntervalLabel(autoUpdateIntervalHours),
                        options = autoUpdateIntervalOptions.map { option ->
                            DropdownOption(
                                value = option.intervalHours,
                                label = autoUpdateIntervalLabel(option.intervalHours),
                            )
                        },
                        onSelected = { autoUpdateIntervalHours = it },
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                SubscriptionUserAgentSection(
                    selectedMode = userAgentMode,
                    customUserAgent = customUserAgent,
                    customHeaders = customHeaders,
                    onModeChange = { userAgentMode = it },
                    onCustomUserAgentChange = { customUserAgent = it },
                    onCustomHeadersChange = { customHeaders = it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(name.trim(), url.trim(), preferJson, autoUpdateIntervalHours, userAgentMode, customUserAgent, customHeaders)
                },
                enabled = url.isNotBlank() && hasChanges,
            ) {
                Text(stringResource(R.string.home_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_action_cancel))
            }
        },
    )
}

@Composable
private fun ApplySubscriptionRoutingDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_apply_subscription_routing_title)) },
        text = { Text(stringResource(R.string.home_apply_subscription_routing_message)) },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.home_apply_subscription_routing_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_apply_subscription_routing_dismiss))
            }
        },
    )
}

@Composable
private fun RemoveSubscriptionDialog(
    serverCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_remove_subscription_title)) },
        text = {
            Text(
                pluralStringResource(
                    R.plurals.home_remove_subscription_message,
                    serverCount,
                    serverCount,
                ),
            )
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text(stringResource(R.string.home_action_remove))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_action_cancel))
            }
        },
    )
}

@Composable
private fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, Boolean, SubscriptionUserAgentMode, String, String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var preferJson by rememberSaveable { mutableStateOf(true) }
    var userAgentMode by rememberSaveable { mutableStateOf(SubscriptionUserAgentMode.default) }
    var customUserAgent by rememberSaveable { mutableStateOf("") }
    var customHeaders by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_add_manually)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.home_field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(R.string.home_name_from_provider_hint)) },
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.home_field_url)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
                SubscriptionFetchTypeDropdown(
                    preferJson = preferJson,
                    onPreferJsonChange = { preferJson = it },
                )
                Spacer(modifier = Modifier.height(16.dp))
                SubscriptionUserAgentSection(
                    selectedMode = userAgentMode,
                    customUserAgent = customUserAgent,
                    customHeaders = customHeaders,
                    onModeChange = { userAgentMode = it },
                    onCustomUserAgentChange = { customUserAgent = it },
                    onCustomHeadersChange = { customHeaders = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name.trim(), url.trim(), preferJson, userAgentMode, customUserAgent, customHeaders) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.home_action_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.home_action_cancel))
            }
        },
    )
}

@Composable
private fun SubscriptionFetchTypeDropdown(
    preferJson: Boolean,
    onPreferJsonChange: (Boolean) -> Unit,
) {
    val jsonFirst = stringResource(R.string.home_fetch_type_json_first)
    val compatibility = stringResource(R.string.home_fetch_type_compatibility)

    ReadOnlyDropdownField(
        label = stringResource(R.string.home_fetch_type_label),
        selectedText = if (preferJson) jsonFirst else compatibility,
        supportingText = if (preferJson) {
            stringResource(R.string.home_fetch_type_json_first_description)
        } else {
            stringResource(R.string.home_fetch_type_compatibility_description)
        },
        options = listOf(
            DropdownOption(value = true, label = jsonFirst),
            DropdownOption(value = false, label = compatibility),
        ),
        onSelected = onPreferJsonChange,
    )
}

@Composable
private fun SubscriptionUserAgentSection(
    selectedMode: SubscriptionUserAgentMode,
    customUserAgent: String,
    customHeaders: String,
    onModeChange: (SubscriptionUserAgentMode) -> Unit,
    onCustomUserAgentChange: (String) -> Unit,
    onCustomHeadersChange: (String) -> Unit,
) {
    ReadOnlyDropdownField(
        label = stringResource(R.string.home_field_user_agent),
        selectedText = stringResource(selectedMode.labelResource),
        options = SubscriptionUserAgentMode.entries.map { mode ->
            DropdownOption(
                value = mode,
                label = stringResource(mode.labelResource),
                description = stringResource(mode.descriptionResource),
            )
        },
        onSelected = onModeChange,
    )
    if (selectedMode == SubscriptionUserAgentMode.CUSTOM) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = customUserAgent,
            onValueChange = onCustomUserAgentChange,
            label = { Text(stringResource(R.string.home_field_user_agent)) },
            placeholder = {
                Text(
                    stringResource(
                        R.string.home_user_agent_example,
                        stringResource(R.string.home_user_agent_example_value),
                    ),
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = customHeaders,
            onValueChange = onCustomHeadersChange,
            label = { Text(stringResource(R.string.home_field_headers)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
            supportingText = { Text(stringResource(R.string.home_headers_example)) },
        )
    }
}
